# NonceConsumer 三存储实现设计

## 背景与目标

当前代码已声明版本 `3.0.0`，但仓库尚未创建 `3.0.0` 标签。3.x 使用签名/加密
JWT 携带 challenge 状态，只需要持久化已经消费的 JWT 签名以阻止重放；它不恢复 2.x 的
`CapStore`，也不保存明文业务 token。

本次在首次发布的 3.0.0 中提供三种官方 `NonceConsumer` 实现：

- Caffeine：零配置默认实现，只保证单 JVM 内原子消费；
- JDBC：使用共享关系数据库保证多实例原子消费；
- Redis：使用 Lettuce 和 Redis 原子命令保证多实例原子消费。

三种实现只改变防重放存储能力，不改变 capjs-core 0.1.1 协议 wire、失败码、challenge
JWT、业务 token/tokenKey 分离模型或 `CapBuilder.nonceConsumer(...)` 扩展点。

## Maven 模块与依赖边界

仓库改为 Maven 多模块项目。根 `pom.xml` 是 artifactId 为 `cap-server-parent` 的聚合及父
POM，统一管理 Java 17、依赖版本、Spotless、测试和发布配置。

### cap-server

- 坐标保持 `com.github.luckygc:cap-server:3.0.0`，避免改变用户依赖坐标。
- 承载现有核心源码、测试和 fixture 测试。
- 包含 `Cap`、`CapBuilder`、`NonceConsumer` 及默认 Caffeine 实现。
- 不依赖数据库驱动、连接池或 Lettuce。
- `Cap.builder(secret).build()` 仍自动选择 Caffeine，不要求显式配置。

### cap-server-jdbc

- 坐标为 `com.github.luckygc:cap-server-jdbc:3.0.0`。
- 依赖 `cap-server`，公开 `github.luckygc.cap.replay.jdbc.JdbcNonceConsumer` 和
  `JdbcDialect`。
- 仅使用 JDK 原生 JDBC 与 `javax.sql.DataSource`，不引入 Jdbi、Spring JDBC、ORM、
  连接池或数据库驱动。
- 首版明确支持 PostgreSQL、MySQL 和 MariaDB。

### cap-server-redis

- 坐标为 `com.github.luckygc:cap-server-redis:3.0.0`。
- 依赖 `cap-server` 和 Lettuce，公开
  `github.luckygc.cap.replay.redis.LettuceNonceConsumer`。
- 接收调用方创建的同步 Lettuce commands；不创建、拥有或关闭连接，不覆盖调用方的
  连接池、超时、重连或线程模型配置。

现有源码及测试移入 `cap-server/` 模块。仓库级 `tools/`、fixture 和文档保留在顶层，所有
引用源码或构建输出的路径随模块布局同步调整。

## 公开 API

Caffeine 实现从 internal 包迁移并正式公开为：

```java
github.luckygc.cap.replay.CaffeineNonceConsumer
```

它继续提供无参数构造器和最大容量构造器。`CapBuilder` 的默认行为和
`nonceCacheMaximumSize(...)` 保持不变。

JDBC 的典型用法为：

```java
Cap cap = Cap.builder(secret)
        .nonceConsumer(new JdbcNonceConsumer(dataSource, JdbcDialect.POSTGRESQL))
        .build();
```

Redis 的典型用法为：

```java
Cap cap = Cap.builder(secret)
        .nonceConsumer(new LettuceNonceConsumer(syncCommands))
        .build();
```

JDBC 默认使用表 `cap_consumed_nonces`。自定义限定表名仅允许由点分隔的安全 SQL 标识符
片段，拒绝空值、引号、注释、分号、空白和其他可注入 SQL 的内容。Redis 默认 key 前缀为
`cap:nonce:`，并允许构造时覆盖非空前缀。

## Caffeine 语义

Caffeine 仍是唯一的零配置默认实现：

- 默认硬容量为 100,000；
- 同一 JVM 内并发消费同一签名时恰好一次返回 `true`；
- 签名在 TTL 内不会因为容量压力提前淘汰；
- 容量耗尽时抛出异常并 fail closed，最终兑换结果为 `nonce_store_error`；
- 不向数据库或 Redis 自动降级，也不与外部 consumer 双写。

文档必须醒目标明 Caffeine 只适用于单 JVM。多实例部署必须配置 JDBC、Redis 或其他共享且
原子的 `NonceConsumer`。

## JDBC 原子消费

数据库表至少包含：

- `signature_hex`：challenge JWT 签名的十六进制字符串，主键或唯一键；
- `expires_at`：该记录可以安全清理的时间。

每次 `consume(signatureHex, ttl)` 执行一个独立短事务：

1. 从 `DataSource` 获取一个由本次调用独占的连接；
2. 保存必要的连接状态，关闭自动提交；
3. 执行一次参数化 `INSERT`；
4. 插入成功时提交并返回 `true`；
5. PostgreSQL SQLState `23505` 或 MySQL/MariaDB duplicate-key 错误时回滚并返回
   `false`；
6. 其他 SQL 错误、提交失败，或无法确认回滚成功时抛出异常；
7. 恢复连接状态并关闭连接，让 `DataSource` 或连接池回收。

consumer 不参与调用方业务事务。消费记录必须在 redeem 返回成功前独立提交；把它绑定到
可能回滚的业务事务会重新打开重放窗口。

调用方提供的 `DataSource` 必须为每次 `getConnection()` 返回可由 consumer 独立提交和
回滚的连接，不得返回绑定到外层业务事务的连接。consumer 不接受事务感知代理提供的共享
连接；这一前置条件在构造器 Javadoc 和部署文档中明确说明。

`JdbcDialect` 必须由调用方显式选择，禁止根据驱动类名或产品名静默猜测。异常分类只把已
确认的唯一键冲突视为 replay，其他完整性约束错误仍 fail closed。关闭、恢复状态或回滚时
发生的异常按 JDBC suppression 规则保留，不能掩盖主异常，也不能把状态不确定误报为已
消费。

consumer 只插入，不在 redeem 热路径删除旧行。宿主应用负责 schema migration 和定时
清理；文档提供 PostgreSQL、MySQL 和 MariaDB 的建表、索引与清理 SQL。`expires_at` 根据
调用侧验证时钟与传入的剩余 TTL 计算，使记录至少保留到 challenge 剩余有效期结束。清理
任务必须避免因数据库与应用时钟偏差提前删除仍有效的记录。

## Redis/Lettuce 原子消费

Redis key 由命名空间前缀和 `signatureHex` 拼接而成，value 使用固定非敏感常量。每次消费
只执行一条等价于以下语义的 Redis 命令：

```text
SET key 1 NX PX ttlMillis
```

- 返回 `OK` 表示首次写入，返回 `true`；
- NX 条件未满足时返回空值，表示已经消费，返回 `false`；
- TTL 向上取整到毫秒且最小为 1ms，避免仍有效的亚毫秒期限转换成零；
- Redis 超时、连接故障、命令错误或未知响应全部抛出并 fail closed；
- 不在 Redis 故障时退回本机 Caffeine，否则不同节点可能各接受一次重放。

`LettuceNonceConsumer` 不关闭调用方传入的 commands 或底层连接。调用方负责配置有界连接
与命令超时；同步命令会在 redeem 调用线程执行。

## 安全与可观测性

所有实现继续遵守现有敏感信息约束：不得记录或暴露 challenge token、签名、完整 Redis
key、SQL 参数、secret、solution、业务 token、tokenKey 或内部摘要。公开异常和兑换失败仍
使用现有固定失败类别；数据库或 Redis 的内部诊断只能通过异常链交给宿主受控处理。

三种实现都必须线程安全。外部存储的阻塞、容量、连接池和超时属于宿主配置责任，README
给出生产部署要求，但库不引入异步 API 或后台线程。

## 测试策略

默认 `mvn test` 和 `mvn verify` 只要求 Java 17，并运行三个模块的真实单元测试：

- Caffeine：并发恰好一次成功、TTL、硬容量、容量耗尽和参数边界；
- JDBC：提交、唯一键回滚、普通 SQL 错误、提交失败、回滚失败、连接状态恢复与关闭、表名
  校验，以及 PostgreSQL/MySQL/MariaDB 错误分类；
- Redis：NX 参数、毫秒 TTL 向上取整、默认和自定义前缀、重复响应、异常传播，以及不关闭
  外部连接。

默认测试使用可控 JDBC/Lettuce 测试替身，不探测 Docker，不允许通过静默 skip 获得成功。

显式 `store-integration` profile 使用 Testcontainers 对真实 PostgreSQL、MySQL、MariaDB 和
Redis 运行集成测试：

- 多连接或多线程同时消费同一签名，恰好一次成功；
- 各数据库的唯一键错误分类正确，其他错误不能误判为 replay；
- Redis 实际执行原子 `SET NX PX` 并按 TTL 过期。

默认生命周期不执行也不 skip 这些集成测试。显式 profile 缺少可用 Docker 时必须硬失败并
给出固定准备说明，诊断不得泄露本机路径、连接凭据或敏感协议值。

## 文档与兼容性

同步更新以下内容：

- README：三个制品的依赖、构造和集群部署示例；
- README 或独立 migration 文档：PostgreSQL、MySQL、MariaDB 建表及安全清理 SQL；
- `AGENTS.md`：新模块布局、命令、默认构建和显式集成测试约束；
- `docs/protocol-compatibility.md`：防重放实现选择与不变的 wire 语义；
- widget E2E 与 fixture 工具中受模块移动影响的路径。

3.0.0 仍是 breaking release，不恢复 `CapManager`、`CapStore`、`MemoryCapStore` 或
`validateCapToken` 兼容层。

## 验证与标签

完成实现后至少执行：

```bash
mise exec maven -- mvn spotless:check
mise exec maven -- mvn test
mise exec maven -- mvn verify
git diff --check
```

Maven 输出必须包含实际测试数量、零失败，且不能显示 `Tests are skipped.`。环境提供 Docker
时还执行：

```bash
mise exec maven -- mvn -Pstore-integration verify
```

若 Docker 不可用，交付说明必须列出未执行命令、原因和真实存储尚未在本机复核的风险。

所有实现、测试和文档完成并形成最终提交后，创建 annotated tag `3.0.0`，标签必须指向该
最终提交。标签创建前不得声称 3.0.0 已完成。Maven 制品部署不在本次范围内；远端 tag 推送
作为单独的显式发布动作处理。

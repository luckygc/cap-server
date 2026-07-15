# Task 6 报告

## 结果

- README 保留核心 `com.github.luckygc:cap-server:3.0.0` 快速依赖，增加独立的
  `cap-server-jdbc` / `cap-server-redis` 依赖与公开构造器示例。
- 新增 `docs/replay-storage.md`，收录 PostgreSQL、MySQL / MariaDB 最终 DDL、一分钟
  安全余量清理 SQL、Redis 原子命令、时钟偏差和会话时区边界。
- 兼容性文档明确三种 consumer 不改变 signature-hex key、TTL、wire 或失败码语义。
- AGENTS 增加模块路径、聚焦命令、默认 Java-only 生命周期、显式 Docker profile 和依赖边界。

## RED / GREEN

1. 先在 `BuildLifecycleContractTest` 增加防重放存储文档契约。首次命令被 Spotless
   在测试前拦截，只修正测试格式后重跑，取得有效 RED：
   `mise exec maven -- mvn -pl cap-server -Dtest=BuildLifecycleContractTest test` 因
   `docs/replay-storage.md` 不存在失败，8 个测试中 1 error。
2. 补文档后运行
   `mise exec maven -- mvn -pl cap-server -Dtest=BuildLifecycleContractTest,DocumentationWireContractTest test`：
   10 tests，0 failures，0 errors，0 skipped。

## 验证

- `mise exec maven -- mvn spotless:apply`：四模块 reactor 成功，无 Java 文件需修改。
- `mise exec maven -- mvn spotless:check`：四模块 reactor 成功。
- `mise exec maven -- mvn test`：核心 183、JDBC 29、Redis 16，合计 228 tests；
  0 failures，0 errors，0 skipped，输出未出现 `Tests are skipped.`。
- `mise exec maven -- mvn verify`：同样执行 228 tests，全部通过并完成三个 jar 打包。
- `git diff --check`：通过。
- 未运行 `-Pstore-integration`；本任务只改文档契约与文档，最终 DDL 已由前置真实 IT 锁定，
  且默认生命周期按要求不探测 Docker。

## 提交

- `docs: 说明 nonce 存储与集群部署`（本报告所在提交）。

## 自审

- 改动限于简报列出的 README、存储/兼容性文档、AGENTS 与文档契约；未改公开
  API、实现、fixture、协议 wire 或失败码。
- README 构造器表达式与当前公开签名一致，并由文档字符串契约锁定；默认 reactor
  同时编译了在模块单测中使用相同构造器的源码。
- PostgreSQL 与 MySQL / MariaDB DDL 逐项与真实 IT 最终结构交叉核对，契约锁定了
  列类型、主键、索引表达式和两种清理条件。
- 已明确 Caffeine 仅单 JVM、JDBC 独立 `autoCommit=true` 非事务绑定连接、Lettuce
  commands / 连接 / 超时的调用方所有权，以及外部失败无 Caffeine fallback。
- 保留 README、AGENTS 和兼容性文档中现有 widget E2E、fixture 与协议说明。
- 独立文档审查对照简报和基线 `68e4520` 复核全部改动，Critical / Important / Minor
  均无发现；审查者重跑文档契约为 10 tests 全通过，`git diff --check 68e4520` 通过。

## Concerns

- Redis 模块测试依赖未配置 SLF4J provider，Maven 依旧输出已有的 NOP logger 警告；不影响测试或打包，
  且本任务未改依赖策略。

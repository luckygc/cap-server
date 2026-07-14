# Capjs Core 0.1.1 Java 迁移设计

## 目标

将项目升级为兼容 `capjs-core 0.1.1` 与当前 `@cap.js/widget` 的 Java 17 服务端库，并发布为不兼容旧 API 的 `3.0.0`。实现必须完整支持 Format 1、Format 2、SHA-256 PoW、RSW、instrumentation、scope、extra、防重放和自定义业务 token，同时用简单的门面 API 隐藏协议复杂度。

上游协议基准：

- <https://github.com/tiagozip/cap/tree/main/core>
- <https://github.com/tiagozip/cap/blob/main/core/src/index.js>
- <https://github.com/tiagozip/cap/blob/main/core/src/instrumentation.js>
- <https://github.com/tiagozip/cap/blob/main/core/src/rsw.js>

## 不在范围内

- 不保留 `CapStore`、`MemoryCapStore`、旧配置类或 `validateCapToken` 兼容层。
- 不集成 Spring MVC、Jakarta REST 或其他 Web 框架。
- 不绑定日志实现、Redis 客户端或数据库驱动。
- Instrumentation 追求相同协议行为，不要求生成与 Node 版逐字节相同的混淆脚本。

## 公共 API

### 默认模式

默认模式与 `capjs-core` 的协议默认值一致，生成 Format 1 SHA-256 PoW challenge，并自动使用本机 Caffeine 防重放；不要求调用方提供存储或 RSW 密钥：

```java
Cap cap = Cap.builder(secret).build();

ChallengeResponse challenge = cap.createChallenge();
RedeemResult result = cap.redeem(request);
```

### 严格模式

严格模式一行启用 Format 2、RSW、instrumentation 和本机防重放：

```java
Cap cap = Cap.builder(secret)
        .profile(CapProfile.STRICT)
        .build();
```

未显式提供 RSW 密钥对时，Builder 在构建期间生成一次 2048 位密钥对并缓存 minter。调用方可传入持久化密钥对，避免每次进程启动的密钥生成耗时。

### 高级配置

Builder 提供以下可选能力：

- Profile：`DEFAULT` 或 `STRICT`。
- Format 1 参数：challenge count、size、difficulty 和 TTL。
- Format 2 协议组合：`SHA256_POW`、`RSW`、`INSTRUMENTATION`。
- RSW：密钥对和连续平方次数 `t`。
- Instrumentation：自动化浏览器阻断、混淆等级和自定义 transformer。
- JWT：scope、extra、challenge TTL 和 redeem token TTL。
- 防重放：本机 Caffeine 配置或外部 `NonceConsumer`。
- 自定义业务 token：`TokenSigner`。
- 可观测性：事件监听器。

高级配置不得成为默认用法的前置条件。

两个 Profile 默认都启用本机 Caffeine 防重放。确实需要完全无状态行为的调用方必须显式关闭防重放；集群部署应使用外部 `NonceConsumer` 替换本机缓存。

## 返回模型

`ChallengeResponse` 使用可序列化 record，字段与上游 wire format 一致：

- Format 1：`challenge`、`token`、`expires`，可选 `instrumentation`。
- Format 2：`format = 2`、`challenges`、`token`、`expires`。

`RedeemResult` 为 sealed interface：

- `RedeemResult.Success`：`success = true`、`token`、`tokenKey`、`expires`、`scope`、`iat`。
- `RedeemResult.Failure`：`success = false`、`reason`、`instrError`，必要时包含非敏感的 `error`。

正常验证失败通过 `Failure` 返回，不使用异常表达协议结果。配置错误和调用方违反 API 前置条件时抛出 `IllegalArgumentException`。

## 模块边界

### 门面与模型

- `Cap`：唯一主要入口，持有不可变配置并协调 challenge 与 redeem 流程。
- `CapBuilder`：验证 secret 和配置，构造可复用、线程安全的 `Cap`。
- API records/enums：只表达协议数据，不包含存储或框架逻辑。

### Token 与 JSON

- `ProtocolJsonCodec`：使用 Jackson Core Streaming API 编解码已知 JWT 字段。
- `JwtCodec`：Base64URL、HS256 签名、恒定时间签名比较和 payload 限制。
- `EncryptedMetadataCodec`：使用 AES-256-GCM 加密 Format 1 instrumentation 元数据和 Format 2 expected 数据。

Jackson 类型不得出现在公共 API 中。`extra` 对外使用 `Map<String, Object>`，只允许 JSON 的字符串、布尔值、有限数值、列表、Map 和 `null`。编码前递归验证深度、节点数、字符串长度和数值范围。

### Format 1

- 生成 `{n,c,s,d,exp,iat}` JWT payload，按需加入 `sk`、`x` 和 `ei`。
- 使用与上游一致的 FNV-1a/PRNG 派生 salt 与 target。
- 验证 solution 数量、数值类型和 SHA-256 前缀。
- Instrumentation 元数据放入 AES-GCM 加密的 `ei`。

### Format 2

- JWT payload 使用 `f = 2`、`n`、`exp`、`iat`、`ev`，按需加入 `sk` 和 `x`。
- `ev` 使用 AAD `cap:fmt2-v1` 加密 expected 数据。
- Challenge 与 solution 保持相同顺序，按协议分别验证。

### RSW

- 使用 JDK `BigInteger` 与 `SecureRandom` 生成 RSA 风格的 `p`、`q` 和 `N`。
- 构建 minter 时预计算 CRT 所需值。
- Challenge 返回 `N`、`x`、`t`，加密元数据保存 expected `y`。
- 验证时规范化十六进制并恒定规则比较结果。
- 默认密钥 2048 位，默认 `t = 75_000`，与上游一致。

### Instrumentation

- 每个 challenge 生成唯一 id、随机变量、初始值和动态运算链。
- 生成脚本执行 DOM 操作、事件派发、原生浏览器对象校验和环境真实性检查。
- `blockAutomatedBrowsers` 启用时执行自动化痕迹检查。
- 脚本使用 raw Deflate 压缩后 Base64 编码。
- 服务端验证 id、有效期、变量集合和预期结果。
- 支持 Format 1 的 `instr`/`instr_blocked`/`instr_timeout` 和 Format 2 的协议 solution 对象。
- 内置 transformer 提供压缩、字符串混淆和 AOSP 格式无关的脚本处理；高级用户可替换 transformer。

### 防重放

默认实现使用 Caffeine：

- key 为 JWT 签名的十六进制摘要。
- value 不承载业务数据。
- 使用自定义 `Expiry` 按 challenge 剩余 TTL 逐条过期。
- 使用 `cache.asMap().putIfAbsent` 原子消费 nonce。
- 设置可配置的最大容量，避免攻击者造成无限内存增长。

本机 Caffeine 只能阻止单 JVM 内重放。集群部署通过 `NonceConsumer` 注入 Redis `SET NX EX` 或数据库唯一约束等跨节点原子实现。外部实现完全替代本机缓存，不进行双写。

### Redeem Token

默认成功响应生成 `id:secret`，同时返回 `id:SHA-256(secret)` 形式的 `tokenKey`。库不保存业务 token；调用方自行保存 `tokenKey` 并在业务请求中派生查找键。`TokenSigner` 可替换默认 token 格式。

## 安全与资源限制

- secret 必须至少 16 UTF-8 字节。
- JWT 只接受 HS256，拒绝未知 header、错误段数、无效 Base64URL 和越界 payload。
- HMAC 使用恒定时间比较。
- AES-GCM 使用独立随机 IV，不复用 nonce。
- challenge count 最大 1000、size 最大 256、difficulty 最大 16。
- JSON 设置 token 总长度、字符串长度、嵌套深度、集合元素数和数字范围上限。
- RSW bits 必须为合理范围内的偶数，`t` 必须为正数且受上限约束。
- 日志和事件不得包含 secret、完整 JWT、solutions、redeem token、tokenKey 或 instrumentation 返回内容。

## 日志与可观测性

项目依赖 SLF4J API，但不绑定后端：

- `DEBUG`：challenge format、协议组合、耗时和兑换失败 reason。
- `WARN`：外部防重放异常、RSW 配置异常、instrumentation 生成失败。
- 正常请求不记录 `INFO`，避免高流量噪声。

事件监听器提供 challenge 创建、兑换成功、兑换失败及耗时事件，供 Micrometer 或其他指标系统适配。监听器异常不得影响协议主流程，只记录一次受限警告。

## JSpecify 空值契约

- 公共和内部包均通过 `package-info.java` 使用 `@NullMarked`。
- 仅 wire format 中真正可选的字段、可选回调和可选配置使用 `@Nullable`。
- 使用 Success/Failure 分型减少可空字段。
- Builder 在构建时完成必填项和跨字段约束校验。

## 依赖与构建

### 运行时依赖

- `tools.jackson.core:jackson-core:3.2.1`
- `com.github.ben-manes.caffeine:caffeine:3.2.4`
- `org.slf4j:slf4j-api:2.0.18`
- `org.jspecify:jspecify:1.0.0`

删除 `commons-lang3`、`commons-codec`。字符串、Hex、摘要、HMAC、AES、Base64、压缩和大整数全部使用 JDK 17。

### 测试与插件

- JUnit Jupiter `6.1.2`。
- AssertJ `3.27.7`，不采用 `4.0.0-M1`。
- Maven Compiler Plugin `3.15.0`，使用 `<release>17</release>`。
- Maven Surefire Plugin `3.5.6`，删除固定 `<skip>true</skip>`。
- Spotless Maven Plugin `3.8.0`。
- google-java-format `1.35.0`，style 为 `AOSP`。

Spotless 配置包含：

- `removeUnusedImports`
- `forbidWildcardImports`
- `trimTrailingWhitespace`
- `endWithNewline`
- `spotless:check` 绑定 `validate`

开发者使用：

```bash
mise exec maven -- mvn spotless:apply
mise exec maven -- mvn test
mise exec maven -- mvn verify
```

删除 Maven Checkstyle Plugin、Checkstyle 依赖和 `tools/maven/`。删除无用途的 `dev` profile、Aliyun repository 和 JitPack repository，构建只使用 Maven Central。

## 现有代码迁移

删除旧的：

- `CapManager`、`CapManagerImpl`、`CapManagerBuilder`
- `CapStore`、`MemoryCapStore`
- `ChallengeConfig`、`CapTokenConfig`
- 旧的 token 校验模型和消息资源

复用或重写：

- PRNG 算法保留协议语义，但去除 Commons Lang。
- API records 按新 wire format 重建。
- README 更新为默认模式、严格模式、Web 端点和集群防重放示例。
- 项目版本改为 `3.0.0`，README 声明兼容 `capjs-core 0.1.1` 与当前 Widget Format 1/2。

## 代码审查发现

迁移同时解决以下现有问题：

- `MemoryCapStore` 的查询后删除不是原子操作，存在并发重放窗口。
- 每次调用清理方法都可能创建后台线程，生命周期和负载不可控。
- challenge 配置没有正数和上限校验。
- `redeemChallenge(null)` 会直接触发空指针。
- 过期测试使用一个 Manager 创建 challenge，却用另一个 Manager 兑换，没有真正覆盖过期路径。
- Surefire 固定跳过测试，构建成功容易被误认为测试通过。
- `slf4j-api` 未使用，Commons 依赖均可由 JDK 17 替代。

## 测试策略

### 协议单元测试

- FNV-1a、PRNG、Hex、Base64URL、SHA-256 和常量时间比较。
- JWT 正常、签名错误、错误 header、错误结构、过期和资源限制。
- AES-GCM 正常、AAD 不匹配、篡改和错误 secret。
- Protocol JSON 正常嵌套、非法类型、非有限数值和深度/大小限制。
- RSW 密钥序列化、minter、solution 验证和非法参数。
- Instrumentation 生成、解压、元数据验证、blocked、timeout 和过期。

### 端到端测试

- Format 1 默认模式成功兑换。
- Format 1 + instrumentation。
- Format 2 的三种协议分别与组合验证。
- STRICT Profile 最小配置成功兑换。
- scope 匹配/不匹配、extra 往返、token TTL 和自定义 signer。
- Caffeine 防重放原子并发测试、自动过期和容量限制。
- 外部 `NonceConsumer` 成功、拒绝和异常。

### 跨语言兼容

从 `capjs-core 0.1.1` 固化 challenge/token/solution 测试向量：

- Java 验证 Node 生成的 Format 1/2 token。
- Node 验证 Java 生成的 token 和 challenge wire format。
- Widget 请求/响应 JSON fixture 覆盖 instrumentation 和 RSW。

固定 fixture 进入仓库，常规 Maven 测试不要求安装 Node/Bun。更新协议时再显式重新生成 fixture。

## 验收标准

- 默认 API 只需 secret 即可创建和兑换 challenge。
- STRICT Profile 一行启用 Format 2、RSW、instrumentation 和本机防重放。
- Format 1/2 与 `capjs-core 0.1.1` fixture 双向兼容。
- 公共 API 不暴露 Jackson、Caffeine 或 SLF4J 实现类型。
- 默认 Maven 生命周期真实执行 Spotless 和全部测试。
- 运行时只有四个明确依赖，且不绑定日志后端或 Web 框架。
- README 能让新用户在一个页面内完成默认、严格和集群部署接入。

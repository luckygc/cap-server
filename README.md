# cap-server

[Cap](https://github.com/tiagorangel1/cap) 服务端协议的 Java 17 实现，兼容
`capjs-core` 0.1.1。它是一个不绑定 Web 框架的库：应用只需持有一个线程安全的
`Cap` 实例，并暴露 `challenge` 与 `redeem` 端点。

## 引入依赖

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependency>
    <groupId>com.github.luckygc</groupId>
    <artifactId>cap-server</artifactId>
    <version>3.0.0</version>
</dependency>
```

运行环境需要 Java 17+。`secret` 至少为 16 个 UTF-8 字节，应从密钥管理系统或环境变量读取，
不要写入源码。

## 快速开始

默认配置使用 Format 1 SHA-256 PoW、10 分钟 challenge TTL、20 分钟业务 token TTL，
并使用本机 Caffeine 缓存阻止 challenge 重复兑换：

```java
Cap cap = Cap.builder(System.getenv("CAP_SECRET")).build();

@PostMapping("challenge")
public ChallengeResponse challenge() {
    return cap.createChallenge();
}

@PostMapping("redeem")
public RedeemResult redeem(@RequestBody RedeemRequest request) {
    return cap.redeem(request);
}
```

上述类型都位于 `github.luckygc.cap`。`Cap` 是线程安全的，建议在 Spring 中注册为单例 Bean，
不要在每个请求中重新构建。此库不依赖 Spring，也不提供控制器、JSON databind 或认证中间件；
这些由宿主应用负责。示例展示最小协议接线；生产控制器应在返回响应前处理成功结果并持久化
`tokenKey` 与授权上下文。

需要绑定业务场景时，在生成和兑换两端使用同一 scope：

```java
ChallengeResponse challenge = cap.createChallenge(
        ChallengeOptions.builder().scope("login").build());

RedeemResult result = cap.redeem(
        request,
        RedeemOptions.builder().expectedScope("login").build());
```

`ChallengeOptions.extra(...)` 可以携带 JSON 兼容的不可变业务数据；不要放入密码、访问令牌等敏感值。

## STRICT 模式

STRICT 使用 Format 2，默认协议顺序为 RSW、instrumentation，并启用自动化浏览器拦截：

```java
Cap cap = Cap.builder(System.getenv("CAP_SECRET"))
        .profile(CapProfile.STRICT)
        .build();
```

默认构建会生成 2048-bit RSW 密钥，成本较高。生产环境应离线生成一次、安全持久化并在各实例复用：

```java
RswKeyPair keyPair = RswKeyPair.generate(2048);

Cap cap = Cap.builder(System.getenv("CAP_SECRET"))
        .profile(CapProfile.STRICT)
        .rswKeyPair(keyPair)
        .build();
```

`RswKeyPair` 的 `modulus`、`primeP`、`primeQ` 都是无符号十进制字符串；`primeP` 和
`primeQ` 属于私密材料。也可以通过 `protocols(...)` 按顺序选择
`SHA256_POW`、`RSW`、`INSTRUMENTATION`。空协议列表按上游语义回退为 RSW，重复协议会保留。

## 防重放与集群部署

默认 Caffeine nonce cache 只覆盖当前 JVM。多实例部署必须用共享存储实现原子
“不存在则写入并设置 TTL”，并完全替代本机缓存：

```java
Cap cap = Cap.builder(System.getenv("CAP_SECRET"))
        .nonceConsumer((signatureHex, ttl) -> redisSetNxEx(signatureHex, ttl))
        .build();
```

例如 Redis 实现应把 `signatureHex` 放入带命名空间的 key，并使用单条
`SET key value NX PX ttlMillis`；返回是否写入成功。回调在兑换线程同步执行，必须线程安全，
并自行设置连接超时。除非外层已有等价且经过审计的原子防重放机制，不要调用
`disableReplayProtection()`。

## 业务 token 与 tokenKey

兑换成功返回 `RedeemResult.Success`。默认 signer 生成：

- `token = id + ":" + secretPart`，交给客户端；
- `tokenKey = id + ":" + hex(sha256(secretPart))`，只交给服务端；
- `expires`、`scope`、`iat` 供业务层保存授权范围和有效期。

服务端应在兑换成功后存储 `tokenKey` 及其业务上下文，不存储明文 `token`。后续请求提交
`token` 时，用同一公式派生查找键，再按 `expires` 和业务策略查库：

```java
static String deriveTokenKey(String token) throws GeneralSecurityException {
    String[] parts = token.split(":", -1);
    if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
        throw new IllegalArgumentException("invalid token");
    }
    byte[] digest = MessageDigest.getInstance("SHA-256")
            .digest(parts[1].getBytes(StandardCharsets.UTF_8));
    return parts[0] + ":" + HexFormat.of().formatHex(digest);
}
```

这里需要导入 `GeneralSecurityException`、`MessageDigest`、`StandardCharsets` 和 `HexFormat`。
对外只返回统一的未授权错误，不记录 token 或派生键。若配置自定义 `TokenSigner`，
`tokenKey` 为 `null`，token 的持久化与校验模型完全由该 signer 的调用方负责。

## 自定义配置与受信回调

常用配置示例：

```java
Cap cap = Cap.builder(System.getenv("CAP_SECRET"))
        .challengeDefaults(ChallengeOptions.builder()
                .ttl(Duration.ofMinutes(5))
                .build())
        .redeemDefaults(RedeemOptions.builder()
                .tokenTtl(Duration.ofMinutes(15))
                .build())
        .format1(50, 32, 4)
        .nonceCacheMaximumSize(200_000)
        .eventListener(listener)
        .build();
```

`NonceConsumer`、`TokenSigner`、`CapEventListener` 和 `InstrumentationTransformer` 都会在
`createChallenge` / `redeem` 的调用线程同步执行，并可能被并发调用。它们必须是受信、线程安全、
有界的代码；库不会为回调提供超时、隔离或 JVM sandbox。instrumentation transformer 还能看到
完整脚本和 nonce 相关内容。事件与默认日志不包含 token、solution 或内部摘要。

## 3.0 迁移

3.0 是破坏性升级。旧版 `CapManager`、`CapStore`、配置/模型类以及
`validateCapToken(...)` 已删除，也没有兼容层：

- 用 `Cap.builder(secret).build()` 替代 `CapManager.builder()`；
- 用 `createChallenge(...)` / `redeem(...)` 的 records 直接作为 Web DTO；
- 用 `NonceConsumer` 或默认 Caffeine cache 替代 challenge 存储；
- 兑换成功后保存 `tokenKey`，不要再调用 `validateCapToken(...)`；
- 集群中为所有实例配置同一 `secret`、RSW key pair 和共享 `NonceConsumer`。

协议字段、失败码、加密 wire 和上游 fixture 说明见
[协议兼容性文档](docs/protocol-compatibility.md)。

## 构建与测试

```bash
mise exec maven -- mvn spotless:check
mise exec maven -- mvn test
mise exec maven -- mvn verify
```

常规构建只需要 Java 17+。Node 24 仅用于显式启用的上游 fixture / JavaScript 语义复核，
详见协议兼容性文档。

## 许可证

Apache License 2.0

# cap-server

[Cap](https://github.com/tiagozip/cap) 服务端协议的 Java 17 实现，兼容
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

需要共享 JDBC 防重放存储时，另加：

```xml
<dependency>
    <groupId>com.github.luckygc</groupId>
    <artifactId>cap-server-jdbc</artifactId>
    <version>3.0.0</version>
</dependency>
```

使用 Redis 时，另加 Lettuce 适配模块：

```xml
<dependency>
    <groupId>com.github.luckygc</groupId>
    <artifactId>cap-server-redis</artifactId>
    <version>3.0.0</version>
</dependency>
```

运行环境需要 Java 17+。`secret` 至少为 16 个 UTF-8 字节，应从密钥管理系统或环境变量读取，
不要写入源码。

## 快速开始

默认配置使用 Format 1 SHA-256 PoW、10 分钟 challenge TTL、20 分钟业务 token TTL。与
`capjs-core` 一样，默认不保存兑换状态；challenge 在有效期内可被重复兑换。需要一次性兑换时，
显式配置 `NonceConsumer`：

```java
import github.luckygc.cap.Cap;
import github.luckygc.cap.ChallengeResponse;
import github.luckygc.cap.RedeemRequest;
import github.luckygc.cap.RedeemResult;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/cap/")
public final class CapController {

    private final Cap cap = Cap.builder(System.getenv("CAP_SECRET")).build();

    @PostMapping("challenge")
    public ChallengeResponse challenge() {
        return cap.createChallenge();
    }

    @PostMapping("redeem")
    public RedeemWireResponse redeem(@RequestBody RedeemWireRequest request) {
        RedeemResult result = cap.redeem(request.toCapRequest());
        if (result instanceof RedeemResult.Success success && success.tokenKey() != null) {
            // 在返回前持久化 success.tokenKey()、scope()、expires() 和业务上下文。
        }
        return RedeemWireResponse.from(result);
    }

    public record RedeemWireRequest(
            String token,
            List<Object> solutions,
            InstrumentationWireResult instr,
            boolean instr_blocked,
            boolean instr_timeout) {

        RedeemRequest toCapRequest() {
            RedeemRequest.InstrumentationResult instrumentation = instr == null
                    ? null
                    : new RedeemRequest.InstrumentationResult(instr.i(), instr.state(), instr.ts());
            return new RedeemRequest(
                    token, solutions, instrumentation, instr_blocked, instr_timeout);
        }
    }

    public record InstrumentationWireResult(String i, Map<String, Object> state, Long ts) {}

    public sealed interface RedeemWireResponse {

        static RedeemWireResponse from(RedeemResult result) {
            if (result instanceof RedeemResult.Success success) {
                return new Success(
                        success.success(),
                        success.token(),
                        success.tokenKey(),
                        success.expires(),
                        success.scope(),
                        success.iat());
            }
            RedeemResult.Failure failure = (RedeemResult.Failure) result;
            return new Failure(
                    failure.success(),
                    failure.reason(),
                    failure.instrError(),
                    failure.error());
        }

        record Success(
                boolean success,
                String token,
                String tokenKey,
                long expires,
                String scope,
                long iat) implements RedeemWireResponse {}

        record Failure(
                boolean success,
                String reason,
                boolean instr_error,
                String error) implements RedeemWireResponse {}
    }
}
```

`ChallengeResponse` 的字段与上游 challenge wire 同名，可以直接返回。redeem wire 则必须经过
adapter：上游 widget 发送 `instr_blocked` / `instr_timeout`，失败响应读取 `instr_error`；核心 Java
API 为保持 Java 命名习惯，访问器分别是 `instrBlocked()`、`instrTimeout()`、`instrError()`。
不要把 `RedeemRequest` / `RedeemResult` 直接作为兼容上游的 Web DTO。示例中的 nullable record
component 未加注解；宿主应用可按自己的空值检查方案补充标注。

`Cap` 是线程安全的，建议注册为 Spring 单例，不要在每个请求中重新构建。此库不依赖 Spring，
也不提供控制器、Jackson Databind 或认证中间件；示例所需的 Web/JSON 依赖由宿主应用提供。

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

默认不启用防重放，与 `capjs-core` 的未配置 `consumeNonce` 语义一致。需要一次性兑换时，必须显式
配置原子的 `NonceConsumer`，以 challenge JWT 签名为 key 执行“不存在则写入并设置 TTL”。
`CaffeineNonceConsumer` 适用于单 JVM；多实例必须使用 JDBC、Redis 或其他共享存储。consumer 失败时
兑换 fail closed，返回 `nonce_store_error`，不会回退到其他存储。

单 JVM 可显式使用 Caffeine：

```java
import github.luckygc.cap.replay.CaffeineNonceConsumer;

Cap cap = Cap.builder(System.getenv("CAP_SECRET"))
        .nonceConsumer(new CaffeineNonceConsumer(100_000))
        .build();
```

JDBC 模块使用数据库唯一约束完成原子消费：

```java
import github.luckygc.cap.Cap;
import github.luckygc.cap.replay.jdbc.JdbcDialect;
import github.luckygc.cap.replay.jdbc.JdbcNonceConsumer;
import javax.sql.DataSource;

static Cap createJdbcCap(DataSource dataSource) {
    return Cap.builder(System.getenv("CAP_SECRET"))
            .nonceConsumer(new JdbcNonceConsumer(dataSource, JdbcDialect.POSTGRESQL))
            .build();
}
```

传入的 `DataSource` 必须在每次 `getConnection()` 时返回独立、`autoCommit=true`、不绑定宿主事务的
连接；不要传入会复用当前业务事务连接的 transaction-aware 包装。表结构与清理 SQL 由宿主应用管理。

Redis 模块接受调用方已有连接的同步 commands：

```java
import github.luckygc.cap.Cap;
import github.luckygc.cap.replay.redis.LettuceNonceConsumer;
import io.lettuce.core.api.StatefulRedisConnection;

static Cap createRedisCap(StatefulRedisConnection<String, String> connection) {
    return Cap.builder(System.getenv("CAP_SECRET"))
            .nonceConsumer(new LettuceNonceConsumer(connection.sync()))
            .build();
}
```

`LettuceNonceConsumer` 使用单条 `SET key 1 NX PX ttlMillis`。commands、底层连接或连接池及有界超时
都由调用方拥有和管理，consumer 不会关闭或重配它们。`NonceConsumer` 在兑换线程同步执行，必须线程
安全且有界。完整的存储选择、DDL、清理和时钟要求见
[防重放存储部署指南](docs/replay-storage.md)。

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
- challenge 可直接返回 `ChallengeResponse`；redeem 使用显式 wire DTO/adapter 处理 snake_case；
- 需要一次性兑换时，用 `NonceConsumer` 显式配置 challenge 防重放；
- 兑换成功后保存 `tokenKey`，不要再调用 `validateCapToken(...)`；
- 集群中为所有实例配置同一 `secret`、RSW key pair 和共享 `NonceConsumer`。

协议字段、失败码、加密 wire 和上游 fixture 说明见
[协议兼容性文档](docs/protocol-compatibility.md)；集群存储迁移见
[防重放存储部署指南](docs/replay-storage.md)。

## 构建与测试

```bash
mise exec maven -- mvn spotless:check
mise exec maven -- mvn test
mise exec maven -- mvn verify
```

常规构建只需要 Java 17+。Node 24 仅用于显式启用的上游 fixture / JavaScript 语义复核，
详见协议兼容性文档。

真实 widget E2E 是显式 opt-in 测试。它固定 `@cap.js/widget@0.1.56`、
`@cap.js/wasm@0.0.7` 和 `playwright@1.52.0`，准备与运行命令如下：

```bash
repo=$(pwd)
tmp=$(mktemp -d)
cd "$tmp"
npm init -y
npm install --save-exact @cap.js/widget@0.1.56 @cap.js/wasm@0.0.7 playwright@1.52.0
npx playwright install chromium
cd "$repo"
mise exec maven -- mvn -Pwidget-e2e -Dcap.widget.dir="$tmp" verify
```

默认 `mvn test` / `mvn verify` 不执行也不 skip 浏览器 IT，不需要 Node 或 Chromium。显式 profile
会严格校验 `package-lock.json` 中三个包的 version、resolved URL 和 integrity；缺少精确 artifact、
Node 或 Chromium 时硬失败，不会静默 skip。
相关失败（包括空或非法 `cap.widget.dir`）只显示固定脱敏类别，并附精确依赖、Chromium 与
`-Dcap.widget.dir` 的可执行准备命令；不会
回显实际本机路径。Java server 只保留 challenge/redeem 的类型、flags、协议顺序和 solution shape
等脱敏事实，不保存原始 token、redeem body 或 solution。浏览器只加载测试 server 提供的本地 widget/WASM，
不访问 CDN，并通过真实回环 HTTP 覆盖 Format 1 成功、原始 redeem replay 返回
`already_redeemed`、Format 1 instrumentation 成功、Format 2 RSW 成功，以及 STRICT 自动化拦截返回
`instr_automated_browser`。STRICT 页面预先植入覆盖默认随机抽样检查的标准自动化标记；即使 iframe
init 时 `documentElement` 尚为空，仍有 11 类稳定命中。不替换 Java 的 production
generator/transformer，也不靠重试等待随机命中。测试输出不包含 secret、JWT、solution、业务 token
或 tokenKey。

这个回环 server 只用于互操作验证，不是可复用的生产 Web 层。本库仍不提供 Web 框架、JSON databind、
认证、CORS 或 CSRF；宿主应用必须实现真实 challenge/redeem 端点以及认证和边界策略。

## 许可证

Apache License 2.0

# Capjs Core 0.1.1 Java Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将项目发布为 `3.0.0`，提供兼容 `capjs-core 0.1.1` Format 1/2、RSW 和 instrumentation 的简单 Java 17 API。

**Architecture:** 以线程安全的 `Cap` 门面协调 challenge 生成与兑换，协议状态加密并签名到 JWT，默认通过 Caffeine 原子消费 JWT 签名防重放。协议、密码学、JSON、RSW 和 instrumentation 实现均放在 `internal` 包，公共 API 只暴露不可变 records、枚举和少量函数接口。

**Tech Stack:** Java 17、Maven、Jackson Core 3.2.1、Caffeine 3.2.4、SLF4J API 2.0.18、JSpecify 1.0.0、JUnit 6.1.2、AssertJ 3.27.7、Spotless 3.8.0、google-java-format 1.35.0 AOSP

## Global Constraints

- 公共包名保持 `github.luckygc.cap`，内部实现放在 `github.luckygc.cap.internal`。
- 默认 Profile 为 Format 1 SHA-256 PoW；`STRICT` 为 Format 2 + RSW + instrumentation。
- 两个 Profile 默认都使用本机 Caffeine 防重放；集群可注入外部 `NonceConsumer` 完全替换本机缓存。
- 不保留 `CapStore`、`MemoryCapStore`、`CapManager` 或 `validateCapToken` 兼容层。
- secret 至少 16 UTF-8 字节；任何日志不得输出 secret、JWT、solutions、token、tokenKey 或 instrumentation 结果。
- 公共和内部包使用 JSpecify `@NullMarked`，仅真正可空位置使用 `@Nullable`。
- 公共 API 不暴露 Jackson、Caffeine 或 SLF4J 实现类型。
- 运行时依赖固定为 Jackson Core 3.2.1、Caffeine 3.2.4、SLF4J API 2.0.18、JSpecify 1.0.0。
- Java 格式由 Spotless 3.8.0 + google-java-format 1.35.0 AOSP 强制执行。
- 每个生产代码任务遵循 TDD：先写失败测试，再实现，再运行聚焦测试和全量测试。

---

### Task 1: 重建 Maven 构建与格式化基线

**Files:**
- Modify: `pom.xml`
- Modify: `AGENTS.md`
- Delete: `tools/maven/custom_google_checks.xml`
- Delete: `tools/maven/intellij-custom-java-google-style.xml`
- Delete: `tools/maven/suppressions.xml`

**Interfaces:**
- Consumes: Java 17 与 `mise exec maven -- mvn` 构建约定。
- Produces: 默认运行测试、`spotless:check` 绑定 `validate`、版本为 `3.0.0` 的构建。

- [ ] **Step 1: 将 POM 改为精简依赖与插件配置**

将 `pom.xml` 的 properties、dependencies 和 build 改成以下等价配置，并删除 `<repositories>`、`dev` profile、Checkstyle 与 Surefire skip：

```xml
<version>3.0.0</version>
<properties>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <maven.compiler.release>17</maven.compiler.release>
    <jackson-core.version>3.2.1</jackson-core.version>
    <caffeine.version>3.2.4</caffeine.version>
    <slf4j.version>2.0.18</slf4j.version>
    <jspecify.version>1.0.0</jspecify.version>
    <junit.version>6.1.2</junit.version>
    <assertj.version>3.27.7</assertj.version>
    <compiler-plugin.version>3.15.0</compiler-plugin.version>
    <surefire.version>3.5.6</surefire.version>
    <spotless.version>3.8.0</spotless.version>
    <google-java-format.version>1.35.0</google-java-format.version>
</properties>
```

新增目标运行时依赖：

```xml
<dependency>
    <groupId>tools.jackson.core</groupId>
    <artifactId>jackson-core</artifactId>
    <version>${jackson-core.version}</version>
</dependency>
<dependency>
    <groupId>com.github.ben-manes.caffeine</groupId>
    <artifactId>caffeine</artifactId>
    <version>${caffeine.version}</version>
</dependency>
<dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>slf4j-api</artifactId>
    <version>${slf4j.version}</version>
</dependency>
<dependency>
    <groupId>org.jspecify</groupId>
    <artifactId>jspecify</artifactId>
    <version>${jspecify.version}</version>
</dependency>
```

为保证迁移期间每次提交都可编译，Task 1 暂时保留旧源码仍在使用的
`commons-lang3` 与 `commons-codec`；两项依赖在 Task 10 与旧实现一起删除。Task 10
完成后，直接运行时依赖才精确收敛为上面四项。

测试依赖使用 `junit-jupiter` 聚合包和 AssertJ：

```xml
<dependency>
    <groupId>org.junit.jupiter</groupId>
    <artifactId>junit-jupiter</artifactId>
    <version>${junit.version}</version>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.assertj</groupId>
    <artifactId>assertj-core</artifactId>
    <version>${assertj.version}</version>
    <scope>test</scope>
</dependency>
```

Spotless 配置使用：

```xml
<plugin>
    <groupId>com.diffplug.spotless</groupId>
    <artifactId>spotless-maven-plugin</artifactId>
    <version>${spotless.version}</version>
    <configuration>
        <java>
            <removeUnusedImports/>
            <forbidWildcardImports/>
            <googleJavaFormat>
                <version>${google-java-format.version}</version>
                <style>AOSP</style>
                <formatJavadoc>true</formatJavadoc>
            </googleJavaFormat>
            <trimTrailingWhitespace/>
            <endWithNewline/>
        </java>
    </configuration>
    <executions>
        <execution>
            <id>spotless-check</id>
            <phase>validate</phase>
            <goals><goal>check</goal></goals>
        </execution>
    </executions>
</plugin>
```

- [ ] **Step 2: 删除 Checkstyle 配置并格式化现有代码**

Run:

```bash
mise exec maven -- mvn spotless:apply
```

Expected: `BUILD SUCCESS`，所有现有 Java 文件被 AOSP 格式化。

- [ ] **Step 3: 更新 AGENTS.md 的命令与质量门**

将 Checkstyle 说明替换为：

```markdown
- 使用 `mise exec maven -- mvn spotless:apply` 自动格式化 Java。
- `mise exec maven -- mvn test` 必须实际执行测试，不允许出现 `Tests are skipped.`。
- `mise exec maven -- mvn verify` 必须通过 Spotless、测试和打包。
```

- [ ] **Step 4: 验证构建配置**

Run:

```bash
mise exec maven -- mvn test
mise exec maven -- mvn verify
git diff --check
```

Expected: 现有 13 个测试执行并通过，Spotless 通过，`git diff --check` 无输出。

- [ ] **Step 5: 提交构建迁移**

```bash
git add pom.xml AGENTS.md src tools/maven
git commit -m "build: 升级依赖并迁移到 Spotless"
```

---

### Task 2: 定义新公共 API 与空值契约

**Files:**
- Create: `src/main/java/github/luckygc/cap/package-info.java`
- Create: `src/main/java/github/luckygc/cap/Cap.java`
- Create: `src/main/java/github/luckygc/cap/CapBuilder.java`
- Create: `src/main/java/github/luckygc/cap/CapProfile.java`
- Create: `src/main/java/github/luckygc/cap/CapProtocol.java`
- Create: `src/main/java/github/luckygc/cap/ChallengeOptions.java`
- Create: `src/main/java/github/luckygc/cap/RedeemOptions.java`
- Create: `src/main/java/github/luckygc/cap/ChallengeResponse.java`
- Create: `src/main/java/github/luckygc/cap/RedeemRequest.java`
- Create: `src/main/java/github/luckygc/cap/RedeemResult.java`
- Create: `src/main/java/github/luckygc/cap/NonceConsumer.java`
- Create: `src/main/java/github/luckygc/cap/TokenSigner.java`
- Create: `src/main/java/github/luckygc/cap/CapEventListener.java`
- Test: `src/test/java/github/luckygc/cap/CapApiTest.java`

**Interfaces:**
- Consumes: JSpecify annotations and Java records/sealed interfaces.
- Produces: 所有后续任务使用的稳定公共签名。

- [ ] **Step 1: 写 API 编译测试**

```java
@Test
void defaultAndStrictApiAreMinimal() {
    Cap defaultCap = Cap.builder("0123456789abcdef").build();
    Cap strictCap = Cap.builder("0123456789abcdef")
            .profile(CapProfile.STRICT)
            .build();

    assertThat(defaultCap).isNotNull();
    assertThat(strictCap).isNotNull();
}

@Test
void rejectsShortSecret() {
    assertThatIllegalArgumentException().isThrownBy(() -> Cap.builder("short").build());
}
```

- [ ] **Step 2: 验证测试因新 API 缺失而失败**

Run: `mise exec maven -- mvn -Dtest=CapApiTest test`

Expected: 编译失败，提示 `Cap`、`CapProfile` 尚不存在。

- [ ] **Step 3: 创建精确公共接口**

`Cap` 的签名：

```java
public interface Cap {
    static CapBuilder builder(String secret) { return new CapBuilder(secret); }
    ChallengeResponse createChallenge();
    ChallengeResponse createChallenge(ChallengeOptions options);
    RedeemResult redeem(RedeemRequest request);
    RedeemResult redeem(RedeemRequest request, RedeemOptions options);
}
```

Profile 与协议：

```java
public enum CapProfile { DEFAULT, STRICT }
public enum CapProtocol { SHA256_POW, RSW, INSTRUMENTATION }
```

函数接口：

```java
@FunctionalInterface
public interface NonceConsumer {
    boolean consume(String signatureHex, Duration ttl) throws Exception;
}

@FunctionalInterface
public interface TokenSigner {
    String sign(@Nullable String scope, Instant expiresAt, Instant issuedAt) throws Exception;
}
```

Challenge 返回值：

```java
public sealed interface ChallengeResponse
        permits ChallengeResponse.Format1, ChallengeResponse.Format2 {
    String token();
    long expires();

    record Format1(Challenge challenge, String token, long expires,
                   @Nullable String instrumentation) implements ChallengeResponse {}

    record Format2(int format, List<ProtocolChallenge> challenges,
                   String token, long expires) implements ChallengeResponse {
        public Format2 { if (format != 2) throw new IllegalArgumentException("format must be 2"); }
    }

    record Challenge(int c, int s, int d) {}
    record ProtocolChallenge(String protocol, Map<String, Object> payload) {}
}
```

兑换模型：

```java
public record RedeemRequest(
        String token,
        List<Object> solutions,
        @Nullable InstrumentationResult instr,
        boolean instrBlocked,
        boolean instrTimeout) {
    public record InstrumentationResult(String i, Map<String, Object> state, @Nullable Long ts) {}
}

public sealed interface RedeemResult permits RedeemResult.Success, RedeemResult.Failure {
    boolean success();

    record Success(boolean success, String token, @Nullable String tokenKey, long expires,
                   @Nullable String scope, long iat) implements RedeemResult {
        public Success { if (!success) throw new IllegalArgumentException("success must be true"); }
    }

    record Failure(boolean success, String reason, boolean instrError,
                   @Nullable String error) implements RedeemResult {
        public Failure { if (success) throw new IllegalArgumentException("success must be false"); }
    }
}
```

`ChallengeOptions` 与 `RedeemOptions` 的精确签名：

```java
public final class ChallengeOptions {
    public static ChallengeOptions defaults();
    public static Builder builder();
    public @Nullable String scope();
    public Map<String, Object> extra();
    public Duration ttl();

    public static final class Builder {
        public Builder scope(@Nullable String scope);
        public Builder extra(Map<String, ?> extra);
        public Builder ttl(Duration ttl);
        public ChallengeOptions build();
    }
}

public final class RedeemOptions {
    public static RedeemOptions defaults();
    public static Builder builder();
    public @Nullable String expectedScope();
    public Duration tokenTtl();

    public static final class Builder {
        public Builder expectedScope(@Nullable String expectedScope);
        public Builder tokenTtl(Duration tokenTtl);
        public RedeemOptions build();
    }
}
```

`defaults()` 分别使用 challenge TTL 10 分钟和 token TTL 20 分钟。所有 Map 在构造时
递归校验并防御性复制；Duration 必须大于 0 且不超过 24 小时。

- [ ] **Step 4: 创建 @NullMarked 包声明与最小 Builder 骨架**

```java
@NullMarked
package github.luckygc.cap;

import org.jspecify.annotations.NullMarked;
```

`CapBuilder` 的稳定高级 API 为：

```java
public final class CapBuilder {
    public CapBuilder profile(CapProfile profile);
    public CapBuilder challengeDefaults(ChallengeOptions options);
    public CapBuilder redeemDefaults(RedeemOptions options);
    public CapBuilder format1(int challengeCount, int challengeSize, int difficulty);
    public CapBuilder protocols(CapProtocol... protocols);
    public CapBuilder rswKeyPair(RswKeyPair keyPair);
    public CapBuilder rswIterations(int iterations);
    public CapBuilder instrumentation(InstrumentationOptions options);
    public CapBuilder nonceCacheMaximumSize(long maximumSize);
    public CapBuilder nonceConsumer(NonceConsumer consumer);
    public CapBuilder disableReplayProtection();
    public CapBuilder tokenSigner(TokenSigner signer);
    public CapBuilder eventListener(CapEventListener listener);
    public Cap build();
}
```

`CapEventListener` 不把请求或 token 交给监听器，只暴露安全的聚合事件：

```java
public interface CapEventListener {
    default void challengeCreated(ChallengeEvent event) {}
    default void redeemSucceeded(RedeemEvent event) {}
    default void redeemFailed(FailureEvent event) {}

    record ChallengeEvent(int format, List<CapProtocol> protocols, Duration duration) {}
    record RedeemEvent(int format, List<CapProtocol> protocols, Duration duration) {}
    record FailureEvent(int format, List<CapProtocol> protocols,
                        String reason, Duration duration) {}
}
```

`CapBuilder` 必须复制 secret 字节、验证至少 16 UTF-8 字节，并将所有默认值保存在不可变
配置中。`protocols` 防御性复制、拒绝 null/空/重复项；外部 nonce consumer 与
`disableReplayProtection()` 互斥。临时 `Cap` 实现只允许构建，协议方法抛出
`UnsupportedOperationException("protocol not implemented")`。

- [ ] **Step 5: 运行 API 测试和格式化**

Run:

```bash
mise exec maven -- mvn spotless:apply
mise exec maven -- mvn -Dtest=CapApiTest test
```

Expected: 2 tests pass。

- [ ] **Step 6: 提交 API**

```bash
git add src/main/java/github/luckygc/cap src/test/java/github/luckygc/cap/CapApiTest.java
git commit -m "feat: 定义 Cap 3.0 公共 API"
```

---

### Task 3: 实现受限 JSON、JWT 与加密元数据

**Files:**
- Create: `src/main/java/github/luckygc/cap/internal/package-info.java`
- Create: `src/main/java/github/luckygc/cap/internal/json/ProtocolJsonCodec.java`
- Create: `src/main/java/github/luckygc/cap/internal/crypto/CryptoSupport.java`
- Create: `src/main/java/github/luckygc/cap/internal/crypto/JwtCodec.java`
- Create: `src/main/java/github/luckygc/cap/internal/crypto/EncryptedMetadataCodec.java`
- Test: `src/test/java/github/luckygc/cap/internal/json/ProtocolJsonCodecTest.java`
- Test: `src/test/java/github/luckygc/cap/internal/crypto/JwtCodecTest.java`
- Test: `src/test/java/github/luckygc/cap/internal/crypto/EncryptedMetadataCodecTest.java`

**Interfaces:**
- Consumes: `Map<String,Object>` JSON payloads、UTF-8 secret。
- Produces: `String JwtCodec.sign(Map<String,Object>)`、`Optional<Map<String,Object>> verify(String)`、AES-GCM metadata 字符串。

- [ ] **Step 1: 写 JSON 与 JWT 失败测试**

测试必须精确覆盖：正常嵌套 Map/List 往返、NaN/Infinity 拒绝、超过 32 层拒绝、超过 64 KiB token 拒绝、HS256 round-trip、篡改签名拒绝、`alg=none` 拒绝。

核心断言：

```java
assertThat(codec.readObject(codec.writeObject(Map.of("n", "abc", "c", 50))))
        .containsEntry("n", "abc").containsEntry("c", 50L);
assertThatIllegalArgumentException().isThrownBy(() -> codec.writeObject(Map.of("x", Double.NaN)));
assertThat(jwt.verify(jwt.sign(Map.of("exp", 123L)))).hasValue(Map.of("exp", 123L));
assertThat(jwt.verify(tokenWithChangedLastCharacter)).isEmpty();
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mise exec maven -- mvn -Dtest='ProtocolJsonCodecTest,JwtCodecTest,EncryptedMetadataCodecTest' test`

Expected: 编译失败，internal codecs 不存在。

- [ ] **Step 3: 实现 ProtocolJsonCodec**

使用 Jackson Core `JsonFactory`、`JsonParser`、`JsonGenerator`，递归只接受 `null/String/Boolean/Byte/Short/Integer/Long/Float/Double/BigInteger/BigDecimal/List/Map<String,?>`。固定限制：最大输入 65,536 字节、深度 32、集合节点 10,000、单字符串 16,384 字符；浮点必须 `Double.isFinite`。解析数字时整数返回 `Long`，越界整数返回 `BigInteger`，小数返回 `BigDecimal`。

- [ ] **Step 4: 实现 CryptoSupport 与 JwtCodec**

`CryptoSupport` 使用 JDK `SecureRandom`、`HexFormat`、`MessageDigest`、`Mac(HmacSHA256)`、`Base64.getUrlEncoder().withoutPadding()`。JWT header 固定为 `{"alg":"HS256","typ":"JWT"}`；verify 必须恰好接收 3 段、检查 header 完全等价、使用 `MessageDigest.isEqual` 比较签名。

- [ ] **Step 5: 实现 EncryptedMetadataCodec**

从 secret 计算 SHA-256 得到 256 位 AES key；每次生成 12 字节随机 IV；使用 `AES/GCM/NoPadding` 和 128 位 tag。wire value 为 Base64URL 编码的 `iv || ciphertext || tag`。Format 2 使用 UTF-8 AAD `cap:fmt2-v1`，Format 1 不使用 AAD。篡改、错误 key 或错误 AAD 返回 empty，不抛出协议异常。

- [ ] **Step 6: 验证并提交**

```bash
mise exec maven -- mvn spotless:apply
mise exec maven -- mvn -Dtest='ProtocolJsonCodecTest,JwtCodecTest,EncryptedMetadataCodecTest' test
mise exec maven -- mvn test
git add src/main/java/github/luckygc/cap/internal src/test/java/github/luckygc/cap/internal
git commit -m "feat: 实现协议 JSON 与 JWT 加密"
```

Expected: 聚焦测试和全量测试均通过。

---

### Task 4: 实现 Format 1 SHA-256 PoW

**Files:**
- Rewrite: `src/main/java/github/luckygc/cap/utils/RandomUtil.java`
- Create: `src/main/java/github/luckygc/cap/internal/protocol/Format1Protocol.java`
- Create: `src/main/java/github/luckygc/cap/internal/protocol/ProtocolFailure.java`
- Test: `src/test/java/github/luckygc/cap/internal/protocol/Format1ProtocolTest.java`
- Test: `src/test/resources/fixtures/capjs-core-0.1.1/format1.json`

**Interfaces:**
- Consumes: JwtCodec、ChallengeOptions、RedeemRequest、secret。
- Produces: Format 1 challenge 与验证结果；`ProtocolFailure(reason,instrError,error)`。

- [ ] **Step 1: 固化 capjs-core 0.1.1 Format 1 fixture 并写失败测试**

Fixture 固定 secret `0123456789abcdef0123456789abcdef`、JWT、`c=2/s=8/d=2`、未过期的远期 exp、两项 solution。测试必须验证 Java 接受上游 token，并验证 Java PRNG 与 fixture salt/target 完全一致。

- [ ] **Step 2: 运行测试确认 Format1Protocol 缺失**

Run: `mise exec maven -- mvn -Dtest=Format1ProtocolTest test`

Expected: 编译失败。

- [ ] **Step 3: 用纯 JDK 重写 RandomUtil**

删除 Commons Lang；使用 `String.isBlank()`、`StringBuilder` 和 `"%08x".formatted(value)` 或无 locale 的手工零填充。保留 FNV-1a 与 xorshift32 的 32 位环绕语义，并新增可从已有 FNV state 继续处理 suffix 的方法，以匹配上游 `fnv1aResume`。

- [ ] **Step 4: 实现 challenge 生成**

默认值与限制精确为：`c=50`、`s=32`、`d=4`、TTL 10 分钟；`1<=c<=1000`、`1<=s<=256`、`1<=d<=16`。JWT payload 为 `n/c/s/d/exp/iat`，按需加入 `sk/x`。返回 `ChallengeResponse.Format1`。

- [ ] **Step 5: 实现 PoW 验证**

验证顺序固定为 body/token/solutions、JWT、scope、exp、参数边界、solutions 数量与整数类型、逐项 SHA-256 前缀。reason 使用 `invalid_body`、`missing_token`、`missing_solutions`、`invalid_token`、`scope_mismatch`、`expired`、`invalid_solutions`、`invalid_solution`。

- [ ] **Step 6: 验证并提交**

```bash
mise exec maven -- mvn spotless:apply
mise exec maven -- mvn -Dtest=Format1ProtocolTest test
mise exec maven -- mvn test
git add src/main/java/github/luckygc/cap/utils/RandomUtil.java src/main/java/github/luckygc/cap/internal/protocol src/test/java/github/luckygc/cap/internal/protocol src/test/resources/fixtures
git commit -m "feat: 实现 Format 1 PoW 协议"
```

---

### Task 5: 实现 Caffeine 防重放、业务 token 与日志事件

**Files:**
- Create: `src/main/java/github/luckygc/cap/internal/replay/CaffeineNonceConsumer.java`
- Create: `src/main/java/github/luckygc/cap/internal/token/DefaultTokenSigner.java`
- Create: `src/main/java/github/luckygc/cap/internal/CapEvents.java`
- Test: `src/test/java/github/luckygc/cap/internal/replay/CaffeineNonceConsumerTest.java`
- Test: `src/test/java/github/luckygc/cap/internal/token/DefaultTokenSignerTest.java`
- Test: `src/test/java/github/luckygc/cap/internal/CapEventsTest.java`

**Interfaces:**
- Consumes: JWT signatureHex、每项 TTL、TokenSigner、CapEventListener。
- Produces: 原子 replay claim、默认 `token/tokenKey` 对与安全日志。

- [ ] **Step 1: 写并发与过期失败测试**

使用 32 个并发任务同时消费同一 signature，断言恰好一个返回 true；使用 Caffeine `Ticker` 测试 TTL 到期后可再次消费；容量设为 2 时写入 3 个不同 key 并调用 `cleanUp()`，断言 estimatedSize 不超过 2。

- [ ] **Step 2: 运行测试确认实现缺失**

Run: `mise exec maven -- mvn -Dtest='CaffeineNonceConsumerTest,DefaultTokenSignerTest,CapEventsTest' test`

Expected: 编译失败。

- [ ] **Step 3: 实现 CaffeineNonceConsumer**

缓存类型为 `Cache<String, Long>`，value 保存该项 TTL 纳秒数；自定义 `Expiry` 在 create
时返回 value，在 update/read 时保持当前 duration，并将 TTL 截断到 1 纳秒至 24 小时。
`consume` 使用 `cache.asMap().putIfAbsent(signatureHex, ttlNanos) == null`。测试构造器注入
Caffeine `Ticker`，生产构造器使用系统 ticker。默认 maximumSize 为 100,000，Builder 可配置
1 到 10,000,000。

- [ ] **Step 4: 实现默认 token**

生成 8 字节随机 id 和 15 字节随机 secret；公开 token 为 `id:secret`，tokenKey 为 `id:sha256Hex(secret)`。内部 record `SignedToken(token,tokenKey)` 不进入公共 API。自定义 `TokenSigner` 返回 token 时，Success 的 tokenKey 为 null。

- [ ] **Step 5: 实现日志与事件隔离**

`CapEvents` 使用 `LoggerFactory.getLogger(Cap.class)`；DEBUG 只输出 format、协议名、duration 和 reason；WARN 只输出异常类型与受限消息。监听器调用包在 try/catch 中，异常不改变主流程。测试使用计数 listener 验证异常被吞掉，且事件对象不包含敏感字段。

- [ ] **Step 6: 验证并提交**

```bash
mise exec maven -- mvn spotless:apply
mise exec maven -- mvn -Dtest='CaffeineNonceConsumerTest,DefaultTokenSignerTest,CapEventsTest' test
mise exec maven -- mvn test
git add src/main/java/github/luckygc/cap/internal/replay src/main/java/github/luckygc/cap/internal/token src/main/java/github/luckygc/cap/internal/CapEvents.java src/test/java/github/luckygc/cap/internal
git commit -m "feat: 添加自动过期防重放与事件日志"
```

---

### Task 6: 实现 RSW 密钥与 minter

**Files:**
- Create: `src/main/java/github/luckygc/cap/RswKeyPair.java`
- Create: `src/main/java/github/luckygc/cap/internal/rsw/RswSupport.java`
- Test: `src/test/java/github/luckygc/cap/internal/rsw/RswSupportTest.java`
- Test: `src/test/resources/fixtures/capjs-core-0.1.1/rsw.json`

**Interfaces:**
- Consumes: bits、p/q/N、t、SecureRandom。
- Produces: serializable RswKeyPair、`RswMinter.mint()` 的 N/x/y/t。

- [ ] **Step 1: 写上游 fixture 与失败测试**

Fixture 包含确定的 `N/p/q/t/r` 和上游计算的 `x/y`。测试覆盖 fixture、serialize/deserialize、奇数 bits、错误 `N != p*q`、非正 t 和 claimed y 前导零规范化。

- [ ] **Step 2: 运行测试确认失败**

Run: `mise exec maven -- mvn -Dtest=RswSupportTest test`

Expected: 编译失败。

- [ ] **Step 3: 实现 RswKeyPair 与生成**

公开类型的精确签名为：

```java
public record RswKeyPair(int bits, String modulus, String primeP, String primeQ) {
    public static RswKeyPair generate(int bits);
}
```

四个值都必须非 null；`modulus/primeP/primeQ` 使用无符号十进制字符串，构造器解析并
验证 `N == p*q`、p/q 为不同的 probable prime、N 的 bit length 与 bits 相差不超过 1。
`generate(2048)` 使用 `BigInteger.probablePrime(bits/2, secureRandom)`；bits 为
1024..8192 的偶数。测试通过 package-private 的 `generate(int, SecureRandom)` 注入随机源，
公共 API 不暴露 `SecureRandom`。

- [ ] **Step 4: 实现 minter**

严格移植上游 CRT：计算 `2^t mod (p-1)/(q-1)`、`h`、`q^-1 mod p`，每次随机 256 位 r，返回固定 modulus 字节宽度的小写十六进制 N/x/y。默认 `t=75_000`，允许 1..10,000,000。

- [ ] **Step 5: 验证并提交**

```bash
mise exec maven -- mvn spotless:apply
mise exec maven -- mvn -Dtest=RswSupportTest test
mise exec maven -- mvn test
git add src/main/java/github/luckygc/cap/RswKeyPair.java src/main/java/github/luckygc/cap/internal/rsw src/test/java/github/luckygc/cap/internal/rsw src/test/resources/fixtures/capjs-core-0.1.1/rsw.json
git commit -m "feat: 实现 RSW challenge minter"
```

---

### Task 7: 实现 Instrumentation 生成与验证

**Files:**
- Create: `src/main/java/github/luckygc/cap/InstrumentationOptions.java`
- Create: `src/main/java/github/luckygc/cap/InstrumentationTransformer.java`
- Create: `src/main/java/github/luckygc/cap/internal/instrumentation/InstrumentationGenerator.java`
- Create: `src/main/java/github/luckygc/cap/internal/instrumentation/InstrumentationVerifier.java`
- Create: `src/main/java/github/luckygc/cap/internal/instrumentation/BrowserChecks.java`
- Test: `src/test/java/github/luckygc/cap/internal/instrumentation/InstrumentationGeneratorTest.java`
- Test: `src/test/java/github/luckygc/cap/internal/instrumentation/InstrumentationVerifierTest.java`
- Test: `src/test/resources/fixtures/capjs-core-0.1.1/instrumentation.json`

**Interfaces:**
- Consumes: InstrumentationOptions、SecureRandom、可选 transformer。
- Produces: `GeneratedInstrumentation(id,expires,expectedVals,vars,blocked,instrumentation)` 和验证 reason。

- [ ] **Step 1: 写 fixture 与失败测试**

测试解压 Base64 raw-Deflate 后的脚本，断言包含唯一 nonce、`postMessage`、DOM 操作与四个随机变量；验证正确 state、错误 id、缺失 state、错误 expected、blocked、timeout 和过期 reason。

- [ ] **Step 2: 运行测试确认失败**

Run: `mise exec maven -- mvn -Dtest='InstrumentationGeneratorTest,InstrumentationVerifierTest' test`

Expected: 编译失败。

- [ ] **Step 3: 移植动态运算 challenge**

先实现稳定公共配置签名：

```java
public final class InstrumentationOptions {
    public static InstrumentationOptions defaults();
    public static Builder builder();
    public int level();
    public boolean blockAutomatedBrowsers();
    public InstrumentationTransformer transformer();

    public static final class Builder {
        public Builder level(int level);
        public Builder blockAutomatedBrowsers(boolean block);
        public Builder transformer(InstrumentationTransformer transformer);
        public InstrumentationOptions build();
    }
}

@FunctionalInterface
public interface InstrumentationTransformer {
    String transform(String script, int level);
}
```

level 允许 0..3，默认 3；默认 transformer 为库内置实现，公共 getter 始终返回非 null。

生成 4 个 12 字符变量、4 个 10..250 初始值、20 次随机 bitwise/DOM 运算、4 个最终 salt 映射；在 Java 中同步计算 expectedVals。客户端脚本必须在 iframe 环境运行并通过 `parent.postMessage({type:'cap:instr',nonce:id,result:{i:id,state,ts}}, '*')` 返回。

- [ ] **Step 4: 移植浏览器真实性与自动化检查**

包含上游的 Navigator/Window/Document/HTMLElement/EventTarget 原生实例检查、DOM event round-trip、Node/Bun/Deno global 泄漏检查。`blockAutomatedBrowsers=true` 时加入 webdriver、Selenium、Puppeteer/Playwright、Headless UA、Electron、PhantomJS 和 offscreen WebGL 标记检查；blocked 使用 `{blocked:true}` wire 状态。

- [ ] **Step 5: 实现 transformer、压缩与验证**

内置 transformer 执行稳定的空白压缩与字符串表混淆；自定义 transformer 签名为 `String transform(String script, int level)`。使用 `Deflater(level=1, nowrap=true)` 生成 raw Deflate，再 Base64。Verifier 精确返回 `missing_meta/missing_output/id_mismatch/invalid_state/invalid_meta/failed_challenge`。

- [ ] **Step 6: 验证并提交**

```bash
mise exec maven -- mvn spotless:apply
mise exec maven -- mvn -Dtest='InstrumentationGeneratorTest,InstrumentationVerifierTest' test
mise exec maven -- mvn test
git add src/main/java/github/luckygc/cap/InstrumentationOptions.java src/main/java/github/luckygc/cap/InstrumentationTransformer.java src/main/java/github/luckygc/cap/internal/instrumentation src/test/java/github/luckygc/cap/internal/instrumentation src/test/resources/fixtures/capjs-core-0.1.1/instrumentation.json
git commit -m "feat: 实现浏览器 instrumentation challenge"
```

---

### Task 8: 实现 Format 2 协议组合

**Files:**
- Create: `src/main/java/github/luckygc/cap/internal/protocol/Format2Protocol.java`
- Test: `src/test/java/github/luckygc/cap/internal/protocol/Format2ProtocolTest.java`
- Test: `src/test/resources/fixtures/capjs-core-0.1.1/format2.json`

**Interfaces:**
- Consumes: JwtCodec、EncryptedMetadataCodec、RswSupport、InstrumentationGenerator、RedeemRequest。
- Produces: Format 2 challenge list 与验证结果。

- [ ] **Step 1: 写三协议组合 fixture 与失败测试**

Fixture 顺序固定为 `sha256-pow/rsw/instrumentation`，测试 Java 验证上游 token 与 solution，并覆盖顺序错误、缺项、未知 protocol、错误 AAD 和 instrumentation timeout。

- [ ] **Step 2: 运行测试确认失败**

Run: `mise exec maven -- mvn -Dtest=Format2ProtocolTest test`

Expected: 编译失败。

- [ ] **Step 3: 实现 Format 2 生成**

payload 为 `f=2/n/exp/iat/ev`；`ev` 加密 `{expected:[...]}` 并使用 AAD `cap:fmt2-v1`。SHA challenge payload 为 salt/target；RSW 为 N/x/t；instrumentation 为 blob。返回 `ChallengeResponse.Format2(2, challenges, token, expires)`。

- [ ] **Step 4: 实现 Format 2 验证**

验证 scope/exp/ev/solution 数量后按 expected 顺序分派。solution 对象从 `Map<String,Object>` 读取：SHA 的 nonce 接受整数或字符串；RSW 的 y 为字符串；instrumentation 接受 instr/blocked/timeout。未知或类型不符返回 `invalid_solution`。

- [ ] **Step 5: 验证并提交**

```bash
mise exec maven -- mvn spotless:apply
mise exec maven -- mvn -Dtest=Format2ProtocolTest test
mise exec maven -- mvn test
git add src/main/java/github/luckygc/cap/internal/protocol/Format2Protocol.java src/test/java/github/luckygc/cap/internal/protocol/Format2ProtocolTest.java src/test/resources/fixtures/capjs-core-0.1.1/format2.json
git commit -m "feat: 实现 Format 2 多协议 challenge"
```

---

### Task 9: 集成 Cap 门面、Profile 与兑换流程

**Files:**
- Complete: `src/main/java/github/luckygc/cap/CapBuilder.java`
- Create: `src/main/java/github/luckygc/cap/internal/DefaultCap.java`
- Modify: `src/main/java/github/luckygc/cap/internal/protocol/Format1Protocol.java`
- Modify: `src/main/java/github/luckygc/cap/internal/protocol/Format2Protocol.java`
- Test: `src/test/java/github/luckygc/cap/CapIntegrationTest.java`

**Interfaces:**
- Consumes: 所有协议组件、NonceConsumer、TokenSigner、CapEventListener。
- Produces: 完整可用的 `Cap.builder(secret).build()` 和 STRICT Profile。

- [ ] **Step 1: 写门面端到端失败测试**

测试必须覆盖：默认 create/redeem、默认重复兑换失败、STRICT 返回 Format2 且协议为 RSW+instrumentation、scope mismatch、custom TokenSigner、外部 NonceConsumer 拒绝与抛异常、listener 事件计数。

- [ ] **Step 2: 运行测试确认当前骨架失败**

Run: `mise exec maven -- mvn -Dtest=CapIntegrationTest test`

Expected: `UnsupportedOperationException: protocol not implemented`。

- [ ] **Step 3: 完成 Builder 默认规则**

DEFAULT：Format 1、c=50/s=32/d=4、challenge TTL=10m、token TTL=20m、无
instrumentation。STRICT：Format 2、协议 `[RSW, INSTRUMENTATION]`、RSW bits=2048、
t=75,000、instrumentation level=3、blockAutomatedBrowsers=true。两者默认 Caffeine
maximumSize=100,000。DEFAULT 调用 `instrumentation(...)` 时启用 Format 1 的 `ei` 与
instrumentation 返回字段；STRICT 调用 `protocols(...)` 时以调用方顺序完整替换默认组合。

- [ ] **Step 4: 实现统一 redeem 后处理**

协议验证成功后计算 JWT signatureHex 与剩余 TTL，调用 nonce consumer；false 返回 `already_redeemed`，异常返回 `nonce_store_error`。随后调用 TokenSigner 或默认 token 生成器，构造 Success。所有阶段发出受限事件和 duration DEBUG 日志。

- [ ] **Step 5: 验证并提交**

```bash
mise exec maven -- mvn spotless:apply
mise exec maven -- mvn -Dtest=CapIntegrationTest test
mise exec maven -- mvn test
git add src/main/java/github/luckygc/cap src/test/java/github/luckygc/cap/CapIntegrationTest.java
git commit -m "feat: 完成 Cap 默认与严格模式"
```

---

### Task 10: 删除旧 API 并补强真实协议测试

**Files:**
- Modify: `pom.xml`
- Delete: `src/main/java/github/luckygc/cap/CapManager.java`
- Delete: `src/main/java/github/luckygc/cap/CapStore.java`
- Delete: `src/main/java/github/luckygc/cap/config/CapTokenConfig.java`
- Delete: `src/main/java/github/luckygc/cap/config/ChallengeConfig.java`
- Delete: `src/main/java/github/luckygc/cap/impl/CapManagerBuilder.java`
- Delete: `src/main/java/github/luckygc/cap/impl/CapManagerImpl.java`
- Delete: `src/main/java/github/luckygc/cap/impl/MemoryCapStore.java`
- Delete: old files under `src/main/java/github/luckygc/cap/model/`
- Delete: `src/main/java/github/luckygc/cap/utils/Messages.java`
- Delete: `src/main/resources/github/luckygc/cap/messages/Messages.properties`
- Delete: `src/test/java/github/luckygc/cap/CreateChallengeTest.java`
- Delete: `src/test/java/github/luckygc/cap/RedeemChallengeTest.java`
- Create: `src/test/java/github/luckygc/cap/ProtocolSecurityTest.java`

**Interfaces:**
- Consumes: 新 Cap API。
- Produces: 无旧 API 的 3.0.0 源码与恶意输入回归覆盖。

- [ ] **Step 1: 写安全回归测试**

覆盖 null request、空 token、超长 token、错误 JWT 段数、错误 alg、篡改 ciphertext、过期、超大 c/s/d、非整数 solution、并发重复兑换、日志不包含输入 token。

- [ ] **Step 2: 删除旧源码、旧测试和临时依赖**

只删除上述明确文件；保留已重写的 `RandomUtil`。从 `pom.xml` 删除 Task 1 暂留的
`commons-lang3`、`commons-codec` 依赖和版本属性。运行
`rg 'CapManager|CapStore|validateCapToken|Messages|org\.apache\.commons' src pom.xml`，预期无命中。

- [ ] **Step 3: 验证新测试与依赖树**

```bash
mise exec maven -- mvn spotless:apply
mise exec maven -- mvn test
mise exec maven -- mvn dependency:tree
```

Expected: 全部测试通过；直接运行时依赖只显示 Jackson Core、Caffeine、SLF4J API、JSpecify。

- [ ] **Step 4: 提交旧 API 移除**

```bash
git add -A src
git commit -m "refactor!: 移除旧存储式 Cap API"
```

---

### Task 11: 更新 README、AGENTS 与兼容性说明

**Files:**
- Rewrite: `README.md`
- Modify: `AGENTS.md`
- Create: `docs/protocol-compatibility.md`

**Interfaces:**
- Consumes: 最终 Cap 3.0 API。
- Produces: 默认、STRICT、Web 端点、集群防重放和迁移说明。

- [ ] **Step 1: 重写 README 快速开始**

README 必须包含可复制的默认示例：

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

并包含 STRICT：

```java
Cap cap = Cap.builder(System.getenv("CAP_SECRET"))
        .profile(CapProfile.STRICT)
        .build();
```

- [ ] **Step 2: 写集群与 tokenKey 示例**

明确本机 Caffeine 只防单 JVM 重放；集群示例使用 `consumeNonce((sig, ttl) -> redisSetNxEx(sig, ttl))`。说明成功后存储 `tokenKey`，客户端持有 `token`，业务层从提交 token 派生 key 后查库。

- [ ] **Step 3: 写协议兼容文档**

列出 capjs-core 0.1.1 的 Format 1/2 字段、支持的失败 reason、DEFAULT/STRICT 映射、fixture 来源和重新生成步骤。明确 2.x 到 3.x 为 breaking change，无旧 API 兼容层。

- [ ] **Step 4: 更新 AGENTS.md**

删除旧一次性 Cap token 与 Surefire skip 说明，改为 JWT 防重放、Format 1/2、Spotless 命令和默认真实测试要求。

- [ ] **Step 5: 验证文档命令并提交**

```bash
mise exec maven -- mvn spotless:check
mise exec maven -- mvn test
mise exec maven -- mvn verify
git diff --check
```

Expected: Spotless、全部测试、打包和 whitespace 检查通过。

```bash
git add README.md AGENTS.md docs/protocol-compatibility.md
git commit -m "docs: 更新 Cap 3.0 接入与迁移说明"
```

---

### Task 12: 最终跨语言与发布前验证

**Files:**
- Create: `tools/fixtures/generate-capjs-core-fixtures.mjs`
- Modify: `src/test/resources/fixtures/capjs-core-0.1.1/*.json`
- Modify: `docs/protocol-compatibility.md`

**Interfaces:**
- Consumes: 上游 `capjs-core@0.1.1` 与 Java 生成结果。
- Produces: 可复现的跨语言 fixture 和发布证据。

- [ ] **Step 1: 创建 fixture 生成脚本**

脚本固定 secret、远期 expiry 和 RSW keypair，调用 `capjs-core` 生成 Format 1、Format 1
instrumentation、Format 2 三协议 fixture；输出前对字段排序并写入明确 schema，同时记录
生成所用的 capjs-core 版本。随机 nonce 与 instrumentation 脚本允许每次重新生成，Java
测试验证 wire 语义和签名，不比较 fixture 文件的逐字节稳定性。脚本只用于协议升级，不进入
Maven 常规生命周期。

- [ ] **Step 2: 在临时目录运行上游生成器**

```bash
repo=$(pwd)
tmp=$(mktemp -d)
cd "$tmp"
npm init -y
npm install capjs-core@0.1.1
node "$repo/tools/fixtures/generate-capjs-core-fixtures.mjs" --output "$tmp/fixtures"
cd "$repo"
mise exec maven -- mvn -Dcap.fixture.dir="$tmp/fixtures" -Dtest='*CompatibilityTest' test
```

Expected: 新生成 fixture 能被 Java 兼容测试全部接受；字段结构、协议顺序和验证结果与仓库
fixture 语义一致，动态随机字段不要求逐字节相同。

- [ ] **Step 3: 运行完整验证**

```bash
mise exec maven -- mvn clean verify
mise exec maven -- mvn dependency:tree
git diff --check
git status --short
```

Expected: `BUILD SUCCESS`；全部测试真实执行且 0 failure/error；直接运行时依赖为四项；无非预期文件。

- [ ] **Step 4: 提交 fixture 工具**

```bash
git add tools/fixtures src/test/resources/fixtures docs/protocol-compatibility.md
git commit -m "test: 添加 capjs-core 跨语言兼容向量"
```

- [ ] **Step 5: 审查提交范围**

```bash
git log --oneline --decorate -15
git diff HEAD~12..HEAD --stat
```

Expected: 提交按构建、API、crypto、Format 1、replay、RSW、instrumentation、Format 2、门面、旧 API 删除、文档、fixture 分层；没有与迁移无关的改动。

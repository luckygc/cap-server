package github.luckygc.cap;

import static org.assertj.core.api.Assertions.assertThat;

import github.luckygc.cap.internal.crypto.EncryptedMetadataCodec;
import github.luckygc.cap.internal.crypto.JwtCodec;
import github.luckygc.cap.utils.RandomUtil;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Cap 门面集成测试")
class CapIntegrationTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    @Test
    @DisplayName("默认模式可创建、兑换并阻止重复兑换")
    void createsRedeemsAndRejectsReplayInDefaultProfile() {
        Cap cap = Cap.builder(SECRET).format1(1, 4, 1).build();
        ChallengeResponse.Format1 challenge = (ChallengeResponse.Format1) cap.createChallenge();
        RedeemRequest request = solve(challenge);

        RedeemResult first = cap.redeem(request);
        RedeemResult second = cap.redeem(request);

        assertThat(challenge.challenge()).isEqualTo(new ChallengeResponse.Challenge(1, 4, 1));
        assertThat(first).isInstanceOf(RedeemResult.Success.class);
        RedeemResult.Success success = (RedeemResult.Success) first;
        assertThat(success.token()).contains(":");
        assertThat(success.tokenKey()).contains(":");
        assertFailure(second, "already_redeemed");
    }

    @Test
    @DisplayName("challenge 与 redeem 默认配置和显式 options 遵循完整替换")
    void usesConfiguredDefaultsAndExplicitOptions() {
        ChallengeOptions configuredChallenge =
                ChallengeOptions.builder().scope("configured").ttl(Duration.ofMinutes(2)).build();
        RedeemOptions configuredRedeem =
                RedeemOptions.builder()
                        .expectedScope("configured")
                        .tokenTtl(Duration.ofMinutes(3))
                        .build();
        Cap cap =
                Cap.builder(SECRET)
                        .format1(1, 4, 1)
                        .challengeDefaults(configuredChallenge)
                        .redeemDefaults(configuredRedeem)
                        .disableReplayProtection()
                        .build();

        ChallengeResponse.Format1 configured = (ChallengeResponse.Format1) cap.createChallenge();
        RedeemResult.Success configuredResult =
                (RedeemResult.Success) cap.redeem(solve(configured));
        ChallengeResponse.Format1 explicit =
                (ChallengeResponse.Format1)
                        cap.createChallenge(
                                ChallengeOptions.builder()
                                        .scope("explicit")
                                        .extra(Map.of("tenant", "alpha"))
                                        .ttl(Duration.ofMinutes(1))
                                        .build());
        RedeemResult.Success explicitResult =
                (RedeemResult.Success)
                        cap.redeem(
                                solve(explicit),
                                RedeemOptions.builder()
                                        .expectedScope("explicit")
                                        .tokenTtl(Duration.ofSeconds(30))
                                        .build());

        long now = System.currentTimeMillis();
        assertThat(configuredResult.scope()).isEqualTo("configured");
        assertThat(configuredResult.expires()).isBetween(now + 175_000, now + 185_000);
        assertThat(explicitResult.scope()).isEqualTo("explicit");
        assertThat(explicitResult.expires()).isBetween(now + 25_000, now + 35_000);
        assertThat(new JwtCodec(SECRET).verify(explicit.token()).orElseThrow().get("x"))
                .isEqualTo(Map.of("tenant", "alpha"));
    }

    @Test
    @DisplayName("STRICT 按配置顺序生成 Format 2 并可兑换")
    void strictProfileUsesOrderedFormat2Protocols() {
        Cap cap =
                Cap.builder(SECRET)
                        .profile(CapProfile.STRICT)
                        .protocols(CapProtocol.SHA256_POW)
                        .format1(1, 4, 1)
                        .build();

        ChallengeResponse.Format2 challenge = (ChallengeResponse.Format2) cap.createChallenge();
        Map<String, Object> protocol = challenge.challenges().get(0).payload();
        long nonce = solvePow((String) protocol.get("salt"), (String) protocol.get("target"));
        RedeemResult result =
                cap.redeem(
                        new RedeemRequest(
                                challenge.token(),
                                List.of(Map.of("nonce", nonce)),
                                null,
                                false,
                                false));

        assertThat(challenge.format()).isEqualTo(2);
        assertThat(challenge.challenges())
                .extracting(ChallengeResponse.ProtocolChallenge::protocol)
                .containsExactly("sha256-pow");
        assertThat(result).isInstanceOf(RedeemResult.Success.class);
    }

    @Test
    @DisplayName("Format 1 instrumentation 生成并按顶层信号顺序验证")
    void format1InstrumentationUsesTopLevelSignals() {
        Cap cap =
                Cap.builder(SECRET)
                        .format1(1, 4, 1)
                        .instrumentation(
                                InstrumentationOptions.builder()
                                        .level(0)
                                        .blockAutomatedBrowsers(true)
                                        .build())
                        .disableReplayProtection()
                        .build();
        ChallengeResponse.Format1 challenge = (ChallengeResponse.Format1) cap.createChallenge();
        RedeemRequest solved = solve(challenge);

        RedeemResult blocked =
                cap.redeem(new RedeemRequest(solved.token(), solved.solutions(), null, true, true));
        RedeemResult missing = cap.redeem(solved);
        RedeemResult valid =
                cap.redeem(
                        new RedeemRequest(
                                solved.token(),
                                solved.solutions(),
                                format1InstrumentationResult(solved.token()),
                                false,
                                false));

        assertThat(challenge.instrumentation()).isNotBlank();
        assertThat(new JwtCodec(SECRET).verify(challenge.token()).orElseThrow()).containsKey("ei");
        assertFailure(blocked, "instr_automated_browser");
        assertFailure(missing, "instr_missing");
        assertThat(valid).isInstanceOf(RedeemResult.Success.class);
    }

    @Test
    @DisplayName("scope mismatch 在 nonce 消费和签发前失败")
    void rejectsScopeMismatchBeforePostProcessing() {
        AtomicInteger nonceCalls = new AtomicInteger();
        AtomicInteger signerCalls = new AtomicInteger();
        Cap cap =
                Cap.builder(SECRET)
                        .format1(1, 4, 1)
                        .nonceConsumer(
                                (signature, ttl) -> {
                                    nonceCalls.incrementAndGet();
                                    return true;
                                })
                        .tokenSigner(
                                (scope, expires, issuedAt) -> {
                                    signerCalls.incrementAndGet();
                                    return "signed";
                                })
                        .build();
        ChallengeResponse.Format1 challenge =
                (ChallengeResponse.Format1)
                        cap.createChallenge(ChallengeOptions.builder().scope("login").build());

        RedeemResult result =
                cap.redeem(
                        solve(challenge), RedeemOptions.builder().expectedScope("signup").build());

        assertFailure(result, "scope_mismatch");
        assertThat(nonceCalls).hasValue(0);
        assertThat(signerCalls).hasValue(0);
    }

    @Test
    @DisplayName("自定义 signer 获取业务时刻且不返回 tokenKey")
    void supportsCustomTokenSigner() {
        List<Object> invocation = new ArrayList<>();
        Cap cap =
                Cap.builder(SECRET)
                        .format1(1, 4, 1)
                        .disableReplayProtection()
                        .tokenSigner(
                                (scope, expiresAt, issuedAt) -> {
                                    invocation.add(scope);
                                    invocation.add(expiresAt);
                                    invocation.add(issuedAt);
                                    return "custom-token";
                                })
                        .build();
        ChallengeResponse.Format1 challenge =
                (ChallengeResponse.Format1)
                        cap.createChallenge(ChallengeOptions.builder().scope("login").build());

        RedeemResult.Success result = (RedeemResult.Success) cap.redeem(solve(challenge));

        assertThat(result.token()).isEqualTo("custom-token");
        assertThat(result.tokenKey()).isNull();
        assertThat(invocation.get(0)).isEqualTo("login");
        assertThat(invocation.get(1)).isEqualTo(Instant.ofEpochMilli(result.expires()));
        assertThat(invocation.get(2)).isEqualTo(Instant.ofEpochMilli(result.iat()));
    }

    @Test
    @DisplayName("外部 nonce consumer 完全决定拒绝与存储异常")
    void mapsExternalNonceConsumerFailures() {
        Cap rejecting =
                Cap.builder(SECRET).format1(1, 4, 1).nonceConsumer((sig, ttl) -> false).build();
        ChallengeResponse.Format1 rejectedChallenge =
                (ChallengeResponse.Format1) rejecting.createChallenge();
        Cap throwing =
                Cap.builder(SECRET)
                        .format1(1, 4, 1)
                        .nonceConsumer(
                                (sig, ttl) -> {
                                    throw new Exception("sensitive-storage-message");
                                })
                        .build();
        ChallengeResponse.Format1 erroredChallenge =
                (ChallengeResponse.Format1) throwing.createChallenge();

        assertFailure(rejecting.redeem(solve(rejectedChallenge)), "already_redeemed");
        RedeemResult errored = throwing.redeem(solve(erroredChallenge));
        assertFailure(errored, "nonce_store_error");
        assertThat(((RedeemResult.Failure) errored).error()).isNull();
    }

    @Test
    @DisplayName("禁用 replay protection 时同一 challenge 可重复兑换")
    void disablingReplayProtectionDoesNotClaim() {
        Cap cap = Cap.builder(SECRET).format1(1, 4, 1).disableReplayProtection().build();
        RedeemRequest request = solve((ChallengeResponse.Format1) cap.createChallenge());

        assertThat(cap.redeem(request)).isInstanceOf(RedeemResult.Success.class);
        assertThat(cap.redeem(request)).isInstanceOf(RedeemResult.Success.class);
    }

    @Test
    @DisplayName("listener 异常被隔离且成功失败事件各计数一次")
    void isolatesListenerAndCountsEvents() {
        AtomicInteger creates = new AtomicInteger();
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        Cap cap =
                Cap.builder(SECRET)
                        .format1(1, 4, 1)
                        .eventListener(
                                new CapEventListener() {
                                    @Override
                                    public void challengeCreated(ChallengeEvent event) {
                                        creates.incrementAndGet();
                                        throw new IllegalStateException("listener-secret");
                                    }

                                    @Override
                                    public void redeemSucceeded(RedeemEvent event) {
                                        successes.incrementAndGet();
                                    }

                                    @Override
                                    public void redeemFailed(FailureEvent event) {
                                        failures.incrementAndGet();
                                    }
                                })
                        .build();
        RedeemRequest request = solve((ChallengeResponse.Format1) cap.createChallenge());

        assertThat(cap.redeem(request)).isInstanceOf(RedeemResult.Success.class);
        assertFailure(cap.redeem(request), "already_redeemed");
        assertThat(creates).hasValue(1);
        assertThat(successes).hasValue(1);
        assertThat(failures).hasValue(1);
    }

    @Test
    @DisplayName("null request 作为正常协议失败返回")
    void nullRequestReturnsFailure() {
        Cap cap = Cap.builder(SECRET).format1(1, 4, 1).build();

        assertFailure(cap.redeem(null), "invalid_body");
    }

    @Test
    @DisplayName("并发重复兑换恰有一个成功")
    void concurrentReplayHasExactlyOneWinner() throws Exception {
        Cap cap = Cap.builder(SECRET).format1(1, 4, 1).build();
        RedeemRequest request = solve((ChallengeResponse.Format1) cap.createChallenge());
        var executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<RedeemResult>> calls = new ArrayList<>();
            for (int index = 0; index < 16; index++) {
                calls.add(() -> cap.redeem(request));
            }
            List<RedeemResult> results =
                    executor.invokeAll(calls).stream()
                            .map(
                                    future -> {
                                        try {
                                            return future.get();
                                        } catch (Exception exception) {
                                            throw new IllegalStateException(exception);
                                        }
                                    })
                            .toList();

            assertThat(results).filteredOn(RedeemResult::success).hasSize(1);
            assertThat(results)
                    .filteredOn(result -> !result.success())
                    .allSatisfy(result -> assertFailure(result, "already_redeemed"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("STRICT 默认组合为 RSW 后 instrumentation 且可兑换")
    void strictDefaultShapeIsUsable() {
        RswKeyPair keyPair = RswKeyPair.generate(1024);
        Cap cap =
                Cap.builder(SECRET)
                        .profile(CapProfile.STRICT)
                        .rswKeyPair(keyPair)
                        .rswIterations(1)
                        .build();
        ChallengeResponse.Format2 challenge = (ChallengeResponse.Format2) cap.createChallenge();

        assertThat(challenge.challenges())
                .extracting(ChallengeResponse.ProtocolChallenge::protocol)
                .containsExactly("rsw", "instrumentation");
        Map<String, Object> rsw = challenge.challenges().get(0).payload();
        String y =
                new BigInteger((String) rsw.get("x"), 16)
                        .modPow(
                                BigInteger.TWO.pow(((Number) rsw.get("t")).intValue()),
                                new BigInteger((String) rsw.get("N"), 16))
                        .toString(16);
        Map<String, Object> instrumentation = format2InstrumentationSolution(challenge.token());

        RedeemResult result =
                cap.redeem(
                        new RedeemRequest(
                                challenge.token(),
                                List.of(Map.of("y", y), instrumentation),
                                null,
                                false,
                                false));

        assertThat(result).isInstanceOf(RedeemResult.Success.class);
    }

    @Test
    @DisplayName("STRICT 精确使用 2048 bits、t=75000、level=3 与自动化拦截")
    void strictUsesExactSecurityDefaults() throws IOException {
        Cap cap = Cap.builder(SECRET).profile(CapProfile.STRICT).build();

        ChallengeResponse.Format2 challenge = (ChallengeResponse.Format2) cap.createChallenge();
        Map<String, Object> rsw = challenge.challenges().get(0).payload();
        Map<String, Object> instrumentation = challenge.challenges().get(1).payload();
        Map<String, Object> instrMeta = format2InstrumentationMetadata(challenge.token());
        String script = inflate((String) instrumentation.get("blob"));

        assertThat((String) rsw.get("N")).hasSize(2048 / 4);
        assertThat(rsw.get("t")).isEqualTo(75_000);
        assertThat(instrMeta.get("blockAutomatedBrowsers")).isEqualTo(true);
        assertThat(script).startsWith("var _T").doesNotContain("\n");
    }

    @Test
    @DisplayName("STRICT 空协议列表按上游回退为 RSW")
    void strictEmptyProtocolsFallBackToRsw() {
        Cap cap =
                Cap.builder(SECRET)
                        .profile(CapProfile.STRICT)
                        .protocols()
                        .rswKeyPair(RswKeyPair.generate(1024))
                        .rswIterations(1)
                        .build();

        ChallengeResponse.Format2 challenge = (ChallengeResponse.Format2) cap.createChallenge();

        assertThat(challenge.challenges())
                .extracting(ChallengeResponse.ProtocolChallenge::protocol)
                .containsExactly("rsw");
    }

    @Test
    @DisplayName("signer 异常安全映射为固定失败码")
    void mapsTokenSignerFailureWithoutMessage() {
        Cap cap =
                Cap.builder(SECRET)
                        .format1(1, 4, 1)
                        .disableReplayProtection()
                        .tokenSigner(
                                (scope, expires, issuedAt) -> {
                                    throw new Exception("sensitive-signer-message");
                                })
                        .build();
        RedeemRequest request = solve((ChallengeResponse.Format1) cap.createChallenge());

        RedeemResult result = cap.redeem(request);

        assertFailure(result, "token_signer_error");
        assertThat(((RedeemResult.Failure) result).error()).isNull();
    }

    @Test
    @DisplayName("nonce TTL 为 challenge 剩余时间且业务 token TTL 从兑换时计算")
    void usesRemainingNonceTtlAndRedeemTimeTokenTtl() {
        List<Duration> nonceTtls = new ArrayList<>();
        Cap cap =
                Cap.builder(SECRET)
                        .format1(1, 4, 1)
                        .nonceConsumer(
                                (signature, ttl) -> {
                                    nonceTtls.add(ttl);
                                    return true;
                                })
                        .build();
        ChallengeResponse.Format1 challenge =
                (ChallengeResponse.Format1)
                        cap.createChallenge(
                                ChallengeOptions.builder().ttl(Duration.ofMinutes(1)).build());
        long before = System.currentTimeMillis();

        RedeemResult.Success result =
                (RedeemResult.Success)
                        cap.redeem(
                                solve(challenge),
                                RedeemOptions.builder().tokenTtl(Duration.ofSeconds(45)).build());
        long after = System.currentTimeMillis();

        assertThat(nonceTtls).singleElement().satisfies(ttl -> assertThat(ttl).isPositive());
        assertThat(nonceTtls.get(0)).isLessThanOrEqualTo(Duration.ofMinutes(1));
        assertThat(result.expires()).isBetween(before + 45_000, after + 45_000);
    }

    private static RedeemRequest solve(ChallengeResponse.Format1 response) {
        int tokenState = RandomUtil.fnv1a(response.token());
        List<Object> solutions = new ArrayList<>();
        for (int index = 0; index < response.challenge().c(); index++) {
            int saltState = RandomUtil.fnv1aResume(tokenState, Integer.toString(index + 1));
            int targetState = RandomUtil.fnv1aResume(saltState, "d");
            String salt = RandomUtil.prngFromHash(saltState, response.challenge().s());
            String target = RandomUtil.prngFromHash(targetState, response.challenge().d());
            solutions.add(solvePow(salt, target));
        }
        return new RedeemRequest(response.token(), solutions, null, false, false);
    }

    private static long solvePow(String salt, String target) {
        for (long nonce = 0; ; nonce++) {
            if (sha256Hex(salt + nonce).startsWith(target)) {
                return nonce;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> format2InstrumentationSolution(String token) {
        Map<String, Object> instrMeta = format2InstrumentationMetadata(token);
        List<String> vars = (List<String>) instrMeta.get("vars");
        List<Object> values = (List<Object>) instrMeta.get("expectedVals");
        Map<String, Object> state = new java.util.LinkedHashMap<>();
        for (int index = 0; index < vars.size(); index++) {
            state.put(vars.get(index), values.get(index));
        }
        return Map.of("instr", Map.of("i", instrMeta.get("id"), "state", state));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> format2InstrumentationMetadata(String token) {
        Map<String, Object> payload =
                (Map<String, Object>) (Map<?, ?>) new JwtCodec(SECRET).verify(token).orElseThrow();
        Map<String, Object> metadata =
                (Map<String, Object>)
                        (Map<?, ?>)
                                new EncryptedMetadataCodec(SECRET)
                                        .decryptFormat2((String) payload.get("ev"))
                                        .orElseThrow();
        List<Map<String, Object>> expected =
                (List<Map<String, Object>>) (List<?>) metadata.get("expected");
        return (Map<String, Object>) expected.get(1).get("instrMeta");
    }

    @SuppressWarnings("unchecked")
    private static RedeemRequest.InstrumentationResult format1InstrumentationResult(String token) {
        Map<String, Object> payload =
                (Map<String, Object>) (Map<?, ?>) new JwtCodec(SECRET).verify(token).orElseThrow();
        Map<String, Object> metadata =
                (Map<String, Object>)
                        (Map<?, ?>)
                                new EncryptedMetadataCodec(SECRET)
                                        .decryptFormat1((String) payload.get("ei"))
                                        .orElseThrow();
        List<String> vars = (List<String>) metadata.get("vars");
        List<Object> values = (List<Object>) metadata.get("expectedVals");
        Map<String, Object> state = new java.util.LinkedHashMap<>();
        for (int index = 0; index < vars.size(); index++) {
            state.put(vars.get(index), values.get(index));
        }
        return new RedeemRequest.InstrumentationResult(
                (String) metadata.get("id"), state, System.currentTimeMillis());
    }

    private static String inflate(String blob) throws IOException {
        Inflater inflater = new Inflater(true);
        try (InflaterInputStream input =
                new InflaterInputStream(
                        new ByteArrayInputStream(Base64.getDecoder().decode(blob)), inflater)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            inflater.end();
        }
    }

    private static String sha256Hex(String value) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            value.getBytes(
                                                    java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void assertFailure(RedeemResult result, String reason) {
        assertThat(result)
                .isEqualTo(
                        new RedeemResult.Failure(false, reason, reason.startsWith("instr_"), null));
    }
}

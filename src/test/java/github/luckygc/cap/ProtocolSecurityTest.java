package github.luckygc.cap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import github.luckygc.cap.utils.RandomUtil;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("公开协议安全回归测试")
class ProtocolSecurityTest {

    private static final String SECRET = "protocol-security-secret-0123456789";

    @Test
    @DisplayName("null 请求和 null、空、超长 token 安全失败")
    void rejectsMissingAndOversizedTokens() {
        Cap cap = format1Cap();

        assertFailure(cap.redeem(null), "invalid_body");
        assertThatNullPointerException()
                .isThrownBy(() -> new RedeemRequest(null, List.of(), null, false, false))
                .withMessage("token");
        assertFailure(
                cap.redeem(new RedeemRequest("", List.of(), null, false, false)), "missing_token");
        assertFailure(
                cap.redeem(new RedeemRequest("x".repeat(65_537), List.of(), null, false, false)),
                "invalid_token");
    }

    @Test
    @DisplayName("拒绝错误 JWT 段数、alg 和签名")
    void rejectsMalformedJwtHeaderAndSignature() {
        Cap cap = format1Cap();
        String payload = validFormat1Payload(future());
        List<String> invalidTokens =
                List.of(
                        "one.two",
                        "one.two.three.four",
                        signJwt("{\"alg\":\"none\",\"typ\":\"JWT\"}", payload),
                        signJwt("{\"alg\":\"HS512\",\"typ\":\"JWT\"}", payload),
                        tamperSignature(signJwt(canonicalHeader(), payload)));

        for (String token : invalidTokens) {
            assertFailure(
                    cap.redeem(new RedeemRequest(token, List.of(0), null, false, false)),
                    "invalid_token");
        }
    }

    @Test
    @DisplayName("拒绝被篡改的 Format 2 密文")
    void rejectsTamperedFormat2Ciphertext() {
        Cap cap = format2Cap();
        ChallengeResponse.Format2 challenge = (ChallengeResponse.Format2) cap.createChallenge();
        String payload = decodeSegment(challenge.token().split("\\.", -1)[1]);
        String tamperedPayload = tamperJsonStringField(payload, "ev");
        String tamperedToken = signJwt(canonicalHeader(), tamperedPayload);

        assertFailure(
                cap.redeem(
                        new RedeemRequest(
                                tamperedToken, List.of(Map.of("nonce", 0)), null, false, false)),
                "invalid_token");
    }

    @Test
    @DisplayName("拒绝过期和越界的 Format 1 token 参数")
    void rejectsExpiredAndOutOfRangeFormat1Claims() {
        Cap cap = format1Cap();
        long now = System.currentTimeMillis();

        assertFailure(redeemSignedFormat1(cap, validFormat1Payload(now - 1)), "expired");
        assertFailure(
                redeemSignedFormat1(cap, format1Payload(1_001, 4, 1, future(), now)),
                "invalid_token");
        assertFailure(
                redeemSignedFormat1(cap, format1Payload(1, 257, 1, future(), now)),
                "invalid_token");
        assertFailure(
                redeemSignedFormat1(cap, format1Payload(1, 4, 17, future(), now)), "invalid_token");
    }

    @Test
    @DisplayName("构建时拒绝越界的 Format 1 参数")
    void rejectsOutOfRangeConfiguredFormat1Parameters() {
        assertThatIllegalFormat1(1_001, 4, 1);
        assertThatIllegalFormat1(1, 257, 1);
        assertThatIllegalFormat1(1, 4, 17);
        assertThatIllegalFormat1(0, 4, 1);
        assertThatIllegalFormat1(1, 0, 1);
        assertThatIllegalFormat1(1, 4, 0);
    }

    @Test
    @DisplayName("Format 1 拒绝非 Number solution")
    void rejectsNonNumberFormat1Solution() {
        Cap cap = format1Cap();
        ChallengeResponse.Format1 challenge = (ChallengeResponse.Format1) cap.createChallenge();

        assertFailure(
                cap.redeem(new RedeemRequest(challenge.token(), List.of("1"), null, false, false)),
                "invalid_solutions");
    }

    @Test
    @DisplayName("Format 2 拒绝恶意 Map 和 List solution")
    void rejectsMaliciousFormat2Containers() {
        Cap cap = format2Cap();
        ChallengeResponse.Format2 challenge = (ChallengeResponse.Format2) cap.createChallenge();
        Map<String, Object> oversizedMap = new LinkedHashMap<>();
        for (int index = 0; index < 17; index++) {
            oversizedMap.put("key" + index, index);
        }

        assertFailure(
                cap.redeem(
                        new RedeemRequest(
                                challenge.token(), List.of(oversizedMap), null, false, false)),
                "invalid_solution");
        assertFailure(
                cap.redeem(
                        new RedeemRequest(
                                challenge.token(), List.of(List.of()), null, false, false)),
                "invalid_solution");
    }

    @Test
    @DisplayName("并发重复兑换恰有一个成功")
    void concurrentReplayHasExactlyOneWinner() throws Exception {
        Cap cap = format1Cap();
        RedeemRequest request = solve((ChallengeResponse.Format1) cap.createChallenge());
        int concurrency = 16;
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<RedeemResult>> calls = new ArrayList<>();
        for (int index = 0; index < concurrency; index++) {
            calls.add(
                    () -> {
                        ready.countDown();
                        if (!start.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException(
                                    "timed out waiting for concurrent start");
                        }
                        return cap.redeem(request);
                    });
        }

        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        try {
            List<Future<RedeemResult>> futures = calls.stream().map(executor::submit).toList();
            assertThat(ready.await(5, TimeUnit.SECONDS))
                    .as("all replay attempts reached the start barrier")
                    .isTrue();
            start.countDown();
            List<RedeemResult> results = new ArrayList<>(concurrency);
            for (Future<RedeemResult> future : futures) {
                results.add(future.get(5, TimeUnit.SECONDS));
            }

            assertThat(results).filteredOn(RedeemResult.Success.class::isInstance).hasSize(1);
            assertThat(results)
                    .filteredOn(RedeemResult.Failure.class::isInstance)
                    .extracting(result -> ((RedeemResult.Failure) result).reason())
                    .containsOnly("already_redeemed")
                    .hasSize(concurrency - 1);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @DisplayName("公开 failure 和 event 不泄露输入 token 或 secret")
    void failuresAndEventsDoNotExposeTokenOrSecret() {
        List<CapEventListener.FailureEvent> events = new ArrayList<>();
        String suppliedToken = "attacker-token-very-sensitive";
        Cap cap =
                Cap.builder(SECRET)
                        .format1(1, 4, 1)
                        .eventListener(
                                new CapEventListener() {
                                    @Override
                                    public void redeemFailed(FailureEvent event) {
                                        events.add(event);
                                    }
                                })
                        .build();

        RedeemResult result =
                cap.redeem(new RedeemRequest(suppliedToken, List.of(), null, false, false));

        assertFailure(result, "invalid_token");
        assertThat(result.toString()).doesNotContain(suppliedToken, SECRET);
        assertThat(events).singleElement();
        assertThat(events.get(0).toString()).doesNotContain(suppliedToken, SECRET);
    }

    private static Cap format1Cap() {
        return Cap.builder(SECRET).format1(1, 4, 1).build();
    }

    private static Cap format2Cap() {
        return Cap.builder(SECRET)
                .profile(CapProfile.STRICT)
                .protocols(CapProtocol.SHA256_POW)
                .format1(1, 4, 1)
                .build();
    }

    private static RedeemResult redeemSignedFormat1(Cap cap, String payload) {
        return cap.redeem(
                new RedeemRequest(
                        signJwt(canonicalHeader(), payload), List.of(0), null, false, false));
    }

    private static String validFormat1Payload(long expires) {
        return format1Payload(1, 4, 1, expires, System.currentTimeMillis());
    }

    private static String format1Payload(
            long count, long size, long difficulty, long exp, long iat) {
        return "{\"n\":\"nonce\",\"c\":"
                + count
                + ",\"s\":"
                + size
                + ",\"d\":"
                + difficulty
                + ",\"exp\":"
                + exp
                + ",\"iat\":"
                + iat
                + "}";
    }

    private static long future() {
        return System.currentTimeMillis() + Duration.ofMinutes(5).toMillis();
    }

    private static String canonicalHeader() {
        return "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
    }

    private static String signJwt(String header, String payload) {
        String signingInput = encodeSegment(header) + "." + encodeSegment(payload);
        return signingInput + "." + encodeBase64Url(hmacSha256(signingInput));
    }

    private static String tamperSignature(String token) {
        int signatureStart = token.lastIndexOf('.') + 1;
        char replacement = token.charAt(signatureStart) == 'A' ? 'B' : 'A';
        return token.substring(0, signatureStart)
                + replacement
                + token.substring(signatureStart + 1);
    }

    private static String tamperJsonStringField(String payload, String field) {
        String prefix = "\"" + field + "\":\"";
        int valueStart = payload.indexOf(prefix) + prefix.length();
        if (valueStart < prefix.length()) {
            throw new IllegalArgumentException("missing JSON field: " + field);
        }
        char replacement = payload.charAt(valueStart) == 'A' ? 'B' : 'A';
        return payload.substring(0, valueStart) + replacement + payload.substring(valueStart + 1);
    }

    private static String encodeSegment(String value) {
        return encodeBase64Url(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeSegment(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static String encodeBase64Url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static byte[] hmacSha256(String signingInput) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static RedeemRequest solve(ChallengeResponse.Format1 challenge) {
        int tokenState = RandomUtil.fnv1a(challenge.token());
        int saltState = RandomUtil.fnv1aResume(tokenState, "1");
        int targetState = RandomUtil.fnv1aResume(saltState, "d");
        String salt = RandomUtil.prngFromHash(saltState, challenge.challenge().s());
        String target = RandomUtil.prngFromHash(targetState, challenge.challenge().d());
        for (long nonce = 0; ; nonce++) {
            if (sha256Hex(salt + nonce).startsWith(target)) {
                return new RedeemRequest(challenge.token(), List.of(nonce), null, false, false);
            }
        }
    }

    private static String sha256Hex(String value) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void assertThatIllegalFormat1(int count, int size, int difficulty) {
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> Cap.builder(SECRET).format1(count, size, difficulty).build());
    }

    private static void assertFailure(RedeemResult result, String reason) {
        assertThat(result).isEqualTo(new RedeemResult.Failure(false, reason, false, null));
    }
}

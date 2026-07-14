package github.luckygc.cap.internal.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import github.luckygc.cap.ChallengeOptions;
import github.luckygc.cap.ChallengeResponse;
import github.luckygc.cap.RedeemRequest;
import github.luckygc.cap.internal.crypto.JwtCodec;
import github.luckygc.cap.internal.json.ProtocolJsonCodec;
import github.luckygc.cap.utils.RandomUtil;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Format 1 SHA-256 PoW 协议测试")
class Format1ProtocolTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final Instant NOW = Instant.parse("2025-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    @DisplayName("接受 capjs-core 0.1.1 固定 fixture")
    void acceptsUpstreamFixture() throws IOException {
        Map<String, @Nullable Object> fixture = fixture();
        @SuppressWarnings("unchecked")
        List<@Nullable Object> solutions = (List<@Nullable Object>) fixture.get("solutions");
        RedeemRequest request =
                new RedeemRequest((String) fixture.get("token"), solutions, null, false, false);

        Format1Protocol.ValidationResult result =
                new Format1Protocol(SECRET, 2, 8, 2, CLOCK, new FixedSecureRandom())
                        .validate(request, null);

        assertThat(result)
                .isEqualTo(
                        new Format1Protocol.Validated(
                                null,
                                1_735_689_600_000L,
                                4_102_444_800_000L,
                                (String) fixture.get("signatureHex")));
    }

    @Test
    @DisplayName("FNV resume 与 xorshift 精确匹配 fixture")
    void matchesUpstreamPrngVectors() throws IOException {
        Map<String, @Nullable Object> fixture = fixture();
        String token = (String) fixture.get("token");
        @SuppressWarnings("unchecked")
        List<Map<String, @Nullable Object>> entries =
                (List<Map<String, @Nullable Object>>) fixture.get("entries");
        int tokenState = RandomUtil.fnv1a(token);

        for (int index = 0; index < entries.size(); index++) {
            Map<String, @Nullable Object> entry = entries.get(index);
            int saltState = RandomUtil.fnv1aResume(tokenState, Integer.toString(index + 1));
            int targetState = RandomUtil.fnv1aResume(saltState, "d");
            assertThat(Integer.toUnsignedLong(saltState)).isEqualTo(entry.get("saltSeed"));
            assertThat(Integer.toUnsignedLong(targetState)).isEqualTo(entry.get("targetSeed"));
            assertThat(RandomUtil.prngFromHash(saltState, 8)).isEqualTo(entry.get("salt"));
            assertThat(RandomUtil.prngFromHash(targetState, 2)).isEqualTo(entry.get("target"));
        }
    }

    @Test
    @DisplayName("xorshift 多 block 拼接精确匹配上游向量")
    void matchesUpstreamMultiBlockPrngVector() throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, @Nullable Object> vector =
                (Map<String, @Nullable Object>) fixture().get("multiBlockPrng");

        assertThat(RandomUtil.prngFromHash((int) ((Long) vector.get("saltSeed")).longValue(), 32))
                .isEqualTo(vector.get("salt32"));
        assertThat(RandomUtil.prngFromHash((int) ((Long) vector.get("targetSeed")).longValue(), 16))
                .isEqualTo(vector.get("target16"));
    }

    @Test
    @DisplayName("生成默认字段、固定 nonce 与十分钟 TTL")
    void generatesDefaultChallenge() {
        Format1Protocol protocol =
                new Format1Protocol(SECRET, 50, 32, 4, CLOCK, new FixedSecureRandom());

        ChallengeResponse.Format1 response = protocol.generate(ChallengeOptions.defaults());
        Map<String, @Nullable Object> payload =
                new JwtCodec(SECRET).verify(response.token()).orElseThrow();

        assertThat(response.challenge()).isEqualTo(new ChallengeResponse.Challenge(50, 32, 4));
        assertThat(response.expires())
                .isEqualTo(NOW.toEpochMilli() + Duration.ofMinutes(10).toMillis());
        assertThat(response.instrumentation()).isNull();
        assertThat(payload)
                .containsEntry("n", "000102030405060708090a0b0c0d0e0f101112131415161718")
                .containsEntry("c", 50L)
                .containsEntry("s", 32L)
                .containsEntry("d", 4L)
                .containsEntry("iat", NOW.toEpochMilli())
                .containsEntry("exp", response.expires())
                .doesNotContainKeys("sk", "x");
    }

    @Test
    @DisplayName("生成边界参数并携带 scope、extra 与自定义 TTL")
    void generatesBoundaryParametersAndOptions() {
        Format1Protocol protocol =
                new Format1Protocol(SECRET, 1000, 256, 16, CLOCK, new FixedSecureRandom());
        ChallengeOptions options =
                ChallengeOptions.builder()
                        .scope("login")
                        .extra(Map.of("tenant", "alpha"))
                        .ttl(Duration.ofSeconds(30))
                        .build();

        ChallengeResponse.Format1 response = protocol.generate(options);
        Map<String, @Nullable Object> payload =
                new JwtCodec(SECRET).verify(response.token()).orElseThrow();

        assertThat(response.challenge()).isEqualTo(new ChallengeResponse.Challenge(1000, 256, 16));
        assertThat(response.expires()).isEqualTo(NOW.toEpochMilli() + 30_000);
        assertThat(payload)
                .containsEntry("sk", "login")
                .containsEntry("x", Map.of("tenant", "alpha"));
    }

    @Test
    @DisplayName("拒绝越界生成参数")
    void rejectsOutOfRangeGenerationParameters() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> protocol(0, 1, 1))
                .withMessageContaining("c");
        assertThatIllegalArgumentException().isThrownBy(() -> protocol(1, 257, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> protocol(1, 1, 17));
    }

    @Test
    @DisplayName("按固定顺序区分 body、token 与 solutions 错误")
    void validatesOuterInputInOrder() {
        Format1Protocol protocol = protocol(1, 4, 1);

        assertFailure(protocol.validateComponents(false, "x.y.z", List.of(), null), "invalid_body");
        assertFailure(protocol.validateComponents(true, null, null, null), "missing_token");
        assertFailure(protocol.validateComponents(true, "x.y.z", null, null), "missing_solutions");
        assertFailure(protocol.validateComponents(true, "x.y.z", List.of(), null), "invalid_token");
    }

    @Test
    @DisplayName("拒绝 scope 不匹配与过期 token")
    void rejectsScopeMismatchAndExpiry() {
        Format1Protocol protocol = protocol(1, 4, 1);
        String scoped =
                sign(
                        Map.of(
                                "n", "n", "c", 1, "s", 4, "d", 1, "exp", future(), "iat", now(),
                                "sk", "login"));
        String expired =
                sign(Map.of("n", "n", "c", 1, "s", 4, "d", 1, "exp", now() - 1, "iat", now()));

        assertFailure(
                protocol.validateComponents(true, scoped, List.of(0), "signup"), "scope_mismatch");
        assertFailure(protocol.validateComponents(true, expired, List.of(0), null), "expired");
    }

    @Test
    @DisplayName("拒绝 token 中缺失、非整数与越界的协议参数")
    void rejectsInvalidTokenParameters() {
        Format1Protocol protocol = protocol(1, 4, 1);
        List<Map<String, Object>> payloads =
                List.of(
                        Map.of("c", 1, "s", 4, "d", 1, "iat", now()),
                        Map.of("c", 1, "s", 4, "d", 1, "exp", future()),
                        Map.of(
                                "c", 1,
                                "s", 4,
                                "d", 1,
                                "exp", future(),
                                "iat", now(),
                                "sk", 1),
                        Map.of("c", 1.0, "s", 4, "d", 1, "exp", future(), "iat", now()),
                        Map.of("c", 1001, "s", 4, "d", 1, "exp", future(), "iat", now()),
                        Map.of("c", 1, "s", 0, "d", 1, "exp", future(), "iat", now()),
                        Map.of("c", 1, "s", 4, "d", 17, "exp", future(), "iat", now()));

        for (Map<String, Object> payload : payloads) {
            assertFailure(
                    protocol.validateComponents(true, sign(payload), List.of(0), null),
                    payload.containsKey("exp") ? "invalid_token" : "expired");
        }
    }

    @Test
    @DisplayName("拒绝 solution 数量错误并接受所有有限 Number 类型")
    void rejectsInvalidSolutionShape() {
        Format1Protocol protocol = protocol(2, 8, 2);
        String token = validToken(2, 8, 2);

        assertFailure(
                protocol.validateComponents(true, token, List.of(1), null), "invalid_solutions");
        assertFailure(
                protocol.validateComponents(true, token, List.of(1, 1.0), null),
                "invalid_solution");
    }

    @Test
    @DisplayName("接受上游验证成功的超出安全整数范围解答")
    void acceptsUpstreamLargeIntegerSolutions() throws IOException {
        Map<String, @Nullable Object> fixture = fixture();
        @SuppressWarnings("unchecked")
        List<@Nullable Object> fixtureSolutions =
                (List<@Nullable Object>) fixture.get("largeSolutions");
        List<@Nullable Object> solutions =
                fixtureSolutions.stream()
                        .map(value -> (Object) BigInteger.valueOf(((Number) value).longValue()))
                        .toList();
        RedeemRequest request =
                new RedeemRequest((String) fixture.get("token"), solutions, null, false, false);

        Format1Protocol.ValidationResult result = protocol(2, 8, 2).validate(request, null);

        assertThat(result)
                .isEqualTo(
                        new Format1Protocol.Validated(
                                null,
                                1_735_689_600_000L,
                                4_102_444_800_000L,
                                (String) fixture.get("signatureHex")));
    }

    @Test
    @DisplayName("Format 1 按 JavaScript Number 字符串验证小数解答")
    void acceptsFractionalJavaScriptNumberSolution() {
        Format1Protocol protocol = protocol(1, 4, 1);
        String token = validToken(1, 4, 1);
        BigDecimal solution = fractionalSolution(token, 4, 1);

        Format1Protocol.ValidationResult result =
                protocol.validateComponents(true, token, List.of(solution), null);

        assertThat(result).isInstanceOf(Format1Protocol.Validated.class);
    }

    @Test
    @DisplayName("Format 1 先按 binary64 舍入再使用 JavaScript Number 字符串")
    void roundsArbitraryPrecisionNumbersLikeJsonParse() {
        Format1Protocol protocol = protocol(1, 4, 1);
        String token = validToken(1, 4, 1);
        BigInteger solution = largeRoundedSolution(token, 4, 1);

        Format1Protocol.ValidationResult result =
                protocol.validateComponents(true, token, List.of(solution), null);

        assertThat(result).isInstanceOf(Format1Protocol.Validated.class);
    }

    @Test
    @DisplayName("Format 1 拒绝非有限 Number")
    void rejectsNonFiniteNumbers() {
        Format1Protocol protocol = protocol(1, 4, 1);
        String token = validToken(1, 4, 1);

        assertFailure(
                protocol.validateComponents(true, token, List.of(Double.NaN), null),
                "invalid_solutions");
        assertFailure(
                protocol.validateComponents(true, token, List.of(Double.POSITIVE_INFINITY), null),
                "invalid_solutions");
    }

    @Test
    @DisplayName("exp 等于当前毫秒时仍然有效")
    void acceptsExpirationEqualToCurrentTime() throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, @Nullable Object> boundary =
                (Map<String, @Nullable Object>) fixture().get("expirationBoundary");
        RedeemRequest request =
                new RedeemRequest(
                        (String) boundary.get("token"),
                        List.of(boundary.get("solution")),
                        null,
                        false,
                        false);

        Format1Protocol.ValidationResult result = protocol(1, 4, 1).validate(request, null);

        assertThat(result)
                .isEqualTo(
                        new Format1Protocol.Validated(
                                null,
                                (Long) boundary.get("iat"),
                                (Long) boundary.get("exp"),
                                (String) boundary.get("signatureHex")));
    }

    @Test
    @DisplayName("拒绝 SHA-256 前缀不匹配")
    void rejectsInvalidProofOfWorkPrefix() throws IOException {
        Map<String, @Nullable Object> fixture = fixture();

        assertFailure(
                protocol(2, 8, 2)
                        .validateComponents(true, fixture.get("token"), List.of(301L, 446L), null),
                "invalid_solution");
    }

    @Test
    @DisplayName("拒绝被篡改的 token")
    void rejectsTamperedToken() throws IOException {
        Map<String, @Nullable Object> fixture = fixture();
        String token = (String) fixture.get("token");
        String tampered =
                token.substring(0, token.length() - 1) + (token.endsWith("A") ? "B" : "A");

        assertFailure(
                protocol(2, 8, 2).validateComponents(true, tampered, List.of(301, 445), null),
                "invalid_token");
    }

    private static Format1Protocol protocol(int count, int size, int difficulty) {
        return new Format1Protocol(SECRET, count, size, difficulty, CLOCK, new FixedSecureRandom());
    }

    private static String validToken(int count, int size, int difficulty) {
        return sign(
                Map.of(
                        "n",
                        "n",
                        "c",
                        count,
                        "s",
                        size,
                        "d",
                        difficulty,
                        "exp",
                        future(),
                        "iat",
                        now()));
    }

    private static BigDecimal fractionalSolution(String token, int size, int difficulty) {
        String salt = salt(token, size);
        String target = target(token, difficulty);
        for (long integer = 0; integer < 1_000_000; integer++) {
            BigDecimal candidate = new BigDecimal(integer + ".5");
            if (Format1Protocol.sha256Hex(salt + candidate).startsWith(target)) {
                return candidate;
            }
        }
        throw new AssertionError("fractional solution not found");
    }

    private static BigInteger largeRoundedSolution(String token, int size, int difficulty) {
        String salt = salt(token, size);
        String target = target(token, difficulty);
        BigInteger base = BigInteger.valueOf(9_007_199_254_740_992L);
        for (long offset = 1; offset < 1_000_000; offset += 2) {
            BigInteger candidate = base.add(BigInteger.valueOf(offset));
            String jsText = Format2Protocol.jsNumberToString(candidate);
            if (Format1Protocol.sha256Hex(salt + jsText).startsWith(target)) {
                return candidate;
            }
        }
        throw new AssertionError("large rounded solution not found");
    }

    private static String salt(String token, int size) {
        int tokenState = RandomUtil.fnv1a(token);
        int saltState = RandomUtil.fnv1aResume(tokenState, "1");
        return RandomUtil.prngFromHash(saltState, size);
    }

    private static String target(String token, int difficulty) {
        int tokenState = RandomUtil.fnv1a(token);
        int saltState = RandomUtil.fnv1aResume(tokenState, "1");
        int targetState = RandomUtil.fnv1aResume(saltState, "d");
        return RandomUtil.prngFromHash(targetState, difficulty);
    }

    private static String sign(Map<String, @Nullable Object> payload) {
        return new JwtCodec(SECRET).sign(payload);
    }

    private static long now() {
        return NOW.toEpochMilli();
    }

    private static long future() {
        return now() + 60_000;
    }

    private static void assertFailure(Format1Protocol.ValidationResult result, String reason) {
        assertThat(result).isEqualTo(new ProtocolFailure(reason, false, null));
    }

    private static Map<String, @Nullable Object> fixture() throws IOException {
        try (InputStream input =
                Format1ProtocolTest.class.getResourceAsStream(
                        "/fixtures/capjs-core-0.1.1/format1.json")) {
            assertThat(input).isNotNull();
            return new ProtocolJsonCodec().readObject(input.readAllBytes());
        }
    }

    private static final class FixedSecureRandom extends SecureRandom {

        @Override
        public void nextBytes(byte[] bytes) {
            for (int index = 0; index < bytes.length; index++) {
                bytes[index] = (byte) index;
            }
        }
    }
}

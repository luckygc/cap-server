package github.luckygc.cap.internal.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import github.luckygc.cap.CapProtocol;
import github.luckygc.cap.ChallengeOptions;
import github.luckygc.cap.ChallengeResponse;
import github.luckygc.cap.InstrumentationOptions;
import github.luckygc.cap.RedeemRequest;
import github.luckygc.cap.RswKeyPair;
import github.luckygc.cap.internal.crypto.EncryptedMetadataCodec;
import github.luckygc.cap.internal.crypto.JwtCodec;
import github.luckygc.cap.internal.json.ProtocolJsonCodec;
import github.luckygc.cap.internal.rsw.RswSupport;
import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Format 2 多协议组合测试")
class Format2ProtocolTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final Instant NOW = Instant.ofEpochMilli(1_700_000_000_000L);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    @DisplayName("完整验证上游三协议 token 与 solutions")
    void acceptsCompleteUpstreamFixture() throws IOException {
        Map<String, @Nullable Object> fixture = fixture();
        Format2Protocol.ValidationResult result = protocol().validate(request(fixture), "login");

        assertThat(result)
                .isEqualTo(
                        new Format2Protocol.Validated(
                                "login",
                                1_700_000_000_000L,
                                1_700_000_600_000L,
                                (String) fixture.get("signatureHex")));
    }

    @Test
    @DisplayName("上游 challenge 顺序与公开 payload 精确匹配 fixture")
    void fixtureLocksUpstreamChallengeWire() throws IOException {
        Map<String, @Nullable Object> fixture = fixture();
        @SuppressWarnings("unchecked")
        Map<String, @Nullable Object> generated =
                (Map<String, @Nullable Object>) fixture.get("generated");
        @SuppressWarnings("unchecked")
        List<Map<String, @Nullable Object>> challenges =
                (List<Map<String, @Nullable Object>>) generated.get("challenges");

        assertThat(challenges)
                .extracting(value -> value.get("protocol"))
                .containsExactly("sha256-pow", "rsw", "instrumentation");
        assertThat(challenges.get(0).get("payload"))
                .isEqualTo(Map.of("salt", "0724415e", "target", "0"));
        @SuppressWarnings("unchecked")
        Map<String, @Nullable Object> rswPayload =
                (Map<String, @Nullable Object>) challenges.get(1).get("payload");
        @SuppressWarnings("unchecked")
        Map<String, @Nullable Object> instrumentationPayload =
                (Map<String, @Nullable Object>) challenges.get(2).get("payload");
        assertThat(rswPayload).containsKeys("N", "x", "t").doesNotContainKey("y");
        assertThat(instrumentationPayload).containsOnlyKeys("blob");
    }

    @Test
    @DisplayName("生成时保持协议顺序并仅在密文中保存 expected")
    void generatesOrderedChallengesWithoutLeakingExpectedValues() {
        Format2Protocol protocol = protocol();
        ChallengeOptions options =
                ChallengeOptions.builder()
                        .scope("login")
                        .extra(Map.of("tenant", "alpha"))
                        .ttl(Duration.ofMinutes(10))
                        .build();

        ChallengeResponse.Format2 response = protocol.generate(options);
        Map<String, @Nullable Object> payload =
                new JwtCodec(SECRET).verify(response.token()).orElseThrow();

        assertThat(response.format()).isEqualTo(2);
        assertThat(response.challenges())
                .extracting(ChallengeResponse.ProtocolChallenge::protocol)
                .containsExactly("sha256-pow", "rsw", "instrumentation");
        assertThat(response.challenges().get(0).payload()).containsOnlyKeys("salt", "target");
        assertThat(response.challenges().get(1).payload())
                .containsOnlyKeys("N", "x", "t")
                .doesNotContainKey("y");
        assertThat(response.challenges().get(2).payload()).containsOnlyKeys("blob");
        assertThat(response.toString()).doesNotContain("expectedVals").doesNotContain("state");
        assertThat(payload)
                .containsEntry("f", 2L)
                .containsEntry("iat", NOW.toEpochMilli())
                .containsEntry("exp", NOW.toEpochMilli() + 600_000)
                .containsEntry("sk", "login")
                .containsEntry("x", Map.of("tenant", "alpha"))
                .containsKeys("n", "ev");
        assertThat((String) payload.get("n")).matches("[0-9a-f]{32}");

        Map<String, @Nullable Object> metadata =
                new EncryptedMetadataCodec(SECRET)
                        .decryptFormat2((String) payload.get("ev"))
                        .orElseThrow();
        assertThat((List<?>) metadata.get("expected")).hasSize(3);
    }

    @Test
    @DisplayName("拒绝空、重复或缺少依赖的协议列表")
    void rejectsInvalidProtocolConfiguration() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> protocol(List.of()))
                .withMessageContaining("protocols");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> protocol(List.of(CapProtocol.RSW, CapProtocol.RSW)))
                .withMessageContaining("duplicate");
        assertThatNullPointerException()
                .isThrownBy(() -> protocol(Arrays.asList(CapProtocol.RSW, null)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> protocol(List.of(CapProtocol.RSW), null))
                .withMessageContaining("RSW");
    }

    @Test
    @DisplayName("未启用 SHA 时不校验其闲置参数")
    void ignoresUnusedPowParameters() {
        Format2Protocol protocol =
                new Format2Protocol(
                        SECRET,
                        List.of(CapProtocol.RSW),
                        0,
                        0,
                        0,
                        minter(),
                        InstrumentationOptions.defaults(),
                        CLOCK,
                        new FixedSecureRandom());

        assertThat(protocol.generate(ChallengeOptions.defaults()).challenges())
                .extracting(ChallengeResponse.ProtocolChallenge::protocol)
                .containsExactly("rsw");
    }

    @Test
    @DisplayName("拒绝 solution 顺序错误、缺项、null 与未知 expected protocol")
    void rejectsWrongOrderMissingNullAndUnknownProtocol() throws IOException {
        Map<String, @Nullable Object> fixture = fixture();
        RedeemRequest valid = request(fixture);
        List<@Nullable Object> swapped = new ArrayList<>(valid.solutions());
        Object first = swapped.get(0);
        swapped.set(0, swapped.get(1));
        swapped.set(1, first);

        assertFailure(
                protocol()
                        .validate(
                                new RedeemRequest(valid.token(), swapped, null, false, false),
                                "login"),
                "invalid_solution");
        assertFailure(
                protocol()
                        .validate(
                                new RedeemRequest(
                                        valid.token(),
                                        valid.solutions().subList(0, 2),
                                        null,
                                        false,
                                        false),
                                "login"),
                "invalid_solutions");
        List<@Nullable Object> withNull = new ArrayList<>(valid.solutions());
        withNull.set(0, null);
        assertFailure(
                protocol()
                        .validate(
                                new RedeemRequest(valid.token(), withNull, null, false, false),
                                "login"),
                "invalid_solution");

        Map<String, @Nullable Object> expected =
                Map.of("expected", List.of(Map.of("protocol", "unknown")));
        assertFailure(
                protocol()
                        .validate(
                                request(
                                        sign(expected, future(), now(), "login"),
                                        List.of(Map.of())),
                                "login"),
                "invalid_solution");
    }

    @Test
    @DisplayName("拒绝错误加密域、scope、过期及 claim 类型")
    void rejectsWrongEncryptionDomainScopeExpiryAndClaimTypes() {
        Map<String, @Nullable Object> expected =
                Map.of("expected", List.of(Map.of("protocol", "rsw", "y", "ab")));
        String wrongDomainEv = new EncryptedMetadataCodec(SECRET).encryptFormat1(expected);
        String wrongDomainToken =
                token(Map.of("f", 2, "n", "n", "exp", future(), "iat", now(), "ev", wrongDomainEv));

        assertFailure(
                protocol().validate(request(wrongDomainToken, List.of(Map.of("y", "ab"))), null),
                "invalid_token");
        assertFailure(
                protocol()
                        .validate(
                                request(
                                        sign(expected, future(), now(), "login"),
                                        List.of(Map.of("y", "ab"))),
                                "signup"),
                "scope_mismatch");
        assertFailure(
                protocol()
                        .validate(
                                request(
                                        sign(expected, now() - 1, now(), null),
                                        List.of(Map.of("y", "ab"))),
                                null),
                "expired");

        List<Map<String, Object>> invalidClaims =
                List.of(
                        Map.of("f", 1, "n", "n", "exp", future(), "iat", now(), "ev", "x"),
                        Map.of("f", 2, "n", 1, "exp", future(), "iat", now(), "ev", "x"),
                        Map.of("f", 2, "n", "n", "exp", 1.5, "iat", now(), "ev", "x"),
                        Map.of("f", 2, "n", "n", "exp", future(), "iat", "bad", "ev", "x"),
                        Map.of("f", 2, "n", "n", "exp", future(), "iat", now(), "ev", 1),
                        Map.of(
                                "f", 2, "n", "n", "exp", future(), "iat", now(), "ev", "x", "sk",
                                1));
        for (Map<String, Object> claims : invalidClaims) {
            assertFailure(
                    protocol().validate(request(token(claims), List.of()), null), "invalid_token");
        }
    }

    @Test
    @DisplayName("SHA nonce 接受整数和字符串并拒绝其他类型或错误解答")
    void validatesShaSolutions() {
        Map<String, @Nullable Object> expected =
                Map.of(
                        "expected",
                        List.of(Map.of("protocol", "sha256-pow", "salt", "salt", "target", "0")));
        long nonce = solve("salt", "0");
        String token = sign(expected, future(), now(), null);

        assertThat(protocol().validate(request(token, List.of(Map.of("nonce", nonce))), null))
                .isInstanceOf(Format2Protocol.Validated.class);
        assertThat(
                        protocol()
                                .validate(
                                        request(
                                                token,
                                                List.of(Map.of("nonce", Long.toString(nonce)))),
                                        null))
                .isInstanceOf(Format2Protocol.Validated.class);
        assertFailure(
                protocol().validate(request(token, List.of(Map.of("nonce", 1.5))), null),
                "invalid_solution");
        assertFailure(
                protocol().validate(request(token, List.of(Map.of("nonce", nonce + 1))), null),
                "invalid_solution");
    }

    @Test
    @DisplayName("RSW y 必须为字符串且按上游规范比较")
    void validatesRswSolutions() {
        Map<String, @Nullable Object> expected =
                Map.of("expected", List.of(Map.of("protocol", "rsw", "y", "000aB")));
        String token = sign(expected, future(), now(), null);

        assertThat(protocol().validate(request(token, List.of(Map.of("y", "0xAB"))), null))
                .isInstanceOf(Format2Protocol.Validated.class);
        assertFailure(
                protocol().validate(request(token, List.of(Map.of("y", 171))), null),
                "invalid_solution");
        assertFailure(
                protocol().validate(request(token, List.of(Map.of("y", "ac"))), null),
                "invalid_solution");
    }

    @Test
    @DisplayName("拒绝超出协议边界的 expected 与 solution Map")
    void rejectsOversizedProtocolMaps() {
        Map<String, @Nullable Object> oversizedExpected = new LinkedHashMap<>();
        oversizedExpected.put("protocol", "rsw");
        oversizedExpected.put("y", "ab");
        Map<String, @Nullable Object> oversizedSolution = new LinkedHashMap<>();
        oversizedSolution.put("y", "ab");
        for (int index = 0; index < 16; index++) {
            oversizedExpected.put("extra" + index, index);
            oversizedSolution.put("extra" + index, index);
        }

        String oversizedExpectedToken =
                sign(Map.of("expected", List.of(oversizedExpected)), future(), now(), null);
        String normalToken =
                sign(
                        Map.of("expected", List.of(Map.of("protocol", "rsw", "y", "ab"))),
                        future(),
                        now(),
                        null);

        assertFailure(
                protocol()
                        .validate(
                                request(oversizedExpectedToken, List.of(Map.of("y", "ab"))), null),
                "invalid_solution");
        assertFailure(
                protocol().validate(request(normalToken, List.of(oversizedSolution)), null),
                "invalid_solution");
    }

    @Test
    @DisplayName("instrumentation 按 blocked、timeout、instr 顺序返回上游 reason")
    void validatesInstrumentationSignalsAndFailures() throws IOException {
        Map<String, @Nullable Object> fixture = fixture();
        @SuppressWarnings("unchecked")
        Map<String, @Nullable Object> metadata =
                (Map<String, @Nullable Object>) fixture.get("expectedMetadata");
        @SuppressWarnings("unchecked")
        List<Map<String, @Nullable Object>> expected =
                (List<Map<String, @Nullable Object>>) metadata.get("expected");
        Map<String, @Nullable Object> onlyInstrumentation =
                Map.of("expected", List.of(expected.get(2)));
        String token = sign(onlyInstrumentation, future(), now(), null);
        @SuppressWarnings("unchecked")
        Map<String, @Nullable Object> fixtureSolution =
                (Map<String, @Nullable Object>) request(fixture).solutions().get(2);

        assertFailure(
                protocol()
                        .validate(
                                request(token, List.of(Map.of("blocked", true, "timeout", true))),
                                null),
                "instr_automated_browser",
                true);
        assertFailure(
                protocol()
                        .validate(
                                request(
                                        token,
                                        List.of(
                                                Map.of(
                                                        "timeout",
                                                        true,
                                                        "instr",
                                                        fixtureSolution.get("instr")))),
                                null),
                "instr_timeout",
                true);
        assertThat(protocol().validate(request(token, List.of(fixtureSolution)), null))
                .isInstanceOf(Format2Protocol.Validated.class);
        assertFailure(
                protocol().validate(request(token, List.of(Map.of())), null),
                "instr_missing",
                true);
        assertFailure(
                protocol()
                        .validate(
                                request(
                                        token,
                                        List.of(
                                                Map.of(
                                                        "instr",
                                                        Map.of("i", "bad", "state", Map.of())))),
                                null),
                "id_mismatch",
                true);
    }

    private static Format2Protocol protocol() {
        return protocol(
                List.of(CapProtocol.SHA256_POW, CapProtocol.RSW, CapProtocol.INSTRUMENTATION));
    }

    private static Format2Protocol protocol(List<CapProtocol> protocols) {
        return protocol(protocols, minter());
    }

    private static Format2Protocol protocol(
            List<CapProtocol> protocols, RswSupport.@Nullable RswMinter minter) {
        return new Format2Protocol(
                SECRET,
                protocols,
                1,
                4,
                1,
                minter,
                InstrumentationOptions.builder().blockAutomatedBrowsers(true).build(),
                CLOCK,
                new FixedSecureRandom());
    }

    private static RswSupport.RswMinter minter() {
        try {
            Map<String, @Nullable Object> fixture = fixture();
            @SuppressWarnings("unchecked")
            Map<String, @Nullable Object> keypair =
                    (Map<String, @Nullable Object>) fixture.get("keypair");
            RswKeyPair pair =
                    new RswKeyPair(
                            ((Number) keypair.get("bits")).intValue(),
                            (String) keypair.get("N"),
                            (String) keypair.get("p"),
                            (String) keypair.get("q"));
            return RswSupport.createMinter(pair, 8);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static RedeemRequest request(Map<String, @Nullable Object> fixture) {
        @SuppressWarnings("unchecked")
        Map<String, @Nullable Object> generated =
                (Map<String, @Nullable Object>) fixture.get("generated");
        @SuppressWarnings("unchecked")
        List<@Nullable Object> solutions = (List<@Nullable Object>) fixture.get("solutions");
        return request((String) generated.get("token"), solutions);
    }

    private static RedeemRequest request(String token, List<@Nullable Object> solutions) {
        return new RedeemRequest(token, solutions, null, false, false);
    }

    private static String sign(
            Map<String, @Nullable Object> expected,
            long expires,
            long issuedAt,
            @Nullable String scope) {
        Map<String, @Nullable Object> claims = new LinkedHashMap<>();
        claims.put("f", 2);
        claims.put("n", "00112233445566778899aabbccddeeff");
        claims.put("exp", expires);
        claims.put("iat", issuedAt);
        claims.put("ev", new EncryptedMetadataCodec(SECRET).encryptFormat2(expected));
        if (scope != null) {
            claims.put("sk", scope);
        }
        return token(claims);
    }

    private static String token(Map<String, ?> claims) {
        Map<String, @Nullable Object> copy = new LinkedHashMap<>();
        copy.putAll(claims);
        return new JwtCodec(SECRET).sign(copy);
    }

    private static long solve(String salt, String target) {
        for (long nonce = 0; ; nonce++) {
            try {
                byte[] hash =
                        java.security.MessageDigest.getInstance("SHA-256")
                                .digest(
                                        (salt + nonce)
                                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                if (java.util.HexFormat.of().formatHex(hash).startsWith(target)) {
                    return nonce;
                }
            } catch (java.security.GeneralSecurityException exception) {
                throw new IllegalStateException(exception);
            }
        }
    }

    private static long now() {
        return NOW.toEpochMilli();
    }

    private static long future() {
        return NOW.toEpochMilli() + 600_000;
    }

    private static Map<String, @Nullable Object> fixture() throws IOException {
        try (InputStream input =
                Format2ProtocolTest.class.getResourceAsStream(
                        "/fixtures/capjs-core-0.1.1/format2.json")) {
            if (input == null) {
                throw new IOException("Format 2 fixture 不存在");
            }
            return new ProtocolJsonCodec().readObject(input.readAllBytes());
        }
    }

    private static void assertFailure(Format2Protocol.ValidationResult result, String reason) {
        assertFailure(result, reason, false);
    }

    private static void assertFailure(
            Format2Protocol.ValidationResult result, String reason, boolean instrError) {
        assertThat(result).isEqualTo(new ProtocolFailure(reason, instrError, null));
    }

    private static final class FixedSecureRandom extends SecureRandom {

        private int next;

        @Override
        public void nextBytes(byte[] bytes) {
            for (int index = 0; index < bytes.length; index++) {
                bytes[index] = (byte) next++;
            }
        }

        @Override
        public int nextInt(int origin, int bound) {
            return origin;
        }
    }
}

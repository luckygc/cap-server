package github.luckygc.cap.internal.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

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
import java.math.BigInteger;
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
    @DisplayName("确定性 Java 三协议生成逐字段匹配静态 fixture")
    void matchesDeterministicJavaGenerationFixture() throws IOException {
        assertThat(javaGenerationOracle()).isEqualTo(javaFixture());
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
    @DisplayName("空协议回退 RSW、重复保序并拒绝 null")
    void appliesUpstreamProtocolConfigurationSemantics() {
        assertThat(protocol(List.of()).generate(ChallengeOptions.defaults()).challenges())
                .extracting(ChallengeResponse.ProtocolChallenge::protocol)
                .containsExactly("rsw");
        assertThat(
                        protocol(
                                        List.of(
                                                CapProtocol.RSW,
                                                CapProtocol.RSW,
                                                CapProtocol.RSW,
                                                CapProtocol.RSW))
                                .generate(ChallengeOptions.defaults())
                                .challenges())
                .extracting(ChallengeResponse.ProtocolChallenge::protocol)
                .containsExactly("rsw", "rsw", "rsw", "rsw");
        assertThatIllegalArgumentException().isThrownBy(() -> protocol(null));
        assertThatIllegalArgumentException()
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
    @DisplayName("SHA nonce 接受 Number 和字符串并拒绝错误解答")
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
                protocol().validate(request(token, List.of(Map.of("nonce", nonce + 1))), null),
                "invalid_solution");
    }

    @Test
    @DisplayName("NumberToString 精确匹配 JavaScript 边界语义")
    void formatsJavaNumbersLikeJavaScript() {
        assertThat(Format2Protocol.jsNumberToString(1.0)).isEqualTo("1");
        assertThat(Format2Protocol.jsNumberToString(-0.0)).isEqualTo("0");
        assertThat(Format2Protocol.jsNumberToString(1.5)).isEqualTo("1.5");
        assertThat(Format2Protocol.jsNumberToString(1e20)).isEqualTo("100000000000000000000");
        assertThat(Format2Protocol.jsNumberToString(1e21)).isEqualTo("1e+21");
        assertThat(Format2Protocol.jsNumberToString(1e-6)).isEqualTo("0.000001");
        assertThat(Format2Protocol.jsNumberToString(1e-7)).isEqualTo("1e-7");
        assertThat(Format2Protocol.jsNumberToString(Double.MIN_VALUE)).isEqualTo("5e-324");
        assertThat(Format2Protocol.jsNumberToString(1_000_000_000_000_000_128L))
                .isEqualTo("1000000000000000100");
        assertThat(Format2Protocol.jsNumberToString(9_007_199_254_740_993L))
                .isEqualTo("9007199254740992");
        assertThat(Format2Protocol.jsNumberToString(new BigInteger("900719925474099299999")))
                .isEqualTo("900719925474099300000");
        assertThat(Format2Protocol.jsNumberToString(Double.NaN)).isEqualTo("NaN");
        assertThat(Format2Protocol.jsNumberToString(Double.POSITIVE_INFINITY))
                .isEqualTo("Infinity");
        assertThat(Format2Protocol.jsNumberToString(Double.NEGATIVE_INFINITY))
                .isEqualTo("-Infinity");
    }

    @Test
    @DisplayName("SHA Number nonce、大小写和奇数 nibble 匹配上游 crypto oracle")
    void validatesUpstreamNumberNonceAndHexPrefixVectors() throws IOException {
        @SuppressWarnings("unchecked")
        List<Map<String, @Nullable Object>> vectors =
                (List<Map<String, @Nullable Object>>) fixture().get("numberNonceVectors");

        for (Map<String, @Nullable Object> vector : vectors) {
            Map<String, @Nullable Object> expected =
                    Map.of(
                            "expected",
                            List.of(
                                    Map.of(
                                            "protocol",
                                            "sha256-pow",
                                            "salt",
                                            vector.get("salt"),
                                            "target",
                                            vector.get("target"))));
            String token = sign(expected, future(), now(), null);

            assertThat(
                            protocol()
                                    .validate(
                                            request(
                                                    token,
                                                    List.of(Map.of("nonce", vector.get("value")))),
                                            null))
                    .as((String) vector.get("label"))
                    .isInstanceOf(Format2Protocol.Validated.class);
        }
    }

    @Test
    @DisplayName("拒绝非法或超过 SHA-256 宽度的 target")
    void rejectsInvalidHexTargets() {
        for (String target : List.of("g", "0g", "-1", "0".repeat(65))) {
            Map<String, @Nullable Object> expected =
                    Map.of(
                            "expected",
                            List.of(
                                    Map.of(
                                            "protocol",
                                            "sha256-pow",
                                            "salt",
                                            "salt",
                                            "target",
                                            target)));
            assertFailure(
                    protocol()
                            .validate(
                                    request(
                                            sign(expected, future(), now(), null),
                                            List.of(Map.of("nonce", 1))),
                                    null),
                    "invalid_solution");
        }

        String asciiNibble = Format1Protocol.sha256Hex("salt1").substring(0, 1);
        char ascii = asciiNibble.charAt(0);
        String fullWidth =
                Character.toString(ascii <= '9' ? '\uff10' + ascii - '0' : '\uff41' + ascii - 'a');
        Map<String, @Nullable Object> expected =
                Map.of(
                        "expected",
                        List.of(
                                Map.of(
                                        "protocol",
                                        "sha256-pow",
                                        "salt",
                                        "salt",
                                        "target",
                                        fullWidth)));
        assertFailure(
                protocol()
                        .validate(
                                request(
                                        sign(expected, future(), now(), null),
                                        List.of(Map.of("nonce", 1))),
                                null),
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
        Map<String, @Nullable Object> instrEntry = expected.get(2);
        Map<String, @Nullable Object> onlyInstrumentation = Map.of("expected", List.of(instrEntry));
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
        assertFailure(
                protocol()
                        .validate(
                                request(token, List.of(Map.of("blocked", "yes", "timeout", true))),
                                null),
                "instr_timeout",
                true);
        assertFailure(
                protocol().validate(request(token, List.of(Map.of("instr", "scalar"))), null),
                "missing_output",
                true);
        assertFailure(
                protocol().validate(request(token, List.of(Map.of("instr", List.of()))), null),
                "id_mismatch",
                true);
        for (Object falsey : List.of(false, 0, 0.0, "")) {
            assertFailure(
                    protocol().validate(request(token, List.of(Map.of("instr", falsey))), null),
                    "instr_missing",
                    true);
        }
        assertThat(
                        protocol()
                                .validate(
                                        request(
                                                token,
                                                List.of(
                                                        Map.of(
                                                                "blocked",
                                                                "yes",
                                                                "timeout",
                                                                "no",
                                                                "instr",
                                                                fixtureSolution.get("instr")))),
                                        null))
                .isInstanceOf(Format2Protocol.Validated.class);

        @SuppressWarnings("unchecked")
        Map<String, @Nullable Object> instrMeta =
                new LinkedHashMap<>((Map<String, @Nullable Object>) instrEntry.get("instrMeta"));
        instrMeta.put("blockAutomatedBrowsers", false);
        Map<String, @Nullable Object> nonBlockingEntry = new LinkedHashMap<>(instrEntry);
        nonBlockingEntry.put("instrMeta", instrMeta);
        String nonBlockingToken =
                sign(Map.of("expected", List.of(nonBlockingEntry)), future(), now(), null);
        assertThat(
                        protocol()
                                .validate(
                                        request(nonBlockingToken, List.of(Map.of("blocked", true))),
                                        null))
                .isInstanceOf(Format2Protocol.Validated.class);
    }

    @Test
    @DisplayName("JavaScript truthiness 仅把规定的标量视为 false")
    void matchesJavaScriptTruthinessForInstrumentationDispatch() {
        assertThat(Format2Protocol.jsTruthy(null)).isFalse();
        assertThat(Format2Protocol.jsTruthy(false)).isFalse();
        assertThat(Format2Protocol.jsTruthy(0)).isFalse();
        assertThat(Format2Protocol.jsTruthy(-0.0)).isFalse();
        assertThat(Format2Protocol.jsTruthy(Double.NaN)).isFalse();
        assertThat(Format2Protocol.jsTruthy("")).isFalse();
        assertThat(Format2Protocol.jsTruthy(true)).isTrue();
        assertThat(Format2Protocol.jsTruthy(1)).isTrue();
        assertThat(Format2Protocol.jsTruthy("0")).isTrue();
        assertThat(Format2Protocol.jsTruthy(Map.of())).isTrue();
        assertThat(Format2Protocol.jsTruthy(List.of())).isTrue();
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
        return RswSupport.createMinter(keyPair(), 8);
    }

    private static RswKeyPair keyPair() {
        try {
            Map<String, @Nullable Object> fixture = fixture();
            @SuppressWarnings("unchecked")
            Map<String, @Nullable Object> keypair =
                    (Map<String, @Nullable Object>) fixture.get("keypair");
            return new RswKeyPair(
                    ((Number) keypair.get("bits")).intValue(),
                    (String) keypair.get("N"),
                    (String) keypair.get("p"),
                    (String) keypair.get("q"));
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Map<String, @Nullable Object> javaGenerationOracle() {
        Format2Protocol protocol =
                new Format2Protocol(
                        SECRET,
                        List.of(
                                CapProtocol.SHA256_POW,
                                CapProtocol.RSW,
                                CapProtocol.INSTRUMENTATION),
                        1,
                        4,
                        1,
                        RswSupport.createMinter(keyPair(), 8, new OracleSecureRandom(0x13579bdfL)),
                        InstrumentationOptions.builder()
                                .level(1)
                                .blockAutomatedBrowsers(true)
                                .build(),
                        CLOCK,
                        new OracleSecureRandom(0x2468ace0L));
        ChallengeOptions options =
                ChallengeOptions.builder()
                        .scope("login")
                        .extra(Map.of("tenant", "java-oracle"))
                        .ttl(Duration.ofMinutes(10))
                        .build();
        ChallengeResponse.Format2 response = protocol.generate(options);
        Map<String, @Nullable Object> payload =
                new JwtCodec(SECRET).verify(response.token()).orElseThrow();
        Map<String, @Nullable Object> metadata =
                new EncryptedMetadataCodec(SECRET)
                        .decryptFormat2((String) payload.get("ev"))
                        .orElseThrow();
        @SuppressWarnings("unchecked")
        List<Map<String, @Nullable Object>> expected =
                (List<Map<String, @Nullable Object>>) metadata.get("expected");
        List<@Nullable Object> solutions = new ArrayList<>();
        for (Map<String, @Nullable Object> entry : expected) {
            switch ((String) entry.get("protocol")) {
                case "sha256-pow" ->
                        solutions.add(
                                Map.of(
                                        "nonce",
                                        solve(
                                                (String) entry.get("salt"),
                                                (String) entry.get("target"))));
                case "rsw" -> solutions.add(Map.of("y", entry.get("y")));
                case "instrumentation" -> {
                    @SuppressWarnings("unchecked")
                    Map<String, @Nullable Object> meta =
                            (Map<String, @Nullable Object>) entry.get("instrMeta");
                    @SuppressWarnings("unchecked")
                    List<String> variables = (List<String>) meta.get("vars");
                    @SuppressWarnings("unchecked")
                    List<@Nullable Object> values =
                            (List<@Nullable Object>) meta.get("expectedVals");
                    Map<String, @Nullable Object> state = new LinkedHashMap<>();
                    for (int index = 0; index < variables.size(); index++) {
                        state.put(variables.get(index), values.get(index));
                    }
                    solutions.add(
                            Map.of(
                                    "instr",
                                    Map.of("i", meta.get("id"), "state", state, "ts", now() + 1)));
                }
                default -> throw new AssertionError("unexpected protocol");
            }
        }
        Format2Protocol.Validated validated =
                (Format2Protocol.Validated)
                        protocol.validate(request(response.token(), solutions), "login");
        Map<String, @Nullable Object> generated = new LinkedHashMap<>();
        generated.put("format", response.format());
        generated.put(
                "challenges",
                response.challenges().stream()
                        .map(
                                challenge ->
                                        Map.of(
                                                "protocol", challenge.protocol(),
                                                "payload", challenge.payload()))
                        .toList());
        generated.put("token", response.token());
        generated.put("expires", response.expires());
        Map<String, @Nullable Object> oracle = new LinkedHashMap<>();
        oracle.put(
                "source",
                Map.of(
                        "generator",
                        "Format2ProtocolTest.javaGenerationOracle",
                        "clock",
                        now(),
                        "secret",
                        SECRET));
        oracle.put("generated", generated);
        oracle.put("solutions", solutions);
        oracle.put(
                "validated",
                Map.of(
                        "scope", validated.scope(),
                        "iat", validated.issuedAt(),
                        "exp", validated.expires(),
                        "signatureHex", validated.signatureHex()));
        ProtocolJsonCodec codec = new ProtocolJsonCodec();
        return codec.readObject(codec.writeObject(oracle));
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

    private static Map<String, @Nullable Object> javaFixture() throws IOException {
        try (InputStream input =
                Format2ProtocolTest.class.getResourceAsStream(
                        "/fixtures/capjs-core-0.1.1/format2-java.json")) {
            if (input == null) {
                throw new IOException("Java Format 2 fixture 不存在");
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

    private static final class OracleSecureRandom extends SecureRandom {

        private int byteValue;
        private long state;

        private OracleSecureRandom(long seed) {
            state = seed;
        }

        @Override
        public void nextBytes(byte[] bytes) {
            for (int index = 0; index < bytes.length; index++) {
                bytes[index] = (byte) ((byteValue++ * 29 + 7) & 0xff);
            }
        }

        @Override
        public double nextDouble() {
            return nextUnsigned() / (double) 0x1_0000_0000L;
        }

        @Override
        public int nextInt(int origin, int bound) {
            return origin + (int) (nextUnsigned() % (bound - origin));
        }

        private long nextUnsigned() {
            state = (state * 1_664_525L + 1_013_904_223L) & 0xffff_ffffL;
            return state;
        }
    }
}

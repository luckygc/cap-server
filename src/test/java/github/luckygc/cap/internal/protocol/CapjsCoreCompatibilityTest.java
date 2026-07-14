package github.luckygc.cap.internal.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import github.luckygc.cap.CapProtocol;
import github.luckygc.cap.InstrumentationOptions;
import github.luckygc.cap.RedeemRequest;
import github.luckygc.cap.internal.json.ProtocolJsonCodec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("capjs-core 0.1.1 生成 fixture 兼容性")
class CapjsCoreCompatibilityTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final String RESOURCE_ROOT = "fixtures/capjs-core-0.1.1/generated/";
    private static final ProtocolJsonCodec JSON = new ProtocolJsonCodec();

    @Test
    @DisplayName("Java 接受上游生成的 Format 1")
    void acceptsGeneratedFormat1() throws IOException {
        Map<String, @Nullable Object> fixture = fixture("format1.json");
        assertMetadata(fixture, "format1");

        Format1Protocol.ValidationResult result =
                format1(fixture).validate(request(fixture, false), "login");

        assertThat(result).isInstanceOf(Format1Protocol.Validated.class);
    }

    @Test
    @DisplayName("Java 接受上游生成的 Format 1 instrumentation 并匹配 blocked oracle")
    void acceptsGeneratedFormat1Instrumentation() throws IOException {
        Map<String, @Nullable Object> fixture = fixture("format1-instrumentation.json");
        assertMetadata(fixture, "format1-instrumentation");
        Format1Protocol protocol = format1(fixture);

        assertThat(protocol.validate(request(fixture, false), "login"))
                .isInstanceOf(Format1Protocol.Validated.class);
        ProtocolFailure blocked =
                (ProtocolFailure) protocol.validate(request(fixture, true), "login");
        assertThat(blocked.reason()).isEqualTo(oracleReason(fixture));
        assertThat(blocked.instrError()).isTrue();
    }

    @Test
    @DisplayName("Java 接受上游生成的 Format 2")
    void acceptsGeneratedFormat2() throws IOException {
        Map<String, @Nullable Object> fixture = fixture("format2.json");
        assertMetadata(fixture, "format2");
        Clock clock = fixtureClock(fixture);
        Format2Protocol protocol =
                new Format2Protocol(
                        SECRET,
                        List.of(CapProtocol.SHA256_POW),
                        1,
                        4,
                        1,
                        null,
                        InstrumentationOptions.defaults(),
                        clock,
                        new SecureRandom());

        assertThat(protocol.validate(request(fixture, false), "login"))
                .isInstanceOf(Format2Protocol.Validated.class);
        ProtocolFailure blocked =
                (ProtocolFailure) protocol.validate(request(fixture, true), "login");
        assertThat(blocked.reason()).isEqualTo(oracleReason(fixture));
        assertThat(blocked.instrError()).isTrue();
    }

    @Test
    @DisplayName("Java NumberToString 匹配 Node 随机 binary64 oracle")
    void matchesNodeBinary64Strings() throws IOException {
        Map<String, @Nullable Object> fixture = fixture("number-string-vectors.json");
        assertMetadata(fixture, "number-string-vectors");
        @SuppressWarnings("unchecked")
        List<Map<String, @Nullable Object>> vectors =
                (List<Map<String, @Nullable Object>>) fixture.get("vectors");

        assertThat(fixture)
                .containsEntry(
                        "algorithm",
                        "xorshift64 with uint64 truncation after each step; Node String(number)");
        assertThat(vectors).hasSize(500);
        assertThat(vectors.stream().map(vector -> vector.get("bits"))).doesNotHaveDuplicates();
        Set<String> boundaryLabels =
                vectors.stream()
                        .limit(8)
                        .map(vector -> (String) vector.get("label"))
                        .collect(Collectors.toSet());
        assertThat(boundaryLabels)
                .containsExactlyInAnyOrder(
                        "negative-zero",
                        "plain-upper-bound",
                        "scientific-upper-bound",
                        "plain-lower-bound",
                        "scientific-lower-bound",
                        "minimum-subnormal",
                        "maximum-finite",
                        "shortest-large-integer");

        for (Map<String, @Nullable Object> vector : vectors) {
            long bits = Long.parseUnsignedLong((String) vector.get("bits"), 16);
            double value = Double.longBitsToDouble(bits);
            assertThat(Format2Protocol.jsNumberToString(value))
                    .as((String) vector.get("label"))
                    .isEqualTo(vector.get("jsString"));
        }
    }

    private static Format1Protocol format1(Map<String, @Nullable Object> fixture) {
        return new Format1Protocol(
                SECRET,
                1,
                4,
                1,
                InstrumentationOptions.defaults(),
                fixtureClock(fixture),
                new SecureRandom());
    }

    private static Clock fixtureClock(Map<String, @Nullable Object> fixture) {
        return Clock.fixed(Instant.ofEpochMilli((Long) fixture.get("now")), ZoneOffset.UTC);
    }

    private static void assertMetadata(Map<String, @Nullable Object> fixture, String kind) {
        assertThat(fixture)
                .containsEntry(
                        "schema", "https://github.com/luckygc/cap-server/fixtures/capjs-core-v1")
                .containsEntry("schemaVersion", 1L)
                .containsEntry("kind", kind);
        @SuppressWarnings("unchecked")
        Map<String, @Nullable Object> source =
                (Map<String, @Nullable Object>) fixture.get("source");
        assertThat(source)
                .containsEntry("package", "capjs-core")
                .containsEntry("semanticReferenceCommit", "f9ffadb");
        @SuppressWarnings("unchecked")
        Map<String, @Nullable Object> npm = (Map<String, @Nullable Object>) source.get("npm");
        assertThat(npm)
                .containsEntry("version", "0.1.1")
                .containsEntry(
                        "resolved", "https://registry.npmjs.org/capjs-core/-/capjs-core-0.1.1.tgz")
                .containsEntry(
                        "integrity",
                        "sha512-I5ZAsG6avdMFs3RxEbNFj9VggWMV6JEUIUvKFCOLR2Q9plxrEe+i4515ejtkCP6nkyE8b75L81ygjYZKmugWMg==");
        @SuppressWarnings("unchecked")
        Map<String, @Nullable Object> filesSha256 =
                (Map<String, @Nullable Object>) npm.get("filesSha256");
        assertThat(filesSha256)
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of(
                                "crypto.js",
                                "a7cdbe4fc286475d1279edfdf4ef5a2377949f795b2ec83a45514acc23539f17",
                                "index.js",
                                "05b3ea7b00d29af72e2ccb7f0770e4b78a99835c8cad4af175315e9d3319e1bc",
                                "instrumentation.js",
                                "73c7ab9f4b89dd30036ae361ec5ec3992dc8600508009e49f935a782408fb970",
                                "prng.js",
                                "62603a23e7d6c6538e65cea26b9e8abd5eaac967f73968de2734dd3c03cb0ed2",
                                "rsw.js",
                                "200f91cd42677377d214e48b0cd476dfe599fdae93f13e8dbea5631617ca1477"));
    }

    private static String oracleReason(Map<String, @Nullable Object> fixture) {
        @SuppressWarnings("unchecked")
        Map<String, @Nullable Object> oracle =
                (Map<String, @Nullable Object>) fixture.get("blockedOracle");
        assertThat(oracle).containsEntry("success", false).containsEntry("instr_error", true);
        return (String) oracle.get("reason");
    }

    private static RedeemRequest request(
            Map<String, @Nullable Object> fixture, boolean blockedOracle) {
        @SuppressWarnings("unchecked")
        Map<String, @Nullable Object> wire = (Map<String, @Nullable Object>) fixture.get("request");
        @SuppressWarnings("unchecked")
        List<@Nullable Object> solutions = (List<@Nullable Object>) wire.get("solutions");
        RedeemRequest.InstrumentationResult instrumentation = instrumentation(wire.get("instr"));
        if (!blockedOracle) {
            return new RedeemRequest(
                    (String) wire.get("token"), solutions, instrumentation, false, false);
        }
        if (fixture.get("kind").equals("format2")) {
            @SuppressWarnings("unchecked")
            List<@Nullable Object> blockedSolutions =
                    (List<@Nullable Object>) fixture.get("blockedSolutions");
            return new RedeemRequest(
                    (String) wire.get("token"), blockedSolutions, null, false, false);
        }
        return new RedeemRequest((String) wire.get("token"), solutions, null, true, false);
    }

    private static RedeemRequest.@Nullable InstrumentationResult instrumentation(
            @Nullable Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return null;
        }
        @SuppressWarnings("unchecked")
        Map<String, @Nullable Object> map = (Map<String, @Nullable Object>) raw;
        @SuppressWarnings("unchecked")
        Map<String, Object> state = (Map<String, Object>) map.get("state");
        return new RedeemRequest.InstrumentationResult(
                (String) map.get("i"), state, (Long) map.get("ts"));
    }

    private static Map<String, @Nullable Object> fixture(String name) throws IOException {
        String externalDirectory = System.getProperty("cap.fixture.dir");
        if (externalDirectory != null) {
            return JSON.readObject(Files.readAllBytes(Path.of(externalDirectory, name)));
        }
        try (InputStream input =
                CapjsCoreCompatibilityTest.class
                        .getClassLoader()
                        .getResourceAsStream(RESOURCE_ROOT + name)) {
            if (input == null) {
                throw new IOException("fixture 不存在: " + RESOURCE_ROOT + name);
            }
            return JSON.readObject(input.readAllBytes());
        }
    }
}

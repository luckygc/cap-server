package github.luckygc.cap.internal.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import github.luckygc.cap.InstrumentationOptions;
import github.luckygc.cap.internal.json.ProtocolJsonCodec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Instrumentation 生成测试")
class InstrumentationGeneratorTest {

    private static final Instant NOW = Instant.ofEpochMilli(1_700_000_000_000L);

    @Test
    @DisplayName("fixture 由真实上游生成且保存原始脚本")
    void fixtureComesFromUpstreamGenerator() throws IOException {
        Map<String, Object> fixture = readFixture();

        assertThat(fixture.get("id")).isEqualTo("0724415e7b98b5d2ef0c294663809dba");
        assertThat(fixture.get("expires")).isEqualTo(1_700_000_300_000L);
        assertThat(fixture.get("expectedVals"))
                .isEqualTo(List.of(494_910L, 243_196L, 714_463L, 691_575L));
        assertThat(inflate((String) fixture.get("instrumentation")))
                .isEqualTo(fixture.get("script"));
        assertThat(fixture.get("script").toString())
                .contains("parent.postMessage", "document.createElement", "EventTarget")
                .contains("node:internal", "leakHashes", "leak2");
        assertThat(fixture.get("source").toString())
                .contains("version=capjs-core 0.1.1", "commit=f9ffadb");
    }

    @Test
    @DisplayName("确定随机源生成四变量、20 次运算和 raw Deflate 标准 Base64")
    void generatesDynamicChallengeWithInjectableRandomness() throws IOException {
        InstrumentationGenerator generator = generator(17);

        InstrumentationGenerator.GeneratedInstrumentation generated =
                generator.generate(InstrumentationOptions.builder().level(0).build());
        String script = inflate(generated.instrumentation());

        assertThat(generated.id()).matches("[0-9a-f]{32}");
        assertThat(generated.expires()).isEqualTo(NOW.toEpochMilli() + 300_000);
        assertThat(generated.vars())
                .hasSize(4)
                .allMatch(value -> value.matches("[a-z][a-z0-9]{11}"));
        assertThat(generated.expectedVals())
                .hasSize(4)
                .allMatch(value -> value >= 100_000 && value < 1_000_000);
        assertThat(generated.vars()).allSatisfy(value -> assertThat(script).contains(value));
        assertThat(script)
                .contains(generated.id(), "parent.postMessage", "type: 'cap:instr'")
                .contains("document.createElement('div')", "innerText", "parseInt")
                .contains("HTMLElement", "Window", "Document", "Navigator", "EventTarget")
                .contains("node:internal", "moduleEvaluation", "leakHashes", "leak2");
        assertThat(Base64.getDecoder().decode(generated.instrumentation())).isNotEmpty();

        InstrumentationGenerator.GeneratedInstrumentation repeated =
                generator(17).generate(InstrumentationOptions.builder().level(0).build());
        assertThat(repeated).isEqualTo(generated);
    }

    @Test
    @DisplayName("启用自动化拦截时加入上游检查和 blocked wire")
    void addsAutomatedBrowserChecksOnlyWhenEnabled() throws IOException {
        String disabled =
                inflate(
                        generator(91)
                                .generate(InstrumentationOptions.builder().level(0).build())
                                .instrumentation());
        String enabled =
                inflate(
                        generator(91)
                                .generate(
                                        InstrumentationOptions.builder()
                                                .level(0)
                                                .blockAutomatedBrowsers(true)
                                                .build())
                                .instrumentation());

        assertThat(disabled).doesNotContain("blocked:true");
        assertThat(enabled)
                .contains("blocked: true", "getOwnPropertyDescriptors(navigator)")
                .contains("Object.getOwnPropertyNames(window)")
                .doesNotContain("navigator.plugins.length === 0")
                .doesNotContain("navigator.languages.length === 0");
        assertThat(enabled.length()).isGreaterThan(disabled.length());
    }

    @Test
    @DisplayName("内置 level 0 到 3 转换保留协议关键语法")
    void builtInTransformerPreservesGeneratedScript() throws IOException {
        for (int level = 0; level <= 3; level++) {
            InstrumentationOptions options = InstrumentationOptions.builder().level(level).build();
            String script = inflate(generator(41).generate(options).instrumentation());

            assertThat(script)
                    .contains("postMessage", "cap:instr", "Date.now()", "/\\(native:/")
                    .contains("new Function", "CustomEvent");
            if (level >= 2) {
                assertThat(script).contains("var _T");
            }
        }
    }

    @Test
    @DisplayName("自定义 transformer 异常、null 和超大输出安全失败")
    void rejectsUnsafeTransformerResults() {
        assertThatIllegalStateException()
                .isThrownBy(
                        () ->
                                generator(1)
                                        .generate(
                                                InstrumentationOptions.builder()
                                                        .transformer(
                                                                (script, level) -> {
                                                                    throw new RuntimeException();
                                                                })
                                                        .build()));
        assertThatIllegalStateException()
                .isThrownBy(
                        () ->
                                generator(1)
                                        .generate(
                                                InstrumentationOptions.builder()
                                                        .transformer((script, level) -> null)
                                                        .build()));
        assertThatIllegalStateException()
                .isThrownBy(
                        () ->
                                generator(1)
                                        .generate(
                                                InstrumentationOptions.builder()
                                                        .transformer(
                                                                (script, level) ->
                                                                        "x".repeat(262_145))
                                                        .build()));
    }

    @Test
    @DisplayName("TTL 仅接受正值且不超过一天")
    void boundsTtl() {
        InstrumentationGenerator generator = generator(3);

        assertThatIllegalArgumentException()
                .isThrownBy(
                        () -> generator.generate(InstrumentationOptions.defaults(), Duration.ZERO));
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                generator.generate(
                                        InstrumentationOptions.defaults(),
                                        Duration.ofDays(1).plusMillis(1)));
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                generator.generate(
                                        InstrumentationOptions.defaults(), Duration.ofNanos(1)));
    }

    private static InstrumentationGenerator generator(long seed) {
        return new InstrumentationGenerator(
                new DeterministicSecureRandom(seed), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static String inflate(String encoded) throws IOException {
        byte[] compressed = Base64.getDecoder().decode(encoded);
        try (InflaterInputStream input =
                        new InflaterInputStream(
                                new java.io.ByteArrayInputStream(compressed), new Inflater(true));
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            input.transferTo(output);
            return output.toString(StandardCharsets.UTF_8);
        }
    }

    private static Map<String, Object> readFixture() throws IOException {
        try (InputStream input =
                InstrumentationGeneratorTest.class.getResourceAsStream(
                        "/fixtures/capjs-core-0.1.1/instrumentation.json")) {
            assertThat(input).isNotNull();
            return new ProtocolJsonCodec().readObject(input.readAllBytes());
        }
    }

    private static final class DeterministicSecureRandom extends SecureRandom {

        private long state;

        private DeterministicSecureRandom(long seed) {
            state = seed;
        }

        @Override
        public void nextBytes(byte[] bytes) {
            for (int index = 0; index < bytes.length; index++) {
                bytes[index] = (byte) nextInt(256);
            }
        }

        @Override
        public int nextInt(int bound) {
            state = state * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L;
            return (int) Long.remainderUnsigned(state, bound);
        }

        @Override
        public double nextDouble() {
            return Integer.toUnsignedLong(nextInt(Integer.MAX_VALUE)) / (double) Integer.MAX_VALUE;
        }
    }
}

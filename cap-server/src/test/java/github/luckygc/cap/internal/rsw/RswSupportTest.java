package github.luckygc.cap.internal.rsw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import github.luckygc.cap.RswKeyPair;
import github.luckygc.cap.internal.json.ProtocolJsonCodec;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RSW minter 测试")
class RswSupportTest {

    @Test
    @DisplayName("精确匹配 capjs-core 0.1.1 CRT fixture")
    void matchesUpstreamCrtFixture() throws IOException {
        Map<String, @Nullable Object> fixture = fixture();
        RswKeyPair keyPair = keyPair(fixture);

        RswSupport.RswMinter minter =
                RswSupport.createMinter(
                        keyPair, ((Long) fixture.get("t")).intValue(), new IndexedSecureRandom());
        RswSupport.MintedChallenge minted = minter.mint();

        assertThat(minter.generator()).isEqualTo(new BigInteger((String) fixture.get("g")));
        assertThat(minter.exponentP()).isEqualTo(new BigInteger((String) fixture.get("eP")));
        assertThat(minter.exponentQ()).isEqualTo(new BigInteger((String) fixture.get("eQ")));
        assertThat(minter.h()).isEqualTo(new BigInteger((String) fixture.get("h")));
        assertThat(minted.modulus()).isEqualTo(fixture.get("NHex"));
        assertThat(minted.x()).isEqualTo(fixture.get("xHex"));
        assertThat(minted.y()).isEqualTo(fixture.get("yHex"));
        assertThat(minted.t()).isEqualTo(((Long) fixture.get("t")).intValue());
    }

    @Test
    @DisplayName("每次 mint 采样无符号 256 位 r")
    void samplesUnsigned256BitRForEveryMint() throws IOException {
        Map<String, @Nullable Object> fixture = fixture();
        CountingSecureRandom random = new CountingSecureRandom();
        RswSupport.RswMinter minter = RswSupport.createMinter(keyPair(fixture), 1, random);

        minter.mint();
        minter.mint();

        assertThat(random.requestedLengths()).containsExactly(128, 32, 32);
    }

    @Test
    @DisplayName("N x y 使用 modulus 固定宽度小写十六进制")
    void padsOutputToModulusWidth() throws IOException {
        RswSupport.RswMinter minter =
                RswSupport.createMinter(keyPair(fixture()), 1, new ZeroSecureRandom());

        RswSupport.MintedChallenge minted = minter.mint();

        assertThat(minted.modulus()).hasSize(256).matches("[0-9a-f]+$");
        assertThat(minted.x()).hasSize(256).endsWith("01").matches("[0-9a-f]+$");
        assertThat(minted.y()).hasSize(256).endsWith("01").matches("[0-9a-f]+$");
    }

    @Test
    @DisplayName("拒绝超过 modulus 固定宽度的值")
    void rejectsValuesWiderThanModulus() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RswSupport.fixedHex(BigInteger.ONE.shiftLeft(1024), 128));
    }

    @Test
    @DisplayName("默认 t 为 75000 并拒绝越界值")
    void validatesSquaringCount() throws IOException {
        RswKeyPair keyPair = keyPair(fixture());

        assertThat(RswSupport.createMinter(keyPair).t()).isEqualTo(75_000);
        assertThatIllegalArgumentException().isThrownBy(() -> RswSupport.createMinter(keyPair, 0));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RswSupport.createMinter(keyPair, 10_000_001));
    }

    @Test
    @DisplayName("验证 y 时消除 0x 大小写与前导零歧义")
    void normalizesClaimedSolution() throws IOException {
        String expected = (String) fixture().get("yHex");

        assertThat(RswSupport.verifySolution(expected, "0x000" + expected.toUpperCase())).isTrue();
        assertThat(RswSupport.verifySolution(expected, "000" + expected)).isTrue();
        assertThat(RswSupport.verifySolution(expected, expected + "0")).isFalse();
        assertThat(RswSupport.verifySolution(expected, "not-hex")).isFalse();
        assertThat(RswSupport.verifySolution(expected, "0X" + expected)).isFalse();
    }

    @Test
    @DisplayName("在规范化分配前限制 claimed y 长度")
    void limitsClaimedSolutionBeforeNormalization() throws IOException {
        String expected = (String) fixture().get("yHex");
        String boundary = "0x" + "0".repeat(expected.length()) + expected;
        String tooLong = "0x" + "0".repeat(expected.length() + 1) + expected;

        assertThat(boundary).hasSize(expected.length() * 2 + 2);
        assertThat(RswSupport.verifySolution(expected, boundary)).isTrue();
        assertThat(RswSupport.verifySolution(expected, tooLong)).isFalse();
        assertThat(RswSupport.verifySolution(expected, "0".repeat(10_000) + expected)).isFalse();
    }

    private static RswKeyPair keyPair(Map<String, @Nullable Object> fixture) {
        return new RswKeyPair(
                ((Long) fixture.get("bits")).intValue(),
                (String) fixture.get("N"),
                (String) fixture.get("p"),
                (String) fixture.get("q"));
    }

    private static Map<String, @Nullable Object> fixture() throws IOException {
        try (InputStream input =
                RswSupportTest.class.getResourceAsStream("/fixtures/capjs-core-0.1.1/rsw.json")) {
            assertThat(input).isNotNull();
            return new ProtocolJsonCodec().readObject(input.readAllBytes());
        }
    }

    private static final class IndexedSecureRandom extends SecureRandom {

        @Override
        public void nextBytes(byte[] bytes) {
            for (int index = 0; index < bytes.length; index++) {
                bytes[index] = (byte) index;
            }
        }
    }

    private static final class ZeroSecureRandom extends SecureRandom {

        @Override
        public void nextBytes(byte[] bytes) {
            java.util.Arrays.fill(bytes, (byte) 0);
        }
    }

    private static final class CountingSecureRandom extends SecureRandom {

        private final java.util.List<Integer> requestedLengths = new java.util.ArrayList<>();

        @Override
        public void nextBytes(byte[] bytes) {
            requestedLengths.add(bytes.length);
            java.util.Arrays.fill(bytes, (byte) 1);
        }

        java.util.List<Integer> requestedLengths() {
            return requestedLengths;
        }
    }
}

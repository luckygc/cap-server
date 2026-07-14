package github.luckygc.cap.internal.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("HS256 JWT 测试")
class JwtCodecTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    private final JwtCodec jwt = new JwtCodec(SECRET);

    @Test
    @DisplayName("签名并验证 HS256 payload")
    void roundTripsHs256Payload() {
        assertThat(jwt.verify(jwt.sign(Map.of("exp", 123L)))).hasValue(Map.of("exp", 123L));
    }

    @Test
    @DisplayName("拒绝被篡改的签名")
    void rejectsTamperedSignature() {
        String token = jwt.sign(Map.of("exp", 123L));
        char replacement = token.charAt(token.length() - 1) == 'A' ? 'B' : 'A';
        String tampered = token.substring(0, token.length() - 1) + replacement;

        assertThat(jwt.verify(tampered)).isEmpty();
    }

    @Test
    @DisplayName("拒绝 none、未知字段与非规范 Base64URL")
    void rejectsInvalidHeadersAndBase64Url() {
        String noneHeader = base64Url("{\"alg\":\"none\",\"typ\":\"JWT\"}");
        String extraHeader = base64Url("{\"alg\":\"HS256\",\"typ\":\"JWT\",\"kid\":\"x\"}");
        String payload = base64Url("{\"exp\":123}");

        assertThat(jwt.verify(noneHeader + "." + payload + ".x")).isEmpty();
        assertThat(jwt.verify(extraHeader + "." + payload + ".x")).isEmpty();
        assertThat(jwt.verify(jwt.sign(Map.of("exp", 123L)) + "=")).isEmpty();
    }

    @Test
    @DisplayName("拒绝错误 header 类型与重复字段")
    void rejectsWrongHeaderTypesAndDuplicateFields() {
        assertThat(jwt.verify(signWithHeader("{\"alg\":123,\"typ\":\"JWT\"}"))).isEmpty();
        assertThat(
                        jwt.verify(
                                signWithHeader(
                                        "{\"alg\":\"HS256\",\"alg\":\"HS256\",\"typ\":\"JWT\"}")))
                .isEmpty();
    }

    @Test
    @DisplayName("拒绝非 32 字节签名")
    void rejectsWrongSignatureLengths() {
        String token = jwt.sign(Map.of("exp", 123L));
        int lastDot = token.lastIndexOf('.');
        String prefix = token.substring(0, lastDot + 1);

        assertThat(jwt.verify(prefix + base64Url(new byte[31]))).isEmpty();
        assertThat(jwt.verify(prefix + base64Url(new byte[33]))).isEmpty();
    }

    @Test
    @DisplayName("拒绝任何非 ASCII JWT 输入")
    void rejectsNonAsciiInput() {
        String token = jwt.sign(Map.of("exp", 123L));

        assertThat(jwt.verify(token.substring(0, token.length() - 1) + "é")).isEmpty();
        assertThat(jwt.verify("é".repeat(40_000))).isEmpty();
    }

    @Test
    @DisplayName("拒绝错误段数与超过 64 KiB 的 token")
    void rejectsInvalidStructureAndSize() {
        assertThat(jwt.verify("a.b")).isEmpty();
        assertThat(jwt.verify("a.b.c.d")).isEmpty();
        assertThat(jwt.verify("a".repeat(65_537))).isEmpty();
    }

    @Test
    @DisplayName("超过上限的输入在读取字符前拒绝")
    void rejectsOversizedInputBeforeScanningCharacters() {
        CharSequence oversized =
                new CharSequence() {
                    @Override
                    public int length() {
                        return 65_537;
                    }

                    @Override
                    public char charAt(int index) {
                        throw new AssertionError("不应扫描超长输入");
                    }

                    @Override
                    public CharSequence subSequence(int start, int end) {
                        throw new UnsupportedOperationException();
                    }
                };

        assertThat(JwtCodec.isAcceptableInput(oversized)).isFalse();
    }

    private static String base64Url(String value) {
        return base64Url(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String base64Url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static String signWithHeader(String header) {
        String signingInput = base64Url(header) + "." + base64Url("{\"exp\":123}");
        byte[] signature =
                CryptoSupport.hmacSha256(
                        SECRET.getBytes(StandardCharsets.UTF_8),
                        signingInput.getBytes(StandardCharsets.US_ASCII));
        return signingInput + "." + base64Url(signature);
    }
}

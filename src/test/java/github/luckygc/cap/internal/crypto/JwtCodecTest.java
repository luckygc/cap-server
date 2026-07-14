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
    @DisplayName("拒绝错误段数与超过 64 KiB 的 token")
    void rejectsInvalidStructureAndSize() {
        assertThat(jwt.verify("a.b")).isEmpty();
        assertThat(jwt.verify("a.b.c.d")).isEmpty();
        assertThat(jwt.verify("a".repeat(65_537))).isEmpty();
    }

    private static String base64Url(String value) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}

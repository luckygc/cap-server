package github.luckygc.cap.internal.token;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("默认业务 token 测试")
class DefaultTokenSignerTest {

    @Test
    @DisplayName("生成 8 字节 id 与 15 字节 secret 的小写十六进制 token")
    void generatesDocumentedWireFormat() {
        DefaultTokenSigner.SignedToken signed =
                new DefaultTokenSigner(new SequentialSecureRandom()).sign();

        assertThat(signed.token()).isEqualTo("0001020304050607:08090a0b0c0d0e0f10111213141516");
    }

    @Test
    @DisplayName("tokenKey 摘要 secretHex 的 UTF-8 编码")
    void derivesTokenKeyFromSecretHexUtf8() throws Exception {
        DefaultTokenSigner.SignedToken signed =
                new DefaultTokenSigner(new SequentialSecureRandom()).sign();
        String id = "0001020304050607";
        String secretHex = "08090a0b0c0d0e0f10111213141516";
        String digest =
                HexFormat.of()
                        .formatHex(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(secretHex.getBytes(StandardCharsets.UTF_8)));

        assertThat(signed.tokenKey()).isEqualTo(id + ":" + digest);
        assertThat(signed.tokenKey()).matches("[0-9a-f]{16}:[0-9a-f]{64}");
    }

    private static final class SequentialSecureRandom extends SecureRandom {

        private int next;

        @Override
        public void nextBytes(byte[] bytes) {
            for (int index = 0; index < bytes.length; index++) {
                bytes[index] = (byte) next++;
            }
        }
    }
}

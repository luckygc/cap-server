package github.luckygc.cap.internal.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AES-GCM 加密元数据测试")
class EncryptedMetadataCodecTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    @Test
    @DisplayName("Format 1 与 Format 2 元数据分别往返")
    void roundTripsBothFormats() {
        EncryptedMetadataCodec codec = codec(SECRET);
        Map<String, Object> metadata = Map.of("expected", 123L);

        assertThat(codec.decryptFormat1(codec.encryptFormat1(metadata))).hasValue(metadata);
        assertThat(codec.decryptFormat2(codec.encryptFormat2(metadata))).hasValue(metadata);
    }

    @Test
    @DisplayName("固定随机源产生 12 字节 IV 且密文包含认证标签")
    void usesTwelveByteIvAndAuthenticationTag() {
        EncryptedMetadataCodec codec = codec(SECRET);

        byte[] wire = CryptoSupport.decodeBase64Url(codec.encryptFormat1(Map.of()));

        assertThat(wire).hasSize(12 + 2 + 16);
        assertThat(Arrays.copyOf(wire, 12)).containsOnly(7);
    }

    @Test
    @DisplayName("拒绝篡改、错误密钥与错误 AAD")
    void rejectsTamperingWrongKeyAndWrongAad() {
        EncryptedMetadataCodec codec = codec(SECRET);
        String encrypted = codec.encryptFormat2(Map.of("expected", "value"));
        byte[] tamperedBytes = CryptoSupport.decodeBase64Url(encrypted);
        tamperedBytes[tamperedBytes.length - 1] ^= 1;
        String tampered = CryptoSupport.encodeBase64Url(tamperedBytes);

        assertThat(codec.decryptFormat2(tampered)).isEmpty();
        assertThat(codec("fedcba9876543210fedcba9876543210").decryptFormat2(encrypted)).isEmpty();
        assertThat(codec.decryptFormat1(encrypted)).isEmpty();
    }

    @Test
    @DisplayName("畸形密文安全返回 empty")
    void rejectsMalformedCiphertext() {
        EncryptedMetadataCodec codec = codec(SECRET);

        assertThat(codec.decryptFormat1("not+base64")).isEmpty();
        assertThat(codec.decryptFormat1(CryptoSupport.encodeBase64Url(new byte[27]))).isEmpty();
    }

    private static EncryptedMetadataCodec codec(String secret) {
        return new EncryptedMetadataCodec(secret, new FixedSecureRandom());
    }

    private static final class FixedSecureRandom extends SecureRandom {
        @Override
        public void nextBytes(byte[] bytes) {
            Arrays.fill(bytes, (byte) 7);
        }
    }
}

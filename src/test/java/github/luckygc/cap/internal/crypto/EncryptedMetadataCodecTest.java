package github.luckygc.cap.internal.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import github.luckygc.cap.internal.json.ProtocolJsonCodec;
import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;
import org.jspecify.annotations.Nullable;
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
    @DisplayName("拒绝篡改、错误密钥与错误加密域")
    void rejectsTamperingWrongKeyAndWrongInfo() {
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
        assertThat(codec.decryptFormat1(CryptoSupport.encodeBase64Url(new byte[28]))).isEmpty();
    }

    @Test
    @DisplayName("拒绝非规范 Base64URL 密文")
    void rejectsNonCanonicalBase64Url() {
        EncryptedMetadataCodec codec = codec(SECRET);
        String canonical = CryptoSupport.encodeBase64Url(new byte[28]);
        char last = canonical.charAt(canonical.length() - 1);
        char nonCanonicalLast = last == 'A' ? 'B' : 'A';

        assertThat(
                        codec.decryptFormat1(
                                canonical.substring(0, canonical.length() - 1) + nonCanonicalLast))
                .isEmpty();
    }

    @Test
    @DisplayName("逐字节匹配上游 Format 1 与 Format 2 加密向量")
    void matchesUpstreamEncryptionVectorsByteForByte() throws IOException {
        Map<String, @Nullable Object> fixture = fixture();
        @SuppressWarnings("unchecked")
        Map<String, @Nullable Object> vectors =
                (Map<String, @Nullable Object>) fixture.get("cryptoVectors");

        assertVector(vectors, "format1", false);
        assertVector(vectors, "format2", true);
        assertVector(vectors, "minimalFormat1", false);
    }

    private static void assertVector(
            Map<String, @Nullable Object> vectors, String name, boolean format2) {
        @SuppressWarnings("unchecked")
        Map<String, @Nullable Object> vector = (Map<String, @Nullable Object>) vectors.get(name);
        @SuppressWarnings("unchecked")
        Map<String, @Nullable Object> metadata =
                (Map<String, @Nullable Object>) vector.get("metadata");
        String encrypted = (String) vector.get("encrypted");
        byte[] iv = Arrays.copyOf(CryptoSupport.decodeBase64Url(encrypted), 12);
        EncryptedMetadataCodec codec =
                new EncryptedMetadataCodec(SECRET, new SuppliedSecureRandom(iv));

        assertThat(format2 ? codec.encryptFormat2(metadata) : codec.encryptFormat1(metadata))
                .isEqualTo(encrypted);
        assertThat(format2 ? codec.decryptFormat2(encrypted) : codec.decryptFormat1(encrypted))
                .hasValue(metadata);
    }

    private static Map<String, @Nullable Object> fixture() throws IOException {
        try (InputStream input =
                EncryptedMetadataCodecTest.class.getResourceAsStream(
                        "/fixtures/capjs-core-0.1.1/format2.json")) {
            if (input == null) {
                throw new IOException("Format 2 fixture 不存在");
            }
            return new ProtocolJsonCodec().readObject(input.readAllBytes());
        }
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

    private static final class SuppliedSecureRandom extends SecureRandom {

        private final byte[] supplied;

        private SuppliedSecureRandom(byte[] supplied) {
            this.supplied = supplied.clone();
        }

        @Override
        public void nextBytes(byte[] bytes) {
            if (bytes.length != supplied.length) {
                throw new IllegalArgumentException("unexpected random byte count");
            }
            System.arraycopy(supplied, 0, bytes, 0, bytes.length);
        }
    }
}

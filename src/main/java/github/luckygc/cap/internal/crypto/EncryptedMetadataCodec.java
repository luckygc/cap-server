package github.luckygc.cap.internal.crypto;

import github.luckygc.cap.internal.json.ProtocolJsonCodec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;

/** 使用 AES-256-GCM 保护协议元数据。 */
public final class EncryptedMetadataCodec {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int TAG_BYTES = TAG_BITS / Byte.SIZE;
    private static final byte[] FORMAT_1_INFO = CryptoSupport.utf8("cap:enc-v1");
    private static final byte[] FORMAT_2_INFO = CryptoSupport.utf8("cap:fmt2-v1");

    private final byte[] secret;
    private final SecureRandom random;
    private final ProtocolJsonCodec jsonCodec;

    public EncryptedMetadataCodec(String secret) {
        this(secret, new SecureRandom());
    }

    /** 使用调用方随机源，供内部协议组合器和确定性互操作测试注入。 */
    public EncryptedMetadataCodec(String secret, SecureRandom random) {
        this.secret = CryptoSupport.secretBytes(secret);
        this.random = random;
        this.jsonCodec = new ProtocolJsonCodec();
    }

    /** 加密 Format 1 instrumentation 元数据。 */
    public String encryptFormat1(Map<String, @Nullable Object> metadata) {
        return encrypt(metadata, FORMAT_1_INFO);
    }

    /** 解密 Format 1 instrumentation 元数据。 */
    public Optional<Map<String, @Nullable Object>> decryptFormat1(String encrypted) {
        return decrypt(encrypted, FORMAT_1_INFO);
    }

    /** 使用固定 info 派生密钥，加密 Format 2 expected 元数据。 */
    public String encryptFormat2(Map<String, @Nullable Object> metadata) {
        return encrypt(metadata, FORMAT_2_INFO);
    }

    /** 使用固定 info 派生密钥，解密 Format 2 expected 元数据。 */
    public Optional<Map<String, @Nullable Object>> decryptFormat2(String encrypted) {
        return decrypt(encrypted, FORMAT_2_INFO);
    }

    private String encrypt(Map<String, @Nullable Object> metadata, byte[] info) {
        byte[] iv = CryptoSupport.randomBytes(random, IV_BYTES);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(info), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertextAndTag = cipher.doFinal(jsonCodec.writeObject(metadata));
            int ciphertextLength = ciphertextAndTag.length - TAG_BYTES;
            byte[] wire = new byte[IV_BYTES + TAG_BYTES + ciphertextLength];
            System.arraycopy(iv, 0, wire, 0, IV_BYTES);
            System.arraycopy(ciphertextAndTag, ciphertextLength, wire, IV_BYTES, TAG_BYTES);
            System.arraycopy(ciphertextAndTag, 0, wire, IV_BYTES + TAG_BYTES, ciphertextLength);
            return CryptoSupport.encodeBase64Url(wire);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("运行环境不支持 AES-256-GCM", exception);
        }
    }

    private Optional<Map<String, @Nullable Object>> decrypt(String encrypted, byte[] info) {
        try {
            byte[] wire = CryptoSupport.decodeBase64Url(encrypted);
            if (wire.length < IV_BYTES + TAG_BYTES) {
                return Optional.empty();
            }
            byte[] iv = Arrays.copyOf(wire, IV_BYTES);
            int ciphertextLength = wire.length - IV_BYTES - TAG_BYTES;
            byte[] ciphertextAndTag = new byte[ciphertextLength + TAG_BYTES];
            System.arraycopy(wire, IV_BYTES + TAG_BYTES, ciphertextAndTag, 0, ciphertextLength);
            System.arraycopy(wire, IV_BYTES, ciphertextAndTag, ciphertextLength, TAG_BYTES);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(info), new GCMParameterSpec(TAG_BITS, iv));
            return Optional.of(jsonCodec.readObject(cipher.doFinal(ciphertextAndTag)));
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private SecretKeySpec key(byte[] info) {
        return new SecretKeySpec(CryptoSupport.hmacSha256(secret, info), "AES");
    }
}

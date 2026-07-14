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
    private static final byte[] FORMAT_2_AAD = CryptoSupport.utf8("cap:fmt2-v1");

    private final SecretKeySpec key;
    private final SecureRandom random;
    private final ProtocolJsonCodec jsonCodec;

    public EncryptedMetadataCodec(String secret) {
        this(secret, new SecureRandom());
    }

    EncryptedMetadataCodec(String secret, SecureRandom random) {
        this.key =
                new SecretKeySpec(CryptoSupport.sha256(CryptoSupport.secretBytes(secret)), "AES");
        this.random = random;
        this.jsonCodec = new ProtocolJsonCodec();
    }

    /** 加密 Format 1 instrumentation 元数据。 */
    public String encryptFormat1(Map<String, @Nullable Object> metadata) {
        return encrypt(metadata, null);
    }

    /** 解密 Format 1 instrumentation 元数据。 */
    public Optional<Map<String, @Nullable Object>> decryptFormat1(String encrypted) {
        return decrypt(encrypted, null);
    }

    /** 使用固定 AAD 加密 Format 2 expected 元数据。 */
    public String encryptFormat2(Map<String, @Nullable Object> metadata) {
        return encrypt(metadata, FORMAT_2_AAD);
    }

    /** 使用固定 AAD 解密 Format 2 expected 元数据。 */
    public Optional<Map<String, @Nullable Object>> decryptFormat2(String encrypted) {
        return decrypt(encrypted, FORMAT_2_AAD);
    }

    private String encrypt(Map<String, @Nullable Object> metadata, byte @Nullable [] aad) {
        byte[] iv = CryptoSupport.randomBytes(random, IV_BYTES);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            if (aad != null) {
                cipher.updateAAD(aad);
            }
            byte[] ciphertext = cipher.doFinal(jsonCodec.writeObject(metadata));
            byte[] wire = Arrays.copyOf(iv, iv.length + ciphertext.length);
            System.arraycopy(ciphertext, 0, wire, iv.length, ciphertext.length);
            return CryptoSupport.encodeBase64Url(wire);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("运行环境不支持 AES-256-GCM", exception);
        }
    }

    private Optional<Map<String, @Nullable Object>> decrypt(
            String encrypted, byte @Nullable [] aad) {
        try {
            byte[] wire = CryptoSupport.decodeBase64Url(encrypted);
            if (wire.length < IV_BYTES + TAG_BYTES) {
                return Optional.empty();
            }
            byte[] iv = Arrays.copyOf(wire, IV_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(wire, IV_BYTES, wire.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            if (aad != null) {
                cipher.updateAAD(aad);
            }
            return Optional.of(jsonCodec.readObject(cipher.doFinal(ciphertext)));
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
}

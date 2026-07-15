package github.luckygc.cap.internal.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class CryptoSupport {

    private static final Base64.Encoder BASE64_URL_ENCODER =
            Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64_URL_DECODER = Base64.getUrlDecoder();
    private static final HexFormat HEX = HexFormat.of();

    private CryptoSupport() {}

    static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    static byte[] secretBytes(String secret) {
        byte[] encoded = utf8(secret);
        if (encoded.length < 16) {
            throw new IllegalArgumentException("secret 至少需要 16 个 UTF-8 字节");
        }
        return encoded;
    }

    static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("运行环境缺少 SHA-256", exception);
        }
    }

    static byte[] hmacSha256(byte[] key, byte[] value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("运行环境缺少 HmacSHA256", exception);
        }
    }

    static byte[] randomBytes(SecureRandom random, int length) {
        byte[] value = new byte[length];
        random.nextBytes(value);
        return value;
    }

    static String encodeBase64Url(byte[] value) {
        return BASE64_URL_ENCODER.encodeToString(value);
    }

    static byte[] decodeBase64Url(String value) {
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Base64URL 不能为空");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean valid =
                    character >= 'A' && character <= 'Z'
                            || character >= 'a' && character <= 'z'
                            || character >= '0' && character <= '9'
                            || character == '-'
                            || character == '_';
            if (!valid) {
                throw new IllegalArgumentException("Base64URL 字符无效");
            }
        }
        byte[] decoded = BASE64_URL_DECODER.decode(value);
        if (!encodeBase64Url(decoded).equals(value)) {
            throw new IllegalArgumentException("Base64URL 编码不规范");
        }
        return decoded;
    }

    static String hex(byte[] value) {
        return HEX.formatHex(value);
    }
}

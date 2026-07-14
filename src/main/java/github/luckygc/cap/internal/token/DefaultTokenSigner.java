package github.luckygc.cap.internal.token;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Objects;

/** 生成默认业务 token 及其服务端查找键。 */
public final class DefaultTokenSigner {

    private static final int ID_BYTES = 8;
    private static final int SECRET_BYTES = 15;
    private static final HexFormat HEX = HexFormat.of();

    private final SecureRandom random;

    public DefaultTokenSigner() {
        this(new SecureRandom());
    }

    DefaultTokenSigner(SecureRandom random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    public SignedToken sign() {
        String id = HEX.formatHex(randomBytes(ID_BYTES));
        String secretHex = HEX.formatHex(randomBytes(SECRET_BYTES));
        String token = id + ":" + secretHex;
        String tokenKey = id + ":" + HEX.formatHex(sha256(secretHex));
        return new SignedToken(token, tokenKey);
    }

    private byte[] randomBytes(int length) {
        byte[] value = new byte[length];
        random.nextBytes(value);
        return value;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("运行环境缺少 SHA-256", exception);
        }
    }

    public record SignedToken(String token, String tokenKey) {

        public SignedToken {
            Objects.requireNonNull(token, "token");
            Objects.requireNonNull(tokenKey, "tokenKey");
        }
    }
}

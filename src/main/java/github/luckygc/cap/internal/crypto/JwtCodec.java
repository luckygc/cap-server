package github.luckygc.cap.internal.crypto;

import github.luckygc.cap.internal.json.ProtocolJsonCodec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** 只接受规范 HS256 token 的 JWT 编解码器。 */
public final class JwtCodec {

    private static final byte[] HEADER_JSON =
            "{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8);
    private static final String HEADER_SEGMENT = CryptoSupport.encodeBase64Url(HEADER_JSON);
    private static final Map<String, Object> EXPECTED_HEADER = Map.of("alg", "HS256", "typ", "JWT");

    private final byte[] secret;
    private final ProtocolJsonCodec jsonCodec;

    public JwtCodec(String secret) {
        this(secret, new ProtocolJsonCodec());
    }

    JwtCodec(String secret, ProtocolJsonCodec jsonCodec) {
        this.secret = CryptoSupport.secretBytes(secret);
        this.jsonCodec = jsonCodec;
    }

    /** 对 payload 生成规范 HS256 JWT。 */
    public String sign(Map<String, @Nullable Object> payload) {
        String payloadSegment = CryptoSupport.encodeBase64Url(jsonCodec.writeObject(payload));
        String signingInput = HEADER_SEGMENT + "." + payloadSegment;
        String signature =
                CryptoSupport.encodeBase64Url(
                        CryptoSupport.hmacSha256(
                                secret, signingInput.getBytes(StandardCharsets.US_ASCII)));
        String token = signingInput + "." + signature;
        if (token.length() > ProtocolJsonCodec.MAX_INPUT_BYTES) {
            throw new IllegalArgumentException("JWT 超过大小限制");
        }
        return token;
    }

    /** 验证 JWT，任何不可信输入错误都安全返回 empty。 */
    public Optional<Map<String, @Nullable Object>> verify(String token) {
        if (token.length() > ProtocolJsonCodec.MAX_INPUT_BYTES) {
            return Optional.empty();
        }
        int firstDot = token.indexOf('.');
        int secondDot = firstDot < 0 ? -1 : token.indexOf('.', firstDot + 1);
        if (firstDot <= 0
                || secondDot <= firstDot + 1
                || secondDot == token.length() - 1
                || token.indexOf('.', secondDot + 1) >= 0) {
            return Optional.empty();
        }
        String headerSegment = token.substring(0, firstDot);
        String payloadSegment = token.substring(firstDot + 1, secondDot);
        String signatureSegment = token.substring(secondDot + 1);
        try {
            Map<String, @Nullable Object> header =
                    jsonCodec.readObject(CryptoSupport.decodeBase64Url(headerSegment));
            if (!EXPECTED_HEADER.equals(header)) {
                return Optional.empty();
            }
            byte[] suppliedSignature = CryptoSupport.decodeBase64Url(signatureSegment);
            String signingInput = token.substring(0, secondDot);
            byte[] expectedSignature =
                    CryptoSupport.hmacSha256(
                            secret, signingInput.getBytes(StandardCharsets.US_ASCII));
            if (!MessageDigest.isEqual(expectedSignature, suppliedSignature)) {
                return Optional.empty();
            }
            return Optional.of(jsonCodec.readObject(CryptoSupport.decodeBase64Url(payloadSegment)));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
}

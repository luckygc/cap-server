package github.luckygc.cap.internal.protocol;

import github.luckygc.cap.ChallengeOptions;
import github.luckygc.cap.ChallengeResponse;
import github.luckygc.cap.InstrumentationOptions;
import github.luckygc.cap.RedeemRequest;
import github.luckygc.cap.internal.crypto.EncryptedMetadataCodec;
import github.luckygc.cap.internal.crypto.JwtCodec;
import github.luckygc.cap.internal.instrumentation.InstrumentationGenerator;
import github.luckygc.cap.internal.instrumentation.InstrumentationGenerator.GeneratedInstrumentation;
import github.luckygc.cap.internal.instrumentation.InstrumentationVerifier;
import github.luckygc.cap.utils.RandomUtil;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** capjs-core Format 1 的无状态 SHA-256 PoW 生成与验证。 */
public final class Format1Protocol {

    private static final int DEFAULT_COUNT = 50;
    private static final int DEFAULT_SIZE = 32;
    private static final int DEFAULT_DIFFICULTY = 4;
    private static final int MAX_COUNT = 1000;
    private static final int MAX_SIZE = 256;
    private static final int MAX_DIFFICULTY = 16;
    private static final HexFormat HEX = HexFormat.of();

    private final JwtCodec jwt;
    private final int count;
    private final int size;
    private final int difficulty;
    private final Clock clock;
    private final SecureRandom random;
    private final @Nullable InstrumentationOptions instrumentationOptions;
    private final EncryptedMetadataCodec encryptedMetadata;
    private final InstrumentationGenerator instrumentationGenerator;
    private final InstrumentationVerifier instrumentationVerifier;

    /** 使用 capjs-core 默认 Format 1 参数。 */
    public Format1Protocol(String secret) {
        this(secret, DEFAULT_COUNT, DEFAULT_SIZE, DEFAULT_DIFFICULTY);
    }

    /** 使用调用方配置的 Format 1 参数。 */
    public Format1Protocol(String secret, int count, int size, int difficulty) {
        this(secret, count, size, difficulty, null);
    }

    /** 使用调用方配置的可选 instrumentation。 */
    public Format1Protocol(
            String secret,
            int count,
            int size,
            int difficulty,
            @Nullable InstrumentationOptions instrumentationOptions) {
        this(
                secret,
                count,
                size,
                difficulty,
                instrumentationOptions,
                Clock.systemUTC(),
                new SecureRandom());
    }

    Format1Protocol(
            String secret, int count, int size, int difficulty, Clock clock, SecureRandom random) {
        this(secret, count, size, difficulty, null, clock, random);
    }

    Format1Protocol(
            String secret,
            int count,
            int size,
            int difficulty,
            @Nullable InstrumentationOptions instrumentationOptions,
            Clock clock,
            SecureRandom random) {
        validateParameters(count, size, difficulty);
        jwt = new JwtCodec(secret);
        this.count = count;
        this.size = size;
        this.difficulty = difficulty;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
        this.instrumentationOptions = instrumentationOptions;
        encryptedMetadata = new EncryptedMetadataCodec(secret, random);
        instrumentationGenerator = new InstrumentationGenerator(random, clock);
        instrumentationVerifier = new InstrumentationVerifier(clock);
    }

    /** 生成带 HS256 状态 token 的 Format 1 challenge。 */
    public ChallengeResponse.Format1 generate(ChallengeOptions options) {
        Objects.requireNonNull(options, "options");
        long issuedAt = clock.millis();
        long expires = Math.addExact(issuedAt, options.ttl().toMillis());
        byte[] nonce = new byte[25];
        random.nextBytes(nonce);

        Map<String, @Nullable Object> payload = new LinkedHashMap<>();
        payload.put("n", HEX.formatHex(nonce));
        payload.put("c", count);
        payload.put("s", size);
        payload.put("d", difficulty);
        payload.put("exp", expires);
        payload.put("iat", issuedAt);
        if (options.scope() != null && !options.scope().isEmpty()) {
            payload.put("sk", options.scope());
        }
        if (!options.extra().isEmpty()) {
            payload.put("x", options.extra());
        }

        @Nullable String instrumentation = null;
        if (instrumentationOptions != null) {
            GeneratedInstrumentation generated =
                    instrumentationGenerator.generate(instrumentationOptions, options.ttl());
            Map<String, @Nullable Object> metadata = new LinkedHashMap<>();
            metadata.put("id", generated.id());
            metadata.put("expectedVals", generated.expectedVals());
            metadata.put("vars", generated.vars());
            metadata.put("blockAutomatedBrowsers", generated.blockAutomatedBrowsers());
            metadata.put("expires", expires);
            payload.put("ei", encryptedMetadata.encryptFormat1(metadata));
            instrumentation = generated.instrumentation();
        }

        return new ChallengeResponse.Format1(
                new ChallengeResponse.Challenge(count, size, difficulty),
                jwt.sign(payload),
                expires,
                instrumentation);
    }

    /** 验证一个已构造的公开兑换请求。 */
    public ValidationResult validate(
            @Nullable RedeemRequest request, @Nullable String expectedScope) {
        if (request == null) {
            return failure("invalid_body");
        }
        return validateComponents(
                true,
                request.token(),
                request.solutions(),
                expectedScope,
                request.instr(),
                request.instrBlocked(),
                request.instrTimeout());
    }

    ValidationResult validateComponents(
            boolean validBody,
            @Nullable Object tokenValue,
            @Nullable Object solutionsValue,
            @Nullable String expectedScope) {
        return validateComponents(
                validBody, tokenValue, solutionsValue, expectedScope, null, false, false);
    }

    private ValidationResult validateComponents(
            boolean validBody,
            @Nullable Object tokenValue,
            @Nullable Object solutionsValue,
            @Nullable String expectedScope,
            RedeemRequest.@Nullable InstrumentationResult instrumentation,
            boolean instrumentationBlocked,
            boolean instrumentationTimeout) {
        if (!validBody) {
            return failure("invalid_body");
        }
        if (!(tokenValue instanceof String token) || token.isEmpty()) {
            return failure("missing_token");
        }
        if (!(solutionsValue instanceof List<?> solutions)) {
            return failure("missing_solutions");
        }

        Map<String, @Nullable Object> payload = jwt.verify(token).orElse(null);
        if (payload == null) {
            return failure("invalid_token");
        }
        if (expectedScope != null
                && !expectedScope.isEmpty()
                && !expectedScope.equals(payload.get("sk"))) {
            return failure("scope_mismatch");
        }

        @Nullable Long expires = protocolInteger(payload.get("exp"));
        if (expires == null || expires == 0 || expires < clock.millis()) {
            return failure("expired");
        }
        @Nullable Long payloadCount = protocolInteger(payload.get("c"));
        @Nullable Long payloadSize = protocolInteger(payload.get("s"));
        @Nullable Long payloadDifficulty = protocolInteger(payload.get("d"));
        @Nullable Long issuedAt = protocolInteger(payload.get("iat"));
        @Nullable Object scopeValue = payload.get("sk");
        if (issuedAt == null
                || scopeValue != null && !(scopeValue instanceof String)
                || payloadCount == null
                || payloadSize == null
                || payloadDifficulty == null
                || !parametersInRange(payloadCount, payloadSize, payloadDifficulty)) {
            return failure("invalid_token");
        }

        int expectedCount = payloadCount.intValue();
        if (solutions.size() != expectedCount) {
            return failure("invalid_solutions");
        }
        String[] solutionStrings = new String[expectedCount];
        for (int index = 0; index < expectedCount; index++) {
            @Nullable String solution = numberText(solutions.get(index));
            if (solution == null) {
                return failure("invalid_solutions");
            }
            solutionStrings[index] = solution;
        }

        int tokenState = RandomUtil.fnv1a(token);
        int challengeSize = payloadSize.intValue();
        int challengeDifficulty = payloadDifficulty.intValue();
        for (int index = 0; index < expectedCount; index++) {
            int saltState = RandomUtil.fnv1aResume(tokenState, Integer.toString(index + 1));
            int targetState = RandomUtil.fnv1aResume(saltState, "d");
            String salt = RandomUtil.prngFromHash(saltState, challengeSize);
            String target = RandomUtil.prngFromHash(targetState, challengeDifficulty);
            if (!sha256Hex(salt + solutionStrings[index]).startsWith(target)) {
                return failure("invalid_solution");
            }
        }

        if (payload.get("ei") != null) {
            if (!(payload.get("ei") instanceof String encrypted)) {
                return instrumentationFailure("instr_corrupted");
            }
            Map<String, @Nullable Object> metadata =
                    encryptedMetadata.decryptFormat1(encrypted).orElse(null);
            GeneratedInstrumentation generated = instrumentationMetadata(metadata);
            if (generated == null) {
                return instrumentationFailure("instr_corrupted");
            }
            if (generated.expires() != 0 && clock.millis() > generated.expires()) {
                return instrumentationFailure("instr_expired");
            }
            if (instrumentationBlocked) {
                if (generated.blockAutomatedBrowsers()) {
                    return instrumentationFailure("instr_automated_browser");
                }
            } else if (instrumentationTimeout) {
                return instrumentationFailure("instr_timeout");
            } else if (instrumentation == null) {
                return instrumentationFailure("instr_missing");
            } else {
                InstrumentationVerifier.VerificationResult result =
                        instrumentationVerifier.verify(generated, instrumentation);
                if (!result.valid()) {
                    return instrumentationFailure(
                            result.reason() == null ? "instr_failed" : result.reason());
                }
            }
        }

        @Nullable String scope = scopeValue instanceof String value ? value : null;
        return new Validated(scope, issuedAt, expires, signatureHex(token));
    }

    private static @Nullable Long protocolInteger(@Nullable Object value) {
        if (value instanceof Byte number) {
            return number.longValue();
        }
        if (value instanceof Short number) {
            return number.longValue();
        }
        if (value instanceof Integer number) {
            return number.longValue();
        }
        if (value instanceof Long number) {
            return number;
        }
        if (value instanceof BigInteger number && number.bitLength() < Long.SIZE) {
            return number.longValue();
        }
        return null;
    }

    private static @Nullable String numberText(@Nullable Object value) {
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            return null;
        }
        return Format2Protocol.jsNumberToString(number);
    }

    private static void validateParameters(int count, int size, int difficulty) {
        if (!parametersInRange(count, size, difficulty)) {
            throw new IllegalArgumentException("c/s/d 越界：1<=c<=1000、1<=s<=256、1<=d<=16");
        }
    }

    private static boolean parametersInRange(long count, long size, long difficulty) {
        return count >= 1
                && count <= MAX_COUNT
                && size >= 1
                && size <= MAX_SIZE
                && difficulty >= 1
                && difficulty <= MAX_DIFFICULTY;
    }

    static String sha256Hex(String value) {
        return HEX.formatHex(sha256Bytes(value));
    }

    static byte[] sha256Bytes(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("运行环境缺少 SHA-256", exception);
        }
    }

    static String signatureHex(String token) {
        int lastDot = token.lastIndexOf('.');
        return HEX.formatHex(Base64.getUrlDecoder().decode(token.substring(lastDot + 1)));
    }

    private static ProtocolFailure failure(String reason) {
        return new ProtocolFailure(reason, false, null);
    }

    private static ProtocolFailure instrumentationFailure(String reason) {
        return new ProtocolFailure(reason, true, null);
    }

    private static @Nullable GeneratedInstrumentation instrumentationMetadata(
            @Nullable Map<String, @Nullable Object> metadata) {
        if (metadata == null
                || !(metadata.get("id") instanceof String id)
                || !(metadata.get("expectedVals") instanceof List<?> expectedValues)
                || !(metadata.get("vars") instanceof List<?> variables)
                || !(metadata.get("blockAutomatedBrowsers") instanceof Boolean blocked)) {
            return null;
        }
        @Nullable Long expires = protocolInteger(metadata.get("expires"));
        if (expires == null) {
            return null;
        }
        List<Integer> expected = new java.util.ArrayList<>(expectedValues.size());
        for (Object value : expectedValues) {
            @Nullable Long integer = protocolInteger(value);
            if (integer == null || integer < Integer.MIN_VALUE || integer > Integer.MAX_VALUE) {
                return null;
            }
            expected.add(integer.intValue());
        }
        List<String> vars = new java.util.ArrayList<>(variables.size());
        for (Object value : variables) {
            if (!(value instanceof String variable)) {
                return null;
            }
            vars.add(variable);
        }
        return new GeneratedInstrumentation(id, expires, expected, vars, blocked, "metadata-only");
    }

    /** 验证结果仅供后续统一 replay 与 token 签发流程使用。 */
    public sealed interface ValidationResult permits Validated, ProtocolFailure {}

    /** 已验证且可进入统一兑换后处理的数据。 */
    public record Validated(
            @Nullable String scope, long issuedAt, long expires, String signatureHex)
            implements ValidationResult {}
}

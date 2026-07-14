package github.luckygc.cap.internal.protocol;

import github.luckygc.cap.CapProtocol;
import github.luckygc.cap.ChallengeOptions;
import github.luckygc.cap.ChallengeResponse;
import github.luckygc.cap.InstrumentationOptions;
import github.luckygc.cap.RedeemRequest;
import github.luckygc.cap.internal.crypto.EncryptedMetadataCodec;
import github.luckygc.cap.internal.crypto.JwtCodec;
import github.luckygc.cap.internal.instrumentation.InstrumentationGenerator;
import github.luckygc.cap.internal.instrumentation.InstrumentationGenerator.GeneratedInstrumentation;
import github.luckygc.cap.internal.instrumentation.InstrumentationVerifier;
import github.luckygc.cap.internal.rsw.RswSupport;
import github.luckygc.cap.internal.rsw.RswSupport.MintedChallenge;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** capjs-core Format 2 的有序多协议 challenge 生成与验证。 */
public final class Format2Protocol {

    private static final int MAX_COUNT = 1000;
    private static final int MAX_SIZE = 256;
    private static final int MAX_DIFFICULTY = 16;
    private static final int MAX_EXPECTED = MAX_COUNT + 2;
    private static final int MAX_PROTOCOL_MAP_ENTRIES = 16;
    private static final HexFormat HEX = HexFormat.of();

    private final JwtCodec jwt;
    private final EncryptedMetadataCodec encryptedMetadata;
    private final List<CapProtocol> protocols;
    private final int count;
    private final int size;
    private final int difficulty;
    private final RswSupport.@Nullable RswMinter rswMinter;
    private final InstrumentationOptions instrumentationOptions;
    private final InstrumentationGenerator instrumentationGenerator;
    private final InstrumentationVerifier instrumentationVerifier;
    private final Clock clock;
    private final SecureRandom random;

    /** 使用调用方配置的协议顺序和协议组件。 */
    public Format2Protocol(
            String secret,
            List<CapProtocol> protocols,
            int count,
            int size,
            int difficulty,
            RswSupport.@Nullable RswMinter rswMinter,
            InstrumentationOptions instrumentationOptions) {
        this(
                secret,
                protocols,
                count,
                size,
                difficulty,
                rswMinter,
                instrumentationOptions,
                Clock.systemUTC(),
                new SecureRandom());
    }

    Format2Protocol(
            String secret,
            List<CapProtocol> protocols,
            int count,
            int size,
            int difficulty,
            RswSupport.@Nullable RswMinter rswMinter,
            InstrumentationOptions instrumentationOptions,
            Clock clock,
            SecureRandom random) {
        this.protocols = validatedProtocols(protocols, rswMinter);
        if (this.protocols.contains(CapProtocol.SHA256_POW)) {
            validatePowParameters(count, size, difficulty);
        }
        this.count = count;
        this.size = size;
        this.difficulty = difficulty;
        this.rswMinter = rswMinter;
        this.instrumentationOptions =
                Objects.requireNonNull(instrumentationOptions, "instrumentationOptions");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
        jwt = new JwtCodec(secret);
        encryptedMetadata = new EncryptedMetadataCodec(secret, random);
        instrumentationGenerator = new InstrumentationGenerator(random, clock);
        instrumentationVerifier = new InstrumentationVerifier(clock);
    }

    /** 按配置顺序生成 Format 2 challenge，并仅在认证加密元数据中保存预期解答。 */
    public ChallengeResponse.Format2 generate(ChallengeOptions options) {
        Objects.requireNonNull(options, "options");
        long issuedAt = clock.millis();
        long expires = Math.addExact(issuedAt, options.ttl().toMillis());
        List<ChallengeResponse.ProtocolChallenge> challenges = new ArrayList<>();
        List<Map<String, @Nullable Object>> expected = new ArrayList<>();

        for (CapProtocol protocol : protocols) {
            switch (protocol) {
                case SHA256_POW -> generatePow(challenges, expected);
                case RSW -> generateRsw(challenges, expected);
                case INSTRUMENTATION ->
                        generateInstrumentation(challenges, expected, options, expires);
            }
        }

        Map<String, @Nullable Object> metadata = new LinkedHashMap<>();
        metadata.put("expected", expected);
        Map<String, @Nullable Object> payload = new LinkedHashMap<>();
        payload.put("f", 2);
        payload.put("n", randomHex(16));
        payload.put("exp", expires);
        payload.put("iat", issuedAt);
        payload.put("ev", encryptedMetadata.encryptFormat2(metadata));
        if (options.scope() != null && !options.scope().isEmpty()) {
            payload.put("sk", options.scope());
        }
        if (!options.extra().isEmpty()) {
            payload.put("x", options.extra());
        }
        return new ChallengeResponse.Format2(2, challenges, jwt.sign(payload), expires);
    }

    /** 验证一个 Format 2 兑换请求，成功结果留给统一 replay 与 token 签发流程处理。 */
    public ValidationResult validate(
            @Nullable RedeemRequest request, @Nullable String expectedScope) {
        if (request == null) {
            return failure("invalid_body");
        }
        Map<String, @Nullable Object> payload = jwt.verify(request.token()).orElse(null);
        if (payload == null) {
            return failure("invalid_token");
        }
        if (expectedScope != null
                && !expectedScope.isEmpty()
                && !expectedScope.equals(payload.get("sk"))) {
            return failure("scope_mismatch");
        }

        @Nullable Long expires = protocolInteger(payload.get("exp"));
        if (payload.get("exp") == null
                || expires != null && (expires == 0 || expires < clock.millis())) {
            return failure("expired");
        }
        @Nullable Long format = protocolInteger(payload.get("f"));
        @Nullable Long issuedAt = protocolInteger(payload.get("iat"));
        @Nullable Object nonce = payload.get("n");
        @Nullable Object encrypted = payload.get("ev");
        @Nullable Object scopeValue = payload.get("sk");
        if (format == null
                || format != 2
                || expires == null
                || issuedAt == null
                || !(nonce instanceof String)
                || !(encrypted instanceof String encryptedValue)
                || scopeValue != null && !(scopeValue instanceof String)) {
            return failure("invalid_token");
        }

        Map<String, @Nullable Object> metadata =
                encryptedMetadata.decryptFormat2(encryptedValue).orElse(null);
        if (metadata == null
                || metadata.size() > MAX_PROTOCOL_MAP_ENTRIES
                || !(metadata.get("expected") instanceof List<?> expected)) {
            return failure("invalid_token");
        }
        if (expected.isEmpty() || expected.size() > MAX_EXPECTED) {
            return failure("invalid_token");
        }
        List<@Nullable Object> solutions = request.solutions();
        if (solutions.size() != expected.size()) {
            return failure("invalid_solutions");
        }
        for (int index = 0; index < expected.size(); index++) {
            @Nullable ProtocolFailure protocolFailure =
                    validateExpected(expected.get(index), solutions.get(index));
            if (protocolFailure != null) {
                return protocolFailure;
            }
        }
        @Nullable String scope = scopeValue instanceof String value ? value : null;
        return new Validated(
                scope, issuedAt, expires, Format1Protocol.signatureHex(request.token()));
    }

    private void generatePow(
            List<ChallengeResponse.ProtocolChallenge> challenges,
            List<Map<String, @Nullable Object>> expected) {
        String target = "0".repeat(difficulty);
        for (int index = 0; index < count; index++) {
            String salt = randomHex(size);
            challenges.add(
                    new ChallengeResponse.ProtocolChallenge(
                            "sha256-pow", Map.of("salt", salt, "target", target)));
            expected.add(Map.of("protocol", "sha256-pow", "salt", salt, "target", target));
        }
    }

    private void generateRsw(
            List<ChallengeResponse.ProtocolChallenge> challenges,
            List<Map<String, @Nullable Object>> expected) {
        RswSupport.RswMinter minter = Objects.requireNonNull(rswMinter, "rswMinter");
        MintedChallenge minted = minter.mint();
        challenges.add(
                new ChallengeResponse.ProtocolChallenge(
                        "rsw", Map.of("N", minted.modulus(), "x", minted.x(), "t", minted.t())));
        expected.add(Map.of("protocol", "rsw", "y", minted.y()));
    }

    private void generateInstrumentation(
            List<ChallengeResponse.ProtocolChallenge> challenges,
            List<Map<String, @Nullable Object>> expected,
            ChallengeOptions options,
            long expires) {
        GeneratedInstrumentation generated =
                instrumentationGenerator.generate(instrumentationOptions, options.ttl());
        challenges.add(
                new ChallengeResponse.ProtocolChallenge(
                        "instrumentation", Map.of("blob", generated.instrumentation())));
        Map<String, @Nullable Object> meta = new LinkedHashMap<>();
        meta.put("id", generated.id());
        meta.put("expectedVals", generated.expectedVals());
        meta.put("vars", generated.vars());
        meta.put("blockAutomatedBrowsers", generated.blockAutomatedBrowsers());
        meta.put("expires", expires);
        Map<String, @Nullable Object> entry = new LinkedHashMap<>();
        entry.put("protocol", "instrumentation");
        entry.put("instrMeta", meta);
        expected.add(entry);
    }

    private @Nullable ProtocolFailure validateExpected(
            @Nullable Object expectedValue, @Nullable Object solutionValue) {
        if (!(expectedValue instanceof Map<?, ?> expected)
                || !(solutionValue instanceof Map<?, ?> solution)
                || expected.size() > MAX_PROTOCOL_MAP_ENTRIES
                || solution.size() > MAX_PROTOCOL_MAP_ENTRIES
                || !(expected.get("protocol") instanceof String protocol)) {
            return failure("invalid_solution");
        }
        return switch (protocol) {
            case "sha256-pow" -> validatePow(expected, solution);
            case "rsw" -> validateRsw(expected, solution);
            case "instrumentation" -> validateInstrumentation(expected, solution);
            default -> failure("invalid_solution");
        };
    }

    private static @Nullable ProtocolFailure validatePow(Map<?, ?> expected, Map<?, ?> solution) {
        if (!(expected.get("salt") instanceof String salt)
                || !(expected.get("target") instanceof String target)) {
            return failure("invalid_solution");
        }
        @Nullable String nonce = nonceText(solution.get("nonce"));
        if (nonce == null || !Format1Protocol.sha256Hex(salt + nonce).startsWith(target)) {
            return failure("invalid_solution");
        }
        return null;
    }

    private static @Nullable ProtocolFailure validateRsw(Map<?, ?> expected, Map<?, ?> solution) {
        if (!(expected.get("y") instanceof String expectedY)
                || !(solution.get("y") instanceof String claimedY)
                || !RswSupport.verifySolution(expectedY, claimedY)) {
            return failure("invalid_solution");
        }
        return null;
    }

    private @Nullable ProtocolFailure validateInstrumentation(
            Map<?, ?> expected, Map<?, ?> solution) {
        @Nullable GeneratedInstrumentation metadata =
                instrumentationMetadata(expected.get("instrMeta"));
        if (metadata == null) {
            return instrumentationFailure("instr_corrupted");
        }
        if (metadata.expires() != 0 && clock.millis() > metadata.expires()) {
            return instrumentationFailure("instr_expired");
        }
        if (invalidBoolean(solution, "blocked") || invalidBoolean(solution, "timeout")) {
            return failure("invalid_solution");
        }
        if (Boolean.TRUE.equals(solution.get("blocked"))) {
            return metadata.blockAutomatedBrowsers()
                    ? instrumentationFailure("instr_automated_browser")
                    : null;
        }
        if (Boolean.TRUE.equals(solution.get("timeout"))) {
            return instrumentationFailure("instr_timeout");
        }
        if (!solution.containsKey("instr")) {
            return instrumentationFailure("instr_missing");
        }
        if (!(solution.get("instr") instanceof Map<?, ?> output)
                || output.size() > MAX_PROTOCOL_MAP_ENTRIES) {
            return failure("invalid_solution");
        }
        @Nullable Map<String, @Nullable Object> state = stringMap(output.get("state"));
        InstrumentationVerifier.VerificationResult result =
                instrumentationVerifier.verify(metadata, output.get("i"), state);
        return result.valid()
                ? null
                : instrumentationFailure(
                        result.reason() == null ? "instr_failed" : result.reason());
    }

    private static @Nullable GeneratedInstrumentation instrumentationMetadata(
            @Nullable Object value) {
        if (!(value instanceof Map<?, ?> metadata)
                || metadata.size() > MAX_PROTOCOL_MAP_ENTRIES
                || !(metadata.get("id") instanceof String id)
                || !(metadata.get("blockAutomatedBrowsers") instanceof Boolean blocked)) {
            return null;
        }
        @Nullable Long expires = protocolInteger(metadata.get("expires"));
        @Nullable List<Integer> expected = integerList(metadata.get("expectedVals"));
        @Nullable List<String> variables = stringList(metadata.get("vars"));
        if (expires == null || expected == null || variables == null) {
            return null;
        }
        return new GeneratedInstrumentation(
                id, expires, expected, variables, blocked, "metadata-only");
    }

    private static @Nullable List<Integer> integerList(@Nullable Object value) {
        if (!(value instanceof List<?> values) || values.size() > MAX_PROTOCOL_MAP_ENTRIES) {
            return null;
        }
        List<Integer> result = new ArrayList<>(values.size());
        for (@Nullable Object element : values) {
            @Nullable Long integer = protocolInteger(element);
            if (integer == null || integer < Integer.MIN_VALUE || integer > Integer.MAX_VALUE) {
                return null;
            }
            result.add(integer.intValue());
        }
        return List.copyOf(result);
    }

    private static @Nullable List<String> stringList(@Nullable Object value) {
        if (!(value instanceof List<?> values) || values.size() > MAX_PROTOCOL_MAP_ENTRIES) {
            return null;
        }
        List<String> result = new ArrayList<>(values.size());
        for (@Nullable Object element : values) {
            if (!(element instanceof String string)) {
                return null;
            }
            result.add(string);
        }
        return List.copyOf(result);
    }

    private static @Nullable Map<String, @Nullable Object> stringMap(@Nullable Object value) {
        if (!(value instanceof Map<?, ?> values) || values.size() > MAX_PROTOCOL_MAP_ENTRIES) {
            return null;
        }
        Map<String, @Nullable Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                return null;
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static boolean invalidBoolean(Map<?, ?> solution, String key) {
        return solution.containsKey(key) && !(solution.get(key) instanceof Boolean);
    }

    private static @Nullable String nonceText(@Nullable Object value) {
        if (value instanceof Byte number) {
            return number.toString();
        }
        if (value instanceof Short number) {
            return number.toString();
        }
        if (value instanceof Integer number) {
            return number.toString();
        }
        if (value instanceof Long number) {
            return number.toString();
        }
        if (value instanceof BigInteger number) {
            return number.toString();
        }
        return value instanceof String string ? string : null;
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

    private static List<CapProtocol> validatedProtocols(
            List<CapProtocol> protocols, RswSupport.@Nullable RswMinter rswMinter) {
        Objects.requireNonNull(protocols, "protocols");
        if (protocols.isEmpty() || protocols.size() > CapProtocol.values().length) {
            throw new IllegalArgumentException("protocols must contain 1 to 3 entries");
        }
        List<CapProtocol> copy = List.copyOf(protocols);
        Set<CapProtocol> unique = new HashSet<>(copy);
        if (unique.size() != copy.size()) {
            throw new IllegalArgumentException("protocols must not contain duplicates");
        }
        if (unique.contains(CapProtocol.RSW) && rswMinter == null) {
            throw new IllegalArgumentException("RSW protocol requires an RSW minter");
        }
        return copy;
    }

    private static void validatePowParameters(int count, int size, int difficulty) {
        if (count < 1
                || count > MAX_COUNT
                || size < 1
                || size > MAX_SIZE
                || difficulty < 1
                || difficulty > MAX_DIFFICULTY) {
            throw new IllegalArgumentException("c/s/d out of range");
        }
    }

    private String randomHex(int byteLength) {
        byte[] bytes = new byte[byteLength];
        random.nextBytes(bytes);
        return HEX.formatHex(bytes);
    }

    private static ProtocolFailure failure(String reason) {
        return new ProtocolFailure(reason, false, null);
    }

    private static ProtocolFailure instrumentationFailure(String reason) {
        return new ProtocolFailure(reason, true, null);
    }

    /** 验证结果仅供后续统一 replay 与 token 签发流程使用。 */
    public sealed interface ValidationResult permits Validated, ProtocolFailure {}

    /** 已验证且可进入统一兑换后处理的数据。 */
    public record Validated(
            @Nullable String scope, long issuedAt, long expires, String signatureHex)
            implements ValidationResult {}
}

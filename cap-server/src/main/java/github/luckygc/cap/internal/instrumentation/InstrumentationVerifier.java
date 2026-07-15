package github.luckygc.cap.internal.instrumentation;

import github.luckygc.cap.RedeemRequest.InstrumentationResult;
import github.luckygc.cap.internal.instrumentation.InstrumentationGenerator.GeneratedInstrumentation;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** 验证 instrumentation 元数据、客户端状态以及调用层超时/拦截信号。 */
public final class InstrumentationVerifier {

    private static final int EXPECTED_VARIABLES = 4;
    private static final int MAX_STATE_ENTRIES = 16;
    private static final int MAX_VARIABLE_LENGTH = 64;

    private final Clock clock;

    public InstrumentationVerifier() {
        this(Clock.systemUTC());
    }

    /** 使用调用方时钟，供内部协议组合器注入。 */
    public InstrumentationVerifier(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 对应上游 verifyInstrumentationResult，仅验证 challenge meta 与输出。 */
    public VerificationResult verify(
            @Nullable GeneratedInstrumentation metadata, @Nullable InstrumentationResult output) {
        if (metadata == null) {
            return failure("missing_meta");
        }
        if (output == null) {
            return failure("missing_output");
        }
        return verify(metadata, output.i(), output.state());
    }

    /** 验证已确认存在的 wire payload，允许协议解析层保留缺失的 id/state。 */
    public VerificationResult verify(
            @Nullable GeneratedInstrumentation metadata,
            @Nullable Object id,
            @Nullable Map<String, @Nullable Object> actual) {
        if (metadata == null) {
            return failure("missing_meta");
        }
        if (!(id instanceof String suppliedId) || !suppliedId.equals(metadata.id())) {
            return failure("id_mismatch");
        }
        if (actual == null || actual.size() > MAX_STATE_ENTRIES) {
            return failure("invalid_state");
        }
        List<String> variables = metadata.vars();
        List<Integer> expected = metadata.expectedVals();
        if (!validMetadata(metadata.id(), variables, expected)) {
            return failure("invalid_meta");
        }
        for (int index = 0; index < variables.size(); index++) {
            @Nullable Object value = actual.get(variables.get(index));
            if (!sameInteger(value, expected.get(index))) {
                return failure("failed_challenge");
            }
        }
        return VerificationResult.success();
    }

    /** 对应上游 redeem 调用层，过期优先于 blocked、timeout 和 state。 */
    public VerificationResult verify(
            @Nullable GeneratedInstrumentation metadata,
            @Nullable InstrumentationResult output,
            boolean blocked,
            boolean timeout) {
        if (metadata == null) {
            return failure("missing_meta");
        }
        if (metadata.expires() != 0 && clock.millis() > metadata.expires()) {
            return failure("instr_expired");
        }
        if (blocked) {
            return metadata.blockAutomatedBrowsers()
                    ? failure("instr_automated_browser")
                    : VerificationResult.success();
        }
        if (timeout) {
            return failure("instr_timeout");
        }
        return verify(metadata, output);
    }

    private static boolean validMetadata(
            String id, List<String> variables, List<Integer> expected) {
        if (!id.matches("[0-9a-f]{32}")
                || variables.size() != EXPECTED_VARIABLES
                || expected.size() != EXPECTED_VARIABLES) {
            return false;
        }
        for (String variable : variables) {
            if (variable.isEmpty()
                    || variable.length() > MAX_VARIABLE_LENGTH
                    || !variable.matches("[a-z][a-z0-9]*")) {
                return false;
            }
        }
        return variables.stream().distinct().count() == EXPECTED_VARIABLES;
    }

    private static boolean sameInteger(@Nullable Object actual, int expected) {
        if (actual instanceof Byte number) {
            return number.intValue() == expected;
        }
        if (actual instanceof Short number) {
            return number.intValue() == expected;
        }
        if (actual instanceof Integer number) {
            return number == expected;
        }
        if (actual instanceof Long number) {
            return number == expected;
        }
        if (actual instanceof BigInteger number) {
            return number.equals(BigInteger.valueOf(expected));
        }
        if (actual instanceof BigDecimal number) {
            return number.compareTo(BigDecimal.valueOf(expected)) == 0;
        }
        if (actual instanceof Float number) {
            return Float.isFinite(number) && number.doubleValue() == expected;
        }
        if (actual instanceof Double number) {
            return Double.isFinite(number) && number == expected;
        }
        return false;
    }

    private static VerificationResult failure(String reason) {
        return new VerificationResult(false, reason);
    }

    public record VerificationResult(boolean valid, @Nullable String reason) {

        private static final VerificationResult SUCCESS = new VerificationResult(true, null);

        public static VerificationResult success() {
            return SUCCESS;
        }
    }
}

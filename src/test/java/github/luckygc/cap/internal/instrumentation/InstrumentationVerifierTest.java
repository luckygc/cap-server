package github.luckygc.cap.internal.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;

import github.luckygc.cap.RedeemRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Instrumentation 验证测试")
class InstrumentationVerifierTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final InstrumentationVerifier VERIFIER =
            new InstrumentationVerifier(Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
    private static final InstrumentationGenerator.GeneratedInstrumentation META =
            new InstrumentationGenerator.GeneratedInstrumentation(
                    "0123456789abcdef0123456789abcdef",
                    NOW + 1_000,
                    List.of(100_001, 200_002, 300_003, 400_004),
                    List.of("aaaaaaaaaaaa", "bbbbbbbbbbbb", "cccccccccccc", "dddddddddddd"),
                    true,
                    "blob");

    @Test
    @DisplayName("匹配 id 与四个预期值时通过")
    void acceptsMatchingState() {
        assertThat(VERIFIER.verify(META, output(validState())))
                .isEqualTo(InstrumentationVerifier.VerificationResult.success());

        Map<String, Object> floatingPointState = new LinkedHashMap<>(validState());
        floatingPointState.put("aaaaaaaaaaaa", 100_001.0);
        assertThat(VERIFIER.verify(META, output(floatingPointState)).valid()).isTrue();
    }

    @Test
    @DisplayName("精确返回基础验证 reason")
    void returnsUpstreamBaseReasons() {
        assertThat(VERIFIER.verify(null, output(validState())).reason()).isEqualTo("missing_meta");
        assertThat(VERIFIER.verify(META, null).reason()).isEqualTo("missing_output");
        assertThat(VERIFIER.verify(META, META.id(), null).reason()).isEqualTo("invalid_state");
        assertThat(
                        VERIFIER.verify(
                                        META,
                                        new RedeemRequest.InstrumentationResult(
                                                "wrong", validState(), null))
                                .reason())
                .isEqualTo("id_mismatch");
        assertThat(VERIFIER.verify(META, output(Map.of())).reason()).isEqualTo("failed_challenge");

        Map<String, Object> mismatched = new LinkedHashMap<>(validState());
        mismatched.put("aaaaaaaaaaaa", 0);
        assertThat(VERIFIER.verify(META, output(mismatched)).reason())
                .isEqualTo("failed_challenge");

        InstrumentationGenerator.GeneratedInstrumentation invalidMeta =
                new InstrumentationGenerator.GeneratedInstrumentation(
                        META.id(), META.expires(), List.of(), List.of(), true, "blob");
        assertThat(VERIFIER.verify(invalidMeta, output(validState())).reason())
                .isEqualTo("invalid_meta");
    }

    @Test
    @DisplayName("非法或超大 state 在比较前拒绝")
    void rejectsInvalidAndOversizedState() {
        Map<String, Object> wrongType = new LinkedHashMap<>(validState());
        wrongType.put("aaaaaaaaaaaa", "100001");
        assertThat(VERIFIER.verify(META, output(wrongType)).reason()).isEqualTo("failed_challenge");

        Map<String, Object> oversized = new LinkedHashMap<>();
        for (int index = 0; index < 17; index++) {
            oversized.put("key" + index, index);
        }
        assertThat(VERIFIER.verify(META, output(oversized)).reason()).isEqualTo("invalid_state");
    }

    @Test
    @DisplayName("过期、blocked 与 timeout 按上游优先级验证")
    void verifiesExpiryBlockedAndTimeout() {
        InstrumentationGenerator.GeneratedInstrumentation expired =
                new InstrumentationGenerator.GeneratedInstrumentation(
                        META.id(), NOW - 1, META.expectedVals(), META.vars(), true, "blob");

        assertThat(VERIFIER.verify(expired, null, true, true).reason()).isEqualTo("instr_expired");
        assertThat(VERIFIER.verify(META, null, true, false).reason())
                .isEqualTo("instr_automated_browser");
        assertThat(VERIFIER.verify(META, null, false, true).reason()).isEqualTo("instr_timeout");

        InstrumentationGenerator.GeneratedInstrumentation nonBlocking =
                new InstrumentationGenerator.GeneratedInstrumentation(
                        META.id(), META.expires(), META.expectedVals(), META.vars(), false, "blob");
        assertThat(VERIFIER.verify(nonBlocking, null, true, false).valid()).isTrue();
    }

    private static RedeemRequest.InstrumentationResult output(Map<String, Object> state) {
        return new RedeemRequest.InstrumentationResult(META.id(), state, NOW);
    }

    private static Map<String, Object> validState() {
        return Map.of(
                "aaaaaaaaaaaa", 100_001L,
                "bbbbbbbbbbbb", 200_002L,
                "cccccccccccc", 300_003L,
                "dddddddddddd", 400_004L);
    }
}

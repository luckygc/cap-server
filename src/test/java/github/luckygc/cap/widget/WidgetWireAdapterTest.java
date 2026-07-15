package github.luckygc.cap.widget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import github.luckygc.cap.ChallengeResponse;
import github.luckygc.cap.RedeemRequest;
import github.luckygc.cap.RedeemResult;
import github.luckygc.cap.internal.json.ProtocolJsonCodec;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@NullMarked
@DisplayName("Widget HTTP JSON adapter 测试")
class WidgetWireAdapterTest {
    private final ProtocolJsonCodec json = new ProtocolJsonCodec();
    private final WidgetWireAdapter adapter = new WidgetWireAdapter();

    @Test
    void encodesFormat1WithoutNullInstrumentation() {
        Map<String, Object> wire =
                object(
                        adapter.encodeChallenge(
                                new ChallengeResponse.Format1(
                                        new ChallengeResponse.Challenge(1, 4, 1),
                                        "jwt",
                                        123L,
                                        null)));

        assertThat(wire)
                .containsOnlyKeys("challenge", "token", "expires")
                .containsEntry("challenge", Map.of("c", 1L, "s", 4L, "d", 1L))
                .containsEntry("token", "jwt")
                .containsEntry("expires", 123L);
    }

    @Test
    void encodesFormat2AndPreservesProtocolPayloadOrder() {
        ChallengeResponse.Format2 response =
                new ChallengeResponse.Format2(
                        2,
                        List.of(
                                new ChallengeResponse.ProtocolChallenge(
                                        "rsw", Map.of("N", "modulus", "x", "base", "t", 75_000L)),
                                new ChallengeResponse.ProtocolChallenge(
                                        "instrumentation", Map.of("blob", "script"))),
                        "jwt",
                        456L);

        Map<String, Object> wire = object(adapter.encodeChallenge(response));

        assertThat(wire)
                .containsEntry("format", 2L)
                .containsEntry("token", "jwt")
                .containsEntry("expires", 456L);
        assertThat(list(wire.get("challenges")))
                .containsExactly(
                        Map.of(
                                "protocol",
                                "rsw",
                                "payload",
                                Map.of("N", "modulus", "x", "base", "t", 75_000L)),
                        Map.of("protocol", "instrumentation", "payload", Map.of("blob", "script")));
    }

    @Test
    void decodesSnakeCaseInstrumentationSignalsAndRejectsInvalidStructures() {
        RedeemRequest request =
                adapter.decodeRedeem(
                        json.writeObject(
                                Map.of(
                                        "token",
                                        "jwt",
                                        "solutions",
                                        List.of(7L),
                                        "instr",
                                        Map.of("i", "id", "state", Map.of("a", 1L), "ts", 9L),
                                        "instr_blocked",
                                        true,
                                        "instr_timeout",
                                        false)));

        assertThat(request.token()).isEqualTo("jwt");
        assertThat(request.solutions()).containsExactly(7L);
        assertThat(request.instrBlocked()).isTrue();
        assertThat(request.instrTimeout()).isFalse();
        assertThat(request.instr()).isNotNull();
        assertThat(request.instr().i()).isEqualTo("id");
        assertThat(request.instr().state()).containsEntry("a", 1L);
        assertThat(request.instr().ts()).isEqualTo(9L);

        assertThatThrownBy(
                        () ->
                                adapter.decodeRedeem(
                                        json.writeObject(
                                                Map.of("token", 1L, "solutions", List.of()))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                adapter.decodeRedeem(
                                        json.writeObject(
                                                Map.of(
                                                        "token",
                                                        "jwt",
                                                        "solutions",
                                                        List.of(),
                                                        "instr",
                                                        Map.of(
                                                                "i", "id",
                                                                "state", Map.of(),
                                                                "ts", "9")))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void encodesOnlyWidgetResultFields() {
        Map<String, Object> success =
                object(
                        adapter.encodeResult(
                                new RedeemResult.Success(
                                        true,
                                        "business-token",
                                        "server-key",
                                        789L,
                                        "login",
                                        123L)));
        Map<String, Object> failure =
                object(
                        adapter.encodeResult(
                                new RedeemResult.Failure(
                                        false, "already_redeemed", false, "internal-detail")));

        assertThat(success)
                .containsOnlyKeys("success", "token", "expires")
                .containsEntry("success", true)
                .containsEntry("token", "business-token")
                .containsEntry("expires", 789L);
        assertThat(failure)
                .containsOnlyKeys("success", "reason", "instr_error", "error")
                .containsEntry("success", false)
                .containsEntry("reason", "already_redeemed")
                .containsEntry("instr_error", false)
                .containsEntry("error", "already_redeemed");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> object(byte[] value) {
        return (Map<String, Object>) (Map<?, ?>) json.readObject(value);
    }

    @SuppressWarnings("unchecked")
    private List<Object> list(Object value) {
        return (List<Object>) value;
    }
}

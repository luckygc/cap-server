package github.luckygc.cap.widget;

import github.luckygc.cap.ChallengeResponse;
import github.luckygc.cap.RedeemRequest;
import github.luckygc.cap.RedeemRequest.InstrumentationResult;
import github.luckygc.cap.RedeemResult;
import github.luckygc.cap.internal.json.ProtocolJsonCodec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
final class WidgetWireAdapter {
    private final ProtocolJsonCodec json = new ProtocolJsonCodec();

    byte[] encodeChallenge(ChallengeResponse response) {
        return json.writeObject(challengeMap(response));
    }

    RedeemRequest decodeRedeem(byte[] body) {
        Map<String, @Nullable Object> wire = json.readObject(body);
        String token = requiredString(wire.get("token"), "token");
        List<@Nullable Object> solutions = requiredList(wire.get("solutions"), "solutions");
        return new RedeemRequest(
                token,
                solutions,
                instrumentation(wire.get("instr")),
                Boolean.TRUE.equals(wire.get("instr_blocked")),
                Boolean.TRUE.equals(wire.get("instr_timeout")));
    }

    byte[] encodeResult(RedeemResult result) {
        if (result instanceof RedeemResult.Success success) {
            return json.writeObject(
                    Map.of(
                            "success",
                            true,
                            "token",
                            success.token(),
                            "expires",
                            success.expires()));
        }
        RedeemResult.Failure failure = (RedeemResult.Failure) result;
        return json.writeObject(
                Map.of(
                        "success",
                        false,
                        "reason",
                        failure.reason(),
                        "instr_error",
                        failure.instrError(),
                        "error",
                        failure.reason()));
    }

    private static Map<String, @Nullable Object> challengeMap(ChallengeResponse response) {
        Map<String, @Nullable Object> wire = new LinkedHashMap<>();
        if (response instanceof ChallengeResponse.Format1 format1) {
            ChallengeResponse.Challenge challenge = format1.challenge();
            wire.put(
                    "challenge",
                    Map.of("c", challenge.c(), "s", challenge.s(), "d", challenge.d()));
            wire.put("token", format1.token());
            wire.put("expires", format1.expires());
            if (format1.instrumentation() != null) {
                wire.put("instrumentation", format1.instrumentation());
            }
            return wire;
        }

        ChallengeResponse.Format2 format2 = (ChallengeResponse.Format2) response;
        List<Map<String, @Nullable Object>> challenges = new ArrayList<>();
        for (ChallengeResponse.ProtocolChallenge challenge : format2.challenges()) {
            Map<String, @Nullable Object> encoded = new LinkedHashMap<>();
            encoded.put("protocol", challenge.protocol());
            encoded.put("payload", challenge.payload());
            challenges.add(encoded);
        }
        wire.put("format", format2.format());
        wire.put("challenges", challenges);
        wire.put("token", format2.token());
        wire.put("expires", format2.expires());
        return wire;
    }

    private static @Nullable InstrumentationResult instrumentation(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        Map<String, @Nullable Object> wire = requiredMap(value, "instr");
        String i = requiredString(wire.get("i"), "instr.i");
        Map<String, @Nullable Object> state = requiredMap(wire.get("state"), "instr.state");
        @Nullable Object tsValue = wire.get("ts");
        @Nullable Long ts;
        if (tsValue == null) {
            ts = null;
        } else if (tsValue instanceof Long integer) {
            ts = integer;
        } else {
            throw invalid("instr.ts");
        }
        return new InstrumentationResult(i, state, ts);
    }

    private static String requiredString(@Nullable Object value, String field) {
        if (value instanceof String string) {
            return string;
        }
        throw invalid(field);
    }

    private static List<@Nullable Object> requiredList(@Nullable Object value, String field) {
        if (!(value instanceof List<?> list)) {
            throw invalid(field);
        }
        List<@Nullable Object> result = new ArrayList<>(list.size());
        result.addAll(list);
        return result;
    }

    private static Map<String, @Nullable Object> requiredMap(@Nullable Object value, String field) {
        if (!(value instanceof Map<?, ?> map)) {
            throw invalid(field);
        }
        Map<String, @Nullable Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw invalid(field);
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static IllegalArgumentException invalid(String field) {
        return new IllegalArgumentException(field + " 结构无效");
    }
}

package github.luckygc.cap;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

public record RedeemRequest(
        String token,
        List<@Nullable Object> solutions,
        @Nullable InstrumentationResult instr,
        boolean instrBlocked,
        boolean instrTimeout) {

    public RedeemRequest {
        Objects.requireNonNull(token, "token");
        solutions = ChallengeOptions.immutableList(solutions);
    }

    public record InstrumentationResult(
            String i, Map<String, @Nullable Object> state, @Nullable Long ts) {

        public InstrumentationResult {
            Objects.requireNonNull(i, "i");
            state = ChallengeOptions.immutableMap(state);
        }
    }
}

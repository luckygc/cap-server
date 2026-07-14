package github.luckygc.cap;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

public interface CapEventListener {

    default void challengeCreated(ChallengeEvent event) {}

    default void redeemSucceeded(RedeemEvent event) {}

    default void redeemFailed(FailureEvent event) {}

    record ChallengeEvent(int format, List<CapProtocol> protocols, Duration duration) {

        public ChallengeEvent {
            protocols = List.copyOf(protocols);
            Objects.requireNonNull(duration, "duration");
        }
    }

    record RedeemEvent(int format, List<CapProtocol> protocols, Duration duration) {

        public RedeemEvent {
            protocols = List.copyOf(protocols);
            Objects.requireNonNull(duration, "duration");
        }
    }

    record FailureEvent(int format, List<CapProtocol> protocols, String reason, Duration duration) {

        public FailureEvent {
            protocols = List.copyOf(protocols);
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(duration, "duration");
        }
    }
}

package github.luckygc.cap;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

public sealed interface ChallengeResponse
        permits ChallengeResponse.Format1, ChallengeResponse.Format2 {

    String token();

    long expires();

    record Format1(
            Challenge challenge, String token, long expires, @Nullable String instrumentation)
            implements ChallengeResponse {

        public Format1 {
            Objects.requireNonNull(challenge, "challenge");
            Objects.requireNonNull(token, "token");
        }
    }

    record Format2(int format, List<ProtocolChallenge> challenges, String token, long expires)
            implements ChallengeResponse {

        public Format2 {
            if (format != 2) {
                throw new IllegalArgumentException("format must be 2");
            }
            challenges = List.copyOf(challenges);
            Objects.requireNonNull(token, "token");
        }
    }

    record Challenge(int c, int s, int d) {}

    record ProtocolChallenge(String protocol, Map<String, Object> payload) {

        public ProtocolChallenge {
            Objects.requireNonNull(protocol, "protocol");
            payload = ChallengeOptions.immutableMap(payload);
        }
    }
}

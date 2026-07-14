package github.luckygc.cap;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

public sealed interface RedeemResult permits RedeemResult.Success, RedeemResult.Failure {

    boolean success();

    record Success(
            boolean success,
            String token,
            @Nullable String tokenKey,
            long expires,
            @Nullable String scope,
            long iat)
            implements RedeemResult {

        public Success {
            if (!success) {
                throw new IllegalArgumentException("success must be true");
            }
            Objects.requireNonNull(token, "token");
        }
    }

    record Failure(boolean success, String reason, boolean instrError, @Nullable String error)
            implements RedeemResult {

        public Failure {
            if (success) {
                throw new IllegalArgumentException("success must be false");
            }
            Objects.requireNonNull(reason, "reason");
        }
    }
}

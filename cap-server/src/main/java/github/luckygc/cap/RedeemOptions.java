package github.luckygc.cap;

import java.time.Duration;
import org.jspecify.annotations.Nullable;

public final class RedeemOptions {

    private static final Duration DEFAULT_TOKEN_TTL = Duration.ofMinutes(20);
    private static final RedeemOptions DEFAULTS = new Builder().build();

    private final @Nullable String expectedScope;
    private final Duration tokenTtl;

    private RedeemOptions(Builder builder) {
        expectedScope = builder.expectedScope;
        tokenTtl = ChallengeOptions.validateDuration(builder.tokenTtl, "tokenTtl");
    }

    public static RedeemOptions defaults() {
        return DEFAULTS;
    }

    public static Builder builder() {
        return new Builder();
    }

    public @Nullable String expectedScope() {
        return expectedScope;
    }

    public Duration tokenTtl() {
        return tokenTtl;
    }

    public static final class Builder {

        private @Nullable String expectedScope;
        private Duration tokenTtl = DEFAULT_TOKEN_TTL;

        private Builder() {}

        public Builder expectedScope(@Nullable String expectedScope) {
            this.expectedScope = expectedScope;
            return this;
        }

        public Builder tokenTtl(Duration tokenTtl) {
            this.tokenTtl = ChallengeOptions.validateDuration(tokenTtl, "tokenTtl");
            return this;
        }

        public RedeemOptions build() {
            return new RedeemOptions(this);
        }
    }
}

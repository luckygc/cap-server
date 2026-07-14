package github.luckygc.cap;

import java.util.Objects;

public final class InstrumentationOptions {

    private static final InstrumentationTransformer IDENTITY_TRANSFORMER =
            (script, level) -> script;
    private static final InstrumentationOptions DEFAULTS = new Builder().build();

    private final int level;
    private final boolean blockAutomatedBrowsers;
    private final InstrumentationTransformer transformer;

    private InstrumentationOptions(Builder builder) {
        level = builder.level;
        blockAutomatedBrowsers = builder.blockAutomatedBrowsers;
        transformer = builder.transformer;
    }

    public static InstrumentationOptions defaults() {
        return DEFAULTS;
    }

    public static Builder builder() {
        return new Builder();
    }

    public int level() {
        return level;
    }

    public boolean blockAutomatedBrowsers() {
        return blockAutomatedBrowsers;
    }

    public InstrumentationTransformer transformer() {
        return transformer;
    }

    public static final class Builder {

        private int level = 3;
        private boolean blockAutomatedBrowsers;
        private InstrumentationTransformer transformer = IDENTITY_TRANSFORMER;

        private Builder() {}

        public Builder level(int level) {
            if (level < 0 || level > 3) {
                throw new IllegalArgumentException("level must be between 0 and 3");
            }
            this.level = level;
            return this;
        }

        public Builder blockAutomatedBrowsers(boolean blockAutomatedBrowsers) {
            this.blockAutomatedBrowsers = blockAutomatedBrowsers;
            return this;
        }

        public Builder transformer(InstrumentationTransformer transformer) {
            this.transformer = Objects.requireNonNull(transformer, "transformer");
            return this;
        }

        public InstrumentationOptions build() {
            return new InstrumentationOptions(this);
        }
    }
}

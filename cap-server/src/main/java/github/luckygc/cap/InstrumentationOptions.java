package github.luckygc.cap;

import github.luckygc.cap.internal.instrumentation.InstrumentationGenerator;
import java.util.Objects;

public final class InstrumentationOptions {

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
        private InstrumentationTransformer transformer =
                InstrumentationGenerator.builtInTransformer();

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

        /**
         * 设置可信的同步 transformer。
         *
         * <p>transformer 可见完整脚本和 nonce 相关内容，并在生成 challenge 的调用线程执行；同一 {@link Cap}
         * 可被并发调用，实现必须线程安全。其阻塞和外部副作用由调用方负责；本库不提供执行超时、内存隔离或 JVM sandbox，仅校验异常、返回值和输出大小。
         */
        public Builder transformer(InstrumentationTransformer transformer) {
            this.transformer = Objects.requireNonNull(transformer, "transformer");
            return this;
        }

        public InstrumentationOptions build() {
            return new InstrumentationOptions(this);
        }
    }
}

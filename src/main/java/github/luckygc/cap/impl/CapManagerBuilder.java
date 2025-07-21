package github.luckygc.cap.impl;

import github.luckygc.cap.CapManager;
import github.luckygc.cap.CapStore;
import github.luckygc.cap.config.CapTokenConfig;
import github.luckygc.cap.config.ChallengeConfig;
import github.luckygc.cap.config.ChallengeConfig.Builder;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 示例用法：
 *
 * <pre>{@code
 * CapManager manager = new CapManagerBuilder()
 *         .challengeConfig(c -> c
 *                 .count(5)
 *                 .size(200)
 *                 .difficulty(3)
 *                 .expireMs(300000L))
 *         .capTokenConfig(c -> c
 *                 .expireMs(600000L))
 *         .build();
 * }</pre>
 */
public class CapManagerBuilder {

    private CapStore capStore;
    private ChallengeConfig challengeConfig;
    private CapTokenConfig capTokenConfig;

    /**
     * 设置 CAPTCHA 存储配置。
     *
     * @param capStore CAPTCHA 数据存储实现
     * @return 当前构建器实例，用于方法链式调用
     */
    private CapManagerBuilder store(CapStore capStore) {
        this.capStore = capStore;
        return this;
    }

    /**
     * 配置挑战（Challenge）相关参数。
     *
     * @param customizerConsumer 用于配置挑战参数的消费者函数
     * @return 当前构建器实例，用于方法链式调用
     */
    public CapManagerBuilder challenge(Consumer<ChallengeConfigCustomizer> customizerConsumer) {
        ChallengeConfigCustomizer customizer = new ChallengeConfigCustomizer();
        customizerConsumer.accept(customizer);
        this.challengeConfig = customizer.builder.build();
        return this;
    }

    /**
     * 配置 CAPTCHA 令牌相关参数。
     *
     * @param customizerConsumer 用于配置令牌参数的消费者函数
     * @return 当前构建器实例，用于方法链式调用
     */
    public CapManagerBuilder capToken(Consumer<CapTokenConfigCustomizer> customizerConsumer) {
        CapTokenConfigCustomizer customizer = new CapTokenConfigCustomizer();
        customizerConsumer.accept(customizer);
        this.capTokenConfig = customizer.builder.build();
        return this;
    }

    /**
     * 构建并返回配置好的 CapManager 实例。
     *
     * @return 配置完成的 CapManager 实例
     * @throws IllegalArgumentException 如果必需的 capStore 为 null
     */
    public CapManager build() {
        capStore = Objects.requireNonNullElseGet(capStore, MemoryCapStore::new);
        challengeConfig = Objects.requireNonNullElseGet(challengeConfig, ChallengeConfig::new);
        capTokenConfig = Objects.requireNonNullElseGet(capTokenConfig, CapTokenConfig::new);
        return new CapManagerImpl(capStore, challengeConfig, capTokenConfig);
    }

    /**
     * 挑战配置自定义器
     */
    public static class ChallengeConfigCustomizer {

        private final ChallengeConfig.Builder builder = new Builder();

        /**
         * 设置挑战数量
         *
         * @param count 挑战数量
         * @return 当前构建器实例，用于方法链式调用
         */
        public ChallengeConfigCustomizer count(Integer count) {
            builder.count(count);
            return this;
        }

        /**
         * 设置挑战长度
         *
         * @param size 挑战长度
         * @return 当前构建器实例，用于方法链式调用
         */
        public ChallengeConfigCustomizer size(Integer size) {
            builder.size(size);
            return this;
        }

        /**
         * 设置挑战难度
         *
         * @param difficulty 挑战难度
         * @return 当前构建器实例，用于方法链式调用
         */
        public ChallengeConfigCustomizer difficulty(Integer difficulty) {
            builder.difficulty(difficulty);
            return this;
        }

        /**
         * 设置挑战过期时间
         *
         * @param expireMs 挑战过期时间，单位为毫秒
         * @return 当前构建器实例，用于方法链式调用
         */
        public ChallengeConfigCustomizer expireMs(Long expireMs) {
            builder.expireMs(expireMs);
            return this;
        }
    }

    /**
     * CAPTCHA 令牌配置自定义器
     */
    public static class CapTokenConfigCustomizer {

        private final CapTokenConfig.Builder builder = new CapTokenConfig.Builder();

        /**
         * 设置 CAPTCHA 令牌过期时间
         *
         * @param expireMs CAPTCHA 令牌过期时间，单位为毫秒
         * @return 当前构建器实例，用于方法链式调用
         */
        public CapTokenConfigCustomizer expireMs(Long expireMs) {
            builder.expireMs(expireMs);
            return this;
        }
    }
}

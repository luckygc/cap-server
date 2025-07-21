package github.luckygc.cap.impl;

import github.luckygc.cap.CapManager;
import github.luckygc.cap.CapStore;
import github.luckygc.cap.config.CapTokenConfig;
import github.luckygc.cap.config.ChallengeConfig;
import github.luckygc.cap.utils.Messages;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * CapManager 的构建器类，用于配置和创建 CapManager 实例。 使用构建器模式来提供流畅的 API 来配置 CAPTCHA 管理器的各种参数。
 *
 * <p>示例用法：</p>
 * <pre>{@code
 * CapManager manager = CapManagerBuilder.store(new MemoryCapStore())
 *     .challengeConfig(config -> config
 *         .count(5)
 *         .size(200)
 *         .difficulty(3)
 *         .expireMs(300000L))
 *     .capTokenConfig(config -> config
 *         .expireMs(600000L))
 *     .build();
 * }</pre>
 */
public class CapManagerBuilder {

    private CapStore capStore;
    private ChallengeConfig challengeConfig;
    private CapTokenConfig capTokenConfig;

    /**
     * 创建一个新的 CapManagerBuilder 实例并设置存储。 这是创建构建器的入口点。
     *
     * @param capStore CAPTCHA 数据存储实现，不能为 null
     * @return 配置了存储的构建器实例
     */
    public static CapManagerBuilder store(CapStore capStore) {
        return new CapManagerBuilder().storeConfig(capStore);
    }

    /**
     * 设置 CAPTCHA 存储配置。
     *
     * @param capStore CAPTCHA 数据存储实现
     * @return 当前构建器实例，用于方法链式调用
     */
    private CapManagerBuilder storeConfig(CapStore capStore) {
        this.capStore = capStore;
        return this;
    }

    /**
     * 配置挑战（Challenge）相关参数。
     *
     * @param configurer 用于配置挑战参数的消费者函数
     * @return 当前构建器实例，用于方法链式调用
     */
    public CapManagerBuilder challengeConfig(Consumer<ChallengeConfigurer> configurer) {
        ChallengeConfigurer challengeConfigurer = new ChallengeConfigurer();
        configurer.accept(challengeConfigurer);
        this.challengeConfig = new ChallengeConfig();
        consumeIfNotNull(challengeConfigurer.count, challengeConfig::setCount);
        consumeIfNotNull(challengeConfigurer.size, challengeConfig::setSize);
        consumeIfNotNull(challengeConfigurer.difficulty, challengeConfig::setDifficulty);
        consumeIfNotNull(challengeConfigurer.expireMs, challengeConfig::setExpireMs);
        return this;
    }

    /**
     * 配置 CAPTCHA 令牌相关参数。
     *
     * @param configurer 用于配置令牌参数的消费者函数
     * @return 当前构建器实例，用于方法链式调用
     */
    public CapManagerBuilder capTokenConfig(Consumer<CapTokenConfigurer> configurer) {
        CapTokenConfigurer capTokenConfigurer = new CapTokenConfigurer();
        configurer.accept(capTokenConfigurer);
        this.capTokenConfig = new CapTokenConfig();
        consumeIfNotNull(capTokenConfigurer.expireMs, capTokenConfig::setExpireMs);
        return this;
    }

    /**
     * 构建并返回配置好的 CapManager 实例。
     *
     * @return 配置完成的 CapManager 实例
     * @throws IllegalArgumentException 如果必需的 capStore 为 null
     */
    public CapManager build() {
        Messages.requireNonNull(capStore, "capStore");

        challengeConfig = Objects.requireNonNullElseGet(challengeConfig, ChallengeConfig::new);
        capTokenConfig = Objects.requireNonNullElseGet(capTokenConfig, CapTokenConfig::new);
        return new CapManagerImpl(capStore, challengeConfig, capTokenConfig);
    }

    /**
     * 如果对象不为 null，则使用消费者处理该对象。
     *
     * @param <T>      对象类型
     * @param obj      要处理的对象
     * @param consumer 处理对象的消费者
     */
    private <T> void consumeIfNotNull(T obj, Consumer<T> consumer) {
        if (obj != null) {
            consumer.accept(obj);
        }
    }

    /**
     * 挑战配置器，用于配置 CAPTCHA 挑战的各种参数。
     */
    public static class ChallengeConfigurer {

        private Integer count;
        private Integer size;
        private Integer difficulty;
        private Long expireMs;

        /**
         * 设置挑战数量。
         *
         * @param count 挑战数量
         * @return 当前配置器实例，用于方法链式调用
         */
        public ChallengeConfigurer count(Integer count) {
            this.count = count;
            return this;
        }

        /**
         * 设置挑战大小。
         *
         * @param size 挑战大小
         * @return 当前配置器实例，用于方法链式调用
         */
        public ChallengeConfigurer size(Integer size) {
            this.size = size;
            return this;
        }

        /**
         * 设置挑战难度。
         *
         * @param difficulty 挑战难度
         * @return 当前配置器实例，用于方法链式调用
         */
        public ChallengeConfigurer difficulty(Integer difficulty) {
            this.difficulty = difficulty;
            return this;
        }

        /**
         * 设置挑战过期时间（毫秒）。
         *
         * @param expireMs 过期时间，单位为毫秒
         * @return 当前配置器实例，用于方法链式调用
         */
        public ChallengeConfigurer expireMs(Long expireMs) {
            this.expireMs = expireMs;
            return this;
        }
    }

    /**
     * CAPTCHA 令牌配置器，用于配置令牌相关参数。
     */
    public static class CapTokenConfigurer {

        private Long expireMs;

        /**
         * 设置令牌过期时间（毫秒）。
         *
         * @param expireMs 过期时间，单位为毫秒
         * @return 当前配置器实例，用于方法链式调用
         */
        public CapTokenConfigurer expireMs(Long expireMs) {
            this.expireMs = expireMs;
            return this;
        }
    }
}

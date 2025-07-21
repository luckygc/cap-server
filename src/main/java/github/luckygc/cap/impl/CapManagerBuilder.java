package github.luckygc.cap.impl;

import github.luckygc.cap.CapManager;
import github.luckygc.cap.CapStore;
import github.luckygc.cap.config.CapTokenConfig;
import github.luckygc.cap.config.ChallengeConfig;
import github.luckygc.cap.utils.Validator;
import java.util.function.Consumer;

public class CapManagerBuilder {

    private CapStore capStore;
    private ChallengeConfig challengeConfig;
    private CapTokenConfigurer capTokenConfigurer;

    public static CapManagerBuilder store(CapStore capStore) {
        return new CapManagerBuilder().storeConfig(capStore);
    }

    private CapManagerBuilder storeConfig(CapStore capStore) {
        this.capStore = capStore;
        return this;
    }

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

    public CapManagerBuilder capTokenConfig(Consumer<CapTokenConfigurer> configurer) {
        CapTokenConfigurer capTokenConfigurer = new CapTokenConfigurer();
        configurer.accept(capTokenConfigurer);
        this.capTokenConfigurer = capTokenConfigurer;
        return this;
    }

    public CapManager build() {
        Validator.notNull(capStore, "capStore");

        CapTokenConfig capTokenConfig = new CapTokenConfig();
        if (capTokenConfigurer != null) {
            consumeIfNotNull(capTokenConfigurer.expireMs, capTokenConfig::setExpireMs);
        }

        return new CapManagerImpl(capStore, challengeConfig, capTokenConfig);
    }

    private <T> void consumeIfNotNull(T obj, Consumer<T> consumer) {
        if (obj != null) {
            consumer.accept(obj);
        }
    }

    public static class ChallengeConfigurer {

        private Integer count;
        private Integer size;
        private Integer difficulty;
        private Long expireMs;

        public ChallengeConfigurer count(Integer count) {
            this.count = count;
            return this;
        }

        public ChallengeConfigurer size(Integer size) {
            this.size = size;
            return this;
        }

        public ChallengeConfigurer difficulty(Integer difficulty) {
            this.difficulty = difficulty;
            return this;
        }

        public ChallengeConfigurer expireMs(Long expireMs) {
            this.expireMs = expireMs;
            return this;
        }
    }

    public static class CapTokenConfigurer {

        private Long expireMs;

        public CapTokenConfigurer expireMs(Long expireMs) {
            this.expireMs = expireMs;
            return this;
        }
    }
}

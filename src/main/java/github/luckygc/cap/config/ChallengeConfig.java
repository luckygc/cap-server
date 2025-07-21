package github.luckygc.cap.config;

public class ChallengeConfig {

    /**
     * 生成的挑战数量
     */
    private int count = 50;

    /**
     * 每条挑战的长度
     */
    private int size = 32;

    /**
     * 挑战难度,目标前缀长度
     */
    private int difficulty = 4;

    /**
     * 挑战过期时间，毫秒
     */
    private long expireMs = 5 * 60 * 1000;

    public ChallengeConfig() {
    }

    private ChallengeConfig(Builder builder) {
        if (builder.count != null) {
            count = builder.count;
        }

        if (builder.size != null) {
            size = builder.size;
        }

        if (builder.difficulty != null) {
            difficulty = builder.difficulty;
        }

        if (builder.expireMs != null) {
            expireMs = builder.expireMs;
        }
    }

    public int getCount() {
        return count;
    }

    public int getSize() {
        return size;
    }

    public int getDifficulty() {
        return difficulty;
    }

    public long getExpireMs() {
        return expireMs;
    }

    public static class Builder {

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
        public Builder count(Integer count) {
            this.count = count;
            return this;
        }

        /**
         * 设置挑战大小。
         *
         * @param size 挑战大小
         * @return 当前配置器实例，用于方法链式调用
         */
        public Builder size(Integer size) {
            this.size = size;
            return this;
        }

        /**
         * 设置挑战难度。
         *
         * @param difficulty 挑战难度
         * @return 当前配置器实例，用于方法链式调用
         */
        public Builder difficulty(Integer difficulty) {
            this.difficulty = difficulty;
            return this;
        }

        /**
         * 设置挑战过期时间（毫秒）。
         *
         * @param expireMs 过期时间，单位为毫秒
         * @return 当前配置器实例，用于方法链式调用
         */
        public Builder expireMs(Long expireMs) {
            this.expireMs = expireMs;
            return this;
        }

        /**
         * 构建并返回配置好的 ChallengeConfig 实例。
         *
         * @return 配置完成的 ChallengeConfig 实例
         */
        public ChallengeConfig build() {
            return new ChallengeConfig(this);
        }
    }
}

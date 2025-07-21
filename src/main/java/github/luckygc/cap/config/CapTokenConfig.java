package github.luckygc.cap.config;

public class CapTokenConfig {

    /**
     * 过期时间，毫秒.默认两分钟
     */
    private long expireMs = 2 * 60 * 1000;

    public CapTokenConfig() {
    }

    private CapTokenConfig(Builder builder) {
        if (builder.expireMs != null) {
            expireMs = builder.expireMs;
        }
    }

    public long getExpireMs() {
        return expireMs;
    }

    public static class Builder {

        private Long expireMs;

        /**
         * 设置过期时间
         *
         * @param expireMs 过期时间，单位为毫秒
         * @return 当前构建器实例，用于方法链式调用
         */
        public Builder expireMs(Long expireMs) {
            this.expireMs = expireMs;
            return this;
        }

        /**
         * 构建并返回配置好的 CapTokenConfig 实例。
         *
         * @return 配置完成的 CapTokenConfig 实例
         */
        public CapTokenConfig build() {
            return new CapTokenConfig(this);
        }
    }
}

package github.luckygc.cap.config;

public class CapTokenConfig {

    /**
     * 过期时间，毫秒.默认两分钟
     */
    private long expireMs = 2 * 60 * 1000;

    public long getExpireMs() {
        return expireMs;
    }

    public void setExpireMs(long expireMs) {
        this.expireMs = expireMs;
    }
}

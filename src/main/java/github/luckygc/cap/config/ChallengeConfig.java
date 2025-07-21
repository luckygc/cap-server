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

    public int getCount() {
        return count;
    }

    public ChallengeConfig setCount(int count) {
        this.count = count;
        return this;
    }

    public int getSize() {
        return size;
    }

    public ChallengeConfig setSize(int size) {
        this.size = size;
        return this;
    }

    public int getDifficulty() {
        return difficulty;
    }

    public ChallengeConfig setDifficulty(int difficulty) {
        this.difficulty = difficulty;
        return this;
    }

    public long getExpireMs() {
        return expireMs;
    }

    public ChallengeConfig setExpireMs(long expireMs) {
        this.expireMs = expireMs;
        return this;
    }
}

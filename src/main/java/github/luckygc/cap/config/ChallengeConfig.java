package github.luckygc.cap.config;

public class ChallengeConfig {

    /**
     * 生成的挑战数量
     */
    private int challengeCount = 50;

    /**
     * 每条挑战的长度
     */
    private int challengeSize = 32;

    /**
     * 挑战难度
     */
    private int challengeDifficulty = 4;

    /**
     * 挑战过期时间，毫秒
     */
    private long challengeExpireMs = 5 * 60 * 1000;

    public int getChallengeCount() {
        return challengeCount;
    }

    public ChallengeConfig setChallengeCount(int challengeCount) {
        this.challengeCount = challengeCount;
        return this;
    }

    public int getChallengeSize() {
        return challengeSize;
    }

    public ChallengeConfig setChallengeSize(int challengeSize) {
        this.challengeSize = challengeSize;
        return this;
    }

    public int getChallengeDifficulty() {
        return challengeDifficulty;
    }

    public ChallengeConfig setChallengeDifficulty(int challengeDifficulty) {
        this.challengeDifficulty = challengeDifficulty;
        return this;
    }

    public long getChallengeExpireMs() {
        return challengeExpireMs;
    }

    public ChallengeConfig setChallengeExpireMs(long challengeExpireMs) {
        this.challengeExpireMs = challengeExpireMs;
        return this;
    }
}

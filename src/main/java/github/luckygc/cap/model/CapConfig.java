package github.luckygc.cap.model;

public class CapConfig {

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

    public void setChallengeCount(int challengeCount) {
        this.challengeCount = challengeCount;
    }

    public int getChallengeSize() {
        return challengeSize;
    }

    public void setChallengeSize(int challengeSize) {
        this.challengeSize = challengeSize;
    }

    public int getChallengeDifficulty() {
        return challengeDifficulty;
    }

    public void setChallengeDifficulty(int challengeDifficulty) {
        this.challengeDifficulty = challengeDifficulty;
    }

    public long getChallengeExpireMs() {
        return challengeExpireMs;
    }

    public void setChallengeExpireMs(long challengeExpireMs) {
        this.challengeExpireMs = challengeExpireMs;
    }
}

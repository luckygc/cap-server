package github.luckygc.cap.model;

/**
 * @param c 挑战数量
 * @param s 挑战长度
 * @param d 难度级别
 */
public record Challenge(int c, int s, int d) {

    public static Challenge of(CapConfig config) {
        return new Challenge(config.getChallengeCount(), config.getChallengeSize(), config.getChallengeDifficulty());
    }
}

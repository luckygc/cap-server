package github.luckygc.cap.model;

import github.luckygc.cap.config.ChallengeConfig;

/**
 * @param c 挑战数量
 * @param s 挑战长度
 * @param d 难度级别
 */
public record Challenge(int c, int s, int d) {

    public static Challenge of(ChallengeConfig config) {
        return new Challenge(config.getCount(), config.getSize(), config.getDifficulty());
    }
}

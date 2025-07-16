package github.luckygc.cap;

import github.luckygc.cap.config.ChallengeConfig;
import github.luckygc.cap.model.ChallengeData;
import github.luckygc.cap.model.RedeemChallengeRequest;
import github.luckygc.cap.model.RedeemChallengeResponse;

public interface CapManager {

    /**
     * 创建挑战,使用默认的挑战配置
     *
     * @return 挑战数据
     */
    ChallengeData createChallenge();

    /**
     * 创建挑战
     *
     * @param challengeConfig 挑战配置
     * @return 挑战数据
     */
    ChallengeData createChallenge(ChallengeConfig challengeConfig);

    /**
     * 兑换挑战
     *
     * @param redeemChallengeRequest 兑换挑战请求
     * @return 兑换挑战的结果
     */
    RedeemChallengeResponse redeemChallenge(RedeemChallengeRequest redeemChallengeRequest);

    /**
     * 验证该token是否通过挑战之后生成的,并且未过期
     *
     * @param capToken 挑战通过后返回的token
     * @return 是否有效token
     */
    boolean validateCapToken(String capToken);
}

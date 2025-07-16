package github.luckygc.cap;

import github.luckygc.cap.model.CapToken;
import github.luckygc.cap.model.ChallengeData;
import java.util.Optional;

public interface CapStore {

    /**
     * 清理过期ChallengeData和CapToken
     */
    void cleanExpiredTokens();

    /**
     * 保存挑战数据
     *
     * @param challengeData 挑战数据
     */
    void saveChallengeData(ChallengeData challengeData);

    /**
     * 根据挑战唯一标识ChallengeData.token查找挑战数据
     *
     * @param token 挑战唯一标识
     * @return 挑战数据
     */
    Optional<ChallengeData> findChallengeData(String token);

    /**
     * 删除挑战数据
     *
     * @param challengeData 挑战数据
     */
    void deleteChallengeData(ChallengeData challengeData);

    /**
     * 保存通过挑战后生成的token
     *
     * @param capToken 通过挑战后生成的token
     */
    void saveCapToken(CapToken capToken);

    /**
     * 根据挑CapToken.token查找CapToken
     */
    Optional<CapToken> findCapToken(String token);
}

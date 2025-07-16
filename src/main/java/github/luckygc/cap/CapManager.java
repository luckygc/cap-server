package github.luckygc.cap;

import github.luckygc.cap.config.ChallengeConfig;
import github.luckygc.cap.model.CapToken;
import github.luckygc.cap.model.Challenge;
import github.luckygc.cap.model.ChallengeData;
import github.luckygc.cap.model.RedeemChallengeRequest;
import github.luckygc.cap.model.RedeemChallengeResponse;
import github.luckygc.cap.utils.RandomUtil;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.RandomUtils;
import org.apache.commons.lang3.StringUtils;

public class CapManager {

    /**
     * 默认挑战成功后生成的token过期时间，2分钟
     */
    public static final long DEFAULT_CAP_TOKEN_EXPIRE_MS = 2 * 60 * 1000;

    private static final int CHALLENGE_TOKEN_BYTES_SIZE = 25;
    private static final int CAP_TOKEN_VER_TOKEN_BYTES_SIZE = 15;
    private static final int CAP_TOKEN_ID_BYTES_SIZE = 8;
    private static final String CAP_TOKEN_SEPARATOR = ":";

    private final CapStore capStore;

    private CapManager(CapStore capStore) {
        this.capStore = capStore;
    }

    /**
     * 创建挑战
     *
     * @param challengeConfig 挑战配置
     * @return 挑战数据
     */
    public ChallengeData createChallenge(ChallengeConfig challengeConfig) {
        capStore.cleanExpiredTokens();

        String token = Hex.encodeHexString(RandomUtils.secureStrong().randomBytes(CHALLENGE_TOKEN_BYTES_SIZE));
        long expires = System.currentTimeMillis() + challengeConfig.getChallengeExpireMs();
        Challenge challenge = Challenge.of(challengeConfig);
        ChallengeData challengeData = new ChallengeData(token, challenge, expires);
        capStore.saveChallengeData(challengeData);

        return challengeData;
    }

    /**
     * 兑换挑战
     *
     * @param redeemChallengeRequest 兑换挑战请求
     * @return 兑换挑战的结果
     */
    public RedeemChallengeResponse redeemChallenge(RedeemChallengeRequest redeemChallengeRequest) {
        String challengeToken = redeemChallengeRequest.token();
        List<Integer> solutions = redeemChallengeRequest.solutions();
        if (StringUtils.isEmpty(challengeToken) || solutions == null || solutions.isEmpty()) {
            return RedeemChallengeResponse.error("Invalid body");
        }

        capStore.cleanExpiredTokens();

        Optional<ChallengeData> challengeDataOptional = capStore.findChallengeData(redeemChallengeRequest.token());
        if (challengeDataOptional.isEmpty()) {
            return RedeemChallengeResponse.error("Challenge expired");
        }

        ChallengeData challengeData = challengeDataOptional.get();
        if (challengeData.expires() < System.currentTimeMillis()) {
            capStore.deleteChallengeData(challengeData);
            return RedeemChallengeResponse.error("Challenge expired");
        }

        capStore.deleteChallengeData(challengeData);

        Challenge challenge = challengeData.challenge();
        boolean isValid = IntStream.range(0, challenge.c()).allMatch(i -> {
            String salt = RandomUtil.prng("%s%d".formatted(challengeToken, i + 1), challenge.s());
            String target = RandomUtil.prng("%s%dd".formatted(challengeToken, i + 1), challenge.d());
            int solution = solutions.get(i);
            return DigestUtils.sha256Hex(salt + solution).startsWith(target);
        });

        if (!isValid) {
            return RedeemChallengeResponse.error("Invalid solution");
        }

        String vertoken = Hex.encodeHexString(RandomUtils.secureStrong().randomBytes(CAP_TOKEN_VER_TOKEN_BYTES_SIZE));
        long expires = System.currentTimeMillis() + DEFAULT_CAP_TOKEN_EXPIRE_MS;
        String hash = DigestUtils.sha256Hex(vertoken);
        String id = Hex.encodeHexString(RandomUtils.secureStrong().randomBytes(CAP_TOKEN_ID_BYTES_SIZE));

        CapToken actualToken = new CapToken(buildCapTokenString(id, hash), expires);
        capStore.saveCapToken(actualToken);

        CapToken capTokenResponse = new CapToken(buildCapTokenString(id, vertoken), expires);
        return RedeemChallengeResponse.ok(capTokenResponse);
    }

    private String buildCapTokenString(String id, String hashOrVertoken) {
        return "%s%s%s".formatted(id, CAP_TOKEN_SEPARATOR, hashOrVertoken);
    }

    /**
     * 验证该token是否通过挑战之后生成的,并且未过期
     *
     * @param capToken 挑战通过后返回的token
     * @return 是否有效token
     */
    public boolean validateCapToken(String capToken) {
        capStore.cleanExpiredTokens();

        String[] idAndVertoken = parseCapTokenString(capToken);
        if (idAndVertoken.length != 2) {
            throw new IllegalArgumentException("人机验证失败");
        }

        String id = idAndVertoken[0];
        String hash = DigestUtils.sha256Hex(idAndVertoken[1]);
        String actualToken = buildCapTokenString(id, hash);

        return capStore.findCapToken(actualToken)
                .filter(token -> token.expires() < System.currentTimeMillis())
                .isPresent();
    }

    private String[] parseCapTokenString(String capToken) {
        return StringUtils.split(capToken, CAP_TOKEN_SEPARATOR);
    }
}

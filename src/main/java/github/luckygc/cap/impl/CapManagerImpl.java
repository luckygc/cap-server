package github.luckygc.cap.impl;

import github.luckygc.cap.CapManager;
import github.luckygc.cap.CapStore;
import github.luckygc.cap.config.CapTokenConfig;
import github.luckygc.cap.config.ChallengeConfig;
import github.luckygc.cap.model.CapToken;
import github.luckygc.cap.model.Challenge;
import github.luckygc.cap.model.ChallengeData;
import github.luckygc.cap.model.RedeemChallengeRequest;
import github.luckygc.cap.model.RedeemChallengeResponse;
import github.luckygc.cap.utils.RandomUtil;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.stream.IntStream;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.RandomUtils;
import org.apache.commons.lang3.StringUtils;

public class CapManagerImpl implements CapManager {

    private static final int CHALLENGE_TOKEN_BYTES_SIZE = 25;
    private static final int CAP_TOKEN_VER_TOKEN_BYTES_SIZE = 15;
    private static final int CAP_TOKEN_ID_BYTES_SIZE = 8;
    private static final String CAP_TOKEN_SEPARATOR = ":";

    private final CapStore capStore;
    private final ChallengeConfig defaultChallengeConfig;
    private final CapTokenConfig capTokenConfig;
    private final ResourceBundle resourceBundle;

    private CapManagerImpl(Builder builder) {
        if (builder.locale == null) {
            this.resourceBundle = ResourceBundle.getBundle("github.luckygc.cap.Messages");
        } else {
            this.resourceBundle = ResourceBundle.getBundle("github.luckygc.cap.Messages", builder.locale);
        }

        this.capStore = Objects.requireNonNull(builder.capStore, this.resourceBundle.getString("capStoreNonNull"));

        this.defaultChallengeConfig = Objects.requireNonNullElseGet(builder.defaultChallengeConfig,
                ChallengeConfig::new);

        this.capTokenConfig = Objects.requireNonNullElseGet(builder.capTokenConfig, CapTokenConfig::new);
    }

    @Override
    public ChallengeData createChallenge() {
        return createChallenge(defaultChallengeConfig);
    }

    @Override
    public ChallengeData createChallenge(ChallengeConfig challengeConfig) {
        capStore.cleanExpiredTokens();

        String token = Hex.encodeHexString(RandomUtils.secureStrong().randomBytes(CHALLENGE_TOKEN_BYTES_SIZE));
        long expires = System.currentTimeMillis() + challengeConfig.getChallengeExpireMs();
        Challenge challenge = Challenge.of(challengeConfig);
        ChallengeData challengeData = new ChallengeData(token, challenge, expires);
        capStore.saveChallengeData(challengeData);

        return challengeData;
    }

    @Override
    public RedeemChallengeResponse redeemChallenge(RedeemChallengeRequest redeemChallengeRequest) {
        String challengeToken = redeemChallengeRequest.token();
        List<Integer> solutions = redeemChallengeRequest.solutions();
        if (StringUtils.isEmpty(challengeToken) || solutions == null || solutions.isEmpty()) {
            return RedeemChallengeResponse.error(resourceBundle.getString("invalidParams"));
        }

        capStore.cleanExpiredTokens();

        Optional<ChallengeData> challengeDataOptional = capStore.findChallengeData(redeemChallengeRequest.token());
        if (challengeDataOptional.isEmpty()) {
            return RedeemChallengeResponse.error(resourceBundle.getString("challengeExpired"));
        }

        ChallengeData challengeData = challengeDataOptional.get();
        if (challengeData.expires() < System.currentTimeMillis()) {
            capStore.deleteChallengeData(challengeData);
            return RedeemChallengeResponse.error(resourceBundle.getString("challengeExpired"));
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
            return RedeemChallengeResponse.error(resourceBundle.getString("invalidSolutions"));
        }

        String vertoken = Hex.encodeHexString(RandomUtils.secureStrong().randomBytes(CAP_TOKEN_VER_TOKEN_BYTES_SIZE));
        long expires = System.currentTimeMillis() + capTokenConfig.getExpireMs();
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
    @Override
    public boolean validateCapToken(String capToken) {
        capStore.cleanExpiredTokens();

        String[] idAndVertoken = parseCapTokenString(capToken);
        if (idAndVertoken.length != 2) {
            return false;
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


    public static class Builder {

        private CapStore capStore;
        private ChallengeConfig defaultChallengeConfig;
        private CapTokenConfig capTokenConfig;
        private Locale locale;

        public Builder capStore(CapStore capStore) {
            this.capStore = capStore;
            return this;
        }

        public Builder defaultChallengeConfig(ChallengeConfig defaultChallengeConfig) {
            this.defaultChallengeConfig = defaultChallengeConfig;
            return this;
        }

        public Builder capTokenConfig(CapTokenConfig capTokenConfig) {
            this.capTokenConfig = capTokenConfig;
            return this;
        }

        public Builder locale(Locale locale) {
            this.locale = locale;
            return this;
        }

        public CapManager build() {
            return new CapManagerImpl(this);
        }
    }
}

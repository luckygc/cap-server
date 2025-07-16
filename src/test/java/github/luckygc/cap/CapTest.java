package github.luckygc.cap;

import static org.assertj.core.api.Assertions.assertThat;

import github.luckygc.cap.config.ChallengeConfig;
import github.luckygc.cap.impl.CapManagerImpl.Builder;
import github.luckygc.cap.impl.MemoryCapStore;
import github.luckygc.cap.model.ChallengeData;
import org.junit.jupiter.api.Test;

public class CapTest {

    @Test
    void testBuildCapManager() {
        CapManager capManager = new Builder()
                .capStore(new MemoryCapStore())
                .build();

        assertThat(capManager).isNotNull();
    }

    @Test
    void testDefaultChallengeConfig() {
        ChallengeConfig challengeConfig = new ChallengeConfig();
        CapManager capManager = new Builder()
                .capStore(new MemoryCapStore())
                .defaultChallengeConfig(challengeConfig)
                .build();

        long startExpireTimeMillis = getExpireTimeMillis(challengeConfig.getChallengeExpireMs());
        ChallengeData challengeData = capManager.createChallenge();
        long endExpireTimeMillis = getExpireTimeMillis(challengeConfig.getChallengeExpireMs());

        assertThat(challengeData.expires()).isBetween(startExpireTimeMillis, endExpireTimeMillis);

        assertThat(challengeData.challenge()).isNotNull()
                .satisfies(challenge -> {
                    assertThat(challenge.c()).isEqualTo(challengeConfig.getChallengeCount());
                    assertThat(challenge.s()).isEqualTo(challengeConfig.getChallengeSize());
                    assertThat(challenge.d()).isEqualTo(challengeConfig.getChallengeDifficulty());
                });
    }

    @Test
    void testChallengeCountConfig() {
        int expectedChallengeCount = 5;

        CapManager capManager = new Builder()
                .capStore(new MemoryCapStore())
                .defaultChallengeConfig(new ChallengeConfig().setChallengeCount(expectedChallengeCount))
                .build();

        ChallengeData challengeData = capManager.createChallenge();
        assertThat(challengeData.challenge()).isNotNull()
                .satisfies(challenge -> assertThat(challenge.c()).isEqualTo(expectedChallengeCount));
    }

    @Test
    void testChallengeSizeConfig() {
        int expectedChallengeSize = 30;

        CapManager capManager = new Builder()
                .capStore(new MemoryCapStore())
                .defaultChallengeConfig(new ChallengeConfig().setChallengeSize(expectedChallengeSize))
                .build();

        ChallengeData challengeData = capManager.createChallenge();
        assertThat(challengeData.challenge()).isNotNull()
                .satisfies(challenge -> assertThat(challenge.s()).isEqualTo(expectedChallengeSize));
    }

    @Test
    void testChallengeDifficultyConfig() {
        int expectedChallengeDifficulty = 6;

        CapManager capManager = new Builder()
                .capStore(new MemoryCapStore())
                .defaultChallengeConfig(new ChallengeConfig().setChallengeDifficulty(expectedChallengeDifficulty))
                .build();

        ChallengeData challengeData = capManager.createChallenge();
        assertThat(challengeData.challenge()).isNotNull()
                .satisfies(challenge -> assertThat(challenge.d()).isEqualTo(expectedChallengeDifficulty));
    }

    @Test
    void testChallengeExpireConfig() {
        long expectedChallengeExpireMs = 3 * 1000;

        CapManager capManager = new Builder()
                .capStore(new MemoryCapStore())
                .defaultChallengeConfig(new ChallengeConfig().setChallengeExpireMs(expectedChallengeExpireMs))
                .build();

        long startExpireTimeMillis = getExpireTimeMillis(expectedChallengeExpireMs);
        ChallengeData challengeData = capManager.createChallenge();
        long endExpireTimeMillis = getExpireTimeMillis(expectedChallengeExpireMs);
        assertThat(challengeData.expires()).isBetween(startExpireTimeMillis, endExpireTimeMillis);
    }

    long getExpireTimeMillis(long expireMs) {
        return expireMs + System.currentTimeMillis();
    }
}

package github.luckygc.cap;

import static org.assertj.core.api.Assertions.assertThat;

import github.luckygc.cap.config.ChallengeConfig;
import github.luckygc.cap.impl.CapManagerBuilder;
import github.luckygc.cap.impl.MemoryCapStore;
import github.luckygc.cap.model.ChallengeData;
import org.junit.jupiter.api.Test;

public class CreateChallengeTest {

    @Test
    void testBuildCapManager() {
        CapManager capManager = CapManagerBuilder
                .store(new MemoryCapStore())
                .build();

        assertThat(capManager).isNotNull();
    }

    @Test
    void testDefaultChallengeConfig() {
        ChallengeConfig challengeConfig = new ChallengeConfig();
        CapManager capManager = CapManagerBuilder
                .store(new MemoryCapStore())
                .build();

        long startExpireTimeMillis = getExpireTimeMillis(challengeConfig.getExpireMs());
        ChallengeData challengeData = capManager.createChallenge();
        long endExpireTimeMillis = getExpireTimeMillis(challengeConfig.getExpireMs());

        assertThat(challengeData.expires()).isBetween(startExpireTimeMillis, endExpireTimeMillis);

        assertThat(challengeData.challenge()).isNotNull()
                .satisfies(challenge -> {
                    assertThat(challenge.c()).isEqualTo(challengeConfig.getCount());
                    assertThat(challenge.s()).isEqualTo(challengeConfig.getSize());
                    assertThat(challenge.d()).isEqualTo(challengeConfig.getDifficulty());
                });
    }

    @Test
    void testChallengeCountConfig() {
        int expectedChallengeCount = 5;

        CapManager capManager = CapManagerBuilder
                .store(new MemoryCapStore())
                .challengeConfig(config -> config.count(expectedChallengeCount))
                .build();

        ChallengeData challengeData = capManager.createChallenge();
        assertThat(challengeData.challenge()).isNotNull()
                .satisfies(challenge -> assertThat(challenge.c()).isEqualTo(expectedChallengeCount));
    }

    @Test
    void testChallengeSizeConfig() {
        int expectedChallengeSize = 30;

        CapManager capManager = CapManagerBuilder
                .store(new MemoryCapStore())
                .challengeConfig(config -> config.size(expectedChallengeSize))
                .build();

        ChallengeData challengeData = capManager.createChallenge();
        assertThat(challengeData.challenge()).isNotNull()
                .satisfies(challenge -> assertThat(challenge.s()).isEqualTo(expectedChallengeSize));
    }

    @Test
    void testChallengeDifficultyConfig() {
        int expectedChallengeDifficulty = 6;

        CapManager capManager = CapManagerBuilder
                .store(new MemoryCapStore())
                .challengeConfig(config -> config.difficulty(expectedChallengeDifficulty))
                .build();

        ChallengeData challengeData = capManager.createChallenge();
        assertThat(challengeData.challenge()).isNotNull()
                .satisfies(challenge -> assertThat(challenge.d()).isEqualTo(expectedChallengeDifficulty));
    }

    @Test
    void testChallengeExpireConfig() {
        long expectedChallengeExpireMs = 3 * 1000;

        CapManager capManager = CapManagerBuilder
                .store(new MemoryCapStore())
                .challengeConfig(config -> config.expireMs(expectedChallengeExpireMs))
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

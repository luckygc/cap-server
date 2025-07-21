package github.luckygc.cap;

import static org.assertj.core.api.Assertions.assertThat;

import github.luckygc.cap.config.ChallengeConfig;
import github.luckygc.cap.impl.CapManagerBuilder;
import github.luckygc.cap.impl.MemoryCapStore;
import github.luckygc.cap.model.ChallengeData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("创建挑战测试")
public class CreateChallengeTest {

    @Test
    @DisplayName("测试默认挑战配置")
    void testDefaultChallengeConfig() {
        MemoryCapStore memoryCapStore = new MemoryCapStore();
        CapManager capManager = CapManagerBuilder
                .store(memoryCapStore)
                .build();

        ChallengeConfig challengeConfig = capManager.getChallengeConfig();
        long startExpireTimeMillis = getExpireTimeMillis(challengeConfig.getExpireMs());
        ChallengeData challengeData = capManager.createChallenge();
        long endExpireTimeMillis = getExpireTimeMillis(challengeConfig.getExpireMs());

        assertThat(challengeData.token()).isNotBlank();
        assertThat(memoryCapStore.findChallengeData(challengeData.token())).isNotNull();

        assertThat(challengeData.expires()).isBetween(startExpireTimeMillis, endExpireTimeMillis);

        assertThat(challengeData.challenge()).isNotNull()
                .satisfies(challenge -> {
                    assertThat(challenge.c()).isEqualTo(challengeConfig.getCount());
                    assertThat(challenge.s()).isEqualTo(challengeConfig.getSize());
                    assertThat(challenge.d()).isEqualTo(challengeConfig.getDifficulty());
                });
    }

    @Test
    @DisplayName("测试挑战数量配置")
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
    @DisplayName("测试挑战长度配置")
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
    @DisplayName("测试挑战难度配置")
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
    @DisplayName("测试挑战过期时间配置")
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

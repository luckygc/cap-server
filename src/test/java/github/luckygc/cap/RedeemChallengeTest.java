package github.luckygc.cap;

import static org.assertj.core.api.Assertions.assertThat;

import github.luckygc.cap.impl.CapManagerBuilder;
import github.luckygc.cap.model.Challenge;
import github.luckygc.cap.model.ChallengeData;
import github.luckygc.cap.model.RedeemChallengeRequest;
import github.luckygc.cap.model.RedeemChallengeResponse;
import github.luckygc.cap.utils.RandomUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 兑换挑战测试类 演示如何测试兑换挑战功能
 */
public class RedeemChallengeTest {

    private CapManager capManager;

    @BeforeEach
    void setUp() {
        capManager = new CapManagerBuilder().build();
    }

    /**
     * 测试成功兑换挑战
     */
    @Test
    void testSuccessfulRedeemChallenge() {
        // 1. 创建挑战
        ChallengeData challengeData = capManager.createChallenge();
        String challengeToken = challengeData.token();
        var challenge = challengeData.challenge();

        // 2. 生成正确的解决方案
        List<Integer> solutions = generateCorrectSolutions(challengeToken, challenge);

        // 3. 兑换挑战
        RedeemChallengeRequest request = new RedeemChallengeRequest(challengeToken, solutions);
        RedeemChallengeResponse response = capManager.redeemChallenge(request);

        // 4. 验证结果
        assertThat(response.success()).isTrue();
        assertThat(response.message()).isNull();
        assertThat(response.token()).isNotNull();
        assertThat(response.expires()).isNotNull().isGreaterThan(System.currentTimeMillis());

        // 5. 验证生成的token是否有效
        boolean isValid = capManager.validateCapToken(response.token());
        assertThat(isValid).isTrue();
    }

    /**
     * 测试无效的解决方案
     */
    @Test
    void testInvalidSolutions() {
        // 1. 创建挑战
        ChallengeData challengeData = capManager.createChallenge();
        String challengeToken = challengeData.token();

        // 2. 生成错误的解决方案（全部为0）
        List<Integer> wrongSolutions = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            wrongSolutions.add(0);
        }

        // 3. 兑换挑战
        RedeemChallengeRequest request = new RedeemChallengeRequest(challengeToken, wrongSolutions);
        RedeemChallengeResponse response = capManager.redeemChallenge(request);

        // 4. 验证结果
        assertThat(response.success()).isFalse();
        assertThat(response.message()).isNotNull().isEqualTo("无效解决方案");
        assertThat(response.token()).isNull();
        assertThat(response.expires()).isNull();
    }

    /**
     * 测试过期的挑战
     */
    @Test
    void testExpiredChallenge() throws InterruptedException {
        // 1. 创建短期挑战（1毫秒过期）
        CapManager customCapManager = new CapManagerBuilder()
                .challenge(config -> config.expireMs(1L))
                .build();
        ChallengeData challengeData = customCapManager.createChallenge();
        String challengeToken = challengeData.token();

        // 2. 等待挑战过期
        Thread.sleep(10);

        // 3. 尝试兑换过期的挑战
        List<Integer> solutions = List.of(1, 2, 3);
        RedeemChallengeRequest request = new RedeemChallengeRequest(challengeToken, solutions);
        RedeemChallengeResponse response = capManager.redeemChallenge(request);

        // 4. 验证结果
        assertThat(response.success()).isFalse();
        assertThat(response.message()).isNotNull().isEqualTo("挑战已过期");
        assertThat(response.token()).isNull();
        assertThat(response.expires()).isNull();
    }

    /**
     * 测试无效的挑战token
     */
    @Test
    void testInvalidChallengeToken() {
        // 1. 使用不存在的token
        String invalidToken = "invalid_token";
        List<Integer> solutions = List.of(1, 2, 3);

        // 2. 兑换挑战
        RedeemChallengeRequest request = new RedeemChallengeRequest(invalidToken, solutions);
        RedeemChallengeResponse response = capManager.redeemChallenge(request);

        // 3. 验证结果
        assertThat(response.success()).isFalse();
        assertThat(response.message()).isNotNull().isEqualTo("挑战已过期");
        assertThat(response.token()).isNull();
        assertThat(response.expires()).isNull();
    }

    /**
     * 测试空参数
     */
    @Test
    void testEmptyParameters() {
        // 1. 测试空token
        RedeemChallengeRequest request1 = new RedeemChallengeRequest("", List.of(1, 2, 3));
        RedeemChallengeResponse response1 = capManager.redeemChallenge(request1);
        assertThat(response1.success()).isFalse();
        assertThat(response1.message()).isEqualTo("参数[redeemChallengeRequest.token]不能为空");

        // 2. 测试空解决方案
        RedeemChallengeRequest request2 = new RedeemChallengeRequest("token", new ArrayList<>());
        RedeemChallengeResponse response2 = capManager.redeemChallenge(request2);
        assertThat(response2.success()).isFalse();
        assertThat(response2.message()).isEqualTo("参数[redeemChallengeRequest.solutions]不能为空");

        // 3. 测试null解决方案
        RedeemChallengeRequest request3 = new RedeemChallengeRequest("token", null);
        RedeemChallengeResponse response3 = capManager.redeemChallenge(request3);
        assertThat(response3.success()).isFalse();
        assertThat(response3.message()).isEqualTo("参数[redeemChallengeRequest.solutions]不能为空");
    }

    /**
     * 测试自定义挑战配置
     */
    @Test
    void testCustomChallengeConfig() {
        // 1. 创建自定义配置的挑战
        CapManager customCapManager = new CapManagerBuilder()
                .challenge(config -> config
                        .count(2)
                        .size(4)
                        .difficulty(2))
                .build();

        ChallengeData challengeData = customCapManager.createChallenge();
        String challengeToken = challengeData.token();
        var challenge = challengeData.challenge();

        // 3. 生成正确的解决方案
        List<Integer> solutions = generateCorrectSolutions(challengeToken, challenge);

        // 4. 兑换挑战
        RedeemChallengeRequest request = new RedeemChallengeRequest(challengeToken, solutions);
        RedeemChallengeResponse response = customCapManager.redeemChallenge(request);

        // 5. 验证结果
        assertThat(response.success()).isTrue();
        assertThat(response.token()).isNotNull();

        boolean isValid = customCapManager.validateCapToken(response.token());
        assertThat(isValid).isTrue();
    }

    /**
     * 生成正确的解决方案
     *
     * @param challengeToken 挑战token
     * @param challenge      挑战配置
     * @return 正确的解决方案列表
     */
    private List<Integer> generateCorrectSolutions(String challengeToken, Challenge challenge) {
        List<Integer> solutions = new ArrayList<>();

        for (int i = 0; i < challenge.c(); i++) {
            String salt = RandomUtil.prng("%s%d".formatted(challengeToken, i + 1), challenge.s());
            String target = RandomUtil.prng("%s%dd".formatted(challengeToken, i + 1), challenge.d());

            // 暴力破解找到正确的nonce
            int solution = findCorrectNonce(salt, target);
            solutions.add(solution);
        }

        return solutions;
    }

    /**
     * 暴力破解找到正确的nonce
     *
     * @param salt   盐值
     * @param target 目标前缀
     * @return 正确的nonce值
     */
    private int findCorrectNonce(String salt, String target) {
        return IntStream.range(0, 1000000) // 限制搜索范围，避免无限循环
                .filter(nonce -> {
                    String hash = DigestUtils.sha256Hex(salt + nonce);
                    return hash.startsWith(target);
                })
                .findFirst()
                .orElse(0); // 如果没找到，返回0（测试中会失败）
    }
} 

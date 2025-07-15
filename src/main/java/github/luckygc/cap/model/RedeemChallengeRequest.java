package github.luckygc.cap.model;

import java.util.List;

/**
 * 兑换挑战请求
 *
 * @param token     挑战数据唯一标识
 * @param solutions 解决方案 nonce集合
 */
public record RedeemChallengeRequest(String token, List<Integer> solutions) {

}

package github.luckygc.cap.model;

/**
 * 兑换挑战结果
 *
 * @param success 是否成功
 * @param message 错误消息
 * @param token   挑战成功后生成的唯一标识
 * @param expires 过期时间
 */
public record RedeemChallengeResponse(boolean success, String message, String token, Long expires) {

    public static RedeemChallengeResponse error(String message) {
        return new RedeemChallengeResponse(false, message, null, null);
    }

    public static RedeemChallengeResponse ok(CapToken capToken) {
        return new RedeemChallengeResponse(true, null, capToken.token(), capToken.expires());
    }
}

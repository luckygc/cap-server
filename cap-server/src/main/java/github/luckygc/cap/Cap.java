package github.luckygc.cap;

public interface Cap {

    static CapBuilder builder(String secret) {
        return new CapBuilder(secret);
    }

    ChallengeResponse createChallenge();

    ChallengeResponse createChallenge(ChallengeOptions options);

    RedeemResult redeem(RedeemRequest request);

    RedeemResult redeem(RedeemRequest request, RedeemOptions options);
}

package github.luckygc.cap;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

public final class CapBuilder {

    private final byte[] secret;
    private CapProfile profile = CapProfile.DEFAULT;
    private ChallengeOptions challengeDefaults = ChallengeOptions.defaults();
    private RedeemOptions redeemDefaults = RedeemOptions.defaults();
    private int challengeCount = 1;
    private int challengeSize = 1;
    private int difficulty = 1;
    private CapProtocol[] protocols = {CapProtocol.SHA256_POW};
    private @Nullable RswKeyPair rswKeyPair;
    private int rswIterations = 1;
    private InstrumentationOptions instrumentation = InstrumentationOptions.defaults();
    private long nonceCacheMaximumSize = 1;
    private @Nullable NonceConsumer nonceConsumer;
    private boolean replayProtectionDisabled;
    private @Nullable TokenSigner tokenSigner;
    private CapEventListener eventListener = new CapEventListener() {};

    CapBuilder(String secret) {
        this.secret = Objects.requireNonNull(secret, "secret").getBytes(StandardCharsets.UTF_8);
    }

    public CapBuilder profile(CapProfile profile) {
        this.profile = Objects.requireNonNull(profile, "profile");
        return this;
    }

    public CapBuilder challengeDefaults(ChallengeOptions options) {
        this.challengeDefaults = Objects.requireNonNull(options, "options");
        return this;
    }

    public CapBuilder redeemDefaults(RedeemOptions options) {
        this.redeemDefaults = Objects.requireNonNull(options, "options");
        return this;
    }

    public CapBuilder format1(int challengeCount, int challengeSize, int difficulty) {
        this.challengeCount = challengeCount;
        this.challengeSize = challengeSize;
        this.difficulty = difficulty;
        return this;
    }

    public CapBuilder protocols(CapProtocol... protocols) {
        if (protocols == null) {
            throw new IllegalArgumentException("protocols must not be null");
        }
        CapProtocol[] copy = protocols.clone();
        for (CapProtocol protocol : copy) {
            if (protocol == null) {
                throw new IllegalArgumentException("protocols must not contain null");
            }
        }
        this.protocols = copy;
        return this;
    }

    public CapBuilder rswKeyPair(RswKeyPair keyPair) {
        this.rswKeyPair = Objects.requireNonNull(keyPair, "keyPair");
        return this;
    }

    public CapBuilder rswIterations(int iterations) {
        this.rswIterations = iterations;
        return this;
    }

    /**
     * 配置 instrumentation；其中的自定义 transformer 必须是可信同步代码。
     *
     * <p>自定义 transformer 可见完整脚本及 nonce 相关内容，并在 challenge 调用线程执行；本库不提供超时、 内存隔离或 JVM
     * sandbox，仅校验异常、返回值和输出大小。
     */
    public CapBuilder instrumentation(InstrumentationOptions options) {
        this.instrumentation = Objects.requireNonNull(options, "options");
        return this;
    }

    public CapBuilder nonceCacheMaximumSize(long maximumSize) {
        this.nonceCacheMaximumSize = maximumSize;
        return this;
    }

    public CapBuilder nonceConsumer(NonceConsumer consumer) {
        Objects.requireNonNull(consumer, "consumer");
        if (replayProtectionDisabled) {
            throw new IllegalArgumentException(
                    "nonceConsumer and disableReplayProtection are mutually exclusive");
        }
        this.nonceConsumer = consumer;
        return this;
    }

    public CapBuilder disableReplayProtection() {
        if (nonceConsumer != null) {
            throw new IllegalArgumentException(
                    "nonceConsumer and disableReplayProtection are mutually exclusive");
        }
        replayProtectionDisabled = true;
        return this;
    }

    public CapBuilder tokenSigner(TokenSigner signer) {
        this.tokenSigner = Objects.requireNonNull(signer, "signer");
        return this;
    }

    public CapBuilder eventListener(CapEventListener listener) {
        this.eventListener = Objects.requireNonNull(listener, "listener");
        return this;
    }

    public Cap build() {
        if (secret.length < 16) {
            throw new IllegalArgumentException("secret must be at least 16 UTF-8 bytes");
        }
        Configuration configuration =
                new Configuration(
                        secret,
                        profile,
                        challengeDefaults,
                        redeemDefaults,
                        challengeCount,
                        challengeSize,
                        difficulty,
                        List.of(protocols.clone()),
                        rswKeyPair,
                        rswIterations,
                        instrumentation,
                        nonceCacheMaximumSize,
                        nonceConsumer,
                        replayProtectionDisabled,
                        tokenSigner,
                        eventListener);
        return new UnimplementedCap(configuration);
    }

    private record Configuration(
            byte[] secret,
            CapProfile profile,
            ChallengeOptions challengeDefaults,
            RedeemOptions redeemDefaults,
            int challengeCount,
            int challengeSize,
            int difficulty,
            List<CapProtocol> protocols,
            @Nullable RswKeyPair rswKeyPair,
            int rswIterations,
            InstrumentationOptions instrumentation,
            long nonceCacheMaximumSize,
            @Nullable NonceConsumer nonceConsumer,
            boolean replayProtectionDisabled,
            @Nullable TokenSigner tokenSigner,
            CapEventListener eventListener) {

        private Configuration {
            secret = secret.clone();
            protocols = List.copyOf(protocols);
        }

        @Override
        public byte[] secret() {
            return secret.clone();
        }
    }

    private record UnimplementedCap(Configuration configuration) implements Cap {

        @Override
        public ChallengeResponse createChallenge() {
            throw protocolNotImplemented();
        }

        @Override
        public ChallengeResponse createChallenge(ChallengeOptions options) {
            Objects.requireNonNull(options, "options");
            throw protocolNotImplemented();
        }

        @Override
        public RedeemResult redeem(RedeemRequest request) {
            Objects.requireNonNull(request, "request");
            throw protocolNotImplemented();
        }

        @Override
        public RedeemResult redeem(RedeemRequest request, RedeemOptions options) {
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(options, "options");
            throw protocolNotImplemented();
        }

        private static UnsupportedOperationException protocolNotImplemented() {
            return new UnsupportedOperationException("protocol not implemented");
        }
    }
}

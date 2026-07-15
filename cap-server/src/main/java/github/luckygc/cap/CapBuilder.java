package github.luckygc.cap;

import github.luckygc.cap.internal.DefaultCap;
import github.luckygc.cap.internal.rsw.RswSupport;
import github.luckygc.cap.replay.CaffeineNonceConsumer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

public final class CapBuilder {

    private final byte[] secret;
    private CapProfile profile = CapProfile.DEFAULT;
    private ChallengeOptions challengeDefaults = ChallengeOptions.defaults();
    private RedeemOptions redeemDefaults = RedeemOptions.defaults();
    private int challengeCount = 50;
    private int challengeSize = 32;
    private int difficulty = 4;
    private CapProtocol[] protocols = {CapProtocol.RSW, CapProtocol.INSTRUMENTATION};
    private @Nullable RswKeyPair rswKeyPair;
    private int rswIterations = RswSupport.DEFAULT_T;
    private InstrumentationOptions instrumentation = InstrumentationOptions.defaults();
    private boolean instrumentationConfigured;
    private long nonceCacheMaximumSize = CaffeineNonceConsumer.DEFAULT_MAXIMUM_SIZE;
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
     * <p>自定义 transformer 可见完整脚本及 nonce 相关内容，并在 challenge 调用线程执行；同一 {@link Cap}
     * 可被并发调用，实现必须线程安全。其阻塞和外部副作用由调用方负责；本库不提供超时、内存隔离或 JVM sandbox，仅校验异常、返回值和输出大小。
     */
    public CapBuilder instrumentation(InstrumentationOptions options) {
        this.instrumentation = Objects.requireNonNull(options, "options");
        instrumentationConfigured = true;
        return this;
    }

    /** 设置本机 nonce cache 的硬容量；TTL 内签名不会被淘汰，容量满时兑换返回 {@code nonce_store_error}。 */
    public CapBuilder nonceCacheMaximumSize(long maximumSize) {
        this.nonceCacheMaximumSize = maximumSize;
        return this;
    }

    /**
     * 使用外部原子 nonce consumer 完全替代本机缓存。
     *
     * <p>consumer 在 redeem 调用线程同步执行，必须是可信且线程安全的；其阻塞和外部副作用由调用方负责。
     */
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

    /**
     * 使用自定义业务 token signer。
     *
     * <p>signer 在 redeem 调用线程同步执行，必须是可信且线程安全的；其阻塞和外部副作用由调用方负责。
     */
    public CapBuilder tokenSigner(TokenSigner signer) {
        this.tokenSigner = Objects.requireNonNull(signer, "signer");
        return this;
    }

    /**
     * 设置同步事件监听器。
     *
     * <p>listener 可被并发调用，必须是可信且线程安全的；其阻塞和外部副作用由调用方负责。
     */
    public CapBuilder eventListener(CapEventListener listener) {
        this.eventListener = Objects.requireNonNull(listener, "listener");
        return this;
    }

    public Cap build() {
        if (secret.length < 16) {
            throw new IllegalArgumentException("secret must be at least 16 UTF-8 bytes");
        }
        List<CapProtocol> selectedProtocols = List.of(protocols.clone());
        if (profile == CapProfile.STRICT && selectedProtocols.isEmpty()) {
            selectedProtocols = List.of(CapProtocol.RSW);
        }
        @Nullable RswKeyPair selectedRswKeyPair = null;
        if (profile == CapProfile.STRICT && selectedProtocols.contains(CapProtocol.RSW)) {
            selectedRswKeyPair = rswKeyPair == null ? RswKeyPair.generate(2048) : rswKeyPair;
        }
        InstrumentationOptions strictInstrumentation =
                instrumentationConfigured
                        ? instrumentation
                        : InstrumentationOptions.builder()
                                .level(3)
                                .blockAutomatedBrowsers(true)
                                .build();
        return new DefaultCap(
                new String(secret, StandardCharsets.UTF_8),
                profile == CapProfile.STRICT ? 2 : 1,
                selectedProtocols,
                challengeDefaults,
                redeemDefaults,
                challengeCount,
                challengeSize,
                difficulty,
                selectedRswKeyPair,
                rswIterations,
                profile == CapProfile.DEFAULT && instrumentationConfigured ? instrumentation : null,
                strictInstrumentation,
                nonceCacheMaximumSize,
                nonceConsumer,
                replayProtectionDisabled,
                tokenSigner,
                eventListener);
    }
}

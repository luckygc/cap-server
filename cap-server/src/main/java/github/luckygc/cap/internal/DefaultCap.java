package github.luckygc.cap.internal;

import github.luckygc.cap.Cap;
import github.luckygc.cap.CapEventListener;
import github.luckygc.cap.CapProtocol;
import github.luckygc.cap.ChallengeOptions;
import github.luckygc.cap.ChallengeResponse;
import github.luckygc.cap.InstrumentationOptions;
import github.luckygc.cap.NonceConsumer;
import github.luckygc.cap.RedeemOptions;
import github.luckygc.cap.RedeemRequest;
import github.luckygc.cap.RedeemResult;
import github.luckygc.cap.RswKeyPair;
import github.luckygc.cap.TokenSigner;
import github.luckygc.cap.internal.CapEvents.Warning;
import github.luckygc.cap.internal.crypto.JwtCodec;
import github.luckygc.cap.internal.protocol.Format1Protocol;
import github.luckygc.cap.internal.protocol.Format2Protocol;
import github.luckygc.cap.internal.protocol.ProtocolFailure;
import github.luckygc.cap.internal.replay.CaffeineNonceConsumer;
import github.luckygc.cap.internal.rsw.RswSupport;
import github.luckygc.cap.internal.token.DefaultTokenSigner;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** 默认线程安全 Cap 门面；该 internal 类型不属于受支持的公开 API。 */
public final class DefaultCap implements Cap {

    private final int format;
    private final List<CapProtocol> protocols;
    private final List<CapProtocol> format1Protocols;
    private final ChallengeOptions challengeDefaults;
    private final RedeemOptions redeemDefaults;
    private final @Nullable Format1Protocol format1;
    private final @Nullable Format2Protocol format2;
    private final JwtCodec jwt;
    private final @Nullable NonceConsumer nonceConsumer;
    private final @Nullable TokenSigner tokenSigner;
    private final DefaultTokenSigner defaultTokenSigner;
    private final CapEvents events;

    public DefaultCap(
            String secret,
            int format,
            List<CapProtocol> protocols,
            ChallengeOptions challengeDefaults,
            RedeemOptions redeemDefaults,
            int challengeCount,
            int challengeSize,
            int difficulty,
            @Nullable RswKeyPair rswKeyPair,
            int rswIterations,
            @Nullable InstrumentationOptions format1Instrumentation,
            InstrumentationOptions format2Instrumentation,
            long nonceCacheMaximumSize,
            @Nullable NonceConsumer configuredNonceConsumer,
            boolean replayProtectionDisabled,
            @Nullable TokenSigner tokenSigner,
            CapEventListener eventListener) {
        this.format = format;
        this.protocols = List.copyOf(protocols);
        format1Protocols =
                format1Instrumentation == null
                        ? List.of(CapProtocol.SHA256_POW)
                        : List.of(CapProtocol.SHA256_POW, CapProtocol.INSTRUMENTATION);
        this.challengeDefaults = Objects.requireNonNull(challengeDefaults, "challengeDefaults");
        this.redeemDefaults = Objects.requireNonNull(redeemDefaults, "redeemDefaults");
        format1 =
                format == 1
                        ? new Format1Protocol(
                                secret,
                                challengeCount,
                                challengeSize,
                                difficulty,
                                format1Instrumentation)
                        : null;
        format2 =
                format == 2
                        ? new Format2Protocol(
                                secret,
                                protocols,
                                challengeCount,
                                challengeSize,
                                difficulty,
                                protocols.contains(CapProtocol.RSW)
                                        ? RswSupport.createMinter(
                                                Objects.requireNonNull(rswKeyPair, "rswKeyPair"),
                                                rswIterations)
                                        : null,
                                format2Instrumentation)
                        : null;
        jwt = new JwtCodec(secret);
        nonceConsumer =
                replayProtectionDisabled
                        ? null
                        : configuredNonceConsumer != null
                                ? configuredNonceConsumer
                                : new CaffeineNonceConsumer(nonceCacheMaximumSize);
        this.tokenSigner = tokenSigner;
        defaultTokenSigner = new DefaultTokenSigner();
        events = new CapEvents(eventListener);
    }

    @Override
    public ChallengeResponse createChallenge() {
        return createChallenge(challengeDefaults);
    }

    @Override
    public ChallengeResponse createChallenge(ChallengeOptions options) {
        Objects.requireNonNull(options, "options");
        long started = System.nanoTime();
        try {
            ChallengeResponse response =
                    format == 2
                            ? Objects.requireNonNull(format2, "format2").generate(options)
                            : Objects.requireNonNull(format1, "format1").generate(options);
            events.challengeCreated(format, eventProtocols(), elapsed(started));
            return response;
        } catch (RuntimeException exception) {
            events.warn(Warning.CHALLENGE_GENERATION_FAILURE, exception);
            throw exception;
        }
    }

    @Override
    public RedeemResult redeem(@Nullable RedeemRequest request) {
        return redeem(request, redeemDefaults);
    }

    @Override
    public RedeemResult redeem(@Nullable RedeemRequest request, RedeemOptions options) {
        Objects.requireNonNull(options, "options");
        long started = System.nanoTime();
        int requestFormat = requestFormat(request);
        List<CapProtocol> requestProtocols = requestFormat == 2 ? protocols : format1Protocols;
        Object validation;
        if (requestFormat == 2 && format2 != null) {
            validation = format2.validate(request, options.expectedScope());
        } else if (requestFormat == 1 && format1 != null) {
            validation = format1.validate(request, options.expectedScope());
        } else {
            validation =
                    new ProtocolFailure(
                            request == null ? "invalid_body" : "invalid_token", false, null);
        }
        if (validation instanceof ProtocolFailure failure) {
            return failure(requestFormat, requestProtocols, failure, started);
        }

        Validated validated = validated(validation);
        if (nonceConsumer != null) {
            Duration remaining =
                    Duration.ofMillis(
                            Math.max(1L, validated.expires() - System.currentTimeMillis()));
            final boolean claimed;
            try {
                claimed = nonceConsumer.consume(validated.signatureHex(), remaining);
            } catch (Exception exception) {
                events.warn(Warning.NONCE_CONSUMER_FAILURE, exception);
                return failure(
                        requestFormat,
                        requestProtocols,
                        new ProtocolFailure("nonce_store_error", false, null),
                        started);
            }
            if (!claimed) {
                return failure(
                        requestFormat,
                        requestProtocols,
                        new ProtocolFailure("already_redeemed", false, null),
                        started);
            }
        }

        long tokenExpires;
        try {
            tokenExpires = Math.addExact(System.currentTimeMillis(), options.tokenTtl().toMillis());
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("tokenTtl overflows epoch milliseconds", exception);
        }
        String token;
        @Nullable String tokenKey;
        if (tokenSigner != null) {
            try {
                token =
                        Objects.requireNonNull(
                                tokenSigner.sign(
                                        validated.scope(),
                                        Instant.ofEpochMilli(tokenExpires),
                                        Instant.ofEpochMilli(validated.issuedAt())),
                                "token signer result");
                tokenKey = null;
            } catch (Exception exception) {
                events.warn(Warning.TOKEN_SIGNER_FAILURE, exception);
                return failure(
                        requestFormat,
                        requestProtocols,
                        new ProtocolFailure("token_signer_error", false, null),
                        started);
            }
        } else {
            DefaultTokenSigner.SignedToken signed = defaultTokenSigner.sign();
            token = signed.token();
            tokenKey = signed.tokenKey();
        }
        RedeemResult.Success result =
                new RedeemResult.Success(
                        true,
                        token,
                        tokenKey,
                        tokenExpires,
                        validated.scope(),
                        validated.issuedAt());
        events.redeemSucceeded(requestFormat, requestProtocols, elapsed(started));
        return result;
    }

    private int requestFormat(@Nullable RedeemRequest request) {
        if (request == null) {
            return format;
        }
        @Nullable Map<String, @Nullable Object> payload = jwt.verify(request.token()).orElse(null);
        if (payload == null) {
            return format;
        }
        return Long.valueOf(2L).equals(payload.get("f")) ? 2 : 1;
    }

    private List<CapProtocol> eventProtocols() {
        return format == 2 ? protocols : format1Protocols;
    }

    private RedeemResult.Failure failure(
            int eventFormat,
            List<CapProtocol> eventProtocols,
            ProtocolFailure failure,
            long started) {
        RedeemResult.Failure result =
                new RedeemResult.Failure(
                        false, failure.reason(), failure.instrError(), failure.error());
        events.redeemFailed(eventFormat, eventProtocols, failure.reason(), elapsed(started));
        return result;
    }

    private static Validated validated(Object validation) {
        if (validation instanceof Format1Protocol.Validated value) {
            return new Validated(
                    value.scope(), value.issuedAt(), value.expires(), value.signatureHex());
        }
        Format2Protocol.Validated value = (Format2Protocol.Validated) validation;
        return new Validated(
                value.scope(), value.issuedAt(), value.expires(), value.signatureHex());
    }

    private static Duration elapsed(long started) {
        return Duration.ofNanos(Math.max(0L, System.nanoTime() - started));
    }

    private record Validated(
            @Nullable String scope, long issuedAt, long expires, String signatureHex) {}
}

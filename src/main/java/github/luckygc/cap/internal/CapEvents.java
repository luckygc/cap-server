package github.luckygc.cap.internal;

import github.luckygc.cap.Cap;
import github.luckygc.cap.CapEventListener;
import github.luckygc.cap.CapProtocol;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 分发不含敏感数据的 CAP 事件，并隔离监听器异常。 */
public final class CapEvents {

    private static final Logger LOGGER = LoggerFactory.getLogger(Cap.class);

    private final CapEventListener listener;

    public CapEvents(CapEventListener listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    public void challengeCreated(int format, List<CapProtocol> protocols, Duration duration) {
        CapEventListener.ChallengeEvent event =
                new CapEventListener.ChallengeEvent(format, protocols, duration);
        LOGGER.debug(
                "CAP challenge created: format={}, protocols={}, duration={}",
                event.format(),
                event.protocols(),
                event.duration());
        notifyListener(() -> listener.challengeCreated(event));
    }

    public void redeemSucceeded(int format, List<CapProtocol> protocols, Duration duration) {
        CapEventListener.RedeemEvent event =
                new CapEventListener.RedeemEvent(format, protocols, duration);
        LOGGER.debug(
                "CAP redeem succeeded: format={}, protocols={}, duration={}",
                event.format(),
                event.protocols(),
                event.duration());
        notifyListener(() -> listener.redeemSucceeded(event));
    }

    public void redeemFailed(
            int format, List<CapProtocol> protocols, String reason, Duration duration) {
        CapEventListener.FailureEvent event =
                new CapEventListener.FailureEvent(format, protocols, reason, duration);
        LOGGER.debug(
                "CAP redeem failed: format={}, protocols={}, duration={}, reason={}",
                event.format(),
                event.protocols(),
                event.duration(),
                event.reason());
        notifyListener(() -> listener.redeemFailed(event));
    }

    public void warn(Warning warning, Throwable exception) {
        Objects.requireNonNull(warning, "warning");
        Objects.requireNonNull(exception, "exception");
        LOGGER.warn(
                "CAP warning: message={}, type={}",
                warning.message,
                exception.getClass().getName());
    }

    private static void notifyListener(ListenerCall call) {
        try {
            call.invoke();
        } catch (RuntimeException exception) {
            LOGGER.warn("CAP event listener failed: type={}", exception.getClass().getName());
        }
    }

    @FunctionalInterface
    private interface ListenerCall {

        void invoke();
    }

    /** 可记录的固定警告类型，避免自由文本意外携带敏感数据。 */
    public enum Warning {
        NONCE_CONSUMER_FAILURE("nonce consumer failed"),
        RSW_FAILURE("RSW operation failed"),
        INSTRUMENTATION_FAILURE("instrumentation generation failed"),
        TOKEN_SIGNER_FAILURE("token signer failed");

        private final String message;

        Warning(String message) {
            this.message = message;
        }
    }
}

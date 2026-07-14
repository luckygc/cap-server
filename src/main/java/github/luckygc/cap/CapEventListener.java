package github.luckygc.cap;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * 接收不含敏感协议数据的同步事件。
 *
 * <p>事件在 create/redeem 调用线程分发；同一 {@link Cap} 可被并发调用，因此自定义实现必须是可信且线程安全的。监听器异常会被隔离，但其阻塞和外部副作用由调用方负责。
 */
public interface CapEventListener {

    default void challengeCreated(ChallengeEvent event) {}

    default void redeemSucceeded(RedeemEvent event) {}

    default void redeemFailed(FailureEvent event) {}

    record ChallengeEvent(int format, List<CapProtocol> protocols, Duration duration) {

        public ChallengeEvent {
            protocols = List.copyOf(protocols);
            Objects.requireNonNull(duration, "duration");
        }
    }

    record RedeemEvent(int format, List<CapProtocol> protocols, Duration duration) {

        public RedeemEvent {
            protocols = List.copyOf(protocols);
            Objects.requireNonNull(duration, "duration");
        }
    }

    record FailureEvent(int format, List<CapProtocol> protocols, String reason, Duration duration) {

        public FailureEvent {
            protocols = List.copyOf(protocols);
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(duration, "duration");
        }
    }
}

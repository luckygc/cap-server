package github.luckygc.cap.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import github.luckygc.cap.CapEventListener;
import github.luckygc.cap.CapProtocol;
import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("CAP 安全事件测试")
class CapEventsTest {

    @Test
    @DisplayName("监听器异常被隔离且不阻止后续流程")
    void isolatesListenerFailures() {
        AtomicInteger invocations = new AtomicInteger();
        CapEventListener listener =
                new CapEventListener() {
                    @Override
                    public void challengeCreated(ChallengeEvent event) {
                        invocations.incrementAndGet();
                        throw new IllegalStateException("sensitive-token");
                    }

                    @Override
                    public void redeemSucceeded(RedeemEvent event) {
                        invocations.incrementAndGet();
                    }
                };
        CapEvents events = new CapEvents(listener);

        assertThatCode(
                        () ->
                                events.challengeCreated(
                                        1, List.of(CapProtocol.SHA256_POW), Duration.ofMillis(4)))
                .doesNotThrowAnyException();
        events.redeemSucceeded(1, List.of(CapProtocol.SHA256_POW), Duration.ofMillis(5));

        assertThat(invocations).hasValue(2);
    }

    @Test
    @DisplayName("事件模型只包含格式、协议、耗时与失败原因")
    void exposesOnlySafeEventFields() {
        assertThat(componentNames(CapEventListener.ChallengeEvent.class))
                .containsExactly("format", "protocols", "duration");
        assertThat(componentNames(CapEventListener.RedeemEvent.class))
                .containsExactly("format", "protocols", "duration");
        assertThat(componentNames(CapEventListener.FailureEvent.class))
                .containsExactly("format", "protocols", "reason", "duration");
    }

    @Test
    @DisplayName("创建、成功与失败事件携带受限字段")
    void dispatchesSafeEvents() {
        CapturingListener listener = new CapturingListener();
        CapEvents events = new CapEvents(listener);
        List<CapProtocol> protocols = List.of(CapProtocol.RSW, CapProtocol.INSTRUMENTATION);

        events.challengeCreated(2, protocols, Duration.ofMillis(3));
        events.redeemSucceeded(2, protocols, Duration.ofMillis(4));
        events.redeemFailed(2, protocols, "invalid_solution", Duration.ofMillis(5));

        assertThat(listener.challenge)
                .isEqualTo(new CapEventListener.ChallengeEvent(2, protocols, Duration.ofMillis(3)));
        assertThat(listener.success)
                .isEqualTo(new CapEventListener.RedeemEvent(2, protocols, Duration.ofMillis(4)));
        assertThat(listener.failure)
                .isEqualTo(
                        new CapEventListener.FailureEvent(
                                2, protocols, "invalid_solution", Duration.ofMillis(5)));
    }

    @Test
    @DisplayName("警告只接受固定消息类型并隔离异常消息")
    void restrictsWarningTexts() {
        CapEvents events = new CapEvents(new CapEventListener() {});

        assertThatCode(
                        () ->
                                events.warn(
                                        CapEvents.Warning.NONCE_CONSUMER_FAILURE,
                                        new IllegalStateException("sensitive-token")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("运行时日志包含诊断字段但不泄露协议秘密")
    void runtimeLogsExcludeSensitiveProtocolValues() {
        String token = "sensitive-token-value";
        String solution = "sensitive-solution-value";
        String digest = "sensitive-internal-digest";
        CapturingLoggerProvider.reset();
        CapEvents events = new CapEvents(new CapEventListener() {});

        events.challengeCreated(2, List.of(CapProtocol.RSW), Duration.ofMillis(3));
        events.redeemFailed(2, List.of(CapProtocol.RSW), "invalid_solution", Duration.ofMillis(4));
        events.warn(
                CapEvents.Warning.NONCE_CONSUMER_FAILURE,
                new IllegalStateException(token + solution + digest));

        assertThat(CapturingLoggerProvider.messages())
                .anyMatch(message -> message.contains("format=2"))
                .anyMatch(message -> message.contains("reason=invalid_solution"))
                .noneMatch(
                        message ->
                                message.contains(token)
                                        || message.contains(solution)
                                        || message.contains(digest));
    }

    private static List<String> componentNames(Class<?> recordType) {
        return Arrays.stream(recordType.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    private static final class CapturingListener implements CapEventListener {

        private ChallengeEvent challenge;
        private RedeemEvent success;
        private FailureEvent failure;

        @Override
        public void challengeCreated(ChallengeEvent event) {
            challenge = event;
        }

        @Override
        public void redeemSucceeded(RedeemEvent event) {
            success = event;
        }

        @Override
        public void redeemFailed(FailureEvent event) {
            failure = event;
        }
    }
}

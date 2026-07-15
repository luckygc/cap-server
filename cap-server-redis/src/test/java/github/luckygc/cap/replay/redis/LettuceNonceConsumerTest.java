package github.luckygc.cap.replay.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisStringCommands;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.protocol.CommandArgs;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LettuceNonceConsumerTest {

    @Test
    @DisplayName("README Redis 示例使用可编译的公开构造器")
    void readmeDocumentsCompilingPublicConstructor() throws Exception {
        RedisStringCommands<String, String> commands =
                redisCommandsReturning("OK", new AtomicReference<>());
        LettuceNonceConsumer consumer = new LettuceNonceConsumer(commands);
        String readme = Files.readString(repositoryRoot().resolve("README.md"));

        assertThat(consumer).isNotNull();
        assertThat(readme)
                .contains(
                        "import github.luckygc.cap.replay.redis.LettuceNonceConsumer;",
                        "import io.lettuce.core.api.StatefulRedisConnection;",
                        "new LettuceNonceConsumer(connection.sync())");
    }

    @Test
    @DisplayName("默认前缀通过 SET NX PX 原子写入固定值")
    void shouldAdaptLettuceSetNxPxWithDefaultPrefix() throws Exception {
        AtomicReference<Object[]> invocationArguments = new AtomicReference<>();
        RedisStringCommands<String, String> commands =
                redisCommandsReturning("OK", invocationArguments);
        LettuceNonceConsumer consumer = new LettuceNonceConsumer(commands);

        assertThat(consumer.consume("abc", Duration.ofMillis(1500))).isTrue();
        Object[] arguments = invocationArguments.get();
        assertThat(arguments[0]).isEqualTo("cap:nonce:abc");
        assertThat(arguments[1]).isEqualTo("1");
        CommandArgs<String, String> commandArgs = new CommandArgs<>(StringCodec.UTF8);
        ((SetArgs) arguments[2]).build(commandArgs);
        assertThat(commandArgs.toCommandString()).isEqualTo("PX 1500 NX");
    }

    @Test
    @DisplayName("亚毫秒 TTL 向上取整并拼接自定义前缀")
    void shouldCeilSubMillisecondTtlAndUseCustomPrefix() throws Exception {
        AtomicReference<String> key = new AtomicReference<>();
        AtomicLong ttlMillis = new AtomicLong();
        LettuceNonceConsumer consumer =
                new LettuceNonceConsumer(
                        (actualKey, actualTtl) -> {
                            key.set(actualKey);
                            ttlMillis.set(actualTtl);
                            return "OK";
                        },
                        "custom:");

        assertThat(consumer.consume("abc", Duration.ofNanos(1))).isTrue();
        assertThat(key).hasValue("custom:abc");
        assertThat(ttlMillis).hasValue(1L);
    }

    @Test
    @DisplayName("完整毫秒 TTL 保持不变")
    void shouldPreserveWholeMillisecondTtl() throws Exception {
        AtomicLong ttlMillis = new AtomicLong();
        LettuceNonceConsumer consumer =
                new LettuceNonceConsumer(
                        (key, actualTtl) -> {
                            ttlMillis.set(actualTtl);
                            return "OK";
                        },
                        "cap:nonce:");

        assertThat(consumer.consume("abc", Duration.ofMillis(1500))).isTrue();
        assertThat(ttlMillis).hasValue(1500L);
    }

    @Test
    @DisplayName("非整数毫秒 TTL 向上取整")
    void shouldCeilFractionalMillisecondTtl() throws Exception {
        AtomicLong ttlMillis = new AtomicLong();
        LettuceNonceConsumer consumer =
                new LettuceNonceConsumer(
                        (key, actualTtl) -> {
                            ttlMillis.set(actualTtl);
                            return "OK";
                        },
                        "cap:nonce:");

        assertThat(consumer.consume("abc", Duration.ofMillis(1).plusNanos(1))).isTrue();
        assertThat(ttlMillis).hasValue(2L);
    }

    @Test
    @DisplayName("TTL 上限为二十四小时")
    void shouldCapTtlAtTwentyFourHours() throws Exception {
        AtomicLong ttlMillis = new AtomicLong();
        LettuceNonceConsumer consumer =
                new LettuceNonceConsumer(
                        (key, actualTtl) -> {
                            ttlMillis.set(actualTtl);
                            return "OK";
                        },
                        "cap:nonce:");

        assertThat(consumer.consume("abc", Duration.ofDays(30))).isTrue();
        assertThat(ttlMillis).hasValue(Duration.ofHours(24).toMillis());
    }

    @Test
    @DisplayName("极大 TTL 安全限制为二十四小时")
    void shouldCapExtremeTtlWithoutOverflow() throws Exception {
        AtomicLong ttlMillis = new AtomicLong();
        LettuceNonceConsumer consumer =
                new LettuceNonceConsumer(
                        (key, actualTtl) -> {
                            ttlMillis.set(actualTtl);
                            return "OK";
                        },
                        "cap:nonce:");

        assertThat(consumer.consume("abc", Duration.ofSeconds(Long.MAX_VALUE))).isTrue();
        assertThat(ttlMillis).hasValue(Duration.ofHours(24).toMillis());
    }

    @Test
    @DisplayName("空回复表示签名已消费")
    void shouldReturnFalseForNullReply() throws Exception {
        LettuceNonceConsumer consumer =
                new LettuceNonceConsumer((key, ttlMillis) -> null, "cap:nonce:");

        assertThat(consumer.consume("abc", Duration.ofSeconds(1))).isFalse();
    }

    @Test
    @DisplayName("非预期回复抛出不泄露数据的固定异常")
    void shouldRejectUnexpectedReplyWithoutLeakingReplyOrKey() {
        LettuceNonceConsumer consumer =
                new LettuceNonceConsumer((key, ttlMillis) -> "SECRET_REPLY", "cap:nonce:");

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> consumer.consume("SECRET_KEY", Duration.ofSeconds(1)))
                .withMessage("unexpected Redis SET response")
                .withMessageNotContaining("SECRET_REPLY")
                .withMessageNotContaining("SECRET_KEY");
    }

    @Test
    @DisplayName("命令异常原样传播")
    void shouldPropagateCommandFailureUnchanged() {
        RuntimeException failure = new RuntimeException("redis unavailable");
        LettuceNonceConsumer consumer =
                new LettuceNonceConsumer(
                        (key, ttlMillis) -> {
                            throw failure;
                        },
                        "cap:nonce:");

        assertThatThrownBy(() -> consumer.consume("abc", Duration.ofSeconds(1))).isSameAs(failure);
    }

    @Test
    @DisplayName("空前缀非法")
    void shouldRejectEmptyPrefix() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new LettuceNonceConsumer((key, ttlMillis) -> "OK", ""))
                .withMessage("keyPrefix must not be empty");
    }

    @SuppressWarnings("unchecked")
    private static RedisStringCommands<String, String> redisCommandsReturning(
            String response, AtomicReference<Object[]> invocationArguments) {
        return (RedisStringCommands<String, String>)
                Proxy.newProxyInstance(
                        LettuceNonceConsumerTest.class.getClassLoader(),
                        new Class<?>[] {RedisStringCommands.class},
                        (proxy, method, arguments) -> {
                            if (method.getName().equals("set") && arguments.length == 3) {
                                invocationArguments.set(arguments);
                                return response;
                            }
                            throw new AssertionError("unexpected Redis command");
                        });
    }

    private static Path repositoryRoot() {
        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        if (Files.isRegularFile(workingDirectory.resolve("README.md"))) {
            return workingDirectory;
        }
        Path parent = workingDirectory.getParent();
        if (parent != null && Files.isRegularFile(parent.resolve("README.md"))) {
            return parent;
        }
        throw new IllegalStateException("repository root unavailable");
    }
}

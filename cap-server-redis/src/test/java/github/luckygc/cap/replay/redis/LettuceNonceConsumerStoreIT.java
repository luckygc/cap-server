package github.luckygc.cap.replay.redis;

import static org.assertj.core.api.Assertions.assertThat;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

@DisplayName("Redis 真实存储互操作")
class LettuceNonceConsumerStoreIT {
    private static final int CONCURRENCY = 32;
    private static final int REDIS_PORT = 6379;
    private static final Duration TTL = Duration.ofSeconds(2);

    @Test
    @DisplayName("Redis 8.2.7 原子消费并按毫秒 TTL 过期")
    void redisPreservesAtomicConsumptionAndExpiry() throws Exception {
        GenericContainer<?> container = startContainer();
        RedisClient client = null;
        StatefulRedisConnection<String, String> connection = null;
        try {
            client =
                    RedisClient.create(
                            "redis://"
                                    + container.getHost()
                                    + ":"
                                    + container.getMappedPort(REDIS_PORT));
            connection = client.connect();
            String prefix = "cap:store-it:" + syntheticSignature() + ":";
            String signature = syntheticSignature();
            String key = prefix + signature;
            LettuceNonceConsumer consumer = new LettuceNonceConsumer(connection.sync(), prefix);

            List<Boolean> results =
                    StoreIntegrationSupport.runConcurrently(
                            CONCURRENCY, () -> consumer.consume(signature, TTL));
            assertThat(results).containsExactlyInAnyOrder(successes());
            assertThat(consumer.consume(signature, TTL)).isFalse();

            long pttl = connection.sync().pttl(key);
            assertThat(pttl).isPositive().isLessThanOrEqualTo(TTL.toMillis());
            awaitExpiry(connection, key);
            assertThat(consumer.consume(signature, TTL)).isTrue();
        } finally {
            try {
                if (connection != null) {
                    connection.close();
                }
            } finally {
                try {
                    if (client != null) {
                        client.shutdown();
                    }
                } finally {
                    container.stop();
                }
            }
        }
    }

    private static GenericContainer<?> startContainer() {
        GenericContainer<?> container =
                new GenericContainer<>(DockerImageName.parse("redis:8.2.7-alpine"))
                        .withExposedPorts(REDIS_PORT);
        try {
            container.start();
            return container;
        } catch (RuntimeException failure) {
            throw StoreIntegrationSupport.dockerUnavailable();
        }
    }

    private static void awaitExpiry(StatefulRedisConnection<String, String> connection, String key)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (connection.sync().pttl(key) != -2 && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
        assertThat(connection.sync().pttl(key)).isEqualTo(-2);
    }

    private static Boolean[] successes() {
        Boolean[] expected = new Boolean[CONCURRENCY];
        expected[0] = true;
        for (int index = 1; index < CONCURRENCY; index++) {
            expected[index] = false;
        }
        return expected;
    }

    private static String syntheticSignature() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}

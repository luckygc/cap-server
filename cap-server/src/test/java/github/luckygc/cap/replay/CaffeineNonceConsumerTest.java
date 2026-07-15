package github.luckygc.cap.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Caffeine 防重放测试")
class CaffeineNonceConsumerTest {

    @Test
    @DisplayName("32 个并发消费同一签名时恰好一个成功")
    void claimsSameSignatureAtomically() throws Exception {
        CaffeineNonceConsumer consumer = new CaffeineNonceConsumer();
        CountDownLatch ready = new CountDownLatch(32);
        CountDownLatch start = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(32);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int index = 0; index < 32; index++) {
                results.add(
                        executor.submit(
                                () -> {
                                    ready.countDown();
                                    start.await();
                                    return consumer.consume(
                                            "same-signature", Duration.ofMinutes(1));
                                }));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            long accepted = 0;
            for (Future<Boolean> result : results) {
                if (result.get(10, TimeUnit.SECONDS)) {
                    accepted++;
                }
            }
            assertThat(accepted).isOne();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("每项 TTL 到期后允许再次消费")
    void allowsConsumptionAfterEntryExpires() throws Exception {
        FakeTicker ticker = new FakeTicker();
        CaffeineNonceConsumer consumer = new CaffeineNonceConsumer(100, ticker);

        assertThat(consumer.consume("signature", Duration.ofSeconds(2))).isTrue();
        assertThat(consumer.consume("signature", Duration.ofSeconds(2))).isFalse();

        ticker.advance(Duration.ofSeconds(2));

        assertThat(consumer.consume("signature", Duration.ofSeconds(2))).isTrue();
    }

    @Test
    @DisplayName("容量上限在清理后生效")
    void boundsCacheSize() throws Exception {
        CaffeineNonceConsumer consumer = new CaffeineNonceConsumer(2, new FakeTicker());

        assertThat(consumer.consume("one", Duration.ofHours(1))).isTrue();
        assertThat(consumer.consume("two", Duration.ofHours(1))).isTrue();
        assertThatThrownBy(() -> consumer.consume("three", Duration.ofHours(1)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(consumer.consume("one", Duration.ofHours(1))).isFalse();
        assertThat(consumer.estimatedSize()).isEqualTo(2);
    }

    @Test
    @DisplayName("容量压力不得淘汰 TTL 内已消费签名")
    void neverEvictsLiveConsumedSignatures() throws Exception {
        CaffeineNonceConsumer consumer = new CaffeineNonceConsumer(2, new FakeTicker());

        assertThat(consumer.consume("protected", Duration.ofHours(1))).isTrue();
        assertThat(consumer.consume("second", Duration.ofHours(1))).isTrue();
        int rejected = 0;
        for (int index = 0; index < 1_000; index++) {
            String signature = "overflow-" + index;
            try {
                consumer.consume(signature, Duration.ofHours(1));
            } catch (IllegalStateException exception) {
                rejected++;
            }
        }

        assertThat(consumer.consume("protected", Duration.ofHours(1))).isFalse();
        assertThat(rejected).isEqualTo(1_000);
    }

    @Test
    @DisplayName("TTL 到期清理后容量可再次准入")
    void admitsAfterExpiredCapacityIsCleaned() throws Exception {
        FakeTicker ticker = new FakeTicker();
        CaffeineNonceConsumer consumer = new CaffeineNonceConsumer(1, ticker);

        assertThat(consumer.consume("old", Duration.ofSeconds(1))).isTrue();
        ticker.advance(Duration.ofSeconds(1));

        assertThat(consumer.consume("new", Duration.ofHours(1))).isTrue();
        assertThat(consumer.consume("new", Duration.ofHours(1))).isFalse();
    }

    @Test
    @DisplayName("并发不同签名不得超过硬容量")
    void concurrentAdmissionsRespectHardCapacity() throws Exception {
        int capacity = 8;
        CaffeineNonceConsumer consumer = new CaffeineNonceConsumer(capacity, new FakeTicker());
        ExecutorService executor = Executors.newFixedThreadPool(32);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int index = 0; index < 64; index++) {
                String signature = "signature-" + index;
                results.add(
                        executor.submit(
                                () -> {
                                    start.await();
                                    try {
                                        return consumer.consume(signature, Duration.ofHours(1));
                                    } catch (IllegalStateException exception) {
                                        return false;
                                    }
                                }));
            }
            start.countDown();
            long admitted = 0;
            for (Future<Boolean> result : results) {
                if (result.get(10, TimeUnit.SECONDS)) {
                    admitted++;
                }
            }
            assertThat(admitted).isEqualTo(capacity);
            assertThat(consumer.estimatedSize()).isEqualTo(capacity);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("TTL 截断为 1 纳秒到 24 小时且超大 Duration 不溢出")
    void clampsTtlWithoutDurationOverflow() throws Exception {
        FakeTicker ticker = new FakeTicker();
        CaffeineNonceConsumer consumer = new CaffeineNonceConsumer(100, ticker);

        assertThat(consumer.consume("zero", Duration.ZERO)).isTrue();
        ticker.advance(Duration.ofNanos(1));
        assertThat(consumer.consume("zero", Duration.ZERO)).isTrue();

        assertThat(consumer.consume("huge", Duration.ofSeconds(Long.MAX_VALUE))).isTrue();
        ticker.advance(Duration.ofHours(24).minusNanos(1));
        assertThat(consumer.consume("huge", Duration.ofSeconds(Long.MAX_VALUE))).isFalse();
        ticker.advance(Duration.ofNanos(1));
        assertThat(consumer.consume("huge", Duration.ofSeconds(Long.MAX_VALUE))).isTrue();
    }

    @Test
    @DisplayName("容量只允许 1 到 10000000")
    void validatesMaximumSizeRange() {
        assertThatIllegalArgumentException().isThrownBy(() -> new CaffeineNonceConsumer(0));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CaffeineNonceConsumer(10_000_001));
    }

    private static final class FakeTicker implements Ticker {

        private final AtomicLong nanos = new AtomicLong();

        @Override
        public long read() {
            return nanos.get();
        }

        void advance(Duration duration) {
            nanos.addAndGet(duration.toNanos());
        }
    }
}

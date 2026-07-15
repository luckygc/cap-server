package github.luckygc.cap.replay;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.Ticker;
import github.luckygc.cap.NonceConsumer;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 使用本机 Caffeine 缓存原子消费 JWT 签名，仅保证单 JVM 内的防重放。缓存采用硬容量，签名在 TTL 内不会提前淘汰；容量耗尽时抛出异常。默认容量为 100000，可配置范围为
 * 1..10000000。
 */
public final class CaffeineNonceConsumer implements NonceConsumer {

    public static final long DEFAULT_MAXIMUM_SIZE = 100_000;

    private static final long MINIMUM_MAXIMUM_SIZE = 1;
    private static final long MAXIMUM_MAXIMUM_SIZE = 10_000_000;
    private static final Duration MAXIMUM_TTL = Duration.ofHours(24);
    private static final long MINIMUM_TTL_NANOS = 1;
    private static final long MAXIMUM_TTL_NANOS = MAXIMUM_TTL.toNanos();

    private final Cache<String, Long> cache;
    private final Map<String, Long> deadlines = new LinkedHashMap<>();
    private final long maximumSize;
    private final Ticker ticker;

    /** 创建默认容量为 100000 的单 JVM 消费者。缓存采用硬容量，签名在 TTL 内不会提前淘汰，容量耗尽时抛出异常；可配置容量范围为 1..10000000。 */
    public CaffeineNonceConsumer() {
        this(DEFAULT_MAXIMUM_SIZE, Ticker.systemTicker());
    }

    /** 创建指定硬容量的单 JVM 消费者。签名在 TTL 内不会提前淘汰，容量耗尽时抛出异常；容量允许范围为 1..10000000，默认值为 100000。 */
    public CaffeineNonceConsumer(long maximumSize) {
        this(maximumSize, Ticker.systemTicker());
    }

    CaffeineNonceConsumer(long maximumSize, Ticker ticker) {
        if (maximumSize < MINIMUM_MAXIMUM_SIZE || maximumSize > MAXIMUM_MAXIMUM_SIZE) {
            throw new IllegalArgumentException("maximumSize 必须在 1 到 10000000 之间");
        }
        this.ticker = Objects.requireNonNull(ticker, "ticker");
        this.maximumSize = maximumSize;
        cache =
                Caffeine.newBuilder()
                        .executor(Runnable::run)
                        .ticker(ticker)
                        .expireAfter(new PerEntryExpiry())
                        .removalListener(this::onRemoval)
                        .build();
    }

    @Override
    public synchronized boolean consume(String signatureHex, Duration ttl) {
        Objects.requireNonNull(signatureHex, "signatureHex");
        Objects.requireNonNull(ttl, "ttl");
        long ttlNanos = clampTtl(ttl);
        long now = ticker.read();
        cache.cleanUp();
        deadlines.entrySet().removeIf(entry -> entry.getValue() - now <= 0);
        if (deadlines.containsKey(signatureHex)) {
            return false;
        }
        if (deadlines.size() >= maximumSize) {
            throw new IllegalStateException("nonce cache capacity exhausted");
        }
        long deadline = now + ttlNanos;
        deadlines.put(signatureHex, deadline);
        cache.put(signatureHex, deadline);
        return true;
    }

    synchronized long estimatedSize() {
        return deadlines.size();
    }

    void cleanUp() {
        cache.cleanUp();
    }

    private synchronized void onRemoval(String key, Long deadlineNanos, RemovalCause cause) {
        deadlines.remove(key, deadlineNanos);
    }

    private static long clampTtl(Duration ttl) {
        if (ttl.isZero() || ttl.isNegative()) {
            return MINIMUM_TTL_NANOS;
        }
        if (ttl.compareTo(MAXIMUM_TTL) >= 0) {
            return MAXIMUM_TTL_NANOS;
        }
        return Math.max(MINIMUM_TTL_NANOS, ttl.toNanos());
    }

    private static final class PerEntryExpiry implements Expiry<String, Long> {

        @Override
        public long expireAfterCreate(String key, Long deadlineNanos, long currentTime) {
            return Math.max(0, deadlineNanos - currentTime);
        }

        @Override
        public long expireAfterUpdate(
                String key, Long ttlNanos, long currentTime, long currentDuration) {
            return currentDuration;
        }

        @Override
        public long expireAfterRead(
                String key, Long ttlNanos, long currentTime, long currentDuration) {
            return currentDuration;
        }
    }
}

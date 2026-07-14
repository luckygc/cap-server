package github.luckygc.cap.internal.replay;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Ticker;
import github.luckygc.cap.NonceConsumer;
import java.time.Duration;
import java.util.Objects;

/** 使用本机 Caffeine 缓存原子消费 JWT 签名。 */
public final class CaffeineNonceConsumer implements NonceConsumer {

    public static final long DEFAULT_MAXIMUM_SIZE = 100_000;

    private static final long MINIMUM_MAXIMUM_SIZE = 1;
    private static final long MAXIMUM_MAXIMUM_SIZE = 10_000_000;
    private static final Duration MAXIMUM_TTL = Duration.ofHours(24);
    private static final long MINIMUM_TTL_NANOS = 1;
    private static final long MAXIMUM_TTL_NANOS = MAXIMUM_TTL.toNanos();

    private final Cache<String, Long> cache;

    public CaffeineNonceConsumer() {
        this(DEFAULT_MAXIMUM_SIZE, Ticker.systemTicker());
    }

    public CaffeineNonceConsumer(long maximumSize) {
        this(maximumSize, Ticker.systemTicker());
    }

    CaffeineNonceConsumer(long maximumSize, Ticker ticker) {
        if (maximumSize < MINIMUM_MAXIMUM_SIZE || maximumSize > MAXIMUM_MAXIMUM_SIZE) {
            throw new IllegalArgumentException("maximumSize 必须在 1 到 10000000 之间");
        }
        Objects.requireNonNull(ticker, "ticker");
        cache =
                Caffeine.newBuilder()
                        .maximumSize(maximumSize)
                        .ticker(ticker)
                        .expireAfter(new PerEntryExpiry())
                        .build();
    }

    @Override
    public boolean consume(String signatureHex, Duration ttl) {
        Objects.requireNonNull(signatureHex, "signatureHex");
        Objects.requireNonNull(ttl, "ttl");
        long ttlNanos = clampTtl(ttl);
        return cache.asMap().putIfAbsent(signatureHex, ttlNanos) == null;
    }

    long estimatedSize() {
        return cache.estimatedSize();
    }

    void cleanUp() {
        cache.cleanUp();
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
        public long expireAfterCreate(String key, Long ttlNanos, long currentTime) {
            return ttlNanos;
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

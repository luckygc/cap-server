package github.luckygc.cap.replay.redis;

import github.luckygc.cap.NonceConsumer;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisStringCommands;
import java.time.Duration;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** 使用 Lettuce 同步命令原子消费 challenge 签名。 */
public final class LettuceNonceConsumer implements NonceConsumer {
    private static final String DEFAULT_KEY_PREFIX = "cap:nonce:";
    private static final Duration MIN_TTL = Duration.ofMillis(1);
    private static final Duration MAX_TTL = Duration.ofHours(24);

    private final SetNxPxCommand command;
    private final String keyPrefix;

    /** 使用默认 key 前缀创建消费者；命令、连接、连接池及超时配置的生命周期均由调用方管理。 */
    public LettuceNonceConsumer(RedisStringCommands<String, String> commands) {
        this(commands, DEFAULT_KEY_PREFIX);
    }

    /** 使用指定 key 前缀创建消费者；命令、连接、连接池及超时配置的生命周期均由调用方管理。 */
    public LettuceNonceConsumer(RedisStringCommands<String, String> commands, String keyPrefix) {
        this(
                (key, ttlMillis) -> commands.set(key, "1", SetArgs.Builder.nx().px(ttlMillis)),
                keyPrefix);
        Objects.requireNonNull(commands);
    }

    LettuceNonceConsumer(SetNxPxCommand command, String keyPrefix) {
        this.command = Objects.requireNonNull(command);
        Objects.requireNonNull(keyPrefix);
        if (keyPrefix.isEmpty()) {
            throw new IllegalArgumentException("keyPrefix must not be empty");
        }
        this.keyPrefix = keyPrefix;
    }

    @Override
    public boolean consume(String signatureHex, Duration ttl) throws Exception {
        Objects.requireNonNull(signatureHex);
        Objects.requireNonNull(ttl);
        @Nullable String response = command.set(keyPrefix + signatureHex, ttlMillis(ttl));
        if (response == null) {
            return false;
        }
        if (response.equals("OK")) {
            return true;
        }
        throw new IllegalStateException("unexpected Redis SET response");
    }

    private static long ttlMillis(Duration ttl) {
        Duration effectiveTtl = ttl.compareTo(MIN_TTL) < 0 ? MIN_TTL : ttl;
        if (effectiveTtl.compareTo(MAX_TTL) > 0) {
            effectiveTtl = MAX_TTL;
        }
        return effectiveTtl.toMillis();
    }
}

@FunctionalInterface
interface SetNxPxCommand {
    @Nullable String set(String key, long ttlMillis);
}

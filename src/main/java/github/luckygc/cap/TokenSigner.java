package github.luckygc.cap;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** 签发业务 token 的扩展点。 */
@FunctionalInterface
public interface TokenSigner {

    /**
     * 在 redeem 调用线程同步签发业务 token。
     *
     * <p>同一 {@link Cap} 可被并发调用，因此自定义实现必须是可信且线程安全的；可能的阻塞、外部副作用和密钥管理由调用方负责。
     */
    String sign(@Nullable String scope, Instant expiresAt, Instant issuedAt) throws Exception;
}

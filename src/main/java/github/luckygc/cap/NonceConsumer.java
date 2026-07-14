package github.luckygc.cap;

import java.time.Duration;

/** 原子消费 challenge JWT 签名的扩展点。 */
@FunctionalInterface
public interface NonceConsumer {

    /**
     * 在 redeem 调用线程同步消费签名。
     *
     * <p>同一 {@link Cap} 可被并发调用，因此自定义实现必须是可信且线程安全的；可能的阻塞、外部副作用和分布式原子性由调用方负责。
     */
    boolean consume(String signatureHex, Duration ttl) throws Exception;
}

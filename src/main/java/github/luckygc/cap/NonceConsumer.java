package github.luckygc.cap;

import java.time.Duration;

@FunctionalInterface
public interface NonceConsumer {

    boolean consume(String signatureHex, Duration ttl) throws Exception;
}

package github.luckygc.cap;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

@FunctionalInterface
public interface TokenSigner {

    String sign(@Nullable String scope, Instant expiresAt, Instant issuedAt) throws Exception;
}

package github.luckygc.cap.internal.protocol;

import org.jspecify.annotations.Nullable;

/** 协议验证失败；instrumentation 与存储错误字段留给后续统一流程复用。 */
public record ProtocolFailure(String reason, boolean instrError, @Nullable String error)
        implements Format1Protocol.ValidationResult, Format2Protocol.ValidationResult {}

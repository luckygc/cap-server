package github.luckygc.cap;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

public final class ChallengeOptions {

    private static final Duration DEFAULT_TTL = Duration.ofMinutes(10);
    private static final Duration MAX_TTL = Duration.ofHours(24);
    private static final ChallengeOptions DEFAULTS = new Builder().build();

    private final @Nullable String scope;
    private final Map<String, Object> extra;
    private final Duration ttl;

    private ChallengeOptions(Builder builder) {
        scope = builder.scope;
        extra = immutableMap(builder.extra);
        ttl = validateDuration(builder.ttl, "ttl");
    }

    public static ChallengeOptions defaults() {
        return DEFAULTS;
    }

    public static Builder builder() {
        return new Builder();
    }

    public @Nullable String scope() {
        return scope;
    }

    public Map<String, Object> extra() {
        return extra;
    }

    public Duration ttl() {
        return ttl;
    }

    static Duration validateDuration(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative() || duration.compareTo(MAX_TTL) > 0) {
            throw new IllegalArgumentException(name + " must be positive and at most 24 hours");
        }
        return duration;
    }

    static Map<String, Object> immutableMap(Map<?, ?> source) {
        return immutableMap(source, newIdentitySet());
    }

    private static Map<String, Object> immutableMap(Map<?, ?> source, Set<Object> visiting) {
        Objects.requireNonNull(source, "map");
        enterContainer(source, visiting);
        Map<String, Object> copy = new LinkedHashMap<>();
        try {
            for (Map.Entry<?, ?> entry : source.entrySet()) {
                Object key = Objects.requireNonNull(entry.getKey(), "map key");
                if (!(key instanceof String stringKey)) {
                    throw new IllegalArgumentException("map keys must be strings");
                }
                copy.put(stringKey, immutableValue(entry.getValue(), visiting));
            }
        } finally {
            visiting.remove(source);
        }
        return Collections.unmodifiableMap(copy);
    }

    static List<Object> immutableList(List<?> source) {
        return immutableList(source, newIdentitySet());
    }

    private static List<Object> immutableList(List<?> source, Set<Object> visiting) {
        Objects.requireNonNull(source, "list");
        enterContainer(source, visiting);
        List<Object> copy = new ArrayList<>(source.size());
        try {
            for (Object value : source) {
                copy.add(immutableValue(value, visiting));
            }
        } finally {
            visiting.remove(source);
        }
        return Collections.unmodifiableList(copy);
    }

    private static @Nullable Object immutableValue(@Nullable Object value, Set<Object> visiting) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            return immutableMap(map, visiting);
        }
        if (value instanceof List<?> list) {
            return immutableList(list, visiting);
        }
        if (value instanceof String
                || value instanceof Boolean
                || value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long
                || value instanceof BigInteger
                || value instanceof BigDecimal) {
            return value;
        }
        if (value instanceof Float floatValue) {
            if (Float.isFinite(floatValue)) {
                return value;
            }
            throw new IllegalArgumentException("JSON floating-point values must be finite");
        }
        if (value instanceof Double doubleValue) {
            if (Double.isFinite(doubleValue)) {
                return value;
            }
            throw new IllegalArgumentException("JSON floating-point values must be finite");
        }
        throw new IllegalArgumentException("map/list values must be immutable JSON values");
    }

    private static Set<Object> newIdentitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private static void enterContainer(Object container, Set<Object> visiting) {
        if (!visiting.add(container)) {
            throw new IllegalArgumentException("map/list values must not contain cycles");
        }
    }

    public static final class Builder {

        private @Nullable String scope;
        private Map<String, ?> extra = Map.of();
        private Duration ttl = DEFAULT_TTL;

        private Builder() {}

        public Builder scope(@Nullable String scope) {
            this.scope = scope;
            return this;
        }

        public Builder extra(Map<String, ?> extra) {
            this.extra = immutableMap(extra);
            return this;
        }

        public Builder ttl(Duration ttl) {
            this.ttl = validateDuration(ttl, "ttl");
            return this;
        }

        public ChallengeOptions build() {
            return new ChallengeOptions(this);
        }
    }
}

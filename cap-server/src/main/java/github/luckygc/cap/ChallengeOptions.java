package github.luckygc.cap;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayDeque;
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
    private static final int MAX_JSON_DEPTH = 32;
    private static final int MAX_JSON_NODES = 10_000;
    private static final int MAX_JSON_STRING_LENGTH = 16_384;
    private static final ChallengeOptions DEFAULTS = new Builder().build();

    private final @Nullable String scope;
    private final Map<String, @Nullable Object> extra;
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

    public Map<String, @Nullable Object> extra() {
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

    static Map<String, @Nullable Object> immutableMap(Map<?, @Nullable ?> source) {
        validateJsonValue(source, false);
        return immutableMap(source, newIdentitySet(), false);
    }

    private static Map<String, @Nullable Object> immutableMap(
            Map<?, @Nullable ?> source, Set<Object> visiting, boolean allowInfinity) {
        Objects.requireNonNull(source, "map");
        enterContainer(source, visiting);
        Map<String, @Nullable Object> copy = new LinkedHashMap<>();
        try {
            for (Map.Entry<?, @Nullable ?> entry : source.entrySet()) {
                Object key = Objects.requireNonNull(entry.getKey(), "map key");
                if (!(key instanceof String stringKey)) {
                    throw new IllegalArgumentException("map keys must be strings");
                }
                copy.put(stringKey, immutableValue(entry.getValue(), visiting, allowInfinity));
            }
        } finally {
            visiting.remove(source);
        }
        return Collections.unmodifiableMap(copy);
    }

    static List<@Nullable Object> immutableList(List<@Nullable ?> source) {
        validateJsonValue(source, false);
        return immutableList(source, newIdentitySet(), false);
    }

    static List<@Nullable Object> immutableSolutions(List<@Nullable ?> source) {
        validateJsonValue(source, true);
        return immutableList(source, newIdentitySet(), true);
    }

    private static List<@Nullable Object> immutableList(
            List<@Nullable ?> source, Set<Object> visiting, boolean allowInfinity) {
        Objects.requireNonNull(source, "list");
        enterContainer(source, visiting);
        List<@Nullable Object> copy = new ArrayList<>(source.size());
        try {
            for (@Nullable Object value : source) {
                copy.add(immutableValue(value, visiting, allowInfinity));
            }
        } finally {
            visiting.remove(source);
        }
        return Collections.unmodifiableList(copy);
    }

    private static @Nullable Object immutableValue(
            @Nullable Object value, Set<Object> visiting, boolean allowInfinity) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, @Nullable ?> map) {
            return immutableMap(map, visiting, allowInfinity);
        }
        if (value instanceof List<@Nullable ?> list) {
            return immutableList(list, visiting, allowInfinity);
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
            if (Float.isFinite(floatValue) || allowInfinity && !Float.isNaN(floatValue)) {
                return value;
            }
            throw new IllegalArgumentException("JSON floating-point values must be finite");
        }
        if (value instanceof Double doubleValue) {
            if (Double.isFinite(doubleValue) || allowInfinity && !Double.isNaN(doubleValue)) {
                return value;
            }
            throw new IllegalArgumentException("JSON floating-point values must be finite");
        }
        throw new IllegalArgumentException("map/list values must be immutable JSON values");
    }

    private static void validateJsonValue(Object root, boolean allowInfinity) {
        ArrayDeque<JsonFrame> pending = new ArrayDeque<>();
        Set<Object> visiting = newIdentitySet();
        pending.push(new JsonFrame(root, 1, false));
        int nodes = 0;
        while (!pending.isEmpty()) {
            JsonFrame frame = pending.pop();
            @Nullable Object value = frame.value();
            if (frame.exiting()) {
                visiting.remove(value);
                continue;
            }
            if (++nodes > MAX_JSON_NODES) {
                throw new IllegalArgumentException("协议 JSON 节点过多");
            }
            if (value instanceof Map<?, ?> map) {
                checkContainer(frame.depth(), map.size(), map, visiting);
                pending.push(new JsonFrame(map, frame.depth(), true));
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    Object key = Objects.requireNonNull(entry.getKey(), "map key");
                    if (!(key instanceof String stringKey)) {
                        throw new IllegalArgumentException("map keys must be strings");
                    }
                    checkJsonString(stringKey);
                    pending.push(new JsonFrame(entry.getValue(), frame.depth() + 1, false));
                }
            } else if (value instanceof List<?> list) {
                checkContainer(frame.depth(), list.size(), list, visiting);
                pending.push(new JsonFrame(list, frame.depth(), true));
                for (int index = list.size() - 1; index >= 0; index--) {
                    pending.push(new JsonFrame(list.get(index), frame.depth() + 1, false));
                }
            } else {
                validateJsonLeaf(value, allowInfinity);
            }
        }
    }

    private static void checkContainer(
            int depth, int size, Object container, Set<Object> visiting) {
        if (depth > MAX_JSON_DEPTH) {
            throw new IllegalArgumentException("协议 JSON 嵌套过深");
        }
        if (size > MAX_JSON_NODES) {
            throw new IllegalArgumentException("协议 JSON 节点过多");
        }
        enterContainer(container, visiting);
    }

    private static void validateJsonLeaf(@Nullable Object value, boolean allowInfinity) {
        if (value == null || value instanceof Boolean) {
            return;
        }
        if (value instanceof String string) {
            checkJsonString(string);
            return;
        }
        if (value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long
                || value instanceof BigInteger
                || value instanceof BigDecimal) {
            return;
        }
        if (value instanceof Float floatValue
                && (Float.isFinite(floatValue) || allowInfinity && !Float.isNaN(floatValue))) {
            return;
        }
        if (value instanceof Double doubleValue
                && (Double.isFinite(doubleValue) || allowInfinity && !Double.isNaN(doubleValue))) {
            return;
        }
        if (value instanceof Number) {
            throw new IllegalArgumentException("JSON floating-point values must be finite");
        }
        throw new IllegalArgumentException("map/list values must be immutable JSON values");
    }

    private static void checkJsonString(String value) {
        if (value.length() > MAX_JSON_STRING_LENGTH) {
            throw new IllegalArgumentException("协议 JSON 字符串过长");
        }
    }

    private static Set<Object> newIdentitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private static void enterContainer(Object container, Set<Object> visiting) {
        if (!visiting.add(container)) {
            throw new IllegalArgumentException("map/list values must not contain cycles");
        }
    }

    private record JsonFrame(@Nullable Object value, int depth, boolean exiting) {}

    public static final class Builder {

        private @Nullable String scope;
        private Map<String, @Nullable ?> extra = Map.of();
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

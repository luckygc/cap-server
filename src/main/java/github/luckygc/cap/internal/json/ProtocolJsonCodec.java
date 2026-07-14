package github.luckygc.cap.internal.json;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.ObjectWriteContext;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;

/** 对协议 JSON 实施类型与资源限制的流式编解码器。 */
public final class ProtocolJsonCodec {

    public static final int MAX_INPUT_BYTES = 65_536;
    private static final int MAX_DEPTH = 32;
    private static final int MAX_NODES = 10_000;
    private static final int MAX_STRING_LENGTH = 16_384;
    // 数字 token 不另设低于整个协议输入的限制；实际可用长度仍受 JSON 语法和总字节限制。
    private static final int MAX_NUMBER_LENGTH = MAX_INPUT_BYTES;
    private static final JsonFactory JSON_FACTORY =
            JsonFactory.builder()
                    .streamReadConstraints(
                            StreamReadConstraints.builder()
                                    .maxDocumentLength(MAX_INPUT_BYTES)
                                    .maxNestingDepth(MAX_DEPTH)
                                    .maxNumberLength(MAX_NUMBER_LENGTH)
                                    .maxStringLength(MAX_STRING_LENGTH)
                                    .maxNameLength(MAX_STRING_LENGTH)
                                    .build())
                    .build();

    /** 将 JSON 对象编码为 UTF-8 字节。 */
    public byte[] writeObject(Map<String, @Nullable Object> value) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Limits limits = new Limits();
        try (JsonGenerator generator =
                JSON_FACTORY.createGenerator(ObjectWriteContext.empty(), output)) {
            writeValue(generator, value, 1, limits);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("无法编码协议 JSON", exception);
        }
        byte[] encoded = output.toByteArray();
        if (encoded.length > MAX_INPUT_BYTES) {
            throw new IllegalArgumentException("协议 JSON 超过大小限制");
        }
        return encoded;
    }

    /** 从受限 UTF-8 JSON 中读取一个对象。 */
    public Map<String, @Nullable Object> readObject(byte[] encoded) {
        if (encoded.length > MAX_INPUT_BYTES) {
            throw new IllegalArgumentException("协议 JSON 超过大小限制");
        }
        Limits limits = new Limits();
        try (JsonParser parser = JSON_FACTORY.createParser(ObjectReadContext.empty(), encoded)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IllegalArgumentException("协议 JSON 顶层必须是对象");
            }
            @Nullable Object value = readValue(parser, 1, limits);
            if (parser.nextToken() != null) {
                throw new IllegalArgumentException("协议 JSON 包含多余内容");
            }
            @SuppressWarnings("unchecked")
            Map<String, @Nullable Object> object = (Map<String, @Nullable Object>) value;
            return object;
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("无法解析协议 JSON", exception);
        }
    }

    private static void writeValue(
            JsonGenerator generator, @Nullable Object value, int depth, Limits limits)
            throws JacksonException {
        limits.addNode();
        if (value == null) {
            generator.writeNull();
        } else if (value instanceof String string) {
            checkString(string);
            generator.writeString(string);
        } else if (value instanceof Boolean bool) {
            generator.writeBoolean(bool);
        } else if (value instanceof Byte number) {
            generator.writeNumber(number.shortValue());
        } else if (value instanceof Short number) {
            generator.writeNumber(number);
        } else if (value instanceof Integer number) {
            generator.writeNumber(number);
        } else if (value instanceof Long number) {
            generator.writeNumber(number);
        } else if (value instanceof BigInteger number) {
            generator.writeNumber(number);
        } else if (value instanceof Float number) {
            if (!Float.isFinite(number)) {
                throw new IllegalArgumentException("浮点数必须有限");
            }
            generator.writeNumber(number);
        } else if (value instanceof Double number) {
            if (!Double.isFinite(number)) {
                throw new IllegalArgumentException("浮点数必须有限");
            }
            generator.writeNumber(number);
        } else if (value instanceof BigDecimal number) {
            generator.writeNumber(number);
        } else if (value instanceof List<?> list) {
            checkDepth(depth);
            generator.writeStartArray();
            for (@Nullable Object element : list) {
                writeValue(generator, element, depth + 1, limits);
            }
            generator.writeEndArray();
        } else if (value instanceof Map<?, ?> map) {
            checkDepth(depth);
            generator.writeStartObject();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String name)) {
                    throw new IllegalArgumentException("JSON 对象键必须是字符串");
                }
                checkString(name);
                generator.writeName(name);
                writeValue(generator, entry.getValue(), depth + 1, limits);
            }
            generator.writeEndObject();
        } else {
            throw new IllegalArgumentException("不支持的 JSON 值类型");
        }
    }

    private static @Nullable Object readValue(JsonParser parser, int depth, Limits limits)
            throws JacksonException {
        limits.addNode();
        JsonToken token = parser.currentToken();
        if (token == JsonToken.START_OBJECT) {
            checkDepth(depth);
            Map<String, @Nullable Object> object = new LinkedHashMap<>();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.PROPERTY_NAME) {
                    throw new IllegalArgumentException("JSON 对象字段无效");
                }
                String name = parser.getString();
                checkString(name);
                if (object.containsKey(name)) {
                    throw new IllegalArgumentException("JSON 对象包含重复字段");
                }
                if (parser.nextToken() == null) {
                    throw new IllegalArgumentException("JSON 对象字段缺少值");
                }
                object.put(name, readValue(parser, depth + 1, limits));
            }
            return object;
        }
        if (token == JsonToken.START_ARRAY) {
            checkDepth(depth);
            List<@Nullable Object> array = new ArrayList<>();
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                if (parser.currentToken() == null) {
                    throw new IllegalArgumentException("JSON 数组未结束");
                }
                array.add(readValue(parser, depth + 1, limits));
            }
            return array;
        }
        if (token == JsonToken.VALUE_STRING) {
            String string = parser.getString();
            checkString(string);
            return string;
        }
        if (token == JsonToken.VALUE_NUMBER_INT) {
            BigInteger integer = parser.getBigIntegerValue();
            if (integer.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) >= 0
                    && integer.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) <= 0) {
                return integer.longValue();
            }
            return integer;
        }
        if (token == JsonToken.VALUE_NUMBER_FLOAT) {
            return parser.getDecimalValue();
        }
        if (token == JsonToken.VALUE_TRUE) {
            return true;
        }
        if (token == JsonToken.VALUE_FALSE) {
            return false;
        }
        if (token == JsonToken.VALUE_NULL) {
            return null;
        }
        throw new IllegalArgumentException("不支持的 JSON token");
    }

    private static void checkDepth(int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("协议 JSON 嵌套过深");
        }
    }

    private static void checkString(String value) {
        if (value.length() > MAX_STRING_LENGTH) {
            throw new IllegalArgumentException("协议 JSON 字符串过长");
        }
    }

    private static final class Limits {
        private int nodes;

        private void addNode() {
            nodes++;
            if (nodes > MAX_NODES) {
                throw new IllegalArgumentException("协议 JSON 节点过多");
            }
        }
    }
}

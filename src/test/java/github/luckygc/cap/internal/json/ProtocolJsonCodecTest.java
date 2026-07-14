package github.luckygc.cap.internal.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("受限协议 JSON 测试")
class ProtocolJsonCodecTest {

    private final ProtocolJsonCodec codec = new ProtocolJsonCodec();

    @Test
    @DisplayName("嵌套 JSON 对象往返并保留 null 与数值语义")
    void roundTripsNestedJsonValues() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("nullable", null);
        nested.put("values", List.of(true, 50, new BigDecimal("1.25")));
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("n", "abc");
        value.put("nested", nested);

        Map<String, Object> decoded = codec.readObject(codec.writeObject(value));
        Map<String, Object> expectedNested = new LinkedHashMap<>();
        expectedNested.put("nullable", null);
        expectedNested.put("values", List.of(true, 50L, new BigDecimal("1.25")));

        assertThat(decoded).containsEntry("n", "abc");
        assertThat(decoded.get("nested")).isEqualTo(expectedNested);
    }

    @Test
    @DisplayName("整数按范围解析为 Long 或 BigInteger")
    void preservesIntegerRange() {
        BigInteger large = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE);

        assertThat(codec.readObject(codec.writeObject(Map.of("small", 50, "large", large))))
                .containsEntry("small", 50L)
                .containsEntry("large", large);
    }

    @Test
    @DisplayName("拒绝非有限浮点数")
    void rejectsNonFiniteNumbers() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> codec.writeObject(Map.of("x", Double.NaN)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> codec.writeObject(Map.of("x", Float.POSITIVE_INFINITY)));
    }

    @Test
    @DisplayName("拒绝超过 32 层的容器")
    void rejectsExcessiveDepth() {
        Object value = "leaf";
        for (int index = 0; index < 33; index++) {
            value = List.of(value);
        }
        Object deeplyNested = value;

        assertThatIllegalArgumentException()
                .isThrownBy(() -> codec.writeObject(Map.of("x", deeplyNested)));
    }

    @Test
    @DisplayName("拒绝超过集合节点与字符串限制的值")
    void rejectsExcessiveNodesAndStrings() {
        List<Object> tooManyNodes = new ArrayList<>();
        for (int index = 0; index < 10_001; index++) {
            tooManyNodes.add(index);
        }

        assertThatIllegalArgumentException()
                .isThrownBy(() -> codec.writeObject(Map.of("x", tooManyNodes)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> codec.writeObject(Map.of("x", "a".repeat(16_385))));
    }

    @Test
    @DisplayName("拒绝超过 64 KiB 的 JSON 输入")
    void rejectsExcessiveInputBytes() {
        byte[] oversized =
                ("{\"x\":\"" + "a".repeat(65_536) + "\"}").getBytes(StandardCharsets.UTF_8);

        assertThatIllegalArgumentException().isThrownBy(() -> codec.readObject(oversized));
    }
}

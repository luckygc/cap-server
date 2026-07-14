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
    @DisplayName("1001 位大整数可对称往返")
    void roundTripsLargeInteger() {
        BigInteger largeInteger = new BigInteger("9".repeat(1_001));

        assertThat(codec.readObject(codec.writeObject(Map.of("integer", largeInteger))))
                .containsEntry("integer", largeInteger);
    }

    @Test
    @DisplayName("有限大十进制不经二进制浮点范围裁剪")
    void roundTripsLargeDecimal() {
        BigDecimal largeDecimal = new BigDecimal("1E+10000");

        assertThat(codec.readObject(codec.writeObject(Map.of("decimal", largeDecimal))))
                .containsEntry("decimal", largeDecimal);
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
    @DisplayName("容器深度边界在 32 层读写一致")
    void enforcesDepthBoundarySymmetrically() {
        Object accepted = nestedLists(31);
        Object rejected = nestedLists(32);

        assertThat(codec.readObject(codec.writeObject(Map.of("x", accepted)))).containsKey("x");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> codec.writeObject(Map.of("x", rejected)));
        assertThat(codec.readObject(nestedJson(31))).containsKey("x");
        assertThatIllegalArgumentException().isThrownBy(() -> codec.readObject(nestedJson(32)));
    }

    @Test
    @DisplayName("节点边界在 10000 个读写一致")
    void enforcesNodeBoundarySymmetrically() {
        List<Object> accepted = repeatedValues(9_998);
        List<Object> rejected = repeatedValues(9_999);

        assertThat(codec.readObject(codec.writeObject(Map.of("x", accepted)))).containsKey("x");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> codec.writeObject(Map.of("x", rejected)));
        assertThat(codec.readObject(arrayJson(9_998))).containsKey("x");
        assertThatIllegalArgumentException().isThrownBy(() -> codec.readObject(arrayJson(9_999)));
    }

    @Test
    @DisplayName("字符串边界在 16384 字符读写一致")
    void enforcesStringBoundarySymmetrically() {
        String accepted = "a".repeat(16_384);
        String rejected = "a".repeat(16_385);

        assertThat(codec.readObject(codec.writeObject(Map.of("x", accepted))))
                .containsEntry("x", accepted);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> codec.writeObject(Map.of("x", rejected)));
        assertThat(codec.readObject(jsonString(accepted))).containsEntry("x", accepted);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> codec.readObject(jsonString(rejected)));
    }

    @Test
    @DisplayName("拒绝超过 64 KiB 的 JSON 输入")
    void rejectsExcessiveInputBytes() {
        byte[] oversized =
                ("{\"x\":\"" + "a".repeat(65_536) + "\"}").getBytes(StandardCharsets.UTF_8);

        assertThatIllegalArgumentException().isThrownBy(() -> codec.readObject(oversized));
    }

    @Test
    @DisplayName("拒绝重复对象键与尾随 JSON token")
    void rejectsDuplicateKeysAndTrailingTokens() {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                codec.readObject(
                                        "{\"x\":1,\"x\":2}".getBytes(StandardCharsets.UTF_8)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> codec.readObject("{} {}".getBytes(StandardCharsets.UTF_8)));
    }

    private static Object nestedLists(int count) {
        Object value = "leaf";
        for (int index = 0; index < count; index++) {
            value = List.of(value);
        }
        return value;
    }

    private static byte[] nestedJson(int listCount) {
        return ("{\"x\":" + "[".repeat(listCount) + "0" + "]".repeat(listCount) + "}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static List<Object> repeatedValues(int count) {
        List<Object> values = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            values.add(0);
        }
        return values;
    }

    private static byte[] arrayJson(int elementCount) {
        return ("{\"x\":[" + "0,".repeat(elementCount - 1) + "0]}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] jsonString(String value) {
        return ("{\"x\":\"" + value + "\"}").getBytes(StandardCharsets.UTF_8);
    }
}

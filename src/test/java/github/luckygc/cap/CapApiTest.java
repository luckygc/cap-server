package github.luckygc.cap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Cap 公共 API 测试")
class CapApiTest {

    private static final String RSW_P =
            "10205806259106035084377733293751571221634735407569037626604267995729452116937228627283539979168064533928037302797902948501155720031179330069960893973885513";
    private static final String RSW_Q =
            "10845464008756549268190555743979032034274036693428991709208796705469030851618683070577460536493687572459333035321711534755267580660241588700371851381830067";
    private static final String RSW_N =
            "110686704463476821019825213696084061631122038578773520214764097348026028038263427036367632926659401989612436691479529501092690042328247421761368664760264261838558998566846860941584253683441579544363196073466607148146474251177495731808807464348333652254559011736542375235851606763258133047869235323164679119371";

    @Test
    @DisplayName("默认与严格配置可构建")
    void defaultAndStrictApiAreMinimal() {
        Cap defaultCap = Cap.builder("0123456789abcdef").build();
        Cap strictCap = Cap.builder("0123456789abcdef").profile(CapProfile.STRICT).build();

        assertThat(defaultCap).isNotNull();
        assertThat(strictCap).isNotNull();
    }

    @Test
    @DisplayName("拒绝过短密钥")
    void rejectsShortSecret() {
        assertThatIllegalArgumentException().isThrownBy(() -> Cap.builder("short").build());
    }

    @Test
    @DisplayName("包使用非空默认契约")
    void packageIsNullMarked() {
        assertThat(Cap.class.getPackage().isAnnotationPresent(NullMarked.class)).isTrue();
    }

    @Test
    @DisplayName("JSON 容器声明可空元素契约")
    void jsonContainersDeclareNullableElementTypes() throws NoSuchMethodException {
        AnnotatedType extraType =
                ChallengeOptions.class.getDeclaredMethod("extra").getAnnotatedReturnType();
        assertJsonValueTypeIsNullable(extraType, 1);
        assertJsonValueTypeIsNullable(
                recordComponent(ChallengeResponse.ProtocolChallenge.class, "payload"), 1);
        assertJsonValueTypeIsNullable(recordComponent(RedeemRequest.class, "solutions"), 0);
        assertJsonValueTypeIsNullable(
                recordComponent(RedeemRequest.InstrumentationResult.class, "state"), 1);
    }

    @Test
    @DisplayName("选项使用稳定默认值")
    void optionsUseStableDefaults() {
        assertThat(ChallengeOptions.defaults().scope()).isNull();
        assertThat(ChallengeOptions.defaults().extra()).isEmpty();
        assertThat(ChallengeOptions.defaults().ttl()).isEqualTo(Duration.ofMinutes(10));
        assertThat(RedeemOptions.defaults().expectedScope()).isNull();
        assertThat(RedeemOptions.defaults().tokenTtl()).isEqualTo(Duration.ofMinutes(20));
    }

    @Test
    @DisplayName("选项校验 TTL 范围")
    void optionsValidateTtlRange() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ChallengeOptions.builder().ttl(Duration.ZERO).build());
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                RedeemOptions.builder()
                                        .tokenTtl(Duration.ofHours(24).plusNanos(1))
                                        .build());

        assertThat(ChallengeOptions.builder().ttl(Duration.ofHours(24)).build().ttl())
                .isEqualTo(Duration.ofHours(24));
    }

    @Test
    @DisplayName("选项递归复制扩展数据")
    void challengeOptionsRecursivelyCopyExtra() {
        List<Object> nestedList = new ArrayList<>(List.of("first"));
        Map<String, Object> nestedMap = new LinkedHashMap<>();
        nestedMap.put("list", nestedList);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("nested", nestedMap);

        ChallengeOptions options = ChallengeOptions.builder().extra(extra).build();
        nestedList.add("second");
        nestedMap.put("later", true);
        extra.put("later", true);

        assertThat(options.extra()).containsOnlyKeys("nested");
        assertThat((Object) ((Map<?, ?>) options.extra().get("nested")).keySet())
                .isEqualTo(Set.of("list"));
        assertThat((Object) ((Map<?, ?>) options.extra().get("nested")).get("list"))
                .isEqualTo(List.of("first"));
        assertThatThrownBy(() -> options.extra().put("new", true))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("映射递归保留 JSON null")
    void mapsRecursivelyPreserveJsonNulls() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("invalid", null);

        ChallengeOptions options =
                ChallengeOptions.builder().extra(Map.of("nested", nested)).build();
        RedeemRequest.InstrumentationResult result =
                new RedeemRequest.InstrumentationResult("i", Map.of("nested", nested), null);

        Map<?, ?> optionsNested = (Map<?, ?>) options.extra().get("nested");
        Map<?, ?> resultNested = (Map<?, ?>) result.state().get("nested");
        assertThat(optionsNested.containsKey("invalid")).isTrue();
        assertThat(optionsNested.get("invalid")).isNull();
        assertThat(resultNested.containsKey("invalid")).isTrue();
        assertThat(resultNested.get("invalid")).isNull();
    }

    @Test
    @DisplayName("映射拒绝无法保证不可变的叶子")
    void mapsRejectMutableLeafValues() {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                ChallengeOptions.builder()
                                        .extra(Map.of("mutable", new StringBuilder("value"))));
    }

    @Test
    @DisplayName("映射拒绝非 JSON 与非有限数值叶子")
    void mapsRejectInvalidJsonLeafValues() {
        assertInvalidJsonLeaf('x');
        assertInvalidJsonLeaf(CapProtocol.SHA256_POW);
        assertInvalidJsonLeaf(Double.NaN);
        assertInvalidJsonLeaf(Double.POSITIVE_INFINITY);
        assertInvalidJsonLeaf(Float.NEGATIVE_INFINITY);

        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                new ChallengeResponse.ProtocolChallenge(
                                        "sha256", Map.of("invalid", 'x')));
    }

    @Test
    @DisplayName("映射拒绝循环容器")
    void mapsRejectContainerCycles() {
        Map<String, Object> cyclic = new LinkedHashMap<>();
        cyclic.put("self", cyclic);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> ChallengeOptions.builder().extra(cyclic));
    }

    @Test
    @DisplayName("协议模型不可变且校验格式")
    void challengeModelsAreImmutableAndValidateFormat() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("key", List.of("value"));
        ChallengeResponse.ProtocolChallenge protocolChallenge =
                new ChallengeResponse.ProtocolChallenge("sha256", payload);
        List<ChallengeResponse.ProtocolChallenge> challenges =
                new ArrayList<>(List.of(protocolChallenge));
        ChallengeResponse.Format2 response =
                new ChallengeResponse.Format2(2, challenges, "token", 123L);
        payload.put("later", true);
        challenges.clear();

        assertThat(response.challenges()).hasSize(1);
        assertThat(response.challenges().get(0).payload()).containsOnlyKeys("key");
        assertThatThrownBy(() -> response.challenges().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ChallengeResponse.Format2(1, List.of(), "token", 123L));
    }

    @Test
    @DisplayName("兑换模型不可变且校验成功标志")
    void redeemModelsAreImmutableAndValidateSuccessFlag() {
        List<Object> solutions = new ArrayList<>(List.of(Map.of("solution", "value")));
        RedeemRequest request = new RedeemRequest("token", solutions, null, false, false);
        solutions.clear();

        assertThat(request.solutions()).hasSize(1);
        assertThatThrownBy(() -> request.solutions().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RedeemResult.Success(false, "token", null, 1L, null, 1L));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RedeemResult.Failure(true, "reason", false, null));
    }

    @Test
    @DisplayName("事件不暴露可变协议列表")
    void eventsCopyProtocolLists() {
        List<CapProtocol> protocols = new ArrayList<>(List.of(CapProtocol.SHA256_POW));
        CapEventListener.ChallengeEvent event =
                new CapEventListener.ChallengeEvent(2, protocols, Duration.ofMillis(5));
        protocols.clear();

        assertThat(event.protocols()).containsExactly(CapProtocol.SHA256_POW);
        assertThatThrownBy(() -> event.protocols().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Builder 暴露稳定高级 API")
    void builderExposesStableAdvancedApi() {
        RswKeyPair keyPair = validRswKeyPair();
        InstrumentationOptions instrumentation =
                InstrumentationOptions.builder()
                        .level(2)
                        .blockAutomatedBrowsers(false)
                        .transformer((script, level) -> script + level)
                        .build();
        NonceConsumer consumer = (signature, ttl) -> true;
        TokenSigner signer = (scope, expiresAt, issuedAt) -> scope == null ? "none" : scope;
        CapEventListener listener = new CapEventListener() {};

        Cap cap =
                Cap.builder("0123456789abcdef")
                        .challengeDefaults(ChallengeOptions.defaults())
                        .redeemDefaults(RedeemOptions.defaults())
                        .format1(1, 1, 1)
                        .protocols(CapProtocol.SHA256_POW, CapProtocol.RSW)
                        .rswKeyPair(keyPair)
                        .rswIterations(1)
                        .instrumentation(instrumentation)
                        .nonceCacheMaximumSize(1)
                        .nonceConsumer(consumer)
                        .tokenSigner(signer)
                        .eventListener(listener)
                        .build();

        assertThat(cap).isNotNull();
    }

    @Test
    @DisplayName("Builder 允许空协议回退和重复保序并拒绝 null")
    void builderUsesUpstreamProtocolConfigurationSemantics() {
        CapBuilder builder = Cap.builder("0123456789abcdef");

        assertThat(builder.protocols()).isSameAs(builder);
        assertThat(builder.protocols(CapProtocol.RSW, CapProtocol.RSW)).isSameAs(builder);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> builder.protocols(CapProtocol.SHA256_POW, null));
    }

    @Test
    @DisplayName("外部 nonce 消费器与禁用重放保护互斥")
    void replayProtectionConfigurationIsMutuallyExclusive() {
        NonceConsumer consumer = (signature, ttl) -> true;

        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                Cap.builder("0123456789abcdef")
                                        .nonceConsumer(consumer)
                                        .disableReplayProtection());
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                Cap.builder("0123456789abcdef")
                                        .disableReplayProtection()
                                        .nonceConsumer(consumer));
    }

    @Test
    @DisplayName("RSW 密钥对执行基础校验")
    void rswKeyPairPerformsBasicValidation() {
        assertThat(validRswKeyPair().bits()).isEqualTo(1024);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RswKeyPair(1023, RSW_N, RSW_P, RSW_Q));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RswKeyPair(1024, "0", RSW_P, RSW_Q));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RswKeyPair(1024, "not-decimal", RSW_P, RSW_Q));
    }

    @Test
    @DisplayName("Instrumentation 默认转换器保持脚本")
    void instrumentationOptionsUseIdentityTransformer() {
        InstrumentationOptions defaults = InstrumentationOptions.defaults();

        assertThat(defaults.level()).isEqualTo(3);
        assertThat(defaults.transformer()).isNotNull();
        assertThat(defaults.transformer().transform("script", defaults.level()))
                .isEqualTo("script");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> InstrumentationOptions.builder().level(4));
    }

    @Test
    @DisplayName("协议门面默认可用且无效兑换安全失败")
    void protocolFacadeIsUsable() {
        Cap cap = Cap.builder("0123456789abcdef").build();
        RedeemRequest request = new RedeemRequest("token", List.of(), null, false, false);

        ChallengeResponse.Format1 challenge = (ChallengeResponse.Format1) cap.createChallenge();
        assertThat(challenge.challenge()).isEqualTo(new ChallengeResponse.Challenge(50, 32, 4));
        assertThat(challenge.instrumentation()).isNull();
        assertThat(challenge.expires())
                .isBetween(
                        System.currentTimeMillis() + 590_000, System.currentTimeMillis() + 610_000);
        assertThat(cap.createChallenge(ChallengeOptions.defaults()))
                .isInstanceOf(ChallengeResponse.Format1.class);
        assertThat(cap.redeem(request))
                .isEqualTo(new RedeemResult.Failure(false, "invalid_token", false, null));
        assertThat(cap.redeem(request, RedeemOptions.defaults()))
                .isEqualTo(new RedeemResult.Failure(false, "invalid_token", false, null));
    }

    @Test
    @DisplayName("函数接口允许受检异常")
    void functionalInterfacesExposeStableSignatures() throws Exception {
        NonceConsumer consumer = (signature, ttl) -> signature.equals("signature");
        TokenSigner signer =
                (scope, expiresAt, issuedAt) ->
                        scope + expiresAt.toEpochMilli() + issuedAt.toEpochMilli();

        assertThat(consumer.consume("signature", Duration.ofSeconds(1))).isTrue();
        assertThat(signer.sign("scope", Instant.ofEpochMilli(2), Instant.ofEpochMilli(1)))
                .isEqualTo("scope21");
    }

    private static RswKeyPair validRswKeyPair() {
        return new RswKeyPair(1024, RSW_N, RSW_P, RSW_Q);
    }

    private static void assertInvalidJsonLeaf(Object value) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ChallengeOptions.builder().extra(Map.of("invalid", value)))
                .as("invalid JSON leaf: %s", value);
    }

    private static AnnotatedType recordComponent(Class<?> recordType, String name) {
        for (RecordComponent component : recordType.getRecordComponents()) {
            if (component.getName().equals(name)) {
                return component.getAnnotatedType();
            }
        }
        throw new AssertionError("record component not found: " + name);
    }

    private static void assertJsonValueTypeIsNullable(AnnotatedType containerType, int index) {
        AnnotatedType valueType =
                ((AnnotatedParameterizedType) containerType)
                        .getAnnotatedActualTypeArguments()[index];
        assertThat(valueType.isAnnotationPresent(Nullable.class)).isTrue();
    }
}

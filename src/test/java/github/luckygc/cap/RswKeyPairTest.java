package github.luckygc.cap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.security.SecureRandom;
import java.util.SplittableRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RSW 密钥对测试")
class RswKeyPairTest {

    private static final String P =
            "10205806259106035084377733293751571221634735407569037626604267995729452116937228627283539979168064533928037302797902948501155720031179330069960893973885513";
    private static final String Q =
            "10845464008756549268190555743979032034274036693428991709208796705469030851618683070577460536493687572459333035321711534755267580660241588700371851381830067";
    private static final String N =
            "110686704463476821019825213696084061631122038578773520214764097348026028038263427036367632926659401989612436691479529501092690042328247421761368664760264261838558998566846860941584253683441579544363196073466607148146474251177495731808807464348333652254559011736542375235851606763258133047869235323164679119371";

    @Test
    @DisplayName("反序列化时防御性规范化无符号十进制字段")
    void canonicalizesDecimalFields() {
        RswKeyPair keyPair = new RswKeyPair(1024, "000" + N, "000" + P, "000" + Q);

        assertThat(keyPair.modulus()).isEqualTo(N);
        assertThat(keyPair.primeP()).isEqualTo(P);
        assertThat(keyPair.primeQ()).isEqualTo(Q);
    }

    @Test
    @DisplayName("拒绝有符号或非十进制字段")
    void rejectsNonUnsignedDecimalFields() {
        assertThatIllegalArgumentException().isThrownBy(() -> new RswKeyPair(1024, "+" + N, P, Q));
        assertThatIllegalArgumentException().isThrownBy(() -> new RswKeyPair(1024, N, "-" + P, Q));
        assertThatIllegalArgumentException().isThrownBy(() -> new RswKeyPair(1024, N, P, "0x10"));
        assertThatNullPointerException().isThrownBy(() -> new RswKeyPair(1024, null, P, Q));
    }

    @Test
    @DisplayName("拒绝相同、合数或与 N 不一致的素因子")
    void validatesPrimeFactorsAndModulus() {
        assertThatIllegalArgumentException().isThrownBy(() -> new RswKeyPair(1024, N, P, P));
        assertThatIllegalArgumentException().isThrownBy(() -> new RswKeyPair(1024, N, P, "15"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RswKeyPair(1024, N.substring(0, N.length() - 1) + "3", P, Q));
    }

    @Test
    @DisplayName("拒绝非法 bits 与不匹配的 modulus bit length")
    void validatesBits() {
        assertThatIllegalArgumentException().isThrownBy(() -> new RswKeyPair(1023, N, P, Q));
        assertThatIllegalArgumentException().isThrownBy(() -> new RswKeyPair(1024, "15", "3", "5"));
        assertThatIllegalArgumentException().isThrownBy(() -> RswKeyPair.generate(1022));
        assertThatIllegalArgumentException().isThrownBy(() -> RswKeyPair.generate(8194));
    }

    @Test
    @DisplayName("使用注入随机源生成最小尺寸密钥")
    void generatesKeyPairWithInjectedRandom() {
        RswKeyPair keyPair = RswKeyPair.generate(1024, new DeterministicSecureRandom(0x5eedL));

        assertThat(keyPair.bits()).isEqualTo(1024);
        assertThat(keyPair.modulus()).containsOnlyDigits();
        assertThat(keyPair.primeP()).isNotEqualTo(keyPair.primeQ());
        assertThat(new java.math.BigInteger(keyPair.primeP()).isProbablePrime(100)).isTrue();
        assertThat(new java.math.BigInteger(keyPair.primeQ()).isProbablePrime(100)).isTrue();
    }

    private static final class DeterministicSecureRandom extends SecureRandom {

        private final SplittableRandom random;

        private DeterministicSecureRandom(long seed) {
            random = new SplittableRandom(seed);
        }

        @Override
        public void nextBytes(byte[] bytes) {
            for (int index = 0; index < bytes.length; index++) {
                bytes[index] = (byte) random.nextInt(256);
            }
        }
    }
}

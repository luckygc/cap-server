package github.luckygc.cap.internal.rsw;

import github.luckygc.cap.RswKeyPair;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** capjs-core 兼容的 RSW challenge 生成与解答比较。 */
public final class RswSupport {

    public static final int DEFAULT_T = 75_000;
    public static final int MAX_T = 10_000_000;
    private static final BigInteger TWO = BigInteger.TWO;
    private static final int R_BYTES = 32;
    private static final int MAX_FIXED_HEX_LENGTH = 8192 / 4;

    private RswSupport() {}

    /** 使用默认连续平方次数构建 minter。 */
    public static RswMinter createMinter(RswKeyPair keyPair) {
        return createMinter(keyPair, DEFAULT_T);
    }

    /** 使用指定连续平方次数构建 minter。 */
    public static RswMinter createMinter(RswKeyPair keyPair, int t) {
        return createMinter(keyPair, t, new SecureRandom());
    }

    /** 使用调用方随机源构建 minter，供内部协议组合器和确定性互操作测试注入。 */
    public static RswMinter createMinter(RswKeyPair keyPair, int t, SecureRandom random) {
        Objects.requireNonNull(keyPair, "keyPair");
        Objects.requireNonNull(random, "random");
        validateT(t);
        BigInteger modulus = new BigInteger(keyPair.modulus());
        int modulusBytes = (keyPair.bits() + 7) / 8;
        BigInteger generator =
                unsignedRandom(random, modulusBytes)
                        .mod(modulus.subtract(BigInteger.valueOf(3)))
                        .add(TWO);
        return new RswMinter(keyPair, t, generator, random);
    }

    /** 比较 expected y 与客户端声明值，忽略合法十六进制的大小写和前导零。 */
    public static boolean verifySolution(@Nullable String expectedY, @Nullable String claimedY) {
        if (expectedY == null || claimedY == null) {
            return false;
        }
        int expectedPrefixLength = expectedY.startsWith("0x") ? 2 : 0;
        int expectedFixedHexLength = expectedY.length() - expectedPrefixLength;
        if (expectedFixedHexLength < 1
                || expectedFixedHexLength > MAX_FIXED_HEX_LENGTH
                || claimedY.length() > expectedFixedHexLength * 2 + 2) {
            return false;
        }
        @Nullable String normalizedExpected = normalizeHex(expectedY);
        @Nullable String normalizedClaimed = normalizeHex(claimedY);
        if (normalizedExpected == null || normalizedClaimed == null) {
            return false;
        }
        return MessageDigest.isEqual(
                normalizedExpected.getBytes(StandardCharsets.US_ASCII),
                normalizedClaimed.getBytes(StandardCharsets.US_ASCII));
    }

    private static void validateT(int t) {
        if (t < 1 || t > MAX_T) {
            throw new IllegalArgumentException("t must be between 1 and 10000000");
        }
    }

    private static BigInteger unsignedRandom(SecureRandom random, int byteLength) {
        byte[] bytes = new byte[byteLength];
        random.nextBytes(bytes);
        return new BigInteger(1, bytes);
    }

    static String fixedHex(BigInteger value, int byteLength) {
        if (value.signum() < 0) {
            throw new IllegalArgumentException("fixed-width hex value must be non-negative");
        }
        String hex = value.toString(16);
        int width = byteLength * 2;
        if (hex.length() > width) {
            throw new IllegalArgumentException("value exceeds fixed-width hex encoding");
        }
        return "0".repeat(width - hex.length()) + hex;
    }

    private static @Nullable String normalizeHex(@Nullable String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        String normalized = value.startsWith("0x") ? value.substring(2) : value;
        if (normalized.isEmpty()) {
            return null;
        }
        normalized = normalized.toLowerCase(Locale.ROOT);
        for (int index = 0; index < normalized.length(); index++) {
            char character = normalized.charAt(index);
            if (!(character >= '0' && character <= '9')
                    && !(character >= 'a' && character <= 'f')) {
                return null;
            }
        }
        int firstNonZero = 0;
        while (firstNonZero < normalized.length() - 1 && normalized.charAt(firstNonZero) == '0') {
            firstNonZero++;
        }
        return normalized.substring(firstNonZero);
    }

    /** 一次 RSW challenge；y 只应保存到服务端加密元数据中。 */
    public record MintedChallenge(String modulus, String x, String y, int t) {}

    /** 针对一个密钥与连续平方次数预计算完成的线程安全 minter。 */
    public static final class RswMinter {

        private final BigInteger modulus;
        private final BigInteger primeP;
        private final BigInteger primeQ;
        private final BigInteger pMinusOne;
        private final BigInteger qMinusOne;
        private final int t;
        private final int modulusBytes;
        private final BigInteger generator;
        private final BigInteger exponentP;
        private final BigInteger exponentQ;
        private final BigInteger hP;
        private final BigInteger hQ;
        private final BigInteger h;
        private final BigInteger qInverseModP;
        private final SecureRandom random;

        private RswMinter(RswKeyPair keyPair, int t, BigInteger generator, SecureRandom random) {
            modulus = new BigInteger(keyPair.modulus());
            primeP = new BigInteger(keyPair.primeP());
            primeQ = new BigInteger(keyPair.primeQ());
            pMinusOne = primeP.subtract(BigInteger.ONE);
            qMinusOne = primeQ.subtract(BigInteger.ONE);
            this.t = t;
            modulusBytes = (keyPair.bits() + 7) / 8;
            this.generator = generator;
            exponentP = TWO.modPow(BigInteger.valueOf(t), pMinusOne);
            exponentQ = TWO.modPow(BigInteger.valueOf(t), qMinusOne);
            hP = generator.mod(primeP).modPow(exponentP, primeP);
            hQ = generator.mod(primeQ).modPow(exponentQ, primeQ);
            qInverseModP = primeQ.mod(primeP).modInverse(primeP);
            h = crtCombine(hP, hQ);
            this.random = random;
        }

        /** 采样一个 256 位无符号 r 并生成 challenge 与预期解答。 */
        public MintedChallenge mint() {
            BigInteger r = unsignedRandom(random, R_BYTES);
            BigInteger rP = r.mod(pMinusOne);
            BigInteger rQ = r.mod(qMinusOne);
            BigInteger x =
                    crtCombine(
                            generator.mod(primeP).modPow(rP, primeP),
                            generator.mod(primeQ).modPow(rQ, primeQ));
            BigInteger y = crtCombine(hP.modPow(rP, primeP), hQ.modPow(rQ, primeQ));
            return new MintedChallenge(
                    fixedHex(modulus, modulusBytes),
                    fixedHex(x, modulusBytes),
                    fixedHex(y, modulusBytes),
                    t);
        }

        /** 返回该 minter 的连续平方次数。 */
        public int t() {
            return t;
        }

        BigInteger generator() {
            return generator;
        }

        BigInteger exponentP() {
            return exponentP;
        }

        BigInteger exponentQ() {
            return exponentQ;
        }

        BigInteger h() {
            return h;
        }

        private BigInteger crtCombine(BigInteger residueP, BigInteger residueQ) {
            BigInteger difference = residueP.subtract(residueQ).mod(primeP);
            BigInteger coefficient = difference.multiply(qInverseModP).mod(primeP);
            return residueQ.add(primeQ.multiply(coefficient));
        }
    }
}

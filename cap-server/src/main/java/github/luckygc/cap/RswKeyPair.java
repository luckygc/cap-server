package github.luckygc.cap;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Objects;

/** RSW 使用的 RSA 风格模数与素因子。十进制字段适合直接持久化。 */
public record RswKeyPair(int bits, String modulus, String primeP, String primeQ) {

    private static final int MIN_BITS = 1024;
    private static final int MAX_BITS = 8192;
    private static final int PRIME_CERTAINTY = 100;

    public RswKeyPair {
        validateBits(bits);
        BigInteger parsedModulus = parseUnsignedDecimal(modulus, "modulus");
        BigInteger parsedPrimeP = parseUnsignedDecimal(primeP, "primeP");
        BigInteger parsedPrimeQ = parseUnsignedDecimal(primeQ, "primeQ");
        if (parsedPrimeP.equals(parsedPrimeQ)) {
            throw new IllegalArgumentException("primeP and primeQ must be different");
        }
        if (!parsedPrimeP.isProbablePrime(PRIME_CERTAINTY)
                || !parsedPrimeQ.isProbablePrime(PRIME_CERTAINTY)) {
            throw new IllegalArgumentException("primeP and primeQ must be probable primes");
        }
        if (!parsedPrimeP.multiply(parsedPrimeQ).equals(parsedModulus)) {
            throw new IllegalArgumentException("modulus must equal primeP * primeQ");
        }
        if (parsedModulus.bitLength() != bits) {
            throw new IllegalArgumentException("modulus bit length must match bits");
        }
        modulus = parsedModulus.toString();
        primeP = parsedPrimeP.toString();
        primeQ = parsedPrimeQ.toString();
    }

    /** 生成指定偶数位数的密钥对；生成开销较高，调用方应持久化并复用结果。 */
    public static RswKeyPair generate(int bits) {
        return generate(bits, new SecureRandom());
    }

    static RswKeyPair generate(int bits, SecureRandom random) {
        validateBits(bits);
        Objects.requireNonNull(random, "random");
        int primeBits = bits / 2;
        BigInteger primeP = randomPrime(primeBits, random);
        BigInteger primeQ;
        do {
            primeQ = randomPrime(primeBits, random);
        } while (primeP.equals(primeQ));
        BigInteger modulus = primeP.multiply(primeQ);
        return new RswKeyPair(bits, modulus.toString(), primeP.toString(), primeQ.toString());
    }

    private static BigInteger randomPrime(int bits, SecureRandom random) {
        int byteLength = (bits + 7) / 8;
        BigInteger mask = BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE);
        while (true) {
            byte[] bytes = new byte[byteLength];
            random.nextBytes(bytes);
            BigInteger candidate =
                    new BigInteger(1, bytes).setBit(bits - 1).setBit(bits - 2).setBit(0).and(mask);
            if (candidate.isProbablePrime(PRIME_CERTAINTY)) {
                return candidate;
            }
        }
    }

    private static void validateBits(int bits) {
        if (bits < MIN_BITS || bits > MAX_BITS || bits % 2 != 0) {
            throw new IllegalArgumentException("bits must be even and between 1024 and 8192");
        }
    }

    private static BigInteger parseUnsignedDecimal(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isEmpty()) {
            throw new IllegalArgumentException(name + " must be an unsigned decimal integer");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < '0' || character > '9') {
                throw new IllegalArgumentException(name + " must be an unsigned decimal integer");
            }
        }
        BigInteger number = new BigInteger(value);
        if (number.signum() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return number;
    }
}

package github.luckygc.cap;

import java.math.BigInteger;
import java.util.Objects;

public record RswKeyPair(int bits, String modulus, String primeP, String primeQ) {

    public RswKeyPair {
        if (bits < 1024 || bits > 8192 || bits % 2 != 0) {
            throw new IllegalArgumentException("bits must be even and between 1024 and 8192");
        }
        requirePositiveDecimal(modulus, "modulus");
        requirePositiveDecimal(primeP, "primeP");
        requirePositiveDecimal(primeQ, "primeQ");
    }

    private static void requirePositiveDecimal(String value, String name) {
        Objects.requireNonNull(value, name);
        final BigInteger number;
        try {
            number = new BigInteger(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be a decimal integer", exception);
        }
        if (number.signum() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}

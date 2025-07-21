package github.luckygc.cap.utils;

public class Validator {

    public Validator() {
    }

    public static <T> T notNull(T obj, String arg) {
        if (obj == null) {
            throw new IllegalArgumentException(Messages.get("arg.notNull", arg));
        }

        return obj;
    }

    public static <T extends CharSequence> T notEmpty(T charSequence, String arg) {
        if (charSequence == null || charSequence.isEmpty()) {
            throw new IllegalArgumentException(Messages.get("arg.notEmpty", arg));
        }

        return charSequence;
    }
}

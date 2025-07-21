package github.luckygc.cap.utils;

import java.text.MessageFormat;
import java.util.ResourceBundle;

public class Messages {

    private static final ResourceBundle BUNDLE = ResourceBundle.getBundle("github.luckygc.messages.Messages");

    private Messages() {
    }

    public static String get(String key, Object... args) {
        return MessageFormat.format(BUNDLE.getString(key), args);
    }

    public static <T> T requireNonNull(T obj, Object... args) {
        if (obj == null) {
            throw new IllegalArgumentException(Messages.get("arg.notNull", args));
        }

        return obj;
    }
}

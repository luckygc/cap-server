package github.luckygc.cap.internal.instrumentation;

import github.luckygc.cap.InstrumentationTransformer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 仅转换库生成的无注释、无模板字面量脚本，避免通用 JavaScript minifier 的语法歧义。 */
final class BuiltinInstrumentationTransformer implements InstrumentationTransformer {

    static final BuiltinInstrumentationTransformer INSTANCE =
            new BuiltinInstrumentationTransformer();

    private BuiltinInstrumentationTransformer() {}

    @Override
    public String transform(String script, int level) {
        if (level == 0) {
            return script;
        }
        if (level == 1) {
            return compactKnownTemplate(script);
        }
        String tabled = tableStrings(script);
        return level == 2 ? tabled : compactKnownTemplate(tabled);
    }

    private static String compactKnownTemplate(String script) {
        return script.replaceAll("\\n\\s*", "");
    }

    private static String tableStrings(String script) {
        Map<String, Integer> indices = new LinkedHashMap<>();
        List<Literal> literals = new ArrayList<>();
        for (int index = 0; index < script.length(); index++) {
            char character = script.charAt(index);
            if (character != '\'' && character != '"') {
                continue;
            }
            int end = quotedEnd(script, index, character);
            String quoted = script.substring(index, end + 1);
            indices.computeIfAbsent(quoted, ignored -> indices.size());
            literals.add(new Literal(index, end + 1, quoted));
            index = end;
        }
        if (literals.isEmpty()) {
            return script;
        }
        String tableName = "_T" + Integer.toUnsignedString(script.hashCode(), 36);
        StringBuilder transformed = new StringBuilder(script.length() + indices.size() * 8);
        transformed.append("var ").append(tableName).append("=[");
        String separator = "";
        for (String quoted : indices.keySet()) {
            transformed.append(separator).append(quoted);
            separator = ",";
        }
        transformed.append("];");
        int cursor = 0;
        for (Literal literal : literals) {
            transformed.append(script, cursor, literal.start());
            boolean objectKey = nextNonWhitespace(script, literal.end()) == ':';
            if (objectKey) {
                transformed.append('[');
            }
            transformed
                    .append(tableName)
                    .append('[')
                    .append(indices.get(literal.quoted()))
                    .append(']');
            if (objectKey) {
                transformed.append(']');
            }
            cursor = literal.end();
        }
        transformed.append(script, cursor, script.length());
        return transformed.toString();
    }

    private static char nextNonWhitespace(String script, int start) {
        for (int index = start; index < script.length(); index++) {
            if (!Character.isWhitespace(script.charAt(index))) {
                return script.charAt(index);
            }
        }
        return '\0';
    }

    private static int quotedEnd(String script, int start, char quote) {
        boolean escaped = false;
        for (int index = start + 1; index < script.length(); index++) {
            char character = script.charAt(index);
            if (escaped) {
                escaped = false;
            } else if (character == '\\') {
                escaped = true;
            } else if (character == quote) {
                return index;
            }
        }
        throw new IllegalStateException("生成的 instrumentation 含未结束字符串");
    }

    private record Literal(int start, int end, String quoted) {}
}

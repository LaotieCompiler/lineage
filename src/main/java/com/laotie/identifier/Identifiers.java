package com.laotie.identifier;

/**
 * Rendering and decoding for quoted identifiers.
 *
 * <p>
 * Rendering escapes backslashes before doubling the quote character; decoding is
 * the exact inverse and also understands Druid's raw {@code \"} form.
 * </p>
 */
public final class Identifiers {

    private Identifiers() {
    }

    /** Render an internal graph identifier: unchanged when simple, else double-quoted. */
    public static String render(String name) {
        if (name == null || name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return name;
        }
        return quoteDouble(name);
    }

    public static String quoteDouble(String name) {
        return "\"" + name.replace("\\", "\\\\")
                .replace("\"", "\"\"") + "\"";
    }

    public static String quoteBacktick(String name) {
        return "`" + name.replace("`", "``") + "`";
    }

    public static String decodeDoubleQuoted(String inner) {
        StringBuilder decoded = new StringBuilder(inner.length());
        for (int i = 0; i < inner.length(); i++) {
            char ch = inner.charAt(i);
            char next = i + 1 < inner.length() ? inner.charAt(i + 1) : 0;
            if (ch == '\\' && next == '\\') {
                decoded.append('\\');
                i++;
            } else if (ch == '\\' && next == '"') {
                decoded.append('"');
                i++;
            } else if (ch == '"' && next == '"') {
                decoded.append('"');
                i++;
            } else {
                decoded.append(ch);
            }
        }
        return decoded.toString();
    }
}

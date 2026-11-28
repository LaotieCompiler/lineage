package com.laotie.identifier;

import java.util.Locale;

/**
 * Locale-independent, code-point-aware case folding used for identifier keys.
 */
public final class IdentifierCase {

    private IdentifierCase() {
    }

    /**
     * Case fold matching modern {@link String#equalsIgnoreCase} semantics without
     * depending on the JVM default locale.
     */
    public static String fold(String value) {
        if (value == null) {
            return null;
        }
        StringBuilder folded = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); ) {
            int codePoint = value.codePointAt(i);
            folded.appendCodePoint(Character.toLowerCase(
                    Character.toUpperCase(codePoint)));
            i += Character.charCount(codePoint);
        }
        return folded.toString();
    }

    public static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    public static String upper(String value) {
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }
}

package com.laotie.identifier;

import com.laotie.model.metadata.MetadataCatalog;

/**
 * A parsed identifier: the raw spelling, its normalized logical name, and whether
 * the spelling used identifier quotes.
 */
public final class Identifier {

    private final String raw;
    private final String name;
    private final boolean quoted;

    private Identifier(String raw, String name, boolean quoted) {
        this.raw = raw;
        this.name = name;
        this.quoted = quoted;
    }

    public static Identifier of(String raw) {
        if (raw == null) {
            return null;
        }
        return new Identifier(raw, MetadataCatalog.normalizeIdentifier(raw),
                MetadataCatalog.isQuotedIdentifier(raw));
    }

    public static Identifier of(String name, boolean quoted) {
        if (name == null) {
            return null;
        }
        return new Identifier(name, name, quoted);
    }

    public String raw() {
        return raw;
    }

    public String name() {
        return name;
    }

    public boolean isQuoted() {
        return quoted;
    }
}

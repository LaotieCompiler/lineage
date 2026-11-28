package com.laotie.analysis.model;

public final class StarColumn {
    public final SourceTable source;
    public final String column;
    public final String key;
    public final boolean nameKnown;
    public final boolean quoted;
    /**
     * Position of this column inside its own source's output, or -1 when the plan
     * does not track it (for example a synthesized placeholder).
     */
    public final int index;

    public StarColumn(SourceTable source, String column, String key) {
        this(source, column, key, true, false);
    }

    public StarColumn(SourceTable source, String column, String key, boolean nameKnown,
            boolean quoted) {
        this(source, column, key, nameKnown, quoted, -1);
    }

    public StarColumn(SourceTable source, String column, String key, boolean nameKnown,
            boolean quoted, int index) {
        this.source = source;
        this.column = column;
        this.key = key;
        this.nameKnown = nameKnown;
        this.quoted = quoted;
        this.index = index;
    }
}

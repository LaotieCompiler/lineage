package com.laotie.analysis.model;

import java.util.List;

public final class ScalarSubquery {
    public final String temp;
    public final List<String> columns;
    /**
     * The exact references the inner analysis wrote, so the outer projection
     * names the same vertices.
     */
    public final List<String> references;

    public ScalarSubquery(String temp, List<String> columns,
            List<String> references) {
        this.temp = temp;
        this.columns = columns;
        this.references = references;
    }
}

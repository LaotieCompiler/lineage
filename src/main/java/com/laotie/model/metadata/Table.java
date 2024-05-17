package com.laotie.model.metadata;

import java.util.HashSet;
import java.util.Set;

public class Table {
    Schema schema;
    String name = "_";
    Set<String> columns = new HashSet<>();

    public Table(Schema schema, String name, Set<String> columns) {
        this.schema = schema;
        this.name = name;
        this.columns = columns;
    }

    public Table(String schema, String name) {
        // this.schema = schema;
        this.name = name;
    }

    public String getName() {
        return name;
    }
}

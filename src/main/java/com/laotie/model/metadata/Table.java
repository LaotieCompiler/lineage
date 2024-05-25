package com.laotie.model.metadata;

import java.util.HashSet;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Table {
    private String schema;
    private String name = "_";
    private Set<String> columns = new HashSet<>();

    public Table(String schema, String name, Set<String> columns) {
        this.schema = schema;
        this.name = name;
        this.columns = columns;
    }

    public Table(String schema, String name) {
        // this.schema = schema;
        this.name = name;
    }

}

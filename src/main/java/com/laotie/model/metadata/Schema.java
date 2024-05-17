package com.laotie.model.metadata;

import java.util.HashSet;
import java.util.Set;

public class Schema {
    String name = "_";
    Set<Table> tables = new HashSet<>();

    public Schema(String name) {
        this.name = name;
    }

    public void addTable(Table table) {
        tables.add(table);
    }

    public String getName() {
        return name;
    }

}

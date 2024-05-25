package com.laotie.model.metadata;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Table {
    private String name = "_";
    // private List<String> columns = new ArrayList<>();

    // public Table(String schema, String name, List<String> columns) {
    //     this.schema = schema;
    //     this.name = name;
    //     this.columns = columns;
    // }

    public Table(String name) {
        this.name = name;
    }

    @Override
    public String toString() {
        return String.format("%s", name);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass()!= o.getClass()) {
            return false;
        }
        Table table = (Table) o;
        return name.equals(table.name);
    }

    @Override
    public int hashCode() {
        return (name).hashCode();
    }

    // public void addColumn(String column) {
    //     columns.add(column);
    // }

    // public void addAllColumn(List<String> columns) {
    //     columns.addAll(columns);
    // }

}

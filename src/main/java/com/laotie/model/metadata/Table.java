package com.laotie.model.metadata;

import java.util.ArrayList;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
/**
 * Represents a database table with a name and a list of columns.
 * The table can be configured to have ordered columns.
 */
public class Table {
    private String name = "_";
    private List<Column> columns = new ArrayList<>();
    private Boolean orderedColumns = false;

    public Table(String name, List<Column> columns, Boolean orderedColumns) {
        this.name = name;
        this.columns.addAll(columns);
        this.orderedColumns = orderedColumns;
        checkColumnUnique();
    }

    public Table(String name, List<Column> columns) {
        this(name, columns, false);
    }

    public Table(String name) {
        this(name, new ArrayList<>(), false);
    }

    public void addColumn(Column column) {
        columns.add(column);
        checkColumnUnique();
    }

    public void addAllColumn(List<Column> cols) {
        columns.addAll(cols);
        checkColumnUnique();
    }

    private void checkColumnUnique(){
        if (columns.stream().map(Column::getColumnName).distinct().count() != columns.size()) {
            throw new IllegalArgumentException("Duplicate column name");
        }
    }

    @Override
    public String toString() {
        
        String ColumnStr = columns.size()==0 ? "_" : columns.stream().map(Column::getColumnName).reduce((a, b) -> a + "," + b).get();
        return String.format("%s(%s)", name, ColumnStr);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Table table = (Table) o;
        return name.equals(table.name);
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }


}

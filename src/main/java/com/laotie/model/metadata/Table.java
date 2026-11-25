package com.laotie.model.metadata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents a database table with a name and an ordered list of fields (columns).
 */
public class Table {
    private String name = "_";
    private final List<Column> columns = new ArrayList<>();
    private Boolean orderedColumns = false;
    private boolean immutable;

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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        checkMutable();
        this.name = name;
    }

    public List<Column> getColumns() {
        return Collections.unmodifiableList(columns);
    }

    public void setColumns(List<Column> columns) {
        checkMutable();
        this.columns.clear();
        this.columns.addAll(columns);
        checkColumnUnique();
    }

    public Boolean getOrderedColumns() {
        return orderedColumns;
    }

    public void setOrderedColumns(Boolean orderedColumns) {
        checkMutable();
        this.orderedColumns = orderedColumns;
    }

    public boolean isImmutable() {
        return immutable;
    }

    public void addColumn(Column column) {
        checkMutable();
        columns.add(column);
        checkColumnUnique();
    }

    public void addAllColumn(List<Column> cols) {
        checkMutable();
        columns.addAll(cols);
        checkColumnUnique();
    }

    /**
     * A field is the same concept as a column at the metadata level.
     */
    public void addField(Column field) {
        addColumn(field);
    }

    public List<Column> getFields() {
        return Collections.unmodifiableList(columns);
    }

    /**
     * SQL identifiers are case insensitive, so field lookup is too.
     */
    public Column getColumn(String columnName) {
        return getColumnIgnoreCase(columnName);
    }

    public Column getColumnExact(String columnName) {
        if (columnName == null) {
            return null;
        }
        for (Column column : columns) {
            if (column.getColumnName().equals(columnName)) {
                return immutable ? column.snapshot() : column;
            }
        }
        return null;
    }

    public Column getColumnIgnoreCase(String columnName) {
        if (columnName == null) {
            return null;
        }
        for (Column column : columns) {
            if (column.getColumnName().equalsIgnoreCase(columnName)) {
                return immutable ? column.snapshot() : column;
            }
        }
        return null;
    }

    public boolean hasColumn(String columnName) {
        return getColumn(columnName) != null;
    }

    public Table snapshot() {
        Table copy = new Table(name, new ArrayList<Column>(), orderedColumns);
        for (Column column : columns) {
            copy.columns.add(column.snapshot());
        }
        copy.immutable = true;
        return copy;
    }

    private void checkMutable() {
        if (immutable) {
            throw new UnsupportedOperationException("table is read-only: " + name);
        }
    }

    private void checkColumnUnique() {
        if (columns.stream().map(Column::getColumnName).distinct().count() != columns.size()) {
            throw new IllegalArgumentException("Duplicate column name");
        }
    }

    @Override
    public String toString() {
        String columnStr = columns.isEmpty() ? "_"
                : columns.stream().map(Column::getColumnName).reduce((a, b) -> a + "," + b).get();
        return String.format("%s(%s)", name, columnStr);
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

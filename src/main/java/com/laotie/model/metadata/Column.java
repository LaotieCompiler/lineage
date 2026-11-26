package com.laotie.model.metadata;

import java.util.Objects;

/**
 * A column (field) reference. It is also used as a vertex in the lineage graph.
 *
 * <p>
 * Temporary columns (intermediate projections) have a distinct identity from physical columns,
 * even when their display string is identical.
 */
public class Column {
    private String tableName;
    private String columnName;
    private String dataType;
    private boolean temporary;
    private boolean quoted;
    private boolean immutable;

    /**
     * Create a physical column from a qualified name such as {@code db1.A.c1}.
     * The last segment is the column name; everything before it is the table name.
     */
    public Column(String qualifiedName) {
        String[] parts = MetadataCatalog.splitQualifiedNamePreservingQuotes(qualifiedName);
        if (parts.length > 1) {
            StringBuilder table = new StringBuilder();
            for (int i = 0; i < parts.length - 1; i++) {
                if (i > 0) {
                    table.append('.');
                }
                table.append(parts[i]);
            }
            this.tableName = table.toString();
            this.columnName = MetadataCatalog.normalizeIdentifier(parts[parts.length - 1]);
        } else {
            this.columnName = parts.length == 0 ? qualifiedName
                    : MetadataCatalog.normalizeIdentifier(parts[0]);
        }
    }

    public Column(String tableName, String columnName) {
        this.tableName = tableName;
        this.columnName = columnName;
    }

    /**
     * Create a temporary graph vertex such as {@code stmt0.temp0.c1}.
     */
    public static Column temporary(String qualifiedName) {
        Column column = new Column(qualifiedName);
        column.temporary = true;
        return column;
    }

    public String getTableName() {
        return tableName;
    }

    public String getColumnName() {
        return columnName;
    }

    public String getDataType() {
        return dataType;
    }

    public boolean isTemporary() {
        return temporary;
    }

    public boolean isQuoted() {
        return quoted;
    }

    public void setQuoted(boolean quoted) {
        checkMutable();
        this.quoted = quoted;
    }

    public boolean isImmutable() {
        return immutable;
    }

    public void setTableName(String tableName) {
        checkMutable();
        this.tableName = tableName;
    }

    public void setColumnName(String columnName) {
        checkMutable();
        this.columnName = columnName;
    }

    public void setDataType(String dataType) {
        checkMutable();
        this.dataType = dataType;
    }

    public void setTemporary(boolean temporary) {
        checkMutable();
        this.temporary = temporary;
    }

    /**
     * A read-only deep copy, used by published metadata snapshots.
     */
    public Column snapshot() {
        Column copy = new Column(tableName, columnName);
        copy.dataType = dataType;
        copy.temporary = temporary;
        copy.quoted = quoted;
        copy.immutable = true;
        return copy;
    }

    private void checkMutable() {
        if (immutable) {
            throw new UnsupportedOperationException("column is read-only: " + this);
        }
    }

    @Override
    public String toString() {
        if (tableName == null) {
            return renderIdentifier(columnName);
        }
        return tableName + "." + renderIdentifier(columnName);
    }

    private static String renderIdentifier(String name) {
        if (name == null || "*".equals(name) || name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return name;
        }
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Column column = (Column) o;
        return temporary == column.temporary
                && Objects.equals(tableName, column.tableName)
                && Objects.equals(columnName, column.columnName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tableName, columnName, temporary);
    }
}

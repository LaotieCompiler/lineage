package com.laotie.model.metadata;

import java.util.Objects;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Column {
    private String tableName;
    private String columnName;

    public Column(String columnName) {
        String[] parts = columnName.split("\\.");
        if (parts.length > 1) {
            int length = parts.length;
            this.tableName = parts[length-2];
            this.columnName = parts[length-1];
        } else {
            this.columnName = columnName;
        }
    }

    public Column(String tableName, String columnName) {
        this(columnName);
        this.tableName = tableName;
    }

    @Override
    public String toString() {
        return tableName + "." + columnName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Column column = (Column) o;
        return Objects.equals(tableName, column.tableName) &&
                Objects.equals(columnName, column.columnName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tableName, columnName);
    }

}

package com.laotie.model.metadata;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A schema inside a database: Instance -> Database -> Schema -> Table -> Field.
 */
public class Schema {
    private final String name;
    private final Map<String, Table> tables = new LinkedHashMap<>();
    private boolean immutable;

    public Schema(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public Table getOrCreateTable(String tableName) {
        checkMutable();
        return tables.computeIfAbsent(tableName, Table::new);
    }

    public Table getTable(String tableName) {
        return tables.get(tableName);
    }

    public void putTable(Table table) {
        checkMutable();
        tables.put(table.getName(), table);
    }

    public Collection<Table> getTables() {
        return tables.values();
    }

    public Map<String, Table> getTableMap() {
        return tables;
    }

    public Schema snapshot() {
        Schema copy = new Schema(name);
        for (Map.Entry<String, Table> entry : tables.entrySet()) {
            copy.tables.put(entry.getKey(), entry.getValue().snapshot());
        }
        copy.immutable = true;
        return copy;
    }

    private void checkMutable() {
        if (immutable) {
            throw new UnsupportedOperationException("schema is read-only: " + name);
        }
    }

    @Override
    public String toString() {
        return name;
    }
}

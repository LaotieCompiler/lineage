package com.laotie.model.metadata;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A database inside an instance: Instance -> Database -> Schema -> Table -> Field.
 */
public class Database {
    private final String name;
    private final Map<String, Schema> schemas = new LinkedHashMap<>();
    private boolean immutable;

    public Database(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public Schema getOrCreateSchema(String schemaName) {
        checkMutable();
        return schemas.computeIfAbsent(schemaName, Schema::new);
    }

    public Schema getSchema(String schemaName) {
        return schemas.get(schemaName);
    }

    public Collection<Schema> getSchemas() {
        return schemas.values();
    }

    public Map<String, Schema> getSchemaMap() {
        return schemas;
    }

    public Database snapshot() {
        Database copy = new Database(name);
        for (Map.Entry<String, Schema> entry : schemas.entrySet()) {
            copy.schemas.put(entry.getKey(), entry.getValue().snapshot());
        }
        copy.immutable = true;
        return copy;
    }

    private void checkMutable() {
        if (immutable) {
            throw new UnsupportedOperationException("database is read-only: " + name);
        }
    }

    @Override
    public String toString() {
        return name;
    }
}

package com.laotie.model.metadata;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The top level of the metadata hierarchy:
 * Instance -> Database -> Schema -> Table -> Field.
 */
public class Instance {
    private final String name;
    private final Map<String, Database> databases = new LinkedHashMap<>();
    private boolean immutable;

    public Instance(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public Database getOrCreateDatabase(String databaseName) {
        checkMutable();
        return databases.computeIfAbsent(databaseName, Database::new);
    }

    public Database getDatabase(String databaseName) {
        return databases.get(databaseName);
    }

    public Collection<Database> getDatabases() {
        return databases.values();
    }

    public Map<String, Database> getDatabaseMap() {
        return databases;
    }

    public Instance snapshot() {
        Instance copy = new Instance(name);
        for (Map.Entry<String, Database> entry : databases.entrySet()) {
            copy.databases.put(entry.getKey(), entry.getValue().snapshot());
        }
        copy.immutable = true;
        return copy;
    }

    private void checkMutable() {
        if (immutable) {
            throw new UnsupportedOperationException("instance is read-only: " + name);
        }
    }

    @Override
    public String toString() {
        return name;
    }
}

package com.laotie.metadata;

import java.util.ArrayList;
import java.util.List;

import com.laotie.model.metadata.MetadataContext;

/**
 * Mutable session defaults accumulated while scanning a SQL script: current
 * instance/database/search path plus Spark ANSI settings.
 */
public final class SessionState {

    private String instance;
    private String database;
    private String rawDatabase;
    private List<String> schemas = new ArrayList<>();
    private List<String> rawSchemas = new ArrayList<>();
    private boolean ansiEnabled;
    private boolean ansiDoubleQuotedIdentifiers;

    public SessionState(MetadataContext base) {
        if (base != null) {
            this.instance = base.getInstance();
            this.database = base.getDatabase();
            this.schemas = new ArrayList<>(base.getSchemas());
        }
    }

    public MetadataContext toContext(MetadataContext base) {
        return base == null ? null : base.withSession(instance, database, schemas);
    }

    public String getInstance() {
        return instance;
    }

    public String getDatabase() {
        return database;
    }

    public void setDatabase(String database) {
        this.database = database;
    }

    public String getRawDatabase() {
        return rawDatabase;
    }

    public void setRawDatabase(String rawDatabase) {
        this.rawDatabase = rawDatabase;
    }

    public List<String> getSchemas() {
        return schemas;
    }

    public void setSchemas(List<String> schemas) {
        this.schemas = schemas == null ? new ArrayList<String>() : schemas;
    }

    public List<String> getRawSchemas() {
        return rawSchemas;
    }

    public void setRawSchemas(List<String> rawSchemas) {
        this.rawSchemas = rawSchemas == null ? new ArrayList<String>() : rawSchemas;
    }

    public boolean isAnsiEnabled() {
        return ansiEnabled;
    }

    public void setAnsiEnabled(boolean ansiEnabled) {
        this.ansiEnabled = ansiEnabled;
    }

    public boolean isAnsiDoubleQuotedIdentifiers() {
        return ansiDoubleQuotedIdentifiers;
    }

    public void setAnsiDoubleQuotedIdentifiers(boolean ansiDoubleQuotedIdentifiers) {
        this.ansiDoubleQuotedIdentifiers = ansiDoubleQuotedIdentifiers;
    }
}

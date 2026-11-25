package com.laotie.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.laotie.model.metadata.MetadataContext;

/**
 * The lineage result of one SQL statement, including the metadata snapshot labels and the
 * effective session context.
 */
public class LineageResult {
    private final String sql;
    private final List<Instruction> instructions;
    private final MetadataContext context;
    private final long metadataVersion;
    private final String metadataChecksum;
    private final Throwable error;
    private final String instance;
    private final String database;
    private final List<String> schemas;

    public LineageResult(String sql, List<Instruction> instructions, MetadataContext context,
            Throwable error, String instance, String database, List<String> schemas) {
        this.sql = sql;
        this.instructions = Collections.unmodifiableList(new ArrayList<>(instructions));
        this.context = context;
        this.metadataVersion = context == null ? 0 : context.getMetadataVersion();
        this.metadataChecksum = context == null ? null : context.getMetadataChecksum();
        this.error = error;
        this.instance = instance;
        this.database = database;
        this.schemas = Collections.unmodifiableList(new ArrayList<>(schemas));
    }

    public String getSql() {
        return sql;
    }

    public List<Instruction> getInstructions() {
        return instructions;
    }

    public MetadataContext getContext() {
        return context;
    }

    public long getMetadataVersion() {
        return metadataVersion;
    }

    public String getMetadataChecksum() {
        return metadataChecksum;
    }

    public Throwable getError() {
        return error;
    }

    public String getInstance() {
        return instance;
    }

    public String getDatabase() {
        return database;
    }

    public String getSchema() {
        return schemas.isEmpty() ? null : schemas.get(0);
    }

    public List<String> getSchemas() {
        return schemas;
    }
}

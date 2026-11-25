package com.laotie.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private final Set<String> temporaryTables;
    private final Map<String, List<String>> targetOutputs;

    public LineageResult(String sql, List<Instruction> instructions, MetadataContext context,
            Throwable error, String instance, String database, List<String> schemas) {
        this(sql, instructions, context, error, instance, database, schemas, new HashSet<>(),
                new HashMap<>());
    }

    public LineageResult(String sql, List<Instruction> instructions, MetadataContext context,
            Throwable error, String instance, String database, List<String> schemas,
            Set<String> temporaryTables) {
        this(sql, instructions, context, error, instance, database, schemas, temporaryTables,
                new HashMap<>());
    }

    public LineageResult(String sql, List<Instruction> instructions, MetadataContext context,
            Throwable error, String instance, String database, List<String> schemas,
            Set<String> temporaryTables, Map<String, List<String>> targetOutputs) {
        this.sql = sql;
        this.instructions = Collections.unmodifiableList(new ArrayList<>(instructions));
        this.context = context;
        this.metadataVersion = context == null ? 0 : context.getMetadataVersion();
        this.metadataChecksum = context == null ? null : context.getMetadataChecksum();
        this.error = error;
        this.instance = instance;
        this.database = database;
        this.schemas = Collections.unmodifiableList(new ArrayList<>(schemas));
        this.temporaryTables = Collections.unmodifiableSet(new HashSet<>(temporaryTables));
        this.targetOutputs = Collections.unmodifiableMap(copyOutputs(targetOutputs));
    }

    private static Map<String, List<String>> copyOutputs(Map<String, List<String>> outputs) {
        Map<String, List<String>> copy = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : outputs.entrySet()) {
            copy.put(entry.getKey(), Collections.unmodifiableList(
                    new ArrayList<>(entry.getValue())));
        }
        return copy;
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

    public Set<String> getTemporaryTables() {
        return temporaryTables;
    }

    public Map<String, List<String>> getTargetOutputs() {
        return targetOutputs;
    }
}

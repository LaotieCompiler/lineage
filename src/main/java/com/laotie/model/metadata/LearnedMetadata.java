package com.laotie.model.metadata;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import com.alibaba.druid.DbType;

/**
 * Table columns discovered while scanning SQL statements.
 *
 * <p>
 * When no catalog is available, an observed reference such as {@code select A.c1 from A} still
 * tells us that column {@code c1} belongs to table {@code A}. Recording those observations lets
 * later statements resolve an unqualified column against the tables that were seen before,
 * instead of failing or guessing.
 *
 * <p>
 * Learned metadata is a heuristic: it only lists the columns that were actually referenced, so
 * it is never used to expand a wildcard into column names. It is only used to decide which
 * table an unqualified column reference belongs to.
 */
public class LearnedMetadata {

    private final DbType dialect;
    private final Map<String, Set<String>> columns = new LinkedHashMap<>();

    public LearnedMetadata(DbType dialect) {
        this.dialect = dialect == null ? DbType.mysql : dialect;
    }

    /**
     * Record that {@code column} was referenced on {@code table}.
     */
    public synchronized void learn(String table, String column) {
        String tableKey = tableKey(table);
        String columnKey = columnKey(column);
        if (tableKey == null || columnKey == null) {
            return;
        }
        columns.computeIfAbsent(tableKey, key -> new LinkedHashSet<>()).add(columnKey);
    }

    /**
     * Whether {@code column} was observed on {@code table}.
     */
    public synchronized boolean hasColumn(String table, String column) {
        String tableKey = tableKey(table);
        String columnKey = columnKey(column);
        if (tableKey == null || columnKey == null) {
            return false;
        }
        Set<String> known = columns.get(tableKey);
        return known != null && known.contains(columnKey);
    }

    /**
     * Whether anything was learned about {@code table}.
     */
    public synchronized boolean knowsTable(String table) {
        String tableKey = tableKey(table);
        return tableKey != null && columns.containsKey(tableKey);
    }

    /**
     * The columns observed on {@code table}, in observation order.
     */
    public synchronized Set<String> columnsOf(String table) {
        String tableKey = tableKey(table);
        Set<String> known = tableKey == null ? null : columns.get(tableKey);
        return known == null ? Collections.<String>emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<>(known));
    }

    public synchronized boolean isEmpty() {
        return columns.isEmpty();
    }

    /**
     * The dialect-aware key of a table reference.
     */
    public String tableKey(String table) {
        if (table == null) {
            return null;
        }
        String[] parts = MetadataCatalog.splitQualifiedNamePreservingQuotes(table);
        if (parts.length == 0) {
            return null;
        }
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                key.append('.');
            }
            key.append(fold(parts[i]));
        }
        return key.toString();
    }

    /**
     * The dialect-aware key of a column reference.
     */
    public String columnKey(String column) {
        if (column == null) {
            return null;
        }
        return fold(column);
    }

    private String fold(String rawName) {
        boolean quoted = MetadataCatalog.isQuotedIdentifier(rawName);
        String name = MetadataCatalog.normalizeIdentifier(rawName);
        if (name == null || name.isEmpty()) {
            return null;
        }
        if (quoted) {
            return name;
        }
        switch (dialect) {
            case mysql:
            case mariadb:
            case postgresql:
                return name.toLowerCase();
            default:
                return name.toUpperCase();
        }
    }
}

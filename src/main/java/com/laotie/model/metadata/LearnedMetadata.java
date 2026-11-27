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
     * A deep copy, used to build a metadata candidate without touching the scanner's
     * published state.
     */
    public LearnedMetadata(LearnedMetadata other) {
        this.dialect = other == null ? DbType.mysql : other.dialect;
        if (other != null) {
            synchronized (other) {
                for (Map.Entry<String, Set<String>> entry : other.columns.entrySet()) {
                    this.columns.put(entry.getKey(),
                            new LinkedHashSet<>(entry.getValue()));
                }
            }
        }
    }

    /**
     * Add every observation of {@code other} to this instance.
     */
    public synchronized void mergeFrom(LearnedMetadata other) {
        if (other == null) {
            return;
        }
        synchronized (other) {
            for (Map.Entry<String, Set<String>> entry : other.columns.entrySet()) {
                columns.computeIfAbsent(entry.getKey(),
                        key -> new LinkedHashSet<String>()).addAll(entry.getValue());
            }
        }
    }

    /**
     * Whether both instances hold exactly the same observations.
     */
    public synchronized boolean sameAs(LearnedMetadata other) {
        if (other == null) {
            return false;
        }
        synchronized (other) {
            return columns.equals(other.columns);
        }
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
     * Record an already normalized observation.
     */
    public synchronized void learnNormalized(String tableKey, String columnKey) {
        if (tableKey == null || columnKey == null) {
            return;
        }
        columns.computeIfAbsent(tableKey, key -> new LinkedHashSet<>()).add(columnKey);
    }

    /**
     * Whether an already normalized observation exists.
     */
    public synchronized boolean hasNormalizedColumn(String tableKey, String columnKey) {
        Set<String> known = tableKey == null ? null : columns.get(tableKey);
        return known != null && columnKey != null && known.contains(columnKey);
    }

    /**
     * Whether anything is known about an already normalized table key.
     */
    public synchronized boolean knowsNormalizedTable(String tableKey) {
        return tableKey != null && columns.containsKey(tableKey);
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

    /**
     * The number of table/column observations, used to compare candidates.
     */
    public synchronized int columnCount() {
        int count = 0;
        for (Set<String> columnsOfTable : columns.values()) {
            count += columnsOfTable.size();
        }
        return count;
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

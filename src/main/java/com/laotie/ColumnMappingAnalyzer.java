package com.laotie;

import java.util.Locale;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.expr.SQLTextLiteralExpr;
import com.laotie.analysis.RelationClassifier;
import com.laotie.analysis.model.SourceTable;
import com.laotie.exception.ColumnNotFoundException;
import com.laotie.exception.MetadataNotFoundException;
import com.laotie.model.Instruction;
import com.laotie.model.Instruction.OperationType;
import com.laotie.model.metadata.MetadataCatalog;
import com.laotie.model.metadata.Table;

/** Resolves and renders physical column identities and column-mapping edges. */
final class ColumnMappingAnalyzer {

    private final DmlLineage host;

    ColumnMappingAnalyzer(DmlLineage host) {
        this.host = host;
    }

    String uncatalogedColumnSpelling(String raw) {
        DbType dialect = host.currentDialect();
        return dialect == DbType.hive || dialect == DbType.antspark
                ? DmlStatementAnalyzer.hiveColumnIdentity(raw) : canonicalColumnName(raw);
    }

    String canonicalColumnName(String raw) {
        if (raw == null) {
            return null;
        }
        String name = DmlLineage.normalizeIdentifier(raw);
        if (!host.detachedMetadata() || MetadataCatalog.isQuotedIdentifier(raw)
                || host.currentDialect() != DbType.postgresql) {
            return name;
        }
        return name.toLowerCase(Locale.ROOT);
    }

    String canonicalColumnName(String table, String rawColumn) {
        if (host.analysisContext() == null || host.state().tempTables.contains(table)) {
            return DmlLineage.normalizeIdentifier(rawColumn);
        }
        try {
            Table metadata = host.analysisContext().getCatalog().findTableByDisplayName(table).getTable();
            String resolved = MetadataCatalog.resolveColumnName(metadata, rawColumn,
                    host.currentDialect());
            if (resolved == null) {
                throw new ColumnNotFoundException(DmlLineage.normalizeIdentifier(rawColumn),
                        java.util.Collections.singletonList(table));
            }
            return resolved;
        } catch (MetadataNotFoundException e) {
            return DmlLineage.normalizeIdentifier(rawColumn);
        }
    }

    private String insertColumnName(Table metadata, String tableName, SQLExpr column) {
        String raw = column.toString();
        if (metadata == null) {
            return canonicalColumnName(raw);
        }
        String resolved = MetadataCatalog.resolveColumnName(metadata, raw, host.currentDialect());
        if (resolved == null) {
            throw new ColumnNotFoundException(DmlLineage.normalizeIdentifier(raw),
                    java.util.Collections.singletonList(tableName));
        }
        return resolved;
    }

    String renderInsertColumn(Table metadata, String tableName, SQLExpr column) {
        String raw = column.toString();
        DbType dialect = host.currentDialect();
        if ((dialect == DbType.hive || dialect == DbType.antspark)
                && column instanceof SQLTextLiteralExpr) {
            if (!Boolean.TRUE.equals(column.getAttribute(
                    DmlLineage.ANSI_IDENTIFIER_ATTRIBUTE))) {
                // An INSERT target list requires identifiers; a quoted string is
                // not one.
                throw new ColumnNotFoundException(raw,
                        java.util.Collections.singletonList(tableName));
            }
            String name = ((SQLTextLiteralExpr) column).getText();
            if (metadata == null) {
                return DmlStatementAnalyzer.renderHiveIdentifier(DmlStatementAnalyzer.foldCase(name));
            }
            String quoted = DmlLineage.quoteIdentifierValue(name);
            String resolved = MetadataCatalog.resolveColumnName(metadata, quoted,
                    dialect);
            if (resolved == null) {
                throw new ColumnNotFoundException(DmlLineage.normalizeIdentifier(name),
                        java.util.Collections.singletonList(tableName));
            }
            return DmlStatementAnalyzer.renderHiveIdentifier(resolved);
        }
        if ((dialect == DbType.hive || dialect == DbType.antspark)
                && metadata == null) {
            // Without a catalog, Hive/Spark column identity is case-insensitive,
            // but literal quotes remain part of the name.
            return DmlStatementAnalyzer.renderHiveIdentifier(DmlStatementAnalyzer.hiveColumnIdentity(raw));
        }
        String resolved = insertColumnName(metadata, tableName, column);
        if (MetadataCatalog.isQuotedIdentifier(raw)) {
            if (dialect == DbType.hive || dialect == DbType.antspark) {
                return DmlStatementAnalyzer.renderHiveIdentifier(resolved);
            }
            return DmlLineage.quoteIdentifierValue(resolved);
        }
        return DmlLineage.renderIdentifier(resolved);
    }

    void addColumnMapping(SourceTable source, String columnName) {
        String sourceName = host.detachedMetadata() && !source.temporary
                ? host.lineageTableName(source) : source.displayName;
        if (source.wildcardContribution && source.temporary) {
            // Reading one named column from a derived table that also carries unknown
            // rows must not cut the table-level lineage.
            String target = host.state().stackTargetTable.peek();
            Instruction wildcard = new Instruction(OperationType.COLUMNS_INJECT,
                    host.lineageTableName(source), target, host.state().currentOrdinal,
                    source.temporary, host.state().tempTables.contains(target));
            wildcard.setPositionalWildcard(true);
            wildcard.setWildcardWidthKnown(true);
            wildcard.setWildcardWidth(1);
            host.instructions().add(wildcard);
            host.recordWildcardSpan(target, host.state().currentOrdinal, host.state().currentOrdinal + 1,
                    host.lineageTableName(source), source.temporary);
            host.state().targetWildcardContribution.put(target, Boolean.TRUE);
        }
        if (!source.outputColumns.isEmpty()) {
            String key = host.identifierKey(columnName);
            if (key == null) {
                return;
            }
            String canonical = null;
            for (int i = 0; i < source.outputKeys.size(); i++) {
                if (source.outputKeys.get(i) != null
                    && source.outputKeys.get(i).equals(key)) {
                    canonical = source.outputColumns.get(i);
                    break;
                }
            }
            if (canonical == null) {
                addColumnMapping(sourceName, DmlLineage.normalizeIdentifier(columnName),
                        source.temporary, null);
            } else {
                addResolvedColumnMapping(sourceName, canonical,
                        source.temporary);
            }
            return;
        }
        if (source.metadataTable == null) {
            DbType dialect = host.currentDialect();
            if (dialect == DbType.hive || dialect == DbType.antspark) {
                addResolvedColumnMapping(sourceName,
                        DmlStatementAnalyzer.hiveColumnIdentity(columnName), source.temporary);
            } else {
                addColumnMapping(sourceName, canonicalColumnName(columnName),
                        source.temporary, null);
            }
            return;
        }
        addColumnMapping(sourceName, columnName, source.temporary,
                source.metadataTable);
    }

    void addColumnMapping(String sourceTable, String columnName,
            boolean sourceTemporary, Table sourceMetadata) {
        if (sourceMetadata != null) {
            String resolved = MetadataCatalog.resolveColumnName(sourceMetadata, columnName,
                    host.currentDialect());
            columnName = resolved != null ? resolved : DmlLineage.normalizeIdentifier(columnName);
        } else {
            columnName = DmlLineage.normalizeIdentifier(columnName);
        }
        addResolvedColumnMapping(sourceTable, columnName, sourceTemporary);
    }

    private void addResolvedColumnMapping(String sourceTable, String columnName,
            boolean sourceTemporary) {
        if (host.state().uncertainOrdinal >= 0 && host.state().currentOrdinal == host.state().uncertainOrdinal) {
            return;
        }
        String targetTable = host.state().stackTargetTable.peek();
        Instruction instruction = new Instruction(OperationType.COLUMN_MAPPING,
                sourceTable + "." + DmlLineage.renderIdentifier(columnName),
                targetTable + "." + host.state().stackTargetColumn.peek(), host.state().currentOrdinal,
                sourceTemporary, host.state().tempTables.contains(targetTable));
        RelationClassifier.applyContext(instruction, host.state());
        host.instructions().add(instruction);
    }
}

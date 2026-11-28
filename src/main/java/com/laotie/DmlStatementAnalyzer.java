package com.laotie;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.ast.*;
import com.alibaba.druid.sql.ast.expr.*;
import com.alibaba.druid.sql.ast.statement.*;

import com.laotie.analysis.model.ScalarSubquery;
import com.laotie.analysis.model.SourceNode;
import com.laotie.analysis.model.StarOutput;
import com.laotie.analysis.model.SourceTable;
import com.laotie.exception.ColumnNotFoundException;
import com.laotie.identifier.IdentifierCase;
import com.laotie.identifier.Identifiers;
import com.laotie.parse.ScriptSplitter;
import com.laotie.model.Instruction;
import com.laotie.model.Instruction.OperationType;
import com.laotie.model.metadata.Column;
import com.laotie.model.metadata.MetadataCatalog;
import com.laotie.model.metadata.Table;
import com.laotie.model.metadata.TableLocation;

/** INSERT/REPLACE/UPDATE/DELETE/MERGE/TRUNCATE statement analysis. */
final class DmlStatementAnalyzer {

    private final DmlLineage host;

    DmlStatementAnalyzer(DmlLineage host) {
        this.host = host;
    }

    void processInsert(SQLInsertStatement insert) {
        annotateAnsiInsertColumns(insert);
        processInsertInto(insert.getTableSource(), insert.getColumns(),
                insert.getQuery(), insert.getWith(), insert.getPartitions());
    }

    /**
     * Druid's Hive/Spark lexer parses every double-quoted INSERT target as a text
     * literal. In Spark ANSI identifier mode those are identifiers; recover the
     * original quote delimiter from the statement text.
     */
    void annotateAnsiInsertColumns(SQLInsertStatement insert) {
        if (!host.ansiEnabled() || !host.ansiDoubleQuotedIdentifiers()
                || host.statementSql() == null || insert.getColumns().isEmpty()) {
            return;
        }
        List<Boolean> styles = insertColumnQuoteStyles(host.statementSql());
        if (styles.size() != insert.getColumns().size()) {
            return;
        }
        for (int i = 0; i < styles.size(); i++) {
            SQLExpr column = insert.getColumns().get(i);
            if (styles.get(i) && column instanceof SQLTextLiteralExpr) {
                column.putAttribute(DmlLineage.ANSI_IDENTIFIER_ATTRIBUTE, Boolean.TRUE);
            }
        }
    }

    /** True for a double-quoted element in the INSERT column list. */
    static List<Boolean> insertColumnQuoteStyles(String sql) {
        List<Boolean> styles = new ArrayList<>();
        int depth = 0;
        int groupStart = -1;
        int lastStart = -1;
        int lastEnd = -1;
        boolean seenInsert = false;
        boolean expectPartition = false;
        boolean groupIsPartition = false;
        boolean sawIntoOrTable = false;
        boolean tableNameSeen = false;
        boolean nextNameIsTablePart = false;
        boolean atInsertStart = false;
        char quote = 0;
        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : 0;
            if (quote != 0) {
                if (ch == '\\' && quote == '\'') {
                    i++;
                } else if (ch == quote) {
                    if (next == quote) {
                        i++;
                    } else {
                        quote = 0;
                    }
                }
                continue;
            }
            if (ch == '-' && next == '-') {
                int end = sql.indexOf('\n', i);
                if (end < 0) {
                    break;
                }
                i = end;
                continue;
            }
            if (ch == '/' && next == '*') {
                int end = sql.indexOf("*/", i + 2);
                if (end < 0) {
                    break;
                }
                i = end + 1;
                continue;
            }
            if (ch == '#') {
                int end = sql.indexOf('\n', i);
                if (end < 0) {
                    break;
                }
                i = end;
                continue;
            }
            if (ch == '\'' || ch == '"' || ch == '`') {
                if (seenInsert && depth == 0) {
                    if (!tableNameSeen) {
                        tableNameSeen = true;
                    }
                    nextNameIsTablePart = false;
                }
                quote = ch;
                continue;
            }
            if (ch == ';') {
                break;
            }
            if (ch == '(') {
                if (depth == 0 && seenInsert) {
                    groupIsPartition = expectPartition;
                    expectPartition = false;
                    groupStart = groupIsPartition ? -1 : i;
                }
                depth++;
            } else if (ch == ')') {
                depth--;
                if (depth == 0 && seenInsert && groupStart >= 0) {
                    if (isQueryGroup(sql, groupStart + 1, i)) {
                        break;
                    }
                    lastStart = groupStart;
                    lastEnd = i;
                }
            } else if (depth == 0 && ch == '.' && tableNameSeen) {
                nextNameIsTablePart = true;
            } else if (depth == 0 && Character.isDigit(ch)
                    && (i == 0 || !ScriptSplitter.isIdentifierChar(sql.charAt(i - 1)))) {
                int end = i + 1;
                while (end < sql.length()
                        && ScriptSplitter.isIdentifierChar(sql.charAt(end))) {
                    end++;
                }
                if (seenInsert && !tableNameSeen) {
                    tableNameSeen = true;
                    atInsertStart = false;
                }
                nextNameIsTablePart = false;
                i = end - 1;
            } else if (depth == 0 && (ch == '_' || ch == '$')
                    && (i == 0 || !ScriptSplitter.isIdentifierChar(sql.charAt(i - 1)))) {
                int end = i + 1;
                while (end < sql.length()
                        && ScriptSplitter.isIdentifierChar(sql.charAt(end))) {
                    end++;
                }
                if (seenInsert && !tableNameSeen) {
                    tableNameSeen = true;
                    atInsertStart = false;
                }
                nextNameIsTablePart = false;
                i = end - 1;
            } else if (depth == 0 && Character.isLetter(ch)
                    && (i == 0 || !ScriptSplitter.isIdentifierChar(sql.charAt(i - 1)))) {
                int end = i + 1;
                while (end < sql.length()
                        && ScriptSplitter.isIdentifierChar(sql.charAt(end))) {
                    end++;
                }
                String word = sql.substring(i, end);
                if (!seenInsert) {
                    if ("INSERT".equalsIgnoreCase(word)) {
                        seenInsert = true;
                        atInsertStart = true;
                    }
                } else if (!tableNameSeen) {
                    if ("INTO".equalsIgnoreCase(word)
                            || "TABLE".equalsIgnoreCase(word)) {
                        sawIntoOrTable = true;
                        atInsertStart = false;
                    } else if (atInsertStart && ("OVERWRITE".equalsIgnoreCase(word)
                            || "IGNORE".equalsIgnoreCase(word))
                            || !sawIntoOrTable
                                    && "OVERWRITE".equalsIgnoreCase(word)) {
                        atInsertStart = false;
                    } else {
                        tableNameSeen = true;
                        nextNameIsTablePart = false;
                        atInsertStart = false;
                    }
                } else if (nextNameIsTablePart) {
                    nextNameIsTablePart = false;
                } else if ("PARTITION".equalsIgnoreCase(word)) {
                    expectPartition = true;
                } else if ("SELECT".equalsIgnoreCase(word)
                        || "VALUES".equalsIgnoreCase(word)
                        || "WITH".equalsIgnoreCase(word)) {
                    break;
                }
                i = end - 1;
            }
        }
        if (lastStart < 0) {
            return styles;
        }
        String list = sql.substring(lastStart + 1, lastEnd);
        int d = 0;
        char q = 0;
        StringBuilder element = new StringBuilder();
        for (int i = 0; i < list.length(); i++) {
            char ch = list.charAt(i);
            char next = i + 1 < list.length() ? list.charAt(i + 1) : 0;
            if (q == 0 && ch == '-' && next == '-') {
                int end = list.indexOf('\n', i);
                if (end < 0) {
                    break;
                }
                i = end;
                continue;
            }
            if (q == 0 && ch == '/' && next == '*') {
                int end = list.indexOf("*/", i + 2);
                if (end < 0) {
                    break;
                }
                i = end + 1;
                continue;
            }
            if (q == 0 && ch == '#') {
                int end = list.indexOf('\n', i);
                if (end < 0) {
                    break;
                }
                i = end;
                continue;
            }
            if (q != 0) {
                element.append(ch);
                if (ch == '\\' && q == '\'') {
                    if (i + 1 < list.length()) {
                        element.append(list.charAt(++i));
                    }
                } else if (ch == q) {
                    if (next == q) {
                        element.append(list.charAt(++i));
                    } else {
                        q = 0;
                    }
                }
                continue;
            }
            if (ch == '\'' || ch == '"' || ch == '`') {
                q = ch;
                element.append(ch);
            } else if (ch == '(') {
                d++;
                element.append(ch);
            } else if (ch == ')') {
                d--;
                element.append(ch);
            } else if (ch == ',' && d == 0) {
                styles.add(isDoubleQuotedElement(element.toString()));
                element.setLength(0);
            } else {
                element.append(ch);
            }
        }
        styles.add(isDoubleQuotedElement(element.toString()));
        return styles;
    }

    static boolean isQueryGroup(String sql, int start, int end) {
        int i = start;
        while (i < end) {
            char ch = sql.charAt(i);
            char next = i + 1 < end ? sql.charAt(i + 1) : 0;
            if (Character.isWhitespace(ch)) {
                i++;
            } else if (ch == '-' && next == '-') {
                int newline = sql.indexOf('\n', i);
                if (newline < 0 || newline >= end) {
                    return false;
                }
                i = newline + 1;
            } else if (ch == '/' && next == '*') {
                int close = sql.indexOf("*/", i + 2);
                if (close < 0 || close >= end) {
                    return false;
                }
                i = close + 2;
            } else if (ch == '#') {
                int newline = sql.indexOf('\n', i);
                if (newline < 0 || newline >= end) {
                    return false;
                }
                i = newline + 1;
            } else if (ch == '(') {
                // A parenthesized source query may be nested.
                i++;
            } else if (Character.isLetter(ch)) {
                int stop = i + 1;
                while (stop < end && ScriptSplitter.isIdentifierChar(sql.charAt(stop))) {
                    stop++;
                }
                String word = sql.substring(i, stop);
                return "SELECT".equalsIgnoreCase(word)
                        || "VALUES".equalsIgnoreCase(word)
                        || "WITH".equalsIgnoreCase(word);
            } else {
                return false;
            }
        }
        return false;
    }

    static boolean isDoubleQuotedElement(String element) {
        String value = element.trim();
        return value.startsWith("\"") && value.endsWith("\"")
                && value.length() >= 2;
    }

    void processReplace(SQLReplaceStatement replace) {
        SQLQueryExpr query = replace.getQuery();
        if (query != null) {
            processInsertInto(replace.getTableSource(), replace.getColumns(),
                    query.getSubQuery(), null, null);
            return;
        }
        // REPLACE INTO ... VALUES: the written values are literals or expressions.
        processValuesInto(replace.getTableSource(), replace.getColumns(),
                replace.getValuesList());
    }

    /**
     * Write a VALUES list into a target table. Literal values carry no lineage, but
     * an expression (for example a scalar subquery) does.
     */
    void processValuesInto(SQLExprTableSource tableSource,
            List<SQLExpr> insertColumns,
            List<SQLInsertStatement.ValuesClause> valueClauses) {
        String rawName = host.extractTableName(tableSource);
        String written = host.normalizeQualifiedName(rawName);
        String tableName = host.detachedMetadata() && host.analysisContext() == null && rawName != null
                ? host.sessionPhysicalReference(rawName) : written;
        Table targetMetadata = null;
        if (host.analysisContext() != null && host.findCteTarget(host.identifierKey(rawName)) == null) {
            TableLocation location = host.resolveMetadataTable(rawName);
            if (location != null) {
                tableName = location.getDisplayName();
                targetMetadata = location.getTable();
            } else if (host.detachedMetadata() && rawName != null) {
                tableName = host.sessionPhysicalReference(rawName);
            }
        }
        host.registerTable(written, tableName, tableName, tableSource.getAlias() == null
                ? null : MetadataCatalog.normalizeIdentifier(tableSource.getAlias()),
                host.findCteTarget(host.identifierKey(rawName)) != null);
        if (valueClauses == null || valueClauses.isEmpty()) {
            return;
        }
        List<String> columnNames = new ArrayList<>();
        if (insertColumns != null && !insertColumns.isEmpty()) {
            for (SQLExpr column : insertColumns) {
                columnNames.add(host.renderInsertColumn(targetMetadata, tableName, column));
            }
        } else if (targetMetadata != null) {
            for (Column column : targetMetadata.getFields()) {
                columnNames.add(renderMetadataColumn(column));
            }
        }
        for (SQLInsertStatement.ValuesClause clause : valueClauses) {
            List<SQLExpr> values = clause.getValues();
            for (int i = 0; i < values.size(); i++) {
                SQLExpr value = values.get(i);
                if (!host.containsScalarSubquery(value)) {
                    // A literal has no source, so the target column keeps none.
                    continue;
                }
                if (i >= columnNames.size()) {
                    break;
                }
                String column = columnNames.get(i);
                recordValueScalarMappings(value, tableName, column);
            }
        }
    }

    /**
     * Record the sources of every scalar subquery inside a written value.
     */
    void recordValueScalarMappings(SQLExpr value, String tableName,
            String column) {
        if (value == null) {
            return;
        }
        if (value instanceof SQLQueryExpr) {
            ScalarSubquery inner = host.analyzeScalarSubquery((SQLQueryExpr) value);
            if (inner == null) {
                return;
            }
            if (inner.columns.isEmpty()) {
                host.recordScalarTableLevel(inner, tableName, -1, column);
                return;
            }
            host.instructions().add(new Instruction(OperationType.COLUMN_MAPPING,
                    inner.references.get(0),
                    tableName + "." + column, -1, true, false));
            return;
        }
        if (value instanceof SQLBinaryOpExpr) {
            SQLBinaryOpExpr binary = (SQLBinaryOpExpr) value;
            recordValueScalarMappings(binary.getLeft(), tableName, column);
            recordValueScalarMappings(binary.getRight(), tableName, column);
        } else if (value instanceof SQLMethodInvokeExpr) {
            for (SQLExpr argument : ((SQLMethodInvokeExpr) value).getArguments()) {
                recordValueScalarMappings(argument, tableName, column);
            }
        } else if (value instanceof SQLCastExpr) {
            recordValueScalarMappings(((SQLCastExpr) value).getExpr(), tableName,
                    column);
        } else if (value instanceof SQLNotExpr) {
            recordValueScalarMappings(((SQLNotExpr) value).getExpr(), tableName,
                    column);
        } else if (value instanceof SQLUnaryExpr) {
            recordValueScalarMappings(((SQLUnaryExpr) value).getExpr(), tableName,
                    column);
        } else if (value instanceof SQLBetweenExpr) {
            SQLBetweenExpr between = (SQLBetweenExpr) value;
            recordValueScalarMappings(between.getTestExpr(), tableName, column);
            recordValueScalarMappings(between.getBeginExpr(), tableName, column);
            recordValueScalarMappings(between.getEndExpr(), tableName, column);
        } else if (value instanceof SQLCaseExpr) {
            SQLCaseExpr caseExpr = (SQLCaseExpr) value;
            recordValueScalarMappings(caseExpr.getValueExpr(), tableName, column);
            if (caseExpr.getItems() != null) {
                for (SQLCaseExpr.Item caseItem : caseExpr.getItems()) {
                    recordValueScalarMappings(caseItem.getConditionExpr(), tableName,
                            column);
                    recordValueScalarMappings(caseItem.getValueExpr(), tableName,
                            column);
                }
            }
            recordValueScalarMappings(caseExpr.getElseExpr(), tableName, column);
        } else if (value instanceof SQLListExpr) {
            for (SQLExpr element : ((SQLListExpr) value).getItems()) {
                recordValueScalarMappings(element, tableName, column);
            }
        } else if (value instanceof SQLInListExpr) {
            SQLInListExpr in = (SQLInListExpr) value;
            recordValueScalarMappings(in.getExpr(), tableName, column);
            if (in.getTargetList() != null) {
                for (SQLExpr element : in.getTargetList()) {
                    recordValueScalarMappings(element, tableName, column);
                }
            }
        } else if (value instanceof SQLInSubQueryExpr) {
            recordValueScalarMappings(((SQLInSubQueryExpr) value).getExpr(), tableName,
                    column);
        } else if (value instanceof SQLExtractExpr) {
            recordValueScalarMappings(((SQLExtractExpr) value).getValue(), tableName,
                    column);
        }
    }

    void processTruncate(SQLTruncateStatement truncate) {
        // A truncate removes every row, so it carries no column lineage.
        if (truncate.getTableSources() == null) {
            return;
        }
        for (SQLExprTableSource tableSource : truncate.getTableSources()) {
            String rawName = host.extractTableName(tableSource);
            if (rawName == null) {
                continue;
            }
            host.registerTable(host.normalizeQualifiedName(rawName),
                    host.normalizeQualifiedName(rawName),
                    host.normalizeQualifiedName(rawName), null, false);
        }
    }

    void pushClauseScope(List<SourceTable> sources,
            Map<String, List<SourceTable>> using,
            Map<String, SourceNode> bindings, Set<SourceTable> hidden,
            List<StarOutput> plan) {
        host.state().stackSources.push(sources);
        host.state().lateralScopes.push(Boolean.FALSE);
        host.state().stackUsing.push(using);
        host.state().stackStarPlan.push(plan == null ? new ArrayList<StarOutput>()
                : new ArrayList<>(plan));
        host.state().stackHiddenSources.push(new HashSet<>(hidden));
        host.state().stackJoinBindings.push(new HashMap<>(bindings));
    }

    void popClauseScope() {
        host.state().stackJoinBindings.pop();
        host.state().stackHiddenSources.pop();
        host.state().stackStarPlan.pop();
        host.state().stackUsing.pop();
        host.state().lateralScopes.pop();
        host.state().stackSources.pop();
    }

    void processMerge(SQLMergeStatement merge) {
        SQLTableSource into = merge.getInto();
        if (!(into instanceof SQLExprTableSource)) {
            return;
        }
        SQLExprTableSource target = (SQLExprTableSource) into;
        String rawName = host.extractTableName(target);
        String written = host.normalizeQualifiedName(rawName);
        String tableName = written;
        TableLocation targetLocation = null;
        if (host.analysisContext() != null) {
            targetLocation = host.resolveMetadataTable(rawName);
        }
        if (targetLocation != null
                && host.findCteTarget(host.identifierKey(rawName)) == null) {
            tableName = targetLocation.getDisplayName();
        }
        host.registerTable(written, tableName, tableName, null, false);

        Map<String, List<SourceTable>> using = new LinkedHashMap<>();
        List<SourceTable> targetSources = new ArrayList<>();
        targetSources.addAll(host.buildSourceNode(target, using).sources);
        List<SourceTable> sourceSources = new ArrayList<>();
        // The join bindings of the USING clause (for example its alias) stay visible
        // to both clauses.
        Map<String, SourceNode> usingBindings = new HashMap<>();
        Set<SourceTable> usingHidden = new HashSet<>();
        host.state().stackJoinBindings.push(usingBindings);
        host.state().stackHiddenSources.push(usingHidden);
        List<StarOutput> usingPlan = new ArrayList<>();
        try {
            if (merge.getUsing() instanceof SQLTableSource) {
                SourceNode usingNode = host.buildSourceNode(
                        (SQLTableSource) merge.getUsing(), using);
                sourceSources.addAll(usingNode.sources);
                usingPlan.addAll(usingNode.plan);
            }
        } finally {
            host.state().stackHiddenSources.pop();
            host.state().stackJoinBindings.pop();
        }
        Table targetTableMetadata = targetLocation == null ? null
                : targetLocation.getTable();
        host.state().stackTargetTable.push(tableName);
        host.state().stackSourceTable.push(tableName);
        host.state().inTargetExpression = true;
        try {
            SQLMergeStatement.MergeUpdateClause update = merge.getUpdateClause();
            if (update != null) {
                // The update expression reads the target and the source.
                List<SourceTable> scope = new ArrayList<>(targetSources);
                scope.addAll(sourceSources);
                pushClauseScope(scope, using, usingBindings, usingHidden, usingPlan);
                try {
                    for (SQLUpdateSetItem item : update.getItems()) {
                        host.state().stackTargetColumn.push(Identifiers.render(
                                mergeTargetColumn(targetTableMetadata, tableName,
                                        item.getColumn())));
                        host.state().currentOrdinal = -1;
                        host.visitExpr(item.getValue());
                        host.state().stackTargetColumn.pop();
                    }
                } finally {
                    popClauseScope();
                }
                host.collectTablesFromExpression(update.getWhere());
            }
            SQLMergeStatement.MergeInsertClause insert = merge.getInsertClause();
            if (insert != null && insert.getValues() != null) {
                // The inserted values come from the source only.
                List<SQLExpr> columns = insert.getColumns();
                List<SQLExpr> values = insert.getValues();
                boolean hasColumnList = columns != null && !columns.isEmpty();
                List<String> targetColumns = hasColumnList ? null
                        : mergeTargetColumns(targetTableMetadata, tableName,
                                values.size());
                pushClauseScope(new ArrayList<>(sourceSources), using, usingBindings,
                        usingHidden, usingPlan);
                try {
                    for (int i = 0; i < values.size(); i++) {
                        String column;
                        if (hasColumnList) {
                            if (i >= columns.size()) {
                                break;
                            }
                            column = mergeTargetColumn(targetTableMetadata, tableName,
                                    columns.get(i));
                        } else {
                            column = targetColumns.get(i);
                        }
                        host.state().stackTargetColumn.push(Identifiers.render(column));
                        host.state().currentOrdinal = -1;
                        host.visitExpr(values.get(i));
                        host.state().stackTargetColumn.pop();
                    }
                } finally {
                    popClauseScope();
                }
                host.collectTablesFromExpression(insert.getWhere());
            }
        } finally {
            host.state().inTargetExpression = false;
            host.state().stackSourceTable.pop();
            host.state().stackTargetTable.pop();
        }
        host.collectTablesFromExpression(merge.getOn());
    }

    String renderMetadataColumn(Column column) {
        if (!column.isQuoted()) {
            return Identifiers.render(column.getColumnName());
        }
        DbType dialect = host.currentDialect();
        if (dialect == DbType.mysql || dialect == DbType.mariadb) {
            return "`" + column.getColumnName().replace("`", "``") + "`";
        }
        return Identifiers.quoteDouble(column.getColumnName());
    }

    String mergeTargetColumn(Table metadata, String tableName, SQLExpr column) {
        String raw = host.rawColumnName(column);
        if (!MetadataCatalog.isQuotedIdentifier(raw)) {
            raw = host.lastIdentifier(raw);
        }
        if (metadata != null) {
            String resolved = MetadataCatalog.resolveColumnName(metadata, raw,
                    host.currentDialect());
            if (resolved == null) {
                throw new ColumnNotFoundException(MetadataCatalog.normalizeIdentifier(raw),
                        java.util.Collections.singletonList(tableName));
            }
            return resolved;
        }
        return MetadataCatalog.normalizeIdentifier(raw);
    }

    List<String> mergeTargetColumns(Table metadata, String tableName,
            int count) {
        if (metadata == null) {
            throw new ColumnNotFoundException("*",
                    java.util.Collections.singletonList(tableName));
        }
        List<String> columns = new ArrayList<>();
        for (Column column : metadata.getFields()) {
            columns.add(column.getColumnName());
        }
        if (columns.size() < count) {
            throw new ColumnNotFoundException("*",
                    java.util.Collections.singletonList(tableName));
        }
        return columns;
    }

    /**
     * Analyzes a write into {@code tableSource} and returns the temporary target the
     * projection was written to, so callers can register the written columns.
     */
    String processInsertInto(SQLExprTableSource tableSource,
            List<SQLExpr> insertColumns, SQLSelect select,
            SQLWithSubqueryClause with, List<SQLAssignItem> partitions) {
        String rawName = host.extractTableName(tableSource);
        String written = host.normalizeQualifiedName(rawName);
        // The graph identity of an unaliased target keeps its written reference when
        // metadata is not available, so later reads resolve to the same vertex.
        String tableName = host.detachedMetadata() && host.analysisContext() == null && rawName != null
                ? host.sessionPhysicalReference(rawName) : written;
        Table targetMetadata = null;
        if (host.analysisContext() != null && host.findCteTarget(host.identifierKey(rawName)) == null) {
            TableLocation location = host.resolveMetadataTable(rawName);
            if (location != null) {
                tableName = location.getDisplayName();
                targetMetadata = location.getTable();
            } else if (host.detachedMetadata() && rawName != null) {
                // The target table is missing from the catalog: keep its written
                // identity so the table-level edge matches later reads.
                tableName = host.sessionPhysicalReference(rawName);
            }
        }
        host.registerTable(written, tableName, tableName, tableSource.getAlias() == null
                ? null : MetadataCatalog.normalizeIdentifier(tableSource.getAlias()),
                host.findCteTarget(host.identifierKey(rawName)) != null);

        // Static partition columns receive no data; dynamic partition columns follow
        // the table's non-partition columns in partition-spec order. Other dialects use
        // PARTITION (...) as a physical partition selector, not a column list.
        DbType insertDialect = host.resolveDialect();
        String partitionTargets = insertDialect == DbType.hive
                || insertDialect == DbType.antspark
                        ? partitionTargetList(insertColumns, partitions,
                                targetMetadata, tableName)
                        : null;

        boolean scoped = with != null;
        String insertTemp;
        if (scoped) {
            host.state().cteScopes.push(new HashMap<String, String>());
        }
        try {
            if (with != null) {
                host.processWithSubQuery(with);
            }

            insertTemp = host.getTempTableName();
            host.state().stackTargetTable.push(insertTemp);
            host.state().intermediateTargets.add(insertTemp);

            if (select != null) {
                host.processSelect(select);
            }

            String fromTable = host.state().stackTargetTable.peek();
            boolean fromTemporary = host.state().tempTables.contains(fromTable);
            List<SQLExpr> columns = insertColumns;
            if (partitionTargets != null) {
                Instruction insertEdge = new Instruction(OperationType.COLUMNS_INJECT,
                        fromTable, String.format("%s(%s)", tableName, partitionTargets),
                        -1, fromTemporary, false);
                insertEdge.setWildcardTable(tableName);
                insertEdge.setPopulatedColumns(partitionTargets);
                host.instructions().add(insertEdge);
            } else if (columns != null && !columns.isEmpty()) {
                final Table insertMetadata = targetMetadata;
                final String insertTable = tableName;
                String colStr = columns.stream()
                        .map(column -> host.renderInsertColumn(insertMetadata, insertTable, column))
                        .collect(Collectors.joining(","));
                Instruction insertEdge = new Instruction(OperationType.COLUMNS_INJECT,
                        fromTable, String.format("%s(%s)", tableName, colStr), -1,
                        fromTemporary, false);
                insertEdge.setWildcardTable(tableName);
                insertEdge.setPopulatedColumns(colStr);
                host.instructions().add(insertEdge);
            } else {
                host.instructions().add(new Instruction(OperationType.COLUMNS_INJECT,
                        fromTable, tableName, -1, fromTemporary, false));
            }
            host.state().stackTargetTable.pop();
        } finally {
            if (scoped) {
                host.state().cteScopes.pop();
            }
        }
        return insertTemp;
    }

    void processCreateTableAs(SQLCreateTableStatement create) {
        processCreateAs(create.getTableSource(), create.getSelect(),
                create.getTableElementList());
    }

    void processCreateViewAs(SQLCreateViewStatement create) {
        processCreateAs(create.getTableSource(), create.getSubQuery(),
                create.getColumns());
    }

    private void processCreateAs(SQLExprTableSource tableSource, SQLSelect select,
            List<SQLTableElement> elements) {
        List<SQLExpr> declared = new ArrayList<>();
        List<SQLColumnDefinition> definitions = new ArrayList<>();
        for (SQLTableElement element : elements) {
            if (element instanceof SQLColumnDefinition) {
                SQLColumnDefinition definition = (SQLColumnDefinition) element;
                declared.add(new SQLIdentifierExpr(definition.getColumnName()));
                definitions.add(definition);
            }
        }
        host.prepareCreatedTable(tableSource, definitions);
        String temp = processInsertInto(tableSource, declared, select, null, null);
        host.completeCreatedTable(tableSource, definitions, temp);
    }

    /**
     * Compare partition names with catalog fields by name, not by SQL quotation.
     * Hive/Spark partition matching is case-insensitive and only the outer
     * backtick/double-quote layer is syntactic.
     */
    static String partitionKey(String raw) {
        return foldCase(hiveLogicalName(raw));
    }

    static String partitionColumnKey(String columnName) {
        return foldCase(columnName);
    }

    /**
     * Case fold matching {@link String#equalsIgnoreCase}: per-character
     * upper-then-lower, independent of the JVM locale.
     */
    static String foldCase(String value) {
        return IdentifierCase.fold(value);
    }

    /** Strip one Hive SQL identifier layer, keeping inner quote characters. */
    /** Fold an already-decoded Hive/Spark planned column name. */
    static String hivePlannedColumn(String column) {
        return foldCase(column);
    }

    static String hiveColumnIdentity(String raw) {
        return foldCase(hiveLogicalName(raw));
    }

    static String hiveLogicalName(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.length() >= 2 && value.charAt(0) == '`'
                && value.charAt(value.length() - 1) == '`') {
            return value.substring(1, value.length() - 1).replace("``", "`");
        }
        return MetadataCatalog.normalizeIdentifier(value);
    }

    static String catalogSpelling(Table metadata, String raw) {
        String logical = hiveLogicalName(raw);
        String key = foldCase(logical);
        for (Column column : metadata.getFields()) {
            if (foldCase(column.getColumnName()).equals(key)) {
                return column.getColumnName();
            }
        }
        return logical;
    }

    static String renderHiveIdentifier(String name) {
        if (name == null || name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return name;
        }
        return "`" + name.replace("`", "``") + "`";
    }

    String partitionTargetList(List<SQLExpr> insertColumns,
            List<SQLAssignItem> partitions, Table targetMetadata, String tableName) {
        if (targetMetadata == null || partitions == null || partitions.isEmpty()) {
            return null;
        }
        Set<String> partitionKeys = new HashSet<>();
        List<String> dynamic = new ArrayList<>();
        for (SQLAssignItem partition : partitions) {
            String name = partition.getTarget() == null ? null
                    : partition.getTarget().toString();
            if (name == null) {
                continue;
            }
            partitionKeys.add(partitionKey(name));
            if (partition.getValue() == null) {
                dynamic.add(name);
            }
        }
        if (partitionKeys.isEmpty()) {
            return null;
        }
        Set<String> dynamicKeys = new HashSet<>();
        for (String name : dynamic) {
            dynamicKeys.add(partitionKey(name));
        }
        List<String> targets = new ArrayList<>();
        if (insertColumns != null && !insertColumns.isEmpty()) {
            for (SQLExpr column : insertColumns) {
                targets.add(host.renderInsertColumn(targetMetadata, tableName, column));
            }
        } else {
            // Follow the table's own column order; a reordered PARTITION clause
            // does not reorder the written data.
            for (Column column : targetMetadata.getFields()) {
                String key = partitionColumnKey(column.getColumnName());
                if (!partitionKeys.contains(key)
                        || dynamicKeys.contains(key)) {
                    targets.add(renderHiveIdentifier(column.getColumnName()));
                }
            }
        }
        Set<String> present = new HashSet<>();
        for (String target : targets) {
            present.add(partitionKey(target));
        }
        for (String name : dynamic) {
            String key = partitionKey(name);
            if (present.add(key)) {
                targets.add(renderHiveIdentifier(
                        catalogSpelling(targetMetadata, name)));
            }
        }
        return targets.isEmpty() ? null : String.join(",", targets);
    }

    void processUpdate(SQLUpdateStatement update) {
        Map<String, List<SourceTable>> using = new LinkedHashMap<>();
        List<SourceTable> sources = new ArrayList<>();
        List<StarOutput> plan = new ArrayList<>();
        host.state().stackJoinBindings.push(new HashMap<String, SourceNode>());
        host.state().stackHiddenSources.push(new HashSet<SourceTable>());
        try {
            if (update.getTableSource() != null) {
                SourceNode node = host.buildSourceNode(update.getTableSource(), using);
                sources.addAll(node.sources);
                plan.addAll(node.plan);
            }
            if (update.getFrom() != null) {
                SourceNode node = host.buildSourceNode(update.getFrom(), using);
                sources.addAll(node.sources);
                plan.addAll(node.plan);
            }
            if (sources.isEmpty()) {
                return;
            }

            String target = sources.get(0).displayName;
            host.state().stackTargetTable.push(target);
            host.state().stackSources.push(sources);
            host.state().lateralScopes.push(Boolean.FALSE);
            host.state().stackUsing.push(using);
            host.state().stackStarPlan.push(plan);
            host.state().stackSourceTable.push(target);
            host.state().inTargetExpression = true;
            try {
                for (SQLUpdateSetItem item : update.getItems()) {
                    String targetColumn = host.canonicalColumnName(target,
                            host.rawColumnName(item.getColumn()));
                    host.state().stackTargetColumn.push(Identifiers.render(targetColumn));
                    host.state().currentOrdinal = -1;
                    host.visitExpr(item.getValue());
                    host.state().stackTargetColumn.pop();
                }
            } finally {
                host.state().inTargetExpression = false;
                host.state().stackSourceTable.pop();
                host.state().stackStarPlan.pop();
                host.state().stackUsing.pop();
                host.state().lateralScopes.pop();
                host.state().stackSources.pop();
                host.state().stackTargetTable.pop();
            }
        } finally {
            host.state().stackHiddenSources.pop();
            host.state().stackJoinBindings.pop();
        }
        host.collectTablesFromExpression(update.getWhere());
    }

    void processDelete(SQLDeleteStatement delete) {
        Map<String, List<SourceTable>> using = new LinkedHashMap<>();
        List<SourceTable> sources = new ArrayList<>();
        if (delete.getTableSource() != null) {
            sources.addAll(host.buildSourceNode(delete.getTableSource(), using).sources);
        }
        if (delete.getFrom() != null) {
            sources.addAll(host.buildSourceNode(delete.getFrom(), using).sources);
        }
        host.collectTablesFromExpression(delete.getWhere());
    }

}

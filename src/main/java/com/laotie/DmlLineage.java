/*-
 * Author: Laotie
 * Licensed under Apache License 2.0
 * #L%
 */
package com.laotie;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.stream.Collectors;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.expr.SQLAllColumnExpr;
import com.alibaba.druid.sql.ast.expr.SQLBetweenExpr;
import com.alibaba.druid.sql.ast.expr.SQLBinaryOpExpr;
import com.alibaba.druid.sql.ast.expr.SQLCaseExpr;
import com.alibaba.druid.sql.ast.expr.SQLCastExpr;
import com.alibaba.druid.sql.ast.expr.SQLExtractExpr;
import com.alibaba.druid.sql.ast.expr.SQLExistsExpr;
import com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.druid.sql.ast.expr.SQLInListExpr;
import com.alibaba.druid.sql.ast.expr.SQLInSubQueryExpr;
import com.alibaba.druid.sql.ast.expr.SQLListExpr;
import com.alibaba.druid.sql.ast.expr.SQLMethodInvokeExpr;
import com.alibaba.druid.sql.ast.expr.SQLNotExpr;
import com.alibaba.druid.sql.ast.expr.SQLPropertyExpr;
import com.alibaba.druid.sql.ast.expr.SQLQueryExpr;
import com.alibaba.druid.sql.ast.expr.SQLUnaryExpr;
import com.alibaba.druid.sql.ast.statement.SQLAssignItem;
import com.alibaba.druid.sql.ast.statement.SQLCreateTableStatement;
import com.alibaba.druid.sql.ast.statement.SQLDeleteStatement;
import com.alibaba.druid.sql.ast.statement.SQLExprTableSource;
import com.alibaba.druid.sql.ast.statement.SQLInsertStatement;
import com.alibaba.druid.sql.ast.statement.SQLJoinTableSource;
import com.alibaba.druid.sql.ast.statement.SQLSelect;
import com.alibaba.druid.sql.ast.statement.SQLSelectItem;
import com.alibaba.druid.sql.ast.statement.SQLSelectQuery;
import com.alibaba.druid.sql.ast.statement.SQLSelectQueryBlock;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.druid.sql.ast.statement.SQLSetStatement;
import com.alibaba.druid.sql.ast.statement.SQLSubqueryTableSource;
import com.alibaba.druid.sql.ast.statement.SQLTableSource;
import com.alibaba.druid.sql.ast.statement.SQLUnionQuery;
import com.alibaba.druid.sql.ast.statement.SQLUnionQueryTableSource;
import com.alibaba.druid.sql.ast.statement.SQLUpdateSetItem;
import com.alibaba.druid.sql.ast.statement.SQLUpdateStatement;
import com.alibaba.druid.sql.ast.statement.SQLUseStatement;
import com.alibaba.druid.sql.ast.statement.SQLWithSubqueryClause;

import com.laotie.exception.AmbiguousColumnException;
import com.laotie.exception.AmbiguousTableException;
import com.laotie.exception.ColumnNotFoundException;
import com.laotie.exception.MetadataNotFoundException;
import com.laotie.model.Instruction;
import com.laotie.model.Instruction.OperationType;
import com.laotie.model.LineageResult;
import com.laotie.model.metadata.Column;
import com.laotie.model.metadata.MetadataCatalog;
import com.laotie.model.metadata.MetadataContext;
import com.laotie.model.metadata.MetadataContextHolder;
import com.laotie.model.metadata.Table;
import com.laotie.model.metadata.TableLocation;

/**
 * Extract table and column level lineage from SQL statements.
 */
@SuppressWarnings({"PMD.CyclomaticComplexity", "PMD.UncommentedEmptyMethodBody"})
public class DmlLineage {

    private Set<String> tables;
    private List<Instruction> instructions;
    private Stack<String> stackTargetTable;
    private Stack<String> stackSourceTable;
    private Stack<String> stackTargetColumn;
    private Stack<List<SourceTable>> stackSources;
    private Stack<Map<String, List<SourceTable>>> stackUsing;

    private boolean inTargetExpression = false;
    private int tempTableNum = 0;
    private int currentOrdinal = -1;

    private List<String> otherItemNames;

    private final DbType dbType;
    private final boolean explicitDialect;
    private final MetadataContext explicitContext;

    private MetadataContext context;

    public DmlLineage() {
        this(DbType.mysql, false, null);
    }

    public DmlLineage(DbType dbType) {
        this(dbType, true, null);
    }

    public DmlLineage(MetadataContext context) {
        this(DbType.mysql, false, context);
    }

    private DmlLineage(DbType dbType, boolean explicitDialect, MetadataContext context) {
        this.dbType = dbType == null ? DbType.mysql : dbType;
        this.explicitDialect = explicitDialect;
        this.explicitContext = context;
    }

    /* ------------------------------------------------------------------ */
    /* public API                                                         */
    /* ------------------------------------------------------------------ */

    public List<Instruction> getLineage(SQLStatement statement) {
        MetadataContext previous = this.context;
        this.context = effectiveContext();
        try {
            init(true);
            stackTargetTable.push(getTempTableName());
            processStatement(statement);
            stackTargetTable.pop();
            return instructions;
        } finally {
            this.context = previous;
        }
    }

    public List<Instruction> getLineage(String sqlStr) {
        return getLineage(parseStatement(sqlStr));
    }

    public SQLStatement parseStatement(String sqlStr) {
        return SQLUtils.parseSingleStatement(sqlStr, resolveDialect());
    }

    public LineageResult parse(String sqlStr) {
        List<LineageResult> results = parseScript(sqlStr);
        if (results.isEmpty()) {
            return new LineageResult(sqlStr, new ArrayList<Instruction>(), effectiveContext(),
                    null, null, null, new ArrayList<String>());
        }
        LineageResult result = results.get(0);
        if (result.getError() != null) {
            Throwable error = result.getError();
            if (error instanceof RuntimeException) {
                throw (RuntimeException) error;
            }
            throw new RuntimeException(error);
        }
        return result;
    }

    public List<LineageResult> parseScript(String sqlStr) {
        MetadataContext base = effectiveContext();
        DbType dialect = resolveDialect();
        SessionState session = new SessionState(base);
        List<LineageResult> results = new ArrayList<>();

        MetadataContext previous = this.context;
        this.context = base;
        try {
            for (String sql : splitStatements(sqlStr)) {
                results.add(parseOne(sql, dialect, base, session));
            }
        } finally {
            this.context = previous;
        }
        return results;
    }

    public Set<String> getTables() {
        return tables;
    }

    /* ------------------------------------------------------------------ */
    /* script / session                                                   */
    /* ------------------------------------------------------------------ */

    private LineageResult parseOne(String sql, DbType dialect, MetadataContext base,
            SessionState session) {
        SQLStatement statement;
        try {
            statement = SQLUtils.parseSingleStatement(sql, dialect);
        } catch (Throwable t) {
            return failed(sql, base, session, t);
        }

        if (statement instanceof SQLUseStatement) {
            applyUse((SQLUseStatement) statement, session);
            return sessionResult(sql, base, session);
        }
        if (statement instanceof SQLSetStatement) {
            applySet((SQLSetStatement) statement, session);
            return sessionResult(sql, base, session);
        }

        MetadataContext working = session.toContext(base);
        MetadataContext previous = this.context;
        this.context = working;
        this.tempTableNum = 0;
        try {
            init(true);
            stackTargetTable.push(getTempTableName());
            processStatement(statement);
            stackTargetTable.pop();
            return new LineageResult(sql, instructions, working, null, session.instance,
                    session.database, session.schemas);
        } catch (Throwable t) {
            return failed(sql, base, session, t);
        } finally {
            this.context = previous;
        }
    }

    private LineageResult sessionResult(String sql, MetadataContext base, SessionState session) {
        return new LineageResult(sql, new ArrayList<Instruction>(), session.toContext(base),
                null, session.instance, session.database, session.schemas);
    }

    private LineageResult failed(String sql, MetadataContext base, SessionState session,
            Throwable error) {
        return new LineageResult(sql, new ArrayList<Instruction>(), session.toContext(base),
                error, session.instance, session.database, session.schemas);
    }

    private void applyUse(SQLUseStatement use, SessionState session) {
        if (use.getDatabase() != null) {
            session.database = normalizeIdentifier(use.getDatabase().toString());
        }
    }

    private void applySet(SQLSetStatement set, SessionState session) {
        for (SQLAssignItem item : set.getItems()) {
            String target = item.getTarget() == null ? ""
                    : item.getTarget().toString().trim().toLowerCase();
            SQLExpr value = item.getValue();
            if (value == null) {
                continue;
            }
            if (target.endsWith("search_path")) {
                session.schemas = stringList(value);
            } else if (target.contains("schema")) {
                session.schemas = new ArrayList<>();
                session.schemas.add(normalizeIdentifier(value.toString()));
            }
        }
    }

    private static List<String> stringList(SQLExpr value) {
        List<String> values = new ArrayList<>();
        if (value instanceof SQLListExpr) {
            for (SQLExpr item : ((SQLListExpr) value).getItems()) {
                values.add(normalizeIdentifier(item.toString()));
            }
        } else {
            values.add(normalizeIdentifier(value.toString()));
        }
        return values;
    }

    static List<String> splitStatements(String sql) {
        List<String> statements = new ArrayList<>();
        if (sql == null) {
            return statements;
        }
        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean lineComment = false;
        boolean blockComment = false;

        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : 0;

            if (lineComment) {
                current.append(ch);
                if (ch == '\n') {
                    lineComment = false;
                }
                continue;
            }
            if (blockComment) {
                current.append(ch);
                if (ch == '*' && next == '/') {
                    current.append(next);
                    i++;
                    blockComment = false;
                }
                continue;
            }
            if (quote != 0) {
                current.append(ch);
                if (ch == quote) {
                    if (next == quote) {
                        current.append(next);
                        i++;
                    } else {
                        quote = 0;
                    }
                }
                continue;
            }
            if (ch == '\'' || ch == '"' || ch == '`') {
                quote = ch;
                current.append(ch);
            } else if (ch == '-' && next == '-') {
                lineComment = true;
                current.append(ch);
            } else if (ch == '/' && next == '*') {
                blockComment = true;
                current.append(ch);
            } else if (ch == ';') {
                addStatement(statements, current);
            } else {
                current.append(ch);
            }
        }
        addStatement(statements, current);
        return statements;
    }

    private static void addStatement(List<String> statements, StringBuilder current) {
        String statement = current.toString().trim();
        current.setLength(0);
        if (!statement.isEmpty()) {
            statements.add(statement);
        }
    }

    private DbType resolveDialect() {
        if (explicitContext != null) {
            return explicitContext.getCatalog().getDialect();
        }
        if (explicitDialect) {
            return dbType;
        }
        MetadataContext threadLocal = MetadataContextHolder.current();
        if (threadLocal != null) {
            return threadLocal.getCatalog().getDialect();
        }
        return dbType;
    }

    private MetadataContext effectiveContext() {
        if (explicitContext != null) {
            return explicitContext;
        }
        return MetadataContextHolder.current();
    }

    /* ------------------------------------------------------------------ */
    /* statement dispatch                                                 */
    /* ------------------------------------------------------------------ */

    private void processStatement(SQLStatement statement) {
        if (statement instanceof SQLSelectStatement) {
            processSelect(((SQLSelectStatement) statement).getSelect());
        } else if (statement instanceof SQLInsertStatement) {
            processInsert((SQLInsertStatement) statement);
        } else if (statement instanceof SQLUpdateStatement) {
            processUpdate((SQLUpdateStatement) statement);
        } else if (statement instanceof SQLDeleteStatement) {
            processDelete((SQLDeleteStatement) statement);
        } else if (statement instanceof SQLCreateTableStatement) {
            processCreateTable((SQLCreateTableStatement) statement);
        } else {
            throw new UnsupportedOperationException(
                    String.format("Finding tables from %s is not supported",
                            statement.getClass().getSimpleName()));
        }
    }

    private void processSelect(SQLSelect select) {
        processWithSubQuery(select.getWithSubQuery());
        processSelectQuery(select.getQuery());
    }

    private void processWithSubQuery(SQLWithSubqueryClause withSubQuery) {
        if (withSubQuery == null) {
            return;
        }
        for (SQLWithSubqueryClause.Entry entry : withSubQuery.getEntries()) {
            String alias = entry.getAlias();
            if (alias != null) {
                otherItemNames.add(alias.toLowerCase());
            }
            stackTargetTable.push(alias != null ? alias : getTempTableName());
            processSelect(entry.getSubQuery());
            stackTargetTable.pop();
        }
    }

    private void processSelectQuery(SQLSelectQuery query) {
        if (query instanceof SQLSelectQueryBlock) {
            processQueryBlock((SQLSelectQueryBlock) query);
        } else if (query instanceof SQLUnionQuery) {
            SQLUnionQuery union = (SQLUnionQuery) query;
            processSelectQuery(union.getLeft());
            processSelectQuery(union.getRight());
        }
    }

    private void processQueryBlock(SQLSelectQueryBlock queryBlock) {
        SQLTableSource fromItem = queryBlock.getFrom();
        if (fromItem == null) {
            return;
        }

        Map<String, List<SourceTable>> using = new HashMap<>();
        List<SourceTable> sources = collectSources(fromItem, using);
        if (sources.isEmpty()) {
            return;
        }

        collectTablesFromExpression(queryBlock.getWhere());

        stackSources.push(sources);
        stackUsing.push(using);
        stackSourceTable.push(sources.get(0).displayName);

        int position = 0;
        for (SQLSelectItem item : queryBlock.getSelectList()) {
            currentOrdinal = position;
            position += processSelectItem(item);
        }
        currentOrdinal = -1;

        stackSourceTable.pop();
        stackUsing.pop();
        stackSources.pop();
    }

    private int processSelectItem(SQLSelectItem item) {
        if (item.getExpr() == null) {
            return 0;
        }
        String targetColumn = item.getAlias() != null
                ? normalizeIdentifier(item.getAlias())
                : targetColumnName(item.getExpr());

        stackTargetColumn.push(targetColumn);
        int before = instructions.size();
        inTargetExpression = true;
        visitExpr(item.getExpr());
        inTargetExpression = false;
        if (instructions.size() == before) {
            instructions.add(new Instruction(OperationType.EXPR_MAPPING,
                    item.getExpr().toString(), stackTargetTable.peek() + "." + targetColumn,
                    currentOrdinal));
        }
        stackTargetColumn.pop();

        return outputColumnCount(item.getExpr());
    }

    private int outputColumnCount(SQLExpr expr) {
        if (expr instanceof SQLAllColumnExpr) {
            int count = 0;
            for (SourceTable source : currentSources()) {
                count += columnCount(source);
            }
            return Math.max(count, 1);
        }
        if (expr instanceof SQLPropertyExpr && "*".equals(((SQLPropertyExpr) expr).getName())) {
            SourceTable source = findByOwner(((SQLPropertyExpr) expr).getOwner());
            return source == null ? 1 : Math.max(columnCount(source), 1);
        }
        return 1;
    }

    private int columnCount(SourceTable source) {
        if (source.metadataTable != null) {
            return Math.max(source.metadataTable.getFields().size(), 1);
        }
        return 1;
    }

    /* ------------------------------------------------------------------ */
    /* sources                                                            */
    /* ------------------------------------------------------------------ */

    private List<SourceTable> collectSources(SQLTableSource tableSource,
            Map<String, List<SourceTable>> using) {
        List<SourceTable> sources = new ArrayList<>();
        if (tableSource instanceof SQLJoinTableSource) {
            SQLJoinTableSource join = (SQLJoinTableSource) tableSource;
            List<SourceTable> left = collectSources(join.getLeft(), using);
            List<SourceTable> right = collectSources(join.getRight(), using);
            if (join.getUsing() != null) {
                for (SQLExpr expr : join.getUsing()) {
                    String column = normalizeIdentifier(expr.toString()).toLowerCase();
                    using.put(column, mergedSources(join.getJoinType(), left, right));
                }
            }
            sources.addAll(left);
            sources.addAll(right);
            return sources;
        }
        if (tableSource instanceof SQLSubqueryTableSource) {
            SQLSubqueryTableSource subquery = (SQLSubqueryTableSource) tableSource;
            String target = subquery.getAlias();
            if (target == null) {
                target = getTempTableName();
                subquery.setAlias(target);
            }
            stackTargetTable.push(target);
            processSelect(subquery.getSelect());
            stackTargetTable.pop();
            SourceTable source = new SourceTable(target, null, null, null);
            sources.add(source);
            return sources;
        }
        if (tableSource instanceof SQLUnionQueryTableSource) {
            SQLUnionQueryTableSource union = (SQLUnionQueryTableSource) tableSource;
            String target = union.getAlias();
            if (target == null) {
                target = getTempTableName();
                union.setAlias(target);
            }
            stackTargetTable.push(target);
            processSelectQuery(union.getUnion());
            stackTargetTable.pop();
            sources.add(new SourceTable(target, null, null, null));
            return sources;
        }
        if (tableSource instanceof SQLExprTableSource) {
            SQLExprTableSource table = (SQLExprTableSource) tableSource;
            SourceTable source = resolveSource(table);
            sources.add(source);
            return sources;
        }
        return sources;
    }

    private static List<SourceTable> mergedSources(SQLJoinTableSource.JoinType type,
            List<SourceTable> left, List<SourceTable> right) {
        List<SourceTable> merged = new ArrayList<>();
        switch (type) {
            case LEFT_OUTER_JOIN:
            case LEFT_SEMI_JOIN:
            case LEFT_ANTI_JOIN:
                merged.addAll(left);
                break;
            case RIGHT_OUTER_JOIN:
                merged.addAll(right);
                break;
            default:
                merged.addAll(left);
                merged.addAll(right);
                break;
        }
        return merged;
    }

    private SourceTable resolveSource(SQLExprTableSource table) {
        String written = normalizeQualifiedName(extractTableName(table));
        String alias = table.getAlias() == null ? null : normalizeIdentifier(table.getAlias());
        Table metadataTable = null;
        String display = alias != null ? alias : lastIdentifier(written);

        if (context != null && !otherItemNames.contains(written.toLowerCase())) {
            TableLocation location = resolveMetadataTable(written);
            if (location != null) {
                metadataTable = location.getTable();
                display = location.getDisplayName();
            }
        }

        String mappingName = metadataTable != null ? display : written;
        registerTable(written, mappingName, display, alias);

        return new SourceTable(display, written, alias, metadataTable);
    }

    private TableLocation resolveMetadataTable(String written) {
        MetadataCatalog catalog = context.getCatalog();
        List<TableLocation> candidates = catalog.findAllTables(written);
        List<TableLocation> filtered = new ArrayList<>();
        for (TableLocation candidate : candidates) {
            if (context.getInstance() != null
                    && !context.getInstance().equalsIgnoreCase(candidate.getInstance())) {
                continue;
            }
            if (context.getDatabase() != null
                    && !context.getDatabase().equalsIgnoreCase(candidate.getDatabase())) {
                continue;
            }
            filtered.add(candidate);
        }

        boolean schemaExplicit = hasExplicitSchema(written);
        if (!schemaExplicit && !context.getSchemas().isEmpty()) {
            for (String schema : context.getSchemas()) {
                for (TableLocation candidate : filtered) {
                    if (schema.equalsIgnoreCase(candidate.getSchema())) {
                        return candidate;
                    }
                }
            }
            throw new MetadataNotFoundException(written);
        }

        if (filtered.isEmpty()) {
            throw new MetadataNotFoundException(written);
        }
        if (filtered.size() > 1) {
            List<String> names = new ArrayList<>();
            for (TableLocation candidate : filtered) {
                names.add(candidate.getQualifiedName());
            }
            throw new AmbiguousTableException(written, names);
        }
        return filtered.get(0);
    }

    private boolean hasExplicitSchema(String reference) {
        String[] parts = MetadataCatalog.splitQualifiedName(reference);
        if (parts.length >= 3) {
            return true;
        }
        if (parts.length == 2) {
            return !isMySqlFamily();
        }
        return false;
    }

    private boolean isMySqlFamily() {
        return context != null && (context.getCatalog().getDialect() == DbType.mysql
                || context.getCatalog().getDialect() == DbType.mariadb);
    }

    private void registerTable(String written, String mappingName, String display, String alias) {
        if (otherItemNames.contains(written.toLowerCase()) || tables.contains(display)) {
            return;
        }
        tables.add(display);
        if (alias != null) {
            instructions.add(new Instruction(OperationType.TABLE_MAPPING, mappingName, alias));
        }
    }

    private String extractTableName(SQLExprTableSource table) {
        SQLExpr expr = table.getExpr();
        return expr != null ? expr.toString() : table.getTableName();
    }

    /* ------------------------------------------------------------------ */
    /* expressions                                                        */
    /* ------------------------------------------------------------------ */

    private void visitExpr(SQLExpr expr) {
        if (expr == null) {
            return;
        }
        if (expr instanceof SQLIdentifierExpr) {
            visitIdentifier((SQLIdentifierExpr) expr);
        } else if (expr instanceof SQLPropertyExpr) {
            visitProperty((SQLPropertyExpr) expr);
        } else if (expr instanceof SQLAllColumnExpr) {
            visitAllColumn();
        } else if (expr instanceof SQLBinaryOpExpr) {
            SQLBinaryOpExpr binary = (SQLBinaryOpExpr) expr;
            visitExpr(binary.getLeft());
            visitExpr(binary.getRight());
        } else if (expr instanceof SQLMethodInvokeExpr) {
            List<SQLExpr> arguments = ((SQLMethodInvokeExpr) expr).getArguments();
            if (arguments != null) {
                for (SQLExpr argument : arguments) {
                    visitExpr(argument);
                }
            }
        } else if (expr instanceof SQLCastExpr) {
            visitExpr(((SQLCastExpr) expr).getExpr());
        } else if (expr instanceof SQLCaseExpr) {
            SQLCaseExpr caseExpr = (SQLCaseExpr) expr;
            visitExpr(caseExpr.getValueExpr());
            if (caseExpr.getItems() != null) {
                for (SQLCaseExpr.Item item : caseExpr.getItems()) {
                    visitExpr(item.getConditionExpr());
                    visitExpr(item.getValueExpr());
                }
            }
            visitExpr(caseExpr.getElseExpr());
        } else if (expr instanceof SQLListExpr) {
            for (SQLExpr item : ((SQLListExpr) expr).getItems()) {
                visitExpr(item);
            }
        } else if (expr instanceof SQLInListExpr) {
            SQLInListExpr inList = (SQLInListExpr) expr;
            visitExpr(inList.getExpr());
            for (SQLExpr target : inList.getTargetList()) {
                visitExpr(target);
            }
        } else if (expr instanceof SQLInSubQueryExpr) {
            visitExpr(((SQLInSubQueryExpr) expr).getExpr());
        } else if (expr instanceof SQLBetweenExpr) {
            SQLBetweenExpr between = (SQLBetweenExpr) expr;
            visitExpr(between.getTestExpr());
            visitExpr(between.getBeginExpr());
            visitExpr(between.getEndExpr());
        } else if (expr instanceof SQLNotExpr) {
            visitExpr(((SQLNotExpr) expr).getExpr());
        } else if (expr instanceof SQLUnaryExpr) {
            visitExpr(((SQLUnaryExpr) expr).getExpr());
        } else if (expr instanceof SQLExtractExpr) {
            visitExpr(((SQLExtractExpr) expr).getValue());
        }
    }

    private void visitIdentifier(SQLIdentifierExpr identifier) {
        if (!inTargetExpression) {
            return;
        }
        List<SourceTable> sources = resolveUnqualified(normalizeIdentifier(identifier.getName()));
        for (SourceTable source : sources) {
            addColumnMapping(source.displayName, normalizeIdentifier(identifier.getName()));
        }
    }

    private void visitProperty(SQLPropertyExpr property) {
        String name = normalizeIdentifier(property.getName());
        if ("*".equals(name)) {
            if (!inTargetExpression) {
                return;
            }
            SourceTable source = findByOwner(property.getOwner());
            if (source != null) {
                instructions.add(new Instruction(OperationType.COLUMNS_INJECT,
                        source.displayName, stackTargetTable.peek(), currentOrdinal));
            }
            return;
        }

        if (!inTargetExpression) {
            return;
        }
        SourceTable source = findByOwner(property.getOwner());
        if (source == null) {
            if (context != null) {
                throw new MetadataNotFoundException(
                        property.getOwner() == null ? name : property.getOwner().toString());
            }
            addColumnMapping(lastIdentifier(property.getOwner() == null
                    ? null : property.getOwner().toString()), name);
            return;
        }
        if (source.metadataTable != null && !source.metadataTable.hasColumn(name)) {
            throw new ColumnNotFoundException(name, displayNames(currentSources()));
        }
        addColumnMapping(source.displayName, name);
    }

    private void visitAllColumn() {
        if (!inTargetExpression) {
            return;
        }
        List<SourceTable> sources = currentSources();
        if (sources.isEmpty()) {
            return;
        }
        if (context == null) {
            instructions.add(new Instruction(OperationType.COLUMNS_INJECT,
                    sources.get(0).displayName, stackTargetTable.peek(), currentOrdinal));
            return;
        }
        int position = currentOrdinal;
        for (SourceTable source : sources) {
            instructions.add(new Instruction(OperationType.COLUMNS_INJECT,
                    source.displayName, stackTargetTable.peek(), position));
            position += columnCount(source);
        }
    }

    private List<SourceTable> resolveUnqualified(String columnName) {
        List<SourceTable> sources = currentSources();
        if (sources.isEmpty()) {
            return sources;
        }

        Map<String, List<SourceTable>> using = stackUsing.isEmpty()
                ? null : stackUsing.peek();
        if (using != null && using.containsKey(columnName.toLowerCase())) {
            return using.get(columnName.toLowerCase());
        }

        if (context == null) {
            List<SourceTable> result = new ArrayList<>();
            result.add(sources.get(0));
            return result;
        }

        List<SourceTable> candidates = new ArrayList<>();
        for (SourceTable source : sources) {
            if (source.metadataTable != null && source.metadataTable.hasColumn(columnName)) {
                candidates.add(source);
            }
        }
        if (candidates.size() == 1) {
            return candidates;
        }
        if (candidates.size() > 1) {
            throw new AmbiguousColumnException(columnName, displayNames(candidates));
        }
        for (SourceTable source : sources) {
            if (source.metadataTable == null) {
                List<SourceTable> fallback = new ArrayList<>();
                fallback.add(sources.get(0));
                return fallback;
            }
        }
        throw new ColumnNotFoundException(columnName, displayNames(sources));
    }

    private static List<String> displayNames(List<SourceTable> sources) {
        List<String> names = new ArrayList<>();
        for (SourceTable source : sources) {
            names.add(source.displayName);
        }
        return names;
    }

    private SourceTable findByOwner(SQLExpr owner) {
        if (owner == null) {
            return null;
        }
        String name = normalizeQualifiedName(owner.toString());
        for (SourceTable source : currentSources()) {
            if (source.alias != null && source.alias.equalsIgnoreCase(name)) {
                return source;
            }
        }
        for (SourceTable source : currentSources()) {
            if (source.displayName.equalsIgnoreCase(name)
                    || (source.writtenName != null && source.writtenName.equalsIgnoreCase(name))) {
                return source;
            }
        }
        for (SourceTable source : currentSources()) {
            if (source.displayName.toLowerCase().endsWith("." + name.toLowerCase())) {
                return source;
            }
        }
        return null;
    }

    private void addColumnMapping(String sourceTable, String columnName) {
        instructions.add(new Instruction(OperationType.COLUMN_MAPPING,
                sourceTable + "." + columnName,
                stackTargetTable.peek() + "." + stackTargetColumn.peek(), currentOrdinal));
    }

    private List<SourceTable> currentSources() {
        return stackSources.isEmpty() ? new ArrayList<SourceTable>() : stackSources.peek();
    }

    /* ------------------------------------------------------------------ */
    /* insert / update / delete / create                                  */
    /* ------------------------------------------------------------------ */

    private void processInsert(SQLInsertStatement insert) {
        SQLExprTableSource tableSource = insert.getTableSource();
        String written = normalizeQualifiedName(extractTableName(tableSource));
        // The target is emitted as written; source tables use fully qualified metadata names.
        String tableName = written;
        String resolvedName = written;
        if (context != null && !otherItemNames.contains(written.toLowerCase())) {
            resolvedName = resolveMetadataTable(written).getDisplayName();
        }
        registerTable(written, resolvedName, resolvedName, tableSource.getAlias() == null
                ? null : normalizeIdentifier(tableSource.getAlias()));

        processWithSubQuery(insert.getWith());

        stackTargetTable.push(getTempTableName());

        SQLSelect select = insert.getQuery();
        if (select != null) {
            processSelect(select);
        }

        List<SQLExpr> columns = insert.getColumns();
        if (columns != null && !columns.isEmpty()) {
            String colStr = columns.stream()
                    .map(column -> normalizeIdentifier(column.toString()))
                    .collect(Collectors.joining(","));
            instructions.add(new Instruction(OperationType.COLUMNS_INJECT,
                    stackTargetTable.peek(), String.format("%s(%s)", tableName, colStr)));
        } else {
            instructions.add(new Instruction(OperationType.COLUMNS_INJECT,
                    stackTargetTable.peek(), tableName));
        }
        stackTargetTable.pop();
    }

    private void processUpdate(SQLUpdateStatement update) {
        Map<String, List<SourceTable>> using = new HashMap<>();
        List<SourceTable> sources = new ArrayList<>();
        if (update.getTableSource() != null) {
            sources.addAll(collectSources(update.getTableSource(), using));
        }
        if (update.getFrom() != null) {
            sources.addAll(collectSources(update.getFrom(), using));
        }
        if (sources.isEmpty()) {
            return;
        }

        String target = sources.get(0).displayName;
        stackTargetTable.push(target);
        stackSources.push(sources);
        stackUsing.push(using);
        stackSourceTable.push(target);
        inTargetExpression = true;
        try {
            for (SQLUpdateSetItem item : update.getItems()) {
                String targetColumn = targetColumnName(item.getColumn());
                stackTargetColumn.push(targetColumn);
                currentOrdinal = -1;
                visitExpr(item.getValue());
                stackTargetColumn.pop();
            }
        } finally {
            inTargetExpression = false;
            stackSourceTable.pop();
            stackUsing.pop();
            stackSources.pop();
            stackTargetTable.pop();
        }
        collectTablesFromExpression(update.getWhere());
    }

    private void processDelete(SQLDeleteStatement delete) {
        Map<String, List<SourceTable>> using = new HashMap<>();
        List<SourceTable> sources = new ArrayList<>();
        if (delete.getTableSource() != null) {
            sources.addAll(collectSources(delete.getTableSource(), using));
        }
        if (delete.getFrom() != null) {
            sources.addAll(collectSources(delete.getFrom(), using));
        }
        collectTablesFromExpression(delete.getWhere());
    }

    private void processCreateTable(SQLCreateTableStatement create) {
        SQLExprTableSource tableSource = create.getTableSource();
        if (tableSource != null) {
            String written = normalizeQualifiedName(extractTableName(tableSource));
            registerTable(written, written, written, null);
        }
        if (create.getSelect() != null) {
            processSelect(create.getSelect());
        }
    }

    /* ------------------------------------------------------------------ */
    /* WHERE subqueries (tables only)                                     */
    /* ------------------------------------------------------------------ */

    private void collectTablesFromExpression(SQLExpr expr) {
        if (expr == null) {
            return;
        }
        if (expr instanceof SQLInSubQueryExpr) {
            collectTablesFromSelect(((SQLInSubQueryExpr) expr).getSubQuery());
        } else if (expr instanceof SQLExistsExpr) {
            collectTablesFromSelect(((SQLExistsExpr) expr).getSubQuery());
        } else if (expr instanceof SQLQueryExpr) {
            collectTablesFromSelect(((SQLQueryExpr) expr).getSubQuery());
        } else if (expr instanceof SQLBinaryOpExpr) {
            collectTablesFromExpression(((SQLBinaryOpExpr) expr).getLeft());
            collectTablesFromExpression(((SQLBinaryOpExpr) expr).getRight());
        } else if (expr instanceof SQLNotExpr) {
            collectTablesFromExpression(((SQLNotExpr) expr).getExpr());
        } else if (expr instanceof SQLUnaryExpr) {
            collectTablesFromExpression(((SQLUnaryExpr) expr).getExpr());
        } else if (expr instanceof SQLBetweenExpr) {
            collectTablesFromExpression(((SQLBetweenExpr) expr).getTestExpr());
            collectTablesFromExpression(((SQLBetweenExpr) expr).getBeginExpr());
            collectTablesFromExpression(((SQLBetweenExpr) expr).getEndExpr());
        } else if (expr instanceof SQLInListExpr) {
            collectTablesFromExpression(((SQLInListExpr) expr).getExpr());
        }
    }

    private void collectTablesFromSelect(SQLSelect select) {
        if (select == null) {
            return;
        }
        if (select.getWithSubQuery() != null) {
            for (SQLWithSubqueryClause.Entry entry : select.getWithSubQuery().getEntries()) {
                collectTablesFromSelect(entry.getSubQuery());
            }
        }
        collectTablesFromQuery(select.getQuery());
    }

    private void collectTablesFromQuery(SQLSelectQuery query) {
        if (query instanceof SQLSelectQueryBlock) {
            SQLSelectQueryBlock block = (SQLSelectQueryBlock) query;
            collectSourceTablesOnly(block.getFrom());
            collectTablesFromExpression(block.getWhere());
        } else if (query instanceof SQLUnionQuery) {
            collectTablesFromQuery(((SQLUnionQuery) query).getLeft());
            collectTablesFromQuery(((SQLUnionQuery) query).getRight());
        }
    }

    private void collectSourceTablesOnly(SQLTableSource tableSource) {
        if (tableSource == null) {
            return;
        }
        if (tableSource instanceof SQLJoinTableSource) {
            SQLJoinTableSource join = (SQLJoinTableSource) tableSource;
            collectSourceTablesOnly(join.getLeft());
            collectSourceTablesOnly(join.getRight());
        } else if (tableSource instanceof SQLSubqueryTableSource) {
            collectTablesFromSelect(((SQLSubqueryTableSource) tableSource).getSelect());
        } else if (tableSource instanceof SQLUnionQueryTableSource) {
            collectTablesFromQuery(((SQLUnionQueryTableSource) tableSource).getUnion());
        } else if (tableSource instanceof SQLExprTableSource) {
            resolveSource((SQLExprTableSource) tableSource);
        }
    }

    /* ------------------------------------------------------------------ */
    /* helpers                                                            */
    /* ------------------------------------------------------------------ */

    protected void init(boolean allowColumnProcessing) {
        otherItemNames = new ArrayList<>();
        tables = new HashSet<>();
        instructions = new ArrayList<>();
        stackSourceTable = new Stack<>();
        stackTargetTable = new Stack<>();
        stackTargetColumn = new Stack<>();
        stackSources = new Stack<>();
        stackUsing = new Stack<>();
        inTargetExpression = false;
        currentOrdinal = -1;
    }

    public String getTempTableName() {
        return String.format("temp%d", tempTableNum++);
    }

    private String targetColumnName(SQLExpr expr) {
        if (expr instanceof SQLCastExpr) {
            return targetColumnName(((SQLCastExpr) expr).getExpr());
        }
        if (expr instanceof SQLIdentifierExpr) {
            return normalizeIdentifier(((SQLIdentifierExpr) expr).getName());
        }
        if (expr instanceof SQLPropertyExpr) {
            SQLPropertyExpr property = (SQLPropertyExpr) expr;
            if (!"*".equals(property.getName())) {
                return normalizeIdentifier(property.getName());
            }
        }
        return expr.toString();
    }

    private static String normalizeIdentifier(String name) {
        return MetadataCatalog.normalizeIdentifier(name);
    }

    private static String normalizeQualifiedName(String name) {
        String[] parts = MetadataCatalog.splitQualifiedName(name);
        return String.join(".", parts);
    }

    private static String lastIdentifier(String name) {
        if (name == null) {
            return null;
        }
        String value = name.trim();
        int idx = value.lastIndexOf('.');
        return idx >= 0 ? value.substring(idx + 1) : value;
    }

    private static final class SourceTable {
        private final String displayName;
        private final String writtenName;
        private final String alias;
        private final Table metadataTable;

        private SourceTable(String displayName, String writtenName, String alias,
                Table metadataTable) {
            this.displayName = displayName;
            this.writtenName = writtenName;
            this.alias = alias;
            this.metadataTable = metadataTable;
        }
    }

    private static final class SessionState {
        private String instance;
        private String database;
        private List<String> schemas = new ArrayList<>();

        private SessionState(MetadataContext base) {
            if (base != null) {
                this.instance = base.getInstance();
                this.database = base.getDatabase();
                this.schemas = new ArrayList<>(base.getSchemas());
            }
        }

        private MetadataContext toContext(MetadataContext base) {
            return base == null ? null : base.withSession(instance, database, schemas);
        }
    }
}

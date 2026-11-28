/*-
 * Author: Laotie
 * Licensed under Apache License 2.0
 * #L%
 */
package com.laotie;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.stream.Collectors;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.SQLName;
import com.alibaba.druid.sql.ast.SQLObject;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.expr.SQLAllColumnExpr;
import com.alibaba.druid.sql.ast.expr.SQLBetweenExpr;
import com.alibaba.druid.sql.ast.expr.SQLBinaryOpExpr;
import com.alibaba.druid.sql.ast.expr.SQLCaseExpr;
import com.alibaba.druid.sql.ast.expr.SQLCastExpr;
import com.alibaba.druid.sql.ast.expr.SQLExtractExpr;
import com.alibaba.druid.sql.ast.expr.SQLExistsExpr;
import com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.druid.sql.ast.expr.SQLTextLiteralExpr;
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
import com.alibaba.druid.sql.ast.statement.SQLColumnDefinition;
import com.alibaba.druid.sql.ast.statement.SQLExprTableSource;
import com.alibaba.druid.sql.ast.statement.SQLInsertStatement;
import com.alibaba.druid.sql.ast.statement.SQLMergeStatement;
import com.alibaba.druid.sql.ast.statement.SQLReplaceStatement;
import com.alibaba.druid.sql.ast.statement.SQLTruncateStatement;
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
import com.alibaba.druid.sql.dialect.oracle.visitor.OracleASTVisitorAdapter;
import com.alibaba.druid.sql.dialect.postgresql.ast.stmt.PGInsertStatement;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlInsertStatement;
import com.alibaba.druid.sql.visitor.SQLASTVisitor;
import com.alibaba.druid.sql.dialect.sqlserver.visitor.SQLServerASTVisitorAdapter;
import com.alibaba.druid.sql.dialect.oracle.ast.stmt.OracleSelectTableReference;
import com.alibaba.druid.sql.dialect.oracle.ast.stmt.OracleSelectJoin;
import com.alibaba.druid.sql.dialect.oracle.ast.stmt.OracleSelectSubqueryTableSource;
import com.alibaba.druid.sql.visitor.SQLASTVisitorAdapter;
import com.alibaba.druid.sql.ast.statement.SQLWithSubqueryClause;

import com.laotie.exception.AmbiguousColumnException;
import com.laotie.exception.AmbiguousTableException;
import com.laotie.exception.ColumnNotFoundException;
import com.laotie.exception.MetadataNotFoundException;
import com.laotie.exception.UnquotedIdentifierException;
import com.laotie.analysis.AnalysisContext;
import com.laotie.analysis.model.CorrelatedOwner;
import com.laotie.analysis.model.OutputName;
import com.laotie.analysis.model.ScalarSubquery;
import com.laotie.analysis.model.SourceNode;
import com.laotie.analysis.model.SourceTable;
import com.laotie.analysis.model.StarColumn;
import com.laotie.analysis.model.StarOutput;
import com.laotie.analysis.model.WildcardRef;
import com.laotie.analysis.model.WildcardSpan;
import com.laotie.dialect.DialectRules;
import com.laotie.identifier.Identifier;
import com.laotie.identifier.IdentifierCase;
import com.laotie.identifier.Identifiers;
import com.laotie.metadata.SessionState;
import com.laotie.metadata.MetadataResolver;
import com.laotie.parse.ScriptSplitter;
import com.laotie.parse.SqlParser;
import com.laotie.validation.StrictQuotedIdentifierValidator;
import com.laotie.model.Instruction;
import com.laotie.model.Instruction.OperationType;
import com.laotie.model.LineageResult;
import com.laotie.model.metadata.Column;
import com.laotie.model.metadata.LearnedMetadata;
import com.laotie.model.metadata.MetadataCatalog;
import com.laotie.model.metadata.MetadataContext;
import com.laotie.model.metadata.MetadataContextHolder;
import com.laotie.model.metadata.MetadataMode;
import com.laotie.model.metadata.Table;
import com.laotie.model.metadata.TableLocation;

/**
 * Extract table and column level lineage from SQL statements.
 */
@SuppressWarnings({"PMD.CyclomaticComplexity", "PMD.UncommentedEmptyMethodBody"})
public class DmlLineage {

    private Set<String> tables;
    private List<Instruction> instructions;
    private final AnalysisContext state = new AnalysisContext();

    List<Instruction> instructions() {
        return instructions;
    }

    MetadataContext analysisContext() {
        return context;
    }

    LineageOptions options() {
        return options;
    }

    boolean ansiEnabled() {
        return ansiEnabled;
    }

    boolean ansiDoubleQuotedIdentifiers() {
        return ansiDoubleQuotedIdentifiers;
    }

    String statementSql() {
        return statementSql;
    }
    private final LearningAnalyzer learning = new LearningAnalyzer(this);

    AnalysisContext state() {
        return state;
    }

    Map<String, Set<String>> pendingLearned() {
        return pendingLearned;
    }

    LearnedMetadata learnedMetadata() {
        return learnedMetadata;
    }

    private final DbType dbType;
    private final boolean explicitDialect;
    private final MetadataContext explicitContext;
    private final LineageOptions options;
    private LearnedMetadata learnedMetadata;
    private String sessionDatabase;
    private String sessionRawDatabase;
    private String sessionSchema;
    private List<String> sessionRawSchemas = new ArrayList<>();
    private Map<String, Set<String>> pendingLearned = new HashMap<>();

    private MetadataContext context;

    public DmlLineage() {
        this(DbType.mysql, false, null, LineageOptions.defaults(), null);
    }

    public DmlLineage(DbType dbType) {
        this(dbType, true, null, LineageOptions.defaults(), null);
    }

    public DmlLineage(MetadataContext context) {
        this(DbType.mysql, false, context, LineageOptions.defaults(), null);
    }

    public DmlLineage(DbType dbType, LineageOptions options) {
        this(dbType, true, null, options, null);
    }

    public DmlLineage(MetadataContext context, LineageOptions options) {
        this(DbType.mysql, false, context, options, null);
    }

    public DmlLineage(LineageOptions options) {
        this(DbType.mysql, false, null, options, null);
    }

    private DmlLineage(DbType dbType, boolean explicitDialect, MetadataContext context,
            LineageOptions options, LearnedMetadata learnedMetadata) {
        this.dbType = dbType == null ? DbType.mysql : dbType;
        this.explicitDialect = explicitDialect;
        this.explicitContext = context;
        this.options = options == null ? LineageOptions.defaults() : options;
        this.learnedMetadata = learnedMetadata;
    }

    /**
     * Whether sources without a known column set are analyzed without inventing column
     * names.
     */
    boolean detachedMetadata() {
        return options.getMetadataMode() == MetadataMode.OPTIONAL;
    }

    /**
     * Attach the session-scoped metadata learned from earlier statements.
     */
    /**
     * Analyze only to discover metadata: instructions are discarded.
     */
    public void setLearningOnly(boolean learningOnly) {
        this.state.learningOnly = learningOnly;
    }

    public void setMetadataPass(boolean metadataPass) {
        this.state.metadataPass = metadataPass;
    }

    public void setLearningDisabled(boolean learningDisabled) {
        this.state.learningDisabled = learningDisabled;
    }

    public void setLearnedMetadata(LearnedMetadata learnedMetadata) {
        this.learnedMetadata = learnedMetadata;
    }

    /**
     * Remember that a column was referenced on a table, so that later statements can
     * resolve an unqualified column against it.
     */
    void learnColumn(SourceTable source, String columnName) {
        learnColumn(source, columnName, false);
    }

    void learnColumn(SourceTable source, String columnName, boolean explicit) {
        if (state.learningDisabled || learnedMetadata == null || source == null
                || source.temporary || !source.learnable
                || source.rawName == null || columnName == null
                || state.metadataPass && !explicit) {
            return;
        }
        String key = learnedTableKey(source.rawName);
        pendingLearned.computeIfAbsent(key, k -> new LinkedHashSet<String>())
                .add(identifierKey(columnName));
    }

    /**
     * The learned-metadata key of a table, scoped to the session database so that
     * observations from one database do not leak into another.
     */
    private String joinIdentifierKeys(List<String> names) {
        StringBuilder key = new StringBuilder();
        for (String name : names) {
            if (key.length() > 0) {
                key.append('\u0001');
            }
            key.append(identifierKey(name));
        }
        return key.toString();
    }

    String quoteForLearning(String name) {
        return DialectRules.of(currentDialect()).quoteForLearning(name);
    }

    Set<String> currentCteKeys() {
        Set<String> ctes = new HashSet<>();
        for (Map<String, String> frame : state.cteScopes) {
            ctes.addAll(frame.keySet());
        }
        return ctes;
    }

    String learnedTableKey(String rawName) {
        StringBuilder key = new StringBuilder();
        if (sessionDatabase != null) {
            key.append(identifierKey(sessionDatabase)).append('\u0001');
        }
        if (sessionSchema != null) {
            key.append(sessionSchema).append('\u0001');
        }
        key.append(qualifiedKey(rawName));
        return key.toString();
    }

    /**
     * Publish the observations of a successful statement. A failed statement must not
     * teach anything.
     */
    private void publishLearned() {
        if (state.learningDisabled) {
            pendingLearned = new HashMap<>();
            return;
        }
        if (learnedMetadata != null) {
            for (Map.Entry<String, Set<String>> entry : pendingLearned.entrySet()) {
                for (String column : entry.getValue()) {
                    learnedMetadata.learnNormalized(entry.getKey(), column);
                }
            }
        }
        pendingLearned = new HashMap<>();
    }

    /* ------------------------------------------------------------------ */
    /* public API                                                         */
    /* ------------------------------------------------------------------ */

    public List<Instruction> getLineage(SQLStatement statement) {
        MetadataContext previous = this.context;
        this.context = effectiveContext();
        this.statementSql = null;
        this.sessionRawSchemas = new ArrayList<>();
        this.sessionRawDatabase = null;
        try {
            if (options.isStrictQuotedIdentifiers()) {
                validateQuotedIdentifiers(statement);
            }
            init(true);
            state.stackTargetTable.push(getTempTableName());
            processStatement(statement);
            state.stackTargetTable.pop();
            return instructions;
        } finally {
            this.context = previous;
        }
    }

    public List<Instruction> getLineage(String sqlStr) {
        return getLineage(parseStatement(sqlStr));
    }

    public SQLStatement parseStatement(String sqlStr) {
        DbType dialect = resolveDialect();
        SQLStatement statement = parseWithDialectFallback(sqlStr, dialect);
        if (dialect == DbType.derby && options.isStrictQuotedIdentifiers()) {
            annotateJdbcEscapes(statement, sqlStr, dialect);
        }
        return statement;
    }

    /**
     * Spark SQL is Hive-compatible; the antspark parser does not accept some Hive
     * statements (for example INSERT OVERWRITE TABLE). Fall back to the Hive parser
     * only when the dialect's own parser rejects the statement.
     */
    /**
     * Mark JDBC escape function names so the validation AST can distinguish the
     * escape form from an ordinary call of the same name.
     */
    private void validateQuotedIdentifiers(SQLStatement statement) {
        StrictQuotedIdentifierValidator.validate(statement, currentDialect());
    }

    private void annotateJdbcEscapes(SQLStatement statement, String source,
            DbType dialect) {
        StrictQuotedIdentifierValidator.annotateJdbcEscapes(statement, source,
                dialect);
    }

    private SQLStatement parseWithDialectFallback(String sql, DbType dialect) {
        return SqlParser.parse(sql, dialect);
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
            for (String sql : splitStatements(sqlStr, dialect)) {
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
            statement = parseWithDialectFallback(sql, dialect);
            if (dialect == DbType.derby
                    && options.isStrictQuotedIdentifiers()) {
                annotateJdbcEscapes(statement, sql, dialect);
            }
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
        state.tempTableNum = 0;
        this.sessionDatabase = session.getDatabase();
        this.sessionRawDatabase = session.getRawDatabase();
        this.sessionRawSchemas = new ArrayList<>(session.getRawSchemas());
        List<String> rawSchemas = session.getRawSchemas().isEmpty()
                ? session.getSchemas() : session.getRawSchemas();
        this.sessionSchema = rawSchemas.isEmpty() ? null
                : joinIdentifierKeys(rawSchemas);
        this.pendingLearned = new HashMap<>();
        this.statementSql = sql;
        this.ansiEnabled = session.isAnsiEnabled();
        this.ansiDoubleQuotedIdentifiers = session.isAnsiDoubleQuotedIdentifiers();
        try {
            if (options.isStrictQuotedIdentifiers()) {
                validateQuotedIdentifiers(statement);
            }
            init(true);
            state.stackTargetTable.push(getTempTableName());
            processStatement(statement);
            state.stackTargetTable.pop();
            LineageResult result = new LineageResult(sql, instructions, working, null,
                    session.getInstance(), session.getDatabase(),
                    session.getSchemas(), state.tempTables,
                    namedTargetOutputs(), detachedMetadata());
            publishLearned();
            return result;
        } catch (Throwable t) {
            if (state.metadataPass) {
                // Discovery keeps the explicit references it already saw.
                publishLearned();
            }
            pendingLearned = new HashMap<>();
            return failed(sql, base, session, t);
        } finally {
            this.context = previous;
        }
    }

    private LineageResult sessionResult(String sql, MetadataContext base, SessionState session) {
        return new LineageResult(sql, new ArrayList<Instruction>(), session.toContext(base),
                null, session.getInstance(), session.getDatabase(),
                session.getSchemas());
    }

    private LineageResult failed(String sql, MetadataContext base, SessionState session,
            Throwable error) {
        return new LineageResult(sql, new ArrayList<Instruction>(), session.toContext(base),
                error, session.getInstance(), session.getDatabase(),
                session.getSchemas());
    }

    private void applyUse(SQLUseStatement use, SessionState session) {
        if (use.getDatabase() != null) {
            session.setDatabase(normalizeIdentifier(use.getDatabase().toString()));
            session.setRawDatabase(use.getDatabase().toString().trim());
        }
    }

    private void applySet(SQLSetStatement set, SessionState session) {
        for (SQLAssignItem item : set.getItems()) {
            String target = item.getTarget() == null ? ""
                    : item.getTarget().toString().trim()
                            .toLowerCase(Locale.ROOT);
            SQLExpr value = item.getValue();
            if (value == null) {
                continue;
            }
            boolean enabled = "true".equalsIgnoreCase(value.toString().trim());
            if (target.equals("spark.sql.ansi.doublequotedidentifiers")) {
                session.setAnsiDoubleQuotedIdentifiers(enabled);
            } else if (target.equals("spark.sql.ansi.enabled")) {
                session.setAnsiEnabled(enabled);
            } else if (target.endsWith("search_path")) {
                session.setSchemas(stringList(value));
                session.setRawSchemas(rawStringList(value));
            } else if (target.contains("schema")) {
                session.setSchemas(new ArrayList<>());
                session.getSchemas().add(normalizeIdentifier(value.toString()));
                session.setRawSchemas(new ArrayList<>());
                session.getRawSchemas().add(value.toString().trim());
            }
        }
    }

    private static List<String> rawStringList(SQLExpr value) {
        List<String> values = new ArrayList<>();
        if (value instanceof SQLListExpr) {
            for (SQLExpr item : ((SQLListExpr) value).getItems()) {
                values.add(rawSchemaToken(item));
            }
        } else {
            values.add(rawSchemaToken(value));
        }
        return values;
    }

    private static List<String> stringList(SQLExpr value) {
        List<String> values = new ArrayList<>();
        for (String raw : rawStringList(value)) {
            values.add(normalizeIdentifier(raw));
        }
        return values;
    }

    private static String rawSchemaToken(SQLExpr value) {
        if (value instanceof SQLTextLiteralExpr) {
            String text = ((SQLTextLiteralExpr) value).getText();
            return quoteIdentifierValue(text);
        }
        return value.toString().trim();
    }

    static List<String> splitStatements(String sql, DbType dialect) {
        return ScriptSplitter.split(sql, dialect);
    }

    private static boolean isIdentifierChar(char ch) {
        return ScriptSplitter.isIdentifierChar(ch);
    }

    DbType resolveDialect() {
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
        } else if (statement instanceof SQLReplaceStatement) {
            processReplace((SQLReplaceStatement) statement);
        } else if (statement instanceof SQLTruncateStatement) {
            processTruncate((SQLTruncateStatement) statement);
        } else if (statement instanceof SQLMergeStatement) {
            processMerge((SQLMergeStatement) statement);
        } else {
            throw new UnsupportedOperationException(
                    String.format("Finding tables from %s is not supported",
                            statement.getClass().getSimpleName()));
        }
    }

    void processSelect(SQLSelect select) {
        SQLWithSubqueryClause with = select.getWithSubQuery();
        if (with == null) {
            processSelectQuery(select.getQuery());
            return;
        }
        state.cteScopes.push(new HashMap<String, String>());
        try {
            processWithSubQuery(with);
            processSelectQuery(select.getQuery());
        } finally {
            state.cteScopes.pop();
        }
    }

    /**
     * Analyze a recursive WITH in dependency order so an earlier body sees a sibling
     * that it references. Cycles (including self references) fall back to declaration
     * order, where the pre-bound shape keeps the contract.
     */
    private java.util.List<SQLWithSubqueryClause.Entry> orderedRecursiveEntries(
            SQLWithSubqueryClause with) {
        java.util.List<SQLWithSubqueryClause.Entry> entries =
                new java.util.ArrayList<>(with.getEntries());
        java.util.Map<SQLWithSubqueryClause.Entry, java.util.Set<String>> aliases =
                new java.util.IdentityHashMap<>();
        for (SQLWithSubqueryClause.Entry entry : entries) {
            java.util.Set<String> keys = new java.util.LinkedHashSet<>();
            for (SQLWithSubqueryClause.Entry other : entries) {
                if (other != entry && other.getAlias() != null) {
                    keys.add(identifierKey(other.getAlias()));
                }
            }
            aliases.put(entry, keys);
        }
        java.util.Map<SQLWithSubqueryClause.Entry, java.util.Set<String>> entryRefs =
                new java.util.IdentityHashMap<>();
        for (SQLWithSubqueryClause.Entry entry : entries) {
            java.util.Set<String> refs = new java.util.LinkedHashSet<>();
            collectSelectReferences(entry.getSubQuery(), refs,
                    java.util.Collections.<String>emptySet());
            entryRefs.put(entry, refs);
        }
        java.util.List<SQLWithSubqueryClause.Entry> ordered = new java.util.ArrayList<>();
        java.util.Set<SQLWithSubqueryClause.Entry> done =
                java.util.Collections.newSetFromMap(
                        new java.util.IdentityHashMap<SQLWithSubqueryClause.Entry, Boolean>());
        while (ordered.size() < entries.size()) {
            boolean progress = false;
            for (SQLWithSubqueryClause.Entry entry : entries) {
                if (done.contains(entry)) {
                    continue;
                }
                boolean ready = true;
                for (String key : aliases.get(entry)) {
                    boolean references = entryRefs.get(entry).contains(key);
                    boolean hasDependent = false;
                    for (SQLWithSubqueryClause.Entry other : entries) {
                        if (other != entry && !done.contains(other)
                                && key.equals(identifierKey(
                                        String.valueOf(other.getAlias())))) {
                            hasDependent = true;
                        }
                    }
                    if (references && hasDependent) {
                        ready = false;
                        break;
                    }
                }
                if (ready) {
                    ordered.add(entry);
                    done.add(entry);
                    progress = true;
                }
            }
            if (!progress) {
                for (SQLWithSubqueryClause.Entry entry : entries) {
                    if (!done.contains(entry)) {
                        ordered.add(entry);
                    }
                }
                break;
            }
        }
        return ordered;
    }

    private void collectSelectReferences(SQLSelect select, Set<String> refs,
            Set<String> shadowed) {
        if (select == null) {
            return;
        }
        SQLWithSubqueryClause with = select.getWithSubQuery();
        Set<String> scope = shadowed;
        if (with != null) {
            Set<String> local = new java.util.LinkedHashSet<>();
            for (SQLWithSubqueryClause.Entry entry : with.getEntries()) {
                if (entry.getAlias() != null) {
                    local.add(identifierKey(entry.getAlias()));
                }
            }
            boolean recursive = Boolean.TRUE.equals(with.getRecursive());
            Set<String> bodyScope = new HashSet<>(shadowed);
            if (recursive) {
                // Every local name shadows an outer sibling inside any local body.
                bodyScope.addAll(local);
                for (SQLWithSubqueryClause.Entry entry : with.getEntries()) {
                    collectSelectReferences(entry.getSubQuery(), refs, bodyScope);
                }
            } else {
                // A non-recursive body only sees earlier local names.
                for (SQLWithSubqueryClause.Entry entry : with.getEntries()) {
                    collectSelectReferences(entry.getSubQuery(), refs, bodyScope);
                    if (entry.getAlias() != null) {
                        bodyScope.add(identifierKey(entry.getAlias()));
                    }
                }
            }
            scope = new HashSet<>(shadowed);
            scope.addAll(local);
        }
        collectQueryReferences(select.getQuery(), refs, scope);
    }

    private void collectQueryReferences(SQLSelectQuery query, Set<String> refs,
            Set<String> shadowed) {
        if (query instanceof SQLSelectQueryBlock) {
            collectSourceReferences(((SQLSelectQueryBlock) query).getFrom(), refs,
                    shadowed);
        } else if (query instanceof SQLUnionQuery) {
            SQLUnionQuery union = (SQLUnionQuery) query;
            collectQueryReferences(union.getLeft(), refs, shadowed);
            collectQueryReferences(union.getRight(), refs, shadowed);
        }
    }

    private void collectSourceReferences(SQLTableSource from, Set<String> refs,
            Set<String> shadowed) {
        if (from == null) {
            return;
        }
        if (from instanceof SQLJoinTableSource) {
            SQLJoinTableSource join = (SQLJoinTableSource) from;
            collectSourceReferences(join.getLeft(), refs, shadowed);
            collectSourceReferences(join.getRight(), refs, shadowed);
        } else if (from instanceof SQLExprTableSource) {
            SQLExprTableSource exprTable = (SQLExprTableSource) from;
            SQLSelect inline = queryExpressionSelect(exprTable.getExpr());
            if (inline != null) {
                collectSelectReferences(inline, refs, shadowed);
                return;
            }
            String name = extractTableName(exprTable);
            if (name != null && isSingleSegmentName(name)) {
                String key = identifierKey(name);
                if (!shadowed.contains(key)) {
                    refs.add(key);
                }
            }
        } else if (from instanceof SQLSubqueryTableSource) {
            collectSelectReferences(((SQLSubqueryTableSource) from).getSelect(), refs,
                    shadowed);
        } else if (from instanceof SQLUnionQueryTableSource) {
            collectQueryReferences(((SQLUnionQueryTableSource) from).getUnion(), refs,
                    shadowed);
        }
    }

    void processWithSubQuery(SQLWithSubqueryClause withSubQuery) {
        if (withSubQuery == null) {
            return;
        }
        boolean recursive = Boolean.TRUE.equals(withSubQuery.getRecursive());
        Map<SQLWithSubqueryClause.Entry, String> prebound = new HashMap<>();
        if (recursive) {
            // WITH RECURSIVE makes every sibling visible to every body, including
            // ones declared later, so bind all names first.
            for (SQLWithSubqueryClause.Entry entry : withSubQuery.getEntries()) {
                String alias = entry.getAlias();
                if (alias == null) {
                    continue;
                }
                String target = uniqueTarget(normalizeIdentifier(alias));
                state.tempTables.add(target);
                prebound.put(entry, target);
                if (!state.cteScopes.isEmpty()) {
                    state.cteScopes.peek().put(identifierKey(alias), target);
                }
            }
            // A sibling's columns are visible to earlier bodies too, so derive the
            // names that can be known statically before any body is analyzed.
            Map<String, List<String>> nameScope = new HashMap<>();
            Map<String, Integer> widthScope = new HashMap<>();
            for (SQLWithSubqueryClause.Entry entry : withSubQuery.getEntries()) {
                if (entry.getAlias() != null) {
                    String key = identifierKey(entry.getAlias());
                    nameScope.put(key, null);
                    widthScope.put(key, -1);
                }
            }
            int widthGuard = withSubQuery.getEntries().size() + 1;
            boolean widthChanged = true;
            while (widthChanged && widthGuard-- > 0) {
                widthChanged = false;
                for (SQLWithSubqueryClause.Entry entry : withSubQuery.getEntries()) {
                    String alias = entry.getAlias();
                    if (alias == null) {
                        continue;
                    }
                    int width = staticSelectWidth(entry.getSubQuery(), widthScope);
                    String key = identifierKey(alias);
                    if (width >= 0 && !Integer.valueOf(width).equals(widthScope.get(key))) {
                        widthScope.put(key, width);
                        widthChanged = true;
                    }
                }
            }
            Map<String, Boolean> widthCertain = new HashMap<>();
            int guard = withSubQuery.getEntries().size() + 1;
            boolean changed = true;
            while (changed && guard-- > 0) {
                changed = false;
                for (SQLWithSubqueryClause.Entry entry : withSubQuery.getEntries()) {
                    String alias = entry.getAlias();
                    if (alias == null) {
                        continue;
                    }
                    List<String> derived = staticOutputNames(entry.getSubQuery(),
                            nameScope);
                    List<String> declared = rawDeclaredColumns(entry);
                    List<String> names = derived;
                    boolean certain = derived != null;
                    if (names == null && declared != null) {
                        names = padToStaticWidth(declared, entry.getSubQuery(),
                                widthScope);
                        if (names == null) {
                            // Unknown width: the declared list names only the first
                            // positions, so later positions stay unknown.
                            names = declared;
                            certain = false;
                        } else {
                            certain = true;
                        }
                    }
                    names = mergeDeclaredColumns(declared, names);
                    if (names != null) {
                        String key = identifierKey(alias);
                        widthCertain.put(key, certain);
                        if (!names.equals(nameScope.get(key))) {
                            nameScope.put(key, names);
                            changed = true;
                        }
                    }
                }
            }
            for (SQLWithSubqueryClause.Entry entry : withSubQuery.getEntries()) {
                String alias = entry.getAlias();
                List<String> names = alias == null ? null
                        : nameScope.get(identifierKey(alias));
                String target = prebound.get(entry);
                if (names != null && target != null) {
                    boolean widthKnown = Boolean.TRUE.equals(
                            widthCertain.get(identifierKey(alias)));
                    Set<String> usedKeys = new HashSet<>();
                    for (String raw : names) {
                        if (raw != null) {
                            usedKeys.add(identifierKey(raw));
                        }
                    }
                    boolean namesKnown = true;
                    for (int i = 0; i < names.size(); i++) {
                        String raw = names.get(i);
                        String key;
                        String display;
                        if (raw == null) {
                            // A padded position has no known column name.
                            display = "col" + (i + 1);
                            while (usedKeys.contains(identifierKey(display))) {
                                display = display + "_";
                            }
                            usedKeys.add(identifierKey(display));
                            key = StaticShapeAnalyzer.CONSTANT_OUTPUT_PREFIX + "unknown:" + i;
                            namesKnown = false;
                        } else if (StaticShapeAnalyzer.CONSTANT_EXPRESSION.equals(raw)) {
                            display = "col" + (i + 1);
                            while (usedKeys.contains(identifierKey(display))) {
                                display = display + "_";
                            }
                            usedKeys.add(identifierKey(display));
                            key = StaticShapeAnalyzer.CONSTANT_OUTPUT_PREFIX + i;
                        } else {
                            String spelling = unescapeQuotedName(raw);
                            display = canonicalIdentifier(spelling);
                            key = identifierKey(spelling);
                        }
                        setTargetOutput(target, i, display, key);
                        if (raw != null) {
                            markOutputNameKnown(target, i, true);
                        }
                    }
                    // Make the sibling's shape visible before its body is analyzed;
                    // recordTargetShape overwrites this with the analyzed result.
                    state.targetWidthKnown.put(target, widthKnown);
                    state.targetNamesKnown.put(target, widthKnown && namesKnown);
                    state.targetMinWidth.put(target, names.size());
                }
            }
        }
        java.util.List<SQLWithSubqueryClause.Entry> ordered = recursive
                ? orderedRecursiveEntries(withSubQuery)
                : withSubQuery.getEntries();
        for (SQLWithSubqueryClause.Entry entry : ordered) {
            String alias = entry.getAlias();
            String target = prebound.containsKey(entry)
                    ? prebound.get(entry)
                    : alias != null
                            ? uniqueTarget(normalizeIdentifier(alias))
                            : getTempTableName();
            if (!prebound.containsKey(entry)) {
                state.tempTables.add(target);
            }

            List<String> declaredColumns = new ArrayList<>();
            List<String> declaredKeys = new ArrayList<>();
            if (entry.getColumns() != null) {
                for (com.alibaba.druid.sql.ast.SQLName column : entry.getColumns()) {
                    String raw = column.toString();
                    declaredColumns.add(canonicalIdentifier(raw));
                    declaredKeys.add(identifierKey(raw));
                }
            }
            Map<String, List<String>> aliases = new HashMap<>();
            if (!declaredColumns.isEmpty()) {
                aliases.put(target, declaredColumns);
                state.cteDeclaredColumns.put(target, declaredColumns);
                state.cteDeclaredKeys.put(target, declaredKeys);
            }
            state.targetColumnAliases.push(aliases);

            try {
                state.stackTargetTable.push(target);
                processSelect(entry.getSubQuery());
                state.stackTargetTable.pop();
                recordTargetShape(target);
            } finally {
                state.targetColumnAliases.pop();
            }
            if (!recursive && alias != null && !state.cteScopes.isEmpty()) {
                state.cteScopes.peek().put(identifierKey(alias), target);
            }
        }
    }

    private void processSelectQuery(SQLSelectQuery query) {
        if (query instanceof SQLSelectQueryBlock) {
            processQueryBlock((SQLSelectQueryBlock) query);
        } else if (query instanceof SQLUnionQuery) {
            SQLUnionQuery union = (SQLUnionQuery) query;
            String target = state.stackTargetTable.peek();
            boolean added = state.unionTargets.add(target);
            Integer previousOverride = state.targetWidthOverride.get(target);
            int staticWidth = staticUnionWidth(union, currentCteWidthScope());
            if (staticWidth > 0) {
                state.targetWidthOverride.put(target, staticWidth);
            }
            try {
                processSelectQuery(union.getLeft());
                int leftWidth = state.lastQueryBlockWidth;
                boolean leftKnown = state.lastQueryBlockWidthKnown;
                boolean leftNamesKnown = state.lastQueryBlockNamesKnown;
                int leftMinWidth = state.lastQueryBlockMinWidth;
                Integer overrideBeforeRight = state.targetWidthOverride.get(target);
                if (leftKnown && leftWidth > 0) {
                    state.targetWidthOverride.put(target, leftWidth);
                }
                incrementNonAuthoritative(target);
                try {
                    processSelectQuery(union.getRight());
                } finally {
                    decrementNonAuthoritative(target);
                    if (overrideBeforeRight == null) {
                        state.targetWidthOverride.remove(target);
                    } else {
                        state.targetWidthOverride.put(target, overrideBeforeRight);
                    }
                }
                int rightWidth = state.lastQueryBlockWidth;
                boolean rightKnown = state.lastQueryBlockWidthKnown;
                int rightMinWidth = state.lastQueryBlockMinWidth;
                if (leftKnown && rightKnown && leftWidth != rightWidth) {
                    throw new IllegalStateException(
                            "UNION column count mismatch: " + leftWidth + " vs " + rightWidth);
                }
                if (leftKnown && rightMinWidth > leftWidth) {
                    throw new IllegalStateException("UNION column count mismatch: "
                            + leftWidth + " vs at least " + rightMinWidth);
                }
                if (rightKnown && leftMinWidth > rightWidth) {
                    throw new IllegalStateException("UNION column count mismatch: at least "
                            + leftMinWidth + " vs " + rightWidth);
                }
                // Keep a known width from any branch so a later known branch that
                // conflicts with it still fails even when another branch is unknown.
                state.lastQueryBlockWidth = leftKnown ? leftWidth : rightWidth;
                state.lastQueryBlockWidthKnown = leftKnown || rightKnown;
                // A UNION takes its output names from the first branch, even when the
                // width is established by another branch.
                state.lastQueryBlockNamesKnown = leftNamesKnown;
                state.lastQueryBlockMinWidth = Math.max(leftMinWidth, state.lastQueryBlockMinWidth);
            } finally {
                if (previousOverride == null) {
                    state.targetWidthOverride.remove(target);
                } else {
                    state.targetWidthOverride.put(target, previousOverride);
                }
                if (added) {
                    state.unionTargets.remove(target);
                }
            }
        }
    }

    private final StaticShapeAnalyzer shape = new StaticShapeAnalyzer(this);

    private Map<String, Integer> currentCteWidthScope() {
        return shape.currentCteWidthScope();
    }

    private int staticSelectWidth(SQLSelect select, Map<String, Integer> outerScope) {
        return shape.staticSelectWidth(select, outerScope);
    }

    private List<String> staticOutputNames(SQLSelect select,
            Map<String, List<String>> scope) {
        return shape.staticOutputNames(select, scope);
    }

    private List<String> staticOutputNames(SQLSelectQuery query,
            Map<String, List<String>> scope) {
        return shape.staticOutputNames(query, scope);
    }

    private static List<String> mergeDeclaredColumns(List<String> declared,
            List<String> projected) {
        return StaticShapeAnalyzer.mergeDeclaredColumns(declared, projected);
    }

    private List<String> padToStaticWidth(List<String> declared, SQLSelect select,
            Map<String, Integer> scope) {
        return shape.padToStaticWidth(declared, select, scope);
    }

    private static List<String> rawDeclaredColumns(SQLWithSubqueryClause.Entry entry) {
        return StaticShapeAnalyzer.rawDeclaredColumns(entry);
    }

    private static String unescapeQuotedName(String raw) {
        return StaticShapeAnalyzer.unescapeQuotedName(raw);
    }

    private List<String> staticFromNames(SQLTableSource from,
            Map<String, List<String>> scope) {
        return shape.staticFromNames(from, scope);
    }

    private List<String> staticTableNames(SQLTableSource table,
            Map<String, List<String>> scope) {
        return shape.staticTableNames(table, scope);
    }

    private static SQLSelect queryExpressionSelect(SQLExpr expr) {
        return StaticShapeAnalyzer.queryExpressionSelect(expr);
    }

    private boolean isLateralSource(SQLTableSource source) {
        return shape.isLateralSource(source);
    }

    private List<String> staticSourceNames(SQLExprTableSource table,
            Map<String, List<String>> scope) {
        return shape.staticSourceNames(table, scope);
    }

    private int staticUnionWidth(SQLSelectQuery query, Map<String, Integer> scope) {
        return shape.staticUnionWidth(query, scope);
    }

    private int staticStarWidth(SQLTableSource from, SQLExpr owner,
            Map<String, Integer> scope) {
        return shape.staticStarWidth(from, owner, scope);
    }

    private int staticTableWidth(SQLTableSource table, Map<String, Integer> scope) {
        return shape.staticTableWidth(table, scope);
    }

    static String lastSegment(String qualifiedName) {
        return StaticShapeAnalyzer.lastSegment(qualifiedName);
    }

    private int staticFromWidth(SQLTableSource from, Map<String, Integer> scope) {
        return shape.staticFromWidth(from, scope);
    }

    private int staticSourceWidth(SQLExprTableSource table, Map<String, Integer> scope) {
        return shape.staticSourceWidth(table, scope);
    }

    private void processQueryBlock(SQLSelectQueryBlock queryBlock) {
        state.currentStarBudget = -1;
        state.currentExpandedSourceNodes = new HashMap<>();
        SQLTableSource fromItem = queryBlock.getFrom();
        if (fromItem == null) {
            if (state.metadataPass) {
                learnNoFromExplicitReferences(queryBlock, outerBlockLocals(true));
            }
            int position = 0;
            int minWidth = 0;
            boolean widthKnown = true;
            boolean namesKnown = true;
            boolean unknownStarSeen = false;
            List<SourceTable> outerSources = currentSources();
            for (SQLSelectItem item : queryBlock.getSelectList()) {
                if ((context != null || detachedMetadata())
                        && outerSources.isEmpty()) {
                    validateUnboundQualified(item.getExpr());
                }
                SQLExpr itemExpr = item.getExpr();
                if (!outerSources.isEmpty() && unknownStarSeen
                        && itemExpr instanceof SQLPropertyExpr
                        && isStarProperty(itemExpr)) {
                    namesKnown = false;
                    int minimum = recordUncertainOuterStar(
                            (SQLPropertyExpr) itemExpr, position);
                    position += minimum;
                    minWidth += minimum;
                    continue;
                }
                if (!outerSources.isEmpty() && itemExpr instanceof SQLPropertyExpr
                        && isStarProperty(itemExpr)) {
                    namesKnown = namesKnown && outerStarNamesKnown(
                            (SQLPropertyExpr) itemExpr);
                    int recorded = recordOuterStar((SQLPropertyExpr) itemExpr,
                            position);
                    boolean unknownWidth = recorded < 0;
                    int width = Math.abs(recorded);
                    position += width;
                    minWidth += width;
                    if (unknownWidth) {
                        widthKnown = false;
                        unknownStarSeen = true;
                    }
                    continue;
                }
                if (!outerSources.isEmpty() && unknownStarSeen) {
                    // The projection's position follows an unknown-width star, so no
                    // fixed column edge may be written for it.
                    recordUncertainOuterMappings(item, position);
                    int width = Math.max(minimumOutputColumns(item.getExpr()), 1);
                    position += width;
                    minWidth += 1;
                    continue;
                }
                int width;
                if (item.getExpr() instanceof SQLQueryExpr) {
                    width = processScalarSubqueryProjection(item,
                            (SQLQueryExpr) item.getExpr(), position);
                } else {
                    width = recordUnboundSelectItem(item, position);
                    if (!outerSources.isEmpty()) {
                        recordOuterExpressionMappings(item, position);
                    } else if (containsScalarSubquery(item.getExpr())) {
                        // A projection without FROM can still read a scalar
                        // subquery, which carries its own lineage.
                        recordUnboundScalarMappings(item, position);
                    }
                }
                position += width;
                minWidth += minimumOutputColumns(item.getExpr());
            }
            state.lastQueryBlockWidth = position;
            state.lastQueryBlockWidthKnown = widthKnown;
            state.lastQueryBlockNamesKnown = namesKnown;
            state.lastQueryBlockMinWidth = minWidth;
            return;
        }

        Map<String, List<SourceTable>> using = new LinkedHashMap<>();
        state.stackJoinBindings.push(new HashMap<String, SourceNode>());
        state.stackHiddenSources.push(new HashSet<SourceTable>());
        SourceNode node = buildSourceNode(fromItem, using);
        List<SourceTable> sources = node.sources;
        if (sources.isEmpty()) {
            state.stackHiddenSources.pop();
            state.stackJoinBindings.pop();
            return;
        }

        collectTablesFromExpression(queryBlock.getWhere());

        state.stackSources.push(sources);
        state.lateralScopes.push(Boolean.FALSE);
        state.stackUsing.push(using);
        state.stackStarPlan.push(node.plan);
        state.stackSourceNode.push(node);
        state.stackSourceTable.push(sources.get(0).displayName);
        state.targetOutputs.computeIfAbsent(state.stackTargetTable.peek(), k -> new ArrayList<String>());
        if (state.metadataPass) {
            learnBlockExplicitReferences(queryBlock, outerBlockLocals(false));
        }

        List<SQLSelectItem> selectItems = queryBlock.getSelectList();
        int inferredUnknownWidth = -1;
        int unknownItemIndex = -1;
        int[] startOverride = new int[selectItems.size()];
        for (int i = 0; i < startOverride.length; i++) {
            startOverride[i] = -1;
        }
        List<String> declaredWidths = currentTargetColumnAliases(state.stackTargetTable.peek());
        DbType dialect = currentDialect();
        boolean declaredExact = declaredWidths != null
                && (dialect == DbType.mysql || dialect == DbType.mariadb);
        int positionalWidth = -1;
        if (declaredExact) {
            positionalWidth = declaredWidths.size();
        } else {
            Integer overrideWidth = state.targetWidthOverride.get(state.stackTargetTable.peek());
            if (overrideWidth != null && overrideWidth > 0) {
                positionalWidth = overrideWidth;
            }
        }
        if (positionalWidth > 0) {
            // The exact output width is known from the declaration or a UNION sibling;
            // use it to anchor trailing fixed-width projections.
            int minSum = 0;
            int knownSum = 0;
            int unknownCount = 0;
            for (int i = 0; i < selectItems.size(); i++) {
                SQLSelectItem item = selectItems.get(i);
                minSum += minimumOutputColumns(item.getExpr());
                if (outputWidthKnown(item)) {
                    knownSum += outputColumnCount(item.getExpr());
                } else {
                    unknownCount++;
                    unknownItemIndex = i;
                }
            }
            if (unknownCount == 0) {
                // Every projection width is already known; positional anchoring could
                // only hide a genuine column-count conflict.
                positionalWidth = -1;
            } else if (positionalWidth >= minSum) {
                int suffix = 0;
                boolean suffixKnown = true;
                for (int i = selectItems.size() - 1; i >= 0; i--) {
                    SQLSelectItem item = selectItems.get(i);
                    if (!outputWidthKnown(item)) {
                        suffixKnown = false;
                        continue;
                    }
                    int itemWidth = outputColumnCount(item.getExpr());
                    if (suffixKnown) {
                        startOverride[i] = positionalWidth - suffix - itemWidth;
                    }
                    suffix += itemWidth;
                }
                if (unknownCount == 1) {
                    int inferred = positionalWidth - knownSum;
                    int unknownMinimum = minimumOutputColumns(
                            selectItems.get(unknownItemIndex).getExpr());
                    if (inferred >= unknownMinimum) {
                        inferredUnknownWidth = inferred;
                    }
                }
            } else {
                positionalWidth = -1;
            }
        }
        state.currentStarBudget = inferredUnknownWidth;
        if (!detachedMetadata() && inferredUnknownWidth > 0 && unknownItemIndex >= 0) {
            SourceNode targetNode = node;
            SQLExpr unknownExpr = selectItems.get(unknownItemIndex).getExpr();
            if (isStarProperty(unknownExpr)) {
                SQLExpr owner = ((SQLPropertyExpr) unknownExpr).getOwner();
                SourceNode binding = joinBinding(owner);
                if (binding != null) {
                    targetNode = binding;
                } else {
                    SourceTable source = findByOwner(owner);
                    if (source != null && !sourceColumnsKnown(source)) {
                        SourceNode sourceNode = sourcePlanNode(source);
                        state.currentExpandedSourceNodes.put(source, sourceNode);
                        targetNode = sourceNode;
                    }
                }
            }
            List<StarOutput> plan = targetNode.plan;
            if (!plan.isEmpty()) {
                // The UNION sibling fixes the projection width; give the unknown-width
                // JOIN side its missing slots so trailing known columns keep positions.
                List<SourceTable> leftUnknown = targetNode.unknownLeftSources;
                List<SourceTable> rightUnknown = targetNode.unknownRightSources;
                // Two or more independent unknown relations leave the distribution
                // of the missing width ambiguous.
                boolean leftOnly = leftUnknown.size() == 1 && rightUnknown.isEmpty()
                        && unknownRegionCount(leftUnknown.get(0)) <= 1;
                boolean rightOnly = rightUnknown.size() == 1 && leftUnknown.isEmpty()
                        && unknownRegionCount(rightUnknown.get(0)) <= 1;
                boolean handledRepeated = false;
                List<SourceTable> repeatedSide = leftUnknown.size() == 1
                        && rightUnknown.isEmpty() ? leftUnknown
                        : rightUnknown.size() == 1 && leftUnknown.isEmpty()
                                ? rightUnknown : null;
                if (repeatedSide != null
                        && totalWildcardOccurrences(repeatedSide.get(0)) > 1) {
                    SourceTable source = repeatedSide.get(0);
                    int deficit = inferredUnknownWidth - plan.size();
                    List<Integer> ends = unknownRegionEnds(source);
                    List<Integer> runs = unknownRegionRuns(source);
                    int occurrences = totalWildcardOccurrences(source);
                    // Locate the source's own plan to translate region boundaries after
                    // any USING reordering.
                    SourceNode sideNode = null;
                    int sideOffset = 0;
                    if (targetNode.leftNode != null
                            && containsSource(targetNode.leftNode.sources, source)) {
                        sideNode = targetNode.leftNode;
                    } else if (targetNode.rightNode != null
                            && containsSource(targetNode.rightNode.sources, source)) {
                        sideNode = targetNode.rightNode;
                        sideOffset = Math.max(plan.size()
                                - countRemaining(plan, targetNode.leftNode == null
                                        ? new ArrayList<StarOutput>()
                                        : targetNode.leftNode.plan)
                                - countRemaining(plan, targetNode.rightNode.plan), 0);
                    } else if (targetNode.sources.size() == 1
                            && targetNode.sources.get(0) == source) {
                        sideNode = targetNode;
                    }
                    int mergedPrefix = 0;
                    if (targetNode.leftNode != null && targetNode.rightNode != null) {
                        mergedPrefix = Math.max(plan.size()
                                - countRemaining(plan, targetNode.leftNode.plan)
                                - countRemaining(plan, targetNode.rightNode.plan), 0);
                    }
                    if (deficit >= 0 && sameUnknownGroup(source) && sideNode != null
                            && sideNode.plan.size() == source.outputNameKnown.size()
                            && occurrences > 0) {
                        if (deficit % occurrences != 0) {
                            throw new IllegalStateException(
                                    "UNION column count mismatch: repeated wildcard "
                                            + "widths cannot satisfy " + inferredUnknownWidth);
                        }
                        int perWildcard = deficit / occurrences;
                        int offset = 0;
                        for (int r = 0; r < ends.size(); r++) {
                            int boundary = ends.get(r);
                            int insertAt = mergedPrefix
                                    + keptBefore(plan, sideNode.plan, boundary) + sideOffset
                                    + offset;
                            insertAt = Math.min(insertAt, plan.size());
                            for (int k = 0; k < perWildcard * runs.get(r); k++) {
                                List<StarColumn> columns = new ArrayList<>();
                                String name = "col" + (insertAt + 1);
                                columns.add(new StarColumn(source, name,
                                        identifierKey(name), false, false));
                                plan.add(insertAt++, new StarOutput(columns, false, true));
                            }
                            offset += perWildcard * runs.get(r);
                        }
                        for (int i = 0; i < plan.size(); i++) {
                            plan.set(i, plan.get(i).withStable(true));
                        }
                        handledRepeated = true;
                    }
                }
                List<SourceTable> expansion;
                int insertAt;
                if (handledRepeated) {
                    expansion = new ArrayList<>();
                    insertAt = plan.size();
                } else if (leftOnly) {
                    expansion = leftUnknown;
                    insertAt = insertionPoint(plan, leftUnknown,
                            Math.min(targetNode.rightBoundary, plan.size()),
                            targetNode.unknownSectionEnd);
                } else if (rightOnly) {
                    expansion = rightUnknown;
                    insertAt = insertionPoint(plan, rightUnknown, plan.size(),
                            targetNode.unknownSectionEnd);
                } else {
                    expansion = new ArrayList<>(leftUnknown);
                    expansion.addAll(rightUnknown);
                    int maxEnd = -1;
                    for (SourceTable source : expansion) {
                        Integer end = targetNode.unknownSectionEnd.get(source);
                        if (end != null) {
                            maxEnd = Math.max(maxEnd, end);
                        }
                    }
                    // Everything after the last unknown section keeps its position.
                    insertAt = maxEnd >= 0 ? Math.min(maxEnd, plan.size()) : plan.size();
                }
                for (int i = plan.size(); i < inferredUnknownWidth
                        && !expansion.isEmpty(); i++) {
                    List<StarColumn> columns = new ArrayList<>();
                    String name = "col" + (insertAt + 1);
                    for (SourceTable source : expansion) {
                        columns.add(new StarColumn(source, name, identifierKey(name),
                                false, false));
                    }
                    plan.add(insertAt++, new StarOutput(columns, false, leftOnly || rightOnly));
                }
                if ((leftOnly || rightOnly) && inferredUnknownWidth >= plan.size()) {
                    // A single unknown side means the total width fixes every trailing
                    // position.
                    for (int i = insertAt; i < plan.size(); i++) {
                        plan.set(i, plan.get(i).withStable(true));
                    }
                } else {
                    // Even when the distribution is ambiguous, a known-width suffix
                    // after every unknown section keeps its positions.
                    int maxEnd = -1;
                    for (SourceTable source : expansion) {
                        Integer end = targetNode.unknownSectionEnd.get(source);
                        if (end != null) {
                            maxEnd = Math.max(maxEnd, end);
                        }
                    }
                    for (int i = Math.max(maxEnd, 0); i < plan.size(); i++) {
                        if (plan.get(i).nameKnown) {
                            plan.set(i, plan.get(i).withStable(true));
                        }
                    }
                }
            }
        }

        int position = 0;
        int minWidth = 0;
        boolean widthKnown = true;
        boolean namesKnown = true;
        boolean prefixCertain = true;
        boolean unknownStarSeen = false;
        for (int itemIndex = 0; itemIndex < selectItems.size(); itemIndex++) {
            SQLSelectItem item = selectItems.get(itemIndex);
            int start = startOverride[itemIndex] > position
                    ? startOverride[itemIndex] : position;
            boolean positionCertain = startOverride[itemIndex] >= 0 || prefixCertain;
            SQLExpr itemExpr = item.getExpr();
            boolean unknownStar = detachedMetadata()
                    && (itemExpr instanceof SQLAllColumnExpr || isStarProperty(itemExpr))
                    && hasUnknownStarSource(itemExpr);
            // Contract: a projection that follows an unknown-width wildcard has an
            // undetermined position, so no column-level edge may be guessed for it.
            state.uncertainOrdinal = positionalWidth > 0 && !positionCertain
                    || detachedMetadata() && unknownStarSeen ? start : -1;
            boolean inferredWidth = itemIndex == unknownItemIndex
                    && inferredUnknownWidth > 0;
            if (unknownStar && !starOutputWidthKnown(itemExpr) && !inferredWidth) {
                // Only an unknown-width wildcard leaves later positions undetermined.
                unknownStarSeen = true;
            }
            state.currentOrdinal = start;
            int advanced;
            try {
                advanced = processSelectItem(item);
            } catch (RuntimeException e) {
                if (!state.metadataPass) {
                    throw e;
                }
                // Discovery keeps walking the statement so explicit references
                // after an unresolvable projection are still observed.
                advanced = Math.max(minimumOutputColumns(item.getExpr()), 1);
            }
            if (inferredWidth) {
                // The UNION width pins this wildcard exactly once; it must not leak
                // into the next projection's width.
                advanced = Math.max(advanced, inferredUnknownWidth);
                state.currentStarBudget = -1;
            }
            position = start + advanced;
            minWidth += minimumOutputColumns(item.getExpr());
            boolean itemWidthKnown = outputWidthKnown(item) || inferredWidth;
            if (!itemWidthKnown) {
                widthKnown = false;
            }
            prefixCertain = prefixCertain && itemWidthKnown;
            if (!outputNamesKnown(item)) {
                namesKnown = false;
            }
        }
        state.uncertainOrdinal = -1;
        state.currentOrdinal = -1;
        state.lastQueryBlockWidth = position;
        state.lastQueryBlockWidthKnown = widthKnown;
        state.lastQueryBlockNamesKnown = namesKnown;
        state.lastQueryBlockMinWidth = minWidth;

        state.stackSourceTable.pop();
        state.stackSourceNode.pop();
        state.stackStarPlan.pop();
        state.stackUsing.pop();
        state.stackSources.pop();
        state.lateralScopes.pop();
        state.stackHiddenSources.pop();
        state.stackJoinBindings.pop();
    }

    private int recordUncertainOuterStar(SQLPropertyExpr star, int position) {
        String target = state.stackTargetTable.peek();
        CorrelatedOwner correlated = findCorrelatedOwner(star.getOwner());
        if (correlated == null) {
            throw new MetadataNotFoundException(star.getOwner() == null
                    ? "*" : star.getOwner().toString());
        }
        if (correlated.source != null) {
            addOuterWildcardOpen(correlated.source, target, position);
            return sourceWidthKnown(correlated.source)
                    ? Math.max(sourceKnownWidth(correlated.source), 1) : 1;
        }
        for (SourceTable candidate : correlated.binding.sources) {
            addOuterWildcardOpen(candidate, target, position);
        }
        return correlated.binding.widthKnown
                ? Math.max(correlated.binding.width, 1) : 1;
    }

    private void recordUncertainOuterMappings(SQLSelectItem item, int position) {
        SQLExpr expr = item.getExpr();
        if (expr == null) {
            return;
        }
        String target = state.stackTargetTable.peek();
        recordUncertainOuterExpression(expr, target, position);
    }

    private void recordUncertainOuterExpression(SQLExpr expr, String target,
            int position) {
        if (expr == null) {
            return;
        }
        if (expr instanceof SQLPropertyExpr) {
            SQLPropertyExpr property = (SQLPropertyExpr) expr;
            if (isStarProperty(property)) {
                return;
            }
            CorrelatedOwner correlated = findCorrelatedOwner(property.getOwner());
            if (correlated == null) {
                throw new MetadataNotFoundException(property.getOwner() == null
                        ? "*" : property.getOwner().toString());
            }
            if (correlated.source != null) {
                resolveOuterColumn(correlated.source, property.getName());
                addOuterWildcardOpen(correlated.source, target, position);
            } else {
                String key = identifierKey(property.getName());
                List<StarOutput> matched = new ArrayList<>();
                for (StarOutput output : correlated.binding.plan) {
                    for (StarColumn column : output.sources) {
                        if (key != null && key.equals(column.key)) {
                            matched.add(output);
                            break;
                        }
                    }
                }
                if (matched.size() > 1) {
                    List<SourceTable> owners = new ArrayList<>();
                    for (StarOutput output : matched) {
                        for (StarColumn column : output.sources) {
                            if (!owners.contains(column.source)) {
                                owners.add(column.source);
                            }
                        }
                    }
                    throw new AmbiguousColumnException(
                            normalizeIdentifier(property.getName()),
                            displayNames(owners));
                }
                if (matched.isEmpty() && nodeNamesKnown(correlated.binding)) {
                    throw new ColumnNotFoundException(
                            normalizeIdentifier(property.getName()),
                            displayNames(correlated.binding.sources));
                }
                if (matched.size() == 1) {
                    for (StarColumn column : matched.get(0).sources) {
                        addOuterWildcardOpen(column.source, target, position);
                    }
                } else {
                    for (SourceTable candidate : correlated.binding.sources) {
                        addOuterWildcardOpen(candidate, target, position);
                    }
                }
            }
            return;
        }
        if (expr instanceof SQLIdentifierExpr) {
            String raw = ((SQLIdentifierExpr) expr).getName();
            List<SourceTable> owners;
            try {
                owners = resolveUnqualified(raw);
            } catch (ColumnNotFoundException e) {
                owners = hasLateralAncestorScope()
                        ? resolveCorrelatedUnqualified(raw)
                        : new ArrayList<SourceTable>();
            }
            if (owners.isEmpty() && hasLateralAncestorScope()) {
                owners = resolveCorrelatedUnqualified(raw);
            }
            for (SourceTable source : owners) {
                resolveOuterColumn(source, raw);
                addOuterWildcardOpen(source, target, position);
            }
            return;
        }
        if (expr instanceof SQLBinaryOpExpr) {
            recordUncertainOuterExpression(((SQLBinaryOpExpr) expr).getLeft(), target,
                    position);
            recordUncertainOuterExpression(((SQLBinaryOpExpr) expr).getRight(), target,
                    position);
        } else if (expr instanceof SQLMethodInvokeExpr) {
            for (SQLExpr argument : ((SQLMethodInvokeExpr) expr).getArguments()) {
                recordUncertainOuterExpression(argument, target, position);
            }
        } else if (expr instanceof SQLCastExpr) {
            recordUncertainOuterExpression(((SQLCastExpr) expr).getExpr(), target,
                    position);
        } else if (expr instanceof SQLNotExpr) {
            recordUncertainOuterExpression(((SQLNotExpr) expr).getExpr(), target,
                    position);
        } else if (expr instanceof SQLUnaryExpr) {
            recordUncertainOuterExpression(((SQLUnaryExpr) expr).getExpr(), target,
                    position);
        } else if (expr instanceof SQLBetweenExpr) {
            SQLBetweenExpr between = (SQLBetweenExpr) expr;
            recordUncertainOuterExpression(between.getTestExpr(), target, position);
            recordUncertainOuterExpression(between.getBeginExpr(), target, position);
            recordUncertainOuterExpression(between.getEndExpr(), target, position);
        } else if (expr instanceof SQLCaseExpr) {
            SQLCaseExpr caseExpr = (SQLCaseExpr) expr;
            recordUncertainOuterExpression(caseExpr.getValueExpr(), target, position);
            if (caseExpr.getItems() != null) {
                for (SQLCaseExpr.Item caseItem : caseExpr.getItems()) {
                    recordUncertainOuterExpression(caseItem.getConditionExpr(),
                            target, position);
                    recordUncertainOuterExpression(caseItem.getValueExpr(), target,
                            position);
                }
            }
            recordUncertainOuterExpression(caseExpr.getElseExpr(), target, position);
        } else if (expr instanceof SQLListExpr) {
            for (SQLExpr element : ((SQLListExpr) expr).getItems()) {
                recordUncertainOuterExpression(element, target, position);
            }
        } else if (expr instanceof SQLInListExpr) {
            SQLInListExpr in = (SQLInListExpr) expr;
            recordUncertainOuterExpression(in.getExpr(), target, position);
            if (in.getTargetList() != null) {
                for (SQLExpr element : in.getTargetList()) {
                    recordUncertainOuterExpression(element, target, position);
                }
            }
        } else if (expr instanceof SQLInSubQueryExpr) {
            recordUncertainOuterExpression(((SQLInSubQueryExpr) expr).getExpr(),
                    target, position);
        } else if (expr instanceof SQLExtractExpr) {
            recordUncertainOuterExpression(((SQLExtractExpr) expr).getValue(), target,
                    position);
        }
    }

    private void recordOuterExpressionMappings(SQLSelectItem item, int position) {
        SQLExpr expr = item.getExpr();
        if (expr == null) {
            return;
        }
        String target = state.stackTargetTable.peek();
        List<String> outputs = state.targetOutputs.get(target);
        String name = outputs != null && position < outputs.size()
                && outputs.get(position) != null
                        ? outputs.get(position)
                        : normalizeIdentifier(expr.toString());
        recordOuterExpression(expr, target, name, position);
    }

    private void recordOuterExpression(SQLExpr expr, String target, String targetName,
            int position) {
        if (expr == null) {
            return;
        }
        if (expr instanceof SQLPropertyExpr) {
            SQLPropertyExpr property = (SQLPropertyExpr) expr;
            if (!isStarProperty(property)) {
                recordOuterColumn(property, target, targetName, position);
            }
            return;
        }
        if (expr instanceof SQLBinaryOpExpr) {
            SQLBinaryOpExpr binary = (SQLBinaryOpExpr) expr;
            recordOuterExpression(binary.getLeft(), target, targetName, position);
            recordOuterExpression(binary.getRight(), target, targetName, position);
        } else if (expr instanceof SQLMethodInvokeExpr) {
            for (SQLExpr argument : ((SQLMethodInvokeExpr) expr).getArguments()) {
                recordOuterExpression(argument, target, targetName, position);
            }
        } else if (expr instanceof SQLCastExpr) {
            recordOuterExpression(((SQLCastExpr) expr).getExpr(), target, targetName,
                    position);
        } else if (expr instanceof SQLNotExpr) {
            recordOuterExpression(((SQLNotExpr) expr).getExpr(), target, targetName,
                    position);
        } else if (expr instanceof SQLUnaryExpr) {
            recordOuterExpression(((SQLUnaryExpr) expr).getExpr(), target, targetName,
                    position);
        } else if (expr instanceof SQLBetweenExpr) {
            SQLBetweenExpr between = (SQLBetweenExpr) expr;
            recordOuterExpression(between.getTestExpr(), target, targetName, position);
            recordOuterExpression(between.getBeginExpr(), target, targetName, position);
            recordOuterExpression(between.getEndExpr(), target, targetName, position);
        } else if (expr instanceof SQLCaseExpr) {
            SQLCaseExpr caseExpr = (SQLCaseExpr) expr;
            recordOuterExpression(caseExpr.getValueExpr(), target, targetName,
                    position);
            if (caseExpr.getItems() != null) {
                for (SQLCaseExpr.Item caseItem : caseExpr.getItems()) {
                    recordOuterExpression(caseItem.getConditionExpr(), target,
                            targetName, position);
                    recordOuterExpression(caseItem.getValueExpr(), target, targetName,
                            position);
                }
            }
            recordOuterExpression(caseExpr.getElseExpr(), target, targetName, position);
        } else if (expr instanceof SQLListExpr) {
            for (SQLExpr element : ((SQLListExpr) expr).getItems()) {
                recordOuterExpression(element, target, targetName, position);
            }
        } else if (expr instanceof SQLInListExpr) {
            SQLInListExpr in = (SQLInListExpr) expr;
            recordOuterExpression(in.getExpr(), target, targetName, position);
            if (in.getTargetList() != null) {
                for (SQLExpr element : in.getTargetList()) {
                    recordOuterExpression(element, target, targetName, position);
                }
            }
        } else if (expr instanceof SQLInSubQueryExpr) {
            recordOuterExpression(((SQLInSubQueryExpr) expr).getExpr(), target,
                    targetName, position);
        } else if (expr instanceof SQLIdentifierExpr) {
            recordOuterIdentifier(((SQLIdentifierExpr) expr).getName(), target,
                    targetName, position);
        } else if (expr instanceof SQLExtractExpr) {
            recordOuterExpression(((SQLExtractExpr) expr).getValue(), target,
                    targetName, position);
        }
    }

    private boolean outerStarNamesKnown(SQLPropertyExpr star) {
        CorrelatedOwner correlated = findCorrelatedOwner(star.getOwner());
        if (correlated == null) {
            return false;
        }
        if (correlated.source != null) {
            return sourceColumnNamesKnown(correlated.source);
        }
        return nodeNamesKnown(correlated.binding);
    }

    private static boolean coveredBySpan(List<WildcardSpan> spans, int relative) {
        for (WildcardSpan span : spans) {
            if (relative >= span.start && relative < span.end) {
                return true;
            }
        }
        return false;
    }

    private boolean hasDuplicateNamedOutputs(SourceTable source) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < source.outputKeys.size(); i++) {
            if (!sourceOutputNameKnown(source, i)) {
                continue;
            }
            String key = source.outputKeys.get(i);
            if (key != null && !seen.add(key)) {
                return true;
            }
        }
        return false;
    }

    private String effectiveOutputName(SourceTable source, int index) {
        if (index < 0 || index >= source.outputColumns.size()) {
            return index >= 0 && index < source.outputColumns.size()
                    ? source.outputColumns.get(index) : null;
        }
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        for (String name : source.outputColumns) {
            counts.merge(name, 1, Integer::sum);
        }
        java.util.Set<String> usedNames = new java.util.HashSet<>(
                source.outputColumns);
        java.util.Set<String> duplicatesSeen = new java.util.HashSet<>();
        java.util.Map<Integer, String> effective = new java.util.HashMap<>();
        for (int position = 0; position < source.outputColumns.size(); position++) {
            String name = source.outputColumns.get(position);
            if (name != null && counts.get(name) > 1
                    && !duplicatesSeen.add(name)) {
                String candidate = "col" + (position + 1);
                int suffix = 1;
                while (usedNames.contains(candidate)
                        || effective.containsValue(candidate)) {
                    candidate = "col" + (position + 1) + "_" + suffix++;
                }
                name = candidate;
            }
            effective.put(position, name);
        }
        return effective.get(index);
    }

    private List<WildcardRef> wildcardRefsAt(SourceTable source, int relative) {
        List<WildcardRef> refs = new ArrayList<>();
        for (WildcardSpan span : wildcardSpansOf(source.displayName)) {
            if (relative >= span.start && relative < span.end) {
                WildcardRef ref = new WildcardRef(span.source, span.temporary);
                if (!refs.contains(ref)) {
                    refs.add(ref);
                }
            }
        }
        return refs;
    }

    private int recordOuterStar(SQLPropertyExpr star, int position) {
        String target = state.stackTargetTable.peek();
        CorrelatedOwner correlated = findCorrelatedOwner(star.getOwner());
        SourceTable source = correlated == null ? null : correlated.source;
        if (source != null) {
            List<String> names = sourceColumnNames(source);
            List<String> declared = currentTargetColumnAliases(target);
            List<String> declaredKeys = currentDeclaredKeys(target);
            if (source.temporary) {
                int width = sourceWidthKnown(source) ? sourceKnownWidth(source) : -1;
                if (width > 0) {
                    List<WildcardSpan> spans = wildcardSpansOf(source.displayName);
                    if (declared == null && hasDuplicateNamedOutputs(source)) {
                        // Ambiguous repeated names cannot be expanded into columns
                        // unless the target declares their order.
                        Instruction wildcard = new Instruction(
                                OperationType.COLUMNS_INJECT,
                                lineageTableName(source), target, position,
                                source.temporary, state.tempTables.contains(target));
                        wildcard.setPositionalWildcard(true);
                        wildcard.setWildcardWidthKnown(true);
                        wildcard.setWildcardWidth(width);
                        instructions.add(wildcard);
                        recordWildcardSpan(target, position, position + width,
                                lineageTableName(source), source.temporary);
                        state.targetWildcardContribution.put(target, Boolean.TRUE);
                        return width;
                    }
                    int limit = width;
                    for (int i = 0; i < limit; i++) {
                        boolean named = sourceOutputNameKnown(source, i)
                                && i < names.size();
                        if (named) {
                            String displayName = names.get(i);
                            String sourceColumn = effectiveOutputName(source, i);
                            if (sourceColumn == null) {
                                sourceColumn = displayName;
                            }
                            String outputName;
                            String outputKey;
                            Boolean quoted = null;
                            String sourceKey = i < source.outputKeys.size()
                                    && source.outputKeys.get(i) != null
                                            ? source.outputKeys.get(i)
                                            : identifierKey(displayName);
                            quoted = !sourceKey.equals(identifierKey(displayName));
                            if (declared != null && position + i < declared.size()) {
                                outputName = declared.get(position + i);
                                outputKey = declaredKeys != null
                                        && position + i < declaredKeys.size()
                                                ? declaredKeys.get(position + i)
                                                : identifierKey(outputName);
                            } else {
                                outputName = displayName;
                                outputKey = sourceKey;
                            }
                            setTargetOutput(target, position + i, outputName, outputKey);
                            markOutputNameKnown(target, position + i, true);
                            addOuterColumnMapping(source, sourceColumn, target,
                                    outputName, position + i, quoted);
                        }
                        for (WildcardRef ref : wildcardRefsAt(source, i)) {
                            Instruction wildcard = new Instruction(
                                    OperationType.COLUMNS_INJECT, ref.name, target,
                                    position + i, ref.temporary,
                                    state.tempTables.contains(target));
                            wildcard.setPositionalWildcard(true);
                            wildcard.setWildcardWidthKnown(true);
                            wildcard.setWildcardWidth(1);
                            instructions.add(wildcard);
                            recordWildcardSpan(target, position + i,
                                    position + i + 1, ref.name, ref.temporary);
                            state.targetWildcardContribution.put(target, Boolean.TRUE);
                        }
                    }
                    return limit;
                }
                List<WildcardSpan> spans = wildcardSpansOf(source.displayName);
                int knownPrefix = 0;
                for (int i = 0; i < names.size() && sourceOutputNameKnown(source, i);
                        i++) {
                    String name = names.get(i);
                    String outputName;
                    String outputKey;
                    Boolean quoted = Boolean.FALSE;
                    if (declared != null && position + i < declared.size()) {
                        outputName = declared.get(position + i);
                        outputKey = declaredKeys != null
                                && position + i < declaredKeys.size()
                                        ? declaredKeys.get(position + i)
                                        : identifierKey(outputName);
                        quoted = !outputKey.equals(identifierKey(outputName));
                    } else {
                        outputName = name;
                        outputKey = i < source.outputKeys.size()
                                && source.outputKeys.get(i) != null
                                        ? source.outputKeys.get(i)
                                        : identifierKey(name);
                        quoted = !outputKey.equals(identifierKey(name));
                    }
                    String sourceColumn = effectiveOutputName(source, i);
                    if (sourceColumn == null) {
                        sourceColumn = name;
                    }
                    setTargetOutput(target, position + i, outputName, outputKey);
                    markOutputNameKnown(target, position + i, true);
                    addOuterColumnMapping(source, sourceColumn, target, outputName,
                            position + i, quoted);
                    knownPrefix = i + 1;
                }
                if (!spans.isEmpty()) {
                    for (WildcardSpan span : spans) {
                        Instruction wildcard = new Instruction(
                                OperationType.COLUMNS_INJECT, span.source, target,
                                position + span.start, span.temporary,
                                state.tempTables.contains(target));
                        wildcard.setPositionalWildcard(true);
                        if (span.end != Integer.MAX_VALUE) {
                            wildcard.setWildcardWidthKnown(true);
                            wildcard.setWildcardWidth(span.end - span.start);
                        }
                        instructions.add(wildcard);
                        recordWildcardSpan(target, position + span.start,
                                span.end == Integer.MAX_VALUE ? Integer.MAX_VALUE
                                        : position + span.end,
                                span.source, span.temporary);
                        state.targetWildcardContribution.put(target, Boolean.TRUE);
                    }
                } else {
                    Instruction wildcard = new Instruction(OperationType.COLUMNS_INJECT,
                            lineageTableName(source), target, position,
                            source.temporary, state.tempTables.contains(target));
                    wildcard.setPositionalWildcard(true);
                    recordWildcardSpan(target, position, Integer.MAX_VALUE,
                            lineageTableName(source), source.temporary);
                    instructions.add(wildcard);
                    state.targetWildcardContribution.put(target, Boolean.TRUE);
                }
                int minimum = Math.max(Math.max(knownPrefix, source.minOutputColumns),
                        maxSpanEnd(spans));
                return -Math.max(minimum, 1);
            }
            int width = sourceWidthKnown(source) ? sourceKnownWidth(source) : -1;
            if (width > 0 && sourceColumnNamesKnown(source)) {
                for (int i = 0; i < Math.min(width, names.size()); i++) {
                    String sourceColumn = names.get(i);
                    String outputName;
                    String outputKey;
                    Boolean quoted = null;
                    if (declared != null && position + i < declared.size()) {
                        outputName = declared.get(position + i);
                        outputKey = declaredKeys != null
                                && position + i < declaredKeys.size()
                                        ? declaredKeys.get(position + i)
                                        : identifierKey(outputName);
                    } else if (source.metadataTable != null
                            && i < source.metadataTable.getFields().size()) {
                        Column column = source.metadataTable.getFields().get(i);
                        quoted = column.isQuoted();
                        outputName = column.getColumnName();
                        outputKey = quoted && currentDialect() != DbType.mysql
                                && currentDialect() != DbType.mariadb
                                        ? column.getColumnName()
                                        : identifierKey(column.getColumnName());
                    } else {
                        outputName = sourceColumn;
                        outputKey = i < source.outputKeys.size()
                                && source.outputKeys.get(i) != null
                                        ? source.outputKeys.get(i)
                                        : identifierKey(sourceColumn);
                        quoted = !outputKey.equals(identifierKey(sourceColumn));
                    }
                    setTargetOutput(target, position + i, outputName, outputKey);
                    markOutputNameKnown(target, position + i, true);
                    addOuterColumnMapping(source, sourceColumn, target, outputName,
                            position + i, quoted);
                }
                return Math.max(width, 1);
            }
            Instruction wildcard = new Instruction(OperationType.COLUMNS_INJECT,
                    lineageTableName(source), target, position,
                    source.temporary, state.tempTables.contains(target));
            wildcard.setPositionalWildcard(true);
            if (width > 0) {
                wildcard.setWildcardWidthKnown(true);
                wildcard.setWildcardWidth(width);
                recordWildcardSpan(target, position, position + width,
                        lineageTableName(source), source.temporary);
            } else {
                recordWildcardSpan(target, position, Integer.MAX_VALUE,
                        lineageTableName(source), source.temporary);
            }
            instructions.add(wildcard);
            state.targetWildcardContribution.put(target, Boolean.TRUE);
            return width > 0 ? width : -1;
        }
        SourceNode binding = correlated == null ? null : correlated.binding;
        if (binding != null) {
            if (!binding.widthKnown) {
                for (SourceTable candidate : binding.sources) {
                    addOuterWildcardOpen(candidate, target, position);
                }
                return -Math.max(binding.minWidth, 1);
            }
            if (hasAmbiguousOutput(binding.plan, binding.width)) {
                for (SourceTable candidate : binding.sources) {
                    Instruction wildcard = new Instruction(
                            OperationType.COLUMNS_INJECT,
                            lineageTableName(candidate), target, position,
                            candidate.temporary, state.tempTables.contains(target));
                    wildcard.setPositionalWildcard(true);
                    wildcard.setWildcardWidthKnown(true);
                    wildcard.setWildcardWidth(Math.max(binding.width, 1));
                    instructions.add(wildcard);
                    recordWildcardSpan(target, position,
                            position + Math.max(binding.width, 1),
                            lineageTableName(candidate), candidate.temporary);
                    state.targetWildcardContribution.put(target, Boolean.TRUE);
                }
                return binding.width;
            }
            boolean allNamesKnown = nodeNamesKnown(binding);
            List<String> declared = currentTargetColumnAliases(target);
            List<String> declaredKeys = currentDeclaredKeys(target);
            Set<SourceTable> represented = new HashSet<>();
            int pos = position;
            for (StarOutput output : binding.plan) {
                if (output.sources.isEmpty()) {
                    continue;
                }
                StarColumn first = output.sources.get(0);
                boolean precise = allNamesKnown && output.nameKnown && output.stable;
                for (StarColumn column : output.sources) {
                    if (!column.nameKnown) {
                        precise = false;
                    }
                }
                String outputName;
                String outputKey;
                if (declared != null && pos < declared.size()) {
                    outputName = declared.get(pos);
                    outputKey = declaredKeys != null && pos < declaredKeys.size()
                            ? declaredKeys.get(pos) : identifierKey(outputName);
                    precise = precise && first.nameKnown;
                } else if (precise) {
                    outputName = first.column;
                    outputKey = first.key != null ? first.key
                            : identifierKey(first.column);
                } else {
                    outputName = "col" + (pos + 1);
                    outputKey = identifierKey(outputName);
                }
                setTargetOutput(target, pos, outputName, outputKey);
                if (precise || declared != null && pos < declared.size()) {
                    markOutputNameKnown(target, pos, true);
                }
                for (StarColumn column : output.sources) {
                    represented.add(column.source);
                    String sourceColumn = column.column;
                    if (declared != null && column.source.temporary
                            && column.index >= 0) {
                        String effective = effectiveOutputName(column.source,
                                column.index);
                        if (effective != null) {
                            sourceColumn = effective;
                        }
                    }
                    if (precise && column.nameKnown) {
                        addOuterColumnMapping(column.source, sourceColumn, target,
                                outputName, pos, column.quoted);
                    } else if (column.source.temporary
                            && !wildcardSpansOf(column.source.displayName).isEmpty()) {
                        int rel = column.index >= 0 ? column.index : 0;
                        for (WildcardRef ref : wildcardSourcesAt(column.source,
                                column, rel)) {
                            Instruction wildcard = new Instruction(
                                    OperationType.COLUMNS_INJECT, ref.name, target,
                                    pos, ref.temporary, state.tempTables.contains(target));
                            wildcard.setPositionalWildcard(true);
                            wildcard.setWildcardWidthKnown(true);
                            wildcard.setWildcardWidth(1);
                            instructions.add(wildcard);
                            recordWildcardSpan(target, pos, pos + 1, ref.name,
                                    ref.temporary);
                            state.targetWildcardContribution.put(target, Boolean.TRUE);
                        }
                    } else {
                        addOuterWildcard(column.source, target, pos);
                    }
                }
                pos++;
            }
            if (!allNamesKnown) {
                for (SourceTable candidate : binding.sources) {
                    if (!represented.contains(candidate)) {
                        addOuterWildcardOpen(candidate, target, pos);
                    }
                }
            }
            return Math.max(binding.width, pos - position);
        }
        throw new MetadataNotFoundException(star.getOwner() == null
                ? "*" : star.getOwner().toString());
    }

    private void recordOuterColumn(SQLPropertyExpr property, String target,
            String targetName, int position) {
        String rawName = property.getName();
        CorrelatedOwner correlated = findCorrelatedOwner(property.getOwner());
        SourceTable source = correlated == null ? null : correlated.source;
        if (source != null) {
            String column = resolveOuterColumn(source, rawName);
            Boolean quoted = MetadataCatalog.isQuotedIdentifier(rawName) ? Boolean.TRUE
                    : null;
            addOuterColumnMapping(source, column, target, targetName, position,
                    quoted);
            return;
        }
        SourceNode binding = correlated == null ? null : correlated.binding;
        if (binding != null) {
            String key = identifierKey(rawName);
            List<StarOutput> matchedOutputs = new ArrayList<>();
            for (StarOutput output : binding.plan) {
                for (StarColumn column : output.sources) {
                    if (key != null && key.equals(column.key)) {
                        matchedOutputs.add(output);
                        break;
                    }
                }
            }
            if (matchedOutputs.size() > 1) {
                List<SourceTable> owners = new ArrayList<>();
                for (StarOutput output : matchedOutputs) {
                    for (StarColumn column : output.sources) {
                        if (!owners.contains(column.source)) {
                            owners.add(column.source);
                        }
                    }
                }
                throw new AmbiguousColumnException(normalizeIdentifier(rawName),
                        displayNames(owners));
            }
            if (matchedOutputs.size() == 1) {
                for (StarColumn column : matchedOutputs.get(0).sources) {
                    addOuterColumnMapping(column.source, column.column, target,
                            targetName, position, column.quoted);
                }
                return;
            }
            if (nodeNamesKnown(binding)) {
                throw new ColumnNotFoundException(normalizeIdentifier(rawName),
                        displayNames(binding.sources));
            }
            for (SourceTable candidate : binding.sources) {
                addOuterWildcard(candidate, target, position);
            }
            return;
        }
        throw new MetadataNotFoundException(property.getOwner() == null
                ? "*" : property.getOwner().toString());
    }

    private void addOuterColumnMapping(SourceTable source, String column,
            String target, String targetName, int position) {
        addOuterColumnMapping(source, column, target, targetName, position, null);
    }

    private void addOuterColumnMapping(SourceTable source, String column,
            String target, String targetName, int position, Boolean quoted) {
        if (source.metadataTable == null && !source.temporary) {
            DbType dialect = currentDialect();
            if (dialect == DbType.hive || dialect == DbType.antspark) {
                // Planned columns are already logical; strip only a backtick
                // layer, fold case, and let rendering re-quote special names.
                column = hivePlannedColumn(column);
                quoted = null;
            } else if (quoted == null) {
                column = uncatalogedColumnSpelling(column);
            }
        }
        String rendered = quoted == null
                ? renderIdentifier(column)
                : quoted ? quoteIdentifierValue(column)
                        : renderIdentifier(column);
        instructions.add(new Instruction(OperationType.COLUMN_MAPPING,
                lineageTableName(source) + "." + rendered,
                target + "." + renderIdentifier(targetName), position,
                source.temporary, state.tempTables.contains(target)));
        if (source.wildcardContribution && source.temporary) {
            int relative = -1;
            String key = quoted != null && quoted
                    ? identifierKey(quoteIdentifierValue(column))
                    : identifierKey(column);
            for (int i = 0; i < source.outputKeys.size(); i++) {
                if (key != null && key.equals(source.outputKeys.get(i))) {
                    relative = i;
                    break;
                }
            }
            List<WildcardSpan> spans = relative < 0
                    ? java.util.Collections.<WildcardSpan>emptyList()
                    : wildcardSpansOf(source.displayName);
            for (WildcardSpan span : spans) {
                if (relative < span.start || relative >= span.end) {
                    continue;
                }
                // Only the wildcard covering the selected column contributes.
                addOuterWildcard(span.source, target, position, span.temporary);
            }
        }
    }

    private void addOuterWildcard(String sourceName, String target, int position,
            boolean temporary) {
        Instruction wildcard = new Instruction(OperationType.COLUMNS_INJECT,
                sourceName, target, position, temporary,
                state.tempTables.contains(target));
        wildcard.setPositionalWildcard(true);
        wildcard.setWildcardWidthKnown(true);
        wildcard.setWildcardWidth(1);
        instructions.add(wildcard);
        recordWildcardSpan(target, position, position + 1, sourceName, temporary);
        state.targetWildcardContribution.put(target, Boolean.TRUE);
    }

    private void addOuterWildcardOpen(SourceTable source, String target,
            int position) {
        Instruction wildcard = new Instruction(OperationType.COLUMNS_INJECT,
                lineageTableName(source), target, position,
                source.temporary, state.tempTables.contains(target));
        wildcard.setPositionalWildcard(true);
        instructions.add(wildcard);
        state.targetWildcardContribution.put(target, Boolean.TRUE);
        recordWildcardSpan(target, position, Integer.MAX_VALUE,
                lineageTableName(source), source.temporary);
    }

    private void addOuterWildcard(SourceTable source, String target, int position) {
        Instruction wildcard = new Instruction(OperationType.COLUMNS_INJECT,
                lineageTableName(source), target, position,
                source.temporary, state.tempTables.contains(target));
        wildcard.setPositionalWildcard(true);
        wildcard.setWildcardWidthKnown(true);
        wildcard.setWildcardWidth(1);
        instructions.add(wildcard);
        state.targetWildcardContribution.put(target, Boolean.TRUE);
        recordWildcardSpan(target, position, position + 1,
                lineageTableName(source), source.temporary);
    }

    private void recordOuterIdentifier(String rawName, String target,
            String targetName, int position) {
        String key = identifierKey(rawName);
        Map<String, List<SourceTable>> using = state.stackUsing.isEmpty()
                ? null : state.stackUsing.peek();
        if (using != null && using.containsKey(key)) {
            List<SourceTable> participants = using.get(key);
            List<SourceTable> sides = participantsForCurrentUsing(key);
            List<SourceTable> extras = new ArrayList<>();
            for (SourceTable source : currentSources()) {
                if (participants.contains(source) || sides.contains(source)) {
                    continue;
                }
                if (ownsOuterColumn(source, key)
                        || !sourceColumnNamesKnown(source)) {
                    extras.add(source);
                }
            }
            if (!extras.isEmpty()) {
                List<SourceTable> all = new ArrayList<>(participants);
                all.addAll(extras);
                throw new AmbiguousColumnException(normalizeIdentifier(rawName),
                        displayNames(all));
            }
            for (SourceTable source : participants) {
                String column = resolveOuterColumn(source, rawName);
                addOuterColumnMapping(source, column, target, targetName, position);
            }
            return;
        }
        List<StarOutput> matchedOutputs = new ArrayList<>();
        List<StarOutput> planned = state.stackStarPlan.isEmpty()
                ? java.util.Collections.<StarOutput>emptyList()
                : state.stackStarPlan.peek();
        for (StarOutput output : planned) {
            for (StarColumn column : output.sources) {
                if (key != null && key.equals(column.key)) {
                    matchedOutputs.add(output);
                    break;
                }
            }
        }
        if (matchedOutputs.size() > 1) {
            List<SourceTable> owners = new ArrayList<>();
            for (StarOutput output : matchedOutputs) {
                for (StarColumn column : output.sources) {
                    if (!owners.contains(column.source)) {
                        owners.add(column.source);
                    }
                }
            }
            throw new AmbiguousColumnException(normalizeIdentifier(rawName),
                    displayNames(owners));
        }
        if (matchedOutputs.size() == 1) {
            List<SourceTable> participants = currentUsingParticipants(key);
            List<SourceTable> matchedSources = new ArrayList<>();
            for (StarColumn column : matchedOutputs.get(0).sources) {
                if (!matchedSources.contains(column.source)) {
                    matchedSources.add(column.source);
                }
            }
            List<SourceTable> owners = new ArrayList<>(matchedSources);
            for (SourceTable source : currentSources()) {
                if (participants.contains(source) || matchedSources.contains(source)) {
                    continue;
                }
                if (ownsOuterColumn(source, key)
                        || !sourceColumnNamesKnown(source)) {
                    owners.add(source);
                }
            }
            if (owners.size() > matchedSources.size()) {
                throw new AmbiguousColumnException(normalizeIdentifier(rawName),
                        displayNames(owners));
            }
            for (StarColumn column : matchedOutputs.get(0).sources) {
                addOuterColumnMapping(column.source, column.column, target,
                        targetName, position, column.quoted);
            }
            return;
        }
        List<SourceTable> owners = new ArrayList<>();
        List<SourceTable> unknown = new ArrayList<>();
        for (SourceTable source : currentSources()) {
            if (ownsOuterColumn(source, key)) {
                owners.add(source);
            } else if (!sourceColumnNamesKnown(source)) {
                unknown.add(source);
            }
        }
        if (owners.size() > 1) {
            throw new AmbiguousColumnException(normalizeIdentifier(rawName),
                    displayNames(owners));
        }
        if (owners.size() == 1) {
            if (!unknown.isEmpty()) {
                List<SourceTable> all = new ArrayList<>(owners);
                all.addAll(unknown);
                throw new AmbiguousColumnException(normalizeIdentifier(rawName),
                        displayNames(all));
            }
            Boolean quoted = MetadataCatalog.isQuotedIdentifier(rawName) ? Boolean.TRUE
                    : null;
            addOuterColumnMapping(owners.get(0),
                    resolveOuterColumn(owners.get(0), rawName), target, targetName,
                    position, quoted);
            return;
        }
        if (unknown.size() == 1) {
            addOuterWildcard(unknown.get(0), target, position);
            return;
        }
        if (unknown.size() > 1) {
            throw new AmbiguousColumnException(normalizeIdentifier(rawName),
                    displayNames(unknown));
        }
        if (hasOuterScopeFallback()) {
            List<SourceTable> ancestors = resolveCorrelatedUnqualified(rawName);
            List<SourceTable> mapped = new ArrayList<>();
            Boolean quoted = MetadataCatalog.isQuotedIdentifier(rawName) ? Boolean.TRUE
                    : null;
            for (SourceTable source : ancestors) {
                String column = resolveOuterColumn(source, rawName);
                addOuterColumnMapping(source, column, target, targetName, position,
                        quoted);
                mapped.add(source);
            }
            if (!mapped.isEmpty()) {
                return;
            }
        }
        throw new ColumnNotFoundException(normalizeIdentifier(rawName),
                displayNames(currentSources()));
    }

    private List<SourceTable> participantsForCurrentUsing(String key) {
        if (state.stackUsing.isEmpty() || state.usingParticipantsByIdentity.isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, List<SourceTable>> using = state.stackUsing.peek();
        if (using == null) {
            return new ArrayList<>();
        }
        Map<String, List<SourceTable>> sides = state.usingParticipantsByIdentity.get(using);
        if (sides == null || !sides.containsKey(key)) {
            return new ArrayList<>();
        }
        return new ArrayList<>(sides.get(key));
    }

    private List<SourceTable> currentUsingParticipants(String key) {
        if (state.stackUsing.isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, List<SourceTable>> using = state.stackUsing.peek();
        if (using == null || state.usingParticipantsByIdentity.isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, List<SourceTable>> participants = state.usingParticipantsByIdentity.get(using);
        if (participants == null || !participants.containsKey(key)) {
            return new ArrayList<>();
        }
        return new ArrayList<>(participants.get(key));
    }

    private boolean ownsOuterColumn(SourceTable source, String key) {
        if (key == null) {
            return false;
        }
        if (source.metadataTable != null) {
            for (int i = 0; i < source.metadataTable.getFields().size(); i++) {
                Column column = source.metadataTable.getFields().get(i);
                String columnKey = column.isQuoted()
                        && currentDialect() != DbType.mysql
                        && currentDialect() != DbType.mariadb
                                ? column.getColumnName()
                                : identifierKey(column.getColumnName());
                if (key.equals(columnKey)) {
                    return true;
                }
            }
            return false;
        }
        if (!source.outputNamesKnown) {
            return false;
        }
        return source.outputKeys.contains(key);
    }

    private boolean sourceColumnNamesKnown(SourceTable source) {
        return source.metadataTable != null || source.outputNamesKnown;
    }

    private String resolveOuterColumn(SourceTable source, String rawName) {
        if (source.metadataTable != null) {
            String resolved = MetadataCatalog.resolveColumnName(source.metadataTable,
                    rawName, currentDialect());
            if (resolved == null) {
                throw new ColumnNotFoundException(normalizeIdentifier(rawName),
                        displayNames(visibleSources()));
            }
            return resolved;
        }
        String key = identifierKey(rawName);
        int found = -1;
        for (int i = 0; i < source.outputKeys.size(); i++) {
            if (key != null && key.equals(source.outputKeys.get(i))) {
                if (found >= 0) {
                    throw new AmbiguousColumnException(normalizeIdentifier(rawName),
                            displayNames(visibleSources()));
                }
                found = i;
            }
        }
        if (source.outputNamesKnown) {
            if (found >= 0) {
                return found < source.outputColumns.size()
                        ? source.outputColumns.get(found)
                        : normalizeIdentifier(rawName);
            }
            throw new ColumnNotFoundException(normalizeIdentifier(rawName),
                    displayNames(visibleSources()));
        }
        if (found >= 0) {
            return found < source.outputColumns.size()
                    ? source.outputColumns.get(found)
                    : normalizeIdentifier(rawName);
        }
        return normalizeIdentifier(rawName);
    }

    /**
     * Record the tables an unnamed scalar subquery reads as a table-level claim on
     * the target. When the written column is known (UPDATE/VALUES), the claim is
     * bound to that column; otherwise it occupies one projection position.
     */
    void recordScalarTableLevel(ScalarSubquery inner, String targetTable,
            int position, String targetColumn) {
        Set<String> tables = new LinkedHashSet<>();
        String prefix = inner.temp + ".";
        for (Instruction instruction : instructions) {
            String to = instruction.getTo();
            if (to == null) {
                continue;
            }
            String base = to.split("\\(")[0];
            if (!inner.temp.equals(base) && !to.startsWith(prefix)) {
                continue;
            }
            String from = instruction.getFrom();
            if (from == null) {
                continue;
            }
            String table;
            if (instruction.getOperation() == OperationType.COLUMNS_INJECT
                    || instruction.getOperation() == OperationType.TABLE_MAPPING) {
                // The wildcard keeps the whole table identity (including schema).
                table = from;
            } else {
                int dot = from.lastIndexOf('.');
                table = dot >= 0 ? from.substring(0, dot) : from;
            }
            if (!table.isEmpty()) {
                tables.add(table);
            }
        }
        for (String table : tables) {
            if (targetColumn != null && !state.tempTables.contains(targetTable)) {
                String rendered = targetColumn;
                Instruction edge = new Instruction(OperationType.COLUMNS_INJECT,
                        table, targetTable + "(" + rendered + ")", -1, false, false);
                edge.setPositionalWildcard(true);
                edge.setWildcardTable(targetTable);
                edge.setPopulatedColumns(rendered);
                instructions.add(edge);
            } else {
                Instruction wildcard = new Instruction(OperationType.COLUMNS_INJECT,
                        table, targetTable, position, false,
                        state.tempTables.contains(targetTable));
                wildcard.setPositionalWildcard(true);
                wildcard.setWildcardWidthKnown(true);
                wildcard.setWildcardWidth(1);
                instructions.add(wildcard);
                recordWildcardSpan(targetTable, position, position + 1, table, false);
                state.targetWildcardContribution.put(targetTable, Boolean.TRUE);
            }
        }
    }

    private int processScalarSubqueryProjection(SQLSelectItem item, SQLQueryExpr expr,
            int position) {
        String targetTable = state.stackTargetTable.peek();
        int savedOrdinal = state.currentOrdinal;
        state.currentOrdinal = position;
        try {
            return processScalarSubqueryProjection(item, expr);
        } finally {
            state.currentOrdinal = savedOrdinal;
        }
    }

    private int processScalarSubqueryProjection(SQLSelectItem item, SQLQueryExpr expr) {
        String targetTable = state.stackTargetTable.peek();
        ScalarSubquery inner = analyzeScalarSubquery(expr);
        if (inner == null) {
            return 1;
        }
        if (inner.columns.isEmpty()) {
            recordScalarTableLevel(inner, state.stackTargetTable.peek(), state.currentOrdinal, null);
            return 1;
        }
        List<String> declaredColumns = currentTargetColumnAliases(targetTable);
        String lockedName = lockedOutputName(targetTable, state.currentOrdinal);
        String lockedKey = lockedOutputKey(targetTable, state.currentOrdinal);
        String targetColumn;
        String targetKey;
        if (lockedName != null) {
            targetColumn = lockedName;
            targetKey = lockedKey != null ? lockedKey : identifierKey(lockedName);
        } else if (declaredColumns != null && state.currentOrdinal >= 0
                && state.currentOrdinal < declaredColumns.size()) {
            targetColumn = declaredColumns.get(state.currentOrdinal);
            targetKey = identifierKey(targetColumn);
        } else if (item.getAlias() != null) {
            targetColumn = normalizeIdentifier(item.getAlias());
            targetKey = identifierKey(item.getAlias());
        } else {
            targetColumn = inner.columns.get(0);
            targetKey = identifierKey(targetColumn);
        }
        setTargetOutput(targetTable, state.currentOrdinal, targetColumn, targetKey);
        markOutputNameKnown(targetTable, state.currentOrdinal, true);
        if (state.uncertainOrdinal >= 0 && state.currentOrdinal == state.uncertainOrdinal) {
            // The position follows an unknown-width wildcard, so no edge may be
            // pinned to a column, exactly like an ordinary projection there.
            return 1;
        }
        for (int i = 0; i < inner.columns.size(); i++) {
            String source = inner.references.get(i);
            instructions.add(new Instruction(OperationType.COLUMN_MAPPING, source,
                    targetTable + "." + renderIdentifier(targetColumn), state.currentOrdinal,
                    true, state.tempTables.contains(targetTable)));
        }
        return 1;
    }

    boolean containsScalarSubquery(SQLExpr expr) {
        if (expr == null) {
            return false;
        }
        if (expr instanceof SQLQueryExpr) {
            return true;
        }
        if (expr instanceof SQLBinaryOpExpr) {
            SQLBinaryOpExpr binary = (SQLBinaryOpExpr) expr;
            return containsScalarSubquery(binary.getLeft())
                    || containsScalarSubquery(binary.getRight());
        }
        if (expr instanceof SQLMethodInvokeExpr) {
            for (SQLExpr argument : ((SQLMethodInvokeExpr) expr).getArguments()) {
                if (containsScalarSubquery(argument)) {
                    return true;
                }
            }
            return false;
        }
        if (expr instanceof SQLCastExpr) {
            return containsScalarSubquery(((SQLCastExpr) expr).getExpr());
        }
        if (expr instanceof SQLNotExpr) {
            return containsScalarSubquery(((SQLNotExpr) expr).getExpr());
        }
        if (expr instanceof SQLUnaryExpr) {
            return containsScalarSubquery(((SQLUnaryExpr) expr).getExpr());
        }
        if (expr instanceof SQLBetweenExpr) {
            SQLBetweenExpr between = (SQLBetweenExpr) expr;
            return containsScalarSubquery(between.getTestExpr())
                    || containsScalarSubquery(between.getBeginExpr())
                    || containsScalarSubquery(between.getEndExpr());
        }
        if (expr instanceof SQLCaseExpr) {
            SQLCaseExpr caseExpr = (SQLCaseExpr) expr;
            if (containsScalarSubquery(caseExpr.getValueExpr())
                    || containsScalarSubquery(caseExpr.getElseExpr())) {
                return true;
            }
            if (caseExpr.getItems() != null) {
                for (SQLCaseExpr.Item caseItem : caseExpr.getItems()) {
                    if (containsScalarSubquery(caseItem.getConditionExpr())
                            || containsScalarSubquery(caseItem.getValueExpr())) {
                        return true;
                    }
                }
            }
            return false;
        }
        if (expr instanceof SQLListExpr) {
            for (SQLExpr element : ((SQLListExpr) expr).getItems()) {
                if (containsScalarSubquery(element)) {
                    return true;
                }
            }
            return false;
        }
        if (expr instanceof SQLInListExpr) {
            SQLInListExpr in = (SQLInListExpr) expr;
            if (containsScalarSubquery(in.getExpr())) {
                return true;
            }
            if (in.getTargetList() != null) {
                for (SQLExpr element : in.getTargetList()) {
                    if (containsScalarSubquery(element)) {
                        return true;
                    }
                }
            }
            return false;
        }
        if (expr instanceof SQLInSubQueryExpr) {
            return containsScalarSubquery(((SQLInSubQueryExpr) expr).getExpr());
        }
        if (expr instanceof SQLExtractExpr) {
            return containsScalarSubquery(((SQLExtractExpr) expr).getValue());
        }
        return false;
    }

    private void recordUnboundScalarMappings(SQLSelectItem item, int position) {
        String targetTable = state.stackTargetTable.peek();
        List<String> outputs = copyOutputs(state.targetOutputs.get(targetTable));
        String name = outputs != null && position < outputs.size() && outputs.get(position) != null
                ? outputs.get(position) : "col" + (position + 1);
        recordUnboundScalarExpression(item.getExpr(), targetTable, name, position);
    }

    private void recordUnboundScalarExpression(SQLExpr expr, String targetTable,
            String targetName, int position) {
        if (expr == null) {
            return;
        }
        if (expr instanceof SQLQueryExpr) {
            ScalarSubquery inner = analyzeScalarSubquery((SQLQueryExpr) expr);
            if (inner == null) {
                return;
            }
            if (inner.columns.isEmpty()) {
                recordScalarTableLevel(inner, targetTable, position, null);
                return;
            }
            instructions.add(new Instruction(OperationType.COLUMN_MAPPING,
                    inner.references.get(0),
                    targetTable + "." + renderIdentifier(targetName), position, true,
                    state.tempTables.contains(targetTable)));
            return;
        }
        if (expr instanceof SQLBinaryOpExpr) {
            SQLBinaryOpExpr binary = (SQLBinaryOpExpr) expr;
            recordUnboundScalarExpression(binary.getLeft(), targetTable, targetName,
                    position);
            recordUnboundScalarExpression(binary.getRight(), targetTable, targetName,
                    position);
        } else if (expr instanceof SQLMethodInvokeExpr) {
            for (SQLExpr argument : ((SQLMethodInvokeExpr) expr).getArguments()) {
                recordUnboundScalarExpression(argument, targetTable, targetName,
                        position);
            }
        } else if (expr instanceof SQLCastExpr) {
            recordUnboundScalarExpression(((SQLCastExpr) expr).getExpr(), targetTable,
                    targetName, position);
        } else if (expr instanceof SQLNotExpr) {
            recordUnboundScalarExpression(((SQLNotExpr) expr).getExpr(), targetTable,
                    targetName, position);
        } else if (expr instanceof SQLUnaryExpr) {
            recordUnboundScalarExpression(((SQLUnaryExpr) expr).getExpr(), targetTable,
                    targetName, position);
        } else if (expr instanceof SQLBetweenExpr) {
            SQLBetweenExpr between = (SQLBetweenExpr) expr;
            recordUnboundScalarExpression(between.getTestExpr(), targetTable, targetName,
                    position);
            recordUnboundScalarExpression(between.getBeginExpr(), targetTable,
                    targetName, position);
            recordUnboundScalarExpression(between.getEndExpr(), targetTable, targetName,
                    position);
        } else if (expr instanceof SQLCaseExpr) {
            SQLCaseExpr caseExpr = (SQLCaseExpr) expr;
            recordUnboundScalarExpression(caseExpr.getValueExpr(), targetTable,
                    targetName, position);
            if (caseExpr.getItems() != null) {
                for (SQLCaseExpr.Item caseItem : caseExpr.getItems()) {
                    recordUnboundScalarExpression(caseItem.getConditionExpr(),
                            targetTable, targetName, position);
                    recordUnboundScalarExpression(caseItem.getValueExpr(), targetTable,
                            targetName, position);
                }
            }
            recordUnboundScalarExpression(caseExpr.getElseExpr(), targetTable,
                    targetName, position);
        } else if (expr instanceof SQLListExpr) {
            for (SQLExpr element : ((SQLListExpr) expr).getItems()) {
                recordUnboundScalarExpression(element, targetTable, targetName,
                        position);
            }
        } else if (expr instanceof SQLInListExpr) {
            SQLInListExpr in = (SQLInListExpr) expr;
            recordUnboundScalarExpression(in.getExpr(), targetTable, targetName,
                    position);
            if (in.getTargetList() != null) {
                for (SQLExpr element : in.getTargetList()) {
                    recordUnboundScalarExpression(element, targetTable, targetName,
                            position);
                }
            }
        } else if (expr instanceof SQLInSubQueryExpr) {
            recordUnboundScalarExpression(((SQLInSubQueryExpr) expr).getExpr(),
                    targetTable, targetName, position);
        } else if (expr instanceof SQLExtractExpr) {
            recordUnboundScalarExpression(((SQLExtractExpr) expr).getValue(),
                    targetTable, targetName, position);
        }
    }

    private int recordUnboundSelectItem(SQLSelectItem item, int position) {
        if (item.getExpr() == null) {
            return 0;
        }
        SQLExpr base = item.getExpr();
        while (base instanceof SQLCastExpr) {
            base = ((SQLCastExpr) base).getExpr();
        }
        String targetTable = state.stackTargetTable.peek();
        String lockedName = lockedOutputName(targetTable, position);
        String lockedKey = lockedOutputKey(targetTable, position);
        if (lockedName != null) {
            setTargetOutput(targetTable, position, lockedName,
                    lockedKey != null ? lockedKey : identifierKey(lockedName));
            return 1;
        }
        List<String> declared = currentTargetColumnAliases(targetTable);
        List<String> declaredKeys = currentDeclaredKeys(targetTable);
        String name;
        String key;
        if (declared != null && position < declared.size()) {
            name = declared.get(position);
            key = declaredKeys != null && position < declaredKeys.size()
                    ? declaredKeys.get(position) : identifierKey(name);
            setTargetOutput(targetTable, position, name, key);
            markOutputNameKnown(targetTable, position, true);
            return 1;
        }
        if (item.getAlias() != null) {
            name = normalizeIdentifier(item.getAlias());
            key = identifierKey(item.getAlias());
        } else if (base instanceof SQLIdentifierExpr) {
            name = normalizeIdentifier(((SQLIdentifierExpr) base).getName());
            key = identifierKey(((SQLIdentifierExpr) base).getName());
        } else if (base instanceof SQLPropertyExpr && !isStarProperty(base)) {
            name = normalizeIdentifier(((SQLPropertyExpr) base).getName());
            key = identifierKey(((SQLPropertyExpr) base).getName());
        } else {
            name = item.getExpr().toString();
            key = identifierKey(name);
        }
        setTargetOutput(state.stackTargetTable.peek(), position, name, key);
        markOutputNameKnown(state.stackTargetTable.peek(), position, true);
        return 1;
    }

    /**
     * The output of an analyzed scalar subquery: the temporary that holds it and the
     * names of its columns.
     */
/**
     * Analyze a scalar subquery as a one-column derived table so its own lineage is
     * kept instead of being folded into the enclosing expression.
     */
    ScalarSubquery analyzeScalarSubquery(SQLQueryExpr expr) {
        SQLSelect select = expr.getSubQuery();
        if (select == null) {
            return null;
        }
        String target = getTempTableName();
        state.tempTables.add(target);
        int savedOrdinal = state.currentOrdinal;
        int savedUncertain = state.uncertainOrdinal;
        int savedBudget = state.currentStarBudget;
        boolean savedInTarget = state.inTargetExpression;
        state.scalarSubqueryDepth++;
        try {
            state.stackTargetTable.push(target);
            processSelect(select);
            state.stackTargetTable.pop();
        } finally {
            state.scalarSubqueryDepth--;
            state.currentOrdinal = savedOrdinal;
            state.uncertainOrdinal = savedUncertain;
            state.currentStarBudget = savedBudget;
            state.inTargetExpression = savedInTarget;
        }
        recordTargetShape(target);
        List<String> outputs = copyOutputs(state.targetOutputs.get(target));
        boolean widthKnown = Boolean.TRUE.equals(state.targetWidthKnown.get(target));
        int minimum = targetMinimumColumns(target, widthKnown, outputs.size());
        int width = Math.max(outputs.size(), minimum);
        if (width > 1) {
            throw new IllegalStateException(
                    "Scalar subquery must return one column: " + width);
        }
        List<String> columns = new ArrayList<>();
        for (String output : outputs) {
            columns.add(output);
        }
        // Reuse the inner instructions' own spelling of each column, so a quoted or
        // dotted name keeps its boundaries on both sides.
        List<String> references = new ArrayList<>();
        String prefix = target + ".";
        for (Instruction instruction : instructions) {
            if (instruction.getOperation() != OperationType.COLUMN_MAPPING
                    && instruction.getOperation() != OperationType.EXPR_MAPPING) {
                continue;
            }
            String to = instruction.getTo();
            if (to != null && to.startsWith(prefix)) {
                references.add(to);
            }
        }
        while (references.size() < columns.size()) {
            references.add(prefix + columns.get(references.size()));
        }
        return new ScalarSubquery(target, columns, references);
    }

    private int processSelectItem(SQLSelectItem item) {
        if (item.getExpr() == null) {
            return 0;
        }
        SQLExpr baseExpr = item.getExpr();
        while (baseExpr instanceof SQLCastExpr) {
            baseExpr = ((SQLCastExpr) baseExpr).getExpr();
        }
        if (baseExpr instanceof SQLQueryExpr) {
            return processScalarSubqueryProjection(item, (SQLQueryExpr) baseExpr,
                    state.currentOrdinal);
        }

        String targetTable = state.stackTargetTable.peek();
        List<String> declaredColumns = currentTargetColumnAliases(targetTable);

        String lockedName = lockedOutputName(targetTable, state.currentOrdinal);
        String lockedKey = lockedOutputKey(targetTable, state.currentOrdinal);

        String targetColumn;
        boolean identifierTarget;
        if (lockedName != null) {
            targetColumn = lockedName;
            identifierTarget = true;
        } else if (declaredColumns != null && state.currentOrdinal >= 0
                && state.currentOrdinal < declaredColumns.size()) {
            targetColumn = declaredColumns.get(state.currentOrdinal);
            identifierTarget = true;
        } else if (item.getAlias() != null) {
            targetColumn = normalizeIdentifier(item.getAlias());
            identifierTarget = true;
        } else if (baseExpr instanceof SQLIdentifierExpr) {
            targetColumn = normalizeIdentifier(((SQLIdentifierExpr) baseExpr).getName());
            identifierTarget = true;
        } else if (baseExpr instanceof SQLPropertyExpr && !isStarProperty(baseExpr)) {
            targetColumn = normalizeIdentifier(((SQLPropertyExpr) baseExpr).getName());
            identifierTarget = true;
        } else {
            targetColumn = item.getExpr().toString();
            identifierTarget = false;
        }
        if (identifierTarget) {
            targetColumn = renderIdentifier(targetColumn);
        }

        if (item.getExpr() instanceof SQLAllColumnExpr) {
            if (detachedMetadata()) {
                int width = emitDetachedStar(targetTable, currentSources(), state.currentOrdinal);
                return Math.max(width, 1);
            }
            Map<String, List<SourceTable>> using = state.stackUsing.isEmpty()
                    ? null : state.stackUsing.peek();
            SourceNode node = state.stackSourceNode.isEmpty() ? null : state.stackSourceNode.peek();
            if (context == null && node != null && !node.plan.isEmpty()
                    && (using != null && !using.isEmpty() || state.currentStarBudget > 0)) {
                recordBindingStar(targetTable, node, state.currentOrdinal);
            } else {
                recordStarOutputs(targetTable, currentSources(), state.currentOrdinal);
            }
        } else if (isStarProperty(item.getExpr())) {
            SQLExpr owner = ((SQLPropertyExpr) item.getExpr()).getOwner();
            CorrelatedOwner correlated = findCorrelatedOwner(owner);
            SourceTable source = correlated == null ? null : correlated.source;
            SourceNode binding = correlated == null ? null : correlated.binding;
            if (detachedMetadata()) {
                List<StarOutput> plan;
                if (source != null) {
                    plan = planFromSource(source);
                } else {
                    if (binding == null) {
                        throw new MetadataNotFoundException(owner == null
                                ? "*" : owner.toString());
                    }
                    plan = binding.plan;
                }
                List<SourceTable> planSources = new ArrayList<>();
                if (source != null) {
                    planSources.add(source);
                } else {
                    planSources.addAll(binding.sources);
                }
                SourceNode planNode = source != null ? null : binding;
                int width = emitDetachedPlan(targetTable, plan, planSources, planNode,
                        state.currentOrdinal);
                return Math.max(width, 1);
            }
            if (source != null) {
                SourceNode expanded = state.currentExpandedSourceNodes.get(source);
                SourceNode leafNode = state.stackSourceNode.isEmpty()
                        ? null : state.stackSourceNode.peek();
                if (expanded != null) {
                    recordBindingStar(targetTable, expanded, state.currentOrdinal);
                } else if (context == null && state.currentStarBudget > 0 && leafNode != null
                        && leafNode.sources.size() == 1
                        && leafNode.sources.get(0) == source
                        && !sourceColumnsKnown(source)) {
                    recordBindingStar(targetTable, leafNode, state.currentOrdinal);
                } else {
                    recordSourceStarOutputs(targetTable, source, state.currentOrdinal);
                }
            } else if (binding != null) {
                recordBindingStar(targetTable, binding, state.currentOrdinal);
            }
        } else {
            List<String> declaredKeys = currentDeclaredKeys(targetTable);
            String targetKey;
            if (lockedKey != null) {
                targetKey = lockedKey;
            } else if (declaredKeys != null && state.currentOrdinal < declaredKeys.size()) {
                targetKey = declaredKeys.get(state.currentOrdinal);
            } else if (item.getAlias() != null) {
                targetKey = identifierKey(item.getAlias());
            } else if (baseExpr instanceof SQLIdentifierExpr) {
                targetKey = identifierKey(((SQLIdentifierExpr) baseExpr).getName());
            } else if (baseExpr instanceof SQLPropertyExpr) {
                targetKey = identifierKey(((SQLPropertyExpr) baseExpr).getName());
            } else {
                targetKey = identifierKey(item.getExpr().toString());
            }
            setTargetOutput(targetTable, state.currentOrdinal, normalizeIdentifier(targetColumn),
                    targetKey);
            if (lockedName == null) {
                markOutputNameKnown(targetTable, state.currentOrdinal, true);
            }
        }

        state.stackTargetColumn.push(targetColumn);
        int before = instructions.size();
        state.inTargetExpression = true;
        visitExpr(item.getExpr());
        state.inTargetExpression = false;
        boolean uncertain = state.uncertainOrdinal >= 0 && state.currentOrdinal == state.uncertainOrdinal;
        if (instructions.size() == before && !uncertain) {
            instructions.add(new Instruction(OperationType.EXPR_MAPPING,
                    item.getExpr().toString(), targetTable + "." + targetColumn,
                    state.currentOrdinal, false, state.tempTables.contains(targetTable)));
        }
        state.stackTargetColumn.pop();

        return outputColumnCount(item.getExpr());
    }

    private int outputColumnCount(SQLExpr expr) {
        if (expr instanceof SQLAllColumnExpr) {
            Map<String, List<SourceTable>> using = state.stackUsing.isEmpty()
                    ? null : state.stackUsing.peek();
            if (!state.stackSourceNode.isEmpty()) {
                SourceNode node = state.stackSourceNode.peek();
                if (node.widthKnown) {
                    return Math.max(node.width, 1);
                }
                // With USING the visible plan already merges keys, so its size is the
                // best known position count even when names are incomplete.
                if (using != null && !using.isEmpty() && !node.plan.isEmpty()) {
                    return Math.max(node.plan.size(), 1);
                }
            }
            if (useVisiblePlan(currentSources(), using)) {
                return Math.max(currentStarPlan(currentSources()).size(), 1);
            }
            int count = 0;
            for (SourceTable source : currentSources()) {
                count += columnCount(source);
            }
            return Math.max(count, 1);
        }
        if (isStarProperty(expr)) {
            SQLExpr owner = ((SQLPropertyExpr) expr).getOwner();
            SourceTable source = findByOwner(owner);
            if (source != null) {
                return Math.max(columnCount(source), 1);
            }
            SourceNode binding = joinBinding(owner);
            if (binding != null) {
                return binding.widthKnown
                        ? Math.max(binding.width, 1) : Math.max(binding.plan.size(), 1);
            }
            return 1;
        }
        return 1;
    }

    private int minimumOutputColumns(SQLExpr expr) {
        if (expr instanceof SQLAllColumnExpr) {
            if (!state.stackSourceNode.isEmpty()) {
                SourceNode node = state.stackSourceNode.peek();
                return node.widthKnown ? node.width : node.minWidth;
            }
            Map<String, List<SourceTable>> using = state.stackUsing.isEmpty()
                    ? null : state.stackUsing.peek();
            if (useVisiblePlan(currentSources(), using)) {
                return currentStarPlan(currentSources()).size();
            }
            int minimum = 0;
            for (SourceTable source : currentSources()) {
                minimum += minimumColumns(source);
            }
            return minimum;
        }
        if (isStarProperty(expr)) {
            SQLExpr owner = ((SQLPropertyExpr) expr).getOwner();
            SourceTable source = findByOwner(owner);
            if (source != null) {
                return minimumColumns(source);
            }
            SourceNode binding = joinBinding(owner);
            if (binding != null) {
                return binding.widthKnown ? binding.width : binding.minWidth;
            }
            return 0;
        }
        return 1;
    }

    private int minimumColumns(SourceTable source) {
        if (source.metadataTable != null) {
            return source.metadataTable.getFields().size();
        }
        if (sourceColumnsKnown(source)) {
            return source.outputColumns.size();
        }
        return source.minOutputColumns;
    }

    private boolean starOutputWidthKnown(SQLExpr expr) {
        if (expr instanceof SQLAllColumnExpr) {
            if (!state.stackSourceNode.isEmpty()) {
                return state.stackSourceNode.peek().widthKnown;
            }
            List<SourceTable> sources = currentSources();
            if (sources.isEmpty()) {
                return false;
            }
            for (SourceTable source : sources) {
                if (!source.outputWidthKnown) {
                    return false;
                }
            }
            return true;
        }
        if (isStarProperty(expr)) {
            SQLExpr owner = ((SQLPropertyExpr) expr).getOwner();
            SourceTable source = findByOwner(owner);
            if (source != null) {
                if (source.metadataTable != null || source.outputWidthKnown) {
                    return true;
                }
                return source.temporary
                        && Boolean.TRUE.equals(state.targetWidthKnown.get(source.displayName));
            }
            SourceNode binding = joinBinding(owner);
            return binding != null && binding.widthKnown;
        }
        return true;
    }

    private boolean outputWidthKnown(SQLSelectItem item) {
        SQLExpr expr = item.getExpr();
        if (expr instanceof SQLAllColumnExpr || isStarProperty(expr)) {
            // A wildcard's width can be known even when its column names are not.
            return starOutputWidthKnown(expr);
        }
        return true;
    }

    private boolean outputNamesKnown(SQLSelectItem item) {
        SQLExpr expr = item.getExpr();
        if (detachedMetadata() && hasUnknownStarSource(expr)) {
            return false;
        }
        if (expr instanceof SQLAllColumnExpr) {
            if (!state.stackSourceNode.isEmpty()) {
                return bindingNamesKnown(state.stackSourceNode.peek());
            }
            for (SourceTable source : currentSources()) {
                if (!sourceNamesKnown(source)) {
                    return false;
                }
            }
            return true;
        }
        if (isStarProperty(expr)) {
            SQLExpr owner = ((SQLPropertyExpr) expr).getOwner();
            SourceTable source = findByOwner(owner);
            if (source != null) {
                return sourceNamesKnown(source);
            }
            SourceNode binding = joinBinding(owner);
            return binding != null && bindingNamesKnown(binding);
        }
        return true;
    }

    private boolean bindingNamesKnown(SourceNode binding) {
        if (!binding.widthKnown || binding.plan.size() != binding.width) {
            return false;
        }
        for (StarOutput output : binding.plan) {
            if (!output.nameKnown || !output.stable) {
                return false;
            }
        }
        return true;
    }

    private boolean sourceColumnsKnown(SourceTable source) {
        return source.metadataTable != null || source.outputWidthKnown;
    }

    private boolean hasUnknownStarSource(SQLExpr expr) {
        if (expr instanceof SQLAllColumnExpr) {
            for (SourceTable source : currentSources()) {
                if (!sourceNamesKnown(source)) {
                    return true;
                }
            }
            return false;
        }
        if (isStarProperty(expr)) {
            SourceTable source = findByOwner(((SQLPropertyExpr) expr).getOwner());
            if (source != null) {
                return !sourceNamesKnown(source);
            }
            SourceNode binding = joinBinding(((SQLPropertyExpr) expr).getOwner());
            if (binding != null) {
                for (SourceTable candidate : binding.sources) {
                    if (!sourceNamesKnown(candidate)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean sourceNamesKnown(SourceTable source) {
        return source.metadataTable != null || source.outputNamesKnown;
    }

    private boolean sourceOutputNameKnown(SourceTable source, int position) {
        if (source.metadataTable != null) {
            return true;
        }
        return position >= 0 && position < source.outputNameKnown.size()
                && Boolean.TRUE.equals(source.outputNameKnown.get(position));
    }

    private int columnCount(SourceTable source) {
        if (source.metadataTable != null) {
            return Math.max(source.metadataTable.getFields().size(), 1);
        }
        if (!source.outputColumns.isEmpty()) {
            return source.outputColumns.size();
        }
        return 1;
    }

    /* ------------------------------------------------------------------ */
    /* sources                                                            */
    /* ------------------------------------------------------------------ */

    SourceNode buildSourceNode(SQLTableSource tableSource,
            Map<String, List<SourceTable>> using) {
        if (tableSource instanceof SQLJoinTableSource) {
            SQLJoinTableSource join = (SQLJoinTableSource) tableSource;
            Map<String, SourceNode> bindingsBefore = state.stackJoinBindings.isEmpty() ? null
                    : new HashMap<>(state.stackJoinBindings.peek());
            SourceNode left = buildSourceNode(join.getLeft(), using);
            boolean lateralRight = isLateralSource(join.getRight());
            if (lateralRight) {
                // A correlated LATERAL body can read the left side's columns,
                // bindings, and hidden-name set.
                state.stackSources.push(left.sources);
                state.lateralScopes.push(Boolean.TRUE);
                state.stackUsing.push(using);
                state.stackStarPlan.push(left.plan);
                state.stackSourceNode.push(left);
                state.stackSourceTable.push(left.sources.isEmpty()
                        ? "" : left.sources.get(0).displayName);
                state.stackHiddenSources.push(state.stackHiddenSources.isEmpty()
                        ? new HashSet<SourceTable>()
                        : new HashSet<>(state.stackHiddenSources.peek()));
                state.stackJoinBindings.push(state.stackJoinBindings.isEmpty()
                        ? new HashMap<String, SourceNode>()
                        : new HashMap<>(state.stackJoinBindings.peek()));
            }
            SourceNode right;
            try {
                right = buildSourceNode(join.getRight(), using);
            } finally {
                if (lateralRight) {
                    state.stackJoinBindings.pop();
                    state.stackHiddenSources.pop();
                    state.stackSourceTable.pop();
                    state.stackSourceNode.pop();
                    state.stackStarPlan.pop();
                    state.stackUsing.pop();
                    state.stackSources.pop();
                    state.lateralScopes.pop();
                }
            }
            List<SourceTable> sources = new ArrayList<>();
            sources.addAll(left.sources);
            sources.addAll(right.sources);
            List<StarOutput> plan;
            int leftUsingBound = 0;
            int rightUsingBound = 0;
            if (join.getUsing() != null && !join.getUsing().isEmpty()) {
                int usingCount = join.getUsing().size();
                Set<String> leftKnownKeys = knownOutputKeys(left);
                Set<String> rightKnownKeys = knownOutputKeys(right);
                int leftRequired = knownOutputPositionCount(left);
                int rightRequired = knownOutputPositionCount(right);
                String missingKey = join.getUsing().get(0).toString();
                for (SQLExpr expr : join.getUsing()) {
                    String key = identifierKey(expr.toString());
                    if (!leftKnownKeys.contains(key)) {
                        leftRequired++;
                    }
                    if (!rightKnownKeys.contains(key)) {
                        rightRequired++;
                        missingKey = expr.toString();
                    }
                }
                leftUsingBound = Math.max(usingCount, leftRequired);
                rightUsingBound = Math.max(usingCount, rightRequired);
                if (left.widthKnown && left.width < leftUsingBound
                        || right.widthKnown && right.width < rightUsingBound) {
                    throw new ColumnNotFoundException(
                            normalizeIdentifier(missingKey), displayNames(sources));
                }
                boolean leftNamesKnown = nodeNamesKnown(left);
                boolean rightNamesKnown = nodeNamesKnown(right);
                Set<String> usingKeys = new HashSet<>();
                for (SQLExpr expr : join.getUsing()) {
                    String column = identifierKey(expr.toString());
                    if (!usingKeys.add(column)) {
                        throw new ColumnNotFoundException(
                                normalizeIdentifier(expr.toString()),
                                displayNames(sources));
                    }
                    if (leftNamesKnown && planKeyCount(left.plan, column) != 1
                            || rightNamesKnown && planKeyCount(right.plan, column) != 1) {
                        throw new ColumnNotFoundException(
                                normalizeIdentifier(expr.toString()),
                                displayNames(sources));
                    }
                    if (!leftNamesKnown && planKnownKeyCount(left.plan, column) > 1
                            || !rightNamesKnown
                                    && planKnownKeyCount(right.plan, column) > 1) {
                        throw new AmbiguousColumnException(
                                normalizeIdentifier(expr.toString()),
                                displayNames(sources));
                    }
                }
                plan = mergeStarPlan(left.plan, right.plan, join.getJoinType(),
                        join.getUsing(), leftNamesKnown, rightNamesKnown,
                        left.sources, right.sources, left.widthKnown);
                Map<String, List<SourceTable>> participants =
                        state.usingParticipantsByIdentity.computeIfAbsent(using,
                                key -> new HashMap<String, List<SourceTable>>());
                for (SQLExpr expr : join.getUsing()) {
                    String column = identifierKey(expr.toString());
                    using.put(column, planSourcesForKey(plan, column));
                    List<SourceTable> sides = new ArrayList<>(
                            planAllSourcesForKey(left.plan, column));
                    addSources(sides, planAllSourcesForKey(right.plan, column));
                    if (!leftNamesKnown) {
                        addUnknownSources(sides, left.sources);
                    }
                    if (!rightNamesKnown) {
                        addUnknownSources(sides, right.sources);
                    }
                    participants.put(column, sides);
                }
            } else {
                plan = new ArrayList<>();
                plan.addAll(left.plan);
                for (StarOutput output : right.plan) {
                    plan.add(left.widthKnown ? output : output.withStable(false));
                }
            }
            int ownUsingCount = join.getUsing() == null ? 0 : join.getUsing().size();
            int joinUsingCount = left.usingCount + right.usingCount + ownUsingCount;
            boolean widthKnown = left.widthKnown && right.widthKnown;
            int width = widthKnown ? left.width + right.width - ownUsingCount : 0;
            // Each side must at least contain the USING keys, even when its exact
            // width is unknown.
            int leftMin = Math.max(left.minWidth, leftUsingBound);
            int rightMin = Math.max(right.minWidth, rightUsingBound);
            int minWidth = Math.max(leftMin + rightMin - ownUsingCount, 0);
            List<SourceTable> leftUnknownSources = new ArrayList<>();
            for (SourceTable source : left.sources) {
                if (!sourceColumnsKnown(source)) {
                    leftUnknownSources.add(source);
                }
            }
            List<SourceTable> rightUnknownSources = new ArrayList<>();
            for (SourceTable source : right.sources) {
                if (!sourceColumnsKnown(source)) {
                    rightUnknownSources.add(source);
                }
            }
            int rightRemaining = Math.max(
                    right.plan.size() - Math.min(right.plan.size(), ownUsingCount), 0);
            int rightBoundary = Math.max(plan.size() - rightRemaining, 0);
            Map<SourceTable, Integer> unknownSectionEnd = new HashMap<>();
            int leftRemaining = countRemaining(plan, left.plan);
            int rightRemainingCount = countRemaining(plan, right.plan);
            int mergedPrefix = Math.max(
                    plan.size() - leftRemaining - rightRemainingCount, 0);
            for (SourceTable source : left.sources) {
                if (!sourceColumnsKnown(source)) {
                    Integer previous = left.unknownSectionEnd.get(source);
                    int end = previous == null ? plan.size()
                            : mergedPrefix + keptBefore(plan, left.plan, previous);
                    unknownSectionEnd.put(source, ownUsingCount > 0 ? end
                            : previous != null ? previous : left.plan.size());
                }
            }
            for (SourceTable source : right.sources) {
                if (!sourceColumnsKnown(source)) {
                    Integer previous = right.unknownSectionEnd.get(source);
                    int end = previous == null ? plan.size()
                            : mergedPrefix + leftRemaining
                                    + keptBefore(plan, right.plan, previous);
                    unknownSectionEnd.put(source, ownUsingCount > 0 ? end
                            : previous != null ? left.plan.size() + previous
                                    : plan.size());
                }
            }
            SourceNode joined = new SourceNode(sources, plan,
                    left.knownColumns && right.knownColumns, widthKnown, width, minWidth,
                    joinUsingCount, rightBoundary, leftUnknownSources, rightUnknownSources,
                    unknownSectionEnd, left, right);
            if (join.getAlias() != null) {
                if (!state.stackJoinBindings.isEmpty()) {
                    Map<String, SourceNode> bindings = state.stackJoinBindings.peek();
                    if (bindingsBefore != null) {
                        // Restore every binding the subtree changed, then expose only
                        // this join's alias.
                        Set<String> keys = new HashSet<>(bindings.keySet());
                        keys.addAll(bindingsBefore.keySet());
                        for (String key : keys) {
                            SourceNode previous = bindingsBefore.get(key);
                            if (previous != null) {
                                bindings.put(key, previous);
                            } else {
                                bindings.remove(key);
                            }
                        }
                    }
                    bindings.put(identifierKey(join.getAlias()), joined);
                    state.tableSourceBindings.put(join, joined);
                }
                if (!state.stackHiddenSources.isEmpty()) {
                    // An alias hides every qualifier inside its join subtree.
                    state.stackHiddenSources.peek().addAll(sources);
                }
            }
            return joined;
        }
        if (tableSource instanceof SQLExprTableSource
                && queryExpressionSelect(
                        ((SQLExprTableSource) tableSource).getExpr()) != null) {
            // Druid parses LATERAL (SELECT ...) as a method-call expression.
            SQLExprTableSource lateral = (SQLExprTableSource) tableSource;
            SQLSelect body = queryExpressionSelect(lateral.getExpr());
            String alias = lateral.getAlias();
            String target = alias != null ? uniqueTarget(alias) : getTempTableName();
            state.tempTables.add(target);
            processDerivedBody(target, body, lateral.getColumns());
            List<String> derivedOutputs = copyOutputs(state.targetOutputs.get(target));
            boolean widthKnown = isOutputWidthKnown(target, derivedOutputs);
            boolean namesKnown = isOutputNamesKnown(target, derivedOutputs);
            int derivedWidth = derivedWidth(target, derivedOutputs, widthKnown);
            SourceTable source = new SourceTable(target, null, null, alias, null, true,
                    widthKnown, namesKnown, derivedOutputs, copyKeys(target),
                    copyNameKnown(target, derivedOutputs.size()),
                    targetMinimumColumns(target, widthKnown, derivedOutputs.size()),
                    copyUnknownGroups(target, derivedOutputs.size()), false,
                    hasWildcardContribution(target));
            return new SourceNode(java.util.Collections.singletonList(source),
                    planFromSource(source), namesKnown, widthKnown, derivedWidth,
                    targetMinimumColumns(target, widthKnown, derivedWidth), 0,
                    derivedWidth,
                    widthKnown ? java.util.Collections.<SourceTable>emptyList()
                            : java.util.Collections.singletonList(source),
                    java.util.Collections.<SourceTable>emptyList(),
                    unknownSectionEndFor(source, widthKnown, planSizeFor(source)));
        }
        if (tableSource instanceof SQLSubqueryTableSource) {
            SQLSubqueryTableSource subquery = (SQLSubqueryTableSource) tableSource;
            String alias = subquery.getAlias();
            String target = alias != null ? uniqueTarget(alias) : getTempTableName();
            state.tempTables.add(target);
            processDerivedBody(target, subquery.getSelect(), subquery.getColumns());
            List<String> derivedOutputs = copyOutputs(state.targetOutputs.get(target));
            boolean widthKnown = isOutputWidthKnown(target, derivedOutputs);
            boolean namesKnown = isOutputNamesKnown(target, derivedOutputs);
            int derivedWidth = derivedWidth(target, derivedOutputs, widthKnown);
            SourceTable source = new SourceTable(target, null, null, alias, null, true,
                    widthKnown, namesKnown, derivedOutputs, copyKeys(target),
                    copyNameKnown(target, derivedOutputs.size()),
                    targetMinimumColumns(target, widthKnown, derivedOutputs.size()),
                    copyUnknownGroups(target, derivedOutputs.size()), false,
                    hasWildcardContribution(target));
            return new SourceNode(java.util.Collections.singletonList(source),
                    planFromSource(source), namesKnown, widthKnown, derivedWidth,
                    targetMinimumColumns(target, widthKnown, derivedWidth), 0,
                    derivedWidth,
                    widthKnown ? java.util.Collections.<SourceTable>emptyList()
                            : java.util.Collections.singletonList(source),
                    java.util.Collections.<SourceTable>emptyList(),
                    unknownSectionEndFor(source, widthKnown, planSizeFor(source)));
        }
        if (tableSource instanceof SQLUnionQueryTableSource) {
            SQLUnionQueryTableSource union = (SQLUnionQueryTableSource) tableSource;
            String alias = union.getAlias();
            String target = alias != null ? uniqueTarget(alias) : getTempTableName();
            state.tempTables.add(target);
            processDerivedUnion(target, union.getUnion(), union.getColumns());
            List<String> derivedOutputs = copyOutputs(state.targetOutputs.get(target));
            boolean widthKnown = isOutputWidthKnown(target, derivedOutputs);
            boolean namesKnown = isOutputNamesKnown(target, derivedOutputs);
            int derivedWidth = derivedWidth(target, derivedOutputs, widthKnown);
            SourceTable source = new SourceTable(target, null, null, alias, null, true,
                    widthKnown, namesKnown, derivedOutputs, copyKeys(target),
                    copyNameKnown(target, derivedOutputs.size()),
                    targetMinimumColumns(target, widthKnown, derivedOutputs.size()),
                    copyUnknownGroups(target, derivedOutputs.size()), false,
                    hasWildcardContribution(target));
            return new SourceNode(java.util.Collections.singletonList(source),
                    planFromSource(source), namesKnown, widthKnown, derivedWidth,
                    targetMinimumColumns(target, widthKnown, derivedWidth), 0,
                    derivedWidth,
                    widthKnown ? java.util.Collections.<SourceTable>emptyList()
                            : java.util.Collections.singletonList(source),
                    java.util.Collections.<SourceTable>emptyList(),
                    unknownSectionEndFor(source, widthKnown, planSizeFor(source)));
        }
        if (tableSource instanceof SQLExprTableSource) {
            SourceTable source = resolveSource((SQLExprTableSource) tableSource);
            boolean widthKnown = sourceWidthKnown(source);
            int width = source.metadataTable != null
                    ? source.metadataTable.getFields().size() : sourceKnownWidth(source);
            return new SourceNode(java.util.Collections.singletonList(source),
                    planFromSource(source), sourceNamesKnown(source), widthKnown, width,
                    minimumColumns(source), 0, width,
                    widthKnown ? java.util.Collections.<SourceTable>emptyList()
                            : java.util.Collections.singletonList(source),
                    java.util.Collections.<SourceTable>emptyList(),
                    unknownSectionEndFor(source, widthKnown, planSizeFor(source)));
        }
        return new SourceNode(new ArrayList<SourceTable>(), new ArrayList<StarOutput>(),
                false, false, 0, 0, 0, 0, java.util.Collections.<SourceTable>emptyList(),
                java.util.Collections.<SourceTable>emptyList(),
                new HashMap<SourceTable, Integer>());
    }

    private void processDerivedBody(String target, SQLSelect select,
            List<com.alibaba.druid.sql.ast.SQLName> columns) {
        pushDerivedAliases(target, columns);
        try {
            state.stackTargetTable.push(target);
            processSelect(select);
            state.stackTargetTable.pop();
            recordTargetShape(target);
        } finally {
            state.targetColumnAliases.pop();
        }
    }

    private void processDerivedUnion(String target, SQLUnionQuery union,
            List<com.alibaba.druid.sql.ast.SQLName> columns) {
        pushDerivedAliases(target, columns);
        try {
            state.stackTargetTable.push(target);
            processSelectQuery(union);
            state.stackTargetTable.pop();
            recordTargetShape(target);
        } finally {
            state.targetColumnAliases.pop();
        }
    }

    private void recordTargetShape(String target) {
        boolean widthKnown = state.lastQueryBlockWidthKnown;
        boolean namesKnown = state.lastQueryBlockNamesKnown;
        int width = state.lastQueryBlockWidth;
        List<String> declared = currentTargetColumnAliases(target);
        if (declared != null) {
            DbType dialect = currentDialect();
            boolean exactCount = dialect == DbType.mysql || dialect == DbType.mariadb;
            if (widthKnown) {
                boolean mismatch = exactCount
                        ? declared.size() != width
                        : declared.size() > width;
                if (mismatch) {
                    throw new IllegalStateException("Column alias count mismatch: "
                            + declared.size() + " vs " + width);
                }
                // A declared column list that covers every output position names them
                // all, even when the first UNION branch's names were unknown.
                if (!namesKnown && declared.size() == width) {
                    namesKnown = true;
                }
                fillDeclaredOutputs(target, Math.min(width, declared.size()));
            } else if (exactCount) {
                // MySQL/MariaDB require an explicit list to match the projection, so
                // the declaration itself fixes both width and names.
                if (declared.size() < state.lastQueryBlockMinWidth) {
                    throw new IllegalStateException("Column alias count mismatch: "
                            + declared.size() + " vs at least "
                            + state.lastQueryBlockMinWidth);
                }
                widthKnown = true;
                namesKnown = true;
                width = declared.size();
                fillDeclaredOutputs(target, declared.size());
            }
        }
        state.targetWidthKnown.put(target, widthKnown);
        state.targetNamesKnown.put(target, namesKnown);
        state.targetMinWidth.put(target, widthKnown ? width : state.lastQueryBlockMinWidth);
    }

    private void fillDeclaredOutputs(String target, int width) {
        List<String> declared = currentTargetColumnAliases(target);
        if (declared == null) {
            return;
        }
        List<String> declaredKeys = currentDeclaredKeys(target);
        List<String> outputs = state.targetOutputs.get(target);
        for (int i = 0; i < width; i++) {
            if (outputs != null && i < outputs.size() && outputs.get(i) != null) {
                continue;
            }
            String name = declared.get(i);
            String key = declaredKeys != null && i < declaredKeys.size()
                    ? declaredKeys.get(i) : identifierKey(name);
            setTargetOutput(target, i, name, key);
            markOutputNameKnown(target, i, true);
        }
    }

    private void pushDerivedAliases(String target,
            List<com.alibaba.druid.sql.ast.SQLName> columns) {
        List<String> displays = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        if (columns != null) {
            for (com.alibaba.druid.sql.ast.SQLName column : columns) {
                String raw = column.toString();
                displays.add(canonicalIdentifier(raw));
                keys.add(identifierKey(raw));
            }
        }
        Map<String, List<String>> aliases = new HashMap<>();
        if (!displays.isEmpty()) {
            aliases.put(target, displays);
            state.cteDeclaredColumns.put(target, displays);
            state.cteDeclaredKeys.put(target, keys);
        }
        state.targetColumnAliases.push(aliases);
    }

    private List<StarOutput> planFromSource(SourceTable source) {
        List<String> names = sourceColumnNames(source);
        List<String> keys = sourceColumnKeys(source);
        int width = names.size();
        if (sourceWidthKnown(source)) {
            width = Math.max(width, sourceKnownWidth(source));
        }
        List<StarOutput> plan = new ArrayList<>();
        for (int i = 0; i < width; i++) {
            if (i >= names.size()) {
                names = new ArrayList<>(names);
                for (int j = names.size(); j <= i; j++) {
                    names.add("col" + (j + 1));
                }
            }
            String key = i < keys.size() ? keys.get(i) : identifierKey(names.get(i));
            boolean nameKnown = sourceOutputNameKnown(source, i);
            boolean quoted = source.metadataTable != null
                    && i < source.metadataTable.getFields().size()
                    && source.metadataTable.getFields().get(i).isQuoted()
                    || source.metadataTable == null && key != null
                            && !key.equals(identifierKey(names.get(i)));
            boolean stable = sourceColumnsKnown(source) || i < firstUnknownIndex(source);
            plan.add(new StarOutput(java.util.Collections.singletonList(
                    new StarColumn(source, names.get(i), key, nameKnown, quoted, i)),
                    nameKnown, stable));
        }
        return plan;
    }

    private List<String> sourceColumnKeys(SourceTable source) {
        if (source.metadataTable != null) {
            List<String> keys = new ArrayList<>();
            DbType dialect = currentDialect();
            boolean hiveFamily = dialect == DbType.hive
                    || dialect == DbType.antspark;
            boolean caseInsensitive = dialect == DbType.mysql
                    || dialect == DbType.mariadb || hiveFamily;
            for (Column column : source.metadataTable.getFields()) {
                if (hiveFamily) {
                    // The catalog name is already logical: do not strip a second
                    // quote layer from a name that literally contains quotes.
                    keys.add(MetadataCatalog.caseFold(column.getColumnName()));
                } else {
                    keys.add(!caseInsensitive && column.isQuoted()
                            ? column.getColumnName()
                            : identifierKey(column.getColumnName()));
                }
            }
            return keys;
        }
        if (!source.outputColumns.isEmpty()) {
            return source.outputKeys;
        }
        return java.util.Collections.singletonList(identifierKey("col1"));
    }

    private List<StarOutput> mergeStarPlan(List<StarOutput> left, List<StarOutput> right,
            SQLJoinTableSource.JoinType type, List<SQLExpr> using,
            boolean leftKnown, boolean rightKnown, List<SourceTable> leftSources,
            List<SourceTable> rightSources, boolean leftWidthKnown) {
        List<StarOutput> leftPlan = new ArrayList<>(left);
        List<StarOutput> rightPlan = new ArrayList<>(right);
        List<StarOutput> merged = new ArrayList<>();
        boolean ambiguousMerge = false;
        for (SQLExpr expr : using) {
            String key = identifierKey(expr.toString());
            if (!leftKnown && !hasUniqueUsingColumn(leftPlan, key)
                    || !rightKnown && !hasUniqueUsingColumn(rightPlan, key)) {
                // More than one candidate column on an unnamed side: which position the
                // USING key occupies cannot be determined, so no position may be fixed.
                ambiguousMerge = true;
            }
            StarOutput leftColumn = removeFirstMatching(leftPlan, key);
            StarOutput rightColumn = removeFirstMatching(rightPlan, key);
            // When one side's output names are unknown, the USING column still owns a
            // position on that side even if its placeholder was already consumed.
            if (leftColumn == null && !leftKnown) {
                boolean certain = singleSourceCertain(leftSources, key);
                leftColumn = removeFirstUnknown(leftPlan, expr, key, certain);
                if (leftColumn == null) {
                    leftColumn = unknownColumn(leftSources, expr, key, certain);
                }
            }
            if (rightColumn == null && !rightKnown) {
                boolean certain = singleSourceCertain(rightSources, key);
                rightColumn = removeFirstUnknown(rightPlan, expr, key, certain);
                if (rightColumn == null) {
                    rightColumn = unknownColumn(rightSources, expr, key, certain);
                }
            }
            List<StarColumn> sources = new ArrayList<>();
            switch (type) {
                case LEFT_OUTER_JOIN:
                case LEFT_SEMI_JOIN:
                case LEFT_ANTI_JOIN:
                    // LEFT JOIN keeps the left side's value for the merged column.
                    if (leftColumn != null) {
                        sources.addAll(leftColumn.sources);
                    }
                    break;
                case RIGHT_OUTER_JOIN:
                    if (rightColumn != null) {
                        sources.addAll(rightColumn.sources);
                    }
                    break;
                default:
                    if (leftColumn != null) {
                        sources.addAll(leftColumn.sources);
                    }
                    if (rightColumn != null) {
                        sources.addAll(rightColumn.sources);
                    }
                    break;
            }
            if (!sources.isEmpty()) {
                // The visible name of a USING column is given by the join condition.
                merged.add(new StarOutput(sources, true));
            }
        }
        merged.addAll(leftPlan);
        for (StarOutput output : rightPlan) {
            // Right-side positions shift when the left subtree's width is unknown.
            merged.add(leftWidthKnown ? output : output.withStable(false));
        }
        if (ambiguousMerge) {
            for (int i = 0; i < merged.size(); i++) {
                merged.set(i, merged.get(i).withAmbiguous(true));
            }
        }
        return merged;
    }

    private static boolean hasUniqueUsingColumn(List<StarOutput> plan, String key) {
        int known = 0;
        int unknown = 0;
        for (StarOutput output : plan) {
            if (!output.nameKnown) {
                unknown++;
                continue;
            }
            for (StarColumn column : output.sources) {
                if (key.equals(column.key)) {
                    known++;
                    break;
                }
            }
        }
        return known == 1 || known == 0 && unknown == 1;
    }

    private static boolean sameUnknownGroup(SourceTable source) {
        SourceTable group = null;
        boolean seen = false;
        for (int i = 0; i < source.outputNameKnown.size(); i++) {
            if (Boolean.TRUE.equals(source.outputNameKnown.get(i))) {
                continue;
            }
            SourceTable current = i < source.outputUnknownGroups.size()
                    ? source.outputUnknownGroups.get(i) : null;
            if (current == null) {
                return false;
            }
            if (!seen) {
                group = current;
                seen = true;
            } else if (group != current) {
                return false;
            }
        }
        return seen;
    }

    private static boolean containsSource(List<SourceTable> sources, SourceTable source) {
        for (SourceTable candidate : sources) {
            if (candidate == source) {
                return true;
            }
        }
        return false;
    }

    private static int totalWildcardOccurrences(SourceTable source) {
        int total = 0;
        for (int run : unknownRegionRuns(source)) {
            total += run;
        }
        return total;
    }

    private static List<Integer> unknownRegionRuns(SourceTable source) {
        List<Integer> runs = new ArrayList<>();
        int current = 0;
        for (int i = 0; i < source.outputNameKnown.size(); i++) {
            if (!Boolean.TRUE.equals(source.outputNameKnown.get(i))) {
                current++;
            } else if (current > 0) {
                runs.add(current);
                current = 0;
            }
        }
        if (current > 0) {
            runs.add(current);
        }
        return runs;
    }

    private static List<Integer> unknownRegionEnds(SourceTable source) {
        List<Integer> ends = new ArrayList<>();
        boolean inRegion = false;
        for (int i = 0; i < source.outputNameKnown.size(); i++) {
            boolean known = Boolean.TRUE.equals(source.outputNameKnown.get(i));
            if (!known) {
                inRegion = true;
            } else if (inRegion) {
                ends.add(i);
                inRegion = false;
            }
        }
        if (inRegion) {
            ends.add(source.outputNameKnown.size());
        }
        return ends;
    }

    private static int firstUnknownIndex(SourceTable source) {
        for (int i = 0; i < source.outputNameKnown.size(); i++) {
            if (!Boolean.TRUE.equals(source.outputNameKnown.get(i))) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    private static int unknownRegionCount(SourceTable source) {
        int regions = 0;
        boolean previousKnown = true;
        for (int i = 0; i < source.outputNameKnown.size(); i++) {
            boolean known = Boolean.TRUE.equals(source.outputNameKnown.get(i));
            if (!known && previousKnown) {
                regions++;
            }
            previousKnown = known;
        }
        return regions;
    }

    private int planSizeFor(SourceTable source) {
        return sourceColumnNames(source).size();
    }

    private SourceNode sourcePlanNode(SourceTable source) {
        List<StarOutput> plan = planFromSource(source);
        boolean widthKnown = sourceColumnsKnown(source);
        int width = source.metadataTable != null
                ? source.metadataTable.getFields().size() : source.outputColumns.size();
        return new SourceNode(java.util.Collections.singletonList(source), plan,
                sourceNamesKnown(source), widthKnown, width, minimumColumns(source), 0,
                plan.size(),
                widthKnown ? java.util.Collections.<SourceTable>emptyList()
                        : java.util.Collections.singletonList(source),
                java.util.Collections.<SourceTable>emptyList(),
                unknownSectionEndFor(source, widthKnown, plan.size()));
    }

    private static Map<SourceTable, Integer> unknownSectionEndFor(SourceTable source,
            boolean widthKnown, int planSize) {
        Map<SourceTable, Integer> ends = new HashMap<>();
        if (widthKnown || source == null) {
            return ends;
        }
        int end = 0;
        for (int i = 0; i < source.outputNameKnown.size(); i++) {
            if (!Boolean.TRUE.equals(source.outputNameKnown.get(i))) {
                end = i + 1;
            }
        }
        ends.put(source, end);
        return ends;
    }

    private static boolean singleSourceCertain(List<SourceTable> sources, String key) {
        if (sources == null || sources.size() != 1) {
            return false;
        }
        SourceTable source = sources.get(0);
        if (source.metadataTable != null) {
            return true;
        }
        if (source.outputColumns.isEmpty()) {
            // A physical table with unknown columns: the USING key must exist once the
            // statement is valid.
            return true;
        }
        for (int i = 0; i < source.outputKeys.size(); i++) {
            if (source.outputKeys.get(i) != null
                    && source.outputKeys.get(i).equals(key)
                    && i < source.outputNameKnown.size()
                    && Boolean.TRUE.equals(source.outputNameKnown.get(i))) {
                return true;
            }
        }
        return false;
    }

    private static StarOutput unknownColumn(List<SourceTable> sources, SQLExpr expr,
            String key, boolean certain) {
        if (sources == null || sources.isEmpty()) {
            return null;
        }
        List<StarColumn> columns = new ArrayList<>();
        String name = normalizeIdentifier(expr.toString());
        boolean quoted = MetadataCatalog.isQuotedIdentifier(expr.toString());
        for (SourceTable source : sources) {
            columns.add(new StarColumn(source, name, key, certain, quoted));
        }
        return new StarOutput(columns, certain);
    }

    private static StarOutput removeFirstUnknown(List<StarOutput> plan, SQLExpr expr,
            String key, boolean certain) {
        for (int i = 0; i < plan.size(); i++) {
            if (!plan.get(i).nameKnown) {
                return positionalColumn(plan.remove(i), expr, key, certain);
            }
        }
        return null;
    }

    private static StarOutput positionalColumn(StarOutput output, SQLExpr expr, String key,
            boolean certain) {
        List<StarColumn> columns = new ArrayList<>();
        String name = normalizeIdentifier(expr.toString());
        boolean quoted = MetadataCatalog.isQuotedIdentifier(expr.toString());
        for (StarColumn column : output.sources) {
            columns.add(new StarColumn(column.source, name, key, certain, quoted,
                    column.index));
        }
        return new StarOutput(columns, certain);
    }

    private static int planKeyCount(List<StarOutput> plan, String key) {
        int count = 0;
        for (StarOutput output : plan) {
            boolean matched = false;
            for (StarColumn column : output.sources) {
                if (column.key != null && column.key.equals(key)) {
                    matched = true;
                }
            }
            if (matched) {
                count++;
            }
        }
        return count;
    }

    /**
     * Every source that takes part in a USING merge, including sides whose column names
     * are unknown. Such sides are merged, not independent candidates.
     */
    private static void addSources(List<SourceTable> target, List<SourceTable> extra) {
        for (SourceTable source : extra) {
            if (!target.contains(source)) {
                target.add(source);
            }
        }
    }

    private void addUnknownSources(List<SourceTable> target, List<SourceTable> candidates) {
        for (SourceTable source : candidates) {
            if (!sourceNamesKnown(source) && !target.contains(source)) {
                target.add(source);
            }
        }
    }

    private static List<SourceTable> planAllSourcesForKey(List<StarOutput> plan,
            String key) {
        for (StarOutput output : plan) {
            boolean matched = false;
            for (StarColumn column : output.sources) {
                if (column.key != null && column.key.equals(key)) {
                    matched = true;
                    break;
                }
            }
            if (matched) {
                List<SourceTable> sources = new ArrayList<>();
                for (StarColumn column : output.sources) {
                    if (!sources.contains(column.source)) {
                        sources.add(column.source);
                    }
                }
                return sources;
            }
        }
        return new ArrayList<>();
    }

    private static List<SourceTable> planSourcesForKey(List<StarOutput> plan, String key) {
        for (StarOutput output : plan) {
            List<SourceTable> sources = new ArrayList<>();
            for (StarColumn column : output.sources) {
                if (column.key != null && column.key.equals(key) && column.nameKnown
                        && !sources.contains(column.source)) {
                    sources.add(column.source);
                }
            }
            if (!sources.isEmpty()) {
                return sources;
            }
        }
        return new ArrayList<>();
    }

    private static int insertionPoint(List<StarOutput> plan, List<SourceTable> sources,
            int fallback, Map<SourceTable, Integer> sectionEnd) {
        // The translated internal section end preserves the source's own order
        // (including explicit columns before or after its wildcard).
        Integer end = sources.isEmpty() ? null : sectionEnd.get(sources.get(0));
        if (end != null) {
            return Math.min(end, plan.size());
        }
        int lastUncertain = lastIndexOfUncertainSource(plan, sources);
        if (lastUncertain >= 0) {
            return lastUncertain + 1;
        }
        int last = lastIndexOfSource(plan, sources);
        if (last >= 0) {
            return last + 1;
        }
        return fallback;
    }

    private static int lastIndexOfUncertainSource(List<StarOutput> plan,
            List<SourceTable> sources) {
        int last = -1;
        for (int i = 0; i < plan.size(); i++) {
            for (StarColumn column : plan.get(i).sources) {
                if (!column.nameKnown && sources.contains(column.source)) {
                    last = i;
                    break;
                }
            }
        }
        return last;
    }

    private static int countRemaining(List<StarOutput> plan, List<StarOutput> originals) {
        int count = 0;
        for (StarOutput output : originals) {
            if (containsIdentity(plan, output)) {
                count++;
            }
        }
        return count;
    }

    private static boolean containsIdentity(List<StarOutput> plan, StarOutput output) {
        for (StarOutput candidate : plan) {
            if (candidate.origin == output.origin) {
                return true;
            }
        }
        return false;
    }

    private static int keptBefore(List<StarOutput> plan, List<StarOutput> originals,
            int boundary) {
        int kept = 0;
        int limit = Math.min(boundary, originals.size());
        for (int i = 0; i < limit; i++) {
            if (containsIdentity(plan, originals.get(i))) {
                kept++;
            }
        }
        return kept;
    }

    private static int lastIndexOfSource(List<StarOutput> plan, List<SourceTable> sources) {
        int last = -1;
        for (int i = 0; i < plan.size(); i++) {
            for (StarColumn column : plan.get(i).sources) {
                if (sources.contains(column.source)) {
                    last = i;
                    break;
                }
            }
        }
        return last;
    }

    private static int knownOutputPositionCount(SourceNode node) {
        int count = 0;
        for (StarOutput output : node.plan) {
            if (output.nameKnown) {
                count++;
            }
        }
        return count;
    }

    private static Set<String> knownOutputKeys(SourceNode node) {
        Set<String> keys = new HashSet<>();
        for (StarOutput output : node.plan) {
            if (!output.nameKnown) {
                continue;
            }
            for (StarColumn column : output.sources) {
                keys.add(column.key);
            }
        }
        return keys;
    }

    private boolean nodeNamesKnown(SourceNode node) {
        return node.knownColumns || bindingNamesKnown(node);
    }

    private static int planKnownKeyCount(List<StarOutput> plan, String key) {
        int count = 0;
        for (StarOutput output : plan) {
            if (!output.nameKnown) {
                continue;
            }
            for (StarColumn column : output.sources) {
                if (column.key != null && column.key.equals(key)) {
                    count++;
                    break;
                }
            }
        }
        return count;
    }

    private StarOutput removeFirstMatching(List<StarOutput> plan, String key) {
        for (int i = 0; i < plan.size(); i++) {
            StarOutput output = plan.get(i);
            for (StarColumn column : output.sources) {
                if (column.key != null && column.key.equals(key)
                        && (column.nameKnown || output.nameKnown)) {
                    return plan.remove(i);
                }
            }
        }
        return null;
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
        String rawName = extractTableName(table);
        String written = normalizeQualifiedName(rawName);
        String alias = table.getAlias();
        Table metadataTable = null;
        String display = alias != null ? alias
                : detachedMetadata() && rawName != null
                        ? sessionPhysicalReference(rawName)
                        : lastIdentifier(written);
        String cteTarget = isPhysicalTableExpression(table)
                && isSingleSegmentName(rawName)
                ? findCteTarget(identifierKey(rawName)) : null;
        boolean temporary = cteTarget != null;
        String sourceAlias = alias;

        if (temporary) {
            display = cteTarget;
            if (sourceAlias == null) {
                sourceAlias = rawName;
            }
        }

        if (context != null && !temporary) {
            TableLocation location = resolveMetadataTable(rawName);
            if (location != null) {
                metadataTable = location.getTable();
                display = location.getDisplayName();
            }
        }

        String mappingName = metadataTable != null ? display : written;
        registerTable(written, mappingName, display, alias, temporary);

        List<String> outputColumns = cteTarget == null
                ? java.util.Collections.<String>emptyList()
                : copyOutputs(state.targetOutputs.get(cteTarget));
        boolean widthKnown = metadataTable != null || cteTarget != null
                && isOutputWidthKnown(cteTarget, outputColumns);
        boolean namesKnown = metadataTable != null || cteTarget != null
                && isOutputNamesKnown(cteTarget, outputColumns);
        int outputWidth = derivedWidth(cteTarget, outputColumns, widthKnown);
        int minOutputColumns = metadataTable != null
                ? metadataTable.getFields().size()
                : targetMinimumColumns(cteTarget, widthKnown, outputWidth);
        return new SourceTable(display, written, rawName, sourceAlias, metadataTable, temporary,
                widthKnown, namesKnown, outputColumns, copyKeys(cteTarget),
                metadataTable != null ? java.util.Collections.<Boolean>emptyList()
                        : copyNameKnown(cteTarget, outputColumns.size()),
                minOutputColumns,
                metadataTable != null ? java.util.Collections.<SourceTable>emptyList()
                        : copyUnknownGroups(cteTarget, outputColumns.size()),
                false, hasWildcardContribution(cteTarget),
                isPhysicalTableExpression(table),
                isPhysicalTableExpression(table) ? null : implicitRelationName(table));
    }

    private int targetMinimumColumns(String target, boolean widthKnown, int outputCount) {
        if (widthKnown) {
            return outputCount;
        }
        Integer minimum = target == null ? null : state.targetMinWidth.get(target);
        return minimum == null ? 0 : minimum;
    }

    private void recordWildcardSpan(String target, int start, int end, String source,
            boolean temporary) {
        if (target == null || start < 0) {
            return;
        }
        state.targetWildcardSpans.computeIfAbsent(target,
                key -> new ArrayList<WildcardSpan>())
                .add(new WildcardSpan(start, end, source, temporary));
    }

    private List<WildcardSpan> wildcardSpansOf(String target) {
        List<WildcardSpan> spans = state.targetWildcardSpans.get(target);
        return spans == null ? java.util.Collections.<WildcardSpan>emptyList()
                : new ArrayList<>(spans);
    }

    private int knownWidthSum(List<SourceTable> sources) {
        int sum = 0;
        for (SourceTable source : sources) {
            sum += sourceColumnsKnown(source)
                    ? Math.max(sourceColumnNames(source).size(), 1) : 1;
        }
        return Math.max(sum, 1);
    }

    private static boolean hasAmbiguousOutput(List<StarOutput> plan, int limit) {
        int position = 0;
        for (StarOutput output : plan) {
            if (position >= limit) {
                break;
            }
            if (output.ambiguous) {
                return true;
            }
            position++;
        }
        return false;
    }

    private static int maxSpanEnd(List<WildcardSpan> spans) {
        int max = 0;
        for (WildcardSpan span : spans) {
            if (span.end != Integer.MAX_VALUE) {
                max = Math.max(max, span.end);
            } else {
                max = Math.max(max, span.start + 1);
            }
        }
        return max;
    }

    private int relativeColumnIndex(SourceTable source, StarColumn column) {
        List<String> keys = sourceColumnKeys(source);
        if (column.key != null) {
            int found = -1;
            for (int i = 0; i < keys.size(); i++) {
                if (column.key.equals(keys.get(i))) {
                    if (found >= 0) {
                        // A duplicated name cannot identify a position; let the caller
                        // fall back to the plan's own occurrence order.
                        return -1;
                    }
                    found = i;
                }
            }
            if (found >= 0) {
                return found;
            }
        }
        List<String> names = sourceColumnNames(source);
        int found = -1;
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).equals(column.column)) {
                if (found >= 0) {
                    return -1;
                }
                found = i;
            }
        }
        return found;
    }

    private List<WildcardRef> wildcardSourcesAt(SourceTable source, StarColumn column,
            int fallbackRelative) {
        List<WildcardRef> refs = new ArrayList<>();
        if (!source.temporary) {
            refs.add(new WildcardRef(lineageTableName(source), false));
            return refs;
        }
        int relative = column.index >= 0 ? column.index
                : relativeColumnIndex(source, column);
        if (relative < 0) {
            relative = fallbackRelative;
        }
        for (WildcardSpan span : wildcardSpansOf(source.displayName)) {
            if (relative >= span.start && relative < span.end) {
                WildcardRef ref = new WildcardRef(span.source, span.temporary);
                if (!refs.contains(ref)) {
                    refs.add(ref);
                }
            }
        }
        return refs;
    }

private boolean hasWildcardContribution(String target) {
        return target != null && Boolean.TRUE.equals(state.targetWildcardContribution.get(target));
    }

    private List<SourceTable> copyUnknownGroups(String target, int size) {
        List<SourceTable> groups = target == null ? null
                : state.targetOutputUnknownGroup.get(target);
        List<SourceTable> result = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            result.add(groups != null && i < groups.size() ? groups.get(i) : null);
        }
        return result;
    }

    private List<Boolean> copyNameKnown(String target, int size) {
        List<Boolean> flags = target == null ? null : state.targetOutputNameKnown.get(target);
        List<Boolean> result = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            result.add(flags != null && i < flags.size() && Boolean.TRUE.equals(flags.get(i)));
        }
        return result;
    }

    private boolean isOutputWidthKnown(String target, List<String> outputs) {
        return Boolean.TRUE.equals(state.targetWidthKnown.get(target));
    }

    private boolean isOutputNamesKnown(String target, List<String> outputs) {
        return !outputs.isEmpty() && Boolean.TRUE.equals(state.targetNamesKnown.get(target));
    }

    private static List<String> copyOutputs(List<String> outputs) {
        return outputs == null ? java.util.Collections.<String>emptyList()
                : new ArrayList<>(outputs);
    }

    private List<String> copyKeys(String target) {
        List<String> keys = target == null ? null : state.targetOutputKeys.get(target);
        return keys == null ? java.util.Collections.<String>emptyList()
                : new ArrayList<>(keys);
    }


    private MetadataResolver metadataResolver() {
        return new MetadataResolver(context, currentDialect(), sessionDatabase,
                sessionRawDatabase,
                context == null ? new ArrayList<String>() : context.getSchemas(),
                sessionRawSchemas, detachedMetadata());
    }

    TableLocation resolveMetadataTable(String written) {
        return metadataResolver().resolve(written);
    }

    String sessionPhysicalReference(String rawName) {
        return metadataResolver().physicalReference(rawName);
    }

    static boolean isSingleSegmentName(String rawName) {
        return MetadataCatalog.splitQualifiedNamePreservingQuotes(rawName).length == 1;
    }

    private String canonicalIdentifier(String rawName) {
        return DialectRules.of(currentDialect())
                .canonical(Identifier.of(rawName));
    }

    String identifierKey(String rawName) {
        return DialectRules.of(currentDialect())
                .key(Identifier.of(rawName));
    }

    private Map<String, List<String>> namedTargetOutputs() {
        Map<String, List<String>> named = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : state.targetOutputs.entrySet()) {
            if (!state.intermediateTargets.contains(entry.getKey())) {
                named.put(entry.getKey(), copyOutputs(entry.getValue()));
            }
        }
        return named;
    }

    private String lockedOutputName(String target, int position) {
        if (!state.unionTargets.contains(target)) {
            return null;
        }
        List<String> outputs = state.targetOutputs.get(target);
        return outputs != null && position < outputs.size() ? outputs.get(position) : null;
    }

    private String lockedOutputKey(String target, int position) {
        if (!state.unionTargets.contains(target)) {
            return null;
        }
        List<String> keys = state.targetOutputKeys.get(target);
        return keys != null && position < keys.size() ? keys.get(position) : null;
    }

    private void incrementNonAuthoritative(String target) {
        Integer depth = state.nonAuthoritativeDepth.get(target);
        state.nonAuthoritativeDepth.put(target, depth == null ? 1 : depth + 1);
    }

    private void decrementNonAuthoritative(String target) {
        Integer depth = state.nonAuthoritativeDepth.get(target);
        if (depth == null || depth <= 1) {
            state.nonAuthoritativeDepth.remove(target);
        } else {
            state.nonAuthoritativeDepth.put(target, depth - 1);
        }
    }

    private void markUnknownGroup(String target, int position, SourceTable source) {
        if (target == null || source == null || position < 0) {
            return;
        }
        List<SourceTable> groups = state.targetOutputUnknownGroup.computeIfAbsent(target,
                k -> new ArrayList<SourceTable>());
        while (groups.size() <= position) {
            groups.add(null);
        }
        groups.set(position, source);
    }

    private void markOutputNameKnown(String target, int position, boolean known) {
        if (target == null || position < 0) {
            return;
        }
        // Only the first (left-most) UNION branch names the result columns.
        Integer depth = state.nonAuthoritativeDepth.get(target);
        if (depth != null && depth > 0) {
            return;
        }
        List<Boolean> flags = state.targetOutputNameKnown.computeIfAbsent(target,
                k -> new ArrayList<Boolean>());
        while (flags.size() <= position) {
            flags.add(Boolean.FALSE);
        }
        flags.set(position, known);
    }

    private void setTargetOutput(String target, int position, String name) {
        setTargetOutput(target, position, name, identifierKey(name));
    }

    private void setTargetOutput(String target, int position, String name, String key) {
        List<String> outputs = state.targetOutputs.computeIfAbsent(target,
                k -> new ArrayList<String>());
        List<String> keys = state.targetOutputKeys.computeIfAbsent(target,
                k -> new ArrayList<String>());
        while (outputs.size() <= position) {
            outputs.add(null);
            keys.add(null);
        }
        outputs.set(position, name);
        keys.set(position, key);
    }

    private void recordStarOutputs(String target, List<SourceTable> sources, int startPosition) {
        Map<String, List<SourceTable>> using = state.stackUsing.isEmpty()
                ? null : state.stackUsing.peek();
        if (useVisiblePlan(sources, using)) {
            List<String> declared = currentTargetColumnAliases(target);
            List<String> declaredKeys = currentDeclaredKeys(target);
            int position = startPosition;
            for (StarOutput output : currentStarPlan(sources)) {
                String lockedName = lockedOutputName(target, position);
                String lockedKey = lockedOutputKey(target, position);
                String name;
                String key;
                if (lockedName != null) {
                    name = lockedName;
                    key = lockedKey != null ? lockedKey : identifierKey(name);
                } else {
                    boolean declaredName = declared != null && position < declared.size();
                    name = declaredName
                            ? declared.get(position) : output.sources.get(0).column;
                    key = declaredKeys != null && position < declaredKeys.size()
                            ? declaredKeys.get(position) : output.sources.get(0).key;
                    if (isExistingOutput(state.targetOutputs.get(target), name, position)) {
                        int suffix = position + 1;
                        do {
                            name = "col" + suffix++;
                            key = identifierKey(name);
                        } while (isExistingOutput(state.targetOutputs.get(target), name, position));
                        declaredName = true;
                    }
                    markOutputNameKnown(target, position, declaredName || output.nameKnown);
                }
                setTargetOutput(target, position++, name, key);
            }
            return;
        }
        List<OutputName> entries = starColumnEntries(target, sources, startPosition);
        for (int i = 0; i < entries.size(); i++) {
            OutputName entry = entries.get(i);
            setTargetOutput(target, startPosition + i, entry.name, entry.key);
        }
    }

    private void recordSourceStarOutputs(String target, SourceTable source, int startPosition) {
        List<OutputName> entries = starColumnEntries(target,
                java.util.Collections.singletonList(source), startPosition);
        for (int i = 0; i < entries.size(); i++) {
            OutputName entry = entries.get(i);
            setTargetOutput(target, startPosition + i, entry.name, entry.key);
        }
    }

    private List<OutputName> starColumnEntries(String target, List<SourceTable> sources,
            int startPosition) {
        List<String> declared = currentTargetColumnAliases(target);
        List<String> declaredKeys = currentDeclaredKeys(target);
        List<String> existing = state.targetOutputs.get(target);
        List<OutputName> entries = new ArrayList<>();
        Set<String> used = new HashSet<>();
        int position = startPosition;
        for (SourceTable source : sources) {
            List<String> names = sourceColumnNames(source);
            List<String> keys = sourceColumnKeys(source);
            for (int i = 0; i < names.size(); i++) {
                String lockedName = lockedOutputName(target, position);
                String lockedKey = lockedOutputKey(target, position);
                if (lockedName != null) {
                    entries.add(new OutputName(lockedName,
                            lockedKey != null ? lockedKey : identifierKey(lockedName)));
                    used.add(lockedName);
                    position++;
                    continue;
                }
                boolean declaredName = declared != null && position < declared.size();
                String name = declaredName ? declared.get(position) : names.get(i);
                String key = declaredKeys != null && position < declaredKeys.size()
                        ? declaredKeys.get(position)
                        : i < keys.size() ? keys.get(i) : identifierKey(name);
                if (used.contains(name) || isExistingOutput(existing, name, position)) {
                    int suffix = position + 1;
                    do {
                        name = "col" + suffix++;
                        key = identifierKey(name);
                    } while (used.contains(name) || isExistingOutput(existing, name, position));
                }
                markOutputNameKnown(target, position,
                        declaredName || sourceOutputNameKnown(source, i));
                if (!declaredName && !sourceOutputNameKnown(source, i)) {
                    SourceTable group = i < source.outputUnknownGroups.size()
                            && source.outputUnknownGroups.get(i) != null
                                    ? source.outputUnknownGroups.get(i) : source;
                    markUnknownGroup(target, position, group);
                }
                entries.add(new OutputName(name, key));
                used.add(name);
                position++;
            }
        }
        return entries;
    }

    private List<String> currentDeclaredKeys(String target) {
        for (int i = state.targetColumnAliases.size() - 1; i >= 0; i--) {
            if (state.targetColumnAliases.get(i).containsKey(target)) {
                List<String> keys = state.cteDeclaredKeys.get(target);
                return keys == null ? java.util.Collections.<String>emptyList() : keys;
            }
        }
        return null;
    }

    private boolean isExistingOutput(List<String> existing, String name, int position) {
        if (existing == null) {
            return false;
        }
        for (int i = 0; i < existing.size(); i++) {
            if (i != position && name.equals(existing.get(i))) {
                return true;
            }
        }
        return false;
    }

    private List<String> sourceColumnNames(SourceTable source) {
        if (source.metadataTable != null) {
            List<String> columns = new ArrayList<>();
            for (Column column : source.metadataTable.getFields()) {
                columns.add(column.getColumnName());
            }
            return columns;
        }
        if (!source.outputColumns.isEmpty()) {
            return source.outputColumns;
        }
        return java.util.Collections.singletonList("col1");
    }

    private List<StarOutput> currentStarPlan(List<SourceTable> sources) {
        if (!state.stackStarPlan.isEmpty() && !state.stackStarPlan.peek().isEmpty()) {
            return state.stackStarPlan.peek();
        }
        List<StarOutput> plan = new ArrayList<>();
        for (SourceTable source : sources) {
            plan.addAll(planFromSource(source));
        }
        return plan;
    }



    private List<String> currentTargetColumnAliases(String target) {
        for (int i = state.targetColumnAliases.size() - 1; i >= 0; i--) {
            Map<String, List<String>> aliases = state.targetColumnAliases.get(i);
            if (aliases.containsKey(target)) {
                return aliases.get(target);
            }
        }
        return null;
    }

    String findCteTarget(String key) {
        for (int i = state.cteScopes.size() - 1; i >= 0; i--) {
            String target = state.cteScopes.get(i).get(key);
            if (target != null) {
                return target;
            }
        }
        return null;
    }

    private boolean isMySqlFamily() {
        return context != null && (context.getCatalog().getDialect() == DbType.mysql
                || context.getCatalog().getDialect() == DbType.mariadb);
    }

    void registerTable(String written, String mappingName, String display, String alias,
            boolean cteReference) {
        if (cteReference || tables.contains(display)) {
            return;
        }
        tables.add(display);
        if (alias != null) {
            instructions.add(new Instruction(OperationType.TABLE_MAPPING, mappingName, alias));
        }
    }

    String extractTableName(SQLExprTableSource table) {
        SQLExpr expr = table.getExpr();
        return expr != null ? expr.toString() : table.getTableName();
    }

    /** A FROM item is a table only when its expression is a relation name. */
    static boolean isPhysicalTableExpression(SQLExprTableSource table) {
        SQLExpr expr = table.getExpr();
        return expr == null || expr instanceof SQLName;
    }

    /** The implicit visible name of a table function in FROM. */
    static String implicitRelationName(SQLExprTableSource table) {
        SQLExpr expr = table.getExpr();
        if (expr instanceof SQLMethodInvokeExpr) {
            return ((SQLMethodInvokeExpr) expr).getMethodName();
        }
        return null;
    }

    /* ------------------------------------------------------------------ */
    /* expressions                                                        */
    /* ------------------------------------------------------------------ */

    private void validateUnboundQualified(SQLExpr expr) {
        if (expr == null) {
            return;
        }
        if (expr instanceof SQLPropertyExpr) {
            SQLPropertyExpr property = (SQLPropertyExpr) expr;
            if (property.getOwner() != null) {
                throw new MetadataNotFoundException(property.getOwner().toString());
            }
        } else if (expr instanceof SQLBinaryOpExpr) {
            SQLBinaryOpExpr binary = (SQLBinaryOpExpr) expr;
            validateUnboundQualified(binary.getLeft());
            validateUnboundQualified(binary.getRight());
        } else if (expr instanceof SQLMethodInvokeExpr) {
            for (SQLExpr argument : ((SQLMethodInvokeExpr) expr).getArguments()) {
                validateUnboundQualified(argument);
            }
        } else if (expr instanceof SQLCastExpr) {
            validateUnboundQualified(((SQLCastExpr) expr).getExpr());
        } else if (expr instanceof SQLCaseExpr) {
            SQLCaseExpr caseExpr = (SQLCaseExpr) expr;
            validateUnboundQualified(caseExpr.getValueExpr());
            if (caseExpr.getItems() != null) {
                for (SQLCaseExpr.Item item : caseExpr.getItems()) {
                    validateUnboundQualified(item.getConditionExpr());
                    validateUnboundQualified(item.getValueExpr());
                }
            }
            validateUnboundQualified(caseExpr.getElseExpr());
        } else if (expr instanceof SQLListExpr) {
            for (SQLExpr item : ((SQLListExpr) expr).getItems()) {
                validateUnboundQualified(item);
            }
        } else if (expr instanceof SQLNotExpr) {
            validateUnboundQualified(((SQLNotExpr) expr).getExpr());
        } else if (expr instanceof SQLUnaryExpr) {
            validateUnboundQualified(((SQLUnaryExpr) expr).getExpr());
        } else if (expr instanceof SQLExtractExpr) {
            validateUnboundQualified(((SQLExtractExpr) expr).getValue());
        }
    }

    private boolean ansiEnabled;
    private boolean ansiDoubleQuotedIdentifiers;
    private String statementSql;
    static final String ANSI_IDENTIFIER_ATTRIBUTE =
            "com.laotie.ansiQuotedIdentifier";

    private void learnBlockExplicitReferences(SQLSelectQueryBlock block,
            Map<String, String> inherited) {
        learning.learnBlockExplicitReferences(block, inherited);
    }

    private void learnNoFromExplicitReferences(SQLSelectQueryBlock block,
            Map<String, String> inherited) {
        learning.learnNoFromExplicitReferences(block, inherited);
    }

    private Map<String, String> outerBlockLocals(boolean includeCurrentFrame) {
        return learning.outerBlockLocals(includeCurrentFrame);
    }

    void visitExpr(SQLExpr expr) {
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
        } else if (expr instanceof SQLQueryExpr) {
            visitScalarSubquery((SQLQueryExpr) expr);
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

    private void visitScalarSubquery(SQLQueryExpr expr) {
        ScalarSubquery inner = analyzeScalarSubquery(expr);
        if (inner == null) {
            return;
        }
        if (inner.columns.isEmpty()) {
            recordScalarTableLevel(inner, state.stackTargetTable.peek(), state.currentOrdinal,
                    state.stackTargetColumn.peek());
            return;
        }
        String targetTable = state.stackTargetTable.peek();
        String targetColumn = state.stackTargetColumn.peek();
        if (state.uncertainOrdinal >= 0 && state.currentOrdinal == state.uncertainOrdinal) {
            return;
        }
        for (int i = 0; i < inner.columns.size(); i++) {
            String source = inner.references.get(i);
            instructions.add(new Instruction(OperationType.COLUMN_MAPPING, source,
                    targetTable + "." + targetColumn, state.currentOrdinal, true,
                    state.tempTables.contains(targetTable)));
        }
    }

    private void visitIdentifier(SQLIdentifierExpr identifier) {
        if (!state.inTargetExpression) {
            return;
        }
        String rawName = identifier.getName();
        List<SourceTable> sources;
        try {
            sources = resolveUnqualified(rawName);
        } catch (ColumnNotFoundException e) {
            if (!hasOuterScopeFallback()) {
                throw e;
            }
            sources = resolveCorrelatedUnqualified(rawName);
        }
        if (sources.isEmpty() && hasOuterScopeFallback()) {
            sources = resolveCorrelatedUnqualified(rawName);
        }
        for (SourceTable source : sources) {
            learnColumn(source, rawName);
            addColumnMapping(source, rawName);
        }
    }

    private List<SourceTable> participantsForFrame(int frame, String key) {
        if (frame >= state.stackUsing.size() || state.usingParticipantsByIdentity.isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, List<SourceTable>> using = state.stackUsing.get(frame);
        if (using == null) {
            return new ArrayList<>();
        }
        Map<String, List<SourceTable>> participants =
                state.usingParticipantsByIdentity.get(using);
        if (participants == null || !participants.containsKey(key)) {
            return new ArrayList<>();
        }
        return new ArrayList<>(participants.get(key));
    }

    private List<SourceTable> frameCandidates(int frame, String key,
            List<SourceTable> participants, List<SourceTable> excluded) {
        Set<SourceTable> hidden = frame < state.stackHiddenSources.size()
                ? state.stackHiddenSources.get(frame)
                : java.util.Collections.<SourceTable>emptySet();
        List<SourceTable> candidates = new ArrayList<>();
        for (SourceTable source : state.stackSources.get(frame)) {
            if (hidden.contains(source) || participants.contains(source)
                    || excluded.contains(source)) {
                continue;
            }
            if (ownsOuterColumn(source, key) || !sourceColumnNamesKnown(source)) {
                candidates.add(source);
            }
        }
        return candidates;
    }

    private boolean lateralScopeActive() {
        int current = state.stackSources.size() - 1;
        return current >= 0 && current < state.lateralScopes.size()
                && Boolean.TRUE.equals(state.lateralScopes.get(current))
                || hasLateralAncestorScope();
    }

    private boolean hasOuterScopeFallback() {
        return hasLateralAncestorScope() || state.scalarSubqueryDepth > 0;
    }

    private boolean hasLateralAncestorScope() {
        for (int frame = 0; frame < state.stackSources.size() - 1; frame++) {
            if (frame < state.lateralScopes.size()
                    && Boolean.TRUE.equals(state.lateralScopes.get(frame))) {
                return true;
            }
        }
        return false;
    }

    private List<SourceTable> resolveCorrelatedUnqualified(String rawName) {
        String key = identifierKey(rawName);
        boolean lateralScope = false;
        for (int frame = state.stackSources.size() - 2; frame >= 0; frame--) {
            boolean lateralFrame = frame < state.lateralScopes.size()
                    && Boolean.TRUE.equals(state.lateralScopes.get(frame));
            if (!lateralFrame && state.scalarSubqueryDepth == 0) {
                continue;
            }
            lateralScope = true;
            Map<String, List<SourceTable>> using = frame < state.stackUsing.size()
                    ? state.stackUsing.get(frame) : null;
            if (using != null && using.containsKey(key)) {
                List<StarOutput> framePlan = frame < state.stackStarPlan.size()
                        ? state.stackStarPlan.get(frame)
                        : java.util.Collections.<StarOutput>emptyList();
                List<StarOutput> planMatches = new ArrayList<>();
                for (StarOutput output : framePlan) {
                    for (StarColumn column : output.sources) {
                        if (key.equals(column.key)) {
                            planMatches.add(output);
                            break;
                        }
                    }
                }
                if (planMatches.size() > 1) {
                    List<SourceTable> all = new ArrayList<>();
                    for (StarOutput output : planMatches) {
                        for (StarColumn column : output.sources) {
                            if (!all.contains(column.source)) {
                                all.add(column.source);
                            }
                        }
                    }
                    throw new AmbiguousColumnException(normalizeIdentifier(rawName),
                            displayNames(all));
                }
                List<SourceTable> participants = using.get(key);
                List<SourceTable> sides = participantsForFrame(frame, key);
                List<SourceTable> extras = frameCandidates(frame, key, sides,
                        new ArrayList<SourceTable>());
                if (!extras.isEmpty()) {
                    List<SourceTable> all = new ArrayList<>(participants);
                    all.addAll(extras);
                    throw new AmbiguousColumnException(normalizeIdentifier(rawName),
                            displayNames(all));
                }
                return participants;
            }
            List<StarOutput> plan = frame < state.stackStarPlan.size()
                    ? state.stackStarPlan.get(frame)
                    : java.util.Collections.<StarOutput>emptyList();
            List<StarOutput> matches = new ArrayList<>();
            for (StarOutput output : plan) {
                for (StarColumn column : output.sources) {
                    if (key != null && key.equals(column.key)) {
                        matches.add(output);
                        break;
                    }
                }
            }
            if (matches.size() > 1) {
                List<SourceTable> owners = new ArrayList<>();
                for (StarOutput output : matches) {
                    for (StarColumn column : output.sources) {
                        if (!owners.contains(column.source)) {
                            owners.add(column.source);
                        }
                    }
                }
                throw new AmbiguousColumnException(normalizeIdentifier(rawName),
                        displayNames(owners));
            }
            if (matches.size() == 1) {
                List<SourceTable> owners = new ArrayList<>();
                for (StarColumn column : matches.get(0).sources) {
                    if (!owners.contains(column.source)) {
                        owners.add(column.source);
                    }
                }
                List<SourceTable> participants = participantsForFrame(frame, key);
                List<SourceTable> extras = frameCandidates(frame, key, participants,
                        owners);
                if (!extras.isEmpty()) {
                    List<SourceTable> all = new ArrayList<>(owners);
                    all.addAll(extras);
                    throw new AmbiguousColumnException(normalizeIdentifier(rawName),
                            displayNames(all));
                }
                return owners;
            }
            Set<SourceTable> hidden = frame < state.stackHiddenSources.size()
                    ? state.stackHiddenSources.get(frame)
                    : java.util.Collections.<SourceTable>emptySet();
            List<SourceTable> owners = new ArrayList<>();
            List<SourceTable> unknown = new ArrayList<>();
            for (SourceTable source : state.stackSources.get(frame)) {
                if (hidden.contains(source)) {
                    continue;
                }
                if (ownsOuterColumn(source, key)) {
                    owners.add(source);
                } else if (!sourceColumnNamesKnown(source)) {
                    unknown.add(source);
                }
            }
            if (owners.size() > 1 || !owners.isEmpty() && !unknown.isEmpty()
                    || unknown.size() > 1) {
                List<SourceTable> all = new ArrayList<>(owners);
                all.addAll(unknown);
                throw new AmbiguousColumnException(normalizeIdentifier(rawName),
                        displayNames(all));
            }
            if (owners.size() == 1) {
                return owners;
            }
            if (unknown.size() == 1) {
                return unknown;
            }
        }
        if (!lateralScope) {
            // Preserve lenient behavior for normal (non-LATERAL) resolution.
            return new ArrayList<>();
        }
        throw new ColumnNotFoundException(normalizeIdentifier(rawName),
                displayNames(currentSources()));
    }

private CorrelatedOwner findCorrelatedOwner(SQLExpr owner) {
        if (owner == null) {
            return null;
        }
        String rawOwner = owner.toString();
        String ownerKey = qualifiedKey(rawOwner);
        List<SourceTable> matches = visibleOwnerMatches(owner);
        if (matches.size() > 1) {
            throw new AmbiguousColumnException(normalizeIdentifier(lastSegment(rawOwner)),
                    displayNames(matches));
        }
        SourceTable direct = matches.isEmpty() ? null : matches.get(0);
        if (direct != null) {
            return new CorrelatedOwner(direct, null);
        }
        SourceNode directBinding = exactJoinBinding(owner);
        if (directBinding != null) {
            return new CorrelatedOwner(null, directBinding);
        }
        String[] ownerParts = MetadataCatalog.splitQualifiedNamePreservingQuotes(rawOwner);
        String aliasKey = isSingleSegmentName(rawOwner) ? identifierKey(rawOwner) : null;
        for (int frame = state.stackSources.size() - 1; frame >= 0; frame--) {
            if (frame < state.stackJoinBindings.size() && aliasKey != null) {
                SourceNode binding = state.stackJoinBindings.get(frame).get(aliasKey);
                if (binding != null) {
                    return new CorrelatedOwner(null, binding);
                }
            }
            Set<SourceTable> hidden = frame < state.stackHiddenSources.size()
                    ? state.stackHiddenSources.get(frame)
                    : java.util.Collections.<SourceTable>emptySet();
            // An explicit alias wins over a same-spelled table name in this frame.
            List<SourceTable> aliasMatches = new ArrayList<>();
            for (SourceTable source : state.stackSources.get(frame)) {
                if (!hidden.contains(source) && source.alias != null
                        && qualifiedKey(source.alias).equals(ownerKey)) {
                    aliasMatches.add(source);
                }
            }
            if (aliasMatches.size() > 1) {
                throw new AmbiguousColumnException(
                        normalizeIdentifier(lastSegment(rawOwner)),
                        displayNames(aliasMatches));
            }
            if (aliasMatches.size() == 1) {
                return new CorrelatedOwner(aliasMatches.get(0), null);
            }
            List<SourceTable> frameMatches = new ArrayList<>();
            for (SourceTable source : state.stackSources.get(frame)) {
                if (!hidden.contains(source) && source.alias == null
                        && matchesOwner(source, ownerKey, ownerParts)) {
                    frameMatches.add(source);
                }
            }
            if (frameMatches.size() > 1) {
                throw new AmbiguousColumnException(
                        normalizeIdentifier(lastSegment(rawOwner)),
                        displayNames(frameMatches));
            }
            if (frameMatches.size() == 1) {
                return new CorrelatedOwner(frameMatches.get(0), null);
            }
        }
        return null;
    }

    private static boolean hasQuotedSegment(String reference) {
        return hasSignificantQuote(reference);
    }

    private static boolean exactNormalizedSuffix(String[] ownerParts,
            String[] referenceParts) {
        if (ownerParts.length == 0 || ownerParts.length > referenceParts.length) {
            return false;
        }
        for (int i = 0; i < ownerParts.length; i++) {
            String ownerName = MetadataCatalog.normalizeIdentifier(ownerParts[i]);
            String referenceName = MetadataCatalog.normalizeIdentifier(
                    referenceParts[referenceParts.length - ownerParts.length + i]);
            if (ownerName == null || !ownerName.equals(referenceName)) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasSignificantQuote(String reference) {
        for (String part : MetadataCatalog
                .splitQualifiedNamePreservingQuotes(reference)) {
            if (!MetadataCatalog.isQuotedIdentifier(part)) {
                continue;
            }
            String name = MetadataCatalog.normalizeIdentifier(part);
            // A quoted spelling that folds to itself (for example "b" in
            // PostgreSQL) is equivalent to the unquoted spelling.
            if (name == null || !name.equals(name.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private List<SourceTable> visibleOwnerMatches(SQLExpr owner) {
        List<SourceTable> matches = new ArrayList<>();
        if (owner == null) {
            return matches;
        }
        String rawOwner = owner.toString();
        String ownerKey = qualifiedKey(rawOwner);
        String[] ownerParts = MetadataCatalog.splitQualifiedNamePreservingQuotes(rawOwner);
        for (SourceTable source : visibleSources()) {
            boolean matched;
            if (source.alias != null) {
                // An alias hides the original table name.
                matched = qualifiedKey(source.alias).equals(ownerKey);
            } else if (!source.learnable) {
                // A table function is visible only through its implicit FROM name;
                // its call text is not a qualifier.
                matched = source.implicitName != null
                        && qualifiedKey(source.implicitName).equals(ownerKey);
            } else {
                String reference = source.rawName != null
                        ? source.rawName : source.displayName;
                matched = qualifiedKey(reference).equals(ownerKey);
                if (!matched && source.displayName != null
                        && (source.rawName == null
                                || !hasQuotedSegment(source.rawName))) {
                    matched = qualifiedKey(source.displayName).equals(ownerKey);
                }
                if (!matched && source.displayName != null
                        && source.rawName != null
                        && hasQuotedSegment(source.rawName)) {
                    matched = exactNormalizedSuffix(ownerParts,
                            MetadataCatalog.splitQualifiedNamePreservingQuotes(
                                    source.displayName));
                }
                if (!matched && source.implicitName != null) {
                    matched = qualifiedKey(source.implicitName).equals(ownerKey);
                }
                if (!matched && ownerParts.length > 0) {
                    String[] referenceParts = MetadataCatalog
                            .splitQualifiedNamePreservingQuotes(reference);
                    if (ownerParts.length <= referenceParts.length) {
                        matched = true;
                        for (int i = 0; i < ownerParts.length; i++) {
                            if (!identifierKey(ownerParts[i]).equals(identifierKey(
                                    referenceParts[referenceParts.length
                                            - ownerParts.length + i]))) {
                                matched = false;
                                break;
                            }
                        }
                    }
                }
            }
            if (matched) {
                matches.add(source);
            }
        }
        return matches;
    }

    private SourceTable findVisibleOwnerStrict(SQLExpr owner) {
        if (owner == null) {
            return null;
        }
        String rawOwner = owner.toString();
        String ownerKey = qualifiedKey(rawOwner);
        for (SourceTable source : visibleSources()) {
            boolean matched;
            if (source.alias != null) {
                // An alias hides the original table name.
                matched = qualifiedKey(source.alias).equals(ownerKey);
            } else if (!source.learnable) {
                matched = source.implicitName != null
                        && qualifiedKey(source.implicitName).equals(ownerKey);
            } else {
                matched = qualifiedKey(source.displayName).equals(ownerKey)
                        || source.rawName != null
                                && qualifiedKey(source.rawName).equals(ownerKey)
                        || source.writtenName != null
                                && qualifiedKey(source.writtenName).equals(ownerKey);
            }
            if (matched) {
                return source;
            }
        }
        return null;
    }

    private SourceNode exactJoinBinding(SQLExpr owner) {
        if (owner == null || !isSingleSegmentName(owner.toString())
                || state.stackJoinBindings.isEmpty()) {
            return null;
        }
        String key = identifierKey(owner.toString());
        return key == null ? null : state.stackJoinBindings.peek().get(key);
    }

    private boolean matchesOwner(SourceTable source, String ownerKey,
            String[] ownerParts) {
        if (source.alias != null) {
            return qualifiedKey(source.alias).equals(ownerKey);
        }
        if (!source.learnable) {
            return source.implicitName != null
                    && qualifiedKey(source.implicitName).equals(ownerKey);
        }
        String reference = source.rawName != null
                ? source.rawName : source.displayName;
        if (reference == null) {
            return false;
        }
        if (qualifiedKey(reference).equals(ownerKey)) {
            return true;
        }
        if (source.displayName != null
                && (source.rawName == null
                        || !hasQuotedSegment(source.rawName))
                && qualifiedKey(source.displayName).equals(ownerKey)) {
            return true;
        }
        if (source.implicitName != null
                && qualifiedKey(source.implicitName).equals(ownerKey)) {
            return true;
        }
        if (source.displayName != null && source.rawName != null
                && hasQuotedSegment(source.rawName)
                && exactNormalizedSuffix(ownerParts, MetadataCatalog
                        .splitQualifiedNamePreservingQuotes(source.displayName))) {
            return true;
        }
        String[] referenceParts = MetadataCatalog
                .splitQualifiedNamePreservingQuotes(reference);
        if (ownerParts.length > 0 && ownerParts.length <= referenceParts.length) {
            for (int i = 0; i < ownerParts.length; i++) {
                if (!identifierKey(ownerParts[i]).equals(identifierKey(
                        referenceParts[referenceParts.length - ownerParts.length
                                + i]))) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private static boolean isStarProperty(SQLExpr expr) {
        if (!(expr instanceof SQLPropertyExpr)) {
            return false;
        }
        String rawName = ((SQLPropertyExpr) expr).getName();
        return "*".equals(normalizeIdentifier(rawName))
                && !MetadataCatalog.isQuotedIdentifier(rawName);
    }

    private void visitProperty(SQLPropertyExpr property) {
        String rawName = property.getName();
        String name = normalizeIdentifier(rawName);
        if ("*".equals(name) && !MetadataCatalog.isQuotedIdentifier(rawName)) {
            if (!state.inTargetExpression) {
                return;
            }
            CorrelatedOwner correlated = findCorrelatedOwner(property.getOwner());
            SourceTable source = correlated == null ? null : correlated.source;
            if (source == null) {
                SourceNode binding = correlated == null ? null : correlated.binding;
                if (binding != null) {
                    mapBindingStar(state.stackTargetTable.peek(), binding, state.currentOrdinal);
                    return;
                }
                if (context != null) {
                    throw new MetadataNotFoundException(property.getOwner() == null
                            ? name : property.getOwner().toString());
                }
                return;
            }
            String targetTable = state.stackTargetTable.peek();
            if (detachedMetadata()) {
                emitDetachedPlan(targetTable, planFromSource(source),
                        java.util.Collections.singletonList(source), null, state.currentOrdinal);
                return;
            }
            SourceNode expanded = state.currentExpandedSourceNodes.get(source);
            if (expanded != null) {
                mapBindingStar(targetTable, expanded, state.currentOrdinal);
                return;
            }
            SourceNode leafNode = state.stackSourceNode.isEmpty()
                    ? null : state.stackSourceNode.peek();
            if (context == null && state.currentStarBudget > 0 && leafNode != null
                    && leafNode.sources.size() == 1
                    && leafNode.sources.get(0) == source
                    && !sourceColumnsKnown(source)) {
                mapBindingStar(targetTable, leafNode, state.currentOrdinal);
                return;
            }
            if (context == null && sourceNamesKnown(source)
                    && !source.outputColumns.isEmpty()) {
                mapSourceStar(source, targetTable, state.currentOrdinal);
                return;
            }
            instructions.add(new Instruction(OperationType.COLUMNS_INJECT,
                    source.displayName, targetTable, state.currentOrdinal, source.temporary,
                    state.tempTables.contains(targetTable)));
            return;
        }

        if (!state.inTargetExpression) {
            return;
        }
        CorrelatedOwner correlated = findCorrelatedOwner(property.getOwner());
        SourceTable source = correlated == null ? null : correlated.source;
        if (source == null) {
            SourceNode binding = correlated == null ? null : correlated.binding;
            if (binding != null) {
                bindJoinProperty(binding, rawName, name);
                return;
            }
            if (context != null || lateralScopeActive()) {
                throw new MetadataNotFoundException(
                        property.getOwner() == null ? name : property.getOwner().toString());
            }
            addColumnMapping(lastIdentifier(property.getOwner() == null
                    ? null : property.getOwner().toString()), name, false, null);
            return;
        }
        if (source.metadataTable != null
                && MetadataCatalog.resolveColumnName(source.metadataTable, rawName,
                        currentDialect()) == null) {
            throw new ColumnNotFoundException(name, displayNames(currentSources()));
        }
        if (source.metadataTable == null) {
            String key = identifierKey(rawName);
            int matches = 0;
            for (String outputKey : source.outputKeys) {
                if (key != null && key.equals(outputKey)) {
                    matches++;
                }
            }
            if (matches > 1) {
                throw new AmbiguousColumnException(name,
                        displayNames(java.util.Collections.singletonList(source)));
            }
        }
        if (source.metadataTable == null && source.outputNamesKnown
                && !source.outputKeys.contains(identifierKey(rawName))) {
            throw new ColumnNotFoundException(name, displayNames(currentSources()));
        }
        learnColumn(source, rawName, true);
        addColumnMapping(source, rawName);
    }

    private void bindJoinProperty(SourceNode binding, String rawName, String name) {
        String key = identifierKey(rawName);
        List<StarOutput> matches = new ArrayList<>();
        for (StarOutput output : binding.plan) {
            for (StarColumn column : output.sources) {
                if (column.key != null && column.key.equals(key)) {
                    matches.add(output);
                    break;
                }
            }
        }
        if (binding.knownColumns || bindingNamesKnown(binding)) {
            if (matches.isEmpty()) {
                throw new ColumnNotFoundException(name, displayNames(binding.sources));
            }
            if (matches.size() > 1) {
                throw new AmbiguousColumnException(name,
                        displayNames(planSources(matches)));
            }
            List<StarColumn> known = knownColumns(matches.get(0));
            if (!known.isEmpty()) {
                emitBindingColumns(known);
            } else if (state.uncertainOrdinal < 0 || state.currentOrdinal != state.uncertainOrdinal) {
                String targetTable = state.stackTargetTable.peek();
                for (SourceTable source : starOutputSources(matches.get(0))) {
                    instructions.add(new Instruction(OperationType.COLUMNS_INJECT,
                            source.displayName, targetTable, state.currentOrdinal,
                            source.temporary, state.tempTables.contains(targetTable)));
                }
            }
            return;
        }
        // A USING-merged output names its column even when the source identity is
        // unknown, so ambiguity is decided by visible output positions first.
        int definiteMatches = 0;
        for (StarOutput output : matches) {
            if (output.nameKnown) {
                definiteMatches++;
            }
        }
        if (definiteMatches > 1) {
            throw new AmbiguousColumnException(name, displayNames(planSources(matches)));
        }
        if (definiteMatches == 1) {
            for (StarOutput output : matches) {
                if (!output.nameKnown) {
                    continue;
                }
                List<StarColumn> known = knownColumns(output);
                if (!known.isEmpty()) {
                    emitBindingColumns(known);
                } else {
                    for (SourceTable source : starOutputSources(output)) {
                        instructions.add(new Instruction(OperationType.COLUMNS_INJECT,
                                source.displayName, state.stackTargetTable.peek(), state.currentOrdinal,
                                source.temporary,
                                state.tempTables.contains(state.stackTargetTable.peek())));
                    }
                }
                return;
            }
        }
        // Only bind when exactly one source can own the column.
        List<SourceTable> candidates = new ArrayList<>();
        for (SourceTable source : binding.sources) {
            if (!sourceNamesKnown(source) || source.outputKeys.contains(key)) {
                candidates.add(source);
            }
        }
        if (candidates.size() == 1) {
            addColumnMapping(candidates.get(0), rawName);
        }
    }

    private static List<StarColumn> knownColumns(StarOutput output) {
        List<StarColumn> known = new ArrayList<>();
        for (StarColumn column : output.sources) {
            if (column.nameKnown) {
                known.add(column);
            }
        }
        return known;
    }

    private static List<SourceTable> planSources(List<StarOutput> outputs) {
        List<SourceTable> sources = new ArrayList<>();
        for (StarOutput output : outputs) {
            for (StarColumn column : knownColumns(output)) {
                if (!sources.contains(column.source)) {
                    sources.add(column.source);
                }
            }
        }
        return sources;
    }

    private void emitBindingColumns(List<StarColumn> columns) {
        if (columns.isEmpty()) {
            return;
        }
        String targetTable = state.stackTargetTable.peek();
        String targetColumn = state.stackTargetColumn.peek();
        for (StarColumn column : columns) {
            if (!column.source.wildcardContribution) {
                continue;
            }
            Instruction wildcard = new Instruction(OperationType.COLUMNS_INJECT,
                    lineageTableName(column.source), targetTable, state.currentOrdinal,
                    column.source.temporary, state.tempTables.contains(targetTable));
            wildcard.setPositionalWildcard(true);
            // The contribution only covers the named column's own position.
            wildcard.setWildcardWidthKnown(true);
            wildcard.setWildcardWidth(1);
            instructions.add(wildcard);
            state.targetWildcardContribution.put(targetTable, Boolean.TRUE);
        }
        if (state.uncertainOrdinal >= 0 && state.currentOrdinal == state.uncertainOrdinal) {
            // The position is unknown: the table-level contribution above is enough.
            return;
        }
        for (StarColumn column : columns) {
            DbType dialect = currentDialect();
            String sourceColumn;
            if (column.source.temporary) {
                sourceColumn = renderSourceColumn(column);
            } else if (column.source.metadataTable == null
                    && (dialect == DbType.hive || dialect == DbType.antspark)) {
                sourceColumn = renderIdentifier(
                        hivePlannedColumn(column.column));
            } else if (column.quoted) {
                sourceColumn = renderSourceColumn(column);
            } else {
                sourceColumn = renderIdentifier(
                        column.source.metadataTable == null
                                ? uncatalogedColumnSpelling(column.column)
                                : canonicalColumnName(column.column));
            }
            instructions.add(new Instruction(OperationType.COLUMN_MAPPING,
                    lineageTableName(column.source) + "." + sourceColumn,
                    targetTable + "." + targetColumn, state.currentOrdinal,
                    column.source.temporary, state.tempTables.contains(targetTable)));
        }
    }

    private static String renderSourceColumn(StarColumn column) {
        if (column.quoted) {
            return quoteIdentifierValue(column.column);
        }
        return renderIdentifier(column.column);
    }

    private void recordBindingStar(String target, SourceNode binding, int startPosition) {
        List<String> declared = currentTargetColumnAliases(target);
        List<String> declaredKeys = currentDeclaredKeys(target);
        int position = startPosition;
        for (StarOutput output : binding.plan) {
            if (output.sources.isEmpty()) {
                continue;
            }
            String lockedName = lockedOutputName(target, position);
            String lockedKey = lockedOutputKey(target, position);
            String name;
            String key;
            if (lockedName != null) {
                name = lockedName;
                key = lockedKey != null ? lockedKey : identifierKey(name);
            } else {
                boolean declaredName = declared != null && position < declared.size();
                boolean stableName = output.nameKnown && output.stable;
                name = declaredName ? declared.get(position)
                        : stableName ? output.sources.get(0).column
                        : "col" + (position + 1);
                key = declaredName && declaredKeys != null
                        && position < declaredKeys.size() ? declaredKeys.get(position)
                        : stableName ? output.sources.get(0).key : identifierKey(name);
                if (isExistingOutput(state.targetOutputs.get(target), name, position)) {
                    int suffix = position + 1;
                    do {
                        name = "col" + suffix++;
                        key = identifierKey(name);
                    } while (isExistingOutput(state.targetOutputs.get(target), name, position));
                }
                markOutputNameKnown(target, position, declaredName || stableName);
            }
            setTargetOutput(target, position++, name, key);
        }
    }

    private void mapBindingStar(String targetTable, SourceNode binding, int startPosition) {
        List<String> declared = currentTargetColumnAliases(targetTable);
        List<String> registered = state.targetOutputs.get(targetTable);
        boolean uncertain = state.uncertainOrdinal >= 0 && state.uncertainOrdinal == startPosition;
        boolean targetTemporary = state.tempTables.contains(targetTable);
        int position = startPosition;
        for (StarOutput output : binding.plan) {
            if (output.sources.isEmpty()) {
                continue;
            }
            String lockedName = lockedOutputName(targetTable, position);
            String targetName = lockedName != null ? lockedName
                    : registered != null && position < registered.size()
                            && registered.get(position) != null
                                    ? registered.get(position)
                            : declared != null && position < declared.size()
                                    ? declared.get(position)
                            : output.sources.get(0).column;
            if (!uncertain) {
                for (StarColumn column : output.sources) {
                    // Columns whose names or positions could not be determined would be
                    // guesses; keep the uncertain source as a wildcard injection.
                    if (!column.nameKnown || !output.stable) {
                        instructions.add(new Instruction(OperationType.COLUMNS_INJECT,
                                column.source.displayName, targetTable, position,
                                column.source.temporary, targetTemporary));
                        continue;
                    }
                    instructions.add(new Instruction(OperationType.COLUMN_MAPPING,
                            column.source.displayName + "." + renderSourceColumn(column),
                            targetTable + "." + renderIdentifier(targetName), position,
                            column.source.temporary, targetTemporary));
                }
            }
            position++;
        }
    }

    /**
     * Projection in metadata-free mode: known sources are mapped column by column, unknown
     * ones stay table-level wildcards instead of being expanded into invented names.
     */
    /**
     * Projection in metadata-free mode. Known columns follow the visible plan (so JOIN
     * USING merges and UNION names still apply), while unknown sources stay table-level
     * wildcards. Placeholder names are never registered.
     */
    private boolean hasColumnMappingTo(String targetReference) {
        for (Instruction instruction : instructions) {
            if ((instruction.getOperation() == OperationType.COLUMN_MAPPING
                    || instruction.getOperation() == OperationType.EXPR_MAPPING)
                    && targetReference.equals(instruction.getTo())) {
                return true;
            }
        }
        return false;
    }

    private int emitDetachedStar(String targetTable, List<SourceTable> sources,
            int startPosition) {
        SourceNode node = state.stackSourceNode.isEmpty() ? null : state.stackSourceNode.peek();
        return emitDetachedPlan(targetTable, currentStarPlan(sources), sources, node,
                startPosition);
    }

    private int emitDetachedPlan(String targetTable, List<StarOutput> template,
            List<SourceTable> sources, SourceNode node, int startPosition) {
        List<String> declared = currentTargetColumnAliases(targetTable);
        boolean targetTemporary = state.tempTables.contains(targetTable);

        boolean uncertainPosition = state.uncertainOrdinal >= 0
                && state.uncertainOrdinal == startPosition;
        boolean determinate = !sources.isEmpty() && !uncertainPosition;
        for (SourceTable source : sources) {
            if (!sourceNamesKnown(source)) {
                determinate = false;
                break;
            }
        }
        if (!determinate) {
            state.targetWildcardContribution.put(targetTable, Boolean.TRUE);
            // Contract: positions cannot be derived from column names. Propagate the
            // unknown sources recorded at each visible position instead of guessing.
            if (sources.size() == 1 && sources.get(0).temporary
                    && !wildcardSpansOf(sources.get(0).displayName).isEmpty()) {
                SourceTable source = sources.get(0);
                List<WildcardSpan> spans = wildcardSpansOf(source.displayName);
                int sourceWidth = uncertainPosition ? -1
                        : sourceWidthKnown(source)
                                ? Math.max(sourceKnownWidth(source), maxSpanEnd(spans))
                                : state.currentStarBudget > 0 ? state.currentStarBudget : -1;
                for (WildcardSpan span : spans) {
                    if (uncertainPosition
                            || sourceWidth <= 0 && span.end == Integer.MAX_VALUE) {
                        // No width is known yet: keep the span open so an outer
                        // projection can bound it instead of shrinking it to one column.
                        Instruction wildcard = new Instruction(
                                OperationType.COLUMNS_INJECT, span.source, targetTable,
                                startPosition + span.start, span.temporary, targetTemporary);
                        wildcard.setPositionalWildcard(true);
                        instructions.add(wildcard);
                        recordWildcardSpan(targetTable, startPosition + span.start,
                                Integer.MAX_VALUE, span.source, span.temporary);
                        continue;
                    }
                    int width = sourceWidth > 0 ? sourceWidth : maxSpanEnd(spans);
                    int end = span.end == Integer.MAX_VALUE
                            ? Math.max(width, span.start + 1)
                            : Math.min(span.end, Math.max(width, span.start + 1));
                    for (int position = span.start; position < end; position++) {
                        Instruction wildcard = new Instruction(
                                OperationType.COLUMNS_INJECT, span.source, targetTable,
                                startPosition + position, span.temporary, targetTemporary);
                        wildcard.setPositionalWildcard(true);
                        wildcard.setWildcardWidthKnown(true);
                        wildcard.setWildcardWidth(1);
                        instructions.add(wildcard);
                        recordWildcardSpan(targetTable, startPosition + position,
                                startPosition + position + 1, span.source, span.temporary);
                    }
                }
                int totalWidth = Math.max(sourceWidth > 0 ? sourceWidth
                        : maxSpanEnd(spans), 1);
                List<String> sourceNames = sourceColumnNames(source);
                List<String> declaredTarget = currentTargetColumnAliases(targetTable);
                for (int relative = 0; !uncertainPosition
                        && relative < totalWidth; relative++) {
                    if (relative >= sourceNames.size()) {
                        continue;
                    }
                    String name = sourceNames.get(relative);
                    if (name == null) {
                        continue;
                    }
                    String sourceReference = source.displayName + "."
                            + renderIdentifier(name);
                    boolean covered = coveredBySpan(spans, relative);
                    if (covered) {
                        if (!hasColumnMappingTo(sourceReference)) {
                            continue;
                        }
                    } else if (!sourceOutputNameKnown(source, relative)) {
                        continue;
                    }
                    String outputName = declaredTarget != null
                            && startPosition + relative < declaredTarget.size()
                                    ? declaredTarget.get(startPosition + relative) : name;
                    String locked = lockedOutputName(targetTable,
                            startPosition + relative);
                    String lockedKey = lockedOutputKey(targetTable,
                            startPosition + relative);
                    if (locked != null) {
                        outputName = locked;
                    }
                    instructions.add(new Instruction(OperationType.COLUMN_MAPPING,
                            sourceReference,
                            targetTable + "." + renderIdentifier(outputName),
                            startPosition + relative, source.temporary,
                            targetTemporary));
                    List<String> declaredKeys = currentDeclaredKeys(targetTable);
                    String declaredKey = declaredKeys != null
                            && startPosition + relative < declaredKeys.size()
                                    ? declaredKeys.get(startPosition + relative)
                                    : null;
                    String sourceKey = relative < source.outputKeys.size()
                            ? source.outputKeys.get(relative) : null;
                    setTargetOutput(targetTable, startPosition + relative,
                            outputName, lockedKey != null ? lockedKey
                                    : declaredKey != null ? declaredKey
                                            : sourceKey != null ? sourceKey
                                                    : identifierKey(outputName));
                }
                return totalWidth;
            }
            boolean planAmbiguous = node != null && node.widthKnown
                    && hasAmbiguousOutput(node.plan, node.width);
            boolean pendingShape = false;
            for (SourceTable source : sources) {
                if (source.temporary && !sourceNamesKnown(source)
                        && sourceWidthKnown(source)
                        && wildcardSpansOf(source.displayName).isEmpty()) {
                    // A forward CTE has a known width but its body has not run yet,
                    // so its wildcard provenance is still unknown; keep a table-level
                    // claim instead of emitting nothing.
                    pendingShape = true;
                    break;
                }
            }
            if (!planAmbiguous && !pendingShape && !uncertainPosition && node != null
                    && node.widthKnown && !node.plan.isEmpty()) {
                int position = startPosition;
                int limit = startPosition + node.width;
                Map<SourceTable, Integer> seen = new java.util.IdentityHashMap<>();
                for (StarOutput output : node.plan) {
                    if (position >= limit || output.sources.isEmpty()) {
                        continue;
                    }
                    for (StarColumn column : output.sources) {
                        SourceTable source = column.source;
                        int relative = seen.containsKey(source)
                                ? seen.get(source) : 0;
                        seen.put(source, relative + 1);
                        for (WildcardRef ref : wildcardSourcesAt(source, column,
                                relative)) {
                            Instruction wildcard = new Instruction(
                                    OperationType.COLUMNS_INJECT, ref.name, targetTable,
                                    position, ref.temporary, targetTemporary);
                            wildcard.setPositionalWildcard(true);
                            wildcard.setWildcardWidthKnown(true);
                            wildcard.setWildcardWidth(1);
                            instructions.add(wildcard);
                            recordWildcardSpan(targetTable, position, position + 1,
                                    ref.name, ref.temporary);
                        }
                    }
                    position++;
                }
                return Math.max(node.width, 1);
            }
            boolean pendingBounded = pendingShape && node != null && node.widthKnown;
            boolean ambiguousBounded = (planAmbiguous || pendingBounded)
                    && !uncertainPosition && node != null && node.widthKnown;
            boolean allKnown = !planAmbiguous && !pendingShape && !uncertainPosition
                    && !sources.isEmpty() && allWidthKnown(sources);
            boolean budgetBounded = !allKnown && !ambiguousBounded
                    && !uncertainPosition && state.currentStarBudget > 0;
            int position = startPosition;
            for (int index = 0; index < sources.size(); index++) {
                SourceTable source = sources.get(index);
                int sourceStart = allKnown ? position : startPosition;
                List<WildcardSpan> sourceSpans = source.temporary
                        ? wildcardSpansOf(source.displayName)
                        : java.util.Collections.<WildcardSpan>emptyList();
                if (allKnown && !sourceSpans.isEmpty()) {
                    // A derived source already knows where its own wildcards sit;
                    // re-emit those positions instead of covering its constant slots.
                    int width = sourceKnownWidth(source);
                    for (WildcardSpan span : sourceSpans) {
                        int start = sourceStart + span.start;
                        int end = Math.min(sourceStart + width,
                                span.end == Integer.MAX_VALUE ? start + 1
                                        : sourceStart + span.end);
                        for (int target = start; target < end; target++) {
                            Instruction perPosition = new Instruction(
                                    OperationType.COLUMNS_INJECT, span.source,
                                    targetTable, target, span.temporary, targetTemporary);
                            perPosition.setPositionalWildcard(true);
                            perPosition.setWildcardWidthKnown(true);
                            perPosition.setWildcardWidth(1);
                            instructions.add(perPosition);
                            recordWildcardSpan(targetTable, target, target + 1,
                                    span.source, span.temporary);
                        }
                    }
                    position += width;
                    continue;
                }
                Instruction wildcard = new Instruction(OperationType.COLUMNS_INJECT,
                        lineageTableName(source), targetTable, sourceStart,
                        source.temporary, targetTemporary);
                wildcard.setPositionalWildcard(true);
                int sourceWidth = -1;
                if (ambiguousBounded) {
                    // The projection's total width is fixed; each source keeps a
                    // table-level claim on that whole range, and nothing beyond it.
                    sourceWidth = Math.max(node.width, 1);
                } else if (allKnown) {
                    sourceWidth = sourceKnownWidth(source);
                } else if (budgetBounded) {
                    // The UNION width pins the whole star; every source keeps a
                    // table-level claim on that range.
                    sourceWidth = state.currentStarBudget;
                }
                if (sourceWidth > 0) {
                    wildcard.setWildcardWidthKnown(true);
                    wildcard.setWildcardWidth(sourceWidth);
                    for (int offset = 0; offset < sourceWidth; offset++) {
                        recordWildcardSpan(targetTable, sourceStart + offset,
                                sourceStart + offset + 1, lineageTableName(source),
                                source.temporary);
                    }
                    position += sourceWidth;
                } else {
                    recordWildcardSpan(targetTable, sourceStart, Integer.MAX_VALUE,
                            lineageTableName(source), source.temporary);
                }
                instructions.add(wildcard);
            }
            int advance;
            if (ambiguousBounded) {
                // The projection's total width is still fixed; only the per-position
                // ownership is unknown, so later projections keep their place.
                advance = node.width;
            } else if (budgetBounded) {
                advance = state.currentStarBudget;
            } else if (uncertainPosition) {
                advance = knownWidthSum(sources);
            } else {
                advance = position - startPosition;
            }
            return Math.max(advance,
                    ambiguousBounded || budgetBounded ? 1 : sources.size());
        }

        Set<String> used = new HashSet<>();
        int position = startPosition;
        for (StarOutput output : template) {
            if (output.sources.isEmpty()) {
                continue;
            }
            String lockedName = lockedOutputName(targetTable, position);
            String name = lockedName != null ? lockedName
                    : declared != null && position < declared.size()
                            ? declared.get(position)
                    : output.nameKnown ? output.sources.get(0).column : null;
            boolean exact = name != null && output.nameKnown;
            if (exact) {
                for (StarColumn column : output.sources) {
                    if (!column.nameKnown) {
                        exact = false;
                        break;
                    }
                }
            }
            if (exact && !used.add(name)) {
                exact = false;
            }
            if (exact && isExistingOutput(state.targetOutputs.get(targetTable), name, position)) {
                exact = false;
            }
            if (exact) {
                for (StarColumn column : output.sources) {
                    instructions.add(new Instruction(OperationType.COLUMN_MAPPING,
                            column.source.displayName + "."
                                    + renderSourceColumn(column),
                            targetTable + "." + renderIdentifier(name), position,
                            column.source.temporary, targetTemporary));
                }
                String outputKey;
                if (lockedName != null) {
                    outputKey = lockedOutputKey(targetTable, position);
                } else if (declared != null && position < declared.size()) {
                    List<String> declaredKeys = currentDeclaredKeys(targetTable);
                    outputKey = declaredKeys != null && position < declaredKeys.size()
                            ? declaredKeys.get(position) : identifierKey(name);
                } else {
                    outputKey = output.sources.get(0).key;
                }
                setTargetOutput(targetTable, position, name,
                        outputKey == null ? identifierKey(name) : outputKey);
                markOutputNameKnown(targetTable, position, true);
            } else {
                for (SourceTable source : starOutputSources(output)) {
                    instructions.add(new Instruction(OperationType.COLUMNS_INJECT,
                            lineageTableName(source), targetTable, position,
                            source.temporary, targetTemporary));
                }
            }
            position++;
        }
        for (SourceTable source : sources) {
            if (!source.wildcardContribution) {
                continue;
            }
            // The source also carries rows from an unknown table: keep a table-level
            // edge next to the precise column mappings.
            Instruction wildcard = new Instruction(OperationType.COLUMNS_INJECT,
                    lineageTableName(source), targetTable, startPosition,
                    source.temporary, targetTemporary);
            wildcard.setPositionalWildcard(true);
            // The union's names (and hence positions) are known even though another
            // branch contributes unknown rows.
            wildcard.setWildcardWidthKnown(true);
            instructions.add(wildcard);
            state.targetWildcardContribution.put(targetTable, Boolean.TRUE);
        }
        return position - startPosition;
    }

    /**
     * The physical table identity of a source: an alias is only a SQL binding, not a
     * lineage vertex.
     */
    /**
     * A canonical physical table reference: dialect-equivalent spellings such as
     * PostgreSQL {@code "a"} and {@code a} map to the same identity.
     */
    /**
     * A canonical column identity: PostgreSQL folds unquoted column names to lower
     * case, quoted names keep their case.
     */
    private String uncatalogedColumnSpelling(String raw) {
        DbType dialect = currentDialect();
        return dialect == DbType.hive || dialect == DbType.antspark
                ? hiveColumnIdentity(raw) : canonicalColumnName(raw);
    }

    String canonicalColumnName(String raw) {
        if (raw == null) {
            return null;
        }
        String name = normalizeIdentifier(raw);
        if (!detachedMetadata() || MetadataCatalog.isQuotedIdentifier(raw)
                || currentDialect() != DbType.postgresql) {
            return name;
        }
        return name.toLowerCase(Locale.ROOT);
    }

    private String lineageTableName(SourceTable source) {
        if (source.temporary || source.metadataTable != null) {
            return source.displayName;
        }
        if (source.rawName != null) {
            return sessionPhysicalReference(source.rawName);
        }
        return source.writtenName == null ? source.displayName : source.writtenName;
    }

    private boolean uniqueOutputNames(SourceTable source, String targetTable, int position) {
        List<String> names = sourceColumnNames(source);
        Set<String> seen = new HashSet<>();
        List<String> existing = state.targetOutputs.get(targetTable);
        for (String name : names) {
            if (!seen.add(name)) {
                return false;
            }
            if (existing != null && isExistingOutput(existing, name, position + seen.size() - 1)) {
                return false;
            }
        }
        return true;
    }

    private void mapSourceStar(SourceTable source, String targetTable, int startPosition) {
        List<String> declared = currentTargetColumnAliases(targetTable);
        boolean uncertain = state.uncertainOrdinal >= 0 && state.uncertainOrdinal == startPosition;
        boolean targetTemporary = state.tempTables.contains(targetTable);
        int position = startPosition;
        for (StarOutput output : planFromSource(source)) {
            if (output.sources.isEmpty()) {
                continue;
            }
            String lockedName = lockedOutputName(targetTable, position);
            String targetName = lockedName != null ? lockedName
                    : declared != null && position < declared.size()
                            ? declared.get(position) : output.sources.get(0).column;
            if (!uncertain) {
                for (StarColumn column : output.sources) {
                    instructions.add(new Instruction(OperationType.COLUMN_MAPPING,
                            column.source.displayName + "." + renderIdentifier(column.column),
                            targetTable + "." + renderIdentifier(targetName), position,
                            column.source.temporary, targetTemporary));
                }
            }
            position++;
        }
    }

    private void visitAllColumn() {
        if (!state.inTargetExpression) {
            return;
        }
        List<SourceTable> sources = currentSources();
        if (sources.isEmpty()) {
            return;
        }
        String targetTable = state.stackTargetTable.peek();
        if (detachedMetadata()) {
            emitDetachedStar(targetTable, sources, state.currentOrdinal);
            return;
        }
        boolean targetTemporary = state.tempTables.contains(targetTable);
        Map<String, List<SourceTable>> using = state.stackUsing.isEmpty()
                ? null : state.stackUsing.peek();
        if (context == null && !state.stackSourceNode.isEmpty()
                && !state.stackSourceNode.peek().plan.isEmpty()
                && (using != null && !using.isEmpty() || state.currentStarBudget > 0)) {
            mapBindingStar(targetTable, state.stackSourceNode.peek(), state.currentOrdinal);
            return;
        }
        boolean planComplete = context == null && allSourceNamesKnown(sources)
                && !state.stackStarPlan.isEmpty() && !state.stackStarPlan.peek().isEmpty();
        if (useVisiblePlan(sources, using) || planComplete) {
            List<String> declared = currentTargetColumnAliases(targetTable);
            boolean uncertain = state.uncertainOrdinal >= 0 && state.currentOrdinal == state.uncertainOrdinal;
            int position = state.currentOrdinal;
            for (StarOutput output : currentStarPlan(sources)) {
                if (output.sources.isEmpty()) {
                    continue;
                }
                String lockedName = lockedOutputName(targetTable, position);
                String targetName = lockedName != null ? lockedName
                        : declared != null && position < declared.size()
                                ? declared.get(position) : output.sources.get(0).column;
                if (!uncertain) {
                    for (StarColumn column : output.sources) {
                        instructions.add(new Instruction(OperationType.COLUMN_MAPPING,
                                column.source.displayName + "."
                                        + renderIdentifier(column.column),
                                targetTable + "." + renderIdentifier(targetName), position,
                                column.source.temporary, targetTemporary));
                    }
                }
                position++;
            }
            return;
        }
        if (context == null) {
            instructions.add(new Instruction(OperationType.COLUMNS_INJECT,
                    sources.get(0).displayName, targetTable, state.currentOrdinal,
                    sources.get(0).temporary, targetTemporary));
            return;
        }
        int position = state.currentOrdinal;
        for (SourceTable source : sources) {
            instructions.add(new Instruction(OperationType.COLUMNS_INJECT,
                    source.displayName, targetTable, position, source.temporary,
                    targetTemporary));
            position += columnCount(source);
        }
    }

    private List<SourceTable> resolveUnqualified(String rawColumn) {
        List<SourceTable> sources = currentSources();
        if (sources.isEmpty()) {
            return sources;
        }
        String columnName = normalizeIdentifier(rawColumn);
        String columnKey = identifierKey(rawColumn);

        if (context != null && !state.stackStarPlan.isEmpty() && !state.stackStarPlan.peek().isEmpty()) {
            List<StarOutput> matches = new ArrayList<>();
            List<SourceTable> matchedSources = new ArrayList<>();
            for (StarOutput output : state.stackStarPlan.peek()) {
                boolean matched = false;
                for (StarColumn column : output.sources) {
                    if (column.key != null && column.key.equals(columnKey)) {
                        matched = true;
                        if (!matchedSources.contains(column.source)) {
                            matchedSources.add(column.source);
                        }
                    }
                }
                if (matched) {
                    matches.add(output);
                }
            }
            if (matches.size() == 1) {
                if (detachedMetadata()) {
                    // A source whose column set is unknown may also own the column,
                    // unless the learned metadata proves it cannot.
                    List<SourceTable> unknown = refineWithLearned(
                            unknownColumnSources(sources, matchedSources), rawColumn);
                    Map<String, List<SourceTable>> participants = state.stackUsing.isEmpty()
                            ? null : state.usingParticipantsByIdentity.get(state.stackUsing.peek());
                    if (participants != null && participants.containsKey(columnKey)) {
                        unknown.removeAll(participants.get(columnKey));
                    }
                    if (!unknown.isEmpty()) {
                        throw new AmbiguousColumnException(columnName,
                                displayNames(allSources(matchedSources, unknown)));
                    }
                    List<SourceTable> extras = new ArrayList<>();
                    List<SourceTable> usingSides = participantsForCurrentUsing(columnKey);
                    for (SourceTable source : sources) {
                        if (matchedSources.contains(source)
                                || usingSides.contains(source)
                                || !sourceColumnNamesKnown(source)) {
                            continue;
                        }
                        if (ownsOuterColumn(source, columnKey)) {
                            extras.add(source);
                        }
                    }
                    if (!extras.isEmpty()) {
                        throw new AmbiguousColumnException(columnName,
                                displayNames(allSources(matchedSources, extras)));
                    }
                }
                return matchedSources;
            }
            if (matches.size() > 1) {
                throw new AmbiguousColumnException(columnName, displayNames(matchedSources));
            }
        }

        if (context == null && !state.stackStarPlan.isEmpty()
                && !state.stackStarPlan.peek().isEmpty()) {
            // Ambiguity between fully known outputs is decided by the visible plan even
            // when another joined source is unknown.
            List<StarOutput> definite = new ArrayList<>();
            for (StarOutput output : state.stackStarPlan.peek()) {
                boolean matched = false;
                for (StarColumn column : output.sources) {
                    if (column.key != null && column.key.equals(columnKey)) {
                        matched = true;
                    }
                }
                if (matched && output.nameKnown) {
                    definite.add(output);
                }
            }
            if (definite.size() > 1) {
                List<SourceTable> ambiguous = new ArrayList<>();
                for (StarOutput output : definite) {
                    ambiguous.addAll(starOutputSources(output));
                }
                throw new AmbiguousColumnException(columnName, displayNames(ambiguous));
            }
        }

        boolean nodeNamesComplete = context == null && !state.stackSourceNode.isEmpty()
                && nodeNamesKnown(state.stackSourceNode.peek());
        if (context == null && (allSourceNamesKnown(sources) || nodeNamesComplete)
                && !state.stackStarPlan.isEmpty() && !state.stackStarPlan.peek().isEmpty()) {
            // The visible JOIN output plan already applies USING merge rules, so it is
            // authoritative once every source names its columns.
            List<StarOutput> matches = new ArrayList<>();
            for (StarOutput output : state.stackStarPlan.peek()) {
                for (StarColumn column : output.sources) {
                    if (column.key != null && column.key.equals(columnKey)) {
                        matches.add(output);
                        break;
                    }
                }
            }
            if (matches.size() == 1) {
                // Only sources whose column identity is confirmed may become edges.
                List<SourceTable> resolved = new ArrayList<>();
                for (StarColumn column : matches.get(0).sources) {
                    if (column.key != null && column.key.equals(columnKey) && column.nameKnown
                            && !resolved.contains(column.source)) {
                        resolved.add(column.source);
                    }
                }
                return resolved;
            }
            if (matches.size() > 1) {
                List<SourceTable> ambiguous = new ArrayList<>();
                for (StarOutput output : matches) {
                    ambiguous.addAll(starOutputSources(output));
                }
                throw new AmbiguousColumnException(columnName, displayNames(ambiguous));
            }
            throw new ColumnNotFoundException(columnName, displayNames(sources));
        }

        Map<String, List<SourceTable>> using = state.stackUsing.isEmpty()
                ? null : state.stackUsing.peek();
        if (using != null && using.containsKey(columnKey)) {
            List<SourceTable> participants = using.get(columnKey);
            List<SourceTable> sides = participantsForCurrentUsing(columnKey);
            List<SourceTable> extras = new ArrayList<>();
            for (SourceTable source : sources) {
                if (participants.contains(source) || sides.contains(source)) {
                    continue;
                }
                if (ownsOuterColumn(source, columnKey)
                        || !sourceColumnNamesKnown(source)) {
                    extras.add(source);
                }
            }
            if (!extras.isEmpty()) {
                List<SourceTable> all = new ArrayList<>(participants);
                all.addAll(extras);
                throw new AmbiguousColumnException(columnName, displayNames(all));
            }
            return participants;
        }

        if (context == null) {
            if (detachedMetadata()) {
                // Without metadata the column must be attributable to exactly one source.
                List<SourceTable> owners = new ArrayList<>();
                for (SourceTable source : sources) {
                    if (source.outputNamesKnown) {
                        if (source.outputKeys.contains(columnKey)) {
                            owners.add(source);
                        }
                    } else if (source.learnable && learnedMetadata != null
                            && source.rawName != null
                            && learnedMetadata.knowsNormalizedTable(
                                    learnedTableKey(source.rawName))) {
                        if (learnedMetadata.hasNormalizedColumn(
                                learnedTableKey(source.rawName), identifierKey(rawColumn))) {
                            owners.add(source);
                        }
                    } else {
                        owners.add(source);
                    }
                }
                owners = refineWithLearned(owners, rawColumn);
                if (owners.size() == 1) {
                    return owners;
                }
                if (owners.size() > 1) {
                    throw new AmbiguousColumnException(columnName, displayNames(owners));
                }
                throw new ColumnNotFoundException(columnName, displayNames(sources));
            }
            // Without metadata, skip temporary sources whose known output list does not
            // contain the column; the first unknown source still accepts any column.
            for (SourceTable source : sources) {
                if (!source.outputNamesKnown || source.outputKeys.contains(columnKey)) {
                    List<SourceTable> result = new ArrayList<>();
                    result.add(source);
                    return result;
                }
            }
            throw new ColumnNotFoundException(columnName, displayNames(sources));
        }

        List<SourceTable> candidates = new ArrayList<>();
        boolean hasUnknownSource = false;
        for (SourceTable source : sources) {
            if (source.metadataTable != null) {
                if (MetadataCatalog.resolveColumnName(source.metadataTable, rawColumn,
                        currentDialect()) != null) {
                    candidates.add(source);
                }
            } else if (source.outputNamesKnown) {
                if (source.outputKeys.contains(columnKey)) {
                    candidates.add(source);
                }
            } else if (detachedMetadata()) {
                // The source has no known column set: it may own the column.
                candidates.add(source);
            } else {
                hasUnknownSource = true;
            }
        }
        if (detachedMetadata()) {
            candidates = refineWithLearned(candidates, rawColumn);
        }
        if (candidates.size() == 1) {
            return candidates;
        }
        if (candidates.size() > 1) {
            throw new AmbiguousColumnException(columnName, displayNames(candidates));
        }
        if (hasUnknownSource) {
            List<SourceTable> fallback = new ArrayList<>();
            fallback.add(sources.get(0));
            return fallback;
        }
        throw new ColumnNotFoundException(columnName, displayNames(sources));
    }

    private boolean useVisiblePlan(List<SourceTable> sources,
            Map<String, List<SourceTable>> using) {
        if (using == null || using.isEmpty()) {
            return false;
        }
        return context != null || allSourceNamesKnown(sources);
    }

    private SourceNode joinBinding(SQLExpr owner) {
        if (owner == null || state.stackJoinBindings.isEmpty()) {
            return null;
        }
        String key = identifierKey(lastSegment(owner.toString()));
        return key == null ? null : state.stackJoinBindings.peek().get(key);
    }

    private boolean isJoinAlias(SQLExpr owner) {
        return joinBinding(owner) != null;
    }

    private boolean allWidthKnown(List<SourceTable> sources) {
        for (SourceTable source : sources) {
            if (!sourceWidthKnown(source)) {
                return false;
            }
        }
        return true;
    }

    private boolean sourceWidthKnown(SourceTable source) {
        if (source.metadataTable != null || source.outputWidthKnown) {
            return true;
        }
        return source.temporary
                && Boolean.TRUE.equals(state.targetWidthKnown.get(source.displayName));
    }

    private int derivedWidth(String target, List<String> outputs, boolean widthKnown) {
        int width = outputs.size();
        if (widthKnown) {
            Integer known = state.targetMinWidth.get(target);
            if (known != null && known > 0) {
                width = Math.max(width, known);
            }
        }
        return width;
    }

    private int sourceKnownWidth(SourceTable source) {
        if (source.metadataTable != null) {
            return Math.max(source.metadataTable.getFields().size(), 1);
        }
        int width = source.outputColumns.size();
        if (source.temporary
                && Boolean.TRUE.equals(state.targetWidthKnown.get(source.displayName))) {
            Integer known = state.targetMinWidth.get(source.displayName);
            if (known != null && known > 0) {
                width = Math.max(width, known);
            }
        }
        if (width > 0) {
            return width;
        }
        return Math.max(sourceColumnNames(source).size(), 1);
    }

    /**
     * Drop sources that the learned metadata proves cannot own the column. Known catalog
     * sources and unknown sources are kept.
     */
    private List<SourceTable> refineWithLearned(List<SourceTable> candidates,
            String rawColumn) {
        if (learnedMetadata == null) {
            return candidates;
        }
        List<SourceTable> refined = new ArrayList<>();
        for (SourceTable source : candidates) {
            if (source.metadataTable != null || source.temporary || !source.learnable
                    || source.rawName == null) {
                refined.add(source);
                continue;
            }
            String key = learnedTableKey(source.rawName);
            if (learnedMetadata.hasNormalizedColumn(key, identifierKey(rawColumn))
                    || !learnedMetadata.knowsNormalizedTable(key)) {
                refined.add(source);
            }
        }
        return refined;
    }

    /**
     * The sources whose column set is unknown, so that they may own any column.
     */
    private List<SourceTable> unknownColumnSources(List<SourceTable> sources,
            List<SourceTable> excluded) {
        List<SourceTable> unknown = new ArrayList<>();
        for (SourceTable source : sources) {
            if (excluded.contains(source) || sourceNamesKnown(source)) {
                continue;
            }
            unknown.add(source);
        }
        return unknown;
    }

    private static List<SourceTable> allSources(List<SourceTable> first,
            List<SourceTable> second) {
        List<SourceTable> all = new ArrayList<>();
        all.addAll(first);
        all.addAll(second);
        return all;
    }

    private boolean allSourceNamesKnown(List<SourceTable> sources) {
        for (SourceTable source : sources) {
            if (!sourceNamesKnown(source)) {
                return false;
            }
        }
        return true;
    }

    private static List<SourceTable> starOutputSources(StarOutput output) {
        List<SourceTable> sources = new ArrayList<>();
        for (StarColumn column : output.sources) {
            if (!sources.contains(column.source)) {
                sources.add(column.source);
            }
        }
        return sources;
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
        String rawOwner = owner.toString();
        String ownerKey = qualifiedKey(rawOwner);
        for (SourceTable source : visibleSources()) {
            if (source.alias != null && qualifiedKey(source.alias).equals(ownerKey)) {
                return source;
            }
        }
        for (SourceTable source : visibleSources()) {
            if (source.temporary) {
                if (source.rawName != null
                        && qualifiedKey(source.rawName).equals(ownerKey)) {
                    return source;
                }
                continue;
            }
            String rawIdentifier = source.rawName != null
                    ? source.rawName : source.writtenName;
            if (qualifiedKey(source.displayName).equals(ownerKey)
                    || (rawIdentifier != null
                            && qualifiedKey(rawIdentifier).equals(ownerKey))) {
                return source;
            }
        }
        String[] ownerParts = MetadataCatalog.splitQualifiedNamePreservingQuotes(rawOwner);
        for (SourceTable source : visibleSources()) {
            if (source.temporary) {
                continue;
            }
            String[] displayParts = MetadataCatalog
                    .splitQualifiedNamePreservingQuotes(source.displayName);
            if (ownerParts.length > 0 && ownerParts.length <= displayParts.length) {
                boolean matched = true;
                for (int i = 0; i < ownerParts.length; i++) {
                    if (!identifierKey(ownerParts[i]).equals(
                            identifierKey(displayParts[displayParts.length - ownerParts.length + i]))) {
                        matched = false;
                        break;
                    }
                }
                if (matched) {
                    return source;
                }
            }
        }
        return null;
    }

    String qualifiedKey(String rawName) {
        String[] parts = MetadataCatalog.splitQualifiedNamePreservingQuotes(rawName);
        if (parts.length == 0) {
            return "";
        }
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                key.append('\u0001');
            }
            key.append(identifierKey(parts[i]));
        }
        return key.toString();
    }

    String canonicalColumnName(String table, String rawColumn) {
        if (context == null || state.tempTables.contains(table)) {
            return normalizeIdentifier(rawColumn);
        }
        try {
            Table metadata = context.getCatalog().findTableByDisplayName(table).getTable();
            String resolved = MetadataCatalog.resolveColumnName(metadata, rawColumn,
                    currentDialect());
            if (resolved == null) {
                throw new ColumnNotFoundException(normalizeIdentifier(rawColumn),
                        java.util.Collections.singletonList(table));
            }
            return resolved;
        } catch (MetadataNotFoundException e) {
            return normalizeIdentifier(rawColumn);
        }
    }

    private String insertColumnName(Table metadata, String tableName, SQLExpr column) {
        String raw = column.toString();
        if (metadata == null) {
            return canonicalColumnName(raw);
        }
        String resolved = MetadataCatalog.resolveColumnName(metadata, raw, currentDialect());
        if (resolved == null) {
            throw new ColumnNotFoundException(normalizeIdentifier(raw),
                    java.util.Collections.singletonList(tableName));
        }
        return resolved;
    }

    String renderInsertColumn(Table metadata, String tableName, SQLExpr column) {
        String raw = column.toString();
        DbType dialect = currentDialect();
        if ((dialect == DbType.hive || dialect == DbType.antspark)
                && column instanceof SQLTextLiteralExpr) {
            if (!Boolean.TRUE.equals(column.getAttribute(
                    ANSI_IDENTIFIER_ATTRIBUTE))) {
                // An INSERT target list requires identifiers; a quoted string is
                // not one.
                throw new ColumnNotFoundException(raw,
                        java.util.Collections.singletonList(tableName));
            }
            String name = ((SQLTextLiteralExpr) column).getText();
            if (metadata == null) {
                return renderHiveIdentifier(foldCase(name));
            }
            String quoted = quoteIdentifierValue(name);
            String resolved = MetadataCatalog.resolveColumnName(metadata, quoted,
                    dialect);
            if (resolved == null) {
                throw new ColumnNotFoundException(normalizeIdentifier(name),
                        java.util.Collections.singletonList(tableName));
            }
            return renderHiveIdentifier(resolved);
        }
        if ((dialect == DbType.hive || dialect == DbType.antspark)
                && metadata == null) {
            // Without a catalog, Hive/Spark column identity is case-insensitive,
            // but literal quotes remain part of the name.
            return renderHiveIdentifier(hiveColumnIdentity(raw));
        }
        String resolved = insertColumnName(metadata, tableName, column);
        if (MetadataCatalog.isQuotedIdentifier(raw)) {
            if (dialect == DbType.hive || dialect == DbType.antspark) {
                return renderHiveIdentifier(resolved);
            }
            return quoteIdentifierValue(resolved);
        }
        return renderIdentifier(resolved);
    }

    DbType currentDialect() {
        return context != null ? context.getCatalog().getDialect() : dbType;
    }

    private void addColumnMapping(SourceTable source, String columnName) {
        String sourceName = detachedMetadata() && !source.temporary
                ? lineageTableName(source) : source.displayName;
        if (source.wildcardContribution && source.temporary) {
            // Reading one named column from a derived table that also carries unknown
            // rows must not cut the table-level lineage.
            String target = state.stackTargetTable.peek();
            Instruction wildcard = new Instruction(OperationType.COLUMNS_INJECT,
                    lineageTableName(source), target, state.currentOrdinal,
                    source.temporary, state.tempTables.contains(target));
            wildcard.setPositionalWildcard(true);
            wildcard.setWildcardWidthKnown(true);
            wildcard.setWildcardWidth(1);
            instructions.add(wildcard);
            recordWildcardSpan(target, state.currentOrdinal, state.currentOrdinal + 1,
                    lineageTableName(source), source.temporary);
            state.targetWildcardContribution.put(target, Boolean.TRUE);
        }
        if (!source.outputColumns.isEmpty()) {
            String key = identifierKey(columnName);
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
                addColumnMapping(sourceName, normalizeIdentifier(columnName),
                        source.temporary, null);
            } else {
                addResolvedColumnMapping(sourceName, canonical,
                        source.temporary);
            }
            return;
        }
        if (source.metadataTable == null) {
            DbType dialect = currentDialect();
            if (dialect == DbType.hive || dialect == DbType.antspark) {
                addResolvedColumnMapping(sourceName,
                        hiveColumnIdentity(columnName), source.temporary);
            } else {
                addColumnMapping(sourceName, canonicalColumnName(columnName),
                        source.temporary, null);
            }
            return;
        }
        addColumnMapping(sourceName, columnName, source.temporary,
                source.metadataTable);
    }

    private void addColumnMapping(String sourceTable, String columnName,
            boolean sourceTemporary, Table sourceMetadata) {
        if (sourceMetadata != null) {
            String resolved = MetadataCatalog.resolveColumnName(sourceMetadata, columnName,
                    currentDialect());
            columnName = resolved != null ? resolved : normalizeIdentifier(columnName);
        } else {
            columnName = normalizeIdentifier(columnName);
        }

        if (state.uncertainOrdinal >= 0 && state.currentOrdinal == state.uncertainOrdinal) {
            // The projection's position cannot be derived from the declaration, so a
            // positional edge would be a guess.
            return;
        }
        String targetTable = state.stackTargetTable.peek();
        instructions.add(new Instruction(OperationType.COLUMN_MAPPING,
                sourceTable + "." + renderIdentifier(columnName),
                targetTable + "." + state.stackTargetColumn.peek(), state.currentOrdinal,
                sourceTemporary, state.tempTables.contains(targetTable)));
    }

    /** Write an edge for a name that is already the catalog/derived spelling. */
    private void addResolvedColumnMapping(String sourceTable, String columnName,
            boolean sourceTemporary) {
        if (state.uncertainOrdinal >= 0 && state.currentOrdinal == state.uncertainOrdinal) {
            return;
        }
        String targetTable = state.stackTargetTable.peek();
        instructions.add(new Instruction(OperationType.COLUMN_MAPPING,
                sourceTable + "." + renderIdentifier(columnName),
                targetTable + "." + state.stackTargetColumn.peek(), state.currentOrdinal,
                sourceTemporary, state.tempTables.contains(targetTable)));
    }

    private static String quoteIdentifierValue(String name) {
        return Identifiers.quoteDouble(name);
    }

    private static String renderIdentifier(String name) {
        return Identifiers.render(name);
    }

    private List<SourceTable> currentSources() {
        return state.stackSources.isEmpty() ? new ArrayList<SourceTable>() : state.stackSources.peek();
    }

    private List<SourceTable> visibleSources() {
        List<SourceTable> sources = currentSources();
        if (state.stackHiddenSources.isEmpty() || state.stackHiddenSources.peek().isEmpty()) {
            return sources;
        }
        Set<SourceTable> hidden = state.stackHiddenSources.peek();
        List<SourceTable> visible = new ArrayList<>();
        for (SourceTable source : sources) {
            if (!hidden.contains(source)) {
                visible.add(source);
            }
        }
        return visible;
    }

    /* ------------------------------------------------------------------ */
    /* insert / update / delete / create                                  */
    /* ------------------------------------------------------------------ */

    private final DmlStatementAnalyzer dml = new DmlStatementAnalyzer(this);

    private void processInsert(SQLInsertStatement insert) {
        dml.processInsert(insert);
    }

    private void processReplace(SQLReplaceStatement replace) {
        dml.processReplace(replace);
    }

    private void processTruncate(SQLTruncateStatement truncate) {
        dml.processTruncate(truncate);
    }

    private void processMerge(SQLMergeStatement merge) {
        dml.processMerge(merge);
    }

    private void processUpdate(SQLUpdateStatement update) {
        dml.processUpdate(update);
    }

    private void processDelete(SQLDeleteStatement delete) {
        dml.processDelete(delete);
    }

    private static String hivePlannedColumn(String column) {
        return DmlStatementAnalyzer.hivePlannedColumn(column);
    }

    private static String hiveColumnIdentity(String raw) {
        return DmlStatementAnalyzer.hiveColumnIdentity(raw);
    }

    private static String renderHiveIdentifier(String name) {
        return DmlStatementAnalyzer.renderHiveIdentifier(name);
    }

    private static String foldCase(String value) {
        return DmlStatementAnalyzer.foldCase(value);
    }

    private void processCreateTable(SQLCreateTableStatement create) {
        SQLExprTableSource tableSource = create.getTableSource();
        if (tableSource != null) {
            String written = normalizeQualifiedName(extractTableName(tableSource));
            registerTable(written, written, written, null, false);
        }
        if (create.getSelect() != null) {
            processSelect(create.getSelect());
        }
    }

    /* ------------------------------------------------------------------ */
    /* WHERE subqueries (tables only)                                     */
    /* ------------------------------------------------------------------ */

    void collectTablesFromExpression(SQLExpr expr) {
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
        SQLWithSubqueryClause with = select.getWithSubQuery();
        if (with != null) {
            Set<String> local = new HashSet<>();
            state.collectCteScopes.push(local);
            try {
                boolean recursive = Boolean.TRUE.equals(with.getRecursive());
                if (recursive) {
                    for (SQLWithSubqueryClause.Entry entry : with.getEntries()) {
                        if (entry.getAlias() != null) {
                            local.add(identifierKey(entry.getAlias()));
                        }
                    }
                }
                for (SQLWithSubqueryClause.Entry entry : with.getEntries()) {
                    collectTablesFromSelect(entry.getSubQuery());
                    if (!recursive && entry.getAlias() != null) {
                        local.add(identifierKey(entry.getAlias()));
                    }
                }
                collectTablesFromQuery(select.getQuery());
            } finally {
                state.collectCteScopes.pop();
            }
            return;
        }
        collectTablesFromQuery(select.getQuery());
    }

    private boolean collectCteNameVisible(String key) {
        for (Set<String> frame : state.collectCteScopes) {
            if (frame.contains(key)) {
                return true;
            }
        }
        return false;
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
            SQLExprTableSource exprTable = (SQLExprTableSource) tableSource;
            SQLSelect inline = queryExpressionSelect(exprTable.getExpr());
            if (inline != null) {
                collectTablesFromSelect(inline);
                return;
            }
            String name = extractTableName(exprTable);
            if (name != null && isSingleSegmentName(name)
                    && (collectCteNameVisible(identifierKey(name))
                            || findCteTarget(identifierKey(name)) != null)) {
                return;
            }
            resolveSource(exprTable);
        }
    }

    /* ------------------------------------------------------------------ */
    /* helpers                                                            */
    /* ------------------------------------------------------------------ */

    protected void init(boolean allowColumnProcessing) {
        tables = new HashSet<>();
        state.reset();
        instructions = state.learningOnly
                ? new java.util.AbstractList<Instruction>() {
                    @Override
                    public Instruction get(int index) {
                        throw new IndexOutOfBoundsException();
                    }

                    @Override
                    public int size() {
                        return 0;
                    }

                    @Override
                    public boolean add(Instruction element) {
                        return true;
                    }

                    @Override
                    public void add(int index, Instruction element) {
                    }
                }
                : new ArrayList<>();
    }

    public String getTempTableName() {
        String name = uniqueTarget(String.format("temp%d", state.tempTableNum++));
        if (state.tempTables != null) {
            state.tempTables.add(name);
        }
        return name;
    }

    private String uniqueTarget(String preferred) {
        String candidate = preferred;
        int suffix = 1;
        while (state.usedTargets.contains(candidate)) {
            candidate = preferred + "#" + suffix++;
        }
        state.usedTargets.add(candidate);
        return candidate;
    }

    static String rawColumnName(SQLExpr expr) {
        if (expr instanceof SQLIdentifierExpr) {
            return ((SQLIdentifierExpr) expr).getName();
        }
        if (expr instanceof SQLPropertyExpr) {
            return ((SQLPropertyExpr) expr).getName();
        }
        return expr.toString();
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

    static String normalizeIdentifier(String name) {
        return MetadataCatalog.normalizeIdentifier(name);
    }

    static String normalizeQualifiedName(String name) {
        String[] parts = MetadataCatalog.splitQualifiedName(name);
        return String.join(".", parts);
    }

    static String lastIdentifier(String name) {
        if (name == null) {
            return null;
        }
        String value = name.trim();
        int idx = value.lastIndexOf('.');
        return idx >= 0 ? value.substring(idx + 1) : value;
    }

}

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
    final TargetShapeAnalyzer targetShape = new TargetShapeAnalyzer(this);
    final StarProjectionAnalyzer starProjection = new StarProjectionAnalyzer(this);
    final QueryDispatcher queryDispatcher = new QueryDispatcher(this);
    final TableCollector tableCollector = new TableCollector(this);
    final ColumnMappingAnalyzer columnMapping = new ColumnMappingAnalyzer(this);
    final SourceShapeAnalyzer sourceShape = new SourceShapeAnalyzer(this);

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

    static SQLSelect queryExpressionSelect(SQLExpr expr) {
        return StaticShapeAnalyzer.queryExpressionSelect(expr);
    }

    static String lastSegment(String qualifiedName) {
        return StaticShapeAnalyzer.lastSegment(qualifiedName);
    }

    void processSelect(SQLSelect select) {
        queryDispatcher.processSelect(select);
    }

    void processWithSubQuery(SQLWithSubqueryClause withSubQuery) {
        queryDispatcher.processWithSubQuery(withSubQuery);
    }

    void processSelectQuery(SQLSelectQuery query) {
        queryDispatcher.processSelectQuery(query);
    }

    boolean isLateralSource(SQLTableSource source) {
        return queryDispatcher.isLateralSource(source);
    }

    final SelectAnalyzer selector = new SelectAnalyzer(this);

    void processQueryBlock(SQLSelectQueryBlock queryBlock) {
        selector.processQueryBlock(queryBlock);
    }

    int recordUncertainOuterStar(SQLPropertyExpr star, int position) {
        return selector.recordUncertainOuterStar(star, position);
    }

    void recordUncertainOuterMappings(SQLSelectItem item, int position) {
        selector.recordUncertainOuterMappings(item, position);
    }

    void recordUncertainOuterExpression(SQLExpr expr, String target, int position) {
        selector.recordUncertainOuterExpression(expr, target, position);
    }

    void recordOuterExpressionMappings(SQLSelectItem item, int position) {
        selector.recordOuterExpressionMappings(item, position);
    }

    void recordOuterExpression(SQLExpr expr, String target, String targetName, int position) {
        selector.recordOuterExpression(expr, target, targetName, position);
    }

    boolean outerStarNamesKnown(SQLPropertyExpr star) {
        return selector.outerStarNamesKnown(star);
    }

    static boolean coveredBySpan(List<WildcardSpan> spans, int relative) {
        return SelectAnalyzer.coveredBySpan(spans, relative);
    }

    boolean hasDuplicateNamedOutputs(SourceTable source) {
        return selector.hasDuplicateNamedOutputs(source);
    }

    String effectiveOutputName(SourceTable source, int index) {
        return selector.effectiveOutputName(source, index);
    }

    List<WildcardRef> wildcardRefsAt(SourceTable source, int relative) {
        return selector.wildcardRefsAt(source, relative);
    }

    int recordOuterStar(SQLPropertyExpr star, int position) {
        return selector.recordOuterStar(star, position);
    }

    void recordOuterColumn(SQLPropertyExpr property, String target, String targetName, int position) {
        selector.recordOuterColumn(property, target, targetName, position);
    }

    void addOuterColumnMapping(SourceTable source, String column, String target, String targetName, int position) {
        selector.addOuterColumnMapping(source, column, target, targetName, position);
    }

    void addOuterColumnMapping(SourceTable source, String column, String target, String targetName, int position, Boolean quoted) {
        selector.addOuterColumnMapping(source, column, target, targetName, position, quoted);
    }

    void addOuterWildcard(String sourceName, String target, int position, boolean temporary) {
        selector.addOuterWildcard(sourceName, target, position, temporary);
    }

    void addOuterWildcardOpen(SourceTable source, String target, int position) {
        selector.addOuterWildcardOpen(source, target, position);
    }

    void addOuterWildcard(SourceTable source, String target, int position) {
        selector.addOuterWildcard(source, target, position);
    }

    void recordOuterIdentifier(String rawName, String target, String targetName, int position) {
        selector.recordOuterIdentifier(rawName, target, targetName, position);
    }

    List<SourceTable> participantsForCurrentUsing(String key) {
        return selector.participantsForCurrentUsing(key);
    }

    List<SourceTable> currentUsingParticipants(String key) {
        return selector.currentUsingParticipants(key);
    }

    boolean ownsOuterColumn(SourceTable source, String key) {
        return selector.ownsOuterColumn(source, key);
    }

    boolean sourceColumnNamesKnown(SourceTable source) {
        return selector.sourceColumnNamesKnown(source);
    }

    String resolveOuterColumn(SourceTable source, String rawName) {
        return selector.resolveOuterColumn(source, rawName);
    }

    void recordScalarTableLevel(ScalarSubquery inner, String targetTable, int position, String targetColumn) {
        selector.recordScalarTableLevel(inner, targetTable, position, targetColumn);
    }

    int processScalarSubqueryProjection(SQLSelectItem item, SQLQueryExpr expr, int position) {
        return selector.processScalarSubqueryProjection(item, expr, position);
    }

    int processScalarSubqueryProjection(SQLSelectItem item, SQLQueryExpr expr) {
        return selector.processScalarSubqueryProjection(item, expr);
    }

    boolean containsScalarSubquery(SQLExpr expr) {
        return selector.containsScalarSubquery(expr);
    }

    void recordUnboundScalarMappings(SQLSelectItem item, int position) {
        selector.recordUnboundScalarMappings(item, position);
    }

    void recordUnboundScalarExpression(SQLExpr expr, String targetTable, String targetName, int position) {
        selector.recordUnboundScalarExpression(expr, targetTable, targetName, position);
    }

    int recordUnboundSelectItem(SQLSelectItem item, int position) {
        return selector.recordUnboundSelectItem(item, position);
    }

    ScalarSubquery analyzeScalarSubquery(SQLQueryExpr expr) {
        return selector.analyzeScalarSubquery(expr);
    }

    int processSelectItem(SQLSelectItem item) {
        return selector.processSelectItem(item);
    }

    int outputColumnCount(SQLExpr expr) {
        return selector.outputColumnCount(expr);
    }

    int minimumOutputColumns(SQLExpr expr) {
        return selector.minimumOutputColumns(expr);
    }

    int minimumColumns(SourceTable source) {
        return selector.minimumColumns(source);
    }

    boolean starOutputWidthKnown(SQLExpr expr) {
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

    boolean outputWidthKnown(SQLSelectItem item) {
        SQLExpr expr = item.getExpr();
        if (expr instanceof SQLAllColumnExpr || isStarProperty(expr)) {
            // A wildcard's width can be known even when its column names are not.
            return starOutputWidthKnown(expr);
        }
        return true;
    }

    boolean outputNamesKnown(SQLSelectItem item) {
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

    boolean bindingNamesKnown(SourceNode binding) {
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

    boolean sourceColumnsKnown(SourceTable source) {
        return source.metadataTable != null || source.outputWidthKnown;
    }

    boolean hasUnknownStarSource(SQLExpr expr) {
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

    boolean sourceNamesKnown(SourceTable source) {
        return source.metadataTable != null || source.outputNamesKnown;
    }

    boolean sourceOutputNameKnown(SourceTable source, int position) {
        if (source.metadataTable != null) {
            return true;
        }
        return position >= 0 && position < source.outputNameKnown.size()
                && Boolean.TRUE.equals(source.outputNameKnown.get(position));
    }

    int columnCount(SourceTable source) {
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

    final SourcePlanner planner = new SourcePlanner(this);

    SourceNode buildSourceNode(SQLTableSource tableSource, Map<String, List<SourceTable>> using) {
        return planner.buildSourceNode(tableSource, using);
    }

    void processDerivedBody(String target, SQLSelect select, List<com.alibaba.druid.sql.ast.SQLName> columns) {
        planner.processDerivedBody(target, select, columns);
    }

    void processDerivedUnion(String target, SQLUnionQuery union, List<com.alibaba.druid.sql.ast.SQLName> columns) {
        planner.processDerivedUnion(target, union, columns);
    }

    void recordTargetShape(String target) {
        planner.recordTargetShape(target);
    }

    void fillDeclaredOutputs(String target, int width) {
        planner.fillDeclaredOutputs(target, width);
    }

    void pushDerivedAliases(String target, List<com.alibaba.druid.sql.ast.SQLName> columns) {
        planner.pushDerivedAliases(target, columns);
    }

    List<StarOutput> planFromSource(SourceTable source) {
        return planner.planFromSource(source);
    }

    List<String> sourceColumnKeys(SourceTable source) {
        return planner.sourceColumnKeys(source);
    }

    List<StarOutput> mergeStarPlan(List<StarOutput> left, List<StarOutput> right, SQLJoinTableSource.JoinType type, List<SQLExpr> using, boolean leftKnown, boolean rightKnown, List<SourceTable> leftSources, List<SourceTable> rightSources, boolean leftWidthKnown) {
        return planner.mergeStarPlan(left, right, type, using, leftKnown, rightKnown, leftSources, rightSources, leftWidthKnown);
    }

    static boolean hasUniqueUsingColumn(List<StarOutput> plan, String key) {
        return SourcePlanner.hasUniqueUsingColumn(plan, key);
    }

    static boolean sameUnknownGroup(SourceTable source) {
        return SourcePlanner.sameUnknownGroup(source);
    }

    static boolean containsSource(List<SourceTable> sources, SourceTable source) {
        return SourcePlanner.containsSource(sources, source);
    }

    static int totalWildcardOccurrences(SourceTable source) {
        return SourcePlanner.totalWildcardOccurrences(source);
    }

    static List<Integer> unknownRegionRuns(SourceTable source) {
        return SourcePlanner.unknownRegionRuns(source);
    }

    static List<Integer> unknownRegionEnds(SourceTable source) {
        return SourcePlanner.unknownRegionEnds(source);
    }

    static int firstUnknownIndex(SourceTable source) {
        return SourcePlanner.firstUnknownIndex(source);
    }

    static int unknownRegionCount(SourceTable source) {
        return SourcePlanner.unknownRegionCount(source);
    }

    int planSizeFor(SourceTable source) {
        return planner.planSizeFor(source);
    }

    SourceNode sourcePlanNode(SourceTable source) {
        return planner.sourcePlanNode(source);
    }

    static Map<SourceTable, Integer> unknownSectionEndFor(SourceTable source, boolean widthKnown, int planSize) {
        return SourcePlanner.unknownSectionEndFor(source, widthKnown, planSize);
    }

    static boolean singleSourceCertain(List<SourceTable> sources, String key) {
        return SourcePlanner.singleSourceCertain(sources, key);
    }

    static StarOutput unknownColumn(List<SourceTable> sources, SQLExpr expr, String key, boolean certain) {
        return SourcePlanner.unknownColumn(sources, expr, key, certain);
    }

    static StarOutput removeFirstUnknown(List<StarOutput> plan, SQLExpr expr, String key, boolean certain) {
        return SourcePlanner.removeFirstUnknown(plan, expr, key, certain);
    }

    static StarOutput positionalColumn(StarOutput output, SQLExpr expr, String key, boolean certain) {
        return SourcePlanner.positionalColumn(output, expr, key, certain);
    }

    static int planKeyCount(List<StarOutput> plan, String key) {
        return SourcePlanner.planKeyCount(plan, key);
    }

    static void addSources(List<SourceTable> target, List<SourceTable> extra) {
        SourcePlanner.addSources(target, extra);
    }

    void addUnknownSources(List<SourceTable> target, List<SourceTable> candidates) {
        planner.addUnknownSources(target, candidates);
    }

    static List<SourceTable> planAllSourcesForKey(List<StarOutput> plan, String key) {
        return SourcePlanner.planAllSourcesForKey(plan, key);
    }

    static List<SourceTable> planSourcesForKey(List<StarOutput> plan, String key) {
        return SourcePlanner.planSourcesForKey(plan, key);
    }

    static int insertionPoint(List<StarOutput> plan, List<SourceTable> sources, int fallback, Map<SourceTable, Integer> sectionEnd) {
        return SourcePlanner.insertionPoint(plan, sources, fallback, sectionEnd);
    }

    static int lastIndexOfUncertainSource(List<StarOutput> plan, List<SourceTable> sources) {
        return SourcePlanner.lastIndexOfUncertainSource(plan, sources);
    }

    static int countRemaining(List<StarOutput> plan, List<StarOutput> originals) {
        return SourcePlanner.countRemaining(plan, originals);
    }

    static boolean containsIdentity(List<StarOutput> plan, StarOutput output) {
        return SourcePlanner.containsIdentity(plan, output);
    }

    static int keptBefore(List<StarOutput> plan, List<StarOutput> originals, int boundary) {
        return SourcePlanner.keptBefore(plan, originals, boundary);
    }

    static int lastIndexOfSource(List<StarOutput> plan, List<SourceTable> sources) {
        return SourcePlanner.lastIndexOfSource(plan, sources);
    }

    static int knownOutputPositionCount(SourceNode node) {
        return SourcePlanner.knownOutputPositionCount(node);
    }

    static Set<String> knownOutputKeys(SourceNode node) {
        return SourcePlanner.knownOutputKeys(node);
    }

    boolean nodeNamesKnown(SourceNode node) {
        return planner.nodeNamesKnown(node);
    }

    static int planKnownKeyCount(List<StarOutput> plan, String key) {
        return SourcePlanner.planKnownKeyCount(plan, key);
    }

    StarOutput removeFirstMatching(List<StarOutput> plan, String key) {
        return planner.removeFirstMatching(plan, key);
    }

    static List<SourceTable> mergedSources(SQLJoinTableSource.JoinType type, List<SourceTable> left, List<SourceTable> right) {
        return SourcePlanner.mergedSources(type, left, right);
    }

    SourceTable resolveSource(SQLExprTableSource table) {
        return planner.resolveSource(table);
    }

    int targetMinimumColumns(String target, boolean widthKnown, int outputCount) {
        return targetShape.targetMinimumColumns(target, widthKnown, outputCount);
    }

    void recordWildcardSpan(String target, int start, int end, String source,
            boolean temporary) {
        targetShape.recordWildcardSpan(target, start, end, source, temporary);
    }

    List<WildcardSpan> wildcardSpansOf(String target) {
        return targetShape.wildcardSpansOf(target);
    }

    int knownWidthSum(List<SourceTable> sources) {
        return targetShape.knownWidthSum(sources);
    }

    boolean hasAmbiguousOutput(List<StarOutput> plan, int limit) {
        return targetShape.hasAmbiguousOutput(plan, limit);
    }

    int maxSpanEnd(List<WildcardSpan> spans) {
        return targetShape.maxSpanEnd(spans);
    }

    List<WildcardRef> wildcardSourcesAt(SourceTable source, StarColumn column,
            int fallbackRelative) {
        return targetShape.wildcardSourcesAt(source, column, fallbackRelative);
    }

    boolean hasWildcardContribution(String target) {
        return targetShape.hasWildcardContribution(target);
    }

    List<SourceTable> copyUnknownGroups(String target, int size) {
        return targetShape.copyUnknownGroups(target, size);
    }

    List<Boolean> copyNameKnown(String target, int size) {
        return targetShape.copyNameKnown(target, size);
    }

    boolean isOutputWidthKnown(String target, List<String> outputs) {
        return targetShape.isOutputWidthKnown(target, outputs);
    }

    boolean isOutputNamesKnown(String target, List<String> outputs) {
        return targetShape.isOutputNamesKnown(target, outputs);
    }

    List<String> copyOutputs(List<String> outputs) {
        return targetShape.copyOutputs(outputs);
    }

    List<String> copyKeys(String target) {
        return targetShape.copyKeys(target);
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

    String canonicalIdentifier(String rawName) {
        return DialectRules.of(currentDialect())
                .canonical(Identifier.of(rawName));
    }

    String identifierKey(String rawName) {
        return DialectRules.of(currentDialect())
                .key(Identifier.of(rawName));
    }

    Map<String, List<String>> namedTargetOutputs() {
        return targetShape.namedTargetOutputs();
    }

    String lockedOutputName(String target, int position) {
        return targetShape.lockedOutputName(target, position);
    }

    String lockedOutputKey(String target, int position) {
        return targetShape.lockedOutputKey(target, position);
    }

    void incrementNonAuthoritative(String target) {
        targetShape.incrementNonAuthoritative(target);
    }

    void decrementNonAuthoritative(String target) {
        targetShape.decrementNonAuthoritative(target);
    }

    boolean isExistingOutput(List<String> existing, String name, int position) {
        return targetShape.isExistingOutput(existing, name, position);
    }

    void markOutputNameKnown(String target, int position, boolean known) {
        targetShape.markOutputNameKnown(target, position, known);
    }

    void setTargetOutput(String target, int position, String name, String key) {
        targetShape.setTargetOutput(target, position, name, key);
    }

    void recordStarOutputs(String target, List<SourceTable> sources, int startPosition) {
        targetShape.recordStarOutputs(target, sources, startPosition);
    }

    void recordSourceStarOutputs(String target, SourceTable source, int startPosition) {
        targetShape.recordSourceStarOutputs(target, source, startPosition);
    }

    List<String> currentDeclaredKeys(String target) {
        return targetShape.currentDeclaredKeys(target);
    }

    List<String> sourceColumnNames(SourceTable source) {
        return targetShape.sourceColumnNames(source);
    }

    List<StarOutput> currentStarPlan(List<SourceTable> sources) {
        return targetShape.currentStarPlan(sources);
    }

    List<String> currentTargetColumnAliases(String target) {
        return targetShape.currentTargetColumnAliases(target);
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

    void validateUnboundQualified(SQLExpr expr) {
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

    void learnBlockExplicitReferences(SQLSelectQueryBlock block,
            Map<String, String> inherited) {
        learning.learnBlockExplicitReferences(block, inherited);
    }

    void learnNoFromExplicitReferences(SQLSelectQueryBlock block,
            Map<String, String> inherited) {
        learning.learnNoFromExplicitReferences(block, inherited);
    }

    Map<String, String> outerBlockLocals(boolean includeCurrentFrame) {
        return learning.outerBlockLocals(includeCurrentFrame);
    }

    final ExpressionAnalyzer expression = new ExpressionAnalyzer(this);

    void visitExpr(SQLExpr expr) {
        expression.visitExpr(expr);
    }

    void visitScalarSubquery(SQLQueryExpr expr) {
        expression.visitScalarSubquery(expr);
    }

    void visitIdentifier(SQLIdentifierExpr identifier) {
        expression.visitIdentifier(identifier);
    }

    List<SourceTable> participantsForFrame(int frame, String key) {
        return expression.participantsForFrame(frame, key);
    }

    List<SourceTable> frameCandidates(int frame, String key, List<SourceTable> participants, List<SourceTable> excluded) {
        return expression.frameCandidates(frame, key, participants, excluded);
    }

    boolean lateralScopeActive() {
        return expression.lateralScopeActive();
    }

    boolean hasOuterScopeFallback() {
        return expression.hasOuterScopeFallback();
    }

    boolean hasLateralAncestorScope() {
        return expression.hasLateralAncestorScope();
    }

    List<SourceTable> resolveCorrelatedUnqualified(String rawName) {
        return expression.resolveCorrelatedUnqualified(rawName);
    }

    private static boolean hasQuotedSegment(String reference) {
        return ExpressionAnalyzer.hasQuotedSegment(reference);
    }

    private static boolean exactNormalizedSuffix(String[] ownerParts, String[] referenceParts) {
        return ExpressionAnalyzer.exactNormalizedSuffix(ownerParts, referenceParts);
    }

    private static boolean hasSignificantQuote(String reference) {
        return ExpressionAnalyzer.hasSignificantQuote(reference);
    }

    List<SourceTable> visibleOwnerMatches(SQLExpr owner) {
        return expression.visibleOwnerMatches(owner);
    }

    SourceTable findVisibleOwnerStrict(SQLExpr owner) {
        return expression.findVisibleOwnerStrict(owner);
    }

    SourceNode exactJoinBinding(SQLExpr owner) {
        return expression.exactJoinBinding(owner);
    }

    CorrelatedOwner findCorrelatedOwner(SQLExpr owner) {
        return expression.findCorrelatedOwner(owner);
    }

    boolean matchesOwner(SourceTable source, String ownerKey, String[] ownerParts) {
        return expression.matchesOwner(source, ownerKey, ownerParts);
    }

    private static boolean isStarProperty(SQLExpr expr) {
        return ExpressionAnalyzer.isStarProperty(expr);
    }

    void visitProperty(SQLPropertyExpr property) {
        expression.visitProperty(property);
    }

    void bindJoinProperty(SourceNode binding, String rawName, String name) {
        expression.bindJoinProperty(binding, rawName, name);
    }

    static List<StarColumn> knownColumns(StarOutput output) {
        List<StarColumn> known = new ArrayList<>();
        for (StarColumn column : output.sources) {
            if (column.nameKnown) {
                known.add(column);
            }
        }
        return known;
    }

    static List<SourceTable> planSources(List<StarOutput> outputs) {
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

    String lineageTableName(SourceTable source) {
        return starProjection.lineageTableName(source);
    }

    void emitBindingColumns(List<StarColumn> columns) {
        starProjection.emitBindingColumns(columns);
    }

    void recordBindingStar(String target, SourceNode binding, int startPosition) {
        starProjection.recordBindingStar(target, binding, startPosition);
    }

    void mapBindingStar(String targetTable, SourceNode binding, int startPosition) {
        starProjection.mapBindingStar(targetTable, binding, startPosition);
    }

    int emitDetachedStar(String targetTable, List<SourceTable> sources,
            int startPosition) {
        return starProjection.emitDetachedStar(targetTable, sources, startPosition);
    }

    int emitDetachedPlan(String targetTable, List<StarOutput> template,
            List<SourceTable> sources, SourceNode node, int startPosition) {
        return starProjection.emitDetachedPlan(targetTable, template, sources, node,
                startPosition);
    }

    void mapSourceStar(SourceTable source, String targetTable, int startPosition) {
        starProjection.mapSourceStar(source, targetTable, startPosition);
    }

    void visitAllColumn() {
        starProjection.visitAllColumn();
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
    String uncatalogedColumnSpelling(String raw) {
        return columnMapping.uncatalogedColumnSpelling(raw);
    }

    String canonicalColumnName(String raw) {
        return columnMapping.canonicalColumnName(raw);
    }

    String canonicalColumnName(String table, String rawColumn) {
        return columnMapping.canonicalColumnName(table, rawColumn);
    }

    String renderInsertColumn(Table metadata, String tableName, SQLExpr column) {
        return columnMapping.renderInsertColumn(metadata, tableName, column);
    }

    void addColumnMapping(SourceTable source, String columnName) {
        columnMapping.addColumnMapping(source, columnName);
    }

    void addColumnMapping(String sourceTable, String columnName,
            boolean sourceTemporary, Table sourceMetadata) {
        columnMapping.addColumnMapping(sourceTable, columnName, sourceTemporary,
                sourceMetadata);
    }






    List<SourceTable> resolveUnqualified(String rawColumn) {
        return expression.resolveUnqualified(rawColumn);
    }

    boolean useVisiblePlan(List<SourceTable> sources,
            Map<String, List<SourceTable>> using) {
        return sourceShape.useVisiblePlan(sources, using);
    }

    boolean allWidthKnown(List<SourceTable> sources) {
        return sourceShape.allWidthKnown(sources);
    }

    boolean sourceWidthKnown(SourceTable source) {
        return sourceShape.sourceWidthKnown(source);
    }

    int derivedWidth(String target, List<String> outputs, boolean widthKnown) {
        return sourceShape.derivedWidth(target, outputs, widthKnown);
    }

    int sourceKnownWidth(SourceTable source) {
        return sourceShape.sourceKnownWidth(source);
    }

    List<SourceTable> refineWithLearned(List<SourceTable> candidates,
            String rawColumn) {
        return sourceShape.refineWithLearned(candidates, rawColumn);
    }

    List<SourceTable> unknownColumnSources(List<SourceTable> sources,
            List<SourceTable> excluded) {
        return sourceShape.unknownColumnSources(sources, excluded);
    }

    static List<SourceTable> allSources(List<SourceTable> first,
            List<SourceTable> second) {
        return SourceShapeAnalyzer.allSources(first, second);
    }

    boolean allSourceNamesKnown(List<SourceTable> sources) {
        return sourceShape.allSourceNamesKnown(sources);
    }

    static List<SourceTable> starOutputSources(StarOutput output) {
        return SourceShapeAnalyzer.starOutputSources(output);
    }

    static List<String> displayNames(List<SourceTable> sources) {
        return SourceShapeAnalyzer.displayNames(sources);
    }

    SourceNode joinBinding(SQLExpr owner) {
        return expression.joinBinding(owner);
    }

    SourceTable findByOwner(SQLExpr owner) {
        return expression.findByOwner(owner);
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




    DbType currentDialect() {
        return context != null ? context.getCatalog().getDialect() : dbType;
    }



    /** Write an edge for a name that is already the catalog/derived spelling. */

    static String quoteIdentifierValue(String name) {
        return Identifiers.quoteDouble(name);
    }

    static String renderIdentifier(String name) {
        return Identifiers.render(name);
    }

    List<SourceTable> currentSources() {
        return state.stackSources.isEmpty() ? new ArrayList<SourceTable>() : state.stackSources.peek();
    }

    List<SourceTable> visibleSources() {
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

    static String hivePlannedColumn(String column) {
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
        tableCollector.collectTablesFromExpression(expr);
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

    String uniqueTarget(String preferred) {
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

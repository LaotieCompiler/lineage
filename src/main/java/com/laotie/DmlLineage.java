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
import com.alibaba.druid.sql.SQLUtils;
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
    private Stack<String> stackTargetTable;
    private Stack<String> stackSourceTable;
    private Stack<String> stackTargetColumn;
    private Stack<List<SourceTable>> stackSources;
    private Stack<Map<String, List<SourceTable>>> stackUsing;
    private Stack<Map<String, SourceNode>> stackJoinBindings;
    private Map<Map<String, List<SourceTable>>, Map<String, List<SourceTable>>>
            usingParticipantsByIdentity;
    private Stack<Set<SourceTable>> stackHiddenSources;

    private boolean inTargetExpression = false;
    private int tempTableNum = 0;
    private int currentOrdinal = -1;
    private int uncertainOrdinal = -1;
    private int currentStarBudget = -1;
    /**
     * When true the traversal still learns metadata but emits no lineage
     * instructions, used for the metadata-discovery pass of a two-pass scan.
     */
    private boolean learningOnly;
    /**
     * Metadata-discovery mode: only explicit references are learned, and a statement
     * that fails elsewhere still publishes what it named explicitly.
     */
    private boolean metadataPass;
    /**
     * Lineage mode: read the learned metadata but do not change it, so every
     * statement sees the same picture.
     */
    private boolean learningDisabled;
    /** JOIN alias bindings visible while discovering a query block. */
    private Map<String, Object> learningBindings = new HashMap<>();
    /** The exact plan built for each JOIN table source, including its alias. */
    private Map<SQLTableSource, SourceNode> tableSourceBindings = new IdentityHashMap<>();
    /** Depth of scalar-subquery analysis; outer scopes stay visible inside it. */
    private int scalarSubqueryDepth;
    private Map<SourceTable, SourceNode> currentExpandedSourceNodes = new HashMap<>();

    private Set<String> tempTables;
    private Set<String> usedTargets;
    private Stack<Map<String, String>> cteScopes;
    private Stack<Set<String>> collectCteScopes;
    private Stack<Boolean> lateralScopes;
    private Stack<Map<String, List<String>>> targetColumnAliases;
    private Map<String, List<String>> cteDeclaredColumns;
    private Map<String, List<String>> cteDeclaredKeys;
    private Map<String, List<String>> targetOutputs;
    private Map<String, List<String>> targetOutputKeys;
    private Set<String> intermediateTargets;
    private Set<String> unionTargets;
    private Map<String, Boolean> targetWidthKnown;
    private Map<String, Boolean> targetNamesKnown;
    private Map<String, List<SourceTable>> targetOutputUnknownGroup;
    private Map<String, Boolean> targetWildcardContribution;
    private Map<String, List<WildcardSpan>> targetWildcardSpans;
    private Map<String, List<Boolean>> targetOutputNameKnown;
    private Map<String, Integer> targetMinWidth;
    private Map<String, Integer> targetWidthOverride;
    private Map<String, Integer> nonAuthoritativeDepth;
    private int lastQueryBlockWidth;
    private boolean lastQueryBlockWidthKnown;
    private boolean lastQueryBlockNamesKnown;
    private int lastQueryBlockMinWidth;
    private Stack<List<StarOutput>> stackStarPlan;
    private Stack<SourceNode> stackSourceNode;

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
    private boolean detachedMetadata() {
        return options.getMetadataMode() == MetadataMode.OPTIONAL;
    }

    /**
     * Attach the session-scoped metadata learned from earlier statements.
     */
    /**
     * Analyze only to discover metadata: instructions are discarded.
     */
    public void setLearningOnly(boolean learningOnly) {
        this.learningOnly = learningOnly;
    }

    public void setMetadataPass(boolean metadataPass) {
        this.metadataPass = metadataPass;
    }

    public void setLearningDisabled(boolean learningDisabled) {
        this.learningDisabled = learningDisabled;
    }

    public void setLearnedMetadata(LearnedMetadata learnedMetadata) {
        this.learnedMetadata = learnedMetadata;
    }

    /**
     * Remember that a column was referenced on a table, so that later statements can
     * resolve an unqualified column against it.
     */
    private void learnColumn(SourceTable source, String columnName) {
        learnColumn(source, columnName, false);
    }

    private void learnColumn(SourceTable source, String columnName, boolean explicit) {
        if (learningDisabled || learnedMetadata == null || source == null
                || source.temporary || !source.learnable
                || source.rawName == null || columnName == null
                || metadataPass && !explicit) {
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

    private String learnedTableKey(String rawName) {
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
        if (learningDisabled) {
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
    private static final class EscapeUnit {
        private final String method;
        private final String unit;

        private EscapeUnit(String method, String unit) {
            this.method = method;
            this.unit = unit;
        }
    }

    private void annotateJdbcEscapes(SQLStatement statement, String source,
            DbType dialect) {
        String marked = markJdbcEscapes(source);
        if (marked.equals(source)) {
            return;
        }
        List<String> markedMethods = new ArrayList<>();
        try {
            SQLStatement markedStatement =
                    parseWithDialectFallback(marked, dialect);
            markedStatement.accept(new SQLASTVisitorAdapter() {
                @Override
                public boolean visit(SQLMethodInvokeExpr x) {
                    markedMethods.add(x.getMethodName());
                    return true;
                }
            });
        } catch (RuntimeException e) {
            return;
        }
        int[] index = {0};
        statement.accept(new SQLASTVisitorAdapter() {
            @Override
            public boolean visit(SQLMethodInvokeExpr x) {
                int i = index[0]++;
                if (i >= markedMethods.size()) {
                    return true;
                }
                String markedMethod = markedMethods.get(i);
                if (markedMethod != null
                        && markedMethod.startsWith(JDBC_MARKER)
                        && !markedMethod.equals(x.getMethodName())
                        && !x.getArguments().isEmpty()
                        && x.getArguments().get(0)
                                instanceof SQLIdentifierExpr) {
                    x.putAttribute(JDBC_ESCAPE_ATTRIBUTE,
                            new JdbcEscape(jdbcSignature(x)));
                }
                return true;
            }
        });
    }

    private static String markJdbcEscapes(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0;
        while (i < sql.length()) {
            char ch = sql.charAt(i);
            if (ch == '\'' || ch == '"' || ch == '`') {
                char quote = ch;
                out.append(ch);
                i++;
                while (i < sql.length()) {
                    char c = sql.charAt(i);
                    out.append(c);
                    if (c == quote) {
                        if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
                            out.append(sql.charAt(++i));
                        } else {
                            break;
                        }
                    }
                    i++;
                }
                i++;
                continue;
            }
            if (ch == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                int end = lineCommentEnd(sql, i + 2);
                out.append(sql, i, end);
                i = end;
                continue;
            }
            if (ch == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                if (end < 0) {
                    out.append(sql, i, sql.length());
                    break;
                }
                out.append(sql, i, end + 2);
                i = end + 2;
                continue;
            }
            if (ch == '{') {
                int[] span = jdbcEscapeMethod(sql, i);
                if (span != null
                        && isJdbcTimestampMethod(sql.substring(span[0], span[1]))) {
                    out.append(sql, i, span[0]);
                    out.append(JDBC_MARKER);
                    out.append(sql, span[0], span[1]);
                    i = span[1];
                    continue;
                }
            }
            out.append(ch);
            i++;
        }
        return out.toString();
    }

    private static int lineCommentEnd(String sql, int start) {
        for (int i = start; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            if (ch == '\n' || ch == '\r') {
                return i + 1;
            }
        }
        return sql.length();
    }

    private static int skipSpaceAndComments(String sql, int start) {
        int i = start;
        while (i < sql.length()) {
            char ch = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : 0;
            if (Character.isWhitespace(ch)) {
                i++;
            } else if (ch == '-' && next == '-') {
                i = lineCommentEnd(sql, i + 2);
            } else if (ch == '/' && next == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? sql.length() : end + 2;
            } else {
                break;
            }
        }
        return i;
    }

    private static boolean isJdbcTimestampMethod(String method) {
        return "TIMESTAMPADD".equalsIgnoreCase(method)
                || "TIMESTAMPDIFF".equalsIgnoreCase(method);
    }

    private static int[] jdbcEscapeMethod(String sql, int brace) {
        int i = skipSpaceAndComments(sql, brace + 1);
        if (i + 2 > sql.length()
                || !sql.regionMatches(true, i, "fn", 0, 2)) {
            return null;
        }
        i += 2;
        if (i < sql.length() && !Character.isWhitespace(sql.charAt(i))
                && sql.charAt(i) != '/' && sql.charAt(i) != '-') {
            return null;
        }
        i = skipSpaceAndComments(sql, i);
        int start = i;
        if (i >= sql.length()
                || !(Character.isLetter(sql.charAt(i)) || sql.charAt(i) == '_'
                        || sql.charAt(i) == '$')) {
            return null;
        }
        i++;
        while (i < sql.length() && isIdentifierChar(sql.charAt(i))) {
            i++;
        }
        return new int[] {start, i};
    }

    /**
     * Strict quoted syntax: every table and column reference (and table alias /
     * CTE name, which act as qualifiers) must carry identifier quotes.
     */
    private void validateQuotedIdentifiers(SQLStatement statement) {
        DbType dialect = currentDialect();
        SQLASTVisitor visitor;
        switch (dialect) {
            case oracle:
                visitor = new OracleQuotedIdentifierVisitor(dialect);
                break;
            case sqlserver:
                visitor = new SqlServerQuotedIdentifierVisitor(dialect);
                break;
            default:
                visitor = new QuotedIdentifierVisitor(dialect);
                break;
        }
        statement.accept(visitor);
        // Clauses that dialect AST nodes do not expose as visited children.
        if (statement instanceof SQLUpdateStatement) {
            for (SQLExpr expr : ((SQLUpdateStatement) statement).getReturning()) {
                if (expr != null) {
                    expr.accept(visitor);
                }
            }
        }
        if (statement instanceof PGInsertStatement) {
            PGInsertStatement insert = (PGInsertStatement) statement;
            SQLExpr returning = insert.getReturning();
            if (returning != null) {
                returning.accept(visitor);
            }
            if (insert.getOnConflictConstraint() != null) {
                requireQuotedIdentifier(
                        insert.getOnConflictConstraint().toString());
            }
            if (insert.getOnConflictTarget() != null) {
                for (SQLExpr target : insert.getOnConflictTarget()) {
                    if (target != null) {
                        target.accept(visitor);
                    }
                }
            }
            if (insert.getOnConflictUpdateSetItems() != null) {
                for (SQLUpdateSetItem item
                        : insert.getOnConflictUpdateSetItems()) {
                    if (item != null) {
                        item.accept(visitor);
                    }
                }
            }
            if (insert.getOnConflictWhere() != null) {
                insert.getOnConflictWhere().accept(visitor);
            }
            if (insert.getOnConflictUpdateWhere() != null) {
                insert.getOnConflictUpdateWhere().accept(visitor);
            }
        }
        if (statement instanceof MySqlInsertStatement) {
            for (SQLExpr expr : ((MySqlInsertStatement) statement)
                    .getDuplicateKeyUpdate()) {
                if (expr != null) {
                    expr.accept(visitor);
                }
            }
        }
    }

    private static final class SqlServerQuotedIdentifierVisitor
            extends SQLServerASTVisitorAdapter {
        private final DbType dialect;

        private SqlServerQuotedIdentifierVisitor(DbType dialect) {
            this.dialect = dialect;
        }

        @Override
        public boolean visit(SQLIdentifierExpr x) {
            checkIdentifier(x, dialect);
            return true;
        }

        @Override
        public boolean visit(SQLPropertyExpr x) {
            checkProperty(x);
            return true;
        }

        @Override
        public boolean visit(SQLExprTableSource x) {
            checkTableSource(x);
            return true;
        }

        @Override
        public boolean visit(SQLJoinTableSource x) {
            checkTableSource(x);
            return true;
        }

        @Override
        public boolean visit(SQLSubqueryTableSource x) {
            checkTableSource(x);
            checkColumnNames(x.getColumns());
            return true;
        }

        @Override
        public boolean visit(SQLUnionQueryTableSource x) {
            checkTableSource(x);
            checkColumnNames(x.getColumns());
            return true;
        }

        @Override
        public boolean visit(SQLWithSubqueryClause.Entry x) {
            requireQuotedIdentifier(x.getAlias());
            checkColumnNames(x.getColumns());
            return true;
        }

        @Override
        public boolean visit(SQLColumnDefinition x) {
            requireQuotedIdentifier(x.getColumnName());
            return true;
        }
    }

    private static final class QuotedIdentifierVisitor extends SQLASTVisitorAdapter {
        private final DbType dialect;

        private QuotedIdentifierVisitor(DbType dialect) {
            this.dialect = dialect;
        }

        @Override
        public boolean visit(SQLIdentifierExpr x) {
            checkIdentifier(x, dialect);
            return true;
        }

        @Override
        public boolean visit(SQLPropertyExpr x) {
            checkProperty(x);
            return true;
        }

        @Override
        public boolean visit(SQLExprTableSource x) {
            checkTableSource(x);
            return true;
        }

        @Override
        public boolean visit(SQLJoinTableSource x) {
            checkTableSource(x);
            return true;
        }

        @Override
        public boolean visit(SQLSubqueryTableSource x) {
            checkTableSource(x);
            checkColumnNames(x.getColumns());
            return true;
        }

        @Override
        public boolean visit(SQLUnionQueryTableSource x) {
            checkTableSource(x);
            checkColumnNames(x.getColumns());
            return true;
        }

        @Override
        public boolean visit(SQLWithSubqueryClause.Entry x) {
            requireQuotedIdentifier(x.getAlias());
            checkColumnNames(x.getColumns());
            return true;
        }

        @Override
        public boolean visit(SQLColumnDefinition x) {
            requireQuotedIdentifier(x.getColumnName());
            return true;
        }
    }

    private static final class OracleQuotedIdentifierVisitor
            extends OracleASTVisitorAdapter {
        private final DbType dialect;

        private OracleQuotedIdentifierVisitor(DbType dialect) {
            this.dialect = dialect;
        }

        @Override
        public boolean visit(SQLIdentifierExpr x) {
            checkIdentifier(x, dialect);
            return true;
        }

        @Override
        public boolean visit(SQLPropertyExpr x) {
            checkProperty(x);
            return true;
        }

        @Override
        public boolean visit(SQLExprTableSource x) {
            checkTableSource(x);
            return true;
        }

        @Override
        public boolean visit(SQLJoinTableSource x) {
            checkTableSource(x);
            return true;
        }

        @Override
        public boolean visit(SQLSubqueryTableSource x) {
            checkTableSource(x);
            checkColumnNames(x.getColumns());
            return true;
        }

        @Override
        public boolean visit(SQLUnionQueryTableSource x) {
            checkTableSource(x);
            checkColumnNames(x.getColumns());
            return true;
        }

        @Override
        public boolean visit(OracleSelectTableReference x) {
            checkTableSource(x);
            return true;
        }

        @Override
        public boolean visit(OracleSelectJoin x) {
            checkTableSource(x);
            return true;
        }

        @Override
        public boolean visit(OracleSelectSubqueryTableSource x) {
            checkTableSource(x);
            checkColumnNames(x.getColumns());
            return true;
        }

        @Override
        public boolean visit(SQLWithSubqueryClause.Entry x) {
            requireQuotedIdentifier(x.getAlias());
            checkColumnNames(x.getColumns());
            return true;
        }

        @Override
        public boolean visit(SQLColumnDefinition x) {
            requireQuotedIdentifier(x.getColumnName());
            return true;
        }
    }

    private static void checkIdentifier(SQLIdentifierExpr identifier,
            DbType dialect) {
        if (isFunctionUnit(identifier, dialect)) {
            return;
        }
        requireQuotedIdentifier(identifier.getName());
    }

    private static final Set<String> UNIT_KEYWORDS = new HashSet<>();

    private static final Set<String> SQL_TSI_UNITS = new HashSet<>();

    static {
        java.util.Collections.addAll(UNIT_KEYWORDS,
                "YEAR", "QUARTER", "MONTH", "WEEK", "DAY", "HOUR", "MINUTE",
                "SECOND", "MILLISECOND", "MICROSECOND", "NANOSECOND",
                "DATE", "TIME", "DATETIME", "TIMESTAMP",
                "YEAR_MONTH", "DAY_HOUR", "DAY_MINUTE", "DAY_SECOND",
                "DAY_MICROSECOND", "HOUR_MINUTE", "HOUR_SECOND",
                "HOUR_MICROSECOND", "MINUTE_SECOND", "MINUTE_MICROSECOND",
                "SECOND_MICROSECOND",
                // SQL Server date-part abbreviations.
                "YY", "YYYY", "QQ", "Q", "MM", "M", "DY", "Y", "DD", "D",
                "WK", "WW", "DW", "HH", "MI", "N", "SS", "S", "MS", "MCS",
                "NS", "ISOWW", "ISO_WEEK", "WEEKDAY", "DAYOFYEAR",
                // PostgreSQL date-part fields.
                "EPOCH", "DOW", "ISODOW", "DOY", "ISOYEAR", "CENTURY",
                "DECADE", "MILLENNIUM", "MICROSECONDS", "MILLISECONDS",
                "TIMEZONE", "TIMEZONE_HOUR", "TIMEZONE_MINUTE", "JULIAN");
        java.util.Collections.addAll(SQL_TSI_UNITS,
                "SQL_TSI_FRAC_SECOND", "SQL_TSI_SECOND", "SQL_TSI_MINUTE",
                "SQL_TSI_HOUR", "SQL_TSI_DAY", "SQL_TSI_WEEK",
                "SQL_TSI_MONTH", "SQL_TSI_QUARTER", "SQL_TSI_YEAR",
                "SQL_TSI_MILLI_SECOND", "SQL_TSI_MICRO_SECOND",
                "SQL_TSI_NANO_SECOND");
    }

    private static boolean isFunctionUnit(SQLIdentifierExpr identifier,
            DbType dialect) {
        SQLObject parent = identifier.getParent();
        if (!(parent instanceof SQLMethodInvokeExpr)) {
            return false;
        }
        SQLMethodInvokeExpr call = (SQLMethodInvokeExpr) parent;
        if (call.getArguments().isEmpty()
                || call.getArguments().get(0) != identifier
                || call.getOwner() != null) {
            return false;
        }
        String method = call.getMethodName();
        Object escape = call.getAttribute(JDBC_ESCAPE_ATTRIBUTE);
        boolean jdbcMarked = escape instanceof JdbcEscape
                && ((JdbcEscape) escape).signature.equals(
                        jdbcSignature(call));
        boolean mysqlFamily = dialect == DbType.mysql
                || dialect == DbType.mariadb;
        boolean hiveFamily = dialect == DbType.hive
                || dialect == DbType.antspark;
        boolean sqlServerFamily = dialect == DbType.sqlserver
                || dialect == DbType.sybase || dialect == DbType.h2;
        boolean allowed;
        if ("EXTRACT".equalsIgnoreCase(method)) {
            allowed = true;
        } else if ("TIMESTAMPDIFF".equalsIgnoreCase(method)
                || "TIMESTAMPADD".equalsIgnoreCase(method)) {
            allowed = mysqlFamily || hiveFamily || sqlServerFamily
                    || dialect == DbType.derby || dialect == DbType.hsql;
        } else if ("GET_FORMAT".equalsIgnoreCase(method)) {
            allowed = mysqlFamily;
        } else if (dialect == DbType.h2) {
            allowed = "DATEADD".equalsIgnoreCase(method)
                    || "DATEDIFF".equalsIgnoreCase(method)
                    || "DATE_TRUNC".equalsIgnoreCase(method);
        } else if (dialect == DbType.sybase) {
            allowed = "DATEADD".equalsIgnoreCase(method)
                    || "DATEDIFF".equalsIgnoreCase(method)
                    || "DATEPART".equalsIgnoreCase(method)
                    || "DATENAME".equalsIgnoreCase(method);
        } else if (dialect == DbType.sqlserver) {
            allowed = "DATEADD".equalsIgnoreCase(method)
                    || "DATEDIFF".equalsIgnoreCase(method)
                    || "DATEDIFF_BIG".equalsIgnoreCase(method)
                    || "DATEPART".equalsIgnoreCase(method)
                    || "DATENAME".equalsIgnoreCase(method)
                    || "DATETRUNC".equalsIgnoreCase(method)
                    || "DATE_BUCKET".equalsIgnoreCase(method);
        } else {
            allowed = false;
        }
        if (!allowed) {
            return false;
        }
        String unit = normalizeIdentifier(identifier.getName());
        if (unit == null) {
            return false;
        }
        String upper = unit.toUpperCase(Locale.ROOT);
        boolean timestampCall = "TIMESTAMPADD".equalsIgnoreCase(method)
                || "TIMESTAMPDIFF".equalsIgnoreCase(method);
        if (dialect == DbType.derby && timestampCall) {
            // Derby only treats the JDBC escape form's unit as a keyword.
            return jdbcMarked && SQL_TSI_UNITS.contains(upper);
        }
        return SQL_TSI_UNITS.contains(upper)
                || UNIT_KEYWORDS.contains(upper);
    }

    private static void checkProperty(SQLPropertyExpr property) {
        if (!isStarProperty(property)) {
            requireQuotedIdentifier(property.getName());
        }
    }

    private static void checkTableSource(SQLTableSource source) {
        requireQuotedIdentifier(source.getAlias());
    }

    private static void checkColumnNames(java.util.List<SQLName> columns) {
        if (columns == null) {
            return;
        }
        for (SQLName column : columns) {
            requireQuotedIdentifier(column == null ? null : column.toString());
        }
    }

    private static void requireQuotedIdentifier(String name) {
        if (name == null || "*".equals(name)
                || MetadataCatalog.isQuotedIdentifier(name)) {
            return;
        }
        throw new UnquotedIdentifierException(name);
    }

    private SQLStatement parseWithDialectFallback(String sql, DbType dialect) {
        try {
            return SQLUtils.parseSingleStatement(sql, dialect);
        } catch (RuntimeException e) {
            if (dialect == DbType.antspark) {
                try {
                    return SQLUtils.parseSingleStatement(sql, DbType.hive);
                } catch (RuntimeException ignored) {
                    // Report the original dialect's error below.
                }
            }
            throw e;
        }
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
        this.tempTableNum = 0;
        this.sessionDatabase = session.database;
        this.sessionRawDatabase = session.rawDatabase;
        this.sessionRawSchemas = new ArrayList<>(session.rawSchemas);
        List<String> rawSchemas = session.rawSchemas.isEmpty()
                ? session.schemas : session.rawSchemas;
        this.sessionSchema = rawSchemas.isEmpty() ? null
                : joinIdentifierKeys(rawSchemas);
        this.pendingLearned = new HashMap<>();
        this.statementSql = sql;
        this.ansiEnabled = session.ansiEnabled;
        this.ansiDoubleQuotedIdentifiers = session.ansiDoubleQuotedIdentifiers;
        try {
            if (options.isStrictQuotedIdentifiers()) {
                validateQuotedIdentifiers(statement);
            }
            init(true);
            stackTargetTable.push(getTempTableName());
            processStatement(statement);
            stackTargetTable.pop();
            LineageResult result = new LineageResult(sql, instructions, working, null,
                    session.instance, session.database, session.schemas, tempTables,
                    namedTargetOutputs(), detachedMetadata());
            publishLearned();
            return result;
        } catch (Throwable t) {
            if (metadataPass) {
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
            session.rawDatabase = use.getDatabase().toString().trim();
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
                session.ansiDoubleQuotedIdentifiers = enabled;
            } else if (target.equals("spark.sql.ansi.enabled")) {
                session.ansiEnabled = enabled;
            } else if (target.endsWith("search_path")) {
                session.schemas = stringList(value);
                session.rawSchemas = rawStringList(value);
            } else if (target.contains("schema")) {
                session.schemas = new ArrayList<>();
                session.schemas.add(normalizeIdentifier(value.toString()));
                session.rawSchemas = new ArrayList<>();
                session.rawSchemas.add(value.toString().trim());
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
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return value.toString().trim();
    }

    static List<String> splitStatements(String sql, DbType dialect) {
        List<String> statements = new ArrayList<>();
        if (sql == null) {
            return statements;
        }
        boolean mysqlFamily = dialect == DbType.mysql || dialect == DbType.mariadb;
        boolean hiveFamily = dialect == DbType.hive || dialect == DbType.antspark;
        boolean hashComments = mysqlFamily;
        boolean postgres = dialect == DbType.postgresql;
        boolean nestedComments = postgres;
        boolean bracketQuotes = dialect == DbType.sqlserver;

        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean escapeString = false;
        boolean rawString = false;
        String dollarTag = null;
        boolean lineComment = false;
        boolean blockComment = false;
        int blockDepth = 0;

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
                if (nestedComments && ch == '/' && next == '*') {
                    current.append(next);
                    i++;
                    blockDepth++;
                } else if (ch == '*' && next == '/') {
                    current.append(next);
                    i++;
                    blockDepth--;
                    if (blockDepth <= 0) {
                        blockComment = false;
                    }
                }
                continue;
            }
            if (dollarTag != null) {
                if (sql.startsWith(dollarTag, i)) {
                    current.append(dollarTag);
                    i += dollarTag.length() - 1;
                    dollarTag = null;
                } else {
                    current.append(ch);
                }
                continue;
            }
            if (quote != 0) {
                current.append(ch);
                if (escapeString && ch == '\\' && i + 1 < sql.length()) {
                    current.append(sql.charAt(++i));
                } else if (ch == quote) {
                    if (!rawString && next == quote) {
                        current.append(next);
                        i++;
                    } else {
                        quote = 0;
                        escapeString = false;
                        rawString = false;
                    }
                }
                continue;
            }
            if (ch == '\'' || ch == '"' || ch == '`'
                    || (bracketQuotes && ch == '[')) {
                quote = ch == '[' ? ']' : ch;
                rawString = dialect == DbType.antspark
                        && (ch == '\'' || ch == '"')
                        && isRawStringPrefix(sql, i);
                boolean backslashQuotes = mysqlFamily || hiveFamily;
                escapeString = !rawString && (backslashQuotes
                        ? (ch == '\'' || ch == '"')
                        : (ch == '\'' && isEscapeStringPrefix(sql, i)));
                current.append(ch);
            } else if (ch == '$' && postgres && isDollarQuoteStart(sql, i)) {
                String tag = dollarTagAt(sql, i);
                if (tag != null) {
                    dollarTag = tag;
                    current.append(tag);
                    i += tag.length() - 1;
                } else {
                    current.append(ch);
                }
            } else if (ch == '-' && next == '-'
                    && (!mysqlFamily || i + 2 >= sql.length()
                            || Character.isWhitespace(sql.charAt(i + 2)))) {
                lineComment = true;
                current.append(ch);
            } else if (hashComments && ch == '#') {
                lineComment = true;
                current.append(ch);
            } else if (ch == '/' && next == '*') {
                blockComment = true;
                blockDepth = 1;
                current.append(ch);
            } else if (ch == ';') {
                addStatement(statements, current, hashComments, nestedComments);
            } else {
                current.append(ch);
            }
        }
        addStatement(statements, current, hashComments, nestedComments);
        return statements;
    }

    private static boolean isIdentifierChar(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_' || ch == '$';
    }

    private static boolean isDollarQuoteStart(String sql, int index) {
        return index == 0 || !isIdentifierChar(sql.charAt(index - 1));
    }

    private static boolean isRawStringPrefix(String sql, int quoteIndex) {
        if (quoteIndex == 0) {
            return false;
        }
        char prefix = sql.charAt(quoteIndex - 1);
        if (prefix != 'r' && prefix != 'R') {
            return false;
        }
        return quoteIndex - 1 == 0 || !isIdentifierChar(sql.charAt(quoteIndex - 2));
    }

    private static boolean isEscapeStringPrefix(String sql, int quoteIndex) {
        if (quoteIndex == 0) {
            return false;
        }
        char prefix = sql.charAt(quoteIndex - 1);
        if (prefix != 'E' && prefix != 'e') {
            return false;
        }
        return quoteIndex - 1 == 0 || !isIdentifierChar(sql.charAt(quoteIndex - 2));
    }

    private static String dollarTagAt(String sql, int start) {
        if (start + 1 < sql.length() && sql.charAt(start + 1) == '$') {
            return "$$";
        }
        int end = sql.indexOf('$', start + 1);
        if (end < 0) {
            return null;
        }
        String tag = sql.substring(start, end + 1);
        if (!tag.matches("\\$[A-Za-z_][A-Za-z0-9_]*\\$")) {
            return null;
        }
        return tag;
    }

    private static void addStatement(List<String> statements, StringBuilder current,
            boolean hashComments, boolean nestedComments) {
        String statement = current.toString().trim();
        current.setLength(0);
        if (!statement.isEmpty()
                && !isCommentOnly(statement, hashComments, nestedComments)) {
            statements.add(statement);
        }
    }

    private static boolean isCommentOnly(String statement, boolean hashComments,
            boolean nestedComments) {
        boolean lineComment = false;
        boolean blockComment = false;
        int blockDepth = 0;
        char quote = 0;
        StringBuilder visible = new StringBuilder();

        for (int i = 0; i < statement.length(); i++) {
            char ch = statement.charAt(i);
            char next = i + 1 < statement.length() ? statement.charAt(i + 1) : 0;
            if (lineComment) {
                if (ch == '\n') {
                    lineComment = false;
                }
                continue;
            }
            if (blockComment) {
                if (nestedComments && ch == '/' && next == '*') {
                    i++;
                    blockDepth++;
                } else if (ch == '*' && next == '/') {
                    i++;
                    blockDepth--;
                    if (blockDepth <= 0) {
                        blockComment = false;
                    }
                }
                continue;
            }
            if (quote != 0) {
                visible.append(ch);
                if (ch == quote) {
                    quote = 0;
                }
                continue;
            }
            if (ch == '\'' || ch == '"' || ch == '`') {
                quote = ch;
                visible.append(ch);
            } else if (ch == '-' && next == '-') {
                lineComment = true;
                i++;
            } else if (hashComments && ch == '#') {
                lineComment = true;
            } else if (ch == '/' && next == '*') {
                blockComment = true;
                blockDepth = 1;
                i++;
            } else if (!Character.isWhitespace(ch)) {
                visible.append(ch);
            }
        }
        return visible.length() == 0;
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

    private void processSelect(SQLSelect select) {
        SQLWithSubqueryClause with = select.getWithSubQuery();
        if (with == null) {
            processSelectQuery(select.getQuery());
            return;
        }
        cteScopes.push(new HashMap<String, String>());
        try {
            processWithSubQuery(with);
            processSelectQuery(select.getQuery());
        } finally {
            cteScopes.pop();
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

    private void processWithSubQuery(SQLWithSubqueryClause withSubQuery) {
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
                tempTables.add(target);
                prebound.put(entry, target);
                if (!cteScopes.isEmpty()) {
                    cteScopes.peek().put(identifierKey(alias), target);
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
                            key = CONSTANT_OUTPUT_PREFIX + "unknown:" + i;
                            namesKnown = false;
                        } else if (CONSTANT_EXPRESSION.equals(raw)) {
                            display = "col" + (i + 1);
                            while (usedKeys.contains(identifierKey(display))) {
                                display = display + "_";
                            }
                            usedKeys.add(identifierKey(display));
                            key = CONSTANT_OUTPUT_PREFIX + i;
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
                    targetWidthKnown.put(target, widthKnown);
                    targetNamesKnown.put(target, widthKnown && namesKnown);
                    targetMinWidth.put(target, names.size());
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
                tempTables.add(target);
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
                cteDeclaredColumns.put(target, declaredColumns);
                cteDeclaredKeys.put(target, declaredKeys);
            }
            targetColumnAliases.push(aliases);

            try {
                stackTargetTable.push(target);
                processSelect(entry.getSubQuery());
                stackTargetTable.pop();
                recordTargetShape(target);
            } finally {
                targetColumnAliases.pop();
            }
            if (!recursive && alias != null && !cteScopes.isEmpty()) {
                cteScopes.peek().put(identifierKey(alias), target);
            }
        }
    }

    private void processSelectQuery(SQLSelectQuery query) {
        if (query instanceof SQLSelectQueryBlock) {
            processQueryBlock((SQLSelectQueryBlock) query);
        } else if (query instanceof SQLUnionQuery) {
            SQLUnionQuery union = (SQLUnionQuery) query;
            String target = stackTargetTable.peek();
            boolean added = unionTargets.add(target);
            Integer previousOverride = targetWidthOverride.get(target);
            int staticWidth = staticUnionWidth(union, currentCteWidthScope());
            if (staticWidth > 0) {
                targetWidthOverride.put(target, staticWidth);
            }
            try {
                processSelectQuery(union.getLeft());
                int leftWidth = lastQueryBlockWidth;
                boolean leftKnown = lastQueryBlockWidthKnown;
                boolean leftNamesKnown = lastQueryBlockNamesKnown;
                int leftMinWidth = lastQueryBlockMinWidth;
                Integer overrideBeforeRight = targetWidthOverride.get(target);
                if (leftKnown && leftWidth > 0) {
                    targetWidthOverride.put(target, leftWidth);
                }
                incrementNonAuthoritative(target);
                try {
                    processSelectQuery(union.getRight());
                } finally {
                    decrementNonAuthoritative(target);
                    if (overrideBeforeRight == null) {
                        targetWidthOverride.remove(target);
                    } else {
                        targetWidthOverride.put(target, overrideBeforeRight);
                    }
                }
                int rightWidth = lastQueryBlockWidth;
                boolean rightKnown = lastQueryBlockWidthKnown;
                int rightMinWidth = lastQueryBlockMinWidth;
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
                lastQueryBlockWidth = leftKnown ? leftWidth : rightWidth;
                lastQueryBlockWidthKnown = leftKnown || rightKnown;
                // A UNION takes its output names from the first branch, even when the
                // width is established by another branch.
                lastQueryBlockNamesKnown = leftNamesKnown;
                lastQueryBlockMinWidth = Math.max(leftMinWidth, lastQueryBlockMinWidth);
            } finally {
                if (previousOverride == null) {
                    targetWidthOverride.remove(target);
                } else {
                    targetWidthOverride.put(target, previousOverride);
                }
                if (added) {
                    unionTargets.remove(target);
                }
            }
        }
    }

    private Map<String, Integer> currentCteWidthScope() {
        Map<String, Integer> scope = new HashMap<>();
        for (Map<String, String> frame : cteScopes) {
            for (Map.Entry<String, String> entry : frame.entrySet()) {
                String target = entry.getValue();
                List<String> outputs = target == null ? null : targetOutputs.get(target);
                boolean known = target != null
                        && Boolean.TRUE.equals(targetWidthKnown.get(target));
                int width = outputs == null ? 0 : outputs.size();
                if (known) {
                    Integer min = targetMinWidth.get(target);
                    if (min != null && min > 0) {
                        width = Math.max(width, min);
                    }
                }
                scope.put(entry.getKey(), known ? width : -1);
            }
        }
        return scope;
    }

    private int staticSelectWidth(SQLSelect select, Map<String, Integer> outerScope) {
        if (select == null) {
            return -1;
        }
        Map<String, Integer> scope = new HashMap<>(outerScope);
        SQLWithSubqueryClause with = select.getWithSubQuery();
        if (with != null) {
            DbType dialect = currentDialect();
            boolean exactCount = dialect == DbType.mysql || dialect == DbType.mariadb;
            boolean recursive = Boolean.TRUE.equals(with.getRecursive());
            if (recursive) {
                // WITH RECURSIVE binds every sibling before any body runs, including
                // forward references.
                for (SQLWithSubqueryClause.Entry entry : with.getEntries()) {
                    String alias = entry.getAlias();
                    if (alias != null) {
                        scope.put(identifierKey(alias), -1);
                    }
                }
            }
            for (SQLWithSubqueryClause.Entry entry : with.getEntries()) {
                String alias = entry.getAlias();
                if (alias == null) {
                    continue;
                }
                String aliasKey = identifierKey(alias);
                int declared = entry.getColumns() == null ? 0 : entry.getColumns().size();
                int width = staticSelectWidth(entry.getSubQuery(), scope);
                if (width < 0 && declared > 0 && exactCount) {
                    width = declared;
                }
                scope.put(aliasKey, width);
            }
            if (recursive) {
                // Resolve forward references that were still unknown in the first pass.
                int guard = with.getEntries().size() + 1;
                boolean changed = true;
                while (changed && guard-- > 0) {
                    changed = false;
                    for (SQLWithSubqueryClause.Entry entry : with.getEntries()) {
                        String alias = entry.getAlias();
                        if (alias == null) {
                            continue;
                        }
                        int width = staticSelectWidth(entry.getSubQuery(), scope);
                        String key = identifierKey(alias);
                        if (width >= 0 && !Integer.valueOf(width).equals(scope.get(key))) {
                            scope.put(key, width);
                            changed = true;
                        }
                    }
                }
            }
        }
        return staticUnionWidth(select.getQuery(), scope);
    }

    private static final String CONSTANT_OUTPUT_PREFIX = "\u0000constant:";
    private static final String CONSTANT_EXPRESSION = "\u0000constant-expression";

    /**
     * Column names a query will produce when they can be derived without analyzing
     * rows, used to pre-bind forward-referenced recursive CTEs.
     */
    private List<String> staticOutputNames(SQLSelect select,
            Map<String, List<String>> scope) {
        if (select == null) {
            return null;
        }
        Map<String, List<String>> nested = new HashMap<>(scope);
        SQLWithSubqueryClause with = select.getWithSubQuery();
        if (with != null) {
            boolean recursive = Boolean.TRUE.equals(with.getRecursive());
            if (recursive) {
                for (SQLWithSubqueryClause.Entry entry : with.getEntries()) {
                    String alias = entry.getAlias();
                    if (alias != null) {
                        nested.put(identifierKey(alias), null);
                    }
                }
            }
            if (recursive) {
                int guard = with.getEntries().size() + 1;
                boolean changed = true;
                while (changed && guard-- > 0) {
                    changed = false;
                    for (SQLWithSubqueryClause.Entry entry : with.getEntries()) {
                        String alias = entry.getAlias();
                        if (alias == null) {
                            continue;
                        }
                        String key = identifierKey(alias);
                        List<String> names = mergeDeclaredColumns(
                                rawDeclaredColumns(entry),
                                staticOutputNames(entry.getSubQuery(), nested));
                        if (names != null && !names.equals(nested.get(key))) {
                            nested.put(key, names);
                            changed = true;
                        }
                    }
                }
            } else {
                // A non-recursive WITH only sees earlier entries, so one ordered pass.
                for (SQLWithSubqueryClause.Entry entry : with.getEntries()) {
                    String alias = entry.getAlias();
                    if (alias == null) {
                        continue;
                    }
                    // Register the name even when its shape is unknown, so it still
                    // shadows a same-named physical table.
                    nested.put(identifierKey(alias), mergeDeclaredColumns(
                            rawDeclaredColumns(entry),
                            staticOutputNames(entry.getSubQuery(), nested)));
                }
            }
        }
        return staticOutputNames(select.getQuery(), nested);
    }

    private static List<String> mergeDeclaredColumns(List<String> declared,
            List<String> derived) {
        if (declared == null) {
            return derived;
        }
        if (derived == null) {
            return new ArrayList<>(declared);
        }
        List<String> merged = new ArrayList<>(derived);
        for (int i = 0; i < declared.size(); i++) {
            if (i < merged.size()) {
                merged.set(i, declared.get(i));
            } else {
                merged.add(declared.get(i));
            }
        }
        return merged;
    }

    private List<String> padToStaticWidth(List<String> declared, SQLSelect select,
            Map<String, Integer> widthScope) {
        int width;
        try {
            width = staticSelectWidth(select, widthScope);
        } catch (RuntimeException e) {
            width = -1;
        }
        if (width < 0) {
            return null;
        }
        if (width <= declared.size()) {
            return new ArrayList<>(declared);
        }
        List<String> names = new ArrayList<>(declared);
        while (names.size() < width) {
            names.add(null);
        }
        return names;
    }

    private static List<String> rawDeclaredColumns(SQLWithSubqueryClause.Entry entry) {
        if (entry.getColumns() == null || entry.getColumns().isEmpty()) {
            return null;
        }
        List<String> names = new ArrayList<>();
        for (com.alibaba.druid.sql.ast.SQLName column : entry.getColumns()) {
            names.add(column.toString());
        }
        return names;
    }

    private static String unescapeQuotedName(String raw) {
        return raw.replace("\"\"", "\"").replace("\\\"", "\"")
                .replace("``", "`");
    }

    private static String rawProjectionName(SQLPropertyExpr expr) {
        String[] parts = MetadataCatalog.splitQualifiedNamePreservingQuotes(
                expr.toString());
        return parts.length == 0 ? expr.getName() : parts[parts.length - 1];
    }

    private static String rawAlias(SQLSelectItem item) {
        String alias = item.getAlias();
        if (alias == null) {
            return null;
        }
        String text = item.toString().trim();
        String exprText = item.getExpr().toString();
        if (text.startsWith(exprText)) {
            String rest = text.substring(exprText.length()).trim();
            if (rest.regionMatches(true, 0, "AS ", 0, 3)
                    || rest.regionMatches(true, 0, "AS\t", 0, 3)) {
                rest = rest.substring(3).trim();
            }
            if (!rest.isEmpty()) {
                return rest;
            }
        }
        return alias;
    }

    private List<String> staticOutputNames(SQLSelectQuery query,
            Map<String, List<String>> scope) {
        if (query instanceof SQLSelectQueryBlock) {
            SQLSelectQueryBlock block = (SQLSelectQueryBlock) query;
            List<String> names = new ArrayList<>();
            for (SQLSelectItem item : block.getSelectList()) {
                SQLExpr expr = item.getExpr();
                if (expr == null) {
                    return null;
                }
                if (item.getAlias() != null) {
                    names.add(rawAlias(item));
                } else if (expr instanceof SQLIdentifierExpr) {
                    names.add(expr.toString());
                } else if (expr instanceof SQLPropertyExpr
                        && !isStarProperty(expr)) {
                    names.add(rawProjectionName((SQLPropertyExpr) expr));
                } else if (expr instanceof SQLAllColumnExpr) {
                    List<String> star = staticFromNames(block.getFrom(), scope);
                    if (star == null) {
                        return null;
                    }
                    names.addAll(star);
                } else if (isStarProperty(expr)) {
                    String owner = ((SQLPropertyExpr) expr).getOwner().toString();
                    SQLTableSource table = isSingleSegmentName(owner)
                            ? findTableSource(block.getFrom(), lastSegment(owner))
                            : findQualifiedTable(block.getFrom(), qualifiedKey(owner),
                                    false, java.util.Collections.<String, Integer>emptyMap());
                    List<String> star = table == null ? null
                            : staticTableNames(table, scope);
                    if (star == null) {
                        return null;
                    }
                    names.addAll(star);
                } else {
                    // A constant owns a position but does not name a column; keep it
                    // distinct from a padded unknown position (null).
                    names.add(CONSTANT_EXPRESSION);
                }
            }
            return names;
        }
        if (query instanceof SQLUnionQuery) {
            List<String> left = staticOutputNames(((SQLUnionQuery) query).getLeft(), scope);
            return left;
        }
        return null;
    }

    private List<String> staticFromNames(SQLTableSource from,
            Map<String, List<String>> scope) {
        if (from == null) {
            return null;
        }
        if (from instanceof SQLExprTableSource) {
            return staticSourceNames((SQLExprTableSource) from, scope);
        }
        if (from instanceof SQLSubqueryTableSource) {
            return ((SQLSubqueryTableSource) from).getAlias() != null
                    ? staticTableNames(from, scope)
                    : staticOutputNames(((SQLSubqueryTableSource) from).getSelect(), scope);
        }
        if (from instanceof SQLUnionQueryTableSource) {
            return staticOutputNames(((SQLUnionQueryTableSource) from).getUnion(), scope);
        }
        if (from instanceof SQLJoinTableSource) {
            SQLJoinTableSource join = (SQLJoinTableSource) from;
            List<String> left = staticFromNames(join.getLeft(), scope);
            List<String> right = staticFromNames(join.getRight(), scope);
            if (left == null || right == null) {
                return null;
            }
            if (join.getUsing() != null && !join.getUsing().isEmpty()) {
                List<String> names = new ArrayList<>();
                Set<String> usingKeys = new HashSet<>();
                for (SQLExpr expr : join.getUsing()) {
                    names.add(expr.toString());
                    usingKeys.add(identifierKey(expr.toString()));
                }
                for (String name : left) {
                    if (name == null || !usingKeys.contains(identifierKey(name))) {
                        names.add(name);
                    }
                }
                for (String name : right) {
                    if (name == null || !usingKeys.contains(identifierKey(name))) {
                        names.add(name);
                    }
                }
                return names;
            }
            List<String> names = new ArrayList<>(left);
            names.addAll(right);
            return names;
        }
        return null;
    }

    private String quoteCatalogName(String name) {
        DbType dialect = currentDialect();
        if (dialect == DbType.mysql || dialect == DbType.mariadb) {
            return "`" + name + "`";
        }
        return "\"" + name + "\"";
    }

    private List<String> staticTableNames(SQLTableSource table,
            Map<String, List<String>> scope) {
        if (table instanceof SQLExprTableSource) {
            return staticSourceNames((SQLExprTableSource) table, scope);
        }
        if (table instanceof SQLSubqueryTableSource) {
            return staticOutputNames(
                    ((SQLSubqueryTableSource) table).getSelect(), scope);
        }
        if (table instanceof SQLUnionQueryTableSource) {
            return staticOutputNames(((SQLUnionQueryTableSource) table).getUnion(), scope);
        }
        return null;
    }

    private static SQLSelect queryExpressionSelect(SQLExpr expr) {
        if (expr instanceof SQLQueryExpr) {
            return ((SQLQueryExpr) expr).getSubQuery();
        }
        if (expr instanceof SQLMethodInvokeExpr) {
            SQLMethodInvokeExpr call = (SQLMethodInvokeExpr) expr;
            if ("LATERAL".equalsIgnoreCase(call.getMethodName())
                    && call.getArguments() != null) {
                for (SQLExpr argument : call.getArguments()) {
                    if (argument instanceof SQLQueryExpr) {
                        return ((SQLQueryExpr) argument).getSubQuery();
                    }
                }
            }
        }
        return null;
    }

    private boolean isLateralSource(SQLTableSource source) {
        if (!(source instanceof SQLExprTableSource)) {
            return false;
        }
        SQLExpr expr = ((SQLExprTableSource) source).getExpr();
        return expr instanceof SQLMethodInvokeExpr
                && "LATERAL".equalsIgnoreCase(
                        ((SQLMethodInvokeExpr) expr).getMethodName());
    }

    private List<String> staticSourceNames(SQLExprTableSource table,
            Map<String, List<String>> scope) {
        SQLSelect inline = queryExpressionSelect(table.getExpr());
        if (inline != null) {
            return staticOutputNames(inline, scope);
        }
        String name = extractTableName(table);
        if (name == null) {
            return null;
        }
        if (isSingleSegmentName(name)) {
            String key = identifierKey(name);
            if (scope != null && scope.containsKey(key)) {
                return scope.get(key);
            }
            if (findCteTarget(key) != null) {
                return null;
            }
        }
        if (context != null) {
            TableLocation location = resolveMetadataTable(name);
            if (location != null) {
                List<String> names = new ArrayList<>();
                for (Column column : location.getTable().getFields()) {
                    names.add(column.isQuoted()
                            ? quoteCatalogName(column.getColumnName())
                            : column.getColumnName());
                }
                return names;
            }
        }
        return null;
    }

    private int staticUnionWidth(SQLSelectQuery query, Map<String, Integer> scope) {
        if (query instanceof SQLSelectQueryBlock) {
            SQLSelectQueryBlock block = (SQLSelectQueryBlock) query;
            int width = 0;
            for (SQLSelectItem item : block.getSelectList()) {
                SQLExpr expr = item.getExpr();
                if (expr == null) {
                    return -1;
                }
                if (expr instanceof SQLAllColumnExpr) {
                    int star = staticStarWidth(block.getFrom(), null, scope);
                    if (star < 0) {
                        return -1;
                    }
                    width += star;
                } else if (isStarProperty(expr)) {
                    int star = staticStarWidth(block.getFrom(),
                            ((SQLPropertyExpr) expr).getOwner(), scope);
                    if (star < 0) {
                        return -1;
                    }
                    width += star;
                } else {
                    width++;
                }
            }
            return width;
        }
        if (query instanceof SQLUnionQuery) {
            SQLUnionQuery union = (SQLUnionQuery) query;
            int left = staticUnionWidth(union.getLeft(), scope);
            int right = staticUnionWidth(union.getRight(), scope);
            if (left > 0 && right > 0) {
                return left == right ? left : -1;
            }
            return left > 0 ? left : right;
        }
        return -1;
    }

    /**
     * Width of a star projection whose table is already bound (for example a CTE).
     * Returns -1 when the width cannot be derived without building the source.
     */
    private int staticStarWidth(SQLTableSource from, SQLExpr owner,
            Map<String, Integer> scope) {
        if (owner == null) {
            return staticFromWidth(from, scope);
        }
        String ownerName = owner.toString();
        SQLTableSource table;
        if (isSingleSegmentName(ownerName)) {
            table = findTableSource(from, lastSegment(ownerName));
        } else {
            // A qualified owner must match the whole reference; taking the last
            // segment could select a same-named table from another schema.
            table = findQualifiedTable(from, qualifiedKey(ownerName), false, scope);
        }
        return table == null ? -1 : staticTableWidth(table, scope);
    }

    private SQLTableSource findQualifiedTable(SQLTableSource from, String key,
            boolean hidden, Map<String, Integer> scope) {
        if (from == null || hidden || key == null) {
            return null;
        }
        if (from instanceof SQLExprTableSource) {
            SQLExprTableSource table = (SQLExprTableSource) from;
            if (table.getAlias() != null) {
                // An alias hides the original table name, and a qualified owner can
                // never refer to an alias.
                return null;
            }
            String name = extractTableName(table);
            if (name == null) {
                return null;
            }
            String leafKey = isSingleSegmentName(name) ? identifierKey(name) : null;
            if (leafKey != null && (scope != null && scope.containsKey(leafKey)
                    || findCteTarget(leafKey) != null)) {
                // The leaf binds to a CTE (possibly a nested one registered in the
                // static scope); a qualified owner never refers to it and the catalog
                // must not be consulted for the CTE's name.
                return null;
            }
            // Compare the bound identity, which honors search_path and per-segment
            // quoting, and keep the written spelling for quoted names whose display
            // form drops the quotes.
            String bound = name;
            if (context != null) {
                TableLocation location = resolveMetadataTable(name);
                if (location != null) {
                    bound = location.getDisplayName();
                }
            }
            return key.equals(qualifiedKey(bound)) || key.equals(qualifiedKey(name))
                    ? from : null;
        }
        if (from instanceof SQLJoinTableSource) {
            SQLJoinTableSource join = (SQLJoinTableSource) from;
            boolean childHidden = join.getAlias() != null;
            SQLTableSource found = findQualifiedTable(join.getLeft(), key,
                    childHidden, scope);
            return found != null ? found
                    : findQualifiedTable(join.getRight(), key, childHidden, scope);
        }
        return null;
    }

    private int staticTableWidth(SQLTableSource table, Map<String, Integer> scope) {
        if (table instanceof SQLExprTableSource) {
            return staticSourceWidth((SQLExprTableSource) table, scope);
        }
        if (table instanceof SQLSubqueryTableSource) {
            return staticSelectWidth(((SQLSubqueryTableSource) table).getSelect(), scope);
        }
        if (table instanceof SQLUnionQueryTableSource) {
            return staticUnionWidth(((SQLUnionQueryTableSource) table).getUnion(), scope);
        }
        if (table instanceof SQLJoinTableSource) {
            return staticFromWidth(table, scope);
        }
        return -1;
    }

    private static String lastSegment(String qualifiedName) {
        String[] parts = MetadataCatalog.splitQualifiedNamePreservingQuotes(qualifiedName);
        return parts.length == 0 ? qualifiedName : parts[parts.length - 1];
    }

    private int staticFromWidth(SQLTableSource from, Map<String, Integer> scope) {
        if (from instanceof SQLExprTableSource) {
            return staticSourceWidth((SQLExprTableSource) from, scope);
        }
        if (from instanceof SQLJoinTableSource) {
            SQLJoinTableSource join = (SQLJoinTableSource) from;
            int left = staticFromWidth(join.getLeft(), scope);
            int right = staticFromWidth(join.getRight(), scope);
            if (left < 0 || right < 0) {
                return -1;
            }
            int using = join.getUsing() == null ? 0 : join.getUsing().size();
            return left + right - using;
        }
        if (from instanceof SQLSubqueryTableSource) {
            return staticSelectWidth(((SQLSubqueryTableSource) from).getSelect(), scope);
        }
        if (from instanceof SQLUnionQueryTableSource) {
            return staticUnionWidth(((SQLUnionQueryTableSource) from).getUnion(), scope);
        }
        return -1;
    }

    private SQLTableSource findTableSource(SQLTableSource from, String owner) {
        if (from == null || owner == null) {
            return null;
        }
        String key = identifierKey(owner);
        if (key == null) {
            return null;
        }
        SQLTableSource byLeafAlias = findLeafByAlias(from, key, false);
        if (byLeafAlias != null) {
            return byLeafAlias;
        }
        SQLTableSource byJoinAlias = findJoinByAlias(from, key, false);
        if (byJoinAlias != null) {
            return byJoinAlias;
        }
        return findUnaliasedByName(from, key, false);
    }

    private SQLTableSource findLeafByAlias(SQLTableSource from, String key, boolean hidden) {
        if (from == null || hidden) {
            return null;
        }
        if (from instanceof SQLExprTableSource) {
            String alias = ((SQLExprTableSource) from).getAlias();
            return alias != null && key.equals(identifierKey(alias)) ? from : null;
        }
        if (from instanceof SQLSubqueryTableSource) {
            String alias = ((SQLSubqueryTableSource) from).getAlias();
            return alias != null && key.equals(identifierKey(alias)) ? from : null;
        }
        if (from instanceof SQLUnionQueryTableSource) {
            String alias = ((SQLUnionQueryTableSource) from).getAlias();
            return alias != null && key.equals(identifierKey(alias)) ? from : null;
        }
        if (from instanceof SQLJoinTableSource) {
            SQLJoinTableSource join = (SQLJoinTableSource) from;
            boolean childHidden = join.getAlias() != null;
            SQLTableSource found = findLeafByAlias(join.getLeft(), key, childHidden);
            return found != null ? found : findLeafByAlias(join.getRight(), key, childHidden);
        }
        return null;
    }

    private SQLTableSource findJoinByAlias(SQLTableSource from, String key, boolean hidden) {
        if (!(from instanceof SQLJoinTableSource) || hidden) {
            return null;
        }
        SQLJoinTableSource join = (SQLJoinTableSource) from;
        if (join.getAlias() != null) {
            return key.equals(identifierKey(join.getAlias())) ? join : null;
        }
        SQLTableSource found = findJoinByAlias(join.getLeft(), key, false);
        return found != null ? found : findJoinByAlias(join.getRight(), key, false);
    }

    private SQLTableSource findUnaliasedByName(SQLTableSource from, String key,
            boolean hidden) {
        if (from == null || hidden) {
            return null;
        }
        if (from instanceof SQLExprTableSource) {
            SQLExprTableSource table = (SQLExprTableSource) from;
            if (table.getAlias() != null) {
                // An alias hides the original table name.
                return null;
            }
            return key.equals(identifierKey(lastSegment(extractTableName(table))))
                    ? table : null;
        }
        if (from instanceof SQLJoinTableSource) {
            SQLJoinTableSource join = (SQLJoinTableSource) from;
            boolean childHidden = join.getAlias() != null;
            SQLTableSource found = findUnaliasedByName(join.getLeft(), key, childHidden);
            return found != null
                    ? found : findUnaliasedByName(join.getRight(), key, childHidden);
        }
        return null;
    }

    private int staticSourceWidth(SQLExprTableSource table, Map<String, Integer> scope) {
        SQLSelect inline = queryExpressionSelect(table.getExpr());
        if (inline != null) {
            return staticSelectWidth(inline, scope);
        }
        String rawName = extractTableName(table);
        if (rawName == null) {
            return -1;
        }
        if (isSingleSegmentName(rawName)) {
            String key = identifierKey(rawName);
            if (key != null && scope.containsKey(key)) {
                return scope.get(key);
            }
            String cteTarget = findCteTarget(key);
            if (cteTarget != null) {
                // A CTE shadows any catalog table with the same name.
                if (!Boolean.TRUE.equals(targetWidthKnown.get(cteTarget))) {
                    return -1;
                }
                List<String> outputs = targetOutputs.get(cteTarget);
                int width = outputs == null ? 0 : outputs.size();
                Integer min = targetMinWidth.get(cteTarget);
                if (min != null && min > 0) {
                    width = Math.max(width, min);
                }
                return width > 0 ? width : -1;
            }
        }
        if (context != null) {
            TableLocation location = resolveMetadataTable(rawName);
            if (location != null) {
                return Math.max(location.getTable().getFields().size(), 1);
            }
        }
        return -1;
    }

    private void processQueryBlock(SQLSelectQueryBlock queryBlock) {
        currentStarBudget = -1;
        currentExpandedSourceNodes = new HashMap<>();
        SQLTableSource fromItem = queryBlock.getFrom();
        if (fromItem == null) {
            if (metadataPass) {
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
            lastQueryBlockWidth = position;
            lastQueryBlockWidthKnown = widthKnown;
            lastQueryBlockNamesKnown = namesKnown;
            lastQueryBlockMinWidth = minWidth;
            return;
        }

        Map<String, List<SourceTable>> using = new LinkedHashMap<>();
        stackJoinBindings.push(new HashMap<String, SourceNode>());
        stackHiddenSources.push(new HashSet<SourceTable>());
        SourceNode node = buildSourceNode(fromItem, using);
        List<SourceTable> sources = node.sources;
        if (sources.isEmpty()) {
            stackHiddenSources.pop();
            stackJoinBindings.pop();
            return;
        }

        collectTablesFromExpression(queryBlock.getWhere());

        stackSources.push(sources);
        lateralScopes.push(Boolean.FALSE);
        stackUsing.push(using);
        stackStarPlan.push(node.plan);
        stackSourceNode.push(node);
        stackSourceTable.push(sources.get(0).displayName);
        targetOutputs.computeIfAbsent(stackTargetTable.peek(), k -> new ArrayList<String>());
        if (metadataPass) {
            learnBlockExplicitReferences(queryBlock, outerBlockLocals(false));
        }

        List<SQLSelectItem> selectItems = queryBlock.getSelectList();
        int inferredUnknownWidth = -1;
        int unknownItemIndex = -1;
        int[] startOverride = new int[selectItems.size()];
        for (int i = 0; i < startOverride.length; i++) {
            startOverride[i] = -1;
        }
        List<String> declaredWidths = currentTargetColumnAliases(stackTargetTable.peek());
        DbType dialect = currentDialect();
        boolean declaredExact = declaredWidths != null
                && (dialect == DbType.mysql || dialect == DbType.mariadb);
        int positionalWidth = -1;
        if (declaredExact) {
            positionalWidth = declaredWidths.size();
        } else {
            Integer overrideWidth = targetWidthOverride.get(stackTargetTable.peek());
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
        currentStarBudget = inferredUnknownWidth;
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
                        currentExpandedSourceNodes.put(source, sourceNode);
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
            uncertainOrdinal = positionalWidth > 0 && !positionCertain
                    || detachedMetadata() && unknownStarSeen ? start : -1;
            boolean inferredWidth = itemIndex == unknownItemIndex
                    && inferredUnknownWidth > 0;
            if (unknownStar && !starOutputWidthKnown(itemExpr) && !inferredWidth) {
                // Only an unknown-width wildcard leaves later positions undetermined.
                unknownStarSeen = true;
            }
            currentOrdinal = start;
            int advanced;
            try {
                advanced = processSelectItem(item);
            } catch (RuntimeException e) {
                if (!metadataPass) {
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
                currentStarBudget = -1;
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
        uncertainOrdinal = -1;
        currentOrdinal = -1;
        lastQueryBlockWidth = position;
        lastQueryBlockWidthKnown = widthKnown;
        lastQueryBlockNamesKnown = namesKnown;
        lastQueryBlockMinWidth = minWidth;

        stackSourceTable.pop();
        stackSourceNode.pop();
        stackStarPlan.pop();
        stackUsing.pop();
        stackSources.pop();
        lateralScopes.pop();
        stackHiddenSources.pop();
        stackJoinBindings.pop();
    }

    private int recordUncertainOuterStar(SQLPropertyExpr star, int position) {
        String target = stackTargetTable.peek();
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
        String target = stackTargetTable.peek();
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
        String target = stackTargetTable.peek();
        List<String> outputs = targetOutputs.get(target);
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
        String target = stackTargetTable.peek();
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
                                source.temporary, tempTables.contains(target));
                        wildcard.setPositionalWildcard(true);
                        wildcard.setWildcardWidthKnown(true);
                        wildcard.setWildcardWidth(width);
                        instructions.add(wildcard);
                        recordWildcardSpan(target, position, position + width,
                                lineageTableName(source), source.temporary);
                        targetWildcardContribution.put(target, Boolean.TRUE);
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
                                    tempTables.contains(target));
                            wildcard.setPositionalWildcard(true);
                            wildcard.setWildcardWidthKnown(true);
                            wildcard.setWildcardWidth(1);
                            instructions.add(wildcard);
                            recordWildcardSpan(target, position + i,
                                    position + i + 1, ref.name, ref.temporary);
                            targetWildcardContribution.put(target, Boolean.TRUE);
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
                                tempTables.contains(target));
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
                        targetWildcardContribution.put(target, Boolean.TRUE);
                    }
                } else {
                    Instruction wildcard = new Instruction(OperationType.COLUMNS_INJECT,
                            lineageTableName(source), target, position,
                            source.temporary, tempTables.contains(target));
                    wildcard.setPositionalWildcard(true);
                    recordWildcardSpan(target, position, Integer.MAX_VALUE,
                            lineageTableName(source), source.temporary);
                    instructions.add(wildcard);
                    targetWildcardContribution.put(target, Boolean.TRUE);
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
                    source.temporary, tempTables.contains(target));
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
            targetWildcardContribution.put(target, Boolean.TRUE);
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
                            candidate.temporary, tempTables.contains(target));
                    wildcard.setPositionalWildcard(true);
                    wildcard.setWildcardWidthKnown(true);
                    wildcard.setWildcardWidth(Math.max(binding.width, 1));
                    instructions.add(wildcard);
                    recordWildcardSpan(target, position,
                            position + Math.max(binding.width, 1),
                            lineageTableName(candidate), candidate.temporary);
                    targetWildcardContribution.put(target, Boolean.TRUE);
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
                                    pos, ref.temporary, tempTables.contains(target));
                            wildcard.setPositionalWildcard(true);
                            wildcard.setWildcardWidthKnown(true);
                            wildcard.setWildcardWidth(1);
                            instructions.add(wildcard);
                            recordWildcardSpan(target, pos, pos + 1, ref.name,
                                    ref.temporary);
                            targetWildcardContribution.put(target, Boolean.TRUE);
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
                : quoted ? "\"" + column.replace("\"", "\"\"") + "\""
                        : renderIdentifier(column);
        instructions.add(new Instruction(OperationType.COLUMN_MAPPING,
                lineageTableName(source) + "." + rendered,
                target + "." + renderIdentifier(targetName), position,
                source.temporary, tempTables.contains(target)));
        if (source.wildcardContribution && source.temporary) {
            int relative = -1;
            String key = quoted != null && quoted
                    ? identifierKey("\"" + column.replace("\"", "\"\"") + "\"")
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
                tempTables.contains(target));
        wildcard.setPositionalWildcard(true);
        wildcard.setWildcardWidthKnown(true);
        wildcard.setWildcardWidth(1);
        instructions.add(wildcard);
        recordWildcardSpan(target, position, position + 1, sourceName, temporary);
        targetWildcardContribution.put(target, Boolean.TRUE);
    }

    private void addOuterWildcardOpen(SourceTable source, String target,
            int position) {
        Instruction wildcard = new Instruction(OperationType.COLUMNS_INJECT,
                lineageTableName(source), target, position,
                source.temporary, tempTables.contains(target));
        wildcard.setPositionalWildcard(true);
        instructions.add(wildcard);
        targetWildcardContribution.put(target, Boolean.TRUE);
        recordWildcardSpan(target, position, Integer.MAX_VALUE,
                lineageTableName(source), source.temporary);
    }

    private void addOuterWildcard(SourceTable source, String target, int position) {
        Instruction wildcard = new Instruction(OperationType.COLUMNS_INJECT,
                lineageTableName(source), target, position,
                source.temporary, tempTables.contains(target));
        wildcard.setPositionalWildcard(true);
        wildcard.setWildcardWidthKnown(true);
        wildcard.setWildcardWidth(1);
        instructions.add(wildcard);
        targetWildcardContribution.put(target, Boolean.TRUE);
        recordWildcardSpan(target, position, position + 1,
                lineageTableName(source), source.temporary);
    }

    private void recordOuterIdentifier(String rawName, String target,
            String targetName, int position) {
        String key = identifierKey(rawName);
        Map<String, List<SourceTable>> using = stackUsing.isEmpty()
                ? null : stackUsing.peek();
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
        List<StarOutput> planned = stackStarPlan.isEmpty()
                ? java.util.Collections.<StarOutput>emptyList()
                : stackStarPlan.peek();
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
        if (stackUsing.isEmpty() || usingParticipantsByIdentity.isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, List<SourceTable>> using = stackUsing.peek();
        if (using == null) {
            return new ArrayList<>();
        }
        Map<String, List<SourceTable>> sides = usingParticipantsByIdentity.get(using);
        if (sides == null || !sides.containsKey(key)) {
            return new ArrayList<>();
        }
        return new ArrayList<>(sides.get(key));
    }

    private List<SourceTable> currentUsingParticipants(String key) {
        if (stackUsing.isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, List<SourceTable>> using = stackUsing.peek();
        if (using == null || usingParticipantsByIdentity.isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, List<SourceTable>> participants = usingParticipantsByIdentity.get(using);
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
    private void recordScalarTableLevel(ScalarSubquery inner, String targetTable,
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
            if (targetColumn != null && !tempTables.contains(targetTable)) {
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
                        tempTables.contains(targetTable));
                wildcard.setPositionalWildcard(true);
                wildcard.setWildcardWidthKnown(true);
                wildcard.setWildcardWidth(1);
                instructions.add(wildcard);
                recordWildcardSpan(targetTable, position, position + 1, table, false);
                targetWildcardContribution.put(targetTable, Boolean.TRUE);
            }
        }
    }

    private int processScalarSubqueryProjection(SQLSelectItem item, SQLQueryExpr expr,
            int position) {
        String targetTable = stackTargetTable.peek();
        int savedOrdinal = currentOrdinal;
        currentOrdinal = position;
        try {
            return processScalarSubqueryProjection(item, expr);
        } finally {
            currentOrdinal = savedOrdinal;
        }
    }

    private int processScalarSubqueryProjection(SQLSelectItem item, SQLQueryExpr expr) {
        String targetTable = stackTargetTable.peek();
        ScalarSubquery inner = analyzeScalarSubquery(expr);
        if (inner == null) {
            return 1;
        }
        if (inner.columns.isEmpty()) {
            recordScalarTableLevel(inner, stackTargetTable.peek(), currentOrdinal, null);
            return 1;
        }
        List<String> declaredColumns = currentTargetColumnAliases(targetTable);
        String lockedName = lockedOutputName(targetTable, currentOrdinal);
        String lockedKey = lockedOutputKey(targetTable, currentOrdinal);
        String targetColumn;
        String targetKey;
        if (lockedName != null) {
            targetColumn = lockedName;
            targetKey = lockedKey != null ? lockedKey : identifierKey(lockedName);
        } else if (declaredColumns != null && currentOrdinal >= 0
                && currentOrdinal < declaredColumns.size()) {
            targetColumn = declaredColumns.get(currentOrdinal);
            targetKey = identifierKey(targetColumn);
        } else if (item.getAlias() != null) {
            targetColumn = normalizeIdentifier(item.getAlias());
            targetKey = identifierKey(item.getAlias());
        } else {
            targetColumn = inner.columns.get(0);
            targetKey = identifierKey(targetColumn);
        }
        setTargetOutput(targetTable, currentOrdinal, targetColumn, targetKey);
        markOutputNameKnown(targetTable, currentOrdinal, true);
        if (uncertainOrdinal >= 0 && currentOrdinal == uncertainOrdinal) {
            // The position follows an unknown-width wildcard, so no edge may be
            // pinned to a column, exactly like an ordinary projection there.
            return 1;
        }
        for (int i = 0; i < inner.columns.size(); i++) {
            String source = inner.references.get(i);
            instructions.add(new Instruction(OperationType.COLUMN_MAPPING, source,
                    targetTable + "." + renderIdentifier(targetColumn), currentOrdinal,
                    true, tempTables.contains(targetTable)));
        }
        return 1;
    }

    private boolean containsScalarSubquery(SQLExpr expr) {
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
        String targetTable = stackTargetTable.peek();
        List<String> outputs = copyOutputs(targetOutputs.get(targetTable));
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
                    tempTables.contains(targetTable)));
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
        String targetTable = stackTargetTable.peek();
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
        setTargetOutput(stackTargetTable.peek(), position, name, key);
        markOutputNameKnown(stackTargetTable.peek(), position, true);
        return 1;
    }

    /**
     * The output of an analyzed scalar subquery: the temporary that holds it and the
     * names of its columns.
     */
    private static final class ScalarSubquery {
        private final String temp;
        private final List<String> columns;
        /**
         * The exact references the inner analysis wrote, so the outer projection
         * names the same vertices.
         */
        private final List<String> references;

        private ScalarSubquery(String temp, List<String> columns,
                List<String> references) {
            this.temp = temp;
            this.columns = columns;
            this.references = references;
        }
    }

    /**
     * Analyze a scalar subquery as a one-column derived table so its own lineage is
     * kept instead of being folded into the enclosing expression.
     */
    private ScalarSubquery analyzeScalarSubquery(SQLQueryExpr expr) {
        SQLSelect select = expr.getSubQuery();
        if (select == null) {
            return null;
        }
        String target = getTempTableName();
        tempTables.add(target);
        int savedOrdinal = currentOrdinal;
        int savedUncertain = uncertainOrdinal;
        int savedBudget = currentStarBudget;
        boolean savedInTarget = inTargetExpression;
        scalarSubqueryDepth++;
        try {
            stackTargetTable.push(target);
            processSelect(select);
            stackTargetTable.pop();
        } finally {
            scalarSubqueryDepth--;
            currentOrdinal = savedOrdinal;
            uncertainOrdinal = savedUncertain;
            currentStarBudget = savedBudget;
            inTargetExpression = savedInTarget;
        }
        recordTargetShape(target);
        List<String> outputs = copyOutputs(targetOutputs.get(target));
        boolean widthKnown = Boolean.TRUE.equals(targetWidthKnown.get(target));
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
                    currentOrdinal);
        }

        String targetTable = stackTargetTable.peek();
        List<String> declaredColumns = currentTargetColumnAliases(targetTable);

        String lockedName = lockedOutputName(targetTable, currentOrdinal);
        String lockedKey = lockedOutputKey(targetTable, currentOrdinal);

        String targetColumn;
        boolean identifierTarget;
        if (lockedName != null) {
            targetColumn = lockedName;
            identifierTarget = true;
        } else if (declaredColumns != null && currentOrdinal >= 0
                && currentOrdinal < declaredColumns.size()) {
            targetColumn = declaredColumns.get(currentOrdinal);
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
                int width = emitDetachedStar(targetTable, currentSources(), currentOrdinal);
                return Math.max(width, 1);
            }
            Map<String, List<SourceTable>> using = stackUsing.isEmpty()
                    ? null : stackUsing.peek();
            SourceNode node = stackSourceNode.isEmpty() ? null : stackSourceNode.peek();
            if (context == null && node != null && !node.plan.isEmpty()
                    && (using != null && !using.isEmpty() || currentStarBudget > 0)) {
                recordBindingStar(targetTable, node, currentOrdinal);
            } else {
                recordStarOutputs(targetTable, currentSources(), currentOrdinal);
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
                        currentOrdinal);
                return Math.max(width, 1);
            }
            if (source != null) {
                SourceNode expanded = currentExpandedSourceNodes.get(source);
                SourceNode leafNode = stackSourceNode.isEmpty()
                        ? null : stackSourceNode.peek();
                if (expanded != null) {
                    recordBindingStar(targetTable, expanded, currentOrdinal);
                } else if (context == null && currentStarBudget > 0 && leafNode != null
                        && leafNode.sources.size() == 1
                        && leafNode.sources.get(0) == source
                        && !sourceColumnsKnown(source)) {
                    recordBindingStar(targetTable, leafNode, currentOrdinal);
                } else {
                    recordSourceStarOutputs(targetTable, source, currentOrdinal);
                }
            } else if (binding != null) {
                recordBindingStar(targetTable, binding, currentOrdinal);
            }
        } else {
            List<String> declaredKeys = currentDeclaredKeys(targetTable);
            String targetKey;
            if (lockedKey != null) {
                targetKey = lockedKey;
            } else if (declaredKeys != null && currentOrdinal < declaredKeys.size()) {
                targetKey = declaredKeys.get(currentOrdinal);
            } else if (item.getAlias() != null) {
                targetKey = identifierKey(item.getAlias());
            } else if (baseExpr instanceof SQLIdentifierExpr) {
                targetKey = identifierKey(((SQLIdentifierExpr) baseExpr).getName());
            } else if (baseExpr instanceof SQLPropertyExpr) {
                targetKey = identifierKey(((SQLPropertyExpr) baseExpr).getName());
            } else {
                targetKey = identifierKey(item.getExpr().toString());
            }
            setTargetOutput(targetTable, currentOrdinal, normalizeIdentifier(targetColumn),
                    targetKey);
            if (lockedName == null) {
                markOutputNameKnown(targetTable, currentOrdinal, true);
            }
        }

        stackTargetColumn.push(targetColumn);
        int before = instructions.size();
        inTargetExpression = true;
        visitExpr(item.getExpr());
        inTargetExpression = false;
        boolean uncertain = uncertainOrdinal >= 0 && currentOrdinal == uncertainOrdinal;
        if (instructions.size() == before && !uncertain) {
            instructions.add(new Instruction(OperationType.EXPR_MAPPING,
                    item.getExpr().toString(), targetTable + "." + targetColumn,
                    currentOrdinal, false, tempTables.contains(targetTable)));
        }
        stackTargetColumn.pop();

        return outputColumnCount(item.getExpr());
    }

    private int outputColumnCount(SQLExpr expr) {
        if (expr instanceof SQLAllColumnExpr) {
            Map<String, List<SourceTable>> using = stackUsing.isEmpty()
                    ? null : stackUsing.peek();
            if (!stackSourceNode.isEmpty()) {
                SourceNode node = stackSourceNode.peek();
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
            if (!stackSourceNode.isEmpty()) {
                SourceNode node = stackSourceNode.peek();
                return node.widthKnown ? node.width : node.minWidth;
            }
            Map<String, List<SourceTable>> using = stackUsing.isEmpty()
                    ? null : stackUsing.peek();
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
            if (!stackSourceNode.isEmpty()) {
                return stackSourceNode.peek().widthKnown;
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
                        && Boolean.TRUE.equals(targetWidthKnown.get(source.displayName));
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
            if (!stackSourceNode.isEmpty()) {
                return bindingNamesKnown(stackSourceNode.peek());
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

    private SourceNode buildSourceNode(SQLTableSource tableSource,
            Map<String, List<SourceTable>> using) {
        if (tableSource instanceof SQLJoinTableSource) {
            SQLJoinTableSource join = (SQLJoinTableSource) tableSource;
            Map<String, SourceNode> bindingsBefore = stackJoinBindings.isEmpty() ? null
                    : new HashMap<>(stackJoinBindings.peek());
            SourceNode left = buildSourceNode(join.getLeft(), using);
            boolean lateralRight = isLateralSource(join.getRight());
            if (lateralRight) {
                // A correlated LATERAL body can read the left side's columns,
                // bindings, and hidden-name set.
                stackSources.push(left.sources);
                lateralScopes.push(Boolean.TRUE);
                stackUsing.push(using);
                stackStarPlan.push(left.plan);
                stackSourceNode.push(left);
                stackSourceTable.push(left.sources.isEmpty()
                        ? "" : left.sources.get(0).displayName);
                stackHiddenSources.push(stackHiddenSources.isEmpty()
                        ? new HashSet<SourceTable>()
                        : new HashSet<>(stackHiddenSources.peek()));
                stackJoinBindings.push(stackJoinBindings.isEmpty()
                        ? new HashMap<String, SourceNode>()
                        : new HashMap<>(stackJoinBindings.peek()));
            }
            SourceNode right;
            try {
                right = buildSourceNode(join.getRight(), using);
            } finally {
                if (lateralRight) {
                    stackJoinBindings.pop();
                    stackHiddenSources.pop();
                    stackSourceTable.pop();
                    stackSourceNode.pop();
                    stackStarPlan.pop();
                    stackUsing.pop();
                    stackSources.pop();
                    lateralScopes.pop();
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
                        usingParticipantsByIdentity.computeIfAbsent(using,
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
                if (!stackJoinBindings.isEmpty()) {
                    Map<String, SourceNode> bindings = stackJoinBindings.peek();
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
                    tableSourceBindings.put(join, joined);
                }
                if (!stackHiddenSources.isEmpty()) {
                    // An alias hides every qualifier inside its join subtree.
                    stackHiddenSources.peek().addAll(sources);
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
            tempTables.add(target);
            processDerivedBody(target, body, lateral.getColumns());
            List<String> derivedOutputs = copyOutputs(targetOutputs.get(target));
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
            tempTables.add(target);
            processDerivedBody(target, subquery.getSelect(), subquery.getColumns());
            List<String> derivedOutputs = copyOutputs(targetOutputs.get(target));
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
            tempTables.add(target);
            processDerivedUnion(target, union.getUnion(), union.getColumns());
            List<String> derivedOutputs = copyOutputs(targetOutputs.get(target));
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
            stackTargetTable.push(target);
            processSelect(select);
            stackTargetTable.pop();
            recordTargetShape(target);
        } finally {
            targetColumnAliases.pop();
        }
    }

    private void processDerivedUnion(String target, SQLUnionQuery union,
            List<com.alibaba.druid.sql.ast.SQLName> columns) {
        pushDerivedAliases(target, columns);
        try {
            stackTargetTable.push(target);
            processSelectQuery(union);
            stackTargetTable.pop();
            recordTargetShape(target);
        } finally {
            targetColumnAliases.pop();
        }
    }

    private void recordTargetShape(String target) {
        boolean widthKnown = lastQueryBlockWidthKnown;
        boolean namesKnown = lastQueryBlockNamesKnown;
        int width = lastQueryBlockWidth;
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
                if (declared.size() < lastQueryBlockMinWidth) {
                    throw new IllegalStateException("Column alias count mismatch: "
                            + declared.size() + " vs at least "
                            + lastQueryBlockMinWidth);
                }
                widthKnown = true;
                namesKnown = true;
                width = declared.size();
                fillDeclaredOutputs(target, declared.size());
            }
        }
        targetWidthKnown.put(target, widthKnown);
        targetNamesKnown.put(target, namesKnown);
        targetMinWidth.put(target, widthKnown ? width : lastQueryBlockMinWidth);
    }

    private void fillDeclaredOutputs(String target, int width) {
        List<String> declared = currentTargetColumnAliases(target);
        if (declared == null) {
            return;
        }
        List<String> declaredKeys = currentDeclaredKeys(target);
        List<String> outputs = targetOutputs.get(target);
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
            cteDeclaredColumns.put(target, displays);
            cteDeclaredKeys.put(target, keys);
        }
        targetColumnAliases.push(aliases);
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
                : copyOutputs(targetOutputs.get(cteTarget));
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
        Integer minimum = target == null ? null : targetMinWidth.get(target);
        return minimum == null ? 0 : minimum;
    }

    private void recordWildcardSpan(String target, int start, int end, String source,
            boolean temporary) {
        if (target == null || start < 0) {
            return;
        }
        targetWildcardSpans.computeIfAbsent(target,
                key -> new ArrayList<WildcardSpan>())
                .add(new WildcardSpan(start, end, source, temporary));
    }

    private List<WildcardSpan> wildcardSpansOf(String target) {
        List<WildcardSpan> spans = targetWildcardSpans.get(target);
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

    private static final class WildcardRef {
        private final String name;
        private final boolean temporary;

        private WildcardRef(String name, boolean temporary) {
            this.name = name;
            this.temporary = temporary;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof WildcardRef)) {
                return false;
            }
            WildcardRef ref = (WildcardRef) other;
            return temporary == ref.temporary
                    && (name == null ? ref.name == null : name.equals(ref.name));
        }

        @Override
        public int hashCode() {
            return (name == null ? 0 : name.hashCode()) * 31 + (temporary ? 1 : 0);
        }
    }

    private static final class WildcardSpan {
        private final int start;
        private final int end;
        private final String source;
        private final boolean temporary;

        private WildcardSpan(int start, int end, String source, boolean temporary) {
            this.start = start;
            this.end = end;
            this.source = source;
            this.temporary = temporary;
        }

        @Override
        public String toString() {
            return "[" + start + "," + end + ")="
                    + (temporary ? "temp:" : "") + source;
        }
    }

    private boolean hasWildcardContribution(String target) {
        return target != null && Boolean.TRUE.equals(targetWildcardContribution.get(target));
    }

    private List<SourceTable> copyUnknownGroups(String target, int size) {
        List<SourceTable> groups = target == null ? null
                : targetOutputUnknownGroup.get(target);
        List<SourceTable> result = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            result.add(groups != null && i < groups.size() ? groups.get(i) : null);
        }
        return result;
    }

    private List<Boolean> copyNameKnown(String target, int size) {
        List<Boolean> flags = target == null ? null : targetOutputNameKnown.get(target);
        List<Boolean> result = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            result.add(flags != null && i < flags.size() && Boolean.TRUE.equals(flags.get(i)));
        }
        return result;
    }

    private boolean isOutputWidthKnown(String target, List<String> outputs) {
        return Boolean.TRUE.equals(targetWidthKnown.get(target));
    }

    private boolean isOutputNamesKnown(String target, List<String> outputs) {
        return !outputs.isEmpty() && Boolean.TRUE.equals(targetNamesKnown.get(target));
    }

    private static List<String> copyOutputs(List<String> outputs) {
        return outputs == null ? java.util.Collections.<String>emptyList()
                : new ArrayList<>(outputs);
    }

    private List<String> copyKeys(String target) {
        List<String> keys = target == null ? null : targetOutputKeys.get(target);
        return keys == null ? java.util.Collections.<String>emptyList()
                : new ArrayList<>(keys);
    }


    private TableLocation resolveMetadataTable(String written) {
        MetadataCatalog catalog = context.getCatalog();
        DbType dialect = currentDialect();
        String[] parts = MetadataCatalog.splitQualifiedName(written);
        boolean explicitInstance = parts.length >= 4;
        boolean explicitDatabase = dialect == DbType.mysql
                || dialect == DbType.mariadb
                        ? parts.length >= 2 : parts.length >= 3;
        boolean explicitSchema = hasExplicitSchema(written);
        boolean hiveFamily = dialect == DbType.hive
                || dialect == DbType.antspark;

        String instanceDefault = explicitInstance ? null
                : context.getInstance() != null ? context.getInstance()
                        : catalog.getDefaultInstance();
        String databaseDefault;
        if (explicitDatabase || hiveFamily) {
            databaseDefault = null;
        } else if (context.getDatabase() != null) {
            databaseDefault = context.getDatabase();
        } else {
            databaseDefault = catalog.getDefaultDatabase();
        }
        String schemaDefault;
        if (explicitSchema || !context.getSchemas().isEmpty()) {
            schemaDefault = null;
        } else if (hiveFamily) {
            if (context.getDatabase() != null) {
                schemaDefault = null;
            } else if (catalog.getDefaultSchema() != null) {
                schemaDefault = catalog.getDefaultSchema();
            } else {
                schemaDefault = catalog.getDefaultDatabase();
            }
        } else {
            schemaDefault = catalog.getDefaultSchema();
        }

        String[] preserved = MetadataCatalog
                .splitQualifiedNamePreservingQuotes(written);
        String schemaRef = null;
        if (preserved.length >= 3) {
            schemaRef = preserved[preserved.length - 2];
        } else if (preserved.length == 2 && !(dialect == DbType.mysql
                || dialect == DbType.mariadb)) {
            schemaRef = preserved[0];
        }
        String tableRef = preserved[preserved.length - 1];
        boolean pgPublic = dialect == DbType.postgresql && explicitSchema
                && isPostgresPublic(schemaRef);
        String lookupReference = written;
        String lookupSchemaDefault = schemaDefault;
        if (pgPublic) {
            lookupReference = tableRef;
            lookupSchemaDefault = null;
            if (preserved.length >= 4) {
                instanceDefault = MetadataCatalog.normalizeIdentifier(
                        preserved[preserved.length - 4]);
                databaseDefault = MetadataCatalog.normalizeIdentifier(
                        preserved[preserved.length - 3]);
            } else if (preserved.length == 3) {
                databaseDefault = MetadataCatalog.normalizeIdentifier(
                        preserved[0]);
            }
        }

        List<TableLocation> filtered = catalog.findAllTables(lookupReference,
                instanceDefault, databaseDefault, lookupSchemaDefault);

        if (explicitInstance && preserved.length >= 4) {
            List<TableLocation> exact = new ArrayList<>();
            for (TableLocation candidate : filtered) {
                if (schemaMatches(preserved[0], candidate.getInstance())) {
                    exact.add(candidate);
                }
            }
            filtered = exact;
        }
        if (explicitDatabase) {
            String databaseRef = null;
            if (preserved.length >= 4) {
                databaseRef = preserved[preserved.length - 3];
            } else if (preserved.length == 3) {
                databaseRef = preserved[0];
            } else if (preserved.length == 2
                    && (dialect == DbType.mysql
                            || dialect == DbType.mariadb)) {
                databaseRef = preserved[0];
            }
            if (databaseRef != null) {
                List<TableLocation> exact = new ArrayList<>();
                for (TableLocation candidate : filtered) {
                    if (schemaMatches(databaseRef, candidate.getDatabase())) {
                        exact.add(candidate);
                    }
                }
                filtered = exact;
            }
        }

        if (explicitSchema && schemaRef != null) {
            List<TableLocation> exact = new ArrayList<>();
            for (TableLocation candidate : filtered) {
                if (schemaMatches(schemaRef, candidate.getSchema())) {
                    exact.add(candidate);
                }
            }
            filtered = exact;
        }
        if (dialect == DbType.postgresql && explicitSchema) {
            List<TableLocation> exact = postgresTableMatches(filtered, tableRef);
            if (!exact.isEmpty() || MetadataCatalog.isQuotedIdentifier(tableRef)) {
                filtered = exact;
            }
        }

        if (!explicitSchema) {
            List<String> searchPath = !sessionRawSchemas.isEmpty()
                    ? sessionRawSchemas : context.getSchemas();
            if (!searchPath.isEmpty()) {
                for (String schema : searchPath) {
                    List<TableLocation> inSchema = new ArrayList<>();
                    for (TableLocation candidate : filtered) {
                        if (schemaMatches(schema, candidate.getSchema())) {
                            inSchema.add(candidate);
                        }
                    }
                    if (dialect == DbType.postgresql) {
                        List<TableLocation> chosen = postgresTableMatches(
                                inSchema, tableRef);
                        if (!chosen.isEmpty()) {
                            return chosen.get(0);
                        }
                    } else {
                        for (TableLocation candidate : inSchema) {
                            return candidate;
                        }
                    }
                }
                return missingTable(written);
            }
            if (hiveFamily && context.getDatabase() != null) {
                for (TableLocation candidate : filtered) {
                    if (schemaMatches(context.getDatabase(),
                            candidate.getSchema())
                            || schemaMatches(context.getDatabase(),
                                    candidate.getDatabase())) {
                        return candidate;
                    }
                }
                return missingTable(written);
            }
        }

        if (dialect == DbType.postgresql && !explicitSchema) {
            List<TableLocation> exact = postgresTableMatches(filtered, tableRef);
            if (!exact.isEmpty() || MetadataCatalog.isQuotedIdentifier(tableRef)) {
                filtered = exact;
            }
        }
        if (filtered.isEmpty()) {
            return missingTable(written);
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

    private static boolean isPostgresPublic(String schemaRef) {
        if (schemaRef == null) {
            return false;
        }
        String name = MetadataCatalog.normalizeIdentifier(schemaRef);
        return MetadataCatalog.isQuotedIdentifier(schemaRef)
                ? "public".equals(name)
                : "public".equals(name.toLowerCase(Locale.ROOT));
    }

    private static List<TableLocation> postgresTableMatches(
            List<TableLocation> candidates, String tableRef) {
        String tableName = MetadataCatalog.normalizeIdentifier(tableRef);
        boolean quoted = MetadataCatalog.isQuotedIdentifier(tableRef);
        List<TableLocation> folded = new ArrayList<>();
        for (TableLocation candidate : candidates) {
            String actual = candidate.getTable().getName();
            if (quoted ? tableName.equals(actual)
                    : tableName.toLowerCase(Locale.ROOT).equals(actual)) {
                folded.add(candidate);
            }
        }
        if (!folded.isEmpty() || quoted) {
            return folded;
        }
        List<TableLocation> lenient = new ArrayList<>();
        for (TableLocation candidate : candidates) {
            if (!candidate.getTable().isQuotedName()
                    && tableName.equalsIgnoreCase(
                            candidate.getTable().getName())) {
                lenient.add(candidate);
            }
        }
        return lenient;
    }

    private boolean schemaMatches(String reference, String actual) {
        if (reference == null) {
            return false;
        }
        DbType dialect = currentDialect();
        if (actual == null) {
            // An unqualified catalog table lives in the default schema.
            if (dialect != DbType.postgresql) {
                return false;
            }
            boolean quotedDefault = MetadataCatalog.isQuotedIdentifier(reference);
            String defaultName = MetadataCatalog.normalizeIdentifier(reference);
            return quotedDefault ? "public".equals(defaultName)
                    : "public".equals(
                            defaultName.toLowerCase(Locale.ROOT));
        }
        boolean quoted = MetadataCatalog.isQuotedIdentifier(reference);
        String name = MetadataCatalog.normalizeIdentifier(reference);
        if (dialect == DbType.postgresql) {
            return quoted ? name.equals(actual)
                    : name.toLowerCase(Locale.ROOT).equals(actual);
        }
        if (dialect == DbType.mysql || dialect == DbType.mariadb
                || dialect == DbType.hive || dialect == DbType.antspark
                || dialect == DbType.sqlserver) {
            return foldCase(name).equals(foldCase(actual));
        }
        return quoted ? name.equals(actual) : name.equalsIgnoreCase(actual);
    }

    /**
     * A table that is absent from the catalog is an error in strict mode; in optional mode
     * it is analyzed without column information.
     */
    private TableLocation missingTable(String written) {
        if (detachedMetadata()) {
            return null;
        }
        throw new MetadataNotFoundException(written);
    }

    private static boolean isSingleSegmentName(String rawName) {
        return MetadataCatalog.splitQualifiedNamePreservingQuotes(rawName).length == 1;
    }

    private String canonicalIdentifier(String rawName) {
        if (rawName == null) {
            return null;
        }
        boolean quoted = MetadataCatalog.isQuotedIdentifier(rawName);
        String name = normalizeIdentifier(rawName);
        DbType dialect = currentDialect();
        if (dialect == DbType.mysql || dialect == DbType.mariadb) {
            return name;
        }
        if (dialect == DbType.postgresql) {
            return quoted ? name : name.toLowerCase();
        }
        return quoted ? name : name.toUpperCase();
    }

    private String identifierKey(String rawName) {
        if (rawName == null) {
            return null;
        }
        boolean quoted = MetadataCatalog.isQuotedIdentifier(rawName);
        String name = normalizeIdentifier(rawName);
        DbType dialect = currentDialect();
        if (dialect == DbType.mysql || dialect == DbType.mariadb) {
            return name.toLowerCase(Locale.ROOT);
        }
        if (dialect == DbType.postgresql) {
            return quoted ? name : name.toLowerCase(Locale.ROOT);
        }
        if (dialect == DbType.hive || dialect == DbType.antspark) {
            // Hive/Spark identifiers are case-insensitive even when quoted.
            return foldCase(name);
        }
        return quoted ? name : name.toUpperCase(Locale.ROOT);
    }

    private Map<String, List<String>> namedTargetOutputs() {
        Map<String, List<String>> named = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : targetOutputs.entrySet()) {
            if (!intermediateTargets.contains(entry.getKey())) {
                named.put(entry.getKey(), copyOutputs(entry.getValue()));
            }
        }
        return named;
    }

    private String lockedOutputName(String target, int position) {
        if (!unionTargets.contains(target)) {
            return null;
        }
        List<String> outputs = targetOutputs.get(target);
        return outputs != null && position < outputs.size() ? outputs.get(position) : null;
    }

    private String lockedOutputKey(String target, int position) {
        if (!unionTargets.contains(target)) {
            return null;
        }
        List<String> keys = targetOutputKeys.get(target);
        return keys != null && position < keys.size() ? keys.get(position) : null;
    }

    private void incrementNonAuthoritative(String target) {
        Integer depth = nonAuthoritativeDepth.get(target);
        nonAuthoritativeDepth.put(target, depth == null ? 1 : depth + 1);
    }

    private void decrementNonAuthoritative(String target) {
        Integer depth = nonAuthoritativeDepth.get(target);
        if (depth == null || depth <= 1) {
            nonAuthoritativeDepth.remove(target);
        } else {
            nonAuthoritativeDepth.put(target, depth - 1);
        }
    }

    private void markUnknownGroup(String target, int position, SourceTable source) {
        if (target == null || source == null || position < 0) {
            return;
        }
        List<SourceTable> groups = targetOutputUnknownGroup.computeIfAbsent(target,
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
        Integer depth = nonAuthoritativeDepth.get(target);
        if (depth != null && depth > 0) {
            return;
        }
        List<Boolean> flags = targetOutputNameKnown.computeIfAbsent(target,
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
        List<String> outputs = targetOutputs.computeIfAbsent(target,
                k -> new ArrayList<String>());
        List<String> keys = targetOutputKeys.computeIfAbsent(target,
                k -> new ArrayList<String>());
        while (outputs.size() <= position) {
            outputs.add(null);
            keys.add(null);
        }
        outputs.set(position, name);
        keys.set(position, key);
    }

    private void recordStarOutputs(String target, List<SourceTable> sources, int startPosition) {
        Map<String, List<SourceTable>> using = stackUsing.isEmpty()
                ? null : stackUsing.peek();
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
                    if (isExistingOutput(targetOutputs.get(target), name, position)) {
                        int suffix = position + 1;
                        do {
                            name = "col" + suffix++;
                            key = identifierKey(name);
                        } while (isExistingOutput(targetOutputs.get(target), name, position));
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
        List<String> existing = targetOutputs.get(target);
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
        for (int i = targetColumnAliases.size() - 1; i >= 0; i--) {
            if (targetColumnAliases.get(i).containsKey(target)) {
                List<String> keys = cteDeclaredKeys.get(target);
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
        if (!stackStarPlan.isEmpty() && !stackStarPlan.peek().isEmpty()) {
            return stackStarPlan.peek();
        }
        List<StarOutput> plan = new ArrayList<>();
        for (SourceTable source : sources) {
            plan.addAll(planFromSource(source));
        }
        return plan;
    }



    private List<String> currentTargetColumnAliases(String target) {
        for (int i = targetColumnAliases.size() - 1; i >= 0; i--) {
            Map<String, List<String>> aliases = targetColumnAliases.get(i);
            if (aliases.containsKey(target)) {
                return aliases.get(target);
            }
        }
        return null;
    }

    private String findCteTarget(String key) {
        for (int i = cteScopes.size() - 1; i >= 0; i--) {
            String target = cteScopes.get(i).get(key);
            if (target != null) {
                return target;
            }
        }
        return null;
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

    private void registerTable(String written, String mappingName, String display, String alias,
            boolean cteReference) {
        if (cteReference || tables.contains(display)) {
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

    /** A FROM item is a table only when its expression is a relation name. */
    private static boolean isPhysicalTableExpression(SQLExprTableSource table) {
        SQLExpr expr = table.getExpr();
        return expr == null || expr instanceof SQLName;
    }

    /** The implicit visible name of a table function in FROM. */
    private static String implicitRelationName(SQLExprTableSource table) {
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

    /** A local short name that matches more than one table in the same frame. */
    private static final String LOCAL_AMBIGUOUS = "\u0000local-ambiguous";
    /** Separates the tables an alias/join binding may come from. */
    private static final String LOCAL_TABLE_SEPARATOR = "\u0002";
    /** Marks a name that resolves through a JOIN binding's visible plan. */
    private static final String LOCAL_BINDING = "\u0000binding";
    /** Join alias whose USING keys attribute to the listed sides. */
    private static final String LOCAL_BINDING_USING = "\u0000binding-using:";
    private int learningBindingSeq;
    private boolean ansiEnabled;
    private boolean ansiDoubleQuotedIdentifiers;
    private String statementSql;
    private static final String JDBC_ESCAPE_ATTRIBUTE =
            "com.laotie.jdbcEscape";

    private static final class JdbcEscape {
        private final String signature;

        private JdbcEscape(String signature) {
            this.signature = signature;
        }
    }

    private static String jdbcSignature(SQLMethodInvokeExpr call) {
        StringBuilder signature = new StringBuilder(call.getMethodName());
        signature.append('|').append(call.getArguments().size());
        for (SQLExpr argument : call.getArguments()) {
            signature.append('|')
                    .append(argument.getClass().getSimpleName())
                    .append(':').append(argument);
        }
        return signature.toString();
    }
    private static final String JDBC_MARKER = "LO_JDBC_";
    private static final String ANSI_IDENTIFIER_ATTRIBUTE =
            "com.laotie.ansiQuotedIdentifier";

    private static String encodeLocalPart(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder encoded = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '%' || ch == '=' || ch == ';'
                    || ch == LOCAL_TABLE_SEPARATOR.charAt(0)) {
                encoded.append('%').append(String.format("%02X", (int) ch));
            } else {
                encoded.append(ch);
            }
        }
        return encoded.toString();
    }

    private static String decodeLocalPart(String value) {
        StringBuilder decoded = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '%' && i + 2 < value.length()) {
                try {
                    decoded.append((char) Integer.parseInt(
                            value.substring(i + 1, i + 3), 16));
                    i += 2;
                    continue;
                } catch (NumberFormatException ignored) {
                    // Keep the literal percent.
                }
            }
            decoded.append(ch);
        }
        return decoded.toString();
    }

    private void learnExplicitReferences(SQLExpr expr) {
        learnExplicitReferences(expr, new HashMap<String, String>(),
                new HashSet<String>());
    }

    private void learnExplicitReferences(SQLExpr expr, Map<String, String> locals) {
        learnExplicitReferences(expr, locals, new HashSet<String>());
    }

    private void learnExplicitReferences(SQLExpr expr, Map<String, String> locals,
            Set<String> cteKeys) {
        if (expr == null) {
            return;
        }
        if (expr instanceof SQLPropertyExpr) {
            SQLPropertyExpr property = (SQLPropertyExpr) expr;
            if (!isStarProperty(property)) {
                String rawName = property.getName();
                String ownerKey = property.getOwner() == null ? null
                        : qualifiedKey(property.getOwner().toString());
                if (ownerKey != null && locals.containsKey(ownerKey)) {
                    String learnedTable = locals.get(ownerKey);
                    if (learnedTable != null
                            && learnedTable.startsWith(LOCAL_BINDING_USING)) {
                        learnThroughUsingBinding(learnedTable, rawName);
                    } else if (learnedTable != null
                            && learnedTable.startsWith(LOCAL_BINDING + "#")) {
                        learnThroughBinding(learnedTable, rawName);
                    } else if (LOCAL_BINDING.equals(learnedTable)) {
                        // A syntactically declared alias whose plan is not built:
                        // nothing can be attributed safely.
                    } else if (learnedTable != null
                            && !LOCAL_AMBIGUOUS.equals(learnedTable)) {
                        pendingLearned.computeIfAbsent(learnedTable,
                                k -> new LinkedHashSet<String>())
                                .add(identifierKey(rawName));
                    }
                    return;
                }
                learnExplicitReference(property);
            }
            return;
        }
        if (expr instanceof SQLQueryExpr) {
            learnSelectExplicitReferences(((SQLQueryExpr) expr).getSubQuery(),
                    locals, cteKeys);
        } else if (expr instanceof SQLInSubQueryExpr) {
            SQLInSubQueryExpr in = (SQLInSubQueryExpr) expr;
            learnExplicitReferences(in.getExpr(), locals, cteKeys);
            learnSelectExplicitReferences(in.getSubQuery(), locals, cteKeys);
        } else if (expr instanceof SQLBinaryOpExpr) {
            learnExplicitReferences(((SQLBinaryOpExpr) expr).getLeft(), locals,
                    cteKeys);
            learnExplicitReferences(((SQLBinaryOpExpr) expr).getRight(), locals,
                    cteKeys);
        } else if (expr instanceof SQLMethodInvokeExpr) {
            for (SQLExpr argument : ((SQLMethodInvokeExpr) expr).getArguments()) {
                learnExplicitReferences(argument, locals, cteKeys);
            }
        } else if (expr instanceof SQLCastExpr) {
            learnExplicitReferences(((SQLCastExpr) expr).getExpr(), locals, cteKeys);
        } else if (expr instanceof SQLNotExpr) {
            learnExplicitReferences(((SQLNotExpr) expr).getExpr(), locals, cteKeys);
        } else if (expr instanceof SQLUnaryExpr) {
            learnExplicitReferences(((SQLUnaryExpr) expr).getExpr(), locals, cteKeys);
        } else if (expr instanceof SQLBetweenExpr) {
            SQLBetweenExpr between = (SQLBetweenExpr) expr;
            learnExplicitReferences(between.getTestExpr(), locals, cteKeys);
            learnExplicitReferences(between.getBeginExpr(), locals, cteKeys);
            learnExplicitReferences(between.getEndExpr(), locals, cteKeys);
        } else if (expr instanceof SQLCaseExpr) {
            SQLCaseExpr caseExpr = (SQLCaseExpr) expr;
            learnExplicitReferences(caseExpr.getValueExpr(), locals, cteKeys);
            if (caseExpr.getItems() != null) {
                for (SQLCaseExpr.Item caseItem : caseExpr.getItems()) {
                    learnExplicitReferences(caseItem.getConditionExpr(), locals,
                            cteKeys);
                    learnExplicitReferences(caseItem.getValueExpr(), locals, cteKeys);
                }
            }
            learnExplicitReferences(caseExpr.getElseExpr(), locals, cteKeys);
        } else if (expr instanceof SQLListExpr) {
            for (SQLExpr element : ((SQLListExpr) expr).getItems()) {
                learnExplicitReferences(element, locals, cteKeys);
            }
        } else if (expr instanceof SQLInListExpr) {
            SQLInListExpr in = (SQLInListExpr) expr;
            learnExplicitReferences(in.getExpr(), locals, cteKeys);
            if (in.getTargetList() != null) {
                for (SQLExpr element : in.getTargetList()) {
                    learnExplicitReferences(element, locals, cteKeys);
                }
            }
        } else if (expr instanceof SQLExtractExpr) {
            learnExplicitReferences(((SQLExtractExpr) expr).getValue(), locals,
                    cteKeys);
        }
    }

    private void learnThroughUsingBinding(String marker, String rawName) {
        String columnKey = identifierKey(rawName);
        String payload = marker.substring(LOCAL_BINDING_USING.length());
        for (String entry : payload.split(";")) {
            int eq = entry.indexOf('=');
            if (eq <= 0
                    || !decodeLocalPart(entry.substring(0, eq)).equals(columnKey)) {
                continue;
            }
            String tables = entry.substring(eq + 1);
            if (tables.isEmpty()) {
                return;
            }
            // Split while still encoded: a separator inside a quoted name is %02.
            for (String encoded : tables.split(LOCAL_TABLE_SEPARATOR)) {
                String table = decodeLocalPart(encoded);
                if (!table.isEmpty()) {
                    pendingLearned.computeIfAbsent(table,
                            k -> new LinkedHashSet<String>())
                            .add(columnKey);
                }
            }
            return;
        }
    }

    private void learnThroughBinding(String marker, String rawName) {
        Object resolved = learningBindings.get(marker);
        if (!(resolved instanceof SourceNode)) {
            return;
        }
        SourceNode binding = (SourceNode) resolved;
        String columnKey = identifierKey(rawName);
        List<StarOutput> matches = new ArrayList<>();
        for (StarOutput output : binding.plan) {
            for (StarColumn column : output.sources) {
                if (columnKey != null && columnKey.equals(column.key)) {
                    matches.add(output);
                    break;
                }
            }
        }
        if (matches.size() != 1) {
            // Several visible outputs (or none): no single table may be learned.
            return;
        }
        for (StarColumn column : matches.get(0).sources) {
            learnColumn(column.source,
                    column.quoted ? quoteForLearning(column.column) : column.column,
                    true);
        }
    }

    private String quoteForLearning(String name) {
        DbType dialect = currentDialect();
        if (dialect == DbType.mysql || dialect == DbType.mariadb) {
            return "`" + name.replace("`", "``") + "`";
        }
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    private void learnExplicitReference(SQLPropertyExpr property) {
        // The caller resolves owners against local or inherited bindings only; an
        // unbound name is never guessed to be a physical table.
    }

    private void learnSelectExplicitReferences(SQLSelect select,
            Map<String, String> inheritedLocals) {
        learnSelectExplicitReferences(select, inheritedLocals, new HashSet<String>());
    }

    private void learnSelectExplicitReferences(SQLSelect select,
            Map<String, String> inheritedLocals, Set<String> inheritedCtes) {
        if (select == null) {
            return;
        }
        Map<String, String> locals = new HashMap<>(inheritedLocals);
        Set<String> ctes = new HashSet<>(inheritedCtes);
        SQLWithSubqueryClause with = select.getWithSubQuery();
        if (with != null) {
            boolean recursive = Boolean.TRUE.equals(with.getRecursive());
            if (recursive) {
                for (SQLWithSubqueryClause.Entry entry : with.getEntries()) {
                    if (entry.getAlias() != null) {
                        ctes.add(qualifiedKey(entry.getAlias()));
                    }
                }
            }
            for (SQLWithSubqueryClause.Entry entry : with.getEntries()) {
                // A non-recursive body still sees a physical table of the same name.
                learnSelectExplicitReferences(entry.getSubQuery(), locals, ctes);
                if (!recursive && entry.getAlias() != null) {
                    ctes.add(qualifiedKey(entry.getAlias()));
                }
            }
        }
        learnQueryExplicitReferences(select.getQuery(), locals, ctes);
    }

    private void learnSelectExplicitReferences(SQLSelectQuery query,
            Map<String, String> locals, Set<String> ctes) {
        learnQueryExplicitReferences(query, locals, ctes);
    }

    private void collectFromNames(SQLSelectQuery query, Set<String> names) {
        if (query instanceof SQLSelectQueryBlock) {
            collectFromNames(((SQLSelectQueryBlock) query).getFrom(), names);
        } else if (query instanceof SQLUnionQuery) {
            SQLUnionQuery union = (SQLUnionQuery) query;
            collectFromNames(union.getLeft(), names);
            collectFromNames(union.getRight(), names);
        }
    }

    private void collectFromNames(SQLTableSource from, Set<String> names) {
        if (from == null) {
            return;
        }
        if (from instanceof SQLJoinTableSource) {
            SQLJoinTableSource join = (SQLJoinTableSource) from;
            collectFromNames(join.getLeft(), names);
            collectFromNames(join.getRight(), names);
        } else if (from instanceof SQLExprTableSource) {
            SQLExprTableSource table = (SQLExprTableSource) from;
            String name = extractTableName(table);
            if (table.getAlias() != null) {
                names.add(qualifiedKey(table.getAlias()));
            } else if (name != null) {
                names.add(qualifiedKey(name));
                if (!isSingleSegmentName(name)) {
                    names.add(qualifiedKey(lastSegment(name)));
                }
            }
        } else if (from instanceof SQLSubqueryTableSource) {
            SQLSubqueryTableSource subquery = (SQLSubqueryTableSource) from;
            if (subquery.getAlias() != null) {
                names.add(qualifiedKey(subquery.getAlias()));
            }
        } else if (from instanceof SQLUnionQueryTableSource) {
            SQLUnionQueryTableSource union = (SQLUnionQueryTableSource) from;
            if (union.getAlias() != null) {
                names.add(qualifiedKey(union.getAlias()));
            }
        }
    }

    private void learnQueryExplicitReferences(SQLSelectQuery query,
            Map<String, String> inheritedLocals, Set<String> ctes) {
        if (query instanceof SQLSelectQueryBlock) {
            SQLSelectQueryBlock block = (SQLSelectQueryBlock) query;
            Map<String, String> locals = new HashMap<>(inheritedLocals);
            Map<String, String> frameLocals = new HashMap<>();
            collectLocalNames(block.getFrom(), frameLocals, ctes);
            // A local table shadows an inherited short name.
            locals.putAll(frameLocals);
            Map<String, Object> savedBindings = new HashMap<>(learningBindings);
            for (Map.Entry<String, String> local : frameLocals.entrySet()) {
                if (LOCAL_BINDING.equals(local.getValue())) {
                    // A same-named local join alias has its own plan.
                    learningBindings.put(local.getKey(), null);
                }
            }
            try {
                // A non-LATERAL derived body and a JOIN ON see the enclosing scope,
                // not the other sibling sources of this FROM.
                learnDerivedBodies(block.getFrom(), inheritedLocals, ctes);
                Set<String> fromNames = new HashSet<>();
                collectFromNames(block.getFrom(), fromNames);
                learnJoinConditions(block.getFrom(), inheritedLocals, ctes, fromNames);
                for (SQLSelectItem item : block.getSelectList()) {
                    learnExplicitReferences(item.getExpr(), locals, ctes);
                }
                learnExplicitReferences(block.getWhere(), locals, ctes);
            } finally {
                learningBindings = savedBindings;
            }
        } else if (query instanceof SQLUnionQuery) {
            SQLUnionQuery union = (SQLUnionQuery) query;
            // Each branch has its own FROM aliases; one branch must not shadow the
            // aliases of another.
            learnQueryExplicitReferences(union.getLeft(),
                    new HashMap<>(inheritedLocals), ctes);
            learnQueryExplicitReferences(union.getRight(),
                    new HashMap<>(inheritedLocals), ctes);
        }
    }

    private void learnDerivedBodies(SQLTableSource from,
            Map<String, String> inheritedLocals, Set<String> ctes) {
        if (from == null) {
            return;
        }
        if (from instanceof SQLJoinTableSource) {
            SQLJoinTableSource join = (SQLJoinTableSource) from;
            learnDerivedBodies(join.getLeft(), inheritedLocals, ctes);
            learnDerivedBodies(join.getRight(), inheritedLocals, ctes);
        } else if (from instanceof SQLSubqueryTableSource) {
            learnSelectExplicitReferences(((SQLSubqueryTableSource) from).getSelect(),
                    inheritedLocals, ctes);
        } else if (from instanceof SQLUnionQueryTableSource) {
            learnSelectExplicitReferences(((SQLUnionQueryTableSource) from).getUnion(),
                    inheritedLocals, ctes);
        }
    }

    private Set<String> currentCteKeys() {
        Set<String> ctes = new HashSet<>();
        for (Map<String, String> frame : cteScopes) {
            ctes.addAll(frame.keySet());
        }
        return ctes;
    }

    private Map<String, String> outerBlockLocals(boolean includeCurrentFrame) {
        Map<String, String> locals = new HashMap<>();
        int top = includeCurrentFrame ? stackSources.size() - 1
                : stackSources.size() - 2;
        // Far frames first, so a nearer binding overrides an outer one.
        for (int frame = 0; frame <= top; frame++) {
            Map<String, String> frameLocals = new HashMap<>();
            Set<SourceTable> hidden = frame < stackHiddenSources.size()
                    ? stackHiddenSources.get(frame)
                    : java.util.Collections.<SourceTable>emptySet();
            for (SourceTable source : stackSources.get(frame)) {
                if (hidden.contains(source)) {
                    continue;
                }
                String learnedTable = source.temporary || !source.learnable
                        || source.rawName == null
                        ? null : learnedTableKey(source.rawName);
                if (source.alias != null) {
                    // An alias hides the original table name.
                    putLocal(frameLocals, qualifiedKey(source.alias), learnedTable);
                    continue;
                }
                for (String reference : new String[] {source.displayName,
                        source.rawName, source.implicitName}) {
                    if (reference != null) {
                        putLocal(frameLocals, qualifiedKey(reference), learnedTable);
                    }
                }
                if (source.rawName != null && !isSingleSegmentName(source.rawName)) {
                    putLocal(frameLocals, qualifiedKey(lastSegment(source.rawName)),
                            learnedTable);
                }
            }
            if (frame < stackJoinBindings.size()) {
                for (Map.Entry<String, SourceNode> binding
                        : stackJoinBindings.get(frame).entrySet()) {
                    putLocal(frameLocals, binding.getKey(),
                            bindingToken(binding.getValue()));
                }
            }
            locals.putAll(frameLocals);
        }
        return locals;
    }

    private String bindingToken(SourceNode binding) {
        String token = LOCAL_BINDING + "#" + (++learningBindingSeq);
        learningBindings.put(token, binding);
        return token;
    }

    private static void putLocal(Map<String, String> locals, String key,
            String learnedTable) {
        if (key == null) {
            return;
        }
        if (locals.containsKey(key)
                && !String.valueOf(learnedTable).equals(
                        String.valueOf(locals.get(key)))) {
            locals.put(key, LOCAL_AMBIGUOUS);
            return;
        }
        locals.put(key, learnedTable);
    }

    private void learnNoFromExplicitReferences(SQLSelectQueryBlock block,
            Map<String, String> inherited) {
        Map<String, Object> savedBindings = new HashMap<>(learningBindings);
        if (!stackJoinBindings.isEmpty()) {
            learningBindings.putAll(stackJoinBindings.peek());
        }
        try {
            Set<String> ctes = currentCteKeys();
            for (SQLSelectItem item : block.getSelectList()) {
                learnExplicitReferences(item.getExpr(), inherited, ctes);
            }
            learnExplicitReferences(block.getWhere(), inherited, ctes);
        } finally {
            learningBindings = savedBindings;
        }
    }

    private void learnBlockExplicitReferences(SQLSelectQueryBlock block,
            Map<String, String> inherited) {
        Map<String, Object> savedBindings = new HashMap<>(learningBindings);
        if (!stackJoinBindings.isEmpty()) {
            learningBindings.putAll(stackJoinBindings.peek());
        }
        try {
            learnBlockExplicitReferencesInScope(block, inherited);
        } finally {
            learningBindings = savedBindings;
        }
    }

    /**
     * Add a side's table only when the side is exactly one physical FROM instance.
     */
    private void addSideInstances(Set<String> sides, SQLTableSource side,
            Set<String> ctes) {
        List<String> instances = sourceInstances(side, ctes);
        if (instances != null && instances.size() == 1) {
            sides.add(instances.get(0));
        }
    }

    private List<String> sourceInstances(SQLTableSource from, Set<String> ctes) {
        if (from == null) {
            return null;
        }
        if (from instanceof SQLJoinTableSource) {
            SQLJoinTableSource join = (SQLJoinTableSource) from;
            List<String> left = sourceInstances(join.getLeft(), ctes);
            List<String> right = sourceInstances(join.getRight(), ctes);
            if (left == null || right == null) {
                return null;
            }
            List<String> instances = new ArrayList<>(left);
            instances.addAll(right);
            return instances;
        }
        if (from instanceof SQLExprTableSource) {
            SQLExprTableSource table = (SQLExprTableSource) from;
            if (!isPhysicalTableExpression(table)) {
                return null;
            }
            String name = extractTableName(table);
            if (name == null) {
                return null;
            }
            if (isSingleSegmentName(name)
                    && ctes.contains(qualifiedKey(name))) {
                // A CTE is not a physical table instance.
                return null;
            }
            return new ArrayList<>(java.util.Collections.singletonList(
                    learnedTableKey(name)));
        }
        return null;
    }

    private String usingBindingMark(SQLJoinTableSource join,
            Map<String, String> childLocals, Set<String> ctes) {
        if (join.getUsing() == null || join.getUsing().isEmpty()) {
            return LOCAL_BINDING;
        }
        Map<String, String> leftLocals = new HashMap<>();
        Map<String, String> rightLocals = new HashMap<>();
        collectLocalNames(join.getLeft(), leftLocals, ctes);
        collectLocalNames(join.getRight(), rightLocals, ctes);
        StringBuilder mark = new StringBuilder(LOCAL_BINDING_USING);
        for (SQLExpr expr : join.getUsing()) {
            String key = identifierKey(expr.toString());
            Set<String> sides = new LinkedHashSet<>();
            switch (join.getJoinType()) {
                case LEFT_OUTER_JOIN:
                case LEFT_SEMI_JOIN:
                case LEFT_ANTI_JOIN:
                    addSideInstances(sides, join.getLeft(), ctes);
                    break;
                case RIGHT_OUTER_JOIN:
                    addSideInstances(sides, join.getRight(), ctes);
                    break;
                default:
                    addSideInstances(sides, join.getLeft(), ctes);
                    addSideInstances(sides, join.getRight(), ctes);
                    break;
            }
            if (sides.isEmpty()) {
                continue;
            }
            if (mark.length() > LOCAL_BINDING_USING.length()) {
                mark.append(';');
            }
            List<String> encodedSides = new ArrayList<>();
            for (String side : sides) {
                encodedSides.add(encodeLocalPart(side));
            }
            mark.append(encodeLocalPart(key)).append('=')
                    .append(String.join(LOCAL_TABLE_SEPARATOR, encodedSides));
        }
        return mark.toString();
    }

    private void learnBlockExplicitReferencesInScope(SQLSelectQueryBlock block,
            Map<String, String> inherited) {
        Set<String> ctes = currentCteKeys();
        Map<String, String> frameLocals = new HashMap<>();
        collectLocalNames(block.getFrom(), frameLocals, ctes);
        upgradeBindings(block.getFrom(), frameLocals);
        Set<String> fromNames = new HashSet<>();
        collectFromNames(block.getFrom(), fromNames);
        learnDerivedBodies(block.getFrom(), inherited, ctes);
        learnJoinConditions(block.getFrom(), inherited, ctes, fromNames);
        Map<String, String> locals = new HashMap<>(inherited);
        locals.putAll(frameLocals);
        for (SQLSelectItem item : block.getSelectList()) {
            learnExplicitReferences(item.getExpr(), locals, ctes);
        }
        learnExplicitReferences(block.getWhere(), locals, ctes);
    }

    private void upgradeBindings(SQLTableSource from, Map<String, String> locals) {
        if (from == null) {
            return;
        }
        if (from instanceof SQLJoinTableSource) {
            SQLJoinTableSource join = (SQLJoinTableSource) from;
            if (join.getAlias() != null) {
                String key = qualifiedKey(join.getAlias());
                String value = locals.get(key);
                SourceNode binding = tableSourceBindings.get(join);
                if (binding != null && value != null
                        && (LOCAL_BINDING.equals(value)
                                || value.startsWith(LOCAL_BINDING_USING))) {
                    locals.put(key, bindingToken(binding));
                }
            }
            if (join.getAlias() == null) {
                // A join alias hides every qualifier inside its subtree, so those
                // child aliases must not take this scope's names.
                upgradeBindings(join.getLeft(), locals);
                upgradeBindings(join.getRight(), locals);
            }
        }
    }

    private void learnJoinConditions(SQLTableSource from, Map<String, String> locals,
            Set<String> ctes, Set<String> fromNames) {
        if (from instanceof SQLJoinTableSource) {
            SQLJoinTableSource join = (SQLJoinTableSource) from;
            Map<String, String> subtree = new HashMap<>();
            collectLocalNames(join.getLeft(), subtree, ctes);
            collectLocalNames(join.getRight(), subtree, ctes);
            upgradeBindings(join.getLeft(), subtree);
            upgradeBindings(join.getRight(), subtree);
            Map<String, String> conditionLocals = new HashMap<>(locals);
            conditionLocals.putAll(subtree);
            for (String name : fromNames) {
                if (!subtree.containsKey(name) && !locals.containsKey(name)) {
                    // A source outside this join's subtree is not visible in ON.
                    conditionLocals.put(name, LOCAL_AMBIGUOUS);
                }
            }
            // A child join's ON cannot see the parent join's other side.
            learnJoinConditions(join.getLeft(), locals, ctes, fromNames);
            learnJoinConditions(join.getRight(), locals, ctes, fromNames);
            if (join.getCondition() != null) {
                // An ON condition sees the joined subtree's aliases even when the
                // whole join is aliased for the outer query.
                learnExplicitReferences(join.getCondition(), conditionLocals, ctes);
            }
        }
    }

    private void collectLocalNames(SQLTableSource from, Map<String, String> locals,
            Set<String> ctes) {
        if (from == null) {
            return;
        }
        if (from instanceof SQLJoinTableSource) {
            SQLJoinTableSource join = (SQLJoinTableSource) from;
            Map<String, String> childLocals = join.getAlias() != null
                    ? new HashMap<String, String>() : locals;
            collectLocalNames(join.getLeft(), childLocals, ctes);
            collectLocalNames(join.getRight(), childLocals, ctes);
            if (join.getAlias() != null) {
                // An alias hides every qualifier inside the joined subtree. A USING
                // key is attributable to its sides; other columns need the plan.
                locals.put(qualifiedKey(join.getAlias()),
                        usingBindingMark(join, childLocals, ctes));
            }
        } else if (from instanceof SQLExprTableSource) {
            SQLExprTableSource table = (SQLExprTableSource) from;
            boolean physical = isPhysicalTableExpression(table);
            String name = physical ? extractTableName(table)
                    : implicitRelationName(table);
            String reference = table.getAlias() != null
                    ? table.getAlias() : name;
            if (reference != null && !physical) {
                // A table function exposes its call name but owns no physical columns;
                // it still has to shadow an outer alias of the same name.
                putLocal(locals, qualifiedKey(reference), null);
                return;
            }
            if (reference != null) {
                String referenceKey = qualifiedKey(reference);
                String nameKey = name == null ? null : qualifiedKey(name);
                if (ctes.contains(nameKey)) {
                    // A CTE reference, possibly through an alias.
                    locals.put(referenceKey, null);
                } else {
                    String learnedTable = name == null ? null : learnedTableKey(name);
                    if (locals.containsKey(referenceKey)
                            && !String.valueOf(learnedTable).equals(
                                    String.valueOf(locals.get(referenceKey)))) {
                        locals.put(referenceKey, LOCAL_AMBIGUOUS);
                        return;
                    }
                    locals.put(referenceKey, learnedTable);
                    if (name != null && !isSingleSegmentName(name)
                            && table.getAlias() == null) {
                        // An unaliased table can also be referenced by its short name
                        // inside this scope; an alias hides the original name.
                        String shortKey = qualifiedKey(lastSegment(name));
                        if (locals.containsKey(shortKey)
                                && !String.valueOf(learnedTable).equals(
                                        String.valueOf(locals.get(shortKey)))) {
                            locals.put(shortKey, LOCAL_AMBIGUOUS);
                        } else {
                            locals.put(shortKey, learnedTable);
                        }
                    }
                }
            }
        } else if (from instanceof SQLSubqueryTableSource) {
            SQLSubqueryTableSource subquery = (SQLSubqueryTableSource) from;
            if (subquery.getAlias() != null) {
                locals.put(qualifiedKey(subquery.getAlias()), null);
            }
        } else if (from instanceof SQLUnionQueryTableSource) {
            SQLUnionQueryTableSource union = (SQLUnionQueryTableSource) from;
            if (union.getAlias() != null) {
                locals.put(qualifiedKey(union.getAlias()), null);
            }
        }
    }

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
            recordScalarTableLevel(inner, stackTargetTable.peek(), currentOrdinal,
                    stackTargetColumn.peek());
            return;
        }
        String targetTable = stackTargetTable.peek();
        String targetColumn = stackTargetColumn.peek();
        if (uncertainOrdinal >= 0 && currentOrdinal == uncertainOrdinal) {
            return;
        }
        for (int i = 0; i < inner.columns.size(); i++) {
            String source = inner.references.get(i);
            instructions.add(new Instruction(OperationType.COLUMN_MAPPING, source,
                    targetTable + "." + targetColumn, currentOrdinal, true,
                    tempTables.contains(targetTable)));
        }
    }

    private void visitIdentifier(SQLIdentifierExpr identifier) {
        if (!inTargetExpression) {
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
        if (frame >= stackUsing.size() || usingParticipantsByIdentity.isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, List<SourceTable>> using = stackUsing.get(frame);
        if (using == null) {
            return new ArrayList<>();
        }
        Map<String, List<SourceTable>> participants =
                usingParticipantsByIdentity.get(using);
        if (participants == null || !participants.containsKey(key)) {
            return new ArrayList<>();
        }
        return new ArrayList<>(participants.get(key));
    }

    private List<SourceTable> frameCandidates(int frame, String key,
            List<SourceTable> participants, List<SourceTable> excluded) {
        Set<SourceTable> hidden = frame < stackHiddenSources.size()
                ? stackHiddenSources.get(frame)
                : java.util.Collections.<SourceTable>emptySet();
        List<SourceTable> candidates = new ArrayList<>();
        for (SourceTable source : stackSources.get(frame)) {
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
        int current = stackSources.size() - 1;
        return current >= 0 && current < lateralScopes.size()
                && Boolean.TRUE.equals(lateralScopes.get(current))
                || hasLateralAncestorScope();
    }

    private boolean hasOuterScopeFallback() {
        return hasLateralAncestorScope() || scalarSubqueryDepth > 0;
    }

    private boolean hasLateralAncestorScope() {
        for (int frame = 0; frame < stackSources.size() - 1; frame++) {
            if (frame < lateralScopes.size()
                    && Boolean.TRUE.equals(lateralScopes.get(frame))) {
                return true;
            }
        }
        return false;
    }

    private List<SourceTable> resolveCorrelatedUnqualified(String rawName) {
        String key = identifierKey(rawName);
        boolean lateralScope = false;
        for (int frame = stackSources.size() - 2; frame >= 0; frame--) {
            boolean lateralFrame = frame < lateralScopes.size()
                    && Boolean.TRUE.equals(lateralScopes.get(frame));
            if (!lateralFrame && scalarSubqueryDepth == 0) {
                continue;
            }
            lateralScope = true;
            Map<String, List<SourceTable>> using = frame < stackUsing.size()
                    ? stackUsing.get(frame) : null;
            if (using != null && using.containsKey(key)) {
                List<StarOutput> framePlan = frame < stackStarPlan.size()
                        ? stackStarPlan.get(frame)
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
            List<StarOutput> plan = frame < stackStarPlan.size()
                    ? stackStarPlan.get(frame)
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
            Set<SourceTable> hidden = frame < stackHiddenSources.size()
                    ? stackHiddenSources.get(frame)
                    : java.util.Collections.<SourceTable>emptySet();
            List<SourceTable> owners = new ArrayList<>();
            List<SourceTable> unknown = new ArrayList<>();
            for (SourceTable source : stackSources.get(frame)) {
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

    private static final class CorrelatedOwner {
        private final SourceTable source;
        private final SourceNode binding;

        private CorrelatedOwner(SourceTable source, SourceNode binding) {
            this.source = source;
            this.binding = binding;
        }
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
        for (int frame = stackSources.size() - 1; frame >= 0; frame--) {
            if (frame < stackJoinBindings.size() && aliasKey != null) {
                SourceNode binding = stackJoinBindings.get(frame).get(aliasKey);
                if (binding != null) {
                    return new CorrelatedOwner(null, binding);
                }
            }
            Set<SourceTable> hidden = frame < stackHiddenSources.size()
                    ? stackHiddenSources.get(frame)
                    : java.util.Collections.<SourceTable>emptySet();
            // An explicit alias wins over a same-spelled table name in this frame.
            List<SourceTable> aliasMatches = new ArrayList<>();
            for (SourceTable source : stackSources.get(frame)) {
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
            for (SourceTable source : stackSources.get(frame)) {
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
                || stackJoinBindings.isEmpty()) {
            return null;
        }
        String key = identifierKey(owner.toString());
        return key == null ? null : stackJoinBindings.peek().get(key);
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
            if (!inTargetExpression) {
                return;
            }
            CorrelatedOwner correlated = findCorrelatedOwner(property.getOwner());
            SourceTable source = correlated == null ? null : correlated.source;
            if (source == null) {
                SourceNode binding = correlated == null ? null : correlated.binding;
                if (binding != null) {
                    mapBindingStar(stackTargetTable.peek(), binding, currentOrdinal);
                    return;
                }
                if (context != null) {
                    throw new MetadataNotFoundException(property.getOwner() == null
                            ? name : property.getOwner().toString());
                }
                return;
            }
            String targetTable = stackTargetTable.peek();
            if (detachedMetadata()) {
                emitDetachedPlan(targetTable, planFromSource(source),
                        java.util.Collections.singletonList(source), null, currentOrdinal);
                return;
            }
            SourceNode expanded = currentExpandedSourceNodes.get(source);
            if (expanded != null) {
                mapBindingStar(targetTable, expanded, currentOrdinal);
                return;
            }
            SourceNode leafNode = stackSourceNode.isEmpty()
                    ? null : stackSourceNode.peek();
            if (context == null && currentStarBudget > 0 && leafNode != null
                    && leafNode.sources.size() == 1
                    && leafNode.sources.get(0) == source
                    && !sourceColumnsKnown(source)) {
                mapBindingStar(targetTable, leafNode, currentOrdinal);
                return;
            }
            if (context == null && sourceNamesKnown(source)
                    && !source.outputColumns.isEmpty()) {
                mapSourceStar(source, targetTable, currentOrdinal);
                return;
            }
            instructions.add(new Instruction(OperationType.COLUMNS_INJECT,
                    source.displayName, targetTable, currentOrdinal, source.temporary,
                    tempTables.contains(targetTable)));
            return;
        }

        if (!inTargetExpression) {
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
            } else if (uncertainOrdinal < 0 || currentOrdinal != uncertainOrdinal) {
                String targetTable = stackTargetTable.peek();
                for (SourceTable source : starOutputSources(matches.get(0))) {
                    instructions.add(new Instruction(OperationType.COLUMNS_INJECT,
                            source.displayName, targetTable, currentOrdinal,
                            source.temporary, tempTables.contains(targetTable)));
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
                                source.displayName, stackTargetTable.peek(), currentOrdinal,
                                source.temporary,
                                tempTables.contains(stackTargetTable.peek())));
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
        String targetTable = stackTargetTable.peek();
        String targetColumn = stackTargetColumn.peek();
        for (StarColumn column : columns) {
            if (!column.source.wildcardContribution) {
                continue;
            }
            Instruction wildcard = new Instruction(OperationType.COLUMNS_INJECT,
                    lineageTableName(column.source), targetTable, currentOrdinal,
                    column.source.temporary, tempTables.contains(targetTable));
            wildcard.setPositionalWildcard(true);
            // The contribution only covers the named column's own position.
            wildcard.setWildcardWidthKnown(true);
            wildcard.setWildcardWidth(1);
            instructions.add(wildcard);
            targetWildcardContribution.put(targetTable, Boolean.TRUE);
        }
        if (uncertainOrdinal >= 0 && currentOrdinal == uncertainOrdinal) {
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
                    targetTable + "." + targetColumn, currentOrdinal,
                    column.source.temporary, tempTables.contains(targetTable)));
        }
    }

    private static String renderSourceColumn(StarColumn column) {
        if (column.quoted) {
            return "\"" + column.column.replace("\"", "\"\"") + "\"";
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
                if (isExistingOutput(targetOutputs.get(target), name, position)) {
                    int suffix = position + 1;
                    do {
                        name = "col" + suffix++;
                        key = identifierKey(name);
                    } while (isExistingOutput(targetOutputs.get(target), name, position));
                }
                markOutputNameKnown(target, position, declaredName || stableName);
            }
            setTargetOutput(target, position++, name, key);
        }
    }

    private void mapBindingStar(String targetTable, SourceNode binding, int startPosition) {
        List<String> declared = currentTargetColumnAliases(targetTable);
        List<String> registered = targetOutputs.get(targetTable);
        boolean uncertain = uncertainOrdinal >= 0 && uncertainOrdinal == startPosition;
        boolean targetTemporary = tempTables.contains(targetTable);
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
    private int emitDetachedStar(String targetTable, List<SourceTable> sources,
            int startPosition) {
        SourceNode node = stackSourceNode.isEmpty() ? null : stackSourceNode.peek();
        return emitDetachedPlan(targetTable, currentStarPlan(sources), sources, node,
                startPosition);
    }

    private int emitDetachedPlan(String targetTable, List<StarOutput> template,
            List<SourceTable> sources, SourceNode node, int startPosition) {
        List<String> declared = currentTargetColumnAliases(targetTable);
        boolean targetTemporary = tempTables.contains(targetTable);

        boolean uncertainPosition = uncertainOrdinal >= 0
                && uncertainOrdinal == startPosition;
        boolean determinate = !sources.isEmpty() && !uncertainPosition;
        for (SourceTable source : sources) {
            if (!sourceNamesKnown(source)) {
                determinate = false;
                break;
            }
        }
        if (!determinate) {
            targetWildcardContribution.put(targetTable, Boolean.TRUE);
            // Contract: positions cannot be derived from column names. Propagate the
            // unknown sources recorded at each visible position instead of guessing.
            if (sources.size() == 1 && sources.get(0).temporary
                    && !wildcardSpansOf(sources.get(0).displayName).isEmpty()) {
                SourceTable source = sources.get(0);
                List<WildcardSpan> spans = wildcardSpansOf(source.displayName);
                int sourceWidth = uncertainPosition ? -1
                        : sourceWidthKnown(source)
                                ? Math.max(sourceKnownWidth(source), maxSpanEnd(spans))
                                : currentStarBudget > 0 ? currentStarBudget : -1;
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
                    if (coveredBySpan(spans, relative)
                            || !sourceOutputNameKnown(source, relative)
                            || relative >= sourceNames.size()) {
                        continue;
                    }
                    String name = sourceNames.get(relative);
                    String outputName = declaredTarget != null
                            && startPosition + relative < declaredTarget.size()
                                    ? declaredTarget.get(startPosition + relative) : name;
                    instructions.add(new Instruction(OperationType.COLUMN_MAPPING,
                            source.displayName + "." + renderIdentifier(name),
                            targetTable + "." + renderIdentifier(outputName),
                            startPosition + relative, source.temporary,
                            targetTemporary));
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
                    && !uncertainPosition && currentStarBudget > 0;
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
                    sourceWidth = currentStarBudget;
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
                advance = currentStarBudget;
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
            if (exact && isExistingOutput(targetOutputs.get(targetTable), name, position)) {
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
            targetWildcardContribution.put(targetTable, Boolean.TRUE);
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

    private String canonicalColumnName(String raw) {
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

    private String canonicalPhysicalReference(String raw) {
        String[] parts = MetadataCatalog.splitQualifiedNamePreservingQuotes(raw);
        StringBuilder reference = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                reference.append('.');
            }
            String part = parts[i];
            boolean quoted = MetadataCatalog.isQuotedIdentifier(part);
            String name = MetadataCatalog.normalizeIdentifier(part);
            if (!quoted) {
                // PostgreSQL folds unquoted identifiers to lower case, so A and a are
                // the same table. Other dialects keep their spelling here.
                reference.append(currentDialect() == DbType.postgresql
                        ? name.toLowerCase(Locale.ROOT) : name);
                continue;
            }
            // A quoted identifier that folds to itself is the same as the unquoted
            // spelling (PostgreSQL "a" and a); one that does not is a distinct name.
            boolean special = name.contains(".") || name.contains("(") || name.contains(")");
            boolean folds = !special && name.equals(foldName(name));
            reference.append(folds ? name : part);
        }
        return reference.toString();
    }

    private String sessionPhysicalReference(String rawName) {
        String reference = rawName;
        String prefix = sessionReferencePrefix();
        if (prefix != null && rawName != null
                && isSingleSegmentName(rawName)) {
            reference = prefix + "." + rawName;
        }
        return canonicalPhysicalReference(reference);
    }

    private String sessionReferencePrefix() {
        DbType dialect = currentDialect();
        if (dialect == DbType.postgresql && !sessionRawSchemas.isEmpty()) {
            return sessionRawSchemas.get(0);
        }
        if (sessionDatabase != null) {
            return sessionRawDatabase != null ? sessionRawDatabase
                    : sessionDatabase;
        }
        if (!sessionRawSchemas.isEmpty()) {
            return sessionRawSchemas.get(0);
        }
        return null;
    }

    private String foldName(String name) {
        DbType dialect = currentDialect();
        return dialect == DbType.mysql || dialect == DbType.mariadb
                || dialect == DbType.postgresql
                        ? name.toLowerCase(Locale.ROOT)
                        : name.toUpperCase(Locale.ROOT);
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
        List<String> existing = targetOutputs.get(targetTable);
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
        boolean uncertain = uncertainOrdinal >= 0 && uncertainOrdinal == startPosition;
        boolean targetTemporary = tempTables.contains(targetTable);
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
        if (!inTargetExpression) {
            return;
        }
        List<SourceTable> sources = currentSources();
        if (sources.isEmpty()) {
            return;
        }
        String targetTable = stackTargetTable.peek();
        if (detachedMetadata()) {
            emitDetachedStar(targetTable, sources, currentOrdinal);
            return;
        }
        boolean targetTemporary = tempTables.contains(targetTable);
        Map<String, List<SourceTable>> using = stackUsing.isEmpty()
                ? null : stackUsing.peek();
        if (context == null && !stackSourceNode.isEmpty()
                && !stackSourceNode.peek().plan.isEmpty()
                && (using != null && !using.isEmpty() || currentStarBudget > 0)) {
            mapBindingStar(targetTable, stackSourceNode.peek(), currentOrdinal);
            return;
        }
        boolean planComplete = context == null && allSourceNamesKnown(sources)
                && !stackStarPlan.isEmpty() && !stackStarPlan.peek().isEmpty();
        if (useVisiblePlan(sources, using) || planComplete) {
            List<String> declared = currentTargetColumnAliases(targetTable);
            boolean uncertain = uncertainOrdinal >= 0 && currentOrdinal == uncertainOrdinal;
            int position = currentOrdinal;
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
                    sources.get(0).displayName, targetTable, currentOrdinal,
                    sources.get(0).temporary, targetTemporary));
            return;
        }
        int position = currentOrdinal;
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

        if (context != null && !stackStarPlan.isEmpty() && !stackStarPlan.peek().isEmpty()) {
            List<StarOutput> matches = new ArrayList<>();
            List<SourceTable> matchedSources = new ArrayList<>();
            for (StarOutput output : stackStarPlan.peek()) {
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
                    Map<String, List<SourceTable>> participants = stackUsing.isEmpty()
                            ? null : usingParticipantsByIdentity.get(stackUsing.peek());
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

        if (context == null && !stackStarPlan.isEmpty()
                && !stackStarPlan.peek().isEmpty()) {
            // Ambiguity between fully known outputs is decided by the visible plan even
            // when another joined source is unknown.
            List<StarOutput> definite = new ArrayList<>();
            for (StarOutput output : stackStarPlan.peek()) {
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

        boolean nodeNamesComplete = context == null && !stackSourceNode.isEmpty()
                && nodeNamesKnown(stackSourceNode.peek());
        if (context == null && (allSourceNamesKnown(sources) || nodeNamesComplete)
                && !stackStarPlan.isEmpty() && !stackStarPlan.peek().isEmpty()) {
            // The visible JOIN output plan already applies USING merge rules, so it is
            // authoritative once every source names its columns.
            List<StarOutput> matches = new ArrayList<>();
            for (StarOutput output : stackStarPlan.peek()) {
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

        Map<String, List<SourceTable>> using = stackUsing.isEmpty()
                ? null : stackUsing.peek();
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
        if (owner == null || stackJoinBindings.isEmpty()) {
            return null;
        }
        String key = identifierKey(lastSegment(owner.toString()));
        return key == null ? null : stackJoinBindings.peek().get(key);
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
                && Boolean.TRUE.equals(targetWidthKnown.get(source.displayName));
    }

    private int derivedWidth(String target, List<String> outputs, boolean widthKnown) {
        int width = outputs.size();
        if (widthKnown) {
            Integer known = targetMinWidth.get(target);
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
                && Boolean.TRUE.equals(targetWidthKnown.get(source.displayName))) {
            Integer known = targetMinWidth.get(source.displayName);
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

    private String qualifiedKey(String rawName) {
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

    private String canonicalColumnName(String table, String rawColumn) {
        if (context == null || tempTables.contains(table)) {
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

    private String renderInsertColumn(Table metadata, String tableName, SQLExpr column) {
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
            String quoted = "\"" + name.replace("\"", "\"\"") + "\"";
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
            return "\"" + resolved.replace("\"", "\"\"") + "\"";
        }
        return renderIdentifier(resolved);
    }

    private DbType currentDialect() {
        return context != null ? context.getCatalog().getDialect() : dbType;
    }

    private void addColumnMapping(SourceTable source, String columnName) {
        String sourceName = detachedMetadata() && !source.temporary
                ? lineageTableName(source) : source.displayName;
        if (source.wildcardContribution && source.temporary) {
            // Reading one named column from a derived table that also carries unknown
            // rows must not cut the table-level lineage.
            String target = stackTargetTable.peek();
            Instruction wildcard = new Instruction(OperationType.COLUMNS_INJECT,
                    lineageTableName(source), target, currentOrdinal,
                    source.temporary, tempTables.contains(target));
            wildcard.setPositionalWildcard(true);
            wildcard.setWildcardWidthKnown(true);
            wildcard.setWildcardWidth(1);
            instructions.add(wildcard);
            recordWildcardSpan(target, currentOrdinal, currentOrdinal + 1,
                    lineageTableName(source), source.temporary);
            targetWildcardContribution.put(target, Boolean.TRUE);
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

        if (uncertainOrdinal >= 0 && currentOrdinal == uncertainOrdinal) {
            // The projection's position cannot be derived from the declaration, so a
            // positional edge would be a guess.
            return;
        }
        String targetTable = stackTargetTable.peek();
        instructions.add(new Instruction(OperationType.COLUMN_MAPPING,
                sourceTable + "." + renderIdentifier(columnName),
                targetTable + "." + stackTargetColumn.peek(), currentOrdinal,
                sourceTemporary, tempTables.contains(targetTable)));
    }

    /** Write an edge for a name that is already the catalog/derived spelling. */
    private void addResolvedColumnMapping(String sourceTable, String columnName,
            boolean sourceTemporary) {
        if (uncertainOrdinal >= 0 && currentOrdinal == uncertainOrdinal) {
            return;
        }
        String targetTable = stackTargetTable.peek();
        instructions.add(new Instruction(OperationType.COLUMN_MAPPING,
                sourceTable + "." + renderIdentifier(columnName),
                targetTable + "." + stackTargetColumn.peek(), currentOrdinal,
                sourceTemporary, tempTables.contains(targetTable)));
    }

    private static String renderIdentifier(String name) {
        if (name == null || name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return name;
        }
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    private List<SourceTable> currentSources() {
        return stackSources.isEmpty() ? new ArrayList<SourceTable>() : stackSources.peek();
    }

    private List<SourceTable> visibleSources() {
        List<SourceTable> sources = currentSources();
        if (stackHiddenSources.isEmpty() || stackHiddenSources.peek().isEmpty()) {
            return sources;
        }
        Set<SourceTable> hidden = stackHiddenSources.peek();
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

    private void processInsert(SQLInsertStatement insert) {
        annotateAnsiInsertColumns(insert);
        processInsertInto(insert.getTableSource(), insert.getColumns(),
                insert.getQuery(), insert.getWith(), insert.getPartitions());
    }

    /**
     * Druid's Hive/Spark lexer parses every double-quoted INSERT target as a text
     * literal. In Spark ANSI identifier mode those are identifiers; recover the
     * original quote delimiter from the statement text.
     */
    private void annotateAnsiInsertColumns(SQLInsertStatement insert) {
        if (!ansiEnabled || !ansiDoubleQuotedIdentifiers
                || statementSql == null || insert.getColumns().isEmpty()) {
            return;
        }
        List<Boolean> styles = insertColumnQuoteStyles(statementSql);
        if (styles.size() != insert.getColumns().size()) {
            return;
        }
        for (int i = 0; i < styles.size(); i++) {
            SQLExpr column = insert.getColumns().get(i);
            if (styles.get(i) && column instanceof SQLTextLiteralExpr) {
                column.putAttribute(ANSI_IDENTIFIER_ATTRIBUTE, Boolean.TRUE);
            }
        }
    }

    /** True for a double-quoted element in the INSERT column list. */
    private static List<Boolean> insertColumnQuoteStyles(String sql) {
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
                    && (i == 0 || !isIdentifierChar(sql.charAt(i - 1)))) {
                int end = i + 1;
                while (end < sql.length()
                        && isIdentifierChar(sql.charAt(end))) {
                    end++;
                }
                if (seenInsert && !tableNameSeen) {
                    tableNameSeen = true;
                    atInsertStart = false;
                }
                nextNameIsTablePart = false;
                i = end - 1;
            } else if (depth == 0 && (ch == '_' || ch == '$')
                    && (i == 0 || !isIdentifierChar(sql.charAt(i - 1)))) {
                int end = i + 1;
                while (end < sql.length()
                        && isIdentifierChar(sql.charAt(end))) {
                    end++;
                }
                if (seenInsert && !tableNameSeen) {
                    tableNameSeen = true;
                    atInsertStart = false;
                }
                nextNameIsTablePart = false;
                i = end - 1;
            } else if (depth == 0 && Character.isLetter(ch)
                    && (i == 0 || !isIdentifierChar(sql.charAt(i - 1)))) {
                int end = i + 1;
                while (end < sql.length()
                        && isIdentifierChar(sql.charAt(end))) {
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

    private static boolean isQueryGroup(String sql, int start, int end) {
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
                while (stop < end && isIdentifierChar(sql.charAt(stop))) {
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

    private static boolean isDoubleQuotedElement(String element) {
        String value = element.trim();
        return value.startsWith("\"") && value.endsWith("\"")
                && value.length() >= 2;
    }

    private void processReplace(SQLReplaceStatement replace) {
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
    private void processValuesInto(SQLExprTableSource tableSource,
            List<SQLExpr> insertColumns,
            List<SQLInsertStatement.ValuesClause> valueClauses) {
        String rawName = extractTableName(tableSource);
        String written = normalizeQualifiedName(rawName);
        String tableName = detachedMetadata() && context == null && rawName != null
                ? sessionPhysicalReference(rawName) : written;
        Table targetMetadata = null;
        if (context != null && findCteTarget(identifierKey(rawName)) == null) {
            TableLocation location = resolveMetadataTable(rawName);
            if (location != null) {
                tableName = location.getDisplayName();
                targetMetadata = location.getTable();
            } else if (detachedMetadata() && rawName != null) {
                tableName = sessionPhysicalReference(rawName);
            }
        }
        registerTable(written, tableName, tableName, tableSource.getAlias() == null
                ? null : normalizeIdentifier(tableSource.getAlias()),
                findCteTarget(identifierKey(rawName)) != null);
        if (valueClauses == null || valueClauses.isEmpty()) {
            return;
        }
        List<String> columnNames = new ArrayList<>();
        if (insertColumns != null && !insertColumns.isEmpty()) {
            for (SQLExpr column : insertColumns) {
                columnNames.add(renderInsertColumn(targetMetadata, tableName, column));
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
                if (!containsScalarSubquery(value)) {
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
    private void recordValueScalarMappings(SQLExpr value, String tableName,
            String column) {
        if (value == null) {
            return;
        }
        if (value instanceof SQLQueryExpr) {
            ScalarSubquery inner = analyzeScalarSubquery((SQLQueryExpr) value);
            if (inner == null) {
                return;
            }
            if (inner.columns.isEmpty()) {
                recordScalarTableLevel(inner, tableName, -1, column);
                return;
            }
            instructions.add(new Instruction(OperationType.COLUMN_MAPPING,
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

    private void processTruncate(SQLTruncateStatement truncate) {
        // A truncate removes every row, so it carries no column lineage.
        if (truncate.getTableSources() == null) {
            return;
        }
        for (SQLExprTableSource tableSource : truncate.getTableSources()) {
            String rawName = extractTableName(tableSource);
            if (rawName == null) {
                continue;
            }
            registerTable(normalizeQualifiedName(rawName),
                    normalizeQualifiedName(rawName),
                    normalizeQualifiedName(rawName), null, false);
        }
    }

    private void pushClauseScope(List<SourceTable> sources,
            Map<String, List<SourceTable>> using,
            Map<String, SourceNode> bindings, Set<SourceTable> hidden,
            List<StarOutput> plan) {
        stackSources.push(sources);
        lateralScopes.push(Boolean.FALSE);
        stackUsing.push(using);
        stackStarPlan.push(plan == null ? new ArrayList<StarOutput>()
                : new ArrayList<>(plan));
        stackHiddenSources.push(new HashSet<>(hidden));
        stackJoinBindings.push(new HashMap<>(bindings));
    }

    private void popClauseScope() {
        stackJoinBindings.pop();
        stackHiddenSources.pop();
        stackStarPlan.pop();
        stackUsing.pop();
        lateralScopes.pop();
        stackSources.pop();
    }

    private void processMerge(SQLMergeStatement merge) {
        SQLTableSource into = merge.getInto();
        if (!(into instanceof SQLExprTableSource)) {
            return;
        }
        SQLExprTableSource target = (SQLExprTableSource) into;
        String rawName = extractTableName(target);
        String written = normalizeQualifiedName(rawName);
        String tableName = written;
        if (context != null && findCteTarget(identifierKey(rawName)) == null) {
            TableLocation location = resolveMetadataTable(rawName);
            if (location != null) {
                tableName = location.getDisplayName();
            }
        }
        registerTable(written, tableName, tableName, null, false);

        Map<String, List<SourceTable>> using = new LinkedHashMap<>();
        List<SourceTable> targetSources = new ArrayList<>();
        targetSources.addAll(buildSourceNode(target, using).sources);
        List<SourceTable> sourceSources = new ArrayList<>();
        // The join bindings of the USING clause (for example its alias) stay visible
        // to both clauses.
        Map<String, SourceNode> usingBindings = new HashMap<>();
        Set<SourceTable> usingHidden = new HashSet<>();
        stackJoinBindings.push(usingBindings);
        stackHiddenSources.push(usingHidden);
        List<StarOutput> usingPlan = new ArrayList<>();
        try {
            if (merge.getUsing() instanceof SQLTableSource) {
                SourceNode usingNode = buildSourceNode(
                        (SQLTableSource) merge.getUsing(), using);
                sourceSources.addAll(usingNode.sources);
                usingPlan.addAll(usingNode.plan);
            }
        } finally {
            stackHiddenSources.pop();
            stackJoinBindings.pop();
        }
        Table targetTableMetadata = null;
        if (context != null) {
            TableLocation location = resolveMetadataTable(rawName);
            if (location != null) {
                targetTableMetadata = location.getTable();
            }
        }
        stackTargetTable.push(tableName);
        stackSourceTable.push(tableName);
        inTargetExpression = true;
        try {
            SQLMergeStatement.MergeUpdateClause update = merge.getUpdateClause();
            if (update != null) {
                // The update expression reads the target and the source.
                List<SourceTable> scope = new ArrayList<>(targetSources);
                scope.addAll(sourceSources);
                pushClauseScope(scope, using, usingBindings, usingHidden, usingPlan);
                try {
                    for (SQLUpdateSetItem item : update.getItems()) {
                        stackTargetColumn.push(renderIdentifier(
                                mergeTargetColumn(targetTableMetadata, tableName,
                                        item.getColumn())));
                        currentOrdinal = -1;
                        visitExpr(item.getValue());
                        stackTargetColumn.pop();
                    }
                } finally {
                    popClauseScope();
                }
                collectTablesFromExpression(update.getWhere());
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
                        stackTargetColumn.push(renderIdentifier(column));
                        currentOrdinal = -1;
                        visitExpr(values.get(i));
                        stackTargetColumn.pop();
                    }
                } finally {
                    popClauseScope();
                }
                collectTablesFromExpression(insert.getWhere());
            }
        } finally {
            inTargetExpression = false;
            stackSourceTable.pop();
            stackTargetTable.pop();
        }
        collectTablesFromExpression(merge.getOn());
    }

    private String renderMetadataColumn(Column column) {
        if (!column.isQuoted()) {
            return renderIdentifier(column.getColumnName());
        }
        DbType dialect = currentDialect();
        if (dialect == DbType.mysql || dialect == DbType.mariadb) {
            return "`" + column.getColumnName().replace("`", "``") + "`";
        }
        return "\"" + column.getColumnName().replace("\"", "\"\"") + "\"";
    }

    private String mergeTargetColumn(Table metadata, String tableName, SQLExpr column) {
        String raw = rawColumnName(column);
        if (!MetadataCatalog.isQuotedIdentifier(raw)) {
            raw = lastIdentifier(raw);
        }
        if (metadata != null) {
            String resolved = MetadataCatalog.resolveColumnName(metadata, raw,
                    currentDialect());
            if (resolved == null) {
                throw new ColumnNotFoundException(normalizeIdentifier(raw),
                        java.util.Collections.singletonList(tableName));
            }
            return resolved;
        }
        return normalizeIdentifier(raw);
    }

    private List<String> mergeTargetColumns(Table metadata, String tableName,
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

    private void processInsertInto(SQLExprTableSource tableSource,
            List<SQLExpr> insertColumns, SQLSelect select,
            SQLWithSubqueryClause with, List<SQLAssignItem> partitions) {
        String rawName = extractTableName(tableSource);
        String written = normalizeQualifiedName(rawName);
        // The graph identity of an unaliased target keeps its written reference when
        // metadata is not available, so later reads resolve to the same vertex.
        String tableName = detachedMetadata() && context == null && rawName != null
                ? sessionPhysicalReference(rawName) : written;
        Table targetMetadata = null;
        if (context != null && findCteTarget(identifierKey(rawName)) == null) {
            TableLocation location = resolveMetadataTable(rawName);
            if (location != null) {
                tableName = location.getDisplayName();
                targetMetadata = location.getTable();
            } else if (detachedMetadata() && rawName != null) {
                // The target table is missing from the catalog: keep its written
                // identity so the table-level edge matches later reads.
                tableName = sessionPhysicalReference(rawName);
            }
        }
        registerTable(written, tableName, tableName, tableSource.getAlias() == null
                ? null : normalizeIdentifier(tableSource.getAlias()),
                findCteTarget(identifierKey(rawName)) != null);

        // Static partition columns receive no data; dynamic partition columns follow
        // the table's non-partition columns in partition-spec order. Other dialects use
        // PARTITION (...) as a physical partition selector, not a column list.
        DbType insertDialect = resolveDialect();
        String partitionTargets = insertDialect == DbType.hive
                || insertDialect == DbType.antspark
                        ? partitionTargetList(insertColumns, partitions,
                                targetMetadata, tableName)
                        : null;

        boolean scoped = with != null;
        if (scoped) {
            cteScopes.push(new HashMap<String, String>());
        }
        try {
            if (with != null) {
                processWithSubQuery(with);
            }

            String insertTemp = getTempTableName();
            stackTargetTable.push(insertTemp);
            intermediateTargets.add(insertTemp);

            if (select != null) {
                processSelect(select);
            }

            String fromTable = stackTargetTable.peek();
            boolean fromTemporary = tempTables.contains(fromTable);
            List<SQLExpr> columns = insertColumns;
            if (partitionTargets != null) {
                Instruction insertEdge = new Instruction(OperationType.COLUMNS_INJECT,
                        fromTable, String.format("%s(%s)", tableName, partitionTargets),
                        -1, fromTemporary, false);
                insertEdge.setWildcardTable(tableName);
                insertEdge.setPopulatedColumns(partitionTargets);
                instructions.add(insertEdge);
            } else if (columns != null && !columns.isEmpty()) {
                final Table insertMetadata = targetMetadata;
                final String insertTable = tableName;
                String colStr = columns.stream()
                        .map(column -> renderInsertColumn(insertMetadata, insertTable, column))
                        .collect(Collectors.joining(","));
                Instruction insertEdge = new Instruction(OperationType.COLUMNS_INJECT,
                        fromTable, String.format("%s(%s)", tableName, colStr), -1,
                        fromTemporary, false);
                insertEdge.setWildcardTable(tableName);
                insertEdge.setPopulatedColumns(colStr);
                instructions.add(insertEdge);
            } else {
                instructions.add(new Instruction(OperationType.COLUMNS_INJECT,
                        fromTable, tableName, -1, fromTemporary, false));
            }
            stackTargetTable.pop();
        } finally {
            if (scoped) {
                cteScopes.pop();
            }
        }
    }

    /**
     * Compare partition names with catalog fields by name, not by SQL quotation.
     * Hive/Spark partition matching is case-insensitive and only the outer
     * backtick/double-quote layer is syntactic.
     */
    private static String partitionKey(String raw) {
        return foldCase(hiveLogicalName(raw));
    }

    private static String partitionColumnKey(String columnName) {
        return foldCase(columnName);
    }

    /**
     * Case fold matching {@link String#equalsIgnoreCase}: per-character
     * upper-then-lower, independent of the JVM locale.
     */
    private static String foldCase(String value) {
        if (value == null) {
            return null;
        }
        return MetadataCatalog.caseFold(value);
    }

    /** Strip one Hive SQL identifier layer, keeping inner quote characters. */
    /** Fold an already-decoded Hive/Spark planned column name. */
    private static String hivePlannedColumn(String column) {
        return foldCase(column);
    }

    private static String hiveColumnIdentity(String raw) {
        return foldCase(hiveLogicalName(raw));
    }

    private static String hiveLogicalName(String raw) {
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

    private static String catalogSpelling(Table metadata, String raw) {
        String logical = hiveLogicalName(raw);
        String key = foldCase(logical);
        for (Column column : metadata.getFields()) {
            if (foldCase(column.getColumnName()).equals(key)) {
                return column.getColumnName();
            }
        }
        return logical;
    }

    private static String renderHiveIdentifier(String name) {
        if (name == null || name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return name;
        }
        return "`" + name.replace("`", "``") + "`";
    }

    private String partitionTargetList(List<SQLExpr> insertColumns,
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
                targets.add(renderInsertColumn(targetMetadata, tableName, column));
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

    private void processUpdate(SQLUpdateStatement update) {
        Map<String, List<SourceTable>> using = new LinkedHashMap<>();
        List<SourceTable> sources = new ArrayList<>();
        List<StarOutput> plan = new ArrayList<>();
        stackJoinBindings.push(new HashMap<String, SourceNode>());
        stackHiddenSources.push(new HashSet<SourceTable>());
        try {
            if (update.getTableSource() != null) {
                SourceNode node = buildSourceNode(update.getTableSource(), using);
                sources.addAll(node.sources);
                plan.addAll(node.plan);
            }
            if (update.getFrom() != null) {
                SourceNode node = buildSourceNode(update.getFrom(), using);
                sources.addAll(node.sources);
                plan.addAll(node.plan);
            }
            if (sources.isEmpty()) {
                return;
            }

            String target = sources.get(0).displayName;
            stackTargetTable.push(target);
            stackSources.push(sources);
            lateralScopes.push(Boolean.FALSE);
            stackUsing.push(using);
            stackStarPlan.push(plan);
            stackSourceTable.push(target);
            inTargetExpression = true;
            try {
                for (SQLUpdateSetItem item : update.getItems()) {
                    String targetColumn = canonicalColumnName(target,
                            rawColumnName(item.getColumn()));
                    stackTargetColumn.push(renderIdentifier(targetColumn));
                    currentOrdinal = -1;
                    visitExpr(item.getValue());
                    stackTargetColumn.pop();
                }
            } finally {
                inTargetExpression = false;
                stackSourceTable.pop();
                stackStarPlan.pop();
                stackUsing.pop();
                lateralScopes.pop();
                stackSources.pop();
                stackTargetTable.pop();
            }
        } finally {
            stackHiddenSources.pop();
            stackJoinBindings.pop();
        }
        collectTablesFromExpression(update.getWhere());
    }

    private void processDelete(SQLDeleteStatement delete) {
        Map<String, List<SourceTable>> using = new LinkedHashMap<>();
        List<SourceTable> sources = new ArrayList<>();
        if (delete.getTableSource() != null) {
            sources.addAll(buildSourceNode(delete.getTableSource(), using).sources);
        }
        if (delete.getFrom() != null) {
            sources.addAll(buildSourceNode(delete.getFrom(), using).sources);
        }
        collectTablesFromExpression(delete.getWhere());
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
        SQLWithSubqueryClause with = select.getWithSubQuery();
        if (with != null) {
            Set<String> local = new HashSet<>();
            collectCteScopes.push(local);
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
                collectCteScopes.pop();
            }
            return;
        }
        collectTablesFromQuery(select.getQuery());
    }

    private boolean collectCteNameVisible(String key) {
        for (Set<String> frame : collectCteScopes) {
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
        tempTables = new HashSet<>();
        usedTargets = new HashSet<>();
        cteScopes = new Stack<>();
        collectCteScopes = new Stack<>();
        lateralScopes = new Stack<>();
        targetColumnAliases = new Stack<>();
        cteDeclaredColumns = new HashMap<>();
        cteDeclaredKeys = new HashMap<>();
        targetOutputs = new HashMap<>();
        targetOutputKeys = new HashMap<>();
        intermediateTargets = new HashSet<>();
        unionTargets = new HashSet<>();
        targetWidthKnown = new HashMap<>();
        targetNamesKnown = new HashMap<>();
        targetOutputUnknownGroup = new HashMap<>();
        targetWildcardContribution = new HashMap<>();
        targetWildcardSpans = new HashMap<>();
        targetOutputNameKnown = new HashMap<>();
        targetMinWidth = new HashMap<>();
        targetWidthOverride = new HashMap<>();
        nonAuthoritativeDepth = new HashMap<>();
        lastQueryBlockWidth = 0;
        lastQueryBlockWidthKnown = true;
        lastQueryBlockNamesKnown = true;
        lastQueryBlockMinWidth = 0;
        stackStarPlan = new Stack<>();
        stackSourceNode = new Stack<>();
        instructions = learningOnly
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
        stackSourceTable = new Stack<>();
        stackTargetTable = new Stack<>();
        stackTargetColumn = new Stack<>();
        stackSources = new Stack<>();
        stackUsing = new Stack<>();
        stackJoinBindings = new Stack<>();
        usingParticipantsByIdentity = new IdentityHashMap<>();
        tableSourceBindings = new IdentityHashMap<>();
        stackHiddenSources = new Stack<>();
        inTargetExpression = false;
        currentOrdinal = -1;
        uncertainOrdinal = -1;
        currentStarBudget = -1;
    }

    public String getTempTableName() {
        String name = uniqueTarget(String.format("temp%d", tempTableNum++));
        if (tempTables != null) {
            tempTables.add(name);
        }
        return name;
    }

    private String uniqueTarget(String preferred) {
        String candidate = preferred;
        int suffix = 1;
        while (usedTargets.contains(candidate)) {
            candidate = preferred + "#" + suffix++;
        }
        usedTargets.add(candidate);
        return candidate;
    }

    private static String rawColumnName(SQLExpr expr) {
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
        private final String rawName;
        private final String alias;
        private final Table metadataTable;
        private final boolean temporary;
        private final boolean outputWidthKnown;
        private final boolean outputNamesKnown;
        private final List<String> outputColumns;
        private final List<String> outputKeys;
        private final List<Boolean> outputNameKnown;
        private final List<SourceTable> outputUnknownGroups;
        private final int minOutputColumns;
        private final boolean wildcardOnly;
        private final boolean wildcardContribution;
        private final boolean learnable;
        private final String implicitName;

        private SourceTable(String displayName, String writtenName, String rawName, String alias,
                Table metadataTable, boolean temporary, boolean outputWidthKnown,
                boolean outputNamesKnown, List<String> outputColumns, List<String> outputKeys,
                List<Boolean> outputNameKnown, int minOutputColumns,
                List<SourceTable> outputUnknownGroups) {
            this(displayName, writtenName, rawName, alias, metadataTable, temporary,
                    outputWidthKnown, outputNamesKnown, outputColumns, outputKeys,
                    outputNameKnown, minOutputColumns, outputUnknownGroups, false, false);
        }

        private SourceTable(String displayName, String writtenName, String rawName, String alias,
                Table metadataTable, boolean temporary, boolean outputWidthKnown,
                boolean outputNamesKnown, List<String> outputColumns, List<String> outputKeys,
                List<Boolean> outputNameKnown, int minOutputColumns,
                List<SourceTable> outputUnknownGroups, boolean wildcardOnly,
                boolean wildcardContribution) {
            this(displayName, writtenName, rawName, alias, metadataTable, temporary,
                    outputWidthKnown, outputNamesKnown, outputColumns, outputKeys,
                    outputNameKnown, minOutputColumns, outputUnknownGroups, wildcardOnly,
                    wildcardContribution, true, null);
        }

        private SourceTable(String displayName, String writtenName, String rawName, String alias,
                Table metadataTable, boolean temporary, boolean outputWidthKnown,
                boolean outputNamesKnown, List<String> outputColumns, List<String> outputKeys,
                List<Boolean> outputNameKnown, int minOutputColumns,
                List<SourceTable> outputUnknownGroups, boolean wildcardOnly,
                boolean wildcardContribution, boolean learnable, String implicitName) {
            this.displayName = displayName;
            this.writtenName = writtenName;
            this.rawName = rawName;
            this.alias = alias;
            this.metadataTable = metadataTable;
            this.temporary = temporary;
            this.outputWidthKnown = outputWidthKnown;
            this.outputNamesKnown = outputNamesKnown;
            this.outputColumns = outputColumns == null
                    ? java.util.Collections.<String>emptyList() : outputColumns;
            this.outputKeys = outputKeys == null
                    ? java.util.Collections.<String>emptyList() : outputKeys;
            this.outputNameKnown = outputNameKnown == null
                    ? java.util.Collections.<Boolean>emptyList() : outputNameKnown;
            this.minOutputColumns = minOutputColumns;
            this.outputUnknownGroups = outputUnknownGroups == null
                    ? java.util.Collections.<SourceTable>emptyList() : outputUnknownGroups;
            this.wildcardOnly = wildcardOnly;
            this.wildcardContribution = wildcardContribution;
            this.learnable = learnable;
            this.implicitName = implicitName;
        }
    }

    private static final class OutputName {
        private final String name;
        private final String key;

        private OutputName(String name, String key) {
            this.name = name;
            this.key = key;
        }
    }

    private static final class StarOutput {
        private final List<StarColumn> sources;
        private final boolean nameKnown;
        private final boolean stable;
        private final StarOutput origin;
        /**
         * True when the position or source identity of this output was guessed, so the
         * metadata-free contract must keep the projection at table level.
         */
        private final boolean ambiguous;

        private StarOutput(List<StarColumn> sources) {
            this(sources, false);
        }

        private StarOutput(List<StarColumn> sources, boolean nameKnown) {
            this(sources, nameKnown, true);
        }

        private StarOutput(List<StarColumn> sources, boolean nameKnown, boolean stable) {
            this(sources, nameKnown, stable, null);
        }

        private StarOutput(List<StarColumn> sources, boolean nameKnown, boolean stable,
                StarOutput origin) {
            this(sources, nameKnown, stable, origin, false);
        }

        private StarOutput(List<StarColumn> sources, boolean nameKnown, boolean stable,
                StarOutput origin, boolean ambiguous) {
            this.sources = sources;
            this.nameKnown = nameKnown;
            this.stable = stable;
            this.origin = origin == null ? this : origin;
            this.ambiguous = ambiguous;
        }

        private StarOutput withStable(boolean value) {
            return new StarOutput(sources, nameKnown, value, origin, ambiguous);
        }

        private StarOutput withAmbiguous(boolean value) {
            return new StarOutput(sources, nameKnown, stable, origin, value);
        }
    }

    private static final class SourceNode {
        private final List<SourceTable> sources;
        private final List<StarOutput> plan;
        private final boolean knownColumns;
        private final boolean widthKnown;
        private final int width;
        private final int minWidth;
        private final int usingCount;
        private final int rightBoundary;
        private final List<SourceTable> unknownLeftSources;
        private final List<SourceTable> unknownRightSources;
        private final Map<SourceTable, Integer> unknownSectionEnd;
        private final SourceNode leftNode;
        private final SourceNode rightNode;

        private SourceNode(List<SourceTable> sources, List<StarOutput> plan,
                boolean knownColumns, boolean widthKnown, int width, int minWidth,
                int usingCount, int rightBoundary, List<SourceTable> unknownLeftSources,
                List<SourceTable> unknownRightSources,
                Map<SourceTable, Integer> unknownSectionEnd) {
            this(sources, plan, knownColumns, widthKnown, width, minWidth, usingCount,
                    rightBoundary, unknownLeftSources, unknownRightSources,
                    unknownSectionEnd, null, null);
        }

        private SourceNode(List<SourceTable> sources, List<StarOutput> plan,
                boolean knownColumns, boolean widthKnown, int width, int minWidth,
                int usingCount, int rightBoundary, List<SourceTable> unknownLeftSources,
                List<SourceTable> unknownRightSources,
                Map<SourceTable, Integer> unknownSectionEnd, SourceNode leftNode,
                SourceNode rightNode) {
            this.sources = sources;
            this.plan = plan;
            this.knownColumns = knownColumns;
            this.widthKnown = widthKnown;
            this.width = width;
            this.minWidth = minWidth;
            this.usingCount = usingCount;
            this.rightBoundary = rightBoundary;
            this.unknownLeftSources = unknownLeftSources;
            this.unknownRightSources = unknownRightSources;
            this.unknownSectionEnd = unknownSectionEnd;
            this.leftNode = leftNode;
            this.rightNode = rightNode;
        }
    }

    private static final class StarColumn {
        private final SourceTable source;
        private final String column;
        private final String key;
        private final boolean nameKnown;
        private final boolean quoted;
        /**
         * Position of this column inside its own source's output, or -1 when the plan
         * does not track it (for example a synthesized placeholder).
         */
        private final int index;

        private StarColumn(SourceTable source, String column, String key) {
            this(source, column, key, true, false);
        }

        private StarColumn(SourceTable source, String column, String key, boolean nameKnown,
                boolean quoted) {
            this(source, column, key, nameKnown, quoted, -1);
        }

        private StarColumn(SourceTable source, String column, String key, boolean nameKnown,
                boolean quoted, int index) {
            this.source = source;
            this.column = column;
            this.key = key;
            this.nameKnown = nameKnown;
            this.quoted = quoted;
            this.index = index;
        }
    }

    private static final class SessionState {
        private String instance;
        private String database;
        private List<String> schemas = new ArrayList<>();
        private List<String> rawSchemas = new ArrayList<>();
        private String rawDatabase;
        private boolean ansiEnabled;
        private boolean ansiDoubleQuotedIdentifiers;

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

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
    private Map<SourceTable, SourceNode> currentExpandedSourceNodes = new HashMap<>();

    private Set<String> tempTables;
    private Set<String> usedTargets;
    private Stack<Map<String, String>> cteScopes;
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
    private String sessionSchema;
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
    public void setLearnedMetadata(LearnedMetadata learnedMetadata) {
        this.learnedMetadata = learnedMetadata;
    }

    /**
     * Remember that a column was referenced on a table, so that later statements can
     * resolve an unqualified column against it.
     */
    private void learnColumn(SourceTable source, String columnName) {
        if (learnedMetadata == null || source == null || source.temporary
                || source.rawName == null || columnName == null) {
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
        this.sessionDatabase = session.database;
        List<String> rawSchemas = session.rawSchemas.isEmpty()
                ? session.schemas : session.rawSchemas;
        this.sessionSchema = rawSchemas.isEmpty() ? null
                : joinIdentifierKeys(rawSchemas);
        this.pendingLearned = new HashMap<>();
        try {
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
                values.add(item.toString().trim());
            }
        } else {
            values.add(value.toString().trim());
        }
        return values;
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

    static List<String> splitStatements(String sql, DbType dialect) {
        List<String> statements = new ArrayList<>();
        if (sql == null) {
            return statements;
        }
        boolean mysqlFamily = dialect == DbType.mysql || dialect == DbType.mariadb;
        boolean hashComments = mysqlFamily;
        boolean postgres = dialect == DbType.postgresql;
        boolean nestedComments = postgres;
        boolean bracketQuotes = dialect == DbType.sqlserver;

        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean escapeString = false;
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
                    if (next == quote) {
                        current.append(next);
                        i++;
                    } else {
                        quote = 0;
                        escapeString = false;
                    }
                }
                continue;
            }
            if (ch == '\'' || ch == '"' || ch == '`'
                    || (bracketQuotes && ch == '[')) {
                quote = ch == '[' ? ']' : ch;
                escapeString = mysqlFamily
                        ? (ch == '\'' || ch == '"')
                        : (ch == '\'' && isEscapeStringPrefix(sql, i));
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

    private void processWithSubQuery(SQLWithSubqueryClause withSubQuery) {
        if (withSubQuery == null) {
            return;
        }
        boolean recursive = Boolean.TRUE.equals(withSubQuery.getRecursive());
        for (SQLWithSubqueryClause.Entry entry : withSubQuery.getEntries()) {
            String alias = entry.getAlias();
            String target = alias != null
                    ? uniqueTarget(normalizeIdentifier(alias)) : getTempTableName();
            tempTables.add(target);

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

            // A non-recursive CTE body sees the outer binding, not itself; a recursive CTE
            // is visible inside its own definition.
            if (recursive && alias != null && !cteScopes.isEmpty()) {
                cteScopes.peek().put(identifierKey(alias), target);
            }
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
                scope.put(entry.getKey(), known && outputs != null ? outputs.size() : -1);
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
            for (SQLWithSubqueryClause.Entry entry : with.getEntries()) {
                String alias = entry.getAlias();
                if (alias == null) {
                    continue;
                }
                int declared = entry.getColumns() == null ? 0 : entry.getColumns().size();
                int width = staticSelectWidth(entry.getSubQuery(), scope);
                if (width < 0 && declared > 0 && exactCount) {
                    width = declared;
                }
                scope.put(identifierKey(alias), width);
            }
        }
        return staticUnionWidth(select.getQuery(), scope);
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
        SQLTableSource table = findTableSource(from, lastSegment(owner.toString()));
        return table == null ? -1 : staticTableWidth(table, scope);
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
        String rawName = extractTableName(table);
        if (!isSingleSegmentName(rawName)) {
            return -1;
        }
        String key = identifierKey(rawName);
        if (key != null && scope.containsKey(key)) {
            return scope.get(key);
        }
        String cteTarget = findCteTarget(key);
        if (cteTarget == null || !Boolean.TRUE.equals(targetWidthKnown.get(cteTarget))) {
            return -1;
        }
        List<String> outputs = targetOutputs.get(cteTarget);
        return outputs == null ? -1 : outputs.size();
    }

    private void processQueryBlock(SQLSelectQueryBlock queryBlock) {
        currentStarBudget = -1;
        currentExpandedSourceNodes = new HashMap<>();
        SQLTableSource fromItem = queryBlock.getFrom();
        if (fromItem == null) {
            int position = 0;
            int minWidth = 0;
            for (SQLSelectItem item : queryBlock.getSelectList()) {
                if (context != null || detachedMetadata()) {
                    validateUnboundQualified(item.getExpr());
                }
                position += recordUnboundSelectItem(item, position);
                minWidth += minimumOutputColumns(item.getExpr());
            }
            lastQueryBlockWidth = position;
            lastQueryBlockWidthKnown = true;
            lastQueryBlockNamesKnown = true;
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
        stackUsing.push(using);
        stackStarPlan.push(node.plan);
        stackSourceNode.push(node);
        stackSourceTable.push(sources.get(0).displayName);
        targetOutputs.computeIfAbsent(stackTargetTable.peek(), k -> new ArrayList<String>());

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
                    || detachedMetadata() && (unknownStar || unknownStarSeen) ? start : -1;
            if (unknownStar) {
                unknownStarSeen = true;
            }
            currentOrdinal = start;
            int advanced = processSelectItem(item);
            if (itemIndex == unknownItemIndex && inferredUnknownWidth > advanced) {
                advanced = inferredUnknownWidth;
            }
            position = start + advanced;
            minWidth += minimumOutputColumns(item.getExpr());
            boolean itemWidthKnown = outputWidthKnown(item);
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
        stackHiddenSources.pop();
        stackJoinBindings.pop();
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

    private int processSelectItem(SQLSelectItem item) {
        if (item.getExpr() == null) {
            return 0;
        }
        SQLExpr baseExpr = item.getExpr();
        while (baseExpr instanceof SQLCastExpr) {
            baseExpr = ((SQLCastExpr) baseExpr).getExpr();
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
            if (detachedMetadata()) {
                SourceTable source = findByOwner(owner);
                List<StarOutput> plan;
                if (source != null) {
                    plan = planFromSource(source);
                } else {
                    SourceNode binding = joinBinding(owner);
                    if (binding == null) {
                        throw new MetadataNotFoundException(owner == null
                                ? "*" : owner.toString());
                    }
                    plan = binding == null ? new ArrayList<StarOutput>() : binding.plan;
                }
                List<SourceTable> planSources = new ArrayList<>();
                if (source != null) {
                    planSources.add(source);
                } else {
                    SourceNode binding = joinBinding(owner);
                    if (binding != null) {
                        planSources.addAll(binding.sources);
                    }
                }
                SourceNode planNode = source != null ? null : joinBinding(owner);
                int width = emitDetachedPlan(targetTable, plan, planSources, planNode,
                        currentOrdinal);
                return Math.max(width, 1);
            }
            SourceTable source = findByOwner(owner);
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
            } else {
                SourceNode binding = joinBinding(owner);
                if (binding != null) {
                    recordBindingStar(targetTable, binding, currentOrdinal);
                }
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

    private boolean outputWidthKnown(SQLSelectItem item) {
        SQLExpr expr = item.getExpr();
        if (detachedMetadata() && hasUnknownStarSource(expr)) {
            return false;
        }
        if (expr instanceof SQLAllColumnExpr) {
            if (!stackSourceNode.isEmpty()) {
                return stackSourceNode.peek().widthKnown;
            }
            for (SourceTable source : currentSources()) {
                if (!sourceColumnsKnown(source)) {
                    return false;
                }
            }
            return true;
        }
        if (isStarProperty(expr)) {
            SQLExpr owner = ((SQLPropertyExpr) expr).getOwner();
            SourceTable source = findByOwner(owner);
            if (source != null) {
                return sourceColumnsKnown(source);
            }
            SourceNode binding = joinBinding(owner);
            return binding != null && binding.widthKnown;
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
            SourceNode right = buildSourceNode(join.getRight(), using);
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
                }
                if (!stackHiddenSources.isEmpty()) {
                    // An alias hides every qualifier inside its join subtree.
                    stackHiddenSources.peek().addAll(sources);
                }
            }
            return joined;
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
            SourceTable source = new SourceTable(target, null, null, alias, null, true,
                    widthKnown, namesKnown, derivedOutputs, copyKeys(target),
                    copyNameKnown(target, derivedOutputs.size()),
                    targetMinimumColumns(target, widthKnown, derivedOutputs.size()),
                    copyUnknownGroups(target, derivedOutputs.size()), false,
                    hasWildcardContribution(target));
            return new SourceNode(java.util.Collections.singletonList(source),
                    planFromSource(source), namesKnown, widthKnown, derivedOutputs.size(),
                    targetMinimumColumns(target, widthKnown, derivedOutputs.size()), 0,
                    derivedOutputs.size(),
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
            SourceTable source = new SourceTable(target, null, null, alias, null, true,
                    widthKnown, namesKnown, derivedOutputs, copyKeys(target),
                    copyNameKnown(target, derivedOutputs.size()),
                    targetMinimumColumns(target, widthKnown, derivedOutputs.size()),
                    copyUnknownGroups(target, derivedOutputs.size()), false,
                    hasWildcardContribution(target));
            return new SourceNode(java.util.Collections.singletonList(source),
                    planFromSource(source), namesKnown, widthKnown, derivedOutputs.size(),
                    targetMinimumColumns(target, widthKnown, derivedOutputs.size()), 0,
                    derivedOutputs.size(),
                    widthKnown ? java.util.Collections.<SourceTable>emptyList()
                            : java.util.Collections.singletonList(source),
                    java.util.Collections.<SourceTable>emptyList(),
                    unknownSectionEndFor(source, widthKnown, planSizeFor(source)));
        }
        if (tableSource instanceof SQLExprTableSource) {
            SourceTable source = resolveSource((SQLExprTableSource) tableSource);
            boolean widthKnown = sourceColumnsKnown(source);
            int width = source.metadataTable != null
                    ? source.metadataTable.getFields().size() : source.outputColumns.size();
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
        List<StarOutput> plan = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            String key = i < keys.size() ? keys.get(i) : identifierKey(names.get(i));
            boolean nameKnown = sourceOutputNameKnown(source, i);
            boolean quoted = source.metadataTable != null
                    && i < source.metadataTable.getFields().size()
                    && source.metadataTable.getFields().get(i).isQuoted();
            boolean stable = sourceColumnsKnown(source) || i < firstUnknownIndex(source);
            plan.add(new StarOutput(java.util.Collections.singletonList(
                    new StarColumn(source, names.get(i), key, nameKnown, quoted)),
                    nameKnown, stable));
        }
        return plan;
    }

    private List<String> sourceColumnKeys(SourceTable source) {
        if (source.metadataTable != null) {
            List<String> keys = new ArrayList<>();
            boolean mysql = currentDialect() == DbType.mysql
                    || currentDialect() == DbType.mariadb;
            for (Column column : source.metadataTable.getFields()) {
                keys.add(!mysql && column.isQuoted()
                        ? column.getColumnName() : identifierKey(column.getColumnName()));
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
        for (SQLExpr expr : using) {
            String key = identifierKey(expr.toString());
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
        return merged;
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
            columns.add(new StarColumn(column.source, name, key, certain, quoted));
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
                        ? canonicalPhysicalReference(rawName)
                        : lastIdentifier(written);
        String cteTarget = isSingleSegmentName(rawName)
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
        int minOutputColumns = metadataTable != null
                ? metadataTable.getFields().size()
                : targetMinimumColumns(cteTarget, widthKnown, outputColumns.size());
        return new SourceTable(display, written, rawName, sourceAlias, metadataTable, temporary,
                widthKnown, namesKnown, outputColumns, copyKeys(cteTarget),
                metadataTable != null ? java.util.Collections.<Boolean>emptyList()
                        : copyNameKnown(cteTarget, outputColumns.size()),
                minOutputColumns,
                metadataTable != null ? java.util.Collections.<SourceTable>emptyList()
                        : copyUnknownGroups(cteTarget, outputColumns.size()),
                false, hasWildcardContribution(cteTarget));
    }

    private int targetMinimumColumns(String target, boolean widthKnown, int outputCount) {
        if (widthKnown) {
            return outputCount;
        }
        Integer minimum = target == null ? null : targetMinWidth.get(target);
        return minimum == null ? 0 : minimum;
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
        return !outputs.isEmpty() && Boolean.TRUE.equals(targetWidthKnown.get(target));
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
            return missingTable(written);
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
            return name.toLowerCase();
        }
        if (dialect == DbType.postgresql) {
            return quoted ? name : name.toLowerCase();
        }
        return quoted ? name : name.toUpperCase();
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
        String rawName = identifier.getName();
        List<SourceTable> sources = resolveUnqualified(rawName);
        for (SourceTable source : sources) {
            learnColumn(source, rawName);
            addColumnMapping(source, rawName);
        }
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
            SourceTable source = findByOwner(property.getOwner());
            if (source == null) {
                SourceNode binding = joinBinding(property.getOwner());
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
        SourceTable source = findByOwner(property.getOwner());
        if (source == null) {
            SourceNode binding = joinBinding(property.getOwner());
            if (binding != null) {
                bindJoinProperty(binding, rawName, name);
                return;
            }
            if (context != null) {
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
        if (source.metadataTable == null && source.outputNamesKnown
                && !source.outputKeys.contains(identifierKey(rawName))) {
            throw new ColumnNotFoundException(name, displayNames(currentSources()));
        }
        learnColumn(source, rawName);
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
            instructions.add(wildcard);
            targetWildcardContribution.put(targetTable, Boolean.TRUE);
        }
        if (uncertainOrdinal >= 0 && currentOrdinal == uncertainOrdinal) {
            // The position is unknown: the table-level contribution above is enough.
            return;
        }
        for (StarColumn column : columns) {
            String sourceColumn = column.quoted || column.source.temporary
                    ? renderSourceColumn(column)
                    : renderIdentifier(canonicalColumnName(column.column));
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

        boolean determinate = !sources.isEmpty()
                && !(uncertainOrdinal >= 0 && uncertainOrdinal == startPosition);
        for (SourceTable source : sources) {
            if (!sourceNamesKnown(source)) {
                determinate = false;
                break;
            }
        }
        if (!determinate) {
            targetWildcardContribution.put(targetTable, Boolean.TRUE);
            // Contract: when any participating source's columns are unknown the
            // projection positions cannot be determined. Keep table-level edges only
            // and never guess a column.
            for (SourceTable source : sources) {
                Instruction wildcard = new Instruction(OperationType.COLUMNS_INJECT,
                        lineageTableName(source), targetTable, startPosition,
                        source.temporary, targetTemporary);
                wildcard.setPositionalWildcard(true);
                instructions.add(wildcard);
            }
            return Math.max(sources.size(), 1);
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
    private String canonicalColumnName(String raw) {
        if (raw == null) {
            return null;
        }
        String name = normalizeIdentifier(raw);
        if (!detachedMetadata() || MetadataCatalog.isQuotedIdentifier(raw)
                || currentDialect() != DbType.postgresql) {
            return name;
        }
        return name.toLowerCase();
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
                        ? name.toLowerCase() : name);
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

    private String foldName(String name) {
        DbType dialect = currentDialect();
        return dialect == DbType.mysql || dialect == DbType.mariadb
                || dialect == DbType.postgresql
                        ? name.toLowerCase() : name.toUpperCase();
    }

    private String lineageTableName(SourceTable source) {
        if (source.temporary || source.metadataTable != null) {
            return source.displayName;
        }
        if (source.rawName != null) {
            return canonicalPhysicalReference(source.rawName);
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
            return using.get(columnKey);
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
                    } else if (learnedMetadata != null
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
            if (!sourceColumnsKnown(source)) {
                return false;
            }
        }
        return true;
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
            if (source.metadataTable != null || source.temporary || source.rawName == null) {
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
        String resolved = insertColumnName(metadata, tableName, column);
        if (MetadataCatalog.isQuotedIdentifier(raw)) {
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
            instructions.add(wildcard);
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
            addColumnMapping(sourceName,
                    canonical == null ? normalizeIdentifier(columnName) : canonical,
                    source.temporary, null);
            return;
        }
        String mappedName = source.metadataTable == null
                ? canonicalColumnName(columnName) : columnName;
        addColumnMapping(sourceName, mappedName, source.temporary, source.metadataTable);
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
        SQLExprTableSource tableSource = insert.getTableSource();
        String rawName = extractTableName(tableSource);
        String written = normalizeQualifiedName(rawName);
        // The graph identity of an unaliased target keeps its written reference when
        // metadata is not available, so later reads resolve to the same vertex.
        String tableName = detachedMetadata() && context == null && rawName != null
                ? canonicalPhysicalReference(rawName) : written;
        Table targetMetadata = null;
        if (context != null && findCteTarget(identifierKey(rawName)) == null) {
            TableLocation location = resolveMetadataTable(rawName);
            if (location != null) {
                tableName = location.getDisplayName();
                targetMetadata = location.getTable();
            } else if (detachedMetadata() && rawName != null) {
                // The target table is missing from the catalog: keep its written
                // identity so the table-level edge matches later reads.
                tableName = canonicalPhysicalReference(rawName);
            }
        }
        registerTable(written, tableName, tableName, tableSource.getAlias() == null
                ? null : normalizeIdentifier(tableSource.getAlias()),
                findCteTarget(identifierKey(rawName)) != null);

        SQLWithSubqueryClause with = insert.getWith();
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

            SQLSelect select = insert.getQuery();
            if (select != null) {
                processSelect(select);
            }

            String fromTable = stackTargetTable.peek();
            boolean fromTemporary = tempTables.contains(fromTable);
            List<SQLExpr> columns = insert.getColumns();
            if (columns != null && !columns.isEmpty()) {
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

    private void processUpdate(SQLUpdateStatement update) {
        Map<String, List<SourceTable>> using = new LinkedHashMap<>();
        List<SourceTable> sources = new ArrayList<>();
        if (update.getTableSource() != null) {
            sources.addAll(buildSourceNode(update.getTableSource(), using).sources);
        }
        if (update.getFrom() != null) {
            sources.addAll(buildSourceNode(update.getFrom(), using).sources);
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
            stackUsing.pop();
            stackSources.pop();
            stackTargetTable.pop();
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
        tables = new HashSet<>();
        tempTables = new HashSet<>();
        usedTargets = new HashSet<>();
        cteScopes = new Stack<>();
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
        instructions = new ArrayList<>();
        stackSourceTable = new Stack<>();
        stackTargetTable = new Stack<>();
        stackTargetColumn = new Stack<>();
        stackSources = new Stack<>();
        stackUsing = new Stack<>();
        stackJoinBindings = new Stack<>();
        usingParticipantsByIdentity = new IdentityHashMap<>();
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
            this.sources = sources;
            this.nameKnown = nameKnown;
            this.stable = stable;
            this.origin = origin == null ? this : origin;
        }

        private StarOutput withStable(boolean value) {
            return new StarOutput(sources, nameKnown, value, origin);
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

        private StarColumn(SourceTable source, String column, String key) {
            this(source, column, key, true, false);
        }

        private StarColumn(SourceTable source, String column, String key, boolean nameKnown,
                boolean quoted) {
            this.source = source;
            this.column = column;
            this.key = key;
            this.nameKnown = nameKnown;
            this.quoted = quoted;
        }
    }

    private static final class SessionState {
        private String instance;
        private String database;
        private List<String> schemas = new ArrayList<>();
        private List<String> rawSchemas = new ArrayList<>();

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

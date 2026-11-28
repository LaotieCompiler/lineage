package com.laotie.validation;

import java.util.ArrayList;
import java.util.List;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.ast.*;
import com.alibaba.druid.sql.ast.expr.*;
import com.alibaba.druid.sql.ast.statement.*;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlInsertStatement;
import com.alibaba.druid.sql.dialect.oracle.ast.stmt.OracleSelectJoin;
import com.alibaba.druid.sql.dialect.oracle.ast.stmt.OracleSelectSubqueryTableSource;
import com.alibaba.druid.sql.dialect.oracle.ast.stmt.OracleSelectTableReference;
import com.alibaba.druid.sql.dialect.oracle.visitor.OracleASTVisitorAdapter;
import com.alibaba.druid.sql.dialect.postgresql.ast.stmt.PGInsertStatement;
import com.alibaba.druid.sql.dialect.sqlserver.visitor.SQLServerASTVisitorAdapter;
import com.alibaba.druid.sql.visitor.SQLASTVisitor;
import com.alibaba.druid.sql.visitor.SQLASTVisitorAdapter;

import com.laotie.dialect.DialectRules;
import com.laotie.exception.UnquotedIdentifierException;
import com.laotie.model.metadata.MetadataCatalog;
import com.laotie.parse.ScriptSplitter;
import com.laotie.parse.SqlParser;

/**
 * Strict quoted-identifier validation and Derby JDBC-escape annotation.
 */
public final class StrictQuotedIdentifierValidator {

    private StrictQuotedIdentifierValidator() {
    }

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
    private static final class EscapeUnit {
        private final String method;
        private final String unit;

        private EscapeUnit(String method, String unit) {
            this.method = method;
            this.unit = unit;
        }
    }

    public static void annotateJdbcEscapes(SQLStatement statement, String source,
            DbType dialect) {
        String marked = markJdbcEscapes(source);
        if (marked.equals(source)) {
            return;
        }
        List<String> markedMethods = new ArrayList<>();
        try {
            SQLStatement markedStatement =
                    SqlParser.parse(marked, dialect);
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
        while (i < sql.length() && ScriptSplitter.isIdentifierChar(sql.charAt(i))) {
            i++;
        }
        return new int[] {start, i};
    }

    /**
     * Strict quoted syntax: every table and column reference (and table alias /
     * CTE name, which act as qualifiers) must carry identifier quotes.
     */
    public static void validate(SQLStatement statement, DbType dialect) {
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
        DialectRules rules = DialectRules.of(dialect);
        String method = call.getMethodName();
        if (!rules.allowsUnitFunction(method)) {
            return false;
        }
        Object escape = call.getAttribute(JDBC_ESCAPE_ATTRIBUTE);
        boolean jdbcMarked = escape instanceof JdbcEscape
                && ((JdbcEscape) escape).signature.equals(jdbcSignature(call));
        String unit = MetadataCatalog.normalizeIdentifier(identifier.getName());
        if (unit == null) {
            return false;
        }
        boolean timestampCall = "TIMESTAMPADD".equalsIgnoreCase(method)
                || "TIMESTAMPDIFF".equalsIgnoreCase(method);
        if (rules.requiresJdbcTimestampUnit() && timestampCall) {
            return jdbcMarked && rules.isJdbcUnitKeyword(unit);
        }
        return rules.isJdbcUnitKeyword(unit)
                || rules.isUnitKeyword(unit);
    }

    private static boolean isStarProperty(SQLExpr expr) {
        if (!(expr instanceof SQLPropertyExpr)) {
            return false;
        }
        String rawName = ((SQLPropertyExpr) expr).getName();
        return "*".equals(MetadataCatalog.normalizeIdentifier(rawName))
                && !MetadataCatalog.isQuotedIdentifier(rawName);
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

}

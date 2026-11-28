package com.laotie.analysis;

import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.expr.SQLAggregateExpr;
import com.alibaba.druid.sql.ast.expr.SQLAllColumnExpr;
import com.alibaba.druid.sql.ast.expr.SQLBetweenExpr;
import com.alibaba.druid.sql.ast.expr.SQLBinaryOpExpr;
import com.alibaba.druid.sql.ast.expr.SQLCaseExpr;
import com.alibaba.druid.sql.ast.expr.SQLCastExpr;
import com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.druid.sql.ast.expr.SQLInListExpr;
import com.alibaba.druid.sql.ast.expr.SQLInSubQueryExpr;
import com.alibaba.druid.sql.ast.expr.SQLMethodInvokeExpr;
import com.alibaba.druid.sql.ast.expr.SQLNotExpr;
import com.alibaba.druid.sql.ast.expr.SQLPropertyExpr;
import com.alibaba.druid.sql.ast.expr.SQLQueryExpr;
import com.alibaba.druid.sql.ast.expr.SQLExtractExpr;
import com.alibaba.druid.sql.ast.expr.SQLUnaryExpr;
import com.alibaba.druid.sql.ast.expr.SQLValuableExpr;
import com.alibaba.druid.sql.ast.expr.SQLVariantRefExpr;
import com.laotie.model.Instruction;
import com.laotie.model.RelationType;

/**
 * Classifies how a projection expression derives a target column.
 *
 * <p>
 * The root of an expression gives the coarse relation of the whole projection; the
 * analyzers refine it per column while they walk nested operators and calls.
 * </p>
 */
public final class RelationClassifier {

    private RelationClassifier() {
    }

    /**
     * The relation implied by the outermost node of an expression.
     */
    public static RelationType rootRelation(SQLExpr expr) {
        if (expr == null) {
            return RelationType.NONE;
        }
        if (expr instanceof SQLIdentifierExpr || expr instanceof SQLPropertyExpr
                || expr instanceof SQLAllColumnExpr) {
            return RelationType.COLUMN_COPY;
        }
        if (expr instanceof SQLAggregateExpr) {
            return RelationType.AGGREGATE;
        }
        if (expr instanceof SQLMethodInvokeExpr || expr instanceof SQLExtractExpr) {
            return RelationType.FUNCTION;
        }
        if (expr instanceof SQLCastExpr) {
            return RelationType.CAST;
        }
        if (expr instanceof SQLCaseExpr) {
            return RelationType.CASE;
        }
        if (expr instanceof SQLQueryExpr) {
            return RelationType.SUBQUERY;
        }
        if (expr instanceof SQLValuableExpr || expr instanceof SQLVariantRefExpr) {
            return RelationType.CONSTANT;
        }
        return RelationType.EXPRESSION;
    }

    /**
     * The function named by the outermost call, if any.
     */
    public static String rootFunction(SQLExpr expr) {
        if (expr instanceof SQLMethodInvokeExpr) {
            return ((SQLMethodInvokeExpr) expr).getMethodName();
        }
        if (expr instanceof SQLExtractExpr) {
            return "EXTRACT";
        }
        return null;
    }

    /**
     * The operator named by the outermost expression, if any.
     */
    public static String rootOperator(SQLExpr expr) {
        if (expr instanceof SQLBinaryOpExpr) {
            SQLBinaryOpExpr binary = (SQLBinaryOpExpr) expr;
            return binary.getOperator() == null ? null : binary.getOperator().getName();
        }
        if (expr instanceof SQLUnaryExpr) {
            SQLUnaryExpr unary = (SQLUnaryExpr) expr;
            return unary.getOperator() == null ? null : unary.getOperator().name;
        }
        if (expr instanceof SQLNotExpr) {
            return "NOT";
        }
        if (expr instanceof SQLBetweenExpr) {
            return "BETWEEN";
        }
        if (expr instanceof SQLInListExpr || expr instanceof SQLInSubQueryExpr) {
            return "IN";
        }
        if (expr instanceof SQLCaseExpr) {
            return "CASE";
        }
        return null;
    }

    /**
     * Copy the analyzer's current expression context onto a newly created edge.
     * A {@link RelationType#NONE} context leaves the instruction's default in place.
     */
    public static void applyContext(Instruction instruction, AnalysisContext state) {
        if (instruction == null || state == null
                || state.relationType == null
                || state.relationType == RelationType.NONE) {
            return;
        }
        instruction.setRelation(state.relationType);
        instruction.setExpression(state.expressionText);
        instruction.setFunctionName(state.functionName);
        instruction.setOperator(state.operatorName);
    }

    /**
     * Classify a source-less or whole-expression instruction from its root node.
     */
    public static void applyRoot(Instruction instruction, SQLExpr expr) {
        if (instruction == null || expr == null) {
            return;
        }
        instruction.setRelation(rootRelation(expr));
        instruction.setExpression(compact(expr.toString()));
        instruction.setFunctionName(rootFunction(expr));
        instruction.setOperator(rootOperator(expr));
    }

    /**
     * Collapse whitespace outside string literals and quoted identifiers, so the
     * expression stays one line without changing the value of a literal.
     */
    public static String compact(String text) {
        if (text == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(text.length());
        char quote = 0;
        boolean pendingSpace = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quote != 0) {
                out.append(ch);
                if (ch == quote) {
                    if (i + 1 < text.length() && text.charAt(i + 1) == quote) {
                        out.append(text.charAt(++i));
                    } else {
                        quote = 0;
                    }
                } else if (ch == '\\' && i + 1 < text.length()) {
                    out.append(text.charAt(++i));
                }
                continue;
            }
            if (ch == '\'' || ch == '"' || ch == '`') {
                if (pendingSpace && out.length() > 0) {
                    out.append(' ');
                }
                pendingSpace = false;
                quote = ch;
                out.append(ch);
            } else if (Character.isWhitespace(ch)) {
                pendingSpace = true;
            } else {
                if (pendingSpace && out.length() > 0) {
                    out.append(' ');
                }
                pendingSpace = false;
                out.append(ch);
            }
        }
        return out.toString().trim();
    }
}

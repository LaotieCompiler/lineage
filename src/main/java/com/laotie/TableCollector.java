package com.laotie;

import java.util.HashSet;
import java.util.Set;

import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.expr.SQLBetweenExpr;
import com.alibaba.druid.sql.ast.expr.SQLBinaryOpExpr;
import com.alibaba.druid.sql.ast.expr.SQLExistsExpr;
import com.alibaba.druid.sql.ast.expr.SQLInListExpr;
import com.alibaba.druid.sql.ast.expr.SQLInSubQueryExpr;
import com.alibaba.druid.sql.ast.expr.SQLNotExpr;
import com.alibaba.druid.sql.ast.expr.SQLQueryExpr;
import com.alibaba.druid.sql.ast.expr.SQLUnaryExpr;
import com.alibaba.druid.sql.ast.statement.SQLExprTableSource;
import com.alibaba.druid.sql.ast.statement.SQLJoinTableSource;
import com.alibaba.druid.sql.ast.statement.SQLSelect;
import com.alibaba.druid.sql.ast.statement.SQLSelectQuery;
import com.alibaba.druid.sql.ast.statement.SQLSelectQueryBlock;
import com.alibaba.druid.sql.ast.statement.SQLSubqueryTableSource;
import com.alibaba.druid.sql.ast.statement.SQLTableSource;
import com.alibaba.druid.sql.ast.statement.SQLUnionQuery;
import com.alibaba.druid.sql.ast.statement.SQLUnionQueryTableSource;
import com.alibaba.druid.sql.ast.statement.SQLWithSubqueryClause;

/** Collects the physical tables referenced by a statement without analyzing columns. */
final class TableCollector {

    private final DmlLineage host;

    TableCollector(DmlLineage host) {
        this.host = host;
    }

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
            host.state().collectCteScopes.push(local);
            try {
                boolean recursive = Boolean.TRUE.equals(with.getRecursive());
                if (recursive) {
                    for (SQLWithSubqueryClause.Entry entry : with.getEntries()) {
                        if (entry.getAlias() != null) {
                            local.add(host.identifierKey(entry.getAlias()));
                        }
                    }
                }
                for (SQLWithSubqueryClause.Entry entry : with.getEntries()) {
                    collectTablesFromSelect(entry.getSubQuery());
                    if (!recursive && entry.getAlias() != null) {
                        local.add(host.identifierKey(entry.getAlias()));
                    }
                }
                collectTablesFromQuery(select.getQuery());
            } finally {
                host.state().collectCteScopes.pop();
            }
            return;
        }
        collectTablesFromQuery(select.getQuery());
    }

    private boolean collectCteNameVisible(String key) {
        for (Set<String> frame : host.state().collectCteScopes) {
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
            SQLSelect inline = DmlLineage.queryExpressionSelect(exprTable.getExpr());
            if (inline != null) {
                collectTablesFromSelect(inline);
                return;
            }
            String name = host.extractTableName(exprTable);
            if (name != null && DmlLineage.isSingleSegmentName(name)
                    && (collectCteNameVisible(host.identifierKey(name))
                            || host.findCteTarget(host.identifierKey(name)) != null)) {
                return;
            }
            host.resolveSource(exprTable);
        }
    }
}

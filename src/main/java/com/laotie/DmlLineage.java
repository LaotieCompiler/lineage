/*-
 * Author: Laotie
 * Licensed under Apache License 2.0
 * #L%
 */
package com.laotie;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Stack;
import java.util.stream.Collectors;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.expr.SQLAggregateExpr;
import com.alibaba.druid.sql.ast.expr.SQLAllColumnExpr;
import com.alibaba.druid.sql.ast.expr.SQLBetweenExpr;
import com.alibaba.druid.sql.ast.expr.SQLBinaryOpExpr;
import com.alibaba.druid.sql.ast.expr.SQLCaseExpr;
import com.alibaba.druid.sql.ast.expr.SQLCastExpr;
import com.alibaba.druid.sql.ast.expr.SQLExtractExpr;
import com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.druid.sql.ast.expr.SQLInListExpr;
import com.alibaba.druid.sql.ast.expr.SQLInSubQueryExpr;
import com.alibaba.druid.sql.ast.expr.SQLListExpr;
import com.alibaba.druid.sql.ast.expr.SQLMethodInvokeExpr;
import com.alibaba.druid.sql.ast.expr.SQLNotExpr;
import com.alibaba.druid.sql.ast.expr.SQLPropertyExpr;
import com.alibaba.druid.sql.ast.expr.SQLQueryExpr;
import com.alibaba.druid.sql.ast.expr.SQLUnaryExpr;
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
import com.alibaba.druid.sql.ast.statement.SQLSubqueryTableSource;
import com.alibaba.druid.sql.ast.statement.SQLTableSource;
import com.alibaba.druid.sql.ast.statement.SQLUnionQuery;
import com.alibaba.druid.sql.ast.statement.SQLUnionQueryTableSource;
import com.alibaba.druid.sql.ast.statement.SQLUpdateStatement;
import com.alibaba.druid.sql.ast.statement.SQLWithSubqueryClause;

import com.laotie.model.Instruction;
import com.laotie.model.Instruction.OperationType;

/**
 * Extract table and column level lineage from a SQL statement.
 *
 * <p>
 * The parser is backed by Alibaba Druid. The AST is traversed in post-order: nested queries and
 * derived tables are processed before the select list of the query that consumes them, so that the
 * instructions can be chained by {@link com.laotie.model.LineageGraph}.
 */
@SuppressWarnings({"PMD.CyclomaticComplexity", "PMD.UncommentedEmptyMethodBody"})
public class DmlLineage {

    private static final String NOT_SUPPORTED_YET = "Not supported yet.";

    private Set<String> tables;
    private List<Instruction> instructions; //to store the direct relationship between fields
    private Stack<String> stackTargetTable;
    private Stack<String> stackSourceTable;
    private Stack<String> stackTargetColumn;
    // TODO: 为了解决 insert 与 select 中列名对应的问题。未来支持元数据解析之后，可以去掉这个变量，交给元数据解析器处理。
    private boolean inTargetExpression = false;
    private int tempTableNum = 0;

    private List<String> otherItemNames;

    private final DbType dbType;

    public DmlLineage() {
        this(DbType.mysql);
    }

    public DmlLineage(DbType dbType) {
        this.dbType = dbType;
    }

    /**
     * Initializes table names collector. Important is the usage of Column instances to find table
     * names. This is only allowed for expression parsing, where a better place for tablenames could
     * not be there. For complete statements only from items are used to avoid some alias as
     * tablenames.
     *
     * @param allowColumnProcessing
     */
    protected void init(boolean allowColumnProcessing) {
        otherItemNames = new ArrayList<String>();
        tables = new HashSet<>();
        instructions = new ArrayList<>();
        stackSourceTable = new Stack<>();
        stackTargetTable = new Stack<>();
        stackTargetColumn = new Stack<>();
        inTargetExpression = false;
    }

    public String getTempTableName() {
        return String.format("temp%d", tempTableNum++);
    }

    public List<Instruction> getLineage(SQLStatement statement) {
        init(true);
        stackTargetTable.push(getTempTableName());
        processStatement(statement);
        stackTargetTable.pop();
        return instructions;
    }

    public List<Instruction> getLineage(String sqlStr) {
        return getLineage(parse(sqlStr));
    }

    /**
     * Parse a single SQL statement with the configured dialect.
     */
    public SQLStatement parse(String sqlStr) {
        return SQLUtils.parseSingleStatement(sqlStr, dbType);
    }

    public Set<String> getTables() {
        return tables;
    }

    public Set<String> getTables(SQLExpr expr) {
        init(true);
        visitExpr(expr);
        return tables;
    }

    public static Set<String> findTablesInExpression(String exprStr) {
        DmlLineage tablesNamesFinder = new DmlLineage();
        return tablesNamesFinder.getTables(SQLUtils.toSQLExpr(exprStr, DbType.mysql));
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
        // VALUES and other queries do not contribute column lineage.
    }

    /**
     * 查询 SELECT 语句的血缘信息
     *
     * 采用后根法遍历语法树，先处理内层 FROM 语句（子节点），再处理 SELECT 语句（根节点）
     */
    private void processQueryBlock(SQLSelectQueryBlock queryBlock) {
        SQLTableSource fromItem = queryBlock.getFrom();
        // 无 FROM 子句时没有可追踪的源表（常量查询）
        if (fromItem == null) {
            return;
        }

        String fromAlias = processTableSource(fromItem);

        stackSourceTable.push(fromAlias);
        if (queryBlock.getSelectList() != null) {
            for (SQLSelectItem item : queryBlock.getSelectList()) {
                processSelectItem(item);
            }
        }
        stackSourceTable.pop();
    }

    private void processSelectItem(SQLSelectItem item) {
        if (item.getExpr() == null) {
            return;
        }
        String targetColumn = item.getAlias() != null ? item.getAlias() : item.getExpr().toString();

        stackTargetColumn.push(targetColumn);
        int insCountBefore = instructions.size();
        inTargetExpression = true;
        visitExpr(item.getExpr());
        inTargetExpression = false;
        int insCountAfter = instructions.size();
        if (insCountAfter == insCountBefore) {
            instructions.add(new Instruction(OperationType.EXPR_MAPPING, item.getExpr().toString(),
                    stackTargetTable.peek() + "." + targetColumn));
        }
        stackTargetColumn.pop();
    }

    /* ------------------------------------------------------------------ */
    /* FROM / JOIN                                                        */
    /* ------------------------------------------------------------------ */

    /**
     * Process a table source tree (post-order) and return the alias that unqualified columns of the
     * enclosing query block resolve to. For a join this is the alias of its left-most base table.
     */
    private String processTableSource(SQLTableSource tableSource) {
        if (tableSource == null) {
            return null;
        }
        if (tableSource instanceof SQLJoinTableSource) {
            SQLJoinTableSource join = (SQLJoinTableSource) tableSource;
            String baseAlias = processTableSource(join.getLeft());
            processTableSource(join.getRight());
            return baseAlias;
        }
        if (tableSource instanceof SQLSubqueryTableSource) {
            SQLSubqueryTableSource subquery = (SQLSubqueryTableSource) tableSource;
            String targetTable = subquery.getAlias();
            if (targetTable == null) {
                targetTable = getTempTableName();
                subquery.setAlias(targetTable);
            }
            stackTargetTable.push(targetTable);
            processSelect(subquery.getSelect());
            stackTargetTable.pop();
            return targetTable;
        }
        if (tableSource instanceof SQLUnionQueryTableSource) {
            SQLUnionQueryTableSource union = (SQLUnionQueryTableSource) tableSource;
            String targetTable = union.getAlias();
            if (targetTable == null) {
                targetTable = getTempTableName();
                union.setAlias(targetTable);
            }
            stackTargetTable.push(targetTable);
            processSelectQuery(union.getUnion());
            stackTargetTable.pop();
            return targetTable;
        }
        if (tableSource instanceof SQLExprTableSource) {
            SQLExprTableSource table = (SQLExprTableSource) tableSource;
            String tableWholeName = extractTableName(table);
            String alias = table.getAlias();
            if (!otherItemNames.contains(tableWholeName.toLowerCase())
                    && !tables.contains(tableWholeName)) {
                tables.add(tableWholeName);
                if (alias != null && !alias.isEmpty()) {
                    instructions.add(new Instruction(OperationType.TABLE_MAPPING, tableWholeName, alias));
                }
            }
            return alias != null && !alias.isEmpty() ? alias : lastIdentifier(tableWholeName);
        }
        return null;
    }

    /**
     * Override to adapt the tableName generation (e.g. with / without schema).
     */
    protected String extractTableName(SQLExprTableSource table) {
        SQLExpr expr = table.getExpr();
        if (expr != null) {
            return expr.toString();
        }
        return table.getTableName();
    }

    /* ------------------------------------------------------------------ */
    /* INSERT / UPDATE / DELETE / CREATE                                  */
    /* ------------------------------------------------------------------ */

    private void processInsert(SQLInsertStatement insert) {
        SQLExprTableSource tableSource = insert.getTableSource();
        String tableName = extractTableName(tableSource);
        registerTable(tableName, tableSource.getAlias());

        processWithSubQuery(insert.getWith());

        stackTargetTable.push(getTempTableName());

        SQLSelect select = insert.getQuery();
        if (select != null) {
            processSelect(select);
        }

        List<SQLExpr> columns = insert.getColumns();
        if (columns != null && !columns.isEmpty()) {
            String colStr = columns.stream()
                    .map(this::columnName)
                    .collect(Collectors.joining(","));
            instructions.add(new Instruction(OperationType.COLUMNS_INJECT, stackTargetTable.peek(),
                    String.format("%s(%s)", tableName, colStr)));
        } else {
            instructions.add(new Instruction(OperationType.COLUMNS_INJECT, stackTargetTable.peek(), tableName));
        }
        stackTargetTable.pop();
    }

    private void processUpdate(SQLUpdateStatement update) {
        processTableSource(update.getTableSource());
        if (update.getFrom() != null) {
            processTableSource(update.getFrom());
        }
    }

    private void processDelete(SQLDeleteStatement delete) {
        processTableSource(delete.getTableSource());
        if (delete.getFrom() != null) {
            processTableSource(delete.getFrom());
        }
    }

    private void processCreateTable(SQLCreateTableStatement create) {
        SQLExprTableSource tableSource = create.getTableSource();
        if (tableSource != null) {
            registerTable(extractTableName(tableSource), tableSource.getAlias());
        }
        if (create.getSelect() != null) {
            processSelect(create.getSelect());
        }
    }

    private void registerTable(String tableWholeName, String alias) {
        if (tableWholeName == null) {
            return;
        }
        if (!otherItemNames.contains(tableWholeName.toLowerCase()) && !tables.contains(tableWholeName)) {
            tables.add(tableWholeName);
            if (alias != null && !alias.isEmpty()) {
                instructions.add(new Instruction(OperationType.TABLE_MAPPING, tableWholeName, alias));
            }
        }
    }

    private String columnName(SQLExpr column) {
        if (column instanceof SQLIdentifierExpr) {
            return ((SQLIdentifierExpr) column).getName();
        }
        return column.toString();
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
            // covers both regular functions and aggregates (max, count, now, ...)
            visitMethodInvoke((SQLMethodInvokeExpr) expr);
        } else if (expr instanceof SQLCastExpr) {
            visitExpr(((SQLCastExpr) expr).getExpr());
        } else if (expr instanceof SQLCaseExpr) {
            visitCase((SQLCaseExpr) expr);
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
        } else if (expr instanceof SQLQueryExpr) {
            // scalar subquery nested in a select item; its lineage is not tracked here
        }
        // literals, parameters and other terminal expressions carry no lineage
    }

    private void visitIdentifier(SQLIdentifierExpr identifier) {
        if (!inTargetExpression) {
            return;
        }
        if (stackSourceTable.isEmpty()) {
            return;
        }
        addColumnMapping(stackSourceTable.peek(), identifier.getName());
    }

    private void visitProperty(SQLPropertyExpr property) {
        if ("*".equals(property.getName())) {
            if (!inTargetExpression) {
                return;
            }
            String fromTable = property.getOwner() != null
                    ? lastIdentifier(property.getOwner().toString())
                    : stackSourceTable.peek();
            addColumnInjection(fromTable);
            return;
        }

        if (!inTargetExpression) {
            return;
        }
        String fromTable = stackSourceTable.isEmpty() ? null : stackSourceTable.peek();
        if (property.getOwner() != null) {
            fromTable = lastIdentifier(property.getOwner().toString());
        }
        addColumnMapping(fromTable, property.getName());
    }

    private void visitAllColumn() {
        if (!inTargetExpression) {
            return;
        }
        addColumnInjection(stackSourceTable.peek());
    }

    private void visitMethodInvoke(SQLMethodInvokeExpr method) {
        List<SQLExpr> arguments = method.getArguments();
        if (arguments != null) {
            for (SQLExpr argument : arguments) {
                visitExpr(argument);
            }
        }
        if (method instanceof SQLAggregateExpr) {
            SQLExpr filter = ((SQLAggregateExpr) method).getFilter();
            if (filter != null) {
                visitExpr(filter);
            }
        }
    }

    private void visitCase(SQLCaseExpr caseExpr) {
        if (caseExpr.getValueExpr() != null) {
            visitExpr(caseExpr.getValueExpr());
        }
        if (caseExpr.getItems() != null) {
            for (SQLCaseExpr.Item item : caseExpr.getItems()) {
                if (item.getConditionExpr() != null) {
                    visitExpr(item.getConditionExpr());
                }
                if (item.getValueExpr() != null) {
                    visitExpr(item.getValueExpr());
                }
            }
        }
        if (caseExpr.getElseExpr() != null) {
            visitExpr(caseExpr.getElseExpr());
        }
    }

    private void addColumnMapping(String fromTable, String columnName) {
        String toTable = stackTargetTable.peek();
        String toColumn = stackTargetColumn.peek();
        instructions.add(
                new Instruction(OperationType.COLUMN_MAPPING, fromTable + "." + columnName,
                        toTable + "." + toColumn));
    }

    private void addColumnInjection(String fromTable) {
        String toTable = stackTargetTable.peek();
        instructions.add(new Instruction(OperationType.COLUMNS_INJECT, fromTable, toTable));
    }

    private static String lastIdentifier(String name) {
        if (name == null) {
            return null;
        }
        String value = name.trim();
        int idx = value.lastIndexOf('.');
        if (idx >= 0) {
            value = value.substring(idx + 1);
        }
        return value;
    }
}

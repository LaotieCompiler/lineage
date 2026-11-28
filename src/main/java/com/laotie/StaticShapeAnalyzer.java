package com.laotie;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.ast.*;
import com.alibaba.druid.sql.ast.expr.*;
import com.alibaba.druid.sql.ast.statement.*;

import com.laotie.ast.SqlAst;
import com.laotie.model.metadata.Column;
import com.laotie.model.metadata.MetadataCatalog;
import com.laotie.model.metadata.TableLocation;

/** Static width/name inference for query blocks and table sources. */
final class StaticShapeAnalyzer {

    private final DmlLineage host;

    StaticShapeAnalyzer(DmlLineage host) {
        this.host = host;
    }

    Map<String, Integer> currentCteWidthScope() {
        Map<String, Integer> scope = new HashMap<>();
        for (Map<String, String> frame : host.state().cteScopes) {
            for (Map.Entry<String, String> entry : frame.entrySet()) {
                String target = entry.getValue();
                List<String> outputs = target == null ? null : host.state().targetOutputs.get(target);
                boolean known = target != null
                        && Boolean.TRUE.equals(host.state().targetWidthKnown.get(target));
                int width = outputs == null ? 0 : outputs.size();
                if (known) {
                    Integer min = host.state().targetMinWidth.get(target);
                    if (min != null && min > 0) {
                        width = Math.max(width, min);
                    }
                }
                scope.put(entry.getKey(), known ? width : -1);
            }
        }
        return scope;
    }

    int staticSelectWidth(SQLSelect select, Map<String, Integer> outerScope) {
        if (select == null) {
            return -1;
        }
        Map<String, Integer> scope = new HashMap<>(outerScope);
        SQLWithSubqueryClause with = select.getWithSubQuery();
        if (with != null) {
            DbType dialect = host.currentDialect();
            boolean exactCount = dialect == DbType.mysql || dialect == DbType.mariadb;
            boolean recursive = Boolean.TRUE.equals(with.getRecursive());
            if (recursive) {
                // WITH RECURSIVE binds every sibling before any body runs, including
                // forward references.
                for (SQLWithSubqueryClause.Entry entry : with.getEntries()) {
                    String alias = entry.getAlias();
                    if (alias != null) {
                        scope.put(host.identifierKey(alias), -1);
                    }
                }
            }
            for (SQLWithSubqueryClause.Entry entry : with.getEntries()) {
                String alias = entry.getAlias();
                if (alias == null) {
                    continue;
                }
                String aliasKey = host.identifierKey(alias);
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
                        String key = host.identifierKey(alias);
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

    static final String CONSTANT_OUTPUT_PREFIX = "\u0000constant:";
    static final String CONSTANT_EXPRESSION = "\u0000constant-expression";

    /**
     * Column names a query will produce when they can be derived without analyzing
     * rows, used to pre-bind forward-referenced recursive CTEs.
     */
    private static boolean isStarProperty(SQLExpr expr) {
        return SqlAst.isStarProperty(expr);
    }

    List<String> staticOutputNames(SQLSelect select,
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
                        nested.put(host.identifierKey(alias), null);
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
                        String key = host.identifierKey(alias);
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
                    nested.put(host.identifierKey(alias), mergeDeclaredColumns(
                            rawDeclaredColumns(entry),
                            staticOutputNames(entry.getSubQuery(), nested)));
                }
            }
        }
        return staticOutputNames(select.getQuery(), nested);
    }

    static List<String> mergeDeclaredColumns(List<String> declared,
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

    List<String> padToStaticWidth(List<String> declared, SQLSelect select,
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

    static List<String> rawDeclaredColumns(SQLWithSubqueryClause.Entry entry) {
        if (entry.getColumns() == null || entry.getColumns().isEmpty()) {
            return null;
        }
        List<String> names = new ArrayList<>();
        for (com.alibaba.druid.sql.ast.SQLName column : entry.getColumns()) {
            names.add(column.toString());
        }
        return names;
    }

    static String unescapeQuotedName(String raw) {
        return raw.replace("\"\"", "\"").replace("\\\"", "\"")
                .replace("``", "`");
    }

    static String rawProjectionName(SQLPropertyExpr expr) {
        String[] parts = MetadataCatalog.splitQualifiedNamePreservingQuotes(
                expr.toString());
        return parts.length == 0 ? expr.getName() : parts[parts.length - 1];
    }

    static String rawAlias(SQLSelectItem item) {
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

    List<String> staticOutputNames(SQLSelectQuery query,
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
                    SQLTableSource table = host.isSingleSegmentName(owner)
                            ? findTableSource(block.getFrom(), lastSegment(owner))
                            : findQualifiedTable(block.getFrom(), host.qualifiedKey(owner),
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

    List<String> staticFromNames(SQLTableSource from,
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
                    usingKeys.add(host.identifierKey(expr.toString()));
                }
                for (String name : left) {
                    if (name == null || !usingKeys.contains(host.identifierKey(name))) {
                        names.add(name);
                    }
                }
                for (String name : right) {
                    if (name == null || !usingKeys.contains(host.identifierKey(name))) {
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

    String quoteCatalogName(String name) {
        DbType dialect = host.currentDialect();
        if (dialect == DbType.mysql || dialect == DbType.mariadb) {
            return "`" + name + "`";
        }
        return "\"" + name + "\"";
    }

    List<String> staticTableNames(SQLTableSource table,
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

    static SQLSelect queryExpressionSelect(SQLExpr expr) {
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

    boolean isLateralSource(SQLTableSource source) {
        if (!(source instanceof SQLExprTableSource)) {
            return false;
        }
        SQLExpr expr = ((SQLExprTableSource) source).getExpr();
        return expr instanceof SQLMethodInvokeExpr
                && "LATERAL".equalsIgnoreCase(
                        ((SQLMethodInvokeExpr) expr).getMethodName());
    }

    List<String> staticSourceNames(SQLExprTableSource table,
            Map<String, List<String>> scope) {
        SQLSelect inline = queryExpressionSelect(table.getExpr());
        if (inline != null) {
            return staticOutputNames(inline, scope);
        }
        if (!DmlLineage.isPhysicalTableExpression(table)) {
            // A table function's call text is not a relation name and its output
            // names are unknown.
            return null;
        }
        String name = host.extractTableName(table);
        if (name == null) {
            return null;
        }
        if (host.isSingleSegmentName(name)) {
            String key = host.identifierKey(name);
            if (scope != null && scope.containsKey(key)) {
                return scope.get(key);
            }
            if (host.findCteTarget(key) != null) {
                return null;
            }
        }
        if (host.analysisContext() != null) {
            TableLocation location = host.resolveMetadataTable(name);
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

    int staticUnionWidth(SQLSelectQuery query, Map<String, Integer> scope) {
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
    int staticStarWidth(SQLTableSource from, SQLExpr owner,
            Map<String, Integer> scope) {
        if (owner == null) {
            return staticFromWidth(from, scope);
        }
        String ownerName = owner.toString();
        SQLTableSource table;
        if (host.isSingleSegmentName(ownerName)) {
            table = findTableSource(from, lastSegment(ownerName));
        } else {
            // A qualified owner must match the whole reference; taking the last
            // segment could select a same-named table from another schema.
            table = findQualifiedTable(from, host.qualifiedKey(ownerName), false, scope);
        }
        return table == null ? -1 : staticTableWidth(table, scope);
    }

    SQLTableSource findQualifiedTable(SQLTableSource from, String key,
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
            if (!DmlLineage.isPhysicalTableExpression(table)) {
                // A qualified owner never refers to a table function.
                return null;
            }
            String name = host.extractTableName(table);
            if (name == null) {
                return null;
            }
            String leafKey = host.isSingleSegmentName(name) ? host.identifierKey(name) : null;
            if (leafKey != null && (scope != null && scope.containsKey(leafKey)
                    || host.findCteTarget(leafKey) != null)) {
                // The leaf binds to a CTE (possibly a nested one registered in the
                // static scope); a qualified owner never refers to it and the catalog
                // must not be consulted for the CTE's name.
                return null;
            }
            // Compare the bound identity, which honors search_path and per-segment
            // quoting, and keep the written spelling for quoted names whose display
            // form drops the quotes.
            String bound = name;
            if (host.analysisContext() != null) {
                TableLocation location = host.resolveMetadataTable(name);
                if (location != null) {
                    bound = location.getDisplayName();
                }
            }
            return key.equals(host.qualifiedKey(bound)) || key.equals(host.qualifiedKey(name))
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

    int staticTableWidth(SQLTableSource table, Map<String, Integer> scope) {
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

    static String lastSegment(String qualifiedName) {
        String[] parts = MetadataCatalog.splitQualifiedNamePreservingQuotes(qualifiedName);
        return parts.length == 0 ? qualifiedName : parts[parts.length - 1];
    }

    int staticFromWidth(SQLTableSource from, Map<String, Integer> scope) {
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

    SQLTableSource findTableSource(SQLTableSource from, String owner) {
        if (from == null || owner == null) {
            return null;
        }
        String key = host.identifierKey(owner);
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

    SQLTableSource findLeafByAlias(SQLTableSource from, String key, boolean hidden) {
        if (from == null || hidden) {
            return null;
        }
        if (from instanceof SQLExprTableSource) {
            String alias = ((SQLExprTableSource) from).getAlias();
            return alias != null && key.equals(host.identifierKey(alias)) ? from : null;
        }
        if (from instanceof SQLSubqueryTableSource) {
            String alias = ((SQLSubqueryTableSource) from).getAlias();
            return alias != null && key.equals(host.identifierKey(alias)) ? from : null;
        }
        if (from instanceof SQLUnionQueryTableSource) {
            String alias = ((SQLUnionQueryTableSource) from).getAlias();
            return alias != null && key.equals(host.identifierKey(alias)) ? from : null;
        }
        if (from instanceof SQLJoinTableSource) {
            SQLJoinTableSource join = (SQLJoinTableSource) from;
            boolean childHidden = join.getAlias() != null;
            SQLTableSource found = findLeafByAlias(join.getLeft(), key, childHidden);
            return found != null ? found : findLeafByAlias(join.getRight(), key, childHidden);
        }
        return null;
    }

    SQLTableSource findJoinByAlias(SQLTableSource from, String key, boolean hidden) {
        if (!(from instanceof SQLJoinTableSource) || hidden) {
            return null;
        }
        SQLJoinTableSource join = (SQLJoinTableSource) from;
        if (join.getAlias() != null) {
            return key.equals(host.identifierKey(join.getAlias())) ? join : null;
        }
        SQLTableSource found = findJoinByAlias(join.getLeft(), key, false);
        return found != null ? found : findJoinByAlias(join.getRight(), key, false);
    }

    SQLTableSource findUnaliasedByName(SQLTableSource from, String key,
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
            return key.equals(host.identifierKey(lastSegment(host.extractTableName(table))))
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

    int staticSourceWidth(SQLExprTableSource table, Map<String, Integer> scope) {
        SQLSelect inline = queryExpressionSelect(table.getExpr());
        if (inline != null) {
            return staticSelectWidth(inline, scope);
        }
        if (!DmlLineage.isPhysicalTableExpression(table)) {
            // A table function's call text is not a relation name and its width is
            // unknown.
            return -1;
        }
        String rawName = host.extractTableName(table);
        if (rawName == null) {
            return -1;
        }
        if (host.isSingleSegmentName(rawName)) {
            String key = host.identifierKey(rawName);
            if (key != null && scope.containsKey(key)) {
                return scope.get(key);
            }
            String cteTarget = host.findCteTarget(key);
            if (cteTarget != null) {
                // A CTE shadows any catalog table with the same name.
                if (!Boolean.TRUE.equals(host.state().targetWidthKnown.get(cteTarget))) {
                    return -1;
                }
                List<String> outputs = host.state().targetOutputs.get(cteTarget);
                int width = outputs == null ? 0 : outputs.size();
                Integer min = host.state().targetMinWidth.get(cteTarget);
                if (min != null && min > 0) {
                    width = Math.max(width, min);
                }
                return width > 0 ? width : -1;
            }
        }
        if (host.analysisContext() != null) {
            TableLocation location = host.resolveMetadataTable(rawName);
            if (location != null) {
                return Math.max(location.getTable().getFields().size(), 1);
            }
        }
        return -1;
    }

}

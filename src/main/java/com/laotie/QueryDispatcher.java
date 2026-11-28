package com.laotie;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.alibaba.druid.sql.ast.SQLExpr;
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

/** Dispatches SELECT, UNION and WITH analysis and their static shape scopes. */
final class QueryDispatcher {

    private final DmlLineage host;
    private final StaticShapeAnalyzer shape;

    QueryDispatcher(DmlLineage host) {
        this.host = host;
        this.shape = new StaticShapeAnalyzer(host);
    }

    void processSelect(SQLSelect select) {
        SQLWithSubqueryClause with = select.getWithSubQuery();
        if (with == null) {
            processSelectQuery(select.getQuery());
            return;
        }
        host.state().cteScopes.push(new HashMap<String, String>());
        try {
            processWithSubQuery(with);
            processSelectQuery(select.getQuery());
        } finally {
            host.state().cteScopes.pop();
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
                    keys.add(host.identifierKey(other.getAlias()));
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
                                && key.equals(host.identifierKey(
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
                    local.add(host.identifierKey(entry.getAlias()));
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
                        bodyScope.add(host.identifierKey(entry.getAlias()));
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
            String name = host.extractTableName(exprTable);
            if (name != null && DmlLineage.isSingleSegmentName(name)
                    && DmlLineage.isPhysicalTableExpression(exprTable)) {
                String key = host.identifierKey(name);
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
                String target = host.uniqueTarget(DmlLineage.normalizeIdentifier(alias));
                host.state().tempTables.add(target);
                prebound.put(entry, target);
                if (!host.state().cteScopes.isEmpty()) {
                    host.state().cteScopes.peek().put(host.identifierKey(alias), target);
                }
            }
            // A sibling's columns are visible to earlier bodies too, so derive the
            // names that can be known statically before any body is analyzed.
            Map<String, List<String>> nameScope = new HashMap<>();
            Map<String, Integer> widthScope = new HashMap<>();
            for (SQLWithSubqueryClause.Entry entry : withSubQuery.getEntries()) {
                if (entry.getAlias() != null) {
                    String key = host.identifierKey(entry.getAlias());
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
                    String key = host.identifierKey(alias);
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
                        String key = host.identifierKey(alias);
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
                        : nameScope.get(host.identifierKey(alias));
                String target = prebound.get(entry);
                if (names != null && target != null) {
                    boolean widthKnown = Boolean.TRUE.equals(
                            widthCertain.get(host.identifierKey(alias)));
                    Set<String> usedKeys = new HashSet<>();
                    for (String raw : names) {
                        if (raw != null) {
                            usedKeys.add(host.identifierKey(raw));
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
                            while (usedKeys.contains(host.identifierKey(display))) {
                                display = display + "_";
                            }
                            usedKeys.add(host.identifierKey(display));
                            key = StaticShapeAnalyzer.CONSTANT_OUTPUT_PREFIX + "unknown:" + i;
                            namesKnown = false;
                        } else if (StaticShapeAnalyzer.CONSTANT_EXPRESSION.equals(raw)) {
                            display = "col" + (i + 1);
                            while (usedKeys.contains(host.identifierKey(display))) {
                                display = display + "_";
                            }
                            usedKeys.add(host.identifierKey(display));
                            key = StaticShapeAnalyzer.CONSTANT_OUTPUT_PREFIX + i;
                        } else {
                            String spelling = unescapeQuotedName(raw);
                            display = host.canonicalIdentifier(spelling);
                            key = host.identifierKey(spelling);
                        }
                        host.setTargetOutput(target, i, display, key);
                        if (raw != null) {
                            host.markOutputNameKnown(target, i, true);
                        }
                    }
                    // Make the sibling's shape visible before its body is analyzed;
                    // recordTargetShape overwrites this with the analyzed result.
                    host.state().targetWidthKnown.put(target, widthKnown);
                    host.state().targetNamesKnown.put(target, widthKnown && namesKnown);
                    host.state().targetMinWidth.put(target, names.size());
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
                            ? host.uniqueTarget(DmlLineage.normalizeIdentifier(alias))
                            : host.getTempTableName();
            if (!prebound.containsKey(entry)) {
                host.state().tempTables.add(target);
            }

            List<String> declaredColumns = new ArrayList<>();
            List<String> declaredKeys = new ArrayList<>();
            if (entry.getColumns() != null) {
                for (com.alibaba.druid.sql.ast.SQLName column : entry.getColumns()) {
                    String raw = column.toString();
                    declaredColumns.add(host.canonicalIdentifier(raw));
                    declaredKeys.add(host.identifierKey(raw));
                }
            }
            Map<String, List<String>> aliases = new HashMap<>();
            if (!declaredColumns.isEmpty()) {
                aliases.put(target, declaredColumns);
                host.state().cteDeclaredColumns.put(target, declaredColumns);
                host.state().cteDeclaredKeys.put(target, declaredKeys);
            }
            host.state().targetColumnAliases.push(aliases);

            try {
                host.state().stackTargetTable.push(target);
                processSelect(entry.getSubQuery());
                host.state().stackTargetTable.pop();
                host.recordTargetShape(target);
            } finally {
                host.state().targetColumnAliases.pop();
            }
            if (!recursive && alias != null && !host.state().cteScopes.isEmpty()) {
                host.state().cteScopes.peek().put(host.identifierKey(alias), target);
            }
        }
    }

    void processSelectQuery(SQLSelectQuery query) {
        if (query instanceof SQLSelectQueryBlock) {
            host.processQueryBlock((SQLSelectQueryBlock) query);
        } else if (query instanceof SQLUnionQuery) {
            SQLUnionQuery union = (SQLUnionQuery) query;
            String target = host.state().stackTargetTable.peek();
            boolean added = host.state().unionTargets.add(target);
            Integer previousOverride = host.state().targetWidthOverride.get(target);
            int staticWidth = staticUnionWidth(union, currentCteWidthScope());
            if (staticWidth > 0) {
                host.state().targetWidthOverride.put(target, staticWidth);
            }
            try {
                processSelectQuery(union.getLeft());
                int leftWidth = host.state().lastQueryBlockWidth;
                boolean leftKnown = host.state().lastQueryBlockWidthKnown;
                boolean leftNamesKnown = host.state().lastQueryBlockNamesKnown;
                int leftMinWidth = host.state().lastQueryBlockMinWidth;
                Integer overrideBeforeRight = host.state().targetWidthOverride.get(target);
                if (leftKnown && leftWidth > 0) {
                    host.state().targetWidthOverride.put(target, leftWidth);
                }
                host.incrementNonAuthoritative(target);
                try {
                    processSelectQuery(union.getRight());
                } finally {
                    host.decrementNonAuthoritative(target);
                    if (overrideBeforeRight == null) {
                        host.state().targetWidthOverride.remove(target);
                    } else {
                        host.state().targetWidthOverride.put(target, overrideBeforeRight);
                    }
                }
                int rightWidth = host.state().lastQueryBlockWidth;
                boolean rightKnown = host.state().lastQueryBlockWidthKnown;
                int rightMinWidth = host.state().lastQueryBlockMinWidth;
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
                host.state().lastQueryBlockWidth = leftKnown ? leftWidth : rightWidth;
                host.state().lastQueryBlockWidthKnown = leftKnown || rightKnown;
                // A UNION takes its output names from the first branch, even when the
                // width is established by another branch.
                host.state().lastQueryBlockNamesKnown = leftNamesKnown;
                host.state().lastQueryBlockMinWidth = Math.max(leftMinWidth, host.state().lastQueryBlockMinWidth);
            } finally {
                if (previousOverride == null) {
                    host.state().targetWidthOverride.remove(target);
                } else {
                    host.state().targetWidthOverride.put(target, previousOverride);
                }
                if (added) {
                    host.state().unionTargets.remove(target);
                }
            }
        }
    }

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



    static SQLSelect queryExpressionSelect(SQLExpr expr) {
        return StaticShapeAnalyzer.queryExpressionSelect(expr);
    }

    boolean isLateralSource(SQLTableSource source) {
        return shape.isLateralSource(source);
    }


    private int staticUnionWidth(SQLSelectQuery query, Map<String, Integer> scope) {
        return shape.staticUnionWidth(query, scope);
    }



    static String lastSegment(String qualifiedName) {
        return StaticShapeAnalyzer.lastSegment(qualifiedName);
    }


}

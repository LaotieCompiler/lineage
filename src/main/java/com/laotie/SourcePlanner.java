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

import com.laotie.analysis.model.SourceNode;
import com.laotie.analysis.model.SourceTable;
import com.laotie.analysis.model.StarColumn;
import com.laotie.analysis.model.StarOutput;
import com.laotie.exception.AmbiguousColumnException;
import com.laotie.exception.ColumnNotFoundException;
import com.laotie.model.Instruction;
import com.laotie.model.metadata.Column;
import com.laotie.model.metadata.MetadataCatalog;
import com.laotie.model.metadata.Table;
import com.laotie.model.metadata.TableLocation;

/** Table-source resolution and star-plan construction. */
final class SourcePlanner {

    private final DmlLineage host;

    SourcePlanner(DmlLineage host) {
        this.host = host;
    }

    SourceNode buildSourceNode(SQLTableSource tableSource,
            Map<String, List<SourceTable>> using) {
        if (tableSource instanceof SQLJoinTableSource) {
            SQLJoinTableSource join = (SQLJoinTableSource) tableSource;
            Map<String, SourceNode> bindingsBefore = host.state().stackJoinBindings.isEmpty() ? null
                    : new HashMap<>(host.state().stackJoinBindings.peek());
            SourceNode left = buildSourceNode(join.getLeft(), using);
            boolean lateralRight = host.isLateralSource(join.getRight());
            if (lateralRight) {
                // A correlated LATERAL body can read the left side's columns,
                // bindings, and hidden-name set.
                host.state().stackSources.push(left.sources);
                host.state().lateralScopes.push(Boolean.TRUE);
                host.state().stackUsing.push(using);
                host.state().stackStarPlan.push(left.plan);
                host.state().stackSourceNode.push(left);
                host.state().stackSourceTable.push(left.sources.isEmpty()
                        ? "" : left.sources.get(0).displayName);
                host.state().stackHiddenSources.push(host.state().stackHiddenSources.isEmpty()
                        ? new HashSet<SourceTable>()
                        : new HashSet<>(host.state().stackHiddenSources.peek()));
                host.state().stackJoinBindings.push(host.state().stackJoinBindings.isEmpty()
                        ? new HashMap<String, SourceNode>()
                        : new HashMap<>(host.state().stackJoinBindings.peek()));
            }
            SourceNode right;
            try {
                right = buildSourceNode(join.getRight(), using);
            } finally {
                if (lateralRight) {
                    host.state().stackJoinBindings.pop();
                    host.state().stackHiddenSources.pop();
                    host.state().stackSourceTable.pop();
                    host.state().stackSourceNode.pop();
                    host.state().stackStarPlan.pop();
                    host.state().stackUsing.pop();
                    host.state().stackSources.pop();
                    host.state().lateralScopes.pop();
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
                    String key = host.identifierKey(expr.toString());
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
                            DmlLineage.normalizeIdentifier(missingKey), host.displayNames(sources));
                }
                boolean leftNamesKnown = nodeNamesKnown(left);
                boolean rightNamesKnown = nodeNamesKnown(right);
                Set<String> usingKeys = new HashSet<>();
                for (SQLExpr expr : join.getUsing()) {
                    String column = host.identifierKey(expr.toString());
                    if (!usingKeys.add(column)) {
                        throw new ColumnNotFoundException(
                                DmlLineage.normalizeIdentifier(expr.toString()),
                                host.displayNames(sources));
                    }
                    if (leftNamesKnown && planKeyCount(left.plan, column) != 1
                            || rightNamesKnown && planKeyCount(right.plan, column) != 1) {
                        throw new ColumnNotFoundException(
                                DmlLineage.normalizeIdentifier(expr.toString()),
                                host.displayNames(sources));
                    }
                    if (!leftNamesKnown && planKnownKeyCount(left.plan, column) > 1
                            || !rightNamesKnown
                                    && planKnownKeyCount(right.plan, column) > 1) {
                        throw new AmbiguousColumnException(
                                DmlLineage.normalizeIdentifier(expr.toString()),
                                host.displayNames(sources));
                    }
                }
                plan = mergeStarPlan(left.plan, right.plan, join.getJoinType(),
                        join.getUsing(), leftNamesKnown, rightNamesKnown,
                        left.sources, right.sources, left.widthKnown);
                Map<String, List<SourceTable>> participants =
                        host.state().usingParticipantsByIdentity.computeIfAbsent(using,
                                key -> new HashMap<String, List<SourceTable>>());
                for (SQLExpr expr : join.getUsing()) {
                    String column = host.identifierKey(expr.toString());
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
                if (!host.sourceColumnsKnown(source)) {
                    leftUnknownSources.add(source);
                }
            }
            List<SourceTable> rightUnknownSources = new ArrayList<>();
            for (SourceTable source : right.sources) {
                if (!host.sourceColumnsKnown(source)) {
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
                if (!host.sourceColumnsKnown(source)) {
                    Integer previous = left.unknownSectionEnd.get(source);
                    int end = previous == null ? plan.size()
                            : mergedPrefix + keptBefore(plan, left.plan, previous);
                    unknownSectionEnd.put(source, ownUsingCount > 0 ? end
                            : previous != null ? previous : left.plan.size());
                }
            }
            for (SourceTable source : right.sources) {
                if (!host.sourceColumnsKnown(source)) {
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
                if (!host.state().stackJoinBindings.isEmpty()) {
                    Map<String, SourceNode> bindings = host.state().stackJoinBindings.peek();
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
                    bindings.put(host.identifierKey(join.getAlias()), joined);
                    host.state().tableSourceBindings.put(join, joined);
                }
                if (!host.state().stackHiddenSources.isEmpty()) {
                    // An alias hides every qualifier inside its join subtree.
                    host.state().stackHiddenSources.peek().addAll(sources);
                }
            }
            return joined;
        }
        if (tableSource instanceof SQLExprTableSource
                && host.queryExpressionSelect(
                        ((SQLExprTableSource) tableSource).getExpr()) != null) {
            // Druid parses LATERAL (SELECT ...) as a method-call expression.
            SQLExprTableSource lateral = (SQLExprTableSource) tableSource;
            SQLSelect body = host.queryExpressionSelect(lateral.getExpr());
            String alias = lateral.getAlias();
            String target = alias != null ? host.uniqueTarget(alias) : host.getTempTableName();
            host.state().tempTables.add(target);
            processDerivedBody(target, body, lateral.getColumns());
            List<String> derivedOutputs = host.copyOutputs(host.state().targetOutputs.get(target));
            boolean widthKnown = host.isOutputWidthKnown(target, derivedOutputs);
            boolean namesKnown = host.isOutputNamesKnown(target, derivedOutputs);
            int derivedWidth = host.derivedWidth(target, derivedOutputs, widthKnown);
            SourceTable source = new SourceTable(target, null, null, alias, null, true,
                    widthKnown, namesKnown, derivedOutputs, host.copyKeys(target),
                    host.copyNameKnown(target, derivedOutputs.size()),
                    host.targetMinimumColumns(target, widthKnown, derivedOutputs.size()),
                    host.copyUnknownGroups(target, derivedOutputs.size()), false,
                    host.hasWildcardContribution(target));
            return new SourceNode(java.util.Collections.singletonList(source),
                    planFromSource(source), namesKnown, widthKnown, derivedWidth,
                    host.targetMinimumColumns(target, widthKnown, derivedWidth), 0,
                    derivedWidth,
                    widthKnown ? java.util.Collections.<SourceTable>emptyList()
                            : java.util.Collections.singletonList(source),
                    java.util.Collections.<SourceTable>emptyList(),
                    unknownSectionEndFor(source, widthKnown, planSizeFor(source)));
        }
        if (tableSource instanceof SQLSubqueryTableSource) {
            SQLSubqueryTableSource subquery = (SQLSubqueryTableSource) tableSource;
            String alias = subquery.getAlias();
            String target = alias != null ? host.uniqueTarget(alias) : host.getTempTableName();
            host.state().tempTables.add(target);
            processDerivedBody(target, subquery.getSelect(), subquery.getColumns());
            List<String> derivedOutputs = host.copyOutputs(host.state().targetOutputs.get(target));
            boolean widthKnown = host.isOutputWidthKnown(target, derivedOutputs);
            boolean namesKnown = host.isOutputNamesKnown(target, derivedOutputs);
            int derivedWidth = host.derivedWidth(target, derivedOutputs, widthKnown);
            SourceTable source = new SourceTable(target, null, null, alias, null, true,
                    widthKnown, namesKnown, derivedOutputs, host.copyKeys(target),
                    host.copyNameKnown(target, derivedOutputs.size()),
                    host.targetMinimumColumns(target, widthKnown, derivedOutputs.size()),
                    host.copyUnknownGroups(target, derivedOutputs.size()), false,
                    host.hasWildcardContribution(target));
            return new SourceNode(java.util.Collections.singletonList(source),
                    planFromSource(source), namesKnown, widthKnown, derivedWidth,
                    host.targetMinimumColumns(target, widthKnown, derivedWidth), 0,
                    derivedWidth,
                    widthKnown ? java.util.Collections.<SourceTable>emptyList()
                            : java.util.Collections.singletonList(source),
                    java.util.Collections.<SourceTable>emptyList(),
                    unknownSectionEndFor(source, widthKnown, planSizeFor(source)));
        }
        if (tableSource instanceof SQLUnionQueryTableSource) {
            SQLUnionQueryTableSource union = (SQLUnionQueryTableSource) tableSource;
            String alias = union.getAlias();
            String target = alias != null ? host.uniqueTarget(alias) : host.getTempTableName();
            host.state().tempTables.add(target);
            processDerivedUnion(target, union.getUnion(), union.getColumns());
            List<String> derivedOutputs = host.copyOutputs(host.state().targetOutputs.get(target));
            boolean widthKnown = host.isOutputWidthKnown(target, derivedOutputs);
            boolean namesKnown = host.isOutputNamesKnown(target, derivedOutputs);
            int derivedWidth = host.derivedWidth(target, derivedOutputs, widthKnown);
            SourceTable source = new SourceTable(target, null, null, alias, null, true,
                    widthKnown, namesKnown, derivedOutputs, host.copyKeys(target),
                    host.copyNameKnown(target, derivedOutputs.size()),
                    host.targetMinimumColumns(target, widthKnown, derivedOutputs.size()),
                    host.copyUnknownGroups(target, derivedOutputs.size()), false,
                    host.hasWildcardContribution(target));
            return new SourceNode(java.util.Collections.singletonList(source),
                    planFromSource(source), namesKnown, widthKnown, derivedWidth,
                    host.targetMinimumColumns(target, widthKnown, derivedWidth), 0,
                    derivedWidth,
                    widthKnown ? java.util.Collections.<SourceTable>emptyList()
                            : java.util.Collections.singletonList(source),
                    java.util.Collections.<SourceTable>emptyList(),
                    unknownSectionEndFor(source, widthKnown, planSizeFor(source)));
        }
        if (tableSource instanceof SQLExprTableSource) {
            SourceTable source = resolveSource((SQLExprTableSource) tableSource);
            boolean widthKnown = host.sourceWidthKnown(source);
            int width = source.metadataTable != null
                    ? source.metadataTable.getFields().size() : host.sourceKnownWidth(source);
            return new SourceNode(java.util.Collections.singletonList(source),
                    planFromSource(source), host.sourceNamesKnown(source), widthKnown, width,
                    host.minimumColumns(source), 0, width,
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

    void processDerivedBody(String target, SQLSelect select,
            List<com.alibaba.druid.sql.ast.SQLName> columns) {
        pushDerivedAliases(target, columns);
        try {
            host.state().stackTargetTable.push(target);
            host.processSelect(select);
            host.state().stackTargetTable.pop();
            recordTargetShape(target);
        } finally {
            host.state().targetColumnAliases.pop();
        }
    }

    void processDerivedUnion(String target, SQLUnionQuery union,
            List<com.alibaba.druid.sql.ast.SQLName> columns) {
        pushDerivedAliases(target, columns);
        try {
            host.state().stackTargetTable.push(target);
            host.processSelectQuery(union);
            host.state().stackTargetTable.pop();
            recordTargetShape(target);
        } finally {
            host.state().targetColumnAliases.pop();
        }
    }

    void recordTargetShape(String target) {
        boolean widthKnown = host.state().lastQueryBlockWidthKnown;
        boolean namesKnown = host.state().lastQueryBlockNamesKnown;
        int width = host.state().lastQueryBlockWidth;
        List<String> declared = host.currentTargetColumnAliases(target);
        if (declared != null) {
            DbType dialect = host.currentDialect();
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
                if (declared.size() < host.state().lastQueryBlockMinWidth) {
                    throw new IllegalStateException("Column alias count mismatch: "
                            + declared.size() + " vs at least "
                            + host.state().lastQueryBlockMinWidth);
                }
                widthKnown = true;
                namesKnown = true;
                width = declared.size();
                fillDeclaredOutputs(target, declared.size());
            }
        }
        host.state().targetWidthKnown.put(target, widthKnown);
        host.state().targetNamesKnown.put(target, namesKnown);
        host.state().targetMinWidth.put(target, widthKnown ? width : host.state().lastQueryBlockMinWidth);
    }

    void fillDeclaredOutputs(String target, int width) {
        List<String> declared = host.currentTargetColumnAliases(target);
        if (declared == null) {
            return;
        }
        List<String> declaredKeys = host.currentDeclaredKeys(target);
        List<String> outputs = host.state().targetOutputs.get(target);
        for (int i = 0; i < width; i++) {
            if (outputs != null && i < outputs.size() && outputs.get(i) != null) {
                continue;
            }
            String name = declared.get(i);
            String key = declaredKeys != null && i < declaredKeys.size()
                    ? declaredKeys.get(i) : host.identifierKey(name);
            host.setTargetOutput(target, i, name, key);
            host.markOutputNameKnown(target, i, true);
        }
    }

    void pushDerivedAliases(String target,
            List<com.alibaba.druid.sql.ast.SQLName> columns) {
        List<String> displays = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        if (columns != null) {
            for (com.alibaba.druid.sql.ast.SQLName column : columns) {
                String raw = column.toString();
                displays.add(host.canonicalIdentifier(raw));
                keys.add(host.identifierKey(raw));
            }
        }
        Map<String, List<String>> aliases = new HashMap<>();
        if (!displays.isEmpty()) {
            aliases.put(target, displays);
            host.state().cteDeclaredColumns.put(target, displays);
            host.state().cteDeclaredKeys.put(target, keys);
        }
        host.state().targetColumnAliases.push(aliases);
    }

    List<StarOutput> planFromSource(SourceTable source) {
        List<String> names = host.sourceColumnNames(source);
        List<String> keys = sourceColumnKeys(source);
        int width = names.size();
        if (host.sourceWidthKnown(source)) {
            width = Math.max(width, host.sourceKnownWidth(source));
        }
        List<StarOutput> plan = new ArrayList<>();
        for (int i = 0; i < width; i++) {
            if (i >= names.size()) {
                names = new ArrayList<>(names);
                for (int j = names.size(); j <= i; j++) {
                    names.add("col" + (j + 1));
                }
            }
            String key = i < keys.size() ? keys.get(i) : host.identifierKey(names.get(i));
            boolean nameKnown = host.sourceOutputNameKnown(source, i);
            boolean quoted = source.metadataTable != null
                    && i < source.metadataTable.getFields().size()
                    && source.metadataTable.getFields().get(i).isQuoted()
                    || source.metadataTable == null && key != null
                            && !key.equals(host.identifierKey(names.get(i)));
            boolean stable = host.sourceColumnsKnown(source) || i < host.firstUnknownIndex(source);
            plan.add(new StarOutput(java.util.Collections.singletonList(
                    new StarColumn(source, names.get(i), key, nameKnown, quoted, i)),
                    nameKnown, stable));
        }
        return plan;
    }

    List<String> sourceColumnKeys(SourceTable source) {
        if (source.metadataTable != null) {
            List<String> keys = new ArrayList<>();
            DbType dialect = host.currentDialect();
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
                            : host.identifierKey(column.getColumnName()));
                }
            }
            return keys;
        }
        if (!source.outputColumns.isEmpty()) {
            return source.outputKeys;
        }
        return java.util.Collections.singletonList(host.identifierKey("col1"));
    }

    List<StarOutput> mergeStarPlan(List<StarOutput> left, List<StarOutput> right,
            SQLJoinTableSource.JoinType type, List<SQLExpr> using,
            boolean leftKnown, boolean rightKnown, List<SourceTable> leftSources,
            List<SourceTable> rightSources, boolean leftWidthKnown) {
        List<StarOutput> leftPlan = new ArrayList<>(left);
        List<StarOutput> rightPlan = new ArrayList<>(right);
        List<StarOutput> merged = new ArrayList<>();
        boolean ambiguousMerge = false;
        for (SQLExpr expr : using) {
            String key = host.identifierKey(expr.toString());
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

    static boolean hasUniqueUsingColumn(List<StarOutput> plan, String key) {
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

    static boolean sameUnknownGroup(SourceTable source) {
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

    static boolean containsSource(List<SourceTable> sources, SourceTable source) {
        for (SourceTable candidate : sources) {
            if (candidate == source) {
                return true;
            }
        }
        return false;
    }

    static int totalWildcardOccurrences(SourceTable source) {
        int total = 0;
        for (int run : unknownRegionRuns(source)) {
            total += run;
        }
        return total;
    }

    static List<Integer> unknownRegionRuns(SourceTable source) {
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

    static List<Integer> unknownRegionEnds(SourceTable source) {
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

    static int firstUnknownIndex(SourceTable source) {
        for (int i = 0; i < source.outputNameKnown.size(); i++) {
            if (!Boolean.TRUE.equals(source.outputNameKnown.get(i))) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    static int unknownRegionCount(SourceTable source) {
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

    int planSizeFor(SourceTable source) {
        return host.sourceColumnNames(source).size();
    }

    SourceNode sourcePlanNode(SourceTable source) {
        List<StarOutput> plan = planFromSource(source);
        boolean widthKnown = host.sourceColumnsKnown(source);
        int width = source.metadataTable != null
                ? source.metadataTable.getFields().size() : source.outputColumns.size();
        return new SourceNode(java.util.Collections.singletonList(source), plan,
                host.sourceNamesKnown(source), widthKnown, width, host.minimumColumns(source), 0,
                plan.size(),
                widthKnown ? java.util.Collections.<SourceTable>emptyList()
                        : java.util.Collections.singletonList(source),
                java.util.Collections.<SourceTable>emptyList(),
                unknownSectionEndFor(source, widthKnown, plan.size()));
    }

    static Map<SourceTable, Integer> unknownSectionEndFor(SourceTable source,
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

    static boolean singleSourceCertain(List<SourceTable> sources, String key) {
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

    static StarOutput unknownColumn(List<SourceTable> sources, SQLExpr expr,
            String key, boolean certain) {
        if (sources == null || sources.isEmpty()) {
            return null;
        }
        List<StarColumn> columns = new ArrayList<>();
        String name = DmlLineage.normalizeIdentifier(expr.toString());
        boolean quoted = MetadataCatalog.isQuotedIdentifier(expr.toString());
        for (SourceTable source : sources) {
            columns.add(new StarColumn(source, name, key, certain, quoted));
        }
        return new StarOutput(columns, certain);
    }

    static StarOutput removeFirstUnknown(List<StarOutput> plan, SQLExpr expr,
            String key, boolean certain) {
        for (int i = 0; i < plan.size(); i++) {
            if (!plan.get(i).nameKnown) {
                return positionalColumn(plan.remove(i), expr, key, certain);
            }
        }
        return null;
    }

    static StarOutput positionalColumn(StarOutput output, SQLExpr expr, String key,
            boolean certain) {
        List<StarColumn> columns = new ArrayList<>();
        String name = DmlLineage.normalizeIdentifier(expr.toString());
        boolean quoted = MetadataCatalog.isQuotedIdentifier(expr.toString());
        for (StarColumn column : output.sources) {
            columns.add(new StarColumn(column.source, name, key, certain, quoted,
                    column.index));
        }
        return new StarOutput(columns, certain);
    }

    static int planKeyCount(List<StarOutput> plan, String key) {
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
    static void addSources(List<SourceTable> target, List<SourceTable> extra) {
        for (SourceTable source : extra) {
            if (!target.contains(source)) {
                target.add(source);
            }
        }
    }

    void addUnknownSources(List<SourceTable> target, List<SourceTable> candidates) {
        for (SourceTable source : candidates) {
            if (!host.sourceNamesKnown(source) && !target.contains(source)) {
                target.add(source);
            }
        }
    }

    static List<SourceTable> planAllSourcesForKey(List<StarOutput> plan,
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

    static List<SourceTable> planSourcesForKey(List<StarOutput> plan, String key) {
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

    static int insertionPoint(List<StarOutput> plan, List<SourceTable> sources,
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

    static int lastIndexOfUncertainSource(List<StarOutput> plan,
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

    static int countRemaining(List<StarOutput> plan, List<StarOutput> originals) {
        int count = 0;
        for (StarOutput output : originals) {
            if (containsIdentity(plan, output)) {
                count++;
            }
        }
        return count;
    }

    static boolean containsIdentity(List<StarOutput> plan, StarOutput output) {
        for (StarOutput candidate : plan) {
            if (candidate.origin == output.origin) {
                return true;
            }
        }
        return false;
    }

    static int keptBefore(List<StarOutput> plan, List<StarOutput> originals,
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

    static int lastIndexOfSource(List<StarOutput> plan, List<SourceTable> sources) {
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

    static int knownOutputPositionCount(SourceNode node) {
        int count = 0;
        for (StarOutput output : node.plan) {
            if (output.nameKnown) {
                count++;
            }
        }
        return count;
    }

    static Set<String> knownOutputKeys(SourceNode node) {
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

    boolean nodeNamesKnown(SourceNode node) {
        return node.knownColumns || host.bindingNamesKnown(node);
    }

    static int planKnownKeyCount(List<StarOutput> plan, String key) {
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

    StarOutput removeFirstMatching(List<StarOutput> plan, String key) {
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

    static List<SourceTable> mergedSources(SQLJoinTableSource.JoinType type,
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

    SourceTable resolveSource(SQLExprTableSource table) {
        String rawName = host.extractTableName(table);
        String written = host.normalizeQualifiedName(rawName);
        String alias = table.getAlias();
        Table metadataTable = null;
        String display = alias != null ? alias
                : host.detachedMetadata() && rawName != null
                        ? host.sessionPhysicalReference(rawName)
                        : host.lastIdentifier(written);
        String cteTarget = host.isPhysicalTableExpression(table)
                && host.isSingleSegmentName(rawName)
                ? host.findCteTarget(host.identifierKey(rawName)) : null;
        boolean temporary = cteTarget != null;
        String sourceAlias = alias;

        if (temporary) {
            display = cteTarget;
            if (sourceAlias == null) {
                sourceAlias = rawName;
            }
        }

        if (host.analysisContext() != null && !temporary) {
            TableLocation location = host.resolveMetadataTable(rawName);
            if (location != null) {
                metadataTable = location.getTable();
                display = location.getDisplayName();
            }
        }

        String mappingName = metadataTable != null ? display : written;
        host.registerTable(written, mappingName, display, alias, temporary);

        List<String> outputColumns = cteTarget == null
                ? java.util.Collections.<String>emptyList()
                : host.copyOutputs(host.state().targetOutputs.get(cteTarget));
        boolean widthKnown = metadataTable != null || cteTarget != null
                && host.isOutputWidthKnown(cteTarget, outputColumns);
        boolean namesKnown = metadataTable != null || cteTarget != null
                && host.isOutputNamesKnown(cteTarget, outputColumns);
        int outputWidth = host.derivedWidth(cteTarget, outputColumns, widthKnown);
        int minOutputColumns = metadataTable != null
                ? metadataTable.getFields().size()
                : host.targetMinimumColumns(cteTarget, widthKnown, outputWidth);
        return new SourceTable(display, written, rawName, sourceAlias, metadataTable, temporary,
                widthKnown, namesKnown, outputColumns, host.copyKeys(cteTarget),
                metadataTable != null ? java.util.Collections.<Boolean>emptyList()
                        : host.copyNameKnown(cteTarget, outputColumns.size()),
                minOutputColumns,
                metadataTable != null ? java.util.Collections.<SourceTable>emptyList()
                        : host.copyUnknownGroups(cteTarget, outputColumns.size()),
                false, host.hasWildcardContribution(cteTarget),
                host.isPhysicalTableExpression(table),
                host.isPhysicalTableExpression(table) ? null : host.implicitRelationName(table));
    }

}

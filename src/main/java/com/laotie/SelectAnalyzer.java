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

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.ast.*;
import com.alibaba.druid.sql.ast.expr.*;
import com.alibaba.druid.sql.ast.statement.*;

import com.laotie.analysis.model.CorrelatedOwner;
import com.laotie.analysis.model.ScalarSubquery;
import com.laotie.analysis.model.SourceNode;
import com.laotie.analysis.model.SourceTable;
import com.laotie.analysis.model.StarColumn;
import com.laotie.analysis.model.StarOutput;
import com.laotie.analysis.model.WildcardRef;
import com.laotie.analysis.model.WildcardSpan;
import com.laotie.exception.AmbiguousColumnException;
import com.laotie.exception.ColumnNotFoundException;
import com.laotie.exception.MetadataNotFoundException;
import com.laotie.identifier.Identifiers;
import com.laotie.model.Instruction;
import com.laotie.model.Instruction.OperationType;
import com.laotie.model.metadata.Column;
import com.laotie.model.metadata.MetadataCatalog;
import com.laotie.model.metadata.Table;

/** SELECT projection, outer-reference and scalar-subquery analysis. */
final class SelectAnalyzer {

    private final DmlLineage host;

    SelectAnalyzer(DmlLineage host) {
        this.host = host;
    }

    private static boolean isStarProperty(SQLExpr expr) {
        if (!(expr instanceof SQLPropertyExpr)) {
            return false;
        }
        String rawName = ((SQLPropertyExpr) expr).getName();
        return "*".equals(MetadataCatalog.normalizeIdentifier(rawName))
                && !MetadataCatalog.isQuotedIdentifier(rawName);
    }

    void processQueryBlock(SQLSelectQueryBlock queryBlock) {
        host.state().currentStarBudget = -1;
        host.state().currentExpandedSourceNodes = new HashMap<>();
        SQLTableSource fromItem = queryBlock.getFrom();
        if (fromItem == null) {
            if (host.state().metadataPass) {
                host.learnNoFromExplicitReferences(queryBlock, host.outerBlockLocals(true));
            }
            int position = 0;
            int minWidth = 0;
            boolean widthKnown = true;
            boolean namesKnown = true;
            boolean unknownStarSeen = false;
            List<SourceTable> outerSources = host.currentSources();
            for (SQLSelectItem item : queryBlock.getSelectList()) {
                if ((host.analysisContext() != null || host.detachedMetadata())
                        && outerSources.isEmpty()) {
                    host.validateUnboundQualified(item.getExpr());
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
            host.state().lastQueryBlockWidth = position;
            host.state().lastQueryBlockWidthKnown = widthKnown;
            host.state().lastQueryBlockNamesKnown = namesKnown;
            host.state().lastQueryBlockMinWidth = minWidth;
            return;
        }

        Map<String, List<SourceTable>> using = new LinkedHashMap<>();
        host.state().stackJoinBindings.push(new HashMap<String, SourceNode>());
        host.state().stackHiddenSources.push(new HashSet<SourceTable>());
        SourceNode node = host.buildSourceNode(fromItem, using);
        List<SourceTable> sources = node.sources;
        if (sources.isEmpty()) {
            host.state().stackHiddenSources.pop();
            host.state().stackJoinBindings.pop();
            return;
        }

        host.collectTablesFromExpression(queryBlock.getWhere());

        host.state().stackSources.push(sources);
        host.state().lateralScopes.push(Boolean.FALSE);
        host.state().stackUsing.push(using);
        host.state().stackStarPlan.push(node.plan);
        host.state().stackSourceNode.push(node);
        host.state().stackSourceTable.push(sources.get(0).displayName);
        host.state().targetOutputs.computeIfAbsent(host.state().stackTargetTable.peek(), k -> new ArrayList<String>());
        if (host.state().metadataPass) {
            host.learnBlockExplicitReferences(queryBlock, host.outerBlockLocals(false));
        }

        List<SQLSelectItem> selectItems = queryBlock.getSelectList();
        int inferredUnknownWidth = -1;
        int unknownItemIndex = -1;
        int[] startOverride = new int[selectItems.size()];
        for (int i = 0; i < startOverride.length; i++) {
            startOverride[i] = -1;
        }
        List<String> declaredWidths = host.currentTargetColumnAliases(host.state().stackTargetTable.peek());
        DbType dialect = host.currentDialect();
        boolean declaredExact = declaredWidths != null
                && (dialect == DbType.mysql || dialect == DbType.mariadb);
        int positionalWidth = -1;
        if (declaredExact) {
            positionalWidth = declaredWidths.size();
        } else {
            Integer overrideWidth = host.state().targetWidthOverride.get(host.state().stackTargetTable.peek());
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
                if (host.outputWidthKnown(item)) {
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
                    if (!host.outputWidthKnown(item)) {
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
        host.state().currentStarBudget = inferredUnknownWidth;
        if (!host.detachedMetadata() && inferredUnknownWidth > 0 && unknownItemIndex >= 0) {
            SourceNode targetNode = node;
            SQLExpr unknownExpr = selectItems.get(unknownItemIndex).getExpr();
            if (isStarProperty(unknownExpr)) {
                SQLExpr owner = ((SQLPropertyExpr) unknownExpr).getOwner();
                SourceNode binding = host.joinBinding(owner);
                if (binding != null) {
                    targetNode = binding;
                } else {
                    SourceTable source = host.findByOwner(owner);
                    if (source != null && !host.sourceColumnsKnown(source)) {
                        SourceNode sourceNode = host.sourcePlanNode(source);
                        host.state().currentExpandedSourceNodes.put(source, sourceNode);
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
                        && host.unknownRegionCount(leftUnknown.get(0)) <= 1;
                boolean rightOnly = rightUnknown.size() == 1 && leftUnknown.isEmpty()
                        && host.unknownRegionCount(rightUnknown.get(0)) <= 1;
                boolean handledRepeated = false;
                List<SourceTable> repeatedSide = leftUnknown.size() == 1
                        && rightUnknown.isEmpty() ? leftUnknown
                        : rightUnknown.size() == 1 && leftUnknown.isEmpty()
                                ? rightUnknown : null;
                if (repeatedSide != null
                        && host.totalWildcardOccurrences(repeatedSide.get(0)) > 1) {
                    SourceTable source = repeatedSide.get(0);
                    int deficit = inferredUnknownWidth - plan.size();
                    List<Integer> ends = host.unknownRegionEnds(source);
                    List<Integer> runs = host.unknownRegionRuns(source);
                    int occurrences = host.totalWildcardOccurrences(source);
                    // Locate the source's own plan to translate region boundaries after
                    // any USING reordering.
                    SourceNode sideNode = null;
                    int sideOffset = 0;
                    if (targetNode.leftNode != null
                            && host.containsSource(targetNode.leftNode.sources, source)) {
                        sideNode = targetNode.leftNode;
                    } else if (targetNode.rightNode != null
                            && host.containsSource(targetNode.rightNode.sources, source)) {
                        sideNode = targetNode.rightNode;
                        sideOffset = Math.max(plan.size()
                                - host.countRemaining(plan, targetNode.leftNode == null
                                        ? new ArrayList<StarOutput>()
                                        : targetNode.leftNode.plan)
                                - host.countRemaining(plan, targetNode.rightNode.plan), 0);
                    } else if (targetNode.sources.size() == 1
                            && targetNode.sources.get(0) == source) {
                        sideNode = targetNode;
                    }
                    int mergedPrefix = 0;
                    if (targetNode.leftNode != null && targetNode.rightNode != null) {
                        mergedPrefix = Math.max(plan.size()
                                - host.countRemaining(plan, targetNode.leftNode.plan)
                                - host.countRemaining(plan, targetNode.rightNode.plan), 0);
                    }
                    if (deficit >= 0 && host.sameUnknownGroup(source) && sideNode != null
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
                                    + host.keptBefore(plan, sideNode.plan, boundary) + sideOffset
                                    + offset;
                            insertAt = Math.min(insertAt, plan.size());
                            for (int k = 0; k < perWildcard * runs.get(r); k++) {
                                List<StarColumn> columns = new ArrayList<>();
                                String name = "col" + (insertAt + 1);
                                columns.add(new StarColumn(source, name,
                                        host.identifierKey(name), false, false));
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
                    insertAt = host.insertionPoint(plan, leftUnknown,
                            Math.min(targetNode.rightBoundary, plan.size()),
                            targetNode.unknownSectionEnd);
                } else if (rightOnly) {
                    expansion = rightUnknown;
                    insertAt = host.insertionPoint(plan, rightUnknown, plan.size(),
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
                        columns.add(new StarColumn(source, name, host.identifierKey(name),
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
            boolean unknownStar = host.detachedMetadata()
                    && (itemExpr instanceof SQLAllColumnExpr || isStarProperty(itemExpr))
                    && host.hasUnknownStarSource(itemExpr);
            // Contract: a projection that follows an unknown-width wildcard has an
            // undetermined position, so no column-level edge may be guessed for it.
            host.state().uncertainOrdinal = positionalWidth > 0 && !positionCertain
                    || host.detachedMetadata() && unknownStarSeen ? start : -1;
            boolean inferredWidth = itemIndex == unknownItemIndex
                    && inferredUnknownWidth > 0;
            if (unknownStar && !host.starOutputWidthKnown(itemExpr) && !inferredWidth) {
                // Only an unknown-width wildcard leaves later positions undetermined.
                unknownStarSeen = true;
            }
            host.state().currentOrdinal = start;
            int advanced;
            try {
                advanced = processSelectItem(item);
            } catch (RuntimeException e) {
                if (!host.state().metadataPass) {
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
                host.state().currentStarBudget = -1;
            }
            position = start + advanced;
            minWidth += minimumOutputColumns(item.getExpr());
            boolean itemWidthKnown = host.outputWidthKnown(item) || inferredWidth;
            if (!itemWidthKnown) {
                widthKnown = false;
            }
            prefixCertain = prefixCertain && itemWidthKnown;
            if (!host.outputNamesKnown(item)) {
                namesKnown = false;
            }
        }
        host.state().uncertainOrdinal = -1;
        host.state().currentOrdinal = -1;
        host.state().lastQueryBlockWidth = position;
        host.state().lastQueryBlockWidthKnown = widthKnown;
        host.state().lastQueryBlockNamesKnown = namesKnown;
        host.state().lastQueryBlockMinWidth = minWidth;

        host.state().stackSourceTable.pop();
        host.state().stackSourceNode.pop();
        host.state().stackStarPlan.pop();
        host.state().stackUsing.pop();
        host.state().stackSources.pop();
        host.state().lateralScopes.pop();
        host.state().stackHiddenSources.pop();
        host.state().stackJoinBindings.pop();
    }

    int recordUncertainOuterStar(SQLPropertyExpr star, int position) {
        String target = host.state().stackTargetTable.peek();
        CorrelatedOwner correlated = host.findCorrelatedOwner(star.getOwner());
        if (correlated == null) {
            throw new MetadataNotFoundException(star.getOwner() == null
                    ? "*" : star.getOwner().toString());
        }
        if (correlated.source != null) {
            addOuterWildcardOpen(correlated.source, target, position);
            return host.sourceWidthKnown(correlated.source)
                    ? Math.max(host.sourceKnownWidth(correlated.source), 1) : 1;
        }
        for (SourceTable candidate : correlated.binding.sources) {
            addOuterWildcardOpen(candidate, target, position);
        }
        return correlated.binding.widthKnown
                ? Math.max(correlated.binding.width, 1) : 1;
    }

    void recordUncertainOuterMappings(SQLSelectItem item, int position) {
        SQLExpr expr = item.getExpr();
        if (expr == null) {
            return;
        }
        String target = host.state().stackTargetTable.peek();
        recordUncertainOuterExpression(expr, target, position);
    }

    void recordUncertainOuterExpression(SQLExpr expr, String target,
            int position) {
        if (expr == null) {
            return;
        }
        if (expr instanceof SQLPropertyExpr) {
            SQLPropertyExpr property = (SQLPropertyExpr) expr;
            if (isStarProperty(property)) {
                return;
            }
            CorrelatedOwner correlated = host.findCorrelatedOwner(property.getOwner());
            if (correlated == null) {
                throw new MetadataNotFoundException(property.getOwner() == null
                        ? "*" : property.getOwner().toString());
            }
            if (correlated.source != null) {
                resolveOuterColumn(correlated.source, property.getName());
                addOuterWildcardOpen(correlated.source, target, position);
            } else {
                String key = host.identifierKey(property.getName());
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
                            host.normalizeIdentifier(property.getName()),
                            host.displayNames(owners));
                }
                if (matched.isEmpty() && host.nodeNamesKnown(correlated.binding)) {
                    throw new ColumnNotFoundException(
                            host.normalizeIdentifier(property.getName()),
                            host.displayNames(correlated.binding.sources));
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
                owners = host.resolveUnqualified(raw);
            } catch (ColumnNotFoundException e) {
                owners = host.hasLateralAncestorScope()
                        ? host.resolveCorrelatedUnqualified(raw)
                        : new ArrayList<SourceTable>();
            }
            if (owners.isEmpty() && host.hasLateralAncestorScope()) {
                owners = host.resolveCorrelatedUnqualified(raw);
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

    void recordOuterExpressionMappings(SQLSelectItem item, int position) {
        SQLExpr expr = item.getExpr();
        if (expr == null) {
            return;
        }
        String target = host.state().stackTargetTable.peek();
        List<String> outputs = host.state().targetOutputs.get(target);
        String name = outputs != null && position < outputs.size()
                && outputs.get(position) != null
                        ? outputs.get(position)
                        : host.normalizeIdentifier(expr.toString());
        recordOuterExpression(expr, target, name, position);
    }

    void recordOuterExpression(SQLExpr expr, String target, String targetName,
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

    boolean outerStarNamesKnown(SQLPropertyExpr star) {
        CorrelatedOwner correlated = host.findCorrelatedOwner(star.getOwner());
        if (correlated == null) {
            return false;
        }
        if (correlated.source != null) {
            return sourceColumnNamesKnown(correlated.source);
        }
        return host.nodeNamesKnown(correlated.binding);
    }

    static boolean coveredBySpan(List<WildcardSpan> spans, int relative) {
        for (WildcardSpan span : spans) {
            if (relative >= span.start && relative < span.end) {
                return true;
            }
        }
        return false;
    }

    boolean hasDuplicateNamedOutputs(SourceTable source) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < source.outputKeys.size(); i++) {
            if (!host.sourceOutputNameKnown(source, i)) {
                continue;
            }
            String key = source.outputKeys.get(i);
            if (key != null && !seen.add(key)) {
                return true;
            }
        }
        return false;
    }

    String effectiveOutputName(SourceTable source, int index) {
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

    List<WildcardRef> wildcardRefsAt(SourceTable source, int relative) {
        List<WildcardRef> refs = new ArrayList<>();
        for (WildcardSpan span : host.wildcardSpansOf(source.displayName)) {
            if (relative >= span.start && relative < span.end) {
                WildcardRef ref = new WildcardRef(span.source, span.temporary);
                if (!refs.contains(ref)) {
                    refs.add(ref);
                }
            }
        }
        return refs;
    }

    int recordOuterStar(SQLPropertyExpr star, int position) {
        String target = host.state().stackTargetTable.peek();
        CorrelatedOwner correlated = host.findCorrelatedOwner(star.getOwner());
        SourceTable source = correlated == null ? null : correlated.source;
        if (source != null) {
            List<String> names = host.sourceColumnNames(source);
            List<String> declared = host.currentTargetColumnAliases(target);
            List<String> declaredKeys = host.currentDeclaredKeys(target);
            if (source.temporary) {
                int width = host.sourceWidthKnown(source) ? host.sourceKnownWidth(source) : -1;
                if (width > 0) {
                    List<WildcardSpan> spans = host.wildcardSpansOf(source.displayName);
                    if (declared == null && hasDuplicateNamedOutputs(source)) {
                        // Ambiguous repeated names cannot be expanded into columns
                        // unless the target declares their order.
                        Instruction wildcard = new Instruction(
                                OperationType.COLUMNS_INJECT,
                                host.lineageTableName(source), target, position,
                                source.temporary, host.state().tempTables.contains(target));
                        wildcard.setPositionalWildcard(true);
                        wildcard.setWildcardWidthKnown(true);
                        wildcard.setWildcardWidth(width);
                        host.instructions().add(wildcard);
                        host.recordWildcardSpan(target, position, position + width,
                                host.lineageTableName(source), source.temporary);
                        host.state().targetWildcardContribution.put(target, Boolean.TRUE);
                        return width;
                    }
                    int limit = width;
                    for (int i = 0; i < limit; i++) {
                        boolean named = host.sourceOutputNameKnown(source, i)
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
                                            : host.identifierKey(displayName);
                            quoted = !sourceKey.equals(host.identifierKey(displayName));
                            if (declared != null && position + i < declared.size()) {
                                outputName = declared.get(position + i);
                                outputKey = declaredKeys != null
                                        && position + i < declaredKeys.size()
                                                ? declaredKeys.get(position + i)
                                                : host.identifierKey(outputName);
                            } else {
                                outputName = displayName;
                                outputKey = sourceKey;
                            }
                            host.setTargetOutput(target, position + i, outputName, outputKey);
                            host.markOutputNameKnown(target, position + i, true);
                            addOuterColumnMapping(source, sourceColumn, target,
                                    outputName, position + i, quoted);
                        }
                        for (WildcardRef ref : wildcardRefsAt(source, i)) {
                            Instruction wildcard = new Instruction(
                                    OperationType.COLUMNS_INJECT, ref.name, target,
                                    position + i, ref.temporary,
                                    host.state().tempTables.contains(target));
                            wildcard.setPositionalWildcard(true);
                            wildcard.setWildcardWidthKnown(true);
                            wildcard.setWildcardWidth(1);
                            host.instructions().add(wildcard);
                            host.recordWildcardSpan(target, position + i,
                                    position + i + 1, ref.name, ref.temporary);
                            host.state().targetWildcardContribution.put(target, Boolean.TRUE);
                        }
                    }
                    return limit;
                }
                List<WildcardSpan> spans = host.wildcardSpansOf(source.displayName);
                int knownPrefix = 0;
                for (int i = 0; i < names.size() && host.sourceOutputNameKnown(source, i);
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
                                        : host.identifierKey(outputName);
                        quoted = !outputKey.equals(host.identifierKey(outputName));
                    } else {
                        outputName = name;
                        outputKey = i < source.outputKeys.size()
                                && source.outputKeys.get(i) != null
                                        ? source.outputKeys.get(i)
                                        : host.identifierKey(name);
                        quoted = !outputKey.equals(host.identifierKey(name));
                    }
                    String sourceColumn = effectiveOutputName(source, i);
                    if (sourceColumn == null) {
                        sourceColumn = name;
                    }
                    host.setTargetOutput(target, position + i, outputName, outputKey);
                    host.markOutputNameKnown(target, position + i, true);
                    addOuterColumnMapping(source, sourceColumn, target, outputName,
                            position + i, quoted);
                    knownPrefix = i + 1;
                }
                if (!spans.isEmpty()) {
                    for (WildcardSpan span : spans) {
                        Instruction wildcard = new Instruction(
                                OperationType.COLUMNS_INJECT, span.source, target,
                                position + span.start, span.temporary,
                                host.state().tempTables.contains(target));
                        wildcard.setPositionalWildcard(true);
                        if (span.end != Integer.MAX_VALUE) {
                            wildcard.setWildcardWidthKnown(true);
                            wildcard.setWildcardWidth(span.end - span.start);
                        }
                        host.instructions().add(wildcard);
                        host.recordWildcardSpan(target, position + span.start,
                                span.end == Integer.MAX_VALUE ? Integer.MAX_VALUE
                                        : position + span.end,
                                span.source, span.temporary);
                        host.state().targetWildcardContribution.put(target, Boolean.TRUE);
                    }
                } else {
                    Instruction wildcard = new Instruction(OperationType.COLUMNS_INJECT,
                            host.lineageTableName(source), target, position,
                            source.temporary, host.state().tempTables.contains(target));
                    wildcard.setPositionalWildcard(true);
                    host.recordWildcardSpan(target, position, Integer.MAX_VALUE,
                            host.lineageTableName(source), source.temporary);
                    host.instructions().add(wildcard);
                    host.state().targetWildcardContribution.put(target, Boolean.TRUE);
                }
                int minimum = Math.max(Math.max(knownPrefix, source.minOutputColumns),
                        host.maxSpanEnd(spans));
                return -Math.max(minimum, 1);
            }
            int width = host.sourceWidthKnown(source) ? host.sourceKnownWidth(source) : -1;
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
                                        : host.identifierKey(outputName);
                    } else if (source.metadataTable != null
                            && i < source.metadataTable.getFields().size()) {
                        Column column = source.metadataTable.getFields().get(i);
                        quoted = column.isQuoted();
                        outputName = column.getColumnName();
                        outputKey = quoted && host.currentDialect() != DbType.mysql
                                && host.currentDialect() != DbType.mariadb
                                        ? column.getColumnName()
                                        : host.identifierKey(column.getColumnName());
                    } else {
                        outputName = sourceColumn;
                        outputKey = i < source.outputKeys.size()
                                && source.outputKeys.get(i) != null
                                        ? source.outputKeys.get(i)
                                        : host.identifierKey(sourceColumn);
                        quoted = !outputKey.equals(host.identifierKey(sourceColumn));
                    }
                    host.setTargetOutput(target, position + i, outputName, outputKey);
                    host.markOutputNameKnown(target, position + i, true);
                    addOuterColumnMapping(source, sourceColumn, target, outputName,
                            position + i, quoted);
                }
                return Math.max(width, 1);
            }
            Instruction wildcard = new Instruction(OperationType.COLUMNS_INJECT,
                    host.lineageTableName(source), target, position,
                    source.temporary, host.state().tempTables.contains(target));
            wildcard.setPositionalWildcard(true);
            if (width > 0) {
                wildcard.setWildcardWidthKnown(true);
                wildcard.setWildcardWidth(width);
                host.recordWildcardSpan(target, position, position + width,
                        host.lineageTableName(source), source.temporary);
            } else {
                host.recordWildcardSpan(target, position, Integer.MAX_VALUE,
                        host.lineageTableName(source), source.temporary);
            }
            host.instructions().add(wildcard);
            host.state().targetWildcardContribution.put(target, Boolean.TRUE);
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
            if (host.hasAmbiguousOutput(binding.plan, binding.width)) {
                for (SourceTable candidate : binding.sources) {
                    Instruction wildcard = new Instruction(
                            OperationType.COLUMNS_INJECT,
                            host.lineageTableName(candidate), target, position,
                            candidate.temporary, host.state().tempTables.contains(target));
                    wildcard.setPositionalWildcard(true);
                    wildcard.setWildcardWidthKnown(true);
                    wildcard.setWildcardWidth(Math.max(binding.width, 1));
                    host.instructions().add(wildcard);
                    host.recordWildcardSpan(target, position,
                            position + Math.max(binding.width, 1),
                            host.lineageTableName(candidate), candidate.temporary);
                    host.state().targetWildcardContribution.put(target, Boolean.TRUE);
                }
                return binding.width;
            }
            boolean allNamesKnown = host.nodeNamesKnown(binding);
            List<String> declared = host.currentTargetColumnAliases(target);
            List<String> declaredKeys = host.currentDeclaredKeys(target);
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
                            ? declaredKeys.get(pos) : host.identifierKey(outputName);
                    precise = precise && first.nameKnown;
                } else if (precise) {
                    outputName = first.column;
                    outputKey = first.key != null ? first.key
                            : host.identifierKey(first.column);
                } else {
                    outputName = "col" + (pos + 1);
                    outputKey = host.identifierKey(outputName);
                }
                host.setTargetOutput(target, pos, outputName, outputKey);
                if (precise || declared != null && pos < declared.size()) {
                    host.markOutputNameKnown(target, pos, true);
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
                            && !host.wildcardSpansOf(column.source.displayName).isEmpty()) {
                        int rel = column.index >= 0 ? column.index : 0;
                        for (WildcardRef ref : host.wildcardSourcesAt(column.source,
                                column, rel)) {
                            Instruction wildcard = new Instruction(
                                    OperationType.COLUMNS_INJECT, ref.name, target,
                                    pos, ref.temporary, host.state().tempTables.contains(target));
                            wildcard.setPositionalWildcard(true);
                            wildcard.setWildcardWidthKnown(true);
                            wildcard.setWildcardWidth(1);
                            host.instructions().add(wildcard);
                            host.recordWildcardSpan(target, pos, pos + 1, ref.name,
                                    ref.temporary);
                            host.state().targetWildcardContribution.put(target, Boolean.TRUE);
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

    void recordOuterColumn(SQLPropertyExpr property, String target,
            String targetName, int position) {
        String rawName = property.getName();
        CorrelatedOwner correlated = host.findCorrelatedOwner(property.getOwner());
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
            String key = host.identifierKey(rawName);
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
                throw new AmbiguousColumnException(host.normalizeIdentifier(rawName),
                        host.displayNames(owners));
            }
            if (matchedOutputs.size() == 1) {
                for (StarColumn column : matchedOutputs.get(0).sources) {
                    addOuterColumnMapping(column.source, column.column, target,
                            targetName, position, column.quoted);
                }
                return;
            }
            if (host.nodeNamesKnown(binding)) {
                throw new ColumnNotFoundException(host.normalizeIdentifier(rawName),
                        host.displayNames(binding.sources));
            }
            for (SourceTable candidate : binding.sources) {
                addOuterWildcard(candidate, target, position);
            }
            return;
        }
        throw new MetadataNotFoundException(property.getOwner() == null
                ? "*" : property.getOwner().toString());
    }

    void addOuterColumnMapping(SourceTable source, String column,
            String target, String targetName, int position) {
        addOuterColumnMapping(source, column, target, targetName, position, null);
    }

    void addOuterColumnMapping(SourceTable source, String column,
            String target, String targetName, int position, Boolean quoted) {
        if (source.metadataTable == null && !source.temporary) {
            DbType dialect = host.currentDialect();
            if (dialect == DbType.hive || dialect == DbType.antspark) {
                // Planned columns are already logical; strip only a backtick
                // layer, fold case, and let rendering re-quote special names.
                column = host.hivePlannedColumn(column);
                quoted = null;
            } else if (quoted == null) {
                column = host.uncatalogedColumnSpelling(column);
            }
        }
        String rendered = quoted == null
                ? Identifiers.render(column)
                : quoted ? Identifiers.quoteDouble(column)
                        : Identifiers.render(column);
        host.instructions().add(new Instruction(OperationType.COLUMN_MAPPING,
                host.lineageTableName(source) + "." + rendered,
                target + "." + Identifiers.render(targetName), position,
                source.temporary, host.state().tempTables.contains(target)));
        if (source.wildcardContribution && source.temporary) {
            int relative = -1;
            String key = quoted != null && quoted
                    ? host.identifierKey(Identifiers.quoteDouble(column))
                    : host.identifierKey(column);
            for (int i = 0; i < source.outputKeys.size(); i++) {
                if (key != null && key.equals(source.outputKeys.get(i))) {
                    relative = i;
                    break;
                }
            }
            List<WildcardSpan> spans = relative < 0
                    ? java.util.Collections.<WildcardSpan>emptyList()
                    : host.wildcardSpansOf(source.displayName);
            for (WildcardSpan span : spans) {
                if (relative < span.start || relative >= span.end) {
                    continue;
                }
                // Only the wildcard covering the selected column contributes.
                addOuterWildcard(span.source, target, position, span.temporary);
            }
        }
    }

    void addOuterWildcard(String sourceName, String target, int position,
            boolean temporary) {
        Instruction wildcard = new Instruction(OperationType.COLUMNS_INJECT,
                sourceName, target, position, temporary,
                host.state().tempTables.contains(target));
        wildcard.setPositionalWildcard(true);
        wildcard.setWildcardWidthKnown(true);
        wildcard.setWildcardWidth(1);
        host.instructions().add(wildcard);
        host.recordWildcardSpan(target, position, position + 1, sourceName, temporary);
        host.state().targetWildcardContribution.put(target, Boolean.TRUE);
    }

    void addOuterWildcardOpen(SourceTable source, String target,
            int position) {
        Instruction wildcard = new Instruction(OperationType.COLUMNS_INJECT,
                host.lineageTableName(source), target, position,
                source.temporary, host.state().tempTables.contains(target));
        wildcard.setPositionalWildcard(true);
        host.instructions().add(wildcard);
        host.state().targetWildcardContribution.put(target, Boolean.TRUE);
        host.recordWildcardSpan(target, position, Integer.MAX_VALUE,
                host.lineageTableName(source), source.temporary);
    }

    void addOuterWildcard(SourceTable source, String target, int position) {
        Instruction wildcard = new Instruction(OperationType.COLUMNS_INJECT,
                host.lineageTableName(source), target, position,
                source.temporary, host.state().tempTables.contains(target));
        wildcard.setPositionalWildcard(true);
        wildcard.setWildcardWidthKnown(true);
        wildcard.setWildcardWidth(1);
        host.instructions().add(wildcard);
        host.state().targetWildcardContribution.put(target, Boolean.TRUE);
        host.recordWildcardSpan(target, position, position + 1,
                host.lineageTableName(source), source.temporary);
    }

    void recordOuterIdentifier(String rawName, String target,
            String targetName, int position) {
        String key = host.identifierKey(rawName);
        Map<String, List<SourceTable>> using = host.state().stackUsing.isEmpty()
                ? null : host.state().stackUsing.peek();
        if (using != null && using.containsKey(key)) {
            List<SourceTable> participants = using.get(key);
            List<SourceTable> sides = participantsForCurrentUsing(key);
            List<SourceTable> extras = new ArrayList<>();
            for (SourceTable source : host.currentSources()) {
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
                throw new AmbiguousColumnException(host.normalizeIdentifier(rawName),
                        host.displayNames(all));
            }
            for (SourceTable source : participants) {
                String column = resolveOuterColumn(source, rawName);
                addOuterColumnMapping(source, column, target, targetName, position);
            }
            return;
        }
        List<StarOutput> matchedOutputs = new ArrayList<>();
        List<StarOutput> planned = host.state().stackStarPlan.isEmpty()
                ? java.util.Collections.<StarOutput>emptyList()
                : host.state().stackStarPlan.peek();
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
            throw new AmbiguousColumnException(host.normalizeIdentifier(rawName),
                    host.displayNames(owners));
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
            for (SourceTable source : host.currentSources()) {
                if (participants.contains(source) || matchedSources.contains(source)) {
                    continue;
                }
                if (ownsOuterColumn(source, key)
                        || !sourceColumnNamesKnown(source)) {
                    owners.add(source);
                }
            }
            if (owners.size() > matchedSources.size()) {
                throw new AmbiguousColumnException(host.normalizeIdentifier(rawName),
                        host.displayNames(owners));
            }
            for (StarColumn column : matchedOutputs.get(0).sources) {
                addOuterColumnMapping(column.source, column.column, target,
                        targetName, position, column.quoted);
            }
            return;
        }
        List<SourceTable> owners = new ArrayList<>();
        List<SourceTable> unknown = new ArrayList<>();
        for (SourceTable source : host.currentSources()) {
            if (ownsOuterColumn(source, key)) {
                owners.add(source);
            } else if (!sourceColumnNamesKnown(source)) {
                unknown.add(source);
            }
        }
        if (owners.size() > 1) {
            throw new AmbiguousColumnException(host.normalizeIdentifier(rawName),
                    host.displayNames(owners));
        }
        if (owners.size() == 1) {
            if (!unknown.isEmpty()) {
                List<SourceTable> all = new ArrayList<>(owners);
                all.addAll(unknown);
                throw new AmbiguousColumnException(host.normalizeIdentifier(rawName),
                        host.displayNames(all));
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
            throw new AmbiguousColumnException(host.normalizeIdentifier(rawName),
                    host.displayNames(unknown));
        }
        if (host.hasOuterScopeFallback()) {
            List<SourceTable> ancestors = host.resolveCorrelatedUnqualified(rawName);
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
        throw new ColumnNotFoundException(host.normalizeIdentifier(rawName),
                host.displayNames(host.currentSources()));
    }

    List<SourceTable> participantsForCurrentUsing(String key) {
        if (host.state().stackUsing.isEmpty() || host.state().usingParticipantsByIdentity.isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, List<SourceTable>> using = host.state().stackUsing.peek();
        if (using == null) {
            return new ArrayList<>();
        }
        Map<String, List<SourceTable>> sides = host.state().usingParticipantsByIdentity.get(using);
        if (sides == null || !sides.containsKey(key)) {
            return new ArrayList<>();
        }
        return new ArrayList<>(sides.get(key));
    }

    List<SourceTable> currentUsingParticipants(String key) {
        if (host.state().stackUsing.isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, List<SourceTable>> using = host.state().stackUsing.peek();
        if (using == null || host.state().usingParticipantsByIdentity.isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, List<SourceTable>> participants = host.state().usingParticipantsByIdentity.get(using);
        if (participants == null || !participants.containsKey(key)) {
            return new ArrayList<>();
        }
        return new ArrayList<>(participants.get(key));
    }

    boolean ownsOuterColumn(SourceTable source, String key) {
        if (key == null) {
            return false;
        }
        if (source.metadataTable != null) {
            for (int i = 0; i < source.metadataTable.getFields().size(); i++) {
                Column column = source.metadataTable.getFields().get(i);
                String columnKey = column.isQuoted()
                        && host.currentDialect() != DbType.mysql
                        && host.currentDialect() != DbType.mariadb
                                ? column.getColumnName()
                                : host.identifierKey(column.getColumnName());
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

    boolean sourceColumnNamesKnown(SourceTable source) {
        return source.metadataTable != null || source.outputNamesKnown;
    }

    String resolveOuterColumn(SourceTable source, String rawName) {
        if (source.metadataTable != null) {
            String resolved = MetadataCatalog.resolveColumnName(source.metadataTable,
                    rawName, host.currentDialect());
            if (resolved == null) {
                throw new ColumnNotFoundException(host.normalizeIdentifier(rawName),
                        host.displayNames(host.visibleSources()));
            }
            return resolved;
        }
        String key = host.identifierKey(rawName);
        int found = -1;
        for (int i = 0; i < source.outputKeys.size(); i++) {
            if (key != null && key.equals(source.outputKeys.get(i))) {
                if (found >= 0) {
                    throw new AmbiguousColumnException(host.normalizeIdentifier(rawName),
                            host.displayNames(host.visibleSources()));
                }
                found = i;
            }
        }
        if (source.outputNamesKnown) {
            if (found >= 0) {
                return found < source.outputColumns.size()
                        ? source.outputColumns.get(found)
                        : host.normalizeIdentifier(rawName);
            }
            throw new ColumnNotFoundException(host.normalizeIdentifier(rawName),
                    host.displayNames(host.visibleSources()));
        }
        if (found >= 0) {
            return found < source.outputColumns.size()
                    ? source.outputColumns.get(found)
                    : host.normalizeIdentifier(rawName);
        }
        return host.normalizeIdentifier(rawName);
    }

    /**
     * Record the tables an unnamed scalar subquery reads as a table-level claim on
     * the target. When the written column is known (UPDATE/VALUES), the claim is
     * bound to that column; otherwise it occupies one projection position.
     */
    void recordScalarTableLevel(ScalarSubquery inner, String targetTable,
            int position, String targetColumn) {
        Set<String> tables = new LinkedHashSet<>();
        String prefix = inner.temp + ".";
        for (Instruction instruction : host.instructions()) {
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
            if (targetColumn != null && !host.state().tempTables.contains(targetTable)) {
                String rendered = targetColumn;
                Instruction edge = new Instruction(OperationType.COLUMNS_INJECT,
                        table, targetTable + "(" + rendered + ")", -1, false, false);
                edge.setPositionalWildcard(true);
                edge.setWildcardTable(targetTable);
                edge.setPopulatedColumns(rendered);
                host.instructions().add(edge);
            } else {
                Instruction wildcard = new Instruction(OperationType.COLUMNS_INJECT,
                        table, targetTable, position, false,
                        host.state().tempTables.contains(targetTable));
                wildcard.setPositionalWildcard(true);
                wildcard.setWildcardWidthKnown(true);
                wildcard.setWildcardWidth(1);
                host.instructions().add(wildcard);
                host.recordWildcardSpan(targetTable, position, position + 1, table, false);
                host.state().targetWildcardContribution.put(targetTable, Boolean.TRUE);
            }
        }
    }

    int processScalarSubqueryProjection(SQLSelectItem item, SQLQueryExpr expr,
            int position) {
        String targetTable = host.state().stackTargetTable.peek();
        int savedOrdinal = host.state().currentOrdinal;
        host.state().currentOrdinal = position;
        try {
            return processScalarSubqueryProjection(item, expr);
        } finally {
            host.state().currentOrdinal = savedOrdinal;
        }
    }

    int processScalarSubqueryProjection(SQLSelectItem item, SQLQueryExpr expr) {
        String targetTable = host.state().stackTargetTable.peek();
        ScalarSubquery inner = analyzeScalarSubquery(expr);
        if (inner == null) {
            return 1;
        }
        if (inner.columns.isEmpty()) {
            recordScalarTableLevel(inner, host.state().stackTargetTable.peek(), host.state().currentOrdinal, null);
            return 1;
        }
        List<String> declaredColumns = host.currentTargetColumnAliases(targetTable);
        String lockedName = host.lockedOutputName(targetTable, host.state().currentOrdinal);
        String lockedKey = host.lockedOutputKey(targetTable, host.state().currentOrdinal);
        String targetColumn;
        String targetKey;
        if (lockedName != null) {
            targetColumn = lockedName;
            targetKey = lockedKey != null ? lockedKey : host.identifierKey(lockedName);
        } else if (declaredColumns != null && host.state().currentOrdinal >= 0
                && host.state().currentOrdinal < declaredColumns.size()) {
            targetColumn = declaredColumns.get(host.state().currentOrdinal);
            targetKey = host.identifierKey(targetColumn);
        } else if (item.getAlias() != null) {
            targetColumn = host.normalizeIdentifier(item.getAlias());
            targetKey = host.identifierKey(item.getAlias());
        } else {
            targetColumn = inner.columns.get(0);
            targetKey = host.identifierKey(targetColumn);
        }
        host.setTargetOutput(targetTable, host.state().currentOrdinal, targetColumn, targetKey);
        host.markOutputNameKnown(targetTable, host.state().currentOrdinal, true);
        if (host.state().uncertainOrdinal >= 0 && host.state().currentOrdinal == host.state().uncertainOrdinal) {
            // The position follows an unknown-width wildcard, so no edge may be
            // pinned to a column, exactly like an ordinary projection there.
            return 1;
        }
        for (int i = 0; i < inner.columns.size(); i++) {
            String source = inner.references.get(i);
            host.instructions().add(new Instruction(OperationType.COLUMN_MAPPING, source,
                    targetTable + "." + Identifiers.render(targetColumn), host.state().currentOrdinal,
                    true, host.state().tempTables.contains(targetTable)));
        }
        return 1;
    }

    boolean containsScalarSubquery(SQLExpr expr) {
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

    void recordUnboundScalarMappings(SQLSelectItem item, int position) {
        String targetTable = host.state().stackTargetTable.peek();
        List<String> outputs = host.copyOutputs(host.state().targetOutputs.get(targetTable));
        String name = outputs != null && position < outputs.size() && outputs.get(position) != null
                ? outputs.get(position) : "col" + (position + 1);
        recordUnboundScalarExpression(item.getExpr(), targetTable, name, position);
    }

    void recordUnboundScalarExpression(SQLExpr expr, String targetTable,
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
            host.instructions().add(new Instruction(OperationType.COLUMN_MAPPING,
                    inner.references.get(0),
                    targetTable + "." + Identifiers.render(targetName), position, true,
                    host.state().tempTables.contains(targetTable)));
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

    int recordUnboundSelectItem(SQLSelectItem item, int position) {
        if (item.getExpr() == null) {
            return 0;
        }
        SQLExpr base = item.getExpr();
        while (base instanceof SQLCastExpr) {
            base = ((SQLCastExpr) base).getExpr();
        }
        String targetTable = host.state().stackTargetTable.peek();
        String lockedName = host.lockedOutputName(targetTable, position);
        String lockedKey = host.lockedOutputKey(targetTable, position);
        if (lockedName != null) {
            host.setTargetOutput(targetTable, position, lockedName,
                    lockedKey != null ? lockedKey : host.identifierKey(lockedName));
            return 1;
        }
        List<String> declared = host.currentTargetColumnAliases(targetTable);
        List<String> declaredKeys = host.currentDeclaredKeys(targetTable);
        String name;
        String key;
        if (declared != null && position < declared.size()) {
            name = declared.get(position);
            key = declaredKeys != null && position < declaredKeys.size()
                    ? declaredKeys.get(position) : host.identifierKey(name);
            host.setTargetOutput(targetTable, position, name, key);
            host.markOutputNameKnown(targetTable, position, true);
            return 1;
        }
        if (item.getAlias() != null) {
            name = host.normalizeIdentifier(item.getAlias());
            key = host.identifierKey(item.getAlias());
        } else if (base instanceof SQLIdentifierExpr) {
            name = host.normalizeIdentifier(((SQLIdentifierExpr) base).getName());
            key = host.identifierKey(((SQLIdentifierExpr) base).getName());
        } else if (base instanceof SQLPropertyExpr && !isStarProperty(base)) {
            name = host.normalizeIdentifier(((SQLPropertyExpr) base).getName());
            key = host.identifierKey(((SQLPropertyExpr) base).getName());
        } else {
            name = item.getExpr().toString();
            key = host.identifierKey(name);
        }
        host.setTargetOutput(host.state().stackTargetTable.peek(), position, name, key);
        host.markOutputNameKnown(host.state().stackTargetTable.peek(), position, true);
        return 1;
    }

    /**
     * The output of an analyzed scalar subquery: the temporary that holds it and the
     * names of its columns.
     */
/**
     * Analyze a scalar subquery as a one-column derived table so its own lineage is
     * kept instead of being folded into the enclosing expression.
     */
    ScalarSubquery analyzeScalarSubquery(SQLQueryExpr expr) {
        SQLSelect select = expr.getSubQuery();
        if (select == null) {
            return null;
        }
        String target = host.getTempTableName();
        host.state().tempTables.add(target);
        int savedOrdinal = host.state().currentOrdinal;
        int savedUncertain = host.state().uncertainOrdinal;
        int savedBudget = host.state().currentStarBudget;
        boolean savedInTarget = host.state().inTargetExpression;
        host.state().scalarSubqueryDepth++;
        try {
            host.state().stackTargetTable.push(target);
            host.processSelect(select);
            host.state().stackTargetTable.pop();
        } finally {
            host.state().scalarSubqueryDepth--;
            host.state().currentOrdinal = savedOrdinal;
            host.state().uncertainOrdinal = savedUncertain;
            host.state().currentStarBudget = savedBudget;
            host.state().inTargetExpression = savedInTarget;
        }
        host.recordTargetShape(target);
        List<String> outputs = host.copyOutputs(host.state().targetOutputs.get(target));
        boolean widthKnown = Boolean.TRUE.equals(host.state().targetWidthKnown.get(target));
        int minimum = host.targetMinimumColumns(target, widthKnown, outputs.size());
        int width = Math.max(outputs.size(), minimum);
        if (width > 1) {
            throw new IllegalStateException(
                    "Scalar subquery must return one column: " + width);
        }
        List<String> columns = new ArrayList<>();
        for (String output : outputs) {
            columns.add(output);
        }
        // Reuse the inner host.instructions()' own spelling of each column, so a quoted or
        // dotted name keeps its boundaries on both sides.
        List<String> references = new ArrayList<>();
        String prefix = target + ".";
        for (Instruction instruction : host.instructions()) {
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

    int processSelectItem(SQLSelectItem item) {
        if (item.getExpr() == null) {
            return 0;
        }
        SQLExpr baseExpr = item.getExpr();
        while (baseExpr instanceof SQLCastExpr) {
            baseExpr = ((SQLCastExpr) baseExpr).getExpr();
        }
        if (baseExpr instanceof SQLQueryExpr) {
            return processScalarSubqueryProjection(item, (SQLQueryExpr) baseExpr,
                    host.state().currentOrdinal);
        }

        String targetTable = host.state().stackTargetTable.peek();
        List<String> declaredColumns = host.currentTargetColumnAliases(targetTable);

        String lockedName = host.lockedOutputName(targetTable, host.state().currentOrdinal);
        String lockedKey = host.lockedOutputKey(targetTable, host.state().currentOrdinal);

        String targetColumn;
        boolean identifierTarget;
        if (lockedName != null) {
            targetColumn = lockedName;
            identifierTarget = true;
        } else if (declaredColumns != null && host.state().currentOrdinal >= 0
                && host.state().currentOrdinal < declaredColumns.size()) {
            targetColumn = declaredColumns.get(host.state().currentOrdinal);
            identifierTarget = true;
        } else if (item.getAlias() != null) {
            targetColumn = host.normalizeIdentifier(item.getAlias());
            identifierTarget = true;
        } else if (baseExpr instanceof SQLIdentifierExpr) {
            targetColumn = host.normalizeIdentifier(((SQLIdentifierExpr) baseExpr).getName());
            identifierTarget = true;
        } else if (baseExpr instanceof SQLPropertyExpr && !isStarProperty(baseExpr)) {
            targetColumn = host.normalizeIdentifier(((SQLPropertyExpr) baseExpr).getName());
            identifierTarget = true;
        } else {
            targetColumn = item.getExpr().toString();
            identifierTarget = false;
        }
        if (identifierTarget) {
            targetColumn = Identifiers.render(targetColumn);
        }

        if (item.getExpr() instanceof SQLAllColumnExpr) {
            if (host.detachedMetadata()) {
                int width = host.emitDetachedStar(targetTable, host.currentSources(), host.state().currentOrdinal);
                return Math.max(width, 1);
            }
            Map<String, List<SourceTable>> using = host.state().stackUsing.isEmpty()
                    ? null : host.state().stackUsing.peek();
            SourceNode node = host.state().stackSourceNode.isEmpty() ? null : host.state().stackSourceNode.peek();
            if (host.analysisContext() == null && node != null && !node.plan.isEmpty()
                    && (using != null && !using.isEmpty() || host.state().currentStarBudget > 0)) {
                host.recordBindingStar(targetTable, node, host.state().currentOrdinal);
            } else {
                host.recordStarOutputs(targetTable, host.currentSources(), host.state().currentOrdinal);
            }
        } else if (isStarProperty(item.getExpr())) {
            SQLExpr owner = ((SQLPropertyExpr) item.getExpr()).getOwner();
            CorrelatedOwner correlated = host.findCorrelatedOwner(owner);
            SourceTable source = correlated == null ? null : correlated.source;
            SourceNode binding = correlated == null ? null : correlated.binding;
            if (host.detachedMetadata()) {
                List<StarOutput> plan;
                if (source != null) {
                    plan = host.planFromSource(source);
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
                int width = host.emitDetachedPlan(targetTable, plan, planSources, planNode,
                        host.state().currentOrdinal);
                return Math.max(width, 1);
            }
            if (source != null) {
                SourceNode expanded = host.state().currentExpandedSourceNodes.get(source);
                SourceNode leafNode = host.state().stackSourceNode.isEmpty()
                        ? null : host.state().stackSourceNode.peek();
                if (expanded != null) {
                    host.recordBindingStar(targetTable, expanded, host.state().currentOrdinal);
                } else if (host.analysisContext() == null && host.state().currentStarBudget > 0 && leafNode != null
                        && leafNode.sources.size() == 1
                        && leafNode.sources.get(0) == source
                        && !host.sourceColumnsKnown(source)) {
                    host.recordBindingStar(targetTable, leafNode, host.state().currentOrdinal);
                } else {
                    host.recordSourceStarOutputs(targetTable, source, host.state().currentOrdinal);
                }
            } else if (binding != null) {
                host.recordBindingStar(targetTable, binding, host.state().currentOrdinal);
            }
        } else {
            List<String> declaredKeys = host.currentDeclaredKeys(targetTable);
            String targetKey;
            if (lockedKey != null) {
                targetKey = lockedKey;
            } else if (declaredKeys != null && host.state().currentOrdinal < declaredKeys.size()) {
                targetKey = declaredKeys.get(host.state().currentOrdinal);
            } else if (item.getAlias() != null) {
                targetKey = host.identifierKey(item.getAlias());
            } else if (baseExpr instanceof SQLIdentifierExpr) {
                targetKey = host.identifierKey(((SQLIdentifierExpr) baseExpr).getName());
            } else if (baseExpr instanceof SQLPropertyExpr) {
                targetKey = host.identifierKey(((SQLPropertyExpr) baseExpr).getName());
            } else {
                targetKey = host.identifierKey(item.getExpr().toString());
            }
            host.setTargetOutput(targetTable, host.state().currentOrdinal, host.normalizeIdentifier(targetColumn),
                    targetKey);
            if (lockedName == null) {
                host.markOutputNameKnown(targetTable, host.state().currentOrdinal, true);
            }
        }

        host.state().stackTargetColumn.push(targetColumn);
        int before = host.instructions().size();
        host.state().inTargetExpression = true;
        host.visitExpr(item.getExpr());
        host.state().inTargetExpression = false;
        boolean uncertain = host.state().uncertainOrdinal >= 0 && host.state().currentOrdinal == host.state().uncertainOrdinal;
        if (host.instructions().size() == before && !uncertain) {
            host.instructions().add(new Instruction(OperationType.EXPR_MAPPING,
                    item.getExpr().toString(), targetTable + "." + targetColumn,
                    host.state().currentOrdinal, false, host.state().tempTables.contains(targetTable)));
        }
        host.state().stackTargetColumn.pop();

        return outputColumnCount(item.getExpr());
    }

    int outputColumnCount(SQLExpr expr) {
        if (expr instanceof SQLAllColumnExpr) {
            Map<String, List<SourceTable>> using = host.state().stackUsing.isEmpty()
                    ? null : host.state().stackUsing.peek();
            if (!host.state().stackSourceNode.isEmpty()) {
                SourceNode node = host.state().stackSourceNode.peek();
                if (node.widthKnown) {
                    return Math.max(node.width, 1);
                }
                // With USING the visible plan already merges keys, so its size is the
                // best known position count even when names are incomplete.
                if (using != null && !using.isEmpty() && !node.plan.isEmpty()) {
                    return Math.max(node.plan.size(), 1);
                }
            }
            if (host.useVisiblePlan(host.currentSources(), using)) {
                return Math.max(host.currentStarPlan(host.currentSources()).size(), 1);
            }
            int count = 0;
            for (SourceTable source : host.currentSources()) {
                count += host.columnCount(source);
            }
            return Math.max(count, 1);
        }
        if (isStarProperty(expr)) {
            SQLExpr owner = ((SQLPropertyExpr) expr).getOwner();
            SourceTable source = host.findByOwner(owner);
            if (source != null) {
                return Math.max(host.columnCount(source), 1);
            }
            SourceNode binding = host.joinBinding(owner);
            if (binding != null) {
                return binding.widthKnown
                        ? Math.max(binding.width, 1) : Math.max(binding.plan.size(), 1);
            }
            return 1;
        }
        return 1;
    }

    int minimumOutputColumns(SQLExpr expr) {
        if (expr instanceof SQLAllColumnExpr) {
            if (!host.state().stackSourceNode.isEmpty()) {
                SourceNode node = host.state().stackSourceNode.peek();
                return node.widthKnown ? node.width : node.minWidth;
            }
            Map<String, List<SourceTable>> using = host.state().stackUsing.isEmpty()
                    ? null : host.state().stackUsing.peek();
            if (host.useVisiblePlan(host.currentSources(), using)) {
                return host.currentStarPlan(host.currentSources()).size();
            }
            int minimum = 0;
            for (SourceTable source : host.currentSources()) {
                minimum += minimumColumns(source);
            }
            return minimum;
        }
        if (isStarProperty(expr)) {
            SQLExpr owner = ((SQLPropertyExpr) expr).getOwner();
            SourceTable source = host.findByOwner(owner);
            if (source != null) {
                return minimumColumns(source);
            }
            SourceNode binding = host.joinBinding(owner);
            if (binding != null) {
                return binding.widthKnown ? binding.width : binding.minWidth;
            }
            return 0;
        }
        return 1;
    }

    int minimumColumns(SourceTable source) {
        if (source.metadataTable != null) {
            return source.metadataTable.getFields().size();
        }
        if (host.sourceColumnsKnown(source)) {
            return source.outputColumns.size();
        }
        return source.minOutputColumns;
    }

}

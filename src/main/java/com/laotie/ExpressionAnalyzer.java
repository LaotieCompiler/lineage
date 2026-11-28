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
import com.laotie.exception.AmbiguousColumnException;
import com.laotie.exception.ColumnNotFoundException;
import com.laotie.exception.MetadataNotFoundException;
import com.laotie.identifier.Identifiers;
import com.laotie.model.Instruction;
import com.laotie.model.Instruction.OperationType;
import com.laotie.model.metadata.Column;
import com.laotie.model.metadata.MetadataCatalog;
import com.laotie.model.metadata.Table;

/** Expression, owner-resolution and join-binding analysis. */
final class ExpressionAnalyzer {

    private final DmlLineage host;

    ExpressionAnalyzer(DmlLineage host) {
        this.host = host;
    }

    void visitExpr(SQLExpr expr) {
        if (expr == null) {
            return;
        }
        if (expr instanceof SQLIdentifierExpr) {
            visitIdentifier((SQLIdentifierExpr) expr);
        } else if (expr instanceof SQLPropertyExpr) {
            visitProperty((SQLPropertyExpr) expr);
        } else if (expr instanceof SQLAllColumnExpr) {
            host.visitAllColumn();
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

    void visitScalarSubquery(SQLQueryExpr expr) {
        ScalarSubquery inner = host.analyzeScalarSubquery(expr);
        if (inner == null) {
            return;
        }
        if (inner.columns.isEmpty()) {
            host.recordScalarTableLevel(inner, host.state().stackTargetTable.peek(), host.state().currentOrdinal,
                    host.state().stackTargetColumn.peek());
            return;
        }
        String targetTable = host.state().stackTargetTable.peek();
        String targetColumn = host.state().stackTargetColumn.peek();
        if (host.state().uncertainOrdinal >= 0 && host.state().currentOrdinal == host.state().uncertainOrdinal) {
            return;
        }
        for (int i = 0; i < inner.columns.size(); i++) {
            String source = inner.references.get(i);
            host.instructions().add(new Instruction(OperationType.COLUMN_MAPPING, source,
                    targetTable + "." + targetColumn, host.state().currentOrdinal, true,
                    host.state().tempTables.contains(targetTable)));
        }
    }

    void visitIdentifier(SQLIdentifierExpr identifier) {
        if (!host.state().inTargetExpression) {
            return;
        }
        String rawName = identifier.getName();
        List<SourceTable> sources;
        try {
            sources = host.resolveUnqualified(rawName);
        } catch (ColumnNotFoundException e) {
            if (!hasOuterScopeFallback()) {
                throw e;
            }
            sources = host.resolveCorrelatedUnqualified(rawName);
        }
        if (sources.isEmpty() && hasOuterScopeFallback()) {
            sources = host.resolveCorrelatedUnqualified(rawName);
        }
        for (SourceTable source : sources) {
            host.learnColumn(source, rawName);
            host.addColumnMapping(source, rawName);
        }
    }

    List<SourceTable> participantsForFrame(int frame, String key) {
        if (frame >= host.state().stackUsing.size() || host.state().usingParticipantsByIdentity.isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, List<SourceTable>> using = host.state().stackUsing.get(frame);
        if (using == null) {
            return new ArrayList<>();
        }
        Map<String, List<SourceTable>> participants =
                host.state().usingParticipantsByIdentity.get(using);
        if (participants == null || !participants.containsKey(key)) {
            return new ArrayList<>();
        }
        return new ArrayList<>(participants.get(key));
    }

    List<SourceTable> frameCandidates(int frame, String key,
            List<SourceTable> participants, List<SourceTable> excluded) {
        Set<SourceTable> hidden = frame < host.state().stackHiddenSources.size()
                ? host.state().stackHiddenSources.get(frame)
                : java.util.Collections.<SourceTable>emptySet();
        List<SourceTable> candidates = new ArrayList<>();
        for (SourceTable source : host.state().stackSources.get(frame)) {
            if (hidden.contains(source) || participants.contains(source)
                    || excluded.contains(source)) {
                continue;
            }
            if (host.ownsOuterColumn(source, key) || !host.sourceColumnNamesKnown(source)) {
                candidates.add(source);
            }
        }
        return candidates;
    }

    boolean lateralScopeActive() {
        int current = host.state().stackSources.size() - 1;
        return current >= 0 && current < host.state().lateralScopes.size()
                && Boolean.TRUE.equals(host.state().lateralScopes.get(current))
                || hasLateralAncestorScope();
    }

    boolean hasOuterScopeFallback() {
        return hasLateralAncestorScope() || host.state().scalarSubqueryDepth > 0;
    }

    boolean hasLateralAncestorScope() {
        for (int frame = 0; frame < host.state().stackSources.size() - 1; frame++) {
            if (frame < host.state().lateralScopes.size()
                    && Boolean.TRUE.equals(host.state().lateralScopes.get(frame))) {
                return true;
            }
        }
        return false;
    }

    List<SourceTable> resolveCorrelatedUnqualified(String rawName) {
        String key = host.identifierKey(rawName);
        boolean lateralScope = false;
        for (int frame = host.state().stackSources.size() - 2; frame >= 0; frame--) {
            boolean lateralFrame = frame < host.state().lateralScopes.size()
                    && Boolean.TRUE.equals(host.state().lateralScopes.get(frame));
            if (!lateralFrame && host.state().scalarSubqueryDepth == 0) {
                continue;
            }
            lateralScope = true;
            Map<String, List<SourceTable>> using = frame < host.state().stackUsing.size()
                    ? host.state().stackUsing.get(frame) : null;
            if (using != null && using.containsKey(key)) {
                List<StarOutput> framePlan = frame < host.state().stackStarPlan.size()
                        ? host.state().stackStarPlan.get(frame)
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
                    throw new AmbiguousColumnException(DmlLineage.normalizeIdentifier(rawName),
                            host.displayNames(all));
                }
                List<SourceTable> participants = using.get(key);
                List<SourceTable> sides = participantsForFrame(frame, key);
                List<SourceTable> extras = frameCandidates(frame, key, sides,
                        new ArrayList<SourceTable>());
                if (!extras.isEmpty()) {
                    List<SourceTable> all = new ArrayList<>(participants);
                    all.addAll(extras);
                    throw new AmbiguousColumnException(DmlLineage.normalizeIdentifier(rawName),
                            host.displayNames(all));
                }
                return participants;
            }
            List<StarOutput> plan = frame < host.state().stackStarPlan.size()
                    ? host.state().stackStarPlan.get(frame)
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
                throw new AmbiguousColumnException(DmlLineage.normalizeIdentifier(rawName),
                        host.displayNames(owners));
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
                    throw new AmbiguousColumnException(DmlLineage.normalizeIdentifier(rawName),
                            host.displayNames(all));
                }
                return owners;
            }
            Set<SourceTable> hidden = frame < host.state().stackHiddenSources.size()
                    ? host.state().stackHiddenSources.get(frame)
                    : java.util.Collections.<SourceTable>emptySet();
            List<SourceTable> owners = new ArrayList<>();
            List<SourceTable> unknown = new ArrayList<>();
            for (SourceTable source : host.state().stackSources.get(frame)) {
                if (hidden.contains(source)) {
                    continue;
                }
                if (host.ownsOuterColumn(source, key)) {
                    owners.add(source);
                } else if (!host.sourceColumnNamesKnown(source)) {
                    unknown.add(source);
                }
            }
            if (owners.size() > 1 || !owners.isEmpty() && !unknown.isEmpty()
                    || unknown.size() > 1) {
                List<SourceTable> all = new ArrayList<>(owners);
                all.addAll(unknown);
                throw new AmbiguousColumnException(DmlLineage.normalizeIdentifier(rawName),
                        host.displayNames(all));
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
        throw new ColumnNotFoundException(DmlLineage.normalizeIdentifier(rawName),
                host.displayNames(host.currentSources()));
    }

CorrelatedOwner findCorrelatedOwner(SQLExpr owner) {
        if (owner == null) {
            return null;
        }
        String rawOwner = owner.toString();
        String ownerKey = host.qualifiedKey(rawOwner);
        List<SourceTable> matches = visibleOwnerMatches(owner);
        if (matches.size() > 1) {
            throw new AmbiguousColumnException(DmlLineage.normalizeIdentifier(host.lastSegment(rawOwner)),
                    host.displayNames(matches));
        }
        SourceTable direct = matches.isEmpty() ? null : matches.get(0);
        if (direct != null) {
            return new CorrelatedOwner(direct, null);
        }
        SourceNode directBinding = host.exactJoinBinding(owner);
        if (directBinding != null) {
            return new CorrelatedOwner(null, directBinding);
        }
        String[] ownerParts = MetadataCatalog.splitQualifiedNamePreservingQuotes(rawOwner);
        String aliasKey = host.isSingleSegmentName(rawOwner) ? host.identifierKey(rawOwner) : null;
        for (int frame = host.state().stackSources.size() - 1; frame >= 0; frame--) {
            if (frame < host.state().stackJoinBindings.size() && aliasKey != null) {
                SourceNode binding = host.state().stackJoinBindings.get(frame).get(aliasKey);
                if (binding != null) {
                    return new CorrelatedOwner(null, binding);
                }
            }
            Set<SourceTable> hidden = frame < host.state().stackHiddenSources.size()
                    ? host.state().stackHiddenSources.get(frame)
                    : java.util.Collections.<SourceTable>emptySet();
            // An explicit alias wins over a same-spelled table name in this frame.
            List<SourceTable> aliasMatches = new ArrayList<>();
            for (SourceTable source : host.state().stackSources.get(frame)) {
                if (!hidden.contains(source) && source.alias != null
                        && host.qualifiedKey(source.alias).equals(ownerKey)) {
                    aliasMatches.add(source);
                }
            }
            if (aliasMatches.size() > 1) {
                throw new AmbiguousColumnException(
                        DmlLineage.normalizeIdentifier(host.lastSegment(rawOwner)),
                        host.displayNames(aliasMatches));
            }
            if (aliasMatches.size() == 1) {
                return new CorrelatedOwner(aliasMatches.get(0), null);
            }
            List<SourceTable> frameMatches = new ArrayList<>();
            for (SourceTable source : host.state().stackSources.get(frame)) {
                if (!hidden.contains(source) && source.alias == null
                        && matchesOwner(source, ownerKey, ownerParts)) {
                    frameMatches.add(source);
                }
            }
            if (frameMatches.size() > 1) {
                throw new AmbiguousColumnException(
                        DmlLineage.normalizeIdentifier(host.lastSegment(rawOwner)),
                        host.displayNames(frameMatches));
            }
            if (frameMatches.size() == 1) {
                return new CorrelatedOwner(frameMatches.get(0), null);
            }
        }
        return null;
    }

    static boolean hasQuotedSegment(String reference) {
        return hasSignificantQuote(reference);
    }

    static boolean exactNormalizedSuffix(String[] ownerParts,
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

    static boolean hasSignificantQuote(String reference) {
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

    List<SourceTable> visibleOwnerMatches(SQLExpr owner) {
        List<SourceTable> matches = new ArrayList<>();
        if (owner == null) {
            return matches;
        }
        String rawOwner = owner.toString();
        String ownerKey = host.qualifiedKey(rawOwner);
        String[] ownerParts = MetadataCatalog.splitQualifiedNamePreservingQuotes(rawOwner);
        for (SourceTable source : host.visibleSources()) {
            boolean matched;
            if (source.alias != null) {
                // An alias hides the original table name.
                matched = host.qualifiedKey(source.alias).equals(ownerKey);
            } else if (!source.learnable) {
                // A table function is visible only through its implicit FROM name;
                // its call text is not a qualifier.
                matched = source.implicitName != null
                        && host.qualifiedKey(source.implicitName).equals(ownerKey);
            } else {
                String reference = source.rawName != null
                        ? source.rawName : source.displayName;
                matched = host.qualifiedKey(reference).equals(ownerKey);
                if (!matched && source.displayName != null
                        && (source.rawName == null
                                || !hasQuotedSegment(source.rawName))) {
                    matched = host.qualifiedKey(source.displayName).equals(ownerKey);
                }
                if (!matched && source.displayName != null
                        && source.rawName != null
                        && hasQuotedSegment(source.rawName)) {
                    matched = exactNormalizedSuffix(ownerParts,
                            MetadataCatalog.splitQualifiedNamePreservingQuotes(
                                    source.displayName));
                }
                if (!matched && source.implicitName != null) {
                    matched = host.qualifiedKey(source.implicitName).equals(ownerKey);
                }
                if (!matched && ownerParts.length > 0) {
                    String[] referenceParts = MetadataCatalog
                            .splitQualifiedNamePreservingQuotes(reference);
                    if (ownerParts.length <= referenceParts.length) {
                        matched = true;
                        for (int i = 0; i < ownerParts.length; i++) {
                            if (!host.identifierKey(ownerParts[i]).equals(host.identifierKey(
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

    SourceTable findVisibleOwnerStrict(SQLExpr owner) {
        if (owner == null) {
            return null;
        }
        String rawOwner = owner.toString();
        String ownerKey = host.qualifiedKey(rawOwner);
        for (SourceTable source : host.visibleSources()) {
            boolean matched;
            if (source.alias != null) {
                // An alias hides the original table name.
                matched = host.qualifiedKey(source.alias).equals(ownerKey);
            } else if (!source.learnable) {
                matched = source.implicitName != null
                        && host.qualifiedKey(source.implicitName).equals(ownerKey);
            } else {
                matched = host.qualifiedKey(source.displayName).equals(ownerKey)
                        || source.rawName != null
                                && host.qualifiedKey(source.rawName).equals(ownerKey)
                        || source.writtenName != null
                                && host.qualifiedKey(source.writtenName).equals(ownerKey);
            }
            if (matched) {
                return source;
            }
        }
        return null;
    }

    SourceNode exactJoinBinding(SQLExpr owner) {
        if (owner == null || !host.isSingleSegmentName(owner.toString())
                || host.state().stackJoinBindings.isEmpty()) {
            return null;
        }
        String key = host.identifierKey(owner.toString());
        return key == null ? null : host.state().stackJoinBindings.peek().get(key);
    }

    boolean matchesOwner(SourceTable source, String ownerKey,
            String[] ownerParts) {
        if (source.alias != null) {
            return host.qualifiedKey(source.alias).equals(ownerKey);
        }
        if (!source.learnable) {
            return source.implicitName != null
                    && host.qualifiedKey(source.implicitName).equals(ownerKey);
        }
        String reference = source.rawName != null
                ? source.rawName : source.displayName;
        if (reference == null) {
            return false;
        }
        if (host.qualifiedKey(reference).equals(ownerKey)) {
            return true;
        }
        if (source.displayName != null
                && (source.rawName == null
                        || !hasQuotedSegment(source.rawName))
                && host.qualifiedKey(source.displayName).equals(ownerKey)) {
            return true;
        }
        if (source.implicitName != null
                && host.qualifiedKey(source.implicitName).equals(ownerKey)) {
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
                if (!host.identifierKey(ownerParts[i]).equals(host.identifierKey(
                        referenceParts[referenceParts.length - ownerParts.length
                                + i]))) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    static boolean isStarProperty(SQLExpr expr) {
        if (!(expr instanceof SQLPropertyExpr)) {
            return false;
        }
        String rawName = ((SQLPropertyExpr) expr).getName();
        return "*".equals(DmlLineage.normalizeIdentifier(rawName))
                && !MetadataCatalog.isQuotedIdentifier(rawName);
    }

    void visitProperty(SQLPropertyExpr property) {
        String rawName = property.getName();
        String name = DmlLineage.normalizeIdentifier(rawName);
        if ("*".equals(name) && !MetadataCatalog.isQuotedIdentifier(rawName)) {
            if (!host.state().inTargetExpression) {
                return;
            }
            CorrelatedOwner correlated = findCorrelatedOwner(property.getOwner());
            SourceTable source = correlated == null ? null : correlated.source;
            if (source == null) {
                SourceNode binding = correlated == null ? null : correlated.binding;
                if (binding != null) {
                    host.mapBindingStar(host.state().stackTargetTable.peek(), binding, host.state().currentOrdinal);
                    return;
                }
                if (host.analysisContext() != null) {
                    throw new MetadataNotFoundException(property.getOwner() == null
                            ? name : property.getOwner().toString());
                }
                return;
            }
            String targetTable = host.state().stackTargetTable.peek();
            if (host.detachedMetadata()) {
                host.emitDetachedPlan(targetTable, host.planFromSource(source),
                        java.util.Collections.singletonList(source), null, host.state().currentOrdinal);
                return;
            }
            SourceNode expanded = host.state().currentExpandedSourceNodes.get(source);
            if (expanded != null) {
                host.mapBindingStar(targetTable, expanded, host.state().currentOrdinal);
                return;
            }
            SourceNode leafNode = host.state().stackSourceNode.isEmpty()
                    ? null : host.state().stackSourceNode.peek();
            if (host.analysisContext() == null && host.state().currentStarBudget > 0 && leafNode != null
                    && leafNode.sources.size() == 1
                    && leafNode.sources.get(0) == source
                    && !host.sourceColumnsKnown(source)) {
                host.mapBindingStar(targetTable, leafNode, host.state().currentOrdinal);
                return;
            }
            if (host.analysisContext() == null && host.sourceNamesKnown(source)
                    && !source.outputColumns.isEmpty()) {
                host.mapSourceStar(source, targetTable, host.state().currentOrdinal);
                return;
            }
            host.instructions().add(new Instruction(OperationType.COLUMNS_INJECT,
                    source.displayName, targetTable, host.state().currentOrdinal, source.temporary,
                    host.state().tempTables.contains(targetTable)));
            return;
        }

        if (!host.state().inTargetExpression) {
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
            if (host.analysisContext() != null || lateralScopeActive()) {
                throw new MetadataNotFoundException(
                        property.getOwner() == null ? name : property.getOwner().toString());
            }
            host.addColumnMapping(host.lastIdentifier(property.getOwner() == null
                    ? null : property.getOwner().toString()), name, false, null);
            return;
        }
        if (source.metadataTable != null
                && MetadataCatalog.resolveColumnName(source.metadataTable, rawName,
                        host.currentDialect()) == null) {
            throw new ColumnNotFoundException(name, host.displayNames(host.currentSources()));
        }
        if (source.metadataTable == null) {
            String key = host.identifierKey(rawName);
            int matches = 0;
            for (String outputKey : source.outputKeys) {
                if (key != null && key.equals(outputKey)) {
                    matches++;
                }
            }
            if (matches > 1) {
                throw new AmbiguousColumnException(name,
                        host.displayNames(java.util.Collections.singletonList(source)));
            }
        }
        if (source.metadataTable == null && source.outputNamesKnown
                && !source.outputKeys.contains(host.identifierKey(rawName))) {
            throw new ColumnNotFoundException(name, host.displayNames(host.currentSources()));
        }
        host.learnColumn(source, rawName, true);
        host.addColumnMapping(source, rawName);
    }

    void bindJoinProperty(SourceNode binding, String rawName, String name) {
        String key = host.identifierKey(rawName);
        List<StarOutput> matches = new ArrayList<>();
        for (StarOutput output : binding.plan) {
            for (StarColumn column : output.sources) {
                if (column.key != null && column.key.equals(key)) {
                    matches.add(output);
                    break;
                }
            }
        }
        if (binding.knownColumns || host.bindingNamesKnown(binding)) {
            if (matches.isEmpty()) {
                throw new ColumnNotFoundException(name, host.displayNames(binding.sources));
            }
            if (matches.size() > 1) {
                throw new AmbiguousColumnException(name,
                        host.displayNames(host.planSources(matches)));
            }
            List<StarColumn> known = host.knownColumns(matches.get(0));
            if (!known.isEmpty()) {
                host.emitBindingColumns(known);
            } else if (host.state().uncertainOrdinal < 0 || host.state().currentOrdinal != host.state().uncertainOrdinal) {
                String targetTable = host.state().stackTargetTable.peek();
                for (SourceTable source : host.starOutputSources(matches.get(0))) {
                    host.instructions().add(new Instruction(OperationType.COLUMNS_INJECT,
                            source.displayName, targetTable, host.state().currentOrdinal,
                            source.temporary, host.state().tempTables.contains(targetTable)));
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
            throw new AmbiguousColumnException(name, host.displayNames(host.planSources(matches)));
        }
        if (definiteMatches == 1) {
            for (StarOutput output : matches) {
                if (!output.nameKnown) {
                    continue;
                }
                List<StarColumn> known = host.knownColumns(output);
                if (!known.isEmpty()) {
                    host.emitBindingColumns(known);
                } else {
                    for (SourceTable source : host.starOutputSources(output)) {
                        host.instructions().add(new Instruction(OperationType.COLUMNS_INJECT,
                                source.displayName, host.state().stackTargetTable.peek(), host.state().currentOrdinal,
                                source.temporary,
                                host.state().tempTables.contains(host.state().stackTargetTable.peek())));
                    }
                }
                return;
            }
        }
        // Only bind when exactly one source can own the column.
        List<SourceTable> candidates = new ArrayList<>();
        for (SourceTable source : binding.sources) {
            if (!host.sourceNamesKnown(source) || source.outputKeys.contains(key)) {
                candidates.add(source);
            }
        }
        if (candidates.size() == 1) {
            host.addColumnMapping(candidates.get(0), rawName);
        }
    }

    List<SourceTable> resolveUnqualified(String rawColumn) {
        List<SourceTable> sources = host.currentSources();
        if (sources.isEmpty()) {
            return sources;
        }
        String columnName = host.normalizeIdentifier(rawColumn);
        String columnKey = host.identifierKey(rawColumn);

        if (host.analysisContext() != null && !host.state().stackStarPlan.isEmpty() && !host.state().stackStarPlan.peek().isEmpty()) {
            List<StarOutput> matches = new ArrayList<>();
            List<SourceTable> matchedSources = new ArrayList<>();
            for (StarOutput output : host.state().stackStarPlan.peek()) {
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
                if (host.detachedMetadata()) {
                    // A source whose column set is unknown may also own the column,
                    // unless the learned metadata proves it cannot.
                    List<SourceTable> unknown = host.refineWithLearned(
                            host.unknownColumnSources(sources, matchedSources), rawColumn);
                    Map<String, List<SourceTable>> participants = host.state().stackUsing.isEmpty()
                            ? null : host.state().usingParticipantsByIdentity.get(host.state().stackUsing.peek());
                    if (participants != null && participants.containsKey(columnKey)) {
                        unknown.removeAll(participants.get(columnKey));
                    }
                    if (!unknown.isEmpty()) {
                        throw new AmbiguousColumnException(columnName,
                                host.displayNames(DmlLineage.allSources(matchedSources, unknown)));
                    }
                    List<SourceTable> extras = new ArrayList<>();
                    List<SourceTable> usingSides = host.participantsForCurrentUsing(columnKey);
                    for (SourceTable source : sources) {
                        if (matchedSources.contains(source)
                                || usingSides.contains(source)
                                || !host.sourceColumnNamesKnown(source)) {
                            continue;
                        }
                        if (host.ownsOuterColumn(source, columnKey)) {
                            extras.add(source);
                        }
                    }
                    if (!extras.isEmpty()) {
                        throw new AmbiguousColumnException(columnName,
                                host.displayNames(DmlLineage.allSources(matchedSources, extras)));
                    }
                }
                return matchedSources;
            }
            if (matches.size() > 1) {
                throw new AmbiguousColumnException(columnName, host.displayNames(matchedSources));
            }
        }

        if (host.analysisContext() == null && !host.state().stackStarPlan.isEmpty()
                && !host.state().stackStarPlan.peek().isEmpty()) {
            // Ambiguity between fully known outputs is decided by the visible plan even
            // when another joined source is unknown.
            List<StarOutput> definite = new ArrayList<>();
            for (StarOutput output : host.state().stackStarPlan.peek()) {
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
                    ambiguous.addAll(DmlLineage.starOutputSources(output));
                }
                throw new AmbiguousColumnException(columnName, host.displayNames(ambiguous));
            }
        }

        boolean nodeNamesComplete = host.analysisContext() == null && !host.state().stackSourceNode.isEmpty()
                && host.nodeNamesKnown(host.state().stackSourceNode.peek());
        if (host.analysisContext() == null && (host.allSourceNamesKnown(sources) || nodeNamesComplete)
                && !host.state().stackStarPlan.isEmpty() && !host.state().stackStarPlan.peek().isEmpty()) {
            // The visible JOIN output plan already applies USING merge rules, so it is
            // authoritative once every source names its columns.
            List<StarOutput> matches = new ArrayList<>();
            for (StarOutput output : host.state().stackStarPlan.peek()) {
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
                    ambiguous.addAll(DmlLineage.starOutputSources(output));
                }
                throw new AmbiguousColumnException(columnName, host.displayNames(ambiguous));
            }
            throw new ColumnNotFoundException(columnName, host.displayNames(sources));
        }

        Map<String, List<SourceTable>> using = host.state().stackUsing.isEmpty()
                ? null : host.state().stackUsing.peek();
        if (using != null && using.containsKey(columnKey)) {
            List<SourceTable> participants = using.get(columnKey);
            List<SourceTable> sides = host.participantsForCurrentUsing(columnKey);
            List<SourceTable> extras = new ArrayList<>();
            for (SourceTable source : sources) {
                if (participants.contains(source) || sides.contains(source)) {
                    continue;
                }
                if (host.ownsOuterColumn(source, columnKey)
                        || !host.sourceColumnNamesKnown(source)) {
                    extras.add(source);
                }
            }
            if (!extras.isEmpty()) {
                List<SourceTable> all = new ArrayList<>(participants);
                all.addAll(extras);
                throw new AmbiguousColumnException(columnName, host.displayNames(all));
            }
            return participants;
        }

        if (host.analysisContext() == null) {
            if (host.detachedMetadata()) {
                // Without metadata the column must be attributable to exactly one source.
                List<SourceTable> owners = new ArrayList<>();
                for (SourceTable source : sources) {
                    if (source.outputNamesKnown) {
                        if (source.outputKeys.contains(columnKey)) {
                            owners.add(source);
                        }
                    } else if (source.learnable && host.learnedMetadata() != null
                            && source.rawName != null
                            && host.learnedMetadata().knowsNormalizedTable(
                                    host.learnedTableKey(source.rawName))) {
                        if (host.learnedMetadata().hasNormalizedColumn(
                                host.learnedTableKey(source.rawName), host.identifierKey(rawColumn))) {
                            owners.add(source);
                        }
                    } else {
                        owners.add(source);
                    }
                }
                owners = host.refineWithLearned(owners, rawColumn);
                if (owners.size() == 1) {
                    return owners;
                }
                if (owners.size() > 1) {
                    throw new AmbiguousColumnException(columnName, host.displayNames(owners));
                }
                throw new ColumnNotFoundException(columnName, host.displayNames(sources));
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
            throw new ColumnNotFoundException(columnName, host.displayNames(sources));
        }

        List<SourceTable> candidates = new ArrayList<>();
        boolean hasUnknownSource = false;
        for (SourceTable source : sources) {
            if (source.metadataTable != null) {
                if (MetadataCatalog.resolveColumnName(source.metadataTable, rawColumn,
                        host.currentDialect()) != null) {
                    candidates.add(source);
                }
            } else if (source.outputNamesKnown) {
                if (source.outputKeys.contains(columnKey)) {
                    candidates.add(source);
                }
            } else if (host.detachedMetadata()) {
                // The source has no known column set: it may own the column.
                candidates.add(source);
            } else {
                hasUnknownSource = true;
            }
        }
        if (host.detachedMetadata()) {
            candidates = host.refineWithLearned(candidates, rawColumn);
        }
        if (candidates.size() == 1) {
            return candidates;
        }
        if (candidates.size() > 1) {
            throw new AmbiguousColumnException(columnName, host.displayNames(candidates));
        }
        if (hasUnknownSource) {
            List<SourceTable> fallback = new ArrayList<>();
            fallback.add(sources.get(0));
            return fallback;
        }
        throw new ColumnNotFoundException(columnName, host.displayNames(sources));
    }
}

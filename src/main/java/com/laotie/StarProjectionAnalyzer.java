/*-
 * Author: Laotie
 * Licensed under Apache License 2.0
 * #L%
 */
package com.laotie;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.alibaba.druid.DbType;
import com.laotie.analysis.model.SourceNode;
import com.laotie.analysis.model.SourceTable;
import com.laotie.analysis.model.StarColumn;
import com.laotie.analysis.model.StarOutput;
import com.laotie.analysis.model.WildcardRef;
import com.laotie.analysis.model.WildcardSpan;
import com.laotie.model.Instruction;
import com.laotie.model.Instruction.OperationType;

/**
 * Emits the lineage instructions for {@code SELECT *} projections, in both
 * metadata-aware and metadata-free modes.
 */
final class StarProjectionAnalyzer {

    private final DmlLineage host;

    StarProjectionAnalyzer(DmlLineage host) {
        this.host = host;
    }

    String lineageTableName(SourceTable source) {
        if (source.temporary || source.metadataTable != null) {
            return source.displayName;
        }
        if (source.rawName != null) {
            return host.sessionPhysicalReference(source.rawName);
        }
        return source.writtenName == null ? source.displayName : source.writtenName;
    }

    void emitBindingColumns(List<StarColumn> columns) {
        if (columns.isEmpty()) {
            return;
        }
        String targetTable = host.state().stackTargetTable.peek();
        String targetColumn = host.state().stackTargetColumn.peek();
        for (StarColumn column : columns) {
            if (!column.source.wildcardContribution) {
                continue;
            }
            Instruction wildcard = new Instruction(OperationType.COLUMNS_INJECT,
                    lineageTableName(column.source), targetTable, host.state().currentOrdinal,
                    column.source.temporary, host.state().tempTables.contains(targetTable));
            wildcard.setPositionalWildcard(true);
            // The contribution only covers the named column's own position.
            wildcard.setWildcardWidthKnown(true);
            wildcard.setWildcardWidth(1);
            host.instructions().add(wildcard);
            host.state().targetWildcardContribution.put(targetTable, Boolean.TRUE);
        }
        if (host.state().uncertainOrdinal >= 0
                && host.state().currentOrdinal == host.state().uncertainOrdinal) {
            // The position is unknown: the table-level contribution above is enough.
            return;
        }
        for (StarColumn column : columns) {
            DbType dialect = host.currentDialect();
            String sourceColumn;
            if (column.source.temporary) {
                sourceColumn = renderSourceColumn(column);
            } else if (column.source.metadataTable == null
                    && (dialect == DbType.hive || dialect == DbType.antspark)) {
                sourceColumn = DmlLineage.renderIdentifier(
                        DmlLineage.hivePlannedColumn(column.column));
            } else if (column.quoted) {
                sourceColumn = renderSourceColumn(column);
            } else {
                sourceColumn = DmlLineage.renderIdentifier(
                        column.source.metadataTable == null
                                ? host.uncatalogedColumnSpelling(column.column)
                                : host.canonicalColumnName(column.column));
            }
            host.instructions().add(new Instruction(OperationType.COLUMN_MAPPING,
                    lineageTableName(column.source) + "." + sourceColumn,
                    targetTable + "." + targetColumn, host.state().currentOrdinal,
                    column.source.temporary, host.state().tempTables.contains(targetTable)));
        }
    }

    private String renderSourceColumn(StarColumn column) {
        if (column.quoted) {
            return DmlLineage.quoteIdentifierValue(column.column);
        }
        return DmlLineage.renderIdentifier(column.column);
    }

    void recordBindingStar(String target, SourceNode binding, int startPosition) {
        List<String> declared = host.currentTargetColumnAliases(target);
        List<String> declaredKeys = host.currentDeclaredKeys(target);
        int position = startPosition;
        for (StarOutput output : binding.plan) {
            if (output.sources.isEmpty()) {
                continue;
            }
            String lockedName = host.lockedOutputName(target, position);
            String lockedKey = host.lockedOutputKey(target, position);
            String name;
            String key;
            if (lockedName != null) {
                name = lockedName;
                key = lockedKey != null ? lockedKey : host.identifierKey(name);
            } else {
                boolean declaredName = declared != null && position < declared.size();
                boolean stableName = output.nameKnown && output.stable;
                name = declaredName ? declared.get(position)
                        : stableName ? output.sources.get(0).column
                        : "col" + (position + 1);
                key = declaredName && declaredKeys != null
                        && position < declaredKeys.size() ? declaredKeys.get(position)
                        : stableName ? output.sources.get(0).key : host.identifierKey(name);
                if (host.isExistingOutput(host.state().targetOutputs.get(target), name, position)) {
                    int suffix = position + 1;
                    do {
                        name = "col" + suffix++;
                        key = host.identifierKey(name);
                    } while (host.isExistingOutput(host.state().targetOutputs.get(target),
                            name, position));
                }
                host.markOutputNameKnown(target, position, declaredName || stableName);
            }
            host.setTargetOutput(target, position++, name, key);
        }
    }

    void mapBindingStar(String targetTable, SourceNode binding, int startPosition) {
        List<String> declared = host.currentTargetColumnAliases(targetTable);
        List<String> registered = host.state().targetOutputs.get(targetTable);
        boolean uncertain = host.state().uncertainOrdinal >= 0
                && host.state().uncertainOrdinal == startPosition;
        boolean targetTemporary = host.state().tempTables.contains(targetTable);
        int position = startPosition;
        for (StarOutput output : binding.plan) {
            if (output.sources.isEmpty()) {
                continue;
            }
            String lockedName = host.lockedOutputName(targetTable, position);
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
                        host.instructions().add(new Instruction(OperationType.COLUMNS_INJECT,
                                column.source.displayName, targetTable, position,
                                column.source.temporary, targetTemporary));
                        continue;
                    }
                    host.instructions().add(new Instruction(OperationType.COLUMN_MAPPING,
                            column.source.displayName + "." + renderSourceColumn(column),
                            targetTable + "." + DmlLineage.renderIdentifier(targetName), position,
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
    private boolean hasColumnMappingTo(String targetReference) {
        for (Instruction instruction : host.instructions()) {
            if ((instruction.getOperation() == OperationType.COLUMN_MAPPING
                    || instruction.getOperation() == OperationType.EXPR_MAPPING)
                    && targetReference.equals(instruction.getTo())) {
                return true;
            }
        }
        return false;
    }

    int emitDetachedStar(String targetTable, List<SourceTable> sources,
            int startPosition) {
        SourceNode node = host.state().stackSourceNode.isEmpty()
                ? null : host.state().stackSourceNode.peek();
        return emitDetachedPlan(targetTable, host.currentStarPlan(sources), sources, node,
                startPosition);
    }

    int emitDetachedPlan(String targetTable, List<StarOutput> template,
            List<SourceTable> sources, SourceNode node, int startPosition) {
        List<String> declared = host.currentTargetColumnAliases(targetTable);
        boolean targetTemporary = host.state().tempTables.contains(targetTable);

        boolean uncertainPosition = host.state().uncertainOrdinal >= 0
                && host.state().uncertainOrdinal == startPosition;
        boolean determinate = !sources.isEmpty() && !uncertainPosition;
        for (SourceTable source : sources) {
            if (!host.sourceNamesKnown(source)) {
                determinate = false;
                break;
            }
        }
        if (!determinate) {
            host.state().targetWildcardContribution.put(targetTable, Boolean.TRUE);
            // Contract: positions cannot be derived from column names. Propagate the
            // unknown sources recorded at each visible position instead of guessing.
            if (sources.size() == 1 && sources.get(0).temporary
                    && !host.wildcardSpansOf(sources.get(0).displayName).isEmpty()) {
                SourceTable source = sources.get(0);
                List<WildcardSpan> spans = host.wildcardSpansOf(source.displayName);
                int sourceWidth = uncertainPosition ? -1
                        : host.sourceWidthKnown(source)
                                ? Math.max(host.sourceKnownWidth(source), host.maxSpanEnd(spans))
                                : host.state().currentStarBudget > 0
                                        ? host.state().currentStarBudget : -1;
                for (WildcardSpan span : spans) {
                    if (uncertainPosition
                            || sourceWidth <= 0 && span.end == Integer.MAX_VALUE) {
                        // No width is known yet: keep the span open so an outer
                        // projection can bound it instead of shrinking it to one column.
                        Instruction wildcard = new Instruction(
                                OperationType.COLUMNS_INJECT, span.source, targetTable,
                                startPosition + span.start, span.temporary, targetTemporary);
                        wildcard.setPositionalWildcard(true);
                        host.instructions().add(wildcard);
                        host.recordWildcardSpan(targetTable, startPosition + span.start,
                                Integer.MAX_VALUE, span.source, span.temporary);
                        continue;
                    }
                    int width = sourceWidth > 0 ? sourceWidth : host.maxSpanEnd(spans);
                    int end = span.end == Integer.MAX_VALUE
                            ? Math.max(width, span.start + 1)
                            : Math.min(span.end, Math.max(width, span.start + 1));
                    for (int position = span.start; position < end; position++) {
                        Instruction wildcard = new Instruction(
                                OperationType.COLUMNS_INJECT, span.source, targetTable,
                                startPosition + position, span.temporary, targetTemporary);
                        wildcard.setPositionalWildcard(true);
                        wildcard.setWildcardWidthKnown(true);
                        wildcard.setWildcardWidth(1);
                        host.instructions().add(wildcard);
                        host.recordWildcardSpan(targetTable, startPosition + position,
                                startPosition + position + 1, span.source, span.temporary);
                    }
                }
                int totalWidth = Math.max(sourceWidth > 0 ? sourceWidth
                        : host.maxSpanEnd(spans), 1);
                List<String> sourceNames = host.sourceColumnNames(source);
                List<String> declaredTarget = host.currentTargetColumnAliases(targetTable);
                for (int relative = 0; !uncertainPosition
                        && relative < totalWidth; relative++) {
                    if (relative >= sourceNames.size()) {
                        continue;
                    }
                    String name = sourceNames.get(relative);
                    if (name == null) {
                        continue;
                    }
                    String sourceReference = source.displayName + "."
                            + DmlLineage.renderIdentifier(name);
                    boolean covered = DmlLineage.coveredBySpan(spans, relative);
                    if (covered) {
                        if (!hasColumnMappingTo(sourceReference)) {
                            continue;
                        }
                    } else if (!host.sourceOutputNameKnown(source, relative)) {
                        continue;
                    }
                    String outputName = declaredTarget != null
                            && startPosition + relative < declaredTarget.size()
                                    ? declaredTarget.get(startPosition + relative) : name;
                    String locked = host.lockedOutputName(targetTable,
                            startPosition + relative);
                    String lockedKey = host.lockedOutputKey(targetTable,
                            startPosition + relative);
                    if (locked != null) {
                        outputName = locked;
                    }
                    host.instructions().add(new Instruction(OperationType.COLUMN_MAPPING,
                            sourceReference,
                            targetTable + "." + DmlLineage.renderIdentifier(outputName),
                            startPosition + relative, source.temporary,
                            targetTemporary));
                    List<String> declaredKeys = host.currentDeclaredKeys(targetTable);
                    String declaredKey = declaredKeys != null
                            && startPosition + relative < declaredKeys.size()
                                    ? declaredKeys.get(startPosition + relative)
                                    : null;
                    String sourceKey = relative < source.outputKeys.size()
                            ? source.outputKeys.get(relative) : null;
                    host.setTargetOutput(targetTable, startPosition + relative,
                            outputName, lockedKey != null ? lockedKey
                                    : declaredKey != null ? declaredKey
                                            : sourceKey != null ? sourceKey
                                                    : host.identifierKey(outputName));
                }
                return totalWidth;
            }
            boolean planAmbiguous = node != null && node.widthKnown
                    && host.hasAmbiguousOutput(node.plan, node.width);
            boolean pendingShape = false;
            for (SourceTable source : sources) {
                if (source.temporary && !host.sourceNamesKnown(source)
                        && host.sourceWidthKnown(source)
                        && host.wildcardSpansOf(source.displayName).isEmpty()) {
                    // A forward CTE has a known width but its body has not run yet,
                    // so its wildcard provenance is still unknown; keep a table-level
                    // claim instead of emitting nothing.
                    pendingShape = true;
                    break;
                }
            }
            if (!planAmbiguous && !pendingShape && !uncertainPosition && node != null
                    && node.widthKnown && !node.plan.isEmpty()) {
                int position = startPosition;
                int limit = startPosition + node.width;
                Map<SourceTable, Integer> seen = new java.util.IdentityHashMap<>();
                for (StarOutput output : node.plan) {
                    if (position >= limit || output.sources.isEmpty()) {
                        continue;
                    }
                    for (StarColumn column : output.sources) {
                        SourceTable source = column.source;
                        int relative = seen.containsKey(source)
                                ? seen.get(source) : 0;
                        seen.put(source, relative + 1);
                        for (WildcardRef ref : host.wildcardSourcesAt(source, column,
                                relative)) {
                            Instruction wildcard = new Instruction(
                                    OperationType.COLUMNS_INJECT, ref.name, targetTable,
                                    position, ref.temporary, targetTemporary);
                            wildcard.setPositionalWildcard(true);
                            wildcard.setWildcardWidthKnown(true);
                            wildcard.setWildcardWidth(1);
                            host.instructions().add(wildcard);
                            host.recordWildcardSpan(targetTable, position, position + 1,
                                    ref.name, ref.temporary);
                        }
                    }
                    position++;
                }
                return Math.max(node.width, 1);
            }
            boolean pendingBounded = pendingShape && node != null && node.widthKnown;
            boolean ambiguousBounded = (planAmbiguous || pendingBounded)
                    && !uncertainPosition && node != null && node.widthKnown;
            boolean allKnown = !planAmbiguous && !pendingShape && !uncertainPosition
                    && !sources.isEmpty() && host.allWidthKnown(sources);
            boolean budgetBounded = !allKnown && !ambiguousBounded
                    && !uncertainPosition && host.state().currentStarBudget > 0;
            int position = startPosition;
            for (int index = 0; index < sources.size(); index++) {
                SourceTable source = sources.get(index);
                int sourceStart = allKnown ? position : startPosition;
                List<WildcardSpan> sourceSpans = source.temporary
                        ? host.wildcardSpansOf(source.displayName)
                        : java.util.Collections.<WildcardSpan>emptyList();
                if (allKnown && !sourceSpans.isEmpty()) {
                    // A derived source already knows where its own wildcards sit;
                    // re-emit those positions instead of covering its constant slots.
                    int width = host.sourceKnownWidth(source);
                    for (WildcardSpan span : sourceSpans) {
                        int start = sourceStart + span.start;
                        int end = Math.min(sourceStart + width,
                                span.end == Integer.MAX_VALUE ? start + 1
                                        : sourceStart + span.end);
                        for (int target = start; target < end; target++) {
                            Instruction perPosition = new Instruction(
                                    OperationType.COLUMNS_INJECT, span.source,
                                    targetTable, target, span.temporary, targetTemporary);
                            perPosition.setPositionalWildcard(true);
                            perPosition.setWildcardWidthKnown(true);
                            perPosition.setWildcardWidth(1);
                            host.instructions().add(perPosition);
                            host.recordWildcardSpan(targetTable, target, target + 1,
                                    span.source, span.temporary);
                        }
                    }
                    position += width;
                    continue;
                }
                Instruction wildcard = new Instruction(OperationType.COLUMNS_INJECT,
                        lineageTableName(source), targetTable, sourceStart,
                        source.temporary, targetTemporary);
                wildcard.setPositionalWildcard(true);
                int sourceWidth = -1;
                if (ambiguousBounded) {
                    // The projection's total width is fixed; each source keeps a
                    // table-level claim on that whole range, and nothing beyond it.
                    sourceWidth = Math.max(node.width, 1);
                } else if (allKnown) {
                    sourceWidth = host.sourceKnownWidth(source);
                } else if (budgetBounded) {
                    // The UNION width pins the whole star; every source keeps a
                    // table-level claim on that range.
                    sourceWidth = host.state().currentStarBudget;
                }
                if (sourceWidth > 0) {
                    wildcard.setWildcardWidthKnown(true);
                    wildcard.setWildcardWidth(sourceWidth);
                    for (int offset = 0; offset < sourceWidth; offset++) {
                        host.recordWildcardSpan(targetTable, sourceStart + offset,
                                sourceStart + offset + 1, lineageTableName(source),
                                source.temporary);
                    }
                    position += sourceWidth;
                } else {
                    host.recordWildcardSpan(targetTable, sourceStart, Integer.MAX_VALUE,
                            lineageTableName(source), source.temporary);
                }
                host.instructions().add(wildcard);
            }
            int advance;
            if (ambiguousBounded) {
                // The projection's total width is still fixed; only the per-position
                // ownership is unknown, so later projections keep their place.
                advance = node.width;
            } else if (budgetBounded) {
                advance = host.state().currentStarBudget;
            } else if (uncertainPosition) {
                advance = host.knownWidthSum(sources);
            } else {
                advance = position - startPosition;
            }
            return Math.max(advance,
                    ambiguousBounded || budgetBounded ? 1 : sources.size());
        }

        Set<String> used = new HashSet<>();
        int position = startPosition;
        for (StarOutput output : template) {
            if (output.sources.isEmpty()) {
                continue;
            }
            String lockedName = host.lockedOutputName(targetTable, position);
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
            if (exact && host.isExistingOutput(host.state().targetOutputs.get(targetTable),
                    name, position)) {
                exact = false;
            }
            if (exact) {
                for (StarColumn column : output.sources) {
                    host.instructions().add(new Instruction(OperationType.COLUMN_MAPPING,
                            column.source.displayName + "."
                                    + renderSourceColumn(column),
                            targetTable + "." + DmlLineage.renderIdentifier(name), position,
                            column.source.temporary, targetTemporary));
                }
                String outputKey;
                if (lockedName != null) {
                    outputKey = host.lockedOutputKey(targetTable, position);
                } else if (declared != null && position < declared.size()) {
                    List<String> declaredKeys = host.currentDeclaredKeys(targetTable);
                    outputKey = declaredKeys != null && position < declaredKeys.size()
                            ? declaredKeys.get(position) : host.identifierKey(name);
                } else {
                    outputKey = output.sources.get(0).key;
                }
                host.setTargetOutput(targetTable, position, name,
                        outputKey == null ? host.identifierKey(name) : outputKey);
                host.markOutputNameKnown(targetTable, position, true);
            } else {
                for (SourceTable source : DmlLineage.starOutputSources(output)) {
                    host.instructions().add(new Instruction(OperationType.COLUMNS_INJECT,
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
            host.instructions().add(wildcard);
            host.state().targetWildcardContribution.put(targetTable, Boolean.TRUE);
        }
        return position - startPosition;
    }

    void mapSourceStar(SourceTable source, String targetTable, int startPosition) {
        List<String> declared = host.currentTargetColumnAliases(targetTable);
        boolean uncertain = host.state().uncertainOrdinal >= 0
                && host.state().uncertainOrdinal == startPosition;
        boolean targetTemporary = host.state().tempTables.contains(targetTable);
        int position = startPosition;
        for (StarOutput output : host.planFromSource(source)) {
            if (output.sources.isEmpty()) {
                continue;
            }
            String lockedName = host.lockedOutputName(targetTable, position);
            String targetName = lockedName != null ? lockedName
                    : declared != null && position < declared.size()
                            ? declared.get(position) : output.sources.get(0).column;
            if (!uncertain) {
                for (StarColumn column : output.sources) {
                    host.instructions().add(new Instruction(OperationType.COLUMN_MAPPING,
                            column.source.displayName + "."
                                    + DmlLineage.renderIdentifier(column.column),
                            targetTable + "." + DmlLineage.renderIdentifier(targetName), position,
                            column.source.temporary, targetTemporary));
                }
            }
            position++;
        }
    }

    void visitAllColumn() {
        if (!host.state().inTargetExpression) {
            return;
        }
        List<SourceTable> sources = host.currentSources();
        if (sources.isEmpty()) {
            return;
        }
        String targetTable = host.state().stackTargetTable.peek();
        if (host.detachedMetadata()) {
            emitDetachedStar(targetTable, sources, host.state().currentOrdinal);
            return;
        }
        boolean targetTemporary = host.state().tempTables.contains(targetTable);
        Map<String, List<SourceTable>> using = host.state().stackUsing.isEmpty()
                ? null : host.state().stackUsing.peek();
        if (host.analysisContext() == null && !host.state().stackSourceNode.isEmpty()
                && !host.state().stackSourceNode.peek().plan.isEmpty()
                && (using != null && !using.isEmpty() || host.state().currentStarBudget > 0)) {
            mapBindingStar(targetTable, host.state().stackSourceNode.peek(),
                    host.state().currentOrdinal);
            return;
        }
        boolean planComplete = host.analysisContext() == null
                && host.allSourceNamesKnown(sources)
                && !host.state().stackStarPlan.isEmpty()
                && !host.state().stackStarPlan.peek().isEmpty();
        if (host.useVisiblePlan(sources, using) || planComplete) {
            List<String> declared = host.currentTargetColumnAliases(targetTable);
            boolean uncertain = host.state().uncertainOrdinal >= 0
                    && host.state().currentOrdinal == host.state().uncertainOrdinal;
            int position = host.state().currentOrdinal;
            for (StarOutput output : host.currentStarPlan(sources)) {
                if (output.sources.isEmpty()) {
                    continue;
                }
                String lockedName = host.lockedOutputName(targetTable, position);
                String targetName = lockedName != null ? lockedName
                        : declared != null && position < declared.size()
                                ? declared.get(position) : output.sources.get(0).column;
                if (!uncertain) {
                    for (StarColumn column : output.sources) {
                        host.instructions().add(new Instruction(OperationType.COLUMN_MAPPING,
                                column.source.displayName + "."
                                        + DmlLineage.renderIdentifier(column.column),
                                targetTable + "." + DmlLineage.renderIdentifier(targetName),
                                position, column.source.temporary, targetTemporary));
                    }
                }
                position++;
            }
            return;
        }
        if (host.analysisContext() == null) {
            host.instructions().add(new Instruction(OperationType.COLUMNS_INJECT,
                    sources.get(0).displayName, targetTable, host.state().currentOrdinal,
                    sources.get(0).temporary, targetTemporary));
            return;
        }
        int position = host.state().currentOrdinal;
        for (SourceTable source : sources) {
            host.instructions().add(new Instruction(OperationType.COLUMNS_INJECT,
                    source.displayName, targetTable, position, source.temporary,
                    targetTemporary));
            position += host.columnCount(source);
        }
    }

}

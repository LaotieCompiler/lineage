/*-
 * Author: Laotie
 * Licensed under Apache License 2.0
 * #L%
 */
package com.laotie;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.laotie.analysis.model.OutputName;
import com.laotie.analysis.model.SourceTable;
import com.laotie.analysis.model.StarColumn;
import com.laotie.analysis.model.StarOutput;
import com.laotie.analysis.model.WildcardRef;
import com.laotie.analysis.model.WildcardSpan;
import com.laotie.model.metadata.Column;

/**
 * Tracks the shape of intermediate targets: their output columns, wildcard
 * spans and the source groups behind unknown column names.
 */
final class TargetShapeAnalyzer {

    private final DmlLineage host;

    TargetShapeAnalyzer(DmlLineage host) {
        this.host = host;
    }

    int targetMinimumColumns(String target, boolean widthKnown, int outputCount) {
        if (widthKnown) {
            return outputCount;
        }
        Integer minimum = target == null ? null
                : host.state().targetMinWidth.get(target);
        return minimum == null ? 0 : minimum;
    }

    void recordWildcardSpan(String target, int start, int end, String source,
            boolean temporary) {
        if (target == null || start < 0) {
            return;
        }
        host.state().targetWildcardSpans.computeIfAbsent(target,
                key -> new ArrayList<WildcardSpan>())
                .add(new WildcardSpan(start, end, source, temporary));
    }

    List<WildcardSpan> wildcardSpansOf(String target) {
        List<WildcardSpan> spans = host.state().targetWildcardSpans.get(target);
        return spans == null ? java.util.Collections.<WildcardSpan>emptyList()
                : new ArrayList<>(spans);
    }

    int knownWidthSum(List<SourceTable> sources) {
        int sum = 0;
        for (SourceTable source : sources) {
            sum += host.sourceColumnsKnown(source)
                    ? Math.max(sourceColumnNames(source).size(), 1) : 1;
        }
        return Math.max(sum, 1);
    }

    boolean hasAmbiguousOutput(List<StarOutput> plan, int limit) {
        int position = 0;
        for (StarOutput output : plan) {
            if (position >= limit) {
                break;
            }
            if (output.ambiguous) {
                return true;
            }
            position++;
        }
        return false;
    }

    int maxSpanEnd(List<WildcardSpan> spans) {
        int max = 0;
        for (WildcardSpan span : spans) {
            if (span.end != Integer.MAX_VALUE) {
                max = Math.max(max, span.end);
            } else {
                max = Math.max(max, span.start + 1);
            }
        }
        return max;
    }

    private int relativeColumnIndex(SourceTable source, StarColumn column) {
        List<String> keys = host.sourceColumnKeys(source);
        if (column.key != null) {
            int found = -1;
            for (int i = 0; i < keys.size(); i++) {
                if (column.key.equals(keys.get(i))) {
                    if (found >= 0) {
                        // A duplicated name cannot identify a position; let the caller
                        // fall back to the plan's own occurrence order.
                        return -1;
                    }
                    found = i;
                }
            }
            if (found >= 0) {
                return found;
            }
        }
        List<String> names = sourceColumnNames(source);
        int found = -1;
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).equals(column.column)) {
                if (found >= 0) {
                    return -1;
                }
                found = i;
            }
        }
        return found;
    }

    List<WildcardRef> wildcardSourcesAt(SourceTable source, StarColumn column,
            int fallbackRelative) {
        List<WildcardRef> refs = new ArrayList<>();
        if (!source.temporary) {
            refs.add(new WildcardRef(host.lineageTableName(source), false));
            return refs;
        }
        int relative = column.index >= 0 ? column.index
                : relativeColumnIndex(source, column);
        if (relative < 0) {
            relative = fallbackRelative;
        }
        for (WildcardSpan span : wildcardSpansOf(source.displayName)) {
            if (relative >= span.start && relative < span.end) {
                WildcardRef ref = new WildcardRef(span.source, span.temporary);
                if (!refs.contains(ref)) {
                    refs.add(ref);
                }
            }
        }
        return refs;
    }

    boolean hasWildcardContribution(String target) {
        return target != null
                && Boolean.TRUE.equals(host.state().targetWildcardContribution.get(target));
    }

    List<SourceTable> copyUnknownGroups(String target, int size) {
        List<SourceTable> groups = target == null ? null
                : host.state().targetOutputUnknownGroup.get(target);
        List<SourceTable> result = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            result.add(groups != null && i < groups.size() ? groups.get(i) : null);
        }
        return result;
    }

    List<Boolean> copyNameKnown(String target, int size) {
        List<Boolean> flags = target == null ? null
                : host.state().targetOutputNameKnown.get(target);
        List<Boolean> result = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            result.add(flags != null && i < flags.size() && Boolean.TRUE.equals(flags.get(i)));
        }
        return result;
    }

    boolean isOutputWidthKnown(String target, List<String> outputs) {
        return Boolean.TRUE.equals(host.state().targetWidthKnown.get(target));
    }

    boolean isOutputNamesKnown(String target, List<String> outputs) {
        return !outputs.isEmpty()
                && Boolean.TRUE.equals(host.state().targetNamesKnown.get(target));
    }

    List<String> copyOutputs(List<String> outputs) {
        return outputs == null ? java.util.Collections.<String>emptyList()
                : new ArrayList<>(outputs);
    }

    List<String> copyKeys(String target) {
        List<String> keys = target == null ? null
                : host.state().targetOutputKeys.get(target);
        return keys == null ? java.util.Collections.<String>emptyList()
                : new ArrayList<>(keys);
    }

    Map<String, List<String>> namedTargetOutputs() {
        Map<String, List<String>> named = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : host.state().targetOutputs.entrySet()) {
            if (!host.state().intermediateTargets.contains(entry.getKey())) {
                named.put(entry.getKey(), copyOutputs(entry.getValue()));
            }
        }
        return named;
    }

    String lockedOutputName(String target, int position) {
        if (!host.state().unionTargets.contains(target)) {
            return null;
        }
        List<String> outputs = host.state().targetOutputs.get(target);
        return outputs != null && position < outputs.size() ? outputs.get(position) : null;
    }

    String lockedOutputKey(String target, int position) {
        if (!host.state().unionTargets.contains(target)) {
            return null;
        }
        List<String> keys = host.state().targetOutputKeys.get(target);
        return keys != null && position < keys.size() ? keys.get(position) : null;
    }

    void incrementNonAuthoritative(String target) {
        Integer depth = host.state().nonAuthoritativeDepth.get(target);
        host.state().nonAuthoritativeDepth.put(target, depth == null ? 1 : depth + 1);
    }

    void decrementNonAuthoritative(String target) {
        Integer depth = host.state().nonAuthoritativeDepth.get(target);
        if (depth == null || depth <= 1) {
            host.state().nonAuthoritativeDepth.remove(target);
        } else {
            host.state().nonAuthoritativeDepth.put(target, depth - 1);
        }
    }

    private void markUnknownGroup(String target, int position, SourceTable source) {
        if (target == null || source == null || position < 0) {
            return;
        }
        List<SourceTable> groups = host.state().targetOutputUnknownGroup
                .computeIfAbsent(target, k -> new ArrayList<SourceTable>());
        while (groups.size() <= position) {
            groups.add(null);
        }
        groups.set(position, source);
    }

    void markOutputNameKnown(String target, int position, boolean known) {
        if (target == null || position < 0) {
            return;
        }
        // Only the first (left-most) UNION branch names the result columns.
        Integer depth = host.state().nonAuthoritativeDepth.get(target);
        if (depth != null && depth > 0) {
            return;
        }
        List<Boolean> flags = host.state().targetOutputNameKnown
                .computeIfAbsent(target, k -> new ArrayList<Boolean>());
        while (flags.size() <= position) {
            flags.add(Boolean.FALSE);
        }
        flags.set(position, known);
    }

    void setTargetOutput(String target, int position, String name, String key) {
        List<String> outputs = host.state().targetOutputs
                .computeIfAbsent(target, k -> new ArrayList<String>());
        List<String> keys = host.state().targetOutputKeys
                .computeIfAbsent(target, k -> new ArrayList<String>());
        while (outputs.size() <= position) {
            outputs.add(null);
            keys.add(null);
        }
        outputs.set(position, name);
        keys.set(position, key);
    }

    void recordStarOutputs(String target, List<SourceTable> sources, int startPosition) {
        Map<String, List<SourceTable>> using = host.state().stackUsing.isEmpty()
                ? null : host.state().stackUsing.peek();
        if (host.useVisiblePlan(sources, using)) {
            List<String> declared = currentTargetColumnAliases(target);
            List<String> declaredKeys = currentDeclaredKeys(target);
            int position = startPosition;
            for (StarOutput output : currentStarPlan(sources)) {
                String lockedName = lockedOutputName(target, position);
                String lockedKey = lockedOutputKey(target, position);
                String name;
                String key;
                if (lockedName != null) {
                    name = lockedName;
                    key = lockedKey != null ? lockedKey : host.identifierKey(name);
                } else {
                    boolean declaredName = declared != null && position < declared.size();
                    name = declaredName
                            ? declared.get(position) : output.sources.get(0).column;
                    key = declaredKeys != null && position < declaredKeys.size()
                            ? declaredKeys.get(position) : output.sources.get(0).key;
                    if (isExistingOutput(host.state().targetOutputs.get(target), name, position)) {
                        int suffix = position + 1;
                        do {
                            name = "col" + suffix++;
                            key = host.identifierKey(name);
                        } while (isExistingOutput(host.state().targetOutputs.get(target),
                                name, position));
                        declaredName = true;
                    }
                    markOutputNameKnown(target, position, declaredName || output.nameKnown);
                }
                setTargetOutput(target, position++, name, key);
            }
            return;
        }
        List<OutputName> entries = starColumnEntries(target, sources, startPosition);
        for (int i = 0; i < entries.size(); i++) {
            OutputName entry = entries.get(i);
            setTargetOutput(target, startPosition + i, entry.name, entry.key);
        }
    }

    void recordSourceStarOutputs(String target, SourceTable source, int startPosition) {
        List<OutputName> entries = starColumnEntries(target,
                java.util.Collections.singletonList(source), startPosition);
        for (int i = 0; i < entries.size(); i++) {
            OutputName entry = entries.get(i);
            setTargetOutput(target, startPosition + i, entry.name, entry.key);
        }
    }

    private List<OutputName> starColumnEntries(String target, List<SourceTable> sources,
            int startPosition) {
        List<String> declared = currentTargetColumnAliases(target);
        List<String> declaredKeys = currentDeclaredKeys(target);
        List<String> existing = host.state().targetOutputs.get(target);
        List<OutputName> entries = new ArrayList<>();
        Set<String> used = new HashSet<>();
        int position = startPosition;
        for (SourceTable source : sources) {
            List<String> names = sourceColumnNames(source);
            List<String> keys = host.sourceColumnKeys(source);
            for (int i = 0; i < names.size(); i++) {
                String lockedName = lockedOutputName(target, position);
                String lockedKey = lockedOutputKey(target, position);
                if (lockedName != null) {
                    entries.add(new OutputName(lockedName,
                            lockedKey != null ? lockedKey : host.identifierKey(lockedName)));
                    used.add(lockedName);
                    position++;
                    continue;
                }
                boolean declaredName = declared != null && position < declared.size();
                String name = declaredName ? declared.get(position) : names.get(i);
                String key = declaredKeys != null && position < declaredKeys.size()
                        ? declaredKeys.get(position)
                        : i < keys.size() ? keys.get(i) : host.identifierKey(name);
                if (used.contains(name) || isExistingOutput(existing, name, position)) {
                    int suffix = position + 1;
                    do {
                        name = "col" + suffix++;
                        key = host.identifierKey(name);
                    } while (used.contains(name) || isExistingOutput(existing, name, position));
                }
                markOutputNameKnown(target, position,
                        declaredName || host.sourceOutputNameKnown(source, i));
                if (!declaredName && !host.sourceOutputNameKnown(source, i)) {
                    SourceTable group = i < source.outputUnknownGroups.size()
                            && source.outputUnknownGroups.get(i) != null
                                    ? source.outputUnknownGroups.get(i) : source;
                    markUnknownGroup(target, position, group);
                }
                entries.add(new OutputName(name, key));
                used.add(name);
                position++;
            }
        }
        return entries;
    }

    List<String> currentDeclaredKeys(String target) {
        for (int i = host.state().targetColumnAliases.size() - 1; i >= 0; i--) {
            if (host.state().targetColumnAliases.get(i).containsKey(target)) {
                List<String> keys = host.state().cteDeclaredKeys.get(target);
                return keys == null ? java.util.Collections.<String>emptyList() : keys;
            }
        }
        return null;
    }

    boolean isExistingOutput(List<String> existing, String name, int position) {
        if (existing == null) {
            return false;
        }
        for (int i = 0; i < existing.size(); i++) {
            if (i != position && name.equals(existing.get(i))) {
                return true;
            }
        }
        return false;
    }

    List<String> sourceColumnNames(SourceTable source) {
        if (source.metadataTable != null) {
            List<String> columns = new ArrayList<>();
            for (Column column : source.metadataTable.getFields()) {
                columns.add(column.getColumnName());
            }
            return columns;
        }
        if (!source.outputColumns.isEmpty()) {
            return source.outputColumns;
        }
        return java.util.Collections.singletonList("col1");
    }

    List<StarOutput> currentStarPlan(List<SourceTable> sources) {
        if (!host.state().stackStarPlan.isEmpty()
                && !host.state().stackStarPlan.peek().isEmpty()) {
            return host.state().stackStarPlan.peek();
        }
        List<StarOutput> plan = new ArrayList<>();
        for (SourceTable source : sources) {
            plan.addAll(host.planFromSource(source));
        }
        return plan;
    }

    List<String> currentTargetColumnAliases(String target) {
        for (int i = host.state().targetColumnAliases.size() - 1; i >= 0; i--) {
            Map<String, List<String>> aliases = host.state().targetColumnAliases.get(i);
            if (aliases.containsKey(target)) {
                return aliases.get(target);
            }
        }
        return null;
    }
}

package com.laotie.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import com.laotie.model.Instruction.OperationType;
import com.laotie.model.metadata.Column;
import com.laotie.model.metadata.MetadataContext;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.DirectedMultigraph;
import org.jgrapht.traverse.DepthFirstIterator;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LineageGraph {
    Graph<Column, DefaultEdge> reverseGraph; // a directed graph that maps the target column to the source column

    public LineageGraph() {
        init();
    }

    void init() {
        reverseGraph = new DirectedMultigraph<>(DefaultEdge.class);
    }

    public void buildByInstructions(List<Instruction> instructions) {
        init();
        for (Instruction instruction : instructions) {
            if (instruction.getOperation() == OperationType.COLUMN_MAPPING) {
                Column from = new Column(instruction.getFrom());
                Column to = new Column(instruction.getTo());
                reverseGraph.addVertex(from);
                reverseGraph.addVertex(to);
                reverseGraph.addEdge(to, from); // reverse the data flow direction
            }
        }
    }

    /**
     * Merge several statements into one global graph.
     *
     * <p>
     * Temporary targets are namespaced per statement ({@code stmt0.temp0.c1}), and wildcard
     * instructions are expanded with each result's own metadata snapshot. Failed results are
     * skipped.
     */
    public void buildByResults(List<LineageResult> results) {
        init();
        Map<String, Set<String>> populatedColumns = new HashMap<>();
        Map<String, Map<Integer, String>> tempColumnsByPosition = new HashMap<>();
        Map<String, Integer> tempMaxPosition = new HashMap<>();
        Map<String, Integer> tempProjectionWidth = new HashMap<>();
        Map<String, java.util.List<WildcardRegion>> tempWildcardRegions = new HashMap<>();
        Set<String> tempWidthKnown = new HashSet<>();
        java.util.List<Runnable> pendingWildcardLinks = new java.util.ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            LineageResult result = results.get(i);
            if (result.getError() != null) {
                continue;
            }
            List<Instruction> instructions = result.getInstructions();
            if (result.getContext() != null) {
                instructions = Instruction.toColumnInstructions(instructions,
                        result.getContext(), result.getTargetOutputs());
            }
            String prefix = "stmt" + i + ".";
            Map<String, Map<Integer, String>> effectivePositions =
                    assignTemporaryPositions(instructions, prefix);
            for (Instruction instruction : instructions) {
                if (instruction.getOperation() == OperationType.COLUMNS_INJECT
                        && result.isTableLevelWildcards()) {
                    // A wildcard whose columns are unknown stays a table-level edge.
                    Column from = wildcardColumn(instruction.getFrom(), prefix,
                            instruction.isFromTemporary());
                    String toReference = instruction.getWildcardTable() != null
                            ? instruction.getWildcardTable() : instruction.getTo();
                    Column to = wildcardColumn(toReference, prefix,
                            instruction.isToTemporary());
                    if (instruction.isToTemporary() && instruction.getOrdinal() >= 0) {
                        String key = prefix + instruction.getTo().split("\\(")[0];
                        tempMaxPosition.merge(key, instruction.getOrdinal(),
                                Math::max);
                        int start = instruction.getOrdinal();
                        int end = instruction.isWildcardWidthKnown()
                                && instruction.getWildcardWidth() > 0
                                        ? start + instruction.getWildcardWidth()
                                        : Integer.MAX_VALUE;
                        if (instruction.isWildcardWidthKnown()) {
                            tempWidthKnown.add(key);
                            if (instruction.getWildcardWidth() > 0) {
                                tempProjectionWidth.merge(key, end, Math::max);
                            }
                        }
                        java.util.List<WildcardRegion> regions = tempWildcardRegions
                                .computeIfAbsent(key,
                                        k -> new java.util.ArrayList<WildcardRegion>());
                        // Every wildcard instruction keeps its own bounds; overlapping
                        // regions contribute their sources independently.
                        WildcardRegion region = new WildcardRegion(start, end);
                        region.sources.add(from);
                        regions.add(region);
                    }
                    if (!instruction.isToTemporary()) {
                        // Only writes establish which columns a table-level wildcard
                        // covers; reads from a table are not writes.
                        if (instruction.getPopulatedColumns() != null) {
                            recordPopulatedColumns(populatedColumns, toReference,
                                    instruction.getPopulatedColumns());
                            if (!to.isTemporary()) {
                                Column target = to;
                                Column source = from;
                                String columns = instruction.getPopulatedColumns();
                                String fromTable = instruction.getFrom();
                                boolean fromTemporary = instruction.isFromTemporary();
                                int targetWidth = physicalColumnNames(
                                        to.getTableName(), result.getContext()).size();
                                pendingWildcardLinks.add(() -> linkPopulatedColumns(target,
                                        source, columns, targetWidth, true, prefix,
                                        fromTable, fromTemporary, tempColumnsByPosition,
                                        tempWidthKnown, tempWildcardRegions));
                            }
                        } else if (!to.isTemporary() && result.getContext() != null) {
                            java.util.List<String> targetColumns =
                                    physicalColumnNames(to.getTableName(), result.getContext());
                            java.util.List<String> projected =
                                    result.getTargetOutputs().get(instruction.getFrom());
                            String fromKey = prefix + instruction.getFrom();
                            if (targetColumns.isEmpty()) {
                                targetColumns = new java.util.ArrayList<>();
                            }
                            if (projected != null && !projected.isEmpty()) {
                                targetColumns = targetColumns.subList(0,
                                        Math.min(projected.size(), targetColumns.size()));
                            } else if (tempWidthKnown.contains(fromKey)) {
                                Integer declared = tempProjectionWidth.get(fromKey);
                                Integer max = tempMaxPosition.get(fromKey);
                                int width = Math.max(declared == null ? 0 : declared,
                                        max == null ? 0 : max + 1);
                                if (width > 0 && width < targetColumns.size()) {
                                    targetColumns = targetColumns.subList(0, width);
                                }
                            } else {
                                targetColumns = new java.util.ArrayList<>(targetColumns);
                            }
                                Column target = to;
                                Column source = from;
                                java.util.List<String> names = targetColumns;
                                int targetWidth = physicalColumnNames(
                                        to.getTableName(), result.getContext()).size();
                                String fromTable = instruction.getFrom();
                                boolean fromTemporary = instruction.isFromTemporary();
                                pendingWildcardLinks.add(() -> linkPopulatedColumns(target,
                                        source, names, targetWidth, false, prefix,
                                        fromTable, fromTemporary, tempColumnsByPosition,
                                        tempWidthKnown, tempWildcardRegions));
                            }
                    }
                    reverseGraph.addVertex(from);
                    reverseGraph.addVertex(to);
                    if (!from.equals(to)) {
                        reverseGraph.addEdge(to, from);
                    }
                    continue;
                }
                if (instruction.getOperation() == OperationType.EXPR_MAPPING) {
                    if (instruction.isToTemporary() && instruction.getOrdinal() >= 0) {
                        String[] parts = splitReference(instruction.getTo());
                        if (parts != null) {
                            String tableKey = prefix + parts[0];
                            tempColumnsByPosition
                                    .computeIfAbsent(tableKey,
                                            key -> new HashMap<Integer, String>())
                                    .put(instruction.getOrdinal(), CONSTANT_COLUMN);
                            // A constant still occupies a position, so the temporary's
                            // effective width must include it.
                            tempMaxPosition.merge(tableKey, instruction.getOrdinal(),
                                    Math::max);
                        }
                    }
                    continue;
                }
                if (instruction.getOperation() != OperationType.COLUMN_MAPPING) {
                    continue;
                }
                String toReference = instruction.getTo();
                if (instruction.isToTemporary() && instruction.getOrdinal() >= 0) {
                    String[] parts = splitReference(toReference);
                    if (parts != null) {
                        String tableKey = prefix + parts[0];
                        Map<Integer, String> effective = effectivePositions.get(tableKey);
                        String columnName = effective != null
                                && effective.containsKey(instruction.getOrdinal())
                                        ? effective.get(instruction.getOrdinal())
                                        : parts[1];
                        tempColumnsByPosition
                                .computeIfAbsent(tableKey,
                                        key -> new HashMap<Integer, String>())
                                .put(instruction.getOrdinal(), columnName);
                        tempMaxPosition.merge(tableKey, instruction.getOrdinal(),
                                Math::max);
                        toReference = parts[0] + "." + columnName;
                    }
                }
                Column from = graphColumn(instruction.getFrom(), prefix,
                        instruction.isFromTemporary());
                Column to = graphColumn(toReference, prefix,
                        instruction.isToTemporary());
                reverseGraph.addVertex(from);
                reverseGraph.addVertex(to);
                if (!from.equals(to)) {
                    // Self-updates such as SET c1 = c1 + 1 add no external lineage.
                    reverseGraph.addEdge(to, from);
                }
            }
        }
        for (Runnable link : pendingWildcardLinks) {
            link.run();
        }
        attachTableWildcards(populatedColumns);
    }

    /**
     * A physical column read from a table whose only lineage is a table-level wildcard
     * (for example a table populated by INSERT ... SELECT * FROM an unknown table) is
     * connected to that wildcard, so cross-statement lineage stays intact.
     */
    private void attachTableWildcards(Map<String, Set<String>> populatedColumns) {
        Map<String, Column> wildcards = new HashMap<>();
        for (Column vertex : reverseGraph.vertexSet()) {
            if (vertex.isWildcard() && !vertex.isTemporary()) {
                wildcards.put(vertex.getTableName(), vertex);
            }
        }
        if (wildcards.isEmpty()) {
            return;
        }
        for (Column vertex : new ArrayList<>(reverseGraph.vertexSet())) {
            if (vertex.isWildcard() || vertex.isTemporary()
                    || vertex.getTableName() == null) {
                continue;
            }
            Column wildcard = wildcards.get(vertex.getTableName());
            if (wildcard == null || wildcard.equals(vertex)) {
                continue;
            }
            if (reverseGraph.outDegreeOf(vertex) != 0) {
                continue;
            }
            Set<String> populated = populatedColumns.get(vertex.getTableName());
            if (populated != null && populated.isEmpty()) {
                reverseGraph.addEdge(vertex, wildcard);
            }
        }
    }

    /**
     * Link each explicitly written column to the wildcard that populated it, so two
     * INSERTs into the same table keep their own sources.
     */
    private void linkPopulatedColumns(Column target, Column sourceWildcard, String columns,
            int targetWidth, boolean explicitTargets, String prefix, String fromTable,
            boolean fromTemporary,
            Map<String, Map<Integer, String>> tempColumnsByPosition,
            Set<String> tempWidthKnown,
            Map<String, java.util.List<WildcardRegion>> tempWildcardRegions) {
        linkPopulatedColumns(target, sourceWildcard, splitColumns(columns),
                targetWidth, explicitTargets, prefix, fromTable, fromTemporary,
                tempColumnsByPosition, tempWidthKnown, tempWildcardRegions);
    }

    private void linkPopulatedColumns(Column target, Column sourceWildcard,
            java.util.List<String> names, int targetWidth, boolean explicitTargets,
            String prefix, String fromTable, boolean fromTemporary,
            Map<String, Map<Integer, String>> tempColumnsByPosition,
            Set<String> tempWidthKnown,
            Map<String, java.util.List<WildcardRegion>> tempWildcardRegions) {
        Map<Integer, String> tempPositions = fromTemporary
                ? tempColumnsByPosition.get(prefix + fromTable) : null;
        for (int i = 0; i < names.size(); i++) {
            String column = names.get(i);
            Column columnVertex = new Column(target.getTableName(), column);
            reverseGraph.addVertex(columnVertex);
            reverseGraph.addVertex(sourceWildcard);
            if (columnVertex.equals(sourceWildcard)) {
                continue;
            }
            String tempColumn = tempPositions != null && tempPositions.containsKey(i)
                    ? tempPositions.get(i) : null;
            String tempKey = prefix + fromTable;
            java.util.List<WildcardRegion> regions = tempWildcardRegions.get(tempKey);
            java.util.List<Column> positionalSources = positionalWildcardSources(regions, i);
            if (CONSTANT_COLUMN.equals(tempColumn)) {
                for (Column positionalSource : positionalSources) {
                    // Another UNION branch feeds this position from an unknown table.
                    reverseGraph.addEdge(columnVertex, positionalSource);
                }
                continue;
            }
            if (tempColumn != null) {
                Column precise = graphColumn(fromTable + "." + tempColumn, prefix,
                        fromTemporary);
                reverseGraph.addVertex(precise);
                if (!columnVertex.equals(precise)) {
                    reverseGraph.addEdge(columnVertex, precise);
                }
                if (explicitTargets
                        || targetWidth > 0 && names.size() == targetWidth) {
                    for (Column positionalSource : positionalSources) {
                        reverseGraph.addVertex(positionalSource);
                        if (!columnVertex.equals(positionalSource)) {
                            reverseGraph.addEdge(columnVertex, positionalSource);
                        }
                    }
                }
                continue;
            }
            if (regions != null && !regions.isEmpty()) {
                if (positionalSources.isEmpty()) {
                    // Known widths bound the projection: this position is not fed by
                    // the write's wildcard.
                    continue;
                }
                for (Column positionalSource : positionalSources) {
                    reverseGraph.addEdge(columnVertex, positionalSource);
                }
                continue;
            }
            reverseGraph.addEdge(columnVertex, sourceWildcard);
        }
    }

    private static java.util.List<Column> positionalWildcardSources(
            java.util.List<WildcardRegion> regions, int position) {
        java.util.List<Column> sources = new java.util.ArrayList<>();
        if (regions == null) {
            return sources;
        }
        for (WildcardRegion region : regions) {
            if (position >= region.start && position < region.end) {
                for (Column source : region.sources) {
                    if (!sources.contains(source)) {
                        sources.add(source);
                    }
                }
            }
        }
        return sources;
    }

    private static final class WildcardRegion {
        private final int start;
        private final int end;
        private final java.util.List<Column> sources = new java.util.ArrayList<>();

        private WildcardRegion(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }

    private static java.util.List<String> splitColumns(String columns) {
        java.util.List<String> result = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < columns.length(); i++) {
            char ch = columns.charAt(i);
            if (quote != 0) {
                current.append(ch);
                if (ch == quote) {
                    if (i + 1 < columns.length()
                            && columns.charAt(i + 1) == quote) {
                        current.append(columns.charAt(++i));
                    } else {
                        quote = 0;
                    }
                }
            } else if (ch == '"' || ch == '`') {
                quote = ch;
                current.append(ch);
            } else if (ch == ',') {
                result.add(stripQuotes(current.toString()));
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        result.add(stripQuotes(current.toString()));
        return result;
    }

    /**
     * Remember which target columns an INSERT-style wildcard populated. A table-level
     * edge must not attribute unwritten columns.
     */
    private static void recordPopulatedColumns(Map<String, Set<String>> populatedColumns,
            String reference) {
        int lparen = reference.indexOf('(');
        if (lparen <= 0 || !reference.endsWith(")")) {
            return;
        }
        recordPopulatedColumns(populatedColumns, reference.substring(0, lparen),
                reference.substring(lparen + 1, reference.length() - 1));
    }

    private static void recordPopulatedColumns(Map<String, Set<String>> populatedColumns,
            String table, String columns) {
        Set<String> columnsToAdd = populatedColumns.computeIfAbsent(table,
                key -> new HashSet<String>());
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < columns.length(); i++) {
            char ch = columns.charAt(i);
            if (quote != 0) {
                current.append(ch);
                if (ch == quote) {
                    quote = 0;
                }
            } else if (ch == '"' || ch == '`') {
                quote = ch;
                current.append(ch);
            } else if (ch == ',') {
                columnsToAdd.add(stripQuotes(current.toString()));
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        columnsToAdd.add(stripQuotes(current.toString()));
    }

    /**
     * Split {@code table.column} at the last dot outside quotes.
     */
    private static String[] splitReference(String reference) {
        if (reference == null) {
            return null;
        }
        char quote = 0;
        int dot = -1;
        for (int i = 0; i < reference.length(); i++) {
            char ch = reference.charAt(i);
            if (quote != 0) {
                if (ch == quote) {
                    quote = 0;
                }
            } else if (ch == '"' || ch == '`') {
                quote = ch;
            } else if (ch == '[') {
                quote = ']';
            } else if (ch == '.') {
                dot = i;
            }
        }
        if (dot <= 0 || dot == reference.length() - 1) {
            return null;
        }
        return new String[] {reference.substring(0, dot), reference.substring(dot + 1)};
    }

    /**
     * Assign one vertex per temporary position. A name used by several positions is
     * kept for the first one; later positions get a generated name that does not
     * collide with any preserved or generated name.
     */
    private static Map<String, Map<Integer, String>> assignTemporaryPositions(
            List<Instruction> instructions, String prefix) {
        Map<String, Map<Integer, String>> raw = new HashMap<>();
        Map<String, Map<String, Integer>> counts = new HashMap<>();
        for (Instruction instruction : instructions) {
            if (!instruction.isToTemporary() || instruction.getOrdinal() < 0
                    || instruction.getOperation() != OperationType.COLUMN_MAPPING
                            && instruction.getOperation() != OperationType.EXPR_MAPPING) {
                continue;
            }
            if (instruction.getOperation() != OperationType.COLUMN_MAPPING
                    && instruction.getOperation() != OperationType.EXPR_MAPPING) {
                continue;
            }
            String[] parts = splitReference(instruction.getTo());
            if (parts == null) {
                continue;
            }
            String tableKey = prefix + parts[0];
            raw.computeIfAbsent(tableKey, key -> new HashMap<Integer, String>())
                    .put(instruction.getOrdinal(), parts[1]);
            counts.computeIfAbsent(tableKey,
                    key -> new HashMap<String, Integer>())
                    .merge(parts[1], 1, Integer::sum);
        }
        Map<String, Map<Integer, String>> effective = new HashMap<>();
        for (Map.Entry<String, Map<Integer, String>> entry : raw.entrySet()) {
            String tableKey = entry.getKey();
            Map<String, java.util.Set<Integer>> positionsByName = new HashMap<>();
            for (Map.Entry<Integer, String> position : entry.getValue().entrySet()) {
                positionsByName.computeIfAbsent(position.getValue(),
                        key -> new java.util.HashSet<Integer>()).add(position.getKey());
            }
            Map<String, Integer> tableCounts = new HashMap<>();
            java.util.Set<String> usedNames = new java.util.HashSet<>();
            for (Map.Entry<String, java.util.Set<Integer>> byName : positionsByName.entrySet()) {
                tableCounts.put(byName.getKey(), byName.getValue().size());
                // The name is kept by its first position, so a generated name must not
                // take it, even when the group has several positions.
                usedNames.add(byName.getKey());
            }
            java.util.List<Integer> positions = new java.util.ArrayList<>(
                    entry.getValue().keySet());
            java.util.Collections.sort(positions);
            java.util.Set<String> duplicatesSeen = new java.util.HashSet<>();
            Map<Integer, String> tableEffective = new HashMap<>();
            for (Integer position : positions) {
                String name = entry.getValue().get(position);
                if (tableCounts.get(name) > 1 && !duplicatesSeen.add(name)) {
                    // The first position keeps the shared name; later ones get a
                    // generated name that does not collide with anything in the table.
                    String candidate = "col" + (position + 1);
                    int suffix = 1;
                    while (usedNames.contains(candidate)
                            || containsValue(tableEffective, candidate)) {
                        candidate = "col" + (position + 1) + "_" + suffix++;
                    }
                    name = candidate;
                }
                tableEffective.put(position, name);
            }
            effective.put(tableKey, tableEffective);
        }
        return effective;
    }

    private static boolean containsValue(Map<Integer, String> map, String value) {
        return map.containsValue(value);
    }

    private static java.util.List<String> physicalColumnNames(String table,
            com.laotie.model.metadata.MetadataContext context) {
        try {
            com.laotie.model.metadata.Table metadata = context.getCatalog()
                    .findTableByDisplayName(table).getTable();
            java.util.List<String> names = new java.util.ArrayList<>();
            for (Column column : metadata.getFields()) {
                names.add(column.getColumnName());
            }
            return names;
        } catch (RuntimeException e) {
            return java.util.Collections.emptyList();
        }
    }

    private static String stripQuotes(String name) {
        String value = name.trim();
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '`' && last == '`')
                    || (first == '[' && last == ']')) {
                String inner = value.substring(1, value.length() - 1);
                char close = first == '[' ? ']' : first;
                if (first == '"') {
                    return decodeDoubleQuoted(inner);
                }
                return inner.replace(String.valueOf(close) + close,
                        String.valueOf(close));
            }
        }
        return value;
    }

    private static String decodeDoubleQuoted(String inner) {
        StringBuilder decoded = new StringBuilder(inner.length());
        for (int i = 0; i < inner.length(); i++) {
            char ch = inner.charAt(i);
            char next = i + 1 < inner.length() ? inner.charAt(i + 1) : 0;
            if (ch == '\\' && next == '\\') {
                decoded.append('\\');
                i++;
            } else if (ch == '\\' && next == '"') {
                decoded.append('"');
                i++;
            } else if (ch == '"' && next == '"') {
                decoded.append('"');
                i++;
            } else {
                decoded.append(ch);
            }
        }
        return decoded.toString();
    }

    private static final String CONSTANT_COLUMN = "\u0000constant";

    private static Column graphColumn(String reference, String prefix, boolean temporary) {
        if (temporary) {
            return Column.temporary(prefix + reference);
        }
        return new Column(reference);
    }

    /**
     * The vertex of a table-level wildcard: {@code db.schema.table.*}.
     */
    private static Column wildcardColumn(String reference, String prefix, boolean temporary) {
        // The reference is already a table identity: an INSERT column list is stripped
        // when instructions are expanded, so a table name containing parentheses stays.
        String qualified = temporary ? prefix + reference : reference;
        return temporary
                ? Column.temporary(qualified + ".*")
                : new Column(qualified + ".*");
    }

    public Set<Column> getSources(Column targetVertex) {
        Set<Column> reachableVertices = new HashSet<>();
        DepthFirstIterator<Column, DefaultEdge> dfsIterator = new DepthFirstIterator<>(reverseGraph, targetVertex);
        while (dfsIterator.hasNext()) {
            reachableVertices.add(dfsIterator.next());
        }

        Set<Column> sourceVertices = new HashSet<>();
        for (Column vertex : reachableVertices) {
            if (reverseGraph.outDegreeOf(vertex) == 0) {
                sourceVertices.add(vertex);
            }
        }
        return sourceVertices;
    }

    /**
     * BFS to find all reachable vertices from target vertices
     * 
     * @param targetVertices
     * @return
     */
    public Map<Column, Set<Column>> getSources(Set<Column> targetVertices) {
        Map<Column, Set<Column>> sources = new HashMap<>();
        Map<Column, Set<Column>> fromMap = new HashMap<>();
        Set<Column> visited = new HashSet<>();
        Queue<Column> queue = new LinkedList<>();

        for (Column targetVertex : targetVertices) {
            queue.add(targetVertex);
            visited.add(targetVertex);
        }

        while (!queue.isEmpty()) {
            Column vertex = queue.poll();
            visited.add(vertex);
            for (DefaultEdge edge : reverseGraph.outgoingEdgesOf(vertex)) {
                Column neighbor = reverseGraph.getEdgeTarget(edge);
                if (targetVertices.contains(vertex)) {
                    fromMap.putIfAbsent(neighbor, new HashSet<>());
                    fromMap.get(neighbor).add(vertex);
                }
                if (fromMap.containsKey(vertex)) {
                    fromMap.putIfAbsent(neighbor, new HashSet<>());
                    fromMap.get(neighbor).addAll(fromMap.get(vertex));
                }
                if (!visited.contains(neighbor)) {
                    queue.add(neighbor);
                    visited.add(neighbor);
                }
            }
        }

        // update the sources map
        for (Column vertex : fromMap.keySet()) {
            if (reverseGraph.outDegreeOf(vertex) == 0) {
                for (Column targetColumn: fromMap.get(vertex)) {
                    if (!targetVertices.contains(targetColumn)){
                        continue;
                    }
                    sources.putIfAbsent(targetColumn, new HashSet<>());
                    sources.get(targetColumn).add(vertex);
                }
            }
        }

        return sources;
    }

}

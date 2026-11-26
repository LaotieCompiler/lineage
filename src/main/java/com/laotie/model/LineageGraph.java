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
                                pendingWildcardLinks.add(() -> linkPopulatedColumns(target,
                                        source, columns, prefix, fromTable, fromTemporary,
                                        tempColumnsByPosition));
                            }
                        } else if (!to.isTemporary() && result.getContext() != null) {
                            java.util.List<String> targetColumns =
                                    physicalColumnNames(to.getTableName(), result.getContext());
                            if (!targetColumns.isEmpty()) {
                                Column target = to;
                                Column source = from;
                                String fromTable = instruction.getFrom();
                                boolean fromTemporary = instruction.isFromTemporary();
                                pendingWildcardLinks.add(() -> linkPopulatedColumns(target,
                                        source, String.join(",", targetColumns), prefix,
                                        fromTable, fromTemporary, tempColumnsByPosition));
                            }
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
                            tempColumnsByPosition
                                    .computeIfAbsent(prefix + parts[0],
                                            key -> new HashMap<Integer, String>())
                                    .put(instruction.getOrdinal(), CONSTANT_COLUMN);
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
            String prefix, String fromTable, boolean fromTemporary,
            Map<String, Map<Integer, String>> tempColumnsByPosition) {
        java.util.List<String> names = splitColumns(columns);
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
            if (CONSTANT_COLUMN.equals(tempColumn)) {
                // The position is an explicit constant: it has no source.
                continue;
            }
            if (tempColumn != null) {
                Column precise = graphColumn(fromTable + "." + tempColumn, prefix,
                        fromTemporary);
                reverseGraph.addVertex(precise);
                if (!columnVertex.equals(precise)) {
                    reverseGraph.addEdge(columnVertex, precise);
                }
                continue;
            }
            reverseGraph.addEdge(columnVertex, sourceWildcard);
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
                    quote = 0;
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
            if (instruction.getOperation() != OperationType.COLUMN_MAPPING
                    || !instruction.isToTemporary() || instruction.getOrdinal() < 0) {
                continue;
            }
            String[] parts = splitReference(instruction.getTo());
            if (parts == null) {
                continue;
            }
            String tableKey = prefix + parts[0];
            raw.computeIfAbsent(tableKey, key -> new HashMap<Integer, String>())
                    .put(instruction.getOrdinal(), parts[1]);
            counts.computeIfAbsent(tableKey, key -> new HashMap<String, Integer>())
                    .merge(parts[1], 1, Integer::sum);
        }
        Map<String, Map<Integer, String>> effective = new HashMap<>();
        for (Map.Entry<String, Map<Integer, String>> entry : raw.entrySet()) {
            String tableKey = entry.getKey();
            Map<String, Integer> tableCounts = counts.get(tableKey);
            java.util.Set<String> usedNames = new java.util.HashSet<>();
            for (Map.Entry<String, Integer> count : tableCounts.entrySet()) {
                if (count.getValue() == 1) {
                    usedNames.add(count.getKey());
                }
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
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
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

package com.laotie.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import com.laotie.model.Instruction.OperationType;
import com.laotie.model.metadata.Column;
import com.laotie.identifier.Identifiers;
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
        reverseGraph = new DirectedMultigraph<>(LineageEdge.class);
    }

    /**
     * Add one edge in the reverse (target to source) direction, keeping the relation
     * detail of the instruction that produced it.
     */
    private DefaultEdge connect(Column target, Column source, Instruction instruction,
            RelationType fallback) {
        reverseGraph.addVertex(target);
        reverseGraph.addVertex(source);
        DefaultEdge edge = reverseGraph.addEdge(target, source);
        if (edge instanceof LineageEdge) {
            ((LineageEdge) edge).label(instruction, fallback);
        }
        return edge;
    }

    public void buildByInstructions(List<Instruction> instructions) {
        init();
        for (Instruction instruction : instructions) {
            if (instruction.getOperation() == OperationType.COLUMN_MAPPING) {
                Column from = new Column(instruction.getFrom());
                Column to = new Column(instruction.getTo());
                connect(to, from, instruction, RelationType.COLUMN_COPY);
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
                    RelationType wildcardRelation = instruction.getRelation() == null
                            ? RelationType.WILDCARD : instruction.getRelation();
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
                        region.relations.computeIfAbsent(from,
                                ignored -> new HashSet<RelationType>()).add(wildcardRelation);
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
                                        tempWidthKnown, tempWildcardRegions,
                                        wildcardRelation));
                            }
                        } else if (!to.isTemporary() && result.getContext() != null) {
                            java.util.List<String> targetColumns =
                                    physicalColumnNames(to.getTableName(), result.getContext());
                            int targetWidth = targetColumns.size();
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
                                String fromTable = instruction.getFrom();
                                boolean fromTemporary = instruction.isFromTemporary();
                                pendingWildcardLinks.add(() -> linkPopulatedColumns(target,
                                        source, names, targetWidth, false, prefix,
                                        fromTable, fromTemporary, tempColumnsByPosition,
                                        tempWidthKnown, tempWildcardRegions,
                                        wildcardRelation));
                            }
                    }
                    reverseGraph.addVertex(from);
                    reverseGraph.addVertex(to);
                    if (!from.equals(to)) {
                        connect(to, from, instruction, RelationType.WILDCARD);
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
                    connect(to, from, instruction, RelationType.COLUMN_COPY);
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
                connect(vertex, wildcard, null, RelationType.WILDCARD);
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
            Map<String, java.util.List<WildcardRegion>> tempWildcardRegions,
            RelationType wildcardRelation) {
        linkPopulatedColumns(target, sourceWildcard, splitColumns(columns),
                targetWidth, explicitTargets, prefix, fromTable, fromTemporary,
                tempColumnsByPosition, tempWidthKnown, tempWildcardRegions,
                wildcardRelation);
    }

    private void linkPopulatedColumns(Column target, Column sourceWildcard,
            java.util.List<String> names, int targetWidth, boolean explicitTargets,
            String prefix, String fromTable, boolean fromTemporary,
            Map<String, Map<Integer, String>> tempColumnsByPosition,
            Set<String> tempWidthKnown,
            Map<String, java.util.List<WildcardRegion>> tempWildcardRegions,
            RelationType wildcardRelation) {
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
                    for (RelationType relation : positionalSourceRelations(regions, i,
                            positionalSource, wildcardRelation)) {
                        connect(columnVertex, positionalSource, null, relation);
                    }
                }
                continue;
            }
            if (tempColumn != null) {
                Column precise = graphColumn(fromTable + "." + tempColumn, prefix,
                        fromTemporary);
                reverseGraph.addVertex(precise);
                if (!columnVertex.equals(precise)) {
                    connect(columnVertex, precise, null, RelationType.COLUMN_COPY);
                }
                if (explicitTargets
                        || targetWidth > 0 && names.size() == targetWidth) {
                    for (Column positionalSource : positionalSources) {
                        if (columnVertex.equals(positionalSource)) {
                            continue;
                        }
                        for (RelationType relation : positionalSourceRelations(regions, i,
                                positionalSource, wildcardRelation)) {
                            connect(columnVertex, positionalSource, null, relation);
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
                    for (RelationType relation : positionalSourceRelations(regions, i,
                            positionalSource, wildcardRelation)) {
                        connect(columnVertex, positionalSource, null, relation);
                    }
                }
                continue;
            }
            connect(columnVertex, sourceWildcard, null, wildcardRelation);
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
        private final Map<Column, Set<RelationType>> relations = new HashMap<>();

        private WildcardRegion(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }

    private static Set<RelationType> positionalSourceRelations(
            java.util.List<WildcardRegion> regions, int position, Column source,
            RelationType fallback) {
        Set<RelationType> relations = new LinkedHashSet<>();
        if (regions != null) {
            for (WildcardRegion region : regions) {
                if (position >= region.start && position < region.end) {
                    Set<RelationType> found = region.relations.get(source);
                    if (found != null) {
                        for (RelationType relation : found) {
                            if (relation != null && relation != RelationType.NONE) {
                                relations.add(relation);
                            }
                        }
                    }
                }
            }
        }
        if (relations.isEmpty() && fallback != null) {
            relations.add(fallback);
        }
        return relations;
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
                    return Identifiers.decodeDoubleQuoted(inner);
                }
                return inner.replace(String.valueOf(close) + close,
                        String.valueOf(close));
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

    private static final int MAX_PATHS_PER_LEAF = 8;
    private static final int MAX_PATHS_PER_VERTEX = 8;
    private static final int MAX_PATH_STATES = 20000;
    private static final int MAX_PATH_QUEUE = 2048;
    private static final int MAX_PATH_DEPTH = 1024;
    private static final int MAX_MATERIALIZED_HOPS = 1024;

    /**
     * Every relation path from a target column to each reachable leaf source.
     *
     * <p>
     * A leaf is a vertex with no outgoing edge, exactly like
     * {@link #getSources(Column)}. Temporary vertices are walked through but never
     * returned, and a target without any outgoing edge has no path. Parallel
     * derivations (for example two UNION branches, one copying a column and one
     * transforming it) are all kept instead of letting the first one hide the others.
     * A bounded first-pass BFS guarantees that every reachable leaf has at least one
     * path even when the alternative-path budget runs out; the second pass adds the
     * alternatives. Parallel edges with identical labels are collapsed, edge order is
     * stable, and traversal is bounded so a malformed cyclic graph cannot hang the scan.
     * </p>
     */
    public Map<Column, List<List<RelationHop>>> relationPaths(Column target) {
        Map<Column, List<List<RelationHop>>> paths = new LinkedHashMap<>();
        if (target == null || !reverseGraph.containsVertex(target)) {
            return paths;
        }
        addFirstPaths(target, paths);

        Set<Column> onCycle = onCycleVertices(target);
        Deque<PathState> queue = new ArrayDeque<>();
        Set<Column> startVisited = new HashSet<>();
        startVisited.add(target);
        queue.add(new PathState(target, null, 0, startVisited, 0L));
        Map<Column, Set<Long>> seenPaths = new HashMap<>();
        seenPaths.computeIfAbsent(target, key -> new HashSet<Long>()).add(0L);
        Map<Column, Set<Long>> expandedLabels = new HashMap<>();
        Map<Column, Integer> expanded = new HashMap<>();
        int states = 0;
        int enqueued = 0;
        while (!queue.isEmpty() && states++ < MAX_PATH_STATES) {
            PathState state = queue.poll();
            Set<Long> labels = expandedLabels.computeIfAbsent(state.vertex,
                    key -> new HashSet<Long>());
            boolean newLabel = !labels.contains(state.hash);
            if (newLabel) {
                int count = expanded.containsKey(state.vertex)
                        ? expanded.get(state.vertex) : 0;
                if (count >= MAX_PATHS_PER_VERTEX) {
                    // Do not register a label that was rejected by the cap, or a
                    // later state with the same label could bypass the cap.
                    continue;
                }
                labels.add(state.hash);
                expanded.put(state.vertex, count + 1);
            } else if (!onCycle.contains(state.vertex)) {
                // The same label prefix has the same future unless a cycle makes
                // the visited set matter.
                continue;
            }
            if (state.depth >= MAX_PATH_DEPTH) {
                continue;
            }
            for (DefaultEdge edge : sortedOutgoing(state.vertex)) {
                Column source = reverseGraph.getEdgeTarget(edge);
                if (source.equals(state.vertex) || state.visited.contains(source)) {
                    continue;
                }
                PathNode node = new PathNode(state.tail, hopOf(edge));
                if (reverseGraph.outDegreeOf(source) == 0) {
                    if (!source.isTemporary()) {
                        addPath(paths, source, new PathNodeList(node));
                    }
                } else if (queue.size() < MAX_PATH_QUEUE
                        && enqueued < MAX_PATH_STATES) {
                    long key = node.hash;
                    if (onCycle.contains(state.vertex) || onCycle.contains(source)) {
                        // On a cycle the same label prefix may have different usable
                        // outgoing edges depending on which vertices were visited.
                        // An unrelated cycle must not change how other branches
                        // deduplicate.
                        key = key * 31 + visitedHash(state.visited) * 17
                                + visitedHash(java.util.Collections.singleton(source));
                    }
                    if (seenPaths.computeIfAbsent(source,
                            k -> new HashSet<Long>()).add(key)) {
                        enqueued++;
                        Set<Column> visited = new HashSet<>(state.visited);
                        visited.add(source);
                        queue.add(new PathState(source, node, node.depth, visited,
                                node.hash));
                    }
                }
            }
        }
        return paths;
    }

    /**
     * Breadth-first first path to every reachable vertex, so alternative-path
     * enumeration cannot starve a leaf of its only path. Only one back pointer per
     * vertex is kept; leaf paths are materialized on demand, which avoids the
     * quadratic memory of copying a growing path at every hop.
     */
    private void addFirstPaths(Column target,
            Map<Column, List<List<RelationHop>>> paths) {
        Map<Column, EdgeBack> parent = new HashMap<>();
        Deque<Column> queue = new ArrayDeque<>();
        parent.put(target, null);
        queue.add(target);
        while (!queue.isEmpty()) {
            Column vertex = queue.poll();
            for (DefaultEdge edge : sortedOutgoing(vertex)) {
                Column source = reverseGraph.getEdgeTarget(edge);
                if (parent.containsKey(source)) {
                    continue;
                }
                parent.put(source, new EdgeBack(vertex, hopOf(edge)));
                if (reverseGraph.outDegreeOf(source) == 0) {
                    if (!source.isTemporary()) {
                        addPath(paths, source, new ChainPath(source, parent));
                    }
                } else {
                    queue.add(source);
                }
            }
        }
    }

    /**
     * A lazily materialized, immutable list of relation hops. Subclasses own the
     * backing chain; this base class provides the List contract without copying the
     * whole path for every leaf.
     */
    private abstract static class LazyPath extends java.util.AbstractList<RelationHop> {
        @Override
        public RelationHop get(int index) {
            if (index < 0 || index >= size()) {
                throw new IndexOutOfBoundsException("index " + index + " size " + size());
            }
            return materialize().get(index);
        }

        @Override
        public java.util.Iterator<RelationHop> iterator() {
            // Snapshot the view and make remove() fail like a fixed-size list.
            return java.util.Collections.unmodifiableList(materialize()).iterator();
        }

        @Override
        public boolean equals(Object other) {
            if (other == this) {
                return true;
            }
            if (!(other instanceof List)) {
                return false;
            }
            List<?> list = (List<?>) other;
            if (list.size() != size()) {
                return false;
            }
            java.util.Iterator<RelationHop> mine = iterator();
            java.util.Iterator<?> theirs = list.iterator();
            while (mine.hasNext() && theirs.hasNext()) {
                if (!mine.next().equals(theirs.next())) {
                    return false;
                }
            }
            return !mine.hasNext() && !theirs.hasNext();
        }

        @Override
        public int hashCode() {
            int hash = 1;
            for (RelationHop hop : this) {
                hash = 31 * hash + hop.hashCode();
            }
            return hash;
        }

        @Override
        public int indexOf(Object target) {
            int index = 0;
            for (RelationHop hop : this) {
                if (java.util.Objects.equals(hop, target)) {
                    return index;
                }
                index++;
            }
            return -1;
        }

        protected abstract List<RelationHop> materialize();
    }

    /**
     * A lazy path over the coverage BFS back pointers. The first pass keeps only one
     * pointer per vertex; sharing that chain instead of copying a full hop list for
     * every leaf keeps memory linear in the graph size, even when thousands of leaves
     * share a long prefix.
     */
    private static final class ChainPath extends LazyPath {
        private final Column leaf;
        private final Map<Column, EdgeBack> parent;
        private final int size;
        private final boolean truncated;

        private ChainPath(Column leaf, Map<Column, EdgeBack> parent) {
            this.leaf = leaf;
            this.parent = parent;
            int depth = 0;
            Column current = leaf;
            while (parent.get(current) != null) {
                depth++;
                current = parent.get(current).vertex;
            }
            this.size = Math.min(depth, MAX_MATERIALIZED_HOPS);
            this.truncated = depth > MAX_MATERIALIZED_HOPS;
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        protected List<RelationHop> materialize() {
            // Walk leaf -> target, keeping the hops closest to the target.
            Deque<RelationHop> path = new ArrayDeque<>();
            Column current = leaf;
            int guard = 0;
            while (guard++ < 1000000) {
                EdgeBack back = parent.get(current);
                if (back == null) {
                    break;
                }
                path.addFirst(back.hop);
                if (path.size() > size) {
                    path.removeLast();
                }
                current = back.vertex;
            }
            List<RelationHop> result = new ArrayList<>(path);
            if (truncated && !result.isEmpty()) {
                result.set(result.size() - 1,
                        result.get(result.size() - 1).asTruncated());
            }
            return result;
        }
    }

    /**
     * A linked path node used by the alternative-path enumeration. States share their
     * prefix nodes instead of copying a list at every hop.
     */
    private static final class PathNode {
        private final PathNode parent;
        private final RelationHop hop;
        private final int depth;
        private final long hash;

        private PathNode(PathNode parent, RelationHop hop) {
            this.parent = parent;
            this.hop = hop;
            this.depth = parent == null ? 1 : parent.depth + 1;
            this.hash = hashHop(parent == null ? 1469598103934665603L : parent.hash, hop);
        }
    }

    private static final class PathNodeList extends LazyPath {
        private final PathNode tail;

        private PathNodeList(PathNode tail) {
            this.tail = tail;
        }

        @Override
        public int size() {
            return tail == null ? 0 : tail.depth;
        }

        private long labelHash() {
            return tail == null ? 0L : tail.hash;
        }

        @Override
        protected List<RelationHop> materialize() {
            List<RelationHop> path = new ArrayList<>(size());
            PathNode node = tail;
            while (node != null) {
                path.add(node.hop);
                node = node.parent;
            }
            java.util.Collections.reverse(path);
            return path;
        }
    }

    /**
     * Vertices that lie on some cycle reachable from the target. Path deduplication
     * only needs the visited set near these vertices; an unrelated cycle must not
     * change how other branches are deduplicated.
     *
     * <p>
     * Kosaraju's two passes mark whole strongly connected components. A simple
     * back-edge DFS is not enough: a vertex may be finished before a later branch
     * reaches it through a different cycle.
     * </p>
     */
    private Set<Column> onCycleVertices(Column start) {
        Map<Column, java.util.LinkedHashSet<Column>> adjacencySets = new HashMap<>();
        Deque<Column> queue = new ArrayDeque<>();
        Set<Column> visited = new HashSet<>();
        visited.add(start);
        queue.add(start);
        while (!queue.isEmpty()) {
            Column vertex = queue.poll();
            java.util.LinkedHashSet<Column> targets = adjacencySets
                    .computeIfAbsent(vertex, k -> new java.util.LinkedHashSet<Column>());
            for (DefaultEdge edge : reverseGraph.outgoingEdgesOf(vertex)) {
                Column source = reverseGraph.getEdgeTarget(edge);
                targets.add(source);
                if (visited.add(source)) {
                    queue.add(source);
                }
            }
        }

        Set<Column> onCycle = new HashSet<>();
        Map<Column, List<Column>> adjacency = new HashMap<>();
        for (Map.Entry<Column, java.util.LinkedHashSet<Column>> entry
                : adjacencySets.entrySet()) {
            adjacency.put(entry.getKey(), new ArrayList<>(entry.getValue()));
            if (entry.getValue().contains(entry.getKey())) {
                onCycle.add(entry.getKey());
            }
        }

        // First pass: finish order.
        List<Column> order = new ArrayList<>();
        Set<Column> seen = new HashSet<>();
        for (Column root : adjacency.keySet()) {
            if (!seen.add(root)) {
                continue;
            }
            Deque<Column> stack = new ArrayDeque<>();
            Map<Column, Integer> nextChild = new HashMap<>();
            stack.push(root);
            nextChild.put(root, 0);
            while (!stack.isEmpty()) {
                Column vertex = stack.peek();
                List<Column> children = adjacency.get(vertex);
                int index = nextChild.get(vertex);
                if (index < children.size()) {
                    Column child = children.get(index);
                    nextChild.put(vertex, index + 1);
                    if (seen.add(child)) {
                        stack.push(child);
                        nextChild.put(child, 0);
                    }
                } else {
                    stack.pop();
                    order.add(vertex);
                }
            }
        }

        // Reverse adjacency.
        Map<Column, List<Column>> reverse = new HashMap<>();
        for (Column vertex : adjacency.keySet()) {
            reverse.computeIfAbsent(vertex, k -> new ArrayList<Column>());
        }
        for (Map.Entry<Column, List<Column>> entry : adjacency.entrySet()) {
            for (Column source : entry.getValue()) {
                reverse.computeIfAbsent(source, k -> new ArrayList<Column>())
                        .add(entry.getKey());
            }
        }

        // Second pass: strongly connected components.
        Set<Column> assigned = new HashSet<>();
        for (int i = order.size() - 1; i >= 0; i--) {
            Column root = order.get(i);
            if (!assigned.add(root)) {
                continue;
            }
            List<Column> component = new ArrayList<>();
            Deque<Column> stack = new ArrayDeque<>();
            stack.push(root);
            while (!stack.isEmpty()) {
                Column vertex = stack.pop();
                component.add(vertex);
                for (Column previous : reverse.getOrDefault(vertex,
                        java.util.Collections.<Column>emptyList())) {
                    if (assigned.add(previous)) {
                        stack.push(previous);
                    }
                }
            }
            if (component.size() > 1) {
                onCycle.addAll(component);
            }
        }
        return onCycle;
    }

    private static long pathHash(List<RelationHop> path) {
        long hash = 1469598103934665603L;
        for (RelationHop hop : path) {
            hash = hashHop(hash, hop);
        }
        return hash;
    }

    private static long hashHop(long hash, RelationHop hop) {
        hash = hashString(hash, hop.getRelation().name());
        hash = hashString(hash, hop.getFunctionName());
        hash = hashString(hash, hop.getOperator());
        hash = hashString(hash, hop.getExpression());
        hash ^= hop.isTruncated() ? 0xD6E8FEB86659FD93L : 0L;
        return hash;
    }

    private static long hashString(long hash, String text) {
        hash ^= 0xFF;
        hash *= 1099511628211L;
        if (text == null) {
            return hash;
        }
        for (int i = 0; i < text.length(); i++) {
            hash ^= text.charAt(i);
            hash *= 1099511628211L;
        }
        return hash;
    }

    private static long visitedHash(Set<Column> visited) {
        long hash = 0;
        for (Column column : visited) {
            long single = 1469598103934665603L;
            single = hashString(single, column.getTableName());
            single = hashString(single, column.getColumnName());
            single ^= column.isTemporary() ? 0x9E3779B97F4A7C15L : 0L;
            single ^= column.isWildcard() ? 0xC2B2AE3D27D4EB4FL : 0L;
            // XOR keeps the result independent of iteration order.
            hash ^= single;
        }
        return hash;
    }

    private static final class EdgeBack {
        private final Column vertex;
        private final RelationHop hop;

        private EdgeBack(Column vertex, RelationHop hop) {
            this.vertex = vertex;
            this.hop = hop;
        }
    }

    /**
     * Outgoing edges with identical labels collapsed. A multigraph can hold several
     * parallel edges for the same derivation; exploring each one would multiply the
     * path count without adding information.
     */
    private List<DefaultEdge> sortedOutgoing(Column vertex) {
        Map<String, DefaultEdge> unique = new LinkedHashMap<>();
        for (DefaultEdge edge : reverseGraph.outgoingEdgesOf(vertex)) {
            if (!reverseGraph.getEdgeTarget(edge).equals(vertex)) {
                unique.putIfAbsent(pathEdgeKey(edge), edge);
            }
        }
        List<DefaultEdge> edges = new ArrayList<>(unique.values());
        edges.sort(Comparator.comparing(this::pathEdgeKey));
        return edges;
    }

    private static void addPath(Map<Column, List<List<RelationHop>>> paths,
            Column leaf, List<RelationHop> path) {
        List<List<RelationHop>> existing = paths.computeIfAbsent(leaf,
                key -> new ArrayList<List<RelationHop>>());
        if (existing.size() >= MAX_PATHS_PER_LEAF) {
            return;
        }
        long hash = pathHashOf(path);
        for (List<RelationHop> other : existing) {
            if (hash == pathHashOf(other)) {
                return;
            }
        }
        existing.add(path);
    }

    private static long pathHashOf(List<RelationHop> path) {
        return path instanceof PathNodeList
                ? ((PathNodeList) path).labelHash() : pathHash(path);
    }

    private static RelationHop hopOf(DefaultEdge edge) {
        if (edge instanceof LineageEdge) {
            LineageEdge labeled = (LineageEdge) edge;
            return new RelationHop(labeled.getRelation(), labeled.getExpression(),
                    labeled.getFunctionName(), labeled.getOperator());
        }
        return new RelationHop(RelationType.COLUMN_COPY, null, null, null);
    }

    private String pathEdgeKey(DefaultEdge edge) {
        Column source = reverseGraph.getEdgeTarget(edge);
        RelationHop hop = hopOf(edge);
        // The display text alone merges a physical column with a same-named
        // temporary or wildcard vertex; Column equality keeps them distinct.
        return source + "\u0001" + source.isTemporary() + "\u0001"
                + source.isWildcard() + "\u0001" + hop.getRelation().name() + "\u0001"
                + hop.getFunctionName() + "\u0001" + hop.getOperator() + "\u0001"
                + hop.getExpression();
    }

    private static final class PathState {
        private final Column vertex;
        private final PathNode tail;
        private final int depth;
        private final Set<Column> visited;
        private final long hash;

        private PathState(Column vertex, PathNode tail, int depth,
                Set<Column> visited, long hash) {
            this.vertex = vertex;
            this.tail = tail;
            this.depth = depth;
            this.visited = visited;
            this.hash = hash;
        }
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

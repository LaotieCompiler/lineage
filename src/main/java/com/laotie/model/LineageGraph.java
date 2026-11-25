package com.laotie.model;

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
        for (int i = 0; i < results.size(); i++) {
            LineageResult result = results.get(i);
            if (result.getError() != null) {
                continue;
            }
            List<Instruction> instructions = result.getInstructions();
            if (result.getContext() != null) {
                instructions = Instruction.toColumnInstructions(instructions, result.getContext());
            }
            String prefix = "stmt" + i + ".";
            for (Instruction instruction : instructions) {
                if (instruction.getOperation() != OperationType.COLUMN_MAPPING) {
                    continue;
                }
                Column from = graphColumn(instruction.getFrom(), prefix, result.getContext());
                Column to = graphColumn(instruction.getTo(), prefix, result.getContext());
                reverseGraph.addVertex(from);
                reverseGraph.addVertex(to);
                reverseGraph.addEdge(to, from);
            }
        }
    }

    private static Column graphColumn(String reference, String prefix, MetadataContext context) {
        int idx = reference.lastIndexOf('.');
        if (idx < 0) {
            return new Column(reference);
        }
        String table = reference.substring(0, idx);
        String column = reference.substring(idx + 1);

        if (isPhysicalTable(table, context)) {
            return new Column(reference);
        }
        if (isTempTable(table)) {
            return Column.temporary(prefix + table + "." + column);
        }
        return new Column(reference);
    }

    private static boolean isPhysicalTable(String table, MetadataContext context) {
        if (context == null) {
            return false;
        }
        try {
            for (com.laotie.model.metadata.TableLocation location
                    : context.getCatalog().findAllTables(table)) {
                if (location.getDisplayName().equalsIgnoreCase(table)) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean isTempTable(String table) {
        return table.matches("temp\\d+") || table.matches("stmt\\d+\\.temp\\d+");
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

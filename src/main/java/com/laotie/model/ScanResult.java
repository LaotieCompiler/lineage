package com.laotie.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Result of scanning a SQL log: one result per statement plus the merged global lineage graph.
 */
public class ScanResult {
    private final List<LineageResult> results;
    private final LineageGraph graph;

    public ScanResult(List<LineageResult> results, LineageGraph graph) {
        this.results = Collections.unmodifiableList(new ArrayList<>(results));
        this.graph = graph;
    }

    public List<LineageResult> getResults() {
        return results;
    }

    public LineageGraph getGraph() {
        return graph;
    }

    public List<LineageResult> getSuccesses() {
        List<LineageResult> successes = new ArrayList<>();
        for (LineageResult result : results) {
            if (result.getError() == null) {
                successes.add(result);
            }
        }
        return successes;
    }

    public List<LineageResult> getFailures() {
        List<LineageResult> failures = new ArrayList<>();
        for (LineageResult result : results) {
            if (result.getError() != null) {
                failures.add(result);
            }
        }
        return failures;
    }
}

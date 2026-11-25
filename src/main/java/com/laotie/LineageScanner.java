package com.laotie;

import java.util.List;

import com.alibaba.druid.DbType;
import com.laotie.model.LineageGraph;
import com.laotie.model.LineageResult;
import com.laotie.model.ScanResult;
import com.laotie.model.metadata.MetadataContext;

/**
 * Production entry point: scan a batch of SQL log statements and build whole-database lineage.
 *
 * <p>
 * Statements are parsed independently; a failing statement is reported in its
 * {@link LineageResult} and does not stop the rest of the batch. All temporary nodes are
 * namespaced per statement, and wildcard expansion uses each statement's metadata snapshot.
 */
public class LineageScanner {
    private final DbType dbType;
    private final MetadataContext context;

    public LineageScanner() {
        this(null, null);
    }

    public LineageScanner(DbType dbType) {
        this(dbType, null);
    }

    public LineageScanner(MetadataContext context) {
        this(null, context);
    }

    private LineageScanner(DbType dbType, MetadataContext context) {
        this.dbType = dbType;
        this.context = context;
    }

    public ScanResult scan(String sqlLog) {
        DmlLineage lineage;
        if (context != null) {
            lineage = new DmlLineage(context);
        } else if (dbType != null) {
            lineage = new DmlLineage(dbType);
        } else {
            lineage = new DmlLineage();
        }

        List<LineageResult> results = lineage.parseScript(sqlLog);
        LineageGraph graph = new LineageGraph();
        graph.buildByResults(results);
        return new ScanResult(results, graph);
    }

    public ScanResult scan(Iterable<String> logLines) {
        StringBuilder log = new StringBuilder();
        for (String line : logLines) {
            log.append(line).append('\n');
        }
        return scan(log.toString());
    }
}

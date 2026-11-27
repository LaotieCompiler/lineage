package com.laotie;

import java.util.List;

import com.alibaba.druid.DbType;
import com.laotie.model.LineageGraph;
import com.laotie.model.LineageResult;
import com.laotie.model.ScanResult;
import com.laotie.model.metadata.LearnedMetadata;
import com.laotie.model.metadata.MetadataContext;

/**
 * Production entry point: scan a batch of SQL log statements and build whole-database lineage.
 *
 * <p>
 * Statements are parsed independently; a failing statement is reported in its
 * {@link LineageResult} and does not stop the rest of the batch. All temporary nodes are
 * namespaced per statement, and wildcard instructions are expanded with each result's own
 * metadata snapshot.
 *
 * <p>
 * A scanner keeps the metadata it learned from earlier statements, so that a later statement
 * can resolve an unqualified column against the tables that were observed before. The learned
 * metadata is scoped to this scanner instance.
 */
public class LineageScanner {
    private final DbType dbType;
    private final MetadataContext context;
    private final LineageOptions options;
    private final LearnedMetadata learnedMetadata;

    public LineageScanner() {
        this(null, null, LineageOptions.defaults());
    }

    public LineageScanner(DbType dbType) {
        this(null, dbType, LineageOptions.defaults());
    }

    public LineageScanner(MetadataContext context) {
        this(context, null, LineageOptions.defaults());
    }

    private LineageScanner(MetadataContext context, DbType dbType,
            LineageOptions options) {
        this.dbType = dbType;
        this.context = context;
        this.options = options == null ? LineageOptions.defaults() : options;
        this.learnedMetadata = this.options.isInferMetadata()
                ? new LearnedMetadata(dialectOf(context, dbType))
                : null;
    }

    /**
     * A scanner that analyzes with the given options and no metadata.
     */
    public static LineageScanner withoutMetadata(LineageOptions options) {
        return new LineageScanner(null, null, options);
    }

    /**
     * A scanner that analyzes with the given dialect and options and no metadata.
     */
    public static LineageScanner withoutMetadata(DbType dbType,
            LineageOptions options) {
        return new LineageScanner(null, dbType, options);
    }

    /**
     * A scanner that analyzes with the given options and a metadata context.
     */
    public static LineageScanner withMetadata(MetadataContext context,
            LineageOptions options) {
        return new LineageScanner(context, null, options);
    }

    private static DbType dialectOf(MetadataContext context, DbType dbType) {
        if (context != null && context.getCatalog() != null) {
            return context.getCatalog().getDialect();
        }
        return dbType == null ? DbType.mysql : dbType;
    }

    public ScanResult scan(String sqlLog) {
        if (learnedMetadata != null && sqlLog != null && !sqlLog.isEmpty()) {
            // First pass: discover metadata from the whole log without producing
            // lineage, so the second pass resolves every statement against the
            // complete picture (including tables referenced later in the log).
            DmlLineage learner = newLineage();
            learner.setLearningOnly(true);
            learner.parseScript(sqlLog);
        }

        DmlLineage lineage = newLineage();
        List<LineageResult> results = lineage.parseScript(sqlLog);
        LineageGraph graph = new LineageGraph();
        graph.buildByResults(results);
        return new ScanResult(results, graph);
    }

    private DmlLineage newLineage() {
        DmlLineage lineage;
        if (context != null) {
            lineage = new DmlLineage(context, options);
        } else if (dbType != null) {
            lineage = new DmlLineage(dbType, options);
        } else {
            lineage = new DmlLineage(options);
        }
        if (learnedMetadata != null) {
            lineage.setLearnedMetadata(learnedMetadata);
        }
        return lineage;
    }

    public ScanResult scan(Iterable<String> logLines) {
        StringBuilder log = new StringBuilder();
        for (String line : logLines) {
            log.append(line).append('\n');
        }
        return scan(log.toString());
    }
}

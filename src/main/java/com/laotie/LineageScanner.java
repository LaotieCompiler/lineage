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
        if (learnedMetadata == null || sqlLog == null || sqlLog.isEmpty()) {
            return analyze(sqlLog, null, false);
        }
        // Pass 1: discover metadata from the whole log, without lineage. Explicit
        // references from the whole log are collected first, so a statement can be
        // resolved by knowledge that only appears later in the same log.
        LearnedMetadata base = new LearnedMetadata(learnedMetadata);
        LearnedMetadata candidate = new LearnedMetadata(base);
        for (int round = 0; round < MAX_METADATA_ROUNDS; round++) {
            LearnedMetadata next = new LearnedMetadata(candidate);
            learn(sqlLog, next);
            if (next.sameAs(candidate)) {
                break;
            }
            candidate = next;
        }
        // Pass 2: analyze with the discovered metadata. A statement that still fails
        // must not contribute its observations, so rebuild the candidate from the
        // statements that succeed and repeat until it stops changing.
        ScanResult scan = analyze(sqlLog, candidate, true);
        List<LearnedMetadata> seen = new java.util.ArrayList<>();
        seen.add(candidate);
        for (int round = 0; round < MAX_METADATA_ROUNDS; round++) {
            LearnedMetadata rebuilt = new LearnedMetadata(base);
            String accepted = successfulSql(scan.getResults());
            if (!accepted.isEmpty()) {
                learn(accepted, rebuilt);
            }
            if (rebuilt.sameAs(candidate)) {
                break;
            }
            boolean cycled = false;
            for (LearnedMetadata previous : seen) {
                if (rebuilt.sameAs(previous)) {
                    cycled = true;
                    break;
                }
            }
            if (cycled) {
                // The log has no consistent fixed point (a statement's successful
                // projections change what the same statement can prove). Keep the
                // smaller candidate: it does not persist an observation whose only
                // support is the statement it makes fail.
                candidate = rebuilt.columnCount() < candidate.columnCount()
                        ? rebuilt : candidate;
                scan = analyze(sqlLog, candidate, true);
                break;
            }
            seen.add(candidate);
            candidate = rebuilt;
            scan = analyze(sqlLog, candidate, true);
        }
        // Validate the final candidate against the statements that actually succeed;
        // prefer the variant with fewer failures so no unsupported observation (for
        // example a guess that makes its own statement fail) is persisted.
        LearnedMetadata supported = new LearnedMetadata(base);
        String accepted = successfulSql(scan.getResults());
        if (!accepted.isEmpty()) {
            learn(accepted, supported);
        }
        if (!supported.sameAs(candidate)) {
            ScanResult supportedScan = analyze(sqlLog, supported, true);
            if (supportedScan.getFailures().size() <= scan.getFailures().size()) {
                candidate = supported;
                scan = supportedScan;
            }
        }
        learnedMetadata.mergeFrom(candidate);
        return scan;
    }

    private static final int MAX_METADATA_ROUNDS = 8;

    private ScanResult analyze(String sqlLog, LearnedMetadata learned, boolean stable) {
        DmlLineage lineage = newLineage();
        if (learned != null) {
            lineage.setLearnedMetadata(learned);
        }
        if (stable) {
            lineage.setLearningDisabled(true);
        }
        List<LineageResult> results = lineage.parseScript(sqlLog);
        LineageGraph graph = new LineageGraph();
        graph.buildByResults(results);
        return new ScanResult(results, graph);
    }

    private void learn(String sqlLog, LearnedMetadata learned) {
        DmlLineage learner = newLineage();
        learner.setLearnedMetadata(learned);
        learner.setLearningOnly(true);
        learner.setMetadataPass(true);
        learner.parseScript(sqlLog);
    }

    private static String successfulSql(List<LineageResult> results) {
        StringBuilder sql = new StringBuilder();
        for (LineageResult result : results) {
            if (result.getError() != null || result.getSql() == null) {
                continue;
            }
            if (sql.length() > 0) {
                // A trailing line comment must not swallow the separator.
                sql.append("\n;\n");
            }
            sql.append(result.getSql());
        }
        return sql.toString();
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

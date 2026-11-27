package com.laotie;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

import com.alibaba.druid.DbType;
import com.laotie.exception.AmbiguousColumnException;
import com.laotie.exception.ColumnNotFoundException;
import com.laotie.exception.MetadataNotFoundException;
import com.laotie.model.Instruction;
import com.laotie.model.LineageGraph;
import com.laotie.model.Instruction.OperationType;
import com.laotie.model.ScanResult;
import com.laotie.model.metadata.Column;
import com.laotie.model.metadata.MetadataCatalog;
import com.laotie.model.metadata.MetadataContext;
import com.laotie.model.metadata.MetadataMode;

/**
 * Analysis modes that do not rely on a complete metadata catalog.
 *
 * <p>
 * In {@link MetadataMode#OPTIONAL} a table that is missing from the catalog is still analyzed:
 * its wildcard stays a wildcard instead of being expanded into invented column names, and a
 * table-level edge is kept in the global graph.
 *
 * <p>
 * When metadata inference is enabled, columns observed in earlier statements are remembered so
 * that later statements can resolve an unqualified column without guessing.
 */
public class MetadataFreeAnalysisTest {

    private static LineageOptions optional() {
        return LineageOptions.of(MetadataMode.OPTIONAL, false);
    }

    private static LineageOptions inferring() {
        return LineageOptions.of(MetadataMode.OPTIONAL, true);
    }

    private static void assertOnlyWildcardSources(LineageGraph graph, Column target) {
        if (!graph.getReverseGraph().containsVertex(target)) {
            return;
        }
        for (Column source : graph.getSources(target)) {
            assertTrue("column position was guessed: " + source, source.isWildcard());
        }
    }

    private static MetadataContext catalog(String ddl) {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl(ddl);
        return MetadataContext.of(catalog);
    }

    @Test
    public void strictModeRejectsTableMissingFromCatalog() {
        ScanResult result = new LineageScanner(catalog("CREATE TABLE known (c1 INT);"))
                .scan("SELECT * FROM missing;");

        assertEquals(1, result.getResults().size());
        assertTrue(result.getResults().get(0).getError()
                instanceof MetadataNotFoundException);
        assertTrue(result.getResults().get(0).getInstructions().isEmpty());
    }

    @Test
    public void optionalModeKeepsMissingTableAsWildcard() {
        ScanResult result = LineageScanner.withMetadata(
                catalog("CREATE TABLE known (c1 INT);"), optional())
                .scan("SELECT * FROM missing;");

        assertEquals(1, result.getResults().size());
        assertNull(result.getResults().get(0).getError());

        List<Instruction> instructions = result.getResults().get(0).getInstructions();
        assertEquals(1, instructions.size());
        assertEquals(OperationType.COLUMNS_INJECT, instructions.get(0).getOperation());
        assertEquals("missing", instructions.get(0).getFrom());
        assertEquals("temp0", instructions.get(0).getTo());
    }

    @Test
    public void optionalModeKeepsTableLevelEdgeInGraph() {
        ScanResult result = LineageScanner.withMetadata(
                catalog("CREATE TABLE known (c1 INT);"), optional())
                .scan("SELECT * FROM missing;");

        Set<Column> sources = result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.*"));
        assertEquals(1, sources.size());
        assertEquals(new Column("missing.*"), sources.iterator().next());
    }

    @Test
    public void optionalModeDoesNotInventColumnNames() {
        ScanResult result = LineageScanner.withMetadata(
                catalog("CREATE TABLE known (c1 INT);"), optional())
                .scan("SELECT * FROM missing; SELECT c1 FROM missing;");

        for (Instruction instruction : result.getResults().get(0).getInstructions()) {
            assertFalse("invented column name: " + instruction,
                    instruction.getFrom().contains("col1")
                            || instruction.getTo().contains("col1"));
        }
    }

    @Test
    public void optionalModeStillExpandsCatalogTables() {
        ScanResult result = LineageScanner.withMetadata(
                catalog("CREATE TABLE known (c1 INT, c2 INT);"), optional())
                .scan("SELECT * FROM known;");

        assertNull(result.getResults().get(0).getError());
        Set<Column> sources = result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.c1"));
        assertEquals(1, sources.size());
        assertEquals(new Column("known.c1"), sources.iterator().next());
        assertEquals(1, result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.c2")).size());
    }

    @Test
    public void optionalModeStillValidatesCatalogColumns() {
        ScanResult result = LineageScanner.withMetadata(
                catalog("CREATE TABLE known (c1 INT);"), optional())
                .scan("SELECT c9 FROM known;");

        assertTrue(result.getResults().get(0).getError()
                instanceof ColumnNotFoundException);
    }

    @Test
    public void optionalModeRejectsAmbiguousUnqualifiedColumn() {
        ScanResult result = LineageScanner.withMetadata(
                catalog("CREATE TABLE known (c1 INT);"), optional())
                .scan("SELECT c1 FROM known, other;");

        assertTrue(result.getResults().get(0).getError()
                instanceof AmbiguousColumnException);
        assertTrue(result.getResults().get(0).getInstructions().isEmpty());
    }

    @Test
    public void optionalModeResolvesUnqualifiedColumnOfSingleCandidate() {
        ScanResult result = LineageScanner.withMetadata(
                catalog("CREATE TABLE known (c1 INT);"), optional())
                .scan("SELECT c1 FROM other;");

        assertNull(result.getResults().get(0).getError());
        List<Instruction> instructions = result.getResults().get(0).getInstructions();
        assertEquals(1, instructions.size());
        assertEquals("other.c1", instructions.get(0).getFrom());
        assertEquals("temp0.c1", instructions.get(0).getTo());
    }

    @Test
    public void inferredMetadataResolvesLaterUnqualifiedColumn() {
        ScanResult result = LineageScanner.withoutMetadata(inferring())
                .scan("SELECT A.c1, B.c2 FROM A,B; SELECT c1 FROM A,B;");

        assertEquals(2, result.getResults().size());
        assertNull(result.getResults().get(0).getError());
        assertNull(result.getResults().get(1).getError());

        List<Instruction> instructions = result.getResults().get(1).getInstructions();
        assertEquals(1, instructions.size());
        assertEquals("A.c1", instructions.get(0).getFrom());
        assertEquals("temp0.c1", instructions.get(0).getTo());
    }

    @Test
    public void inferenceCanBeDisabled() {
        ScanResult result = LineageScanner.withoutMetadata(optional())
                .scan("SELECT A.c1, B.c2 FROM A,B; SELECT c1 FROM A,B;");

        assertEquals(2, result.getResults().size());
        assertNull(result.getResults().get(0).getError());
        assertTrue(result.getResults().get(1).getError()
                instanceof AmbiguousColumnException);
    }

    @Test
    public void metadataLearningScansTheWholeLogBeforeLineage() {
        // The first statement is ambiguous on its own; the second statement later in
        // the same log teaches that c1 belongs to A. The metadata pass therefore has
        // to see the whole log before the lineage pass starts.
        ScanResult result = LineageScanner.withoutMetadata(inferring())
                .scan("SELECT c1 FROM A,B; SELECT A.c1, B.c2 FROM A,B;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("A.c1"))),
                result.getGraph().getSources(
                        Column.temporary("stmt0.temp0.c1")));
    }

    @Test
    public void twoPassLearningDoesNotPersistAContradiction() {
        LineageScanner scanner = LineageScanner.withoutMetadata(
                DbType.postgresql, inferring());
        assertEquals(0, scanner.scan("SELECT A.y, x FROM A;").getFailures().size());
        // A.y must not be kept in a way that hides A.x from later scans.
        assertEquals(0, scanner.scan("SELECT x FROM A;").getFailures().size());
    }

    @Test
    public void metadataReplayKeepsTheStatementBoundaryAfterComments() {
        LineageScanner scanner = LineageScanner.withoutMetadata(
                DbType.postgresql, inferring());
        scanner.scan("USE db1 -- note\n;\nUSE db2;\nSELECT A.k, B.y FROM A,B;");
        ScanResult result = scanner.scan("SELECT k FROM A,B;");

        // The db2 observation must not resolve an unqualified column in db1.
        assertTrue(result.getResults().get(0).getError()
                instanceof AmbiguousColumnException);
    }

    @Test
    public void metadataDiscoverySeesReferencesAfterAFailingProjection() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.postgresql, inferring()).scan(
                "SELECT k, A.k, B.y FROM A,B;");

        assertEquals(0, result.getFailures().size());
    }

    @Test
    public void twoPassLearningDropsContradictedInferences() {
        LineageScanner scanner = LineageScanner.withoutMetadata(
                DbType.postgresql, inferring());
        ScanResult result = scanner.scan("SELECT X.y FROM X;"
                + " SELECT A.p, k FROM A,X;"
                + " SELECT A.y, X.k, B.y FROM A,X,B;"
                + " SELECT p FROM A,B;");

        // The whole log is learned first; no statement fails on stale guesses.
        assertEquals(0, result.getFailures().size());
        // A rescan sees the same consistent picture.
        ScanResult rescan = scanner.scan("SELECT p FROM A,B;");
        assertEquals(0, rescan.getFailures().size());
    }

    @Test
    public void twoPassLearningCompletesBeforeLineage() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.postgresql, inferring()).scan(
                "SELECT k FROM A,B;"
                        + " SELECT B.k, x FROM B,C,D;"
                        + " SELECT A.y, B.z, C.x, D.y FROM A,B,C,D;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("b.k"))),
                result.getGraph().getSources(
                        Column.temporary("stmt0.temp0.k")));
    }

    @Test
    public void metadataLearningIsWhatResolvesTheEarlierStatement() {
        // The same log without inference keeps the first statement ambiguous, which
        // proves the two-pass scan is what resolves it.
        ScanResult withoutInference = LineageScanner.withoutMetadata(optional())
                .scan("SELECT c1 FROM A,B; SELECT A.c1, B.c2 FROM A,B;");

        assertEquals(1, withoutInference.getFailures().size());
        assertTrue(withoutInference.getResults().get(0).getError()
                instanceof AmbiguousColumnException);
    }

    @Test
    public void inferenceIsScopedToTheScanner() {
        ScanResult first = LineageScanner.withoutMetadata(inferring())
                .scan("SELECT A.c1 FROM A;");
        assertNull(first.getResults().get(0).getError());

        ScanResult second = LineageScanner.withoutMetadata(inferring())
                .scan("SELECT c1 FROM A, B;");
        assertTrue(second.getResults().get(0).getError()
                instanceof AmbiguousColumnException);
    }

    @Test
    public void inferenceDistinguishesTablesByColumn() {
        ScanResult result = LineageScanner.withoutMetadata(inferring())
                .scan("SELECT A.c1, B.c2 FROM A,B; SELECT c2 FROM A,B;");

        assertNull(result.getResults().get(1).getError());
        List<Instruction> instructions = result.getResults().get(1).getInstructions();
        assertEquals(1, instructions.size());
        assertEquals("B.c2", instructions.get(0).getFrom());
    }

    @Test
    public void inferenceDoesNotOverrideCatalog() {
        ScanResult result = LineageScanner.withMetadata(
                catalog("CREATE TABLE known (c1 INT);"), inferring())
                .scan("SELECT c1 FROM known, other;");

        assertTrue(result.getResults().get(0).getError()
                instanceof AmbiguousColumnException);
    }

    @Test
    public void insertTargetColumnListIsNotPartOfTheWildcardVertex() {
        ScanResult result = new LineageScanner(DbType.postgresql)
                .scan("INSERT INTO t(c1,c2) SELECT c1,c2 FROM a;");

        assertEquals(0, result.getFailures().size());
        for (Column vertex : result.getGraph().getReverseGraph().vertexSet()) {
            assertFalse("column list leaked into a vertex: " + vertex,
                    vertex.getTableName() != null && vertex.getTableName().contains("("));
        }
    }

    @Test
    public void learnedMetadataCannotOverrideCatalog() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE a (c1 INT, c2 INT);");
        LineageScanner scanner = LineageScanner.withMetadata(
                MetadataContext.of(catalog), inferring());

        ScanResult result = scanner.scan(
                "SELECT a.c2,b.c1 FROM a,b; SELECT c1 FROM a,b;");

        assertNull(result.getResults().get(0).getError());
        assertTrue(result.getResults().get(1).getError()
                instanceof AmbiguousColumnException);
    }

    @Test
    public void failedStatementDoesNotTeachMetadata() {
        ScanResult result = LineageScanner.withoutMetadata(inferring())
                .scan("SELECT A.c1,B.c2,c3 FROM A,B,C,D; SELECT c1 FROM A,B;");

        assertTrue(result.getResults().get(0).getError()
                instanceof AmbiguousColumnException);
        assertTrue(result.getResults().get(1).getError()
                instanceof AmbiguousColumnException);
    }

    @Test
    public void learnedMetadataIsScopedToTheCurrentDatabase() {
        ScanResult result = LineageScanner.withoutMetadata(inferring()).scan(
                "USE db1; SELECT A.c1,B.c2 FROM A,B; USE db2; SELECT c1 FROM A,B;");

        assertEquals(4, result.getResults().size());
        assertNull(result.getResults().get(1).getError());
        assertTrue(result.getResults().get(3).getError()
                instanceof AmbiguousColumnException);
    }

    @Test
    public void strictModeKeepsGraphUnchangedForWildcards() {
        ScanResult result = new LineageScanner(DbType.postgresql)
                .scan("SELECT * FROM a;");

        assertTrue(result.getGraph().getReverseGraph().vertexSet().isEmpty());
    }

    @Test
    public void optionalModeKeepsEveryUnknownSourceOfBareStar() {
        ScanResult result = LineageScanner.withoutMetadata(optional())
                .scan("SELECT * FROM a,b;");

        assertEquals(0, result.getFailures().size());
        List<Instruction> instructions = result.getResults().get(0).getInstructions();
        assertEquals(2, instructions.size());
        assertEquals("a", instructions.get(0).getFrom());
        assertEquals("b", instructions.get(1).getFrom());
        assertTrue(result.getGraph().getReverseGraph().containsVertex(new Column("a.*")));
        assertTrue(result.getGraph().getReverseGraph().containsVertex(new Column("b.*")));
    }

    @Test
    public void optionalModeKeepsJoinAliasWildcardSources() {
        ScanResult result = LineageScanner.withoutMetadata(optional())
                .scan("SELECT j.* FROM (a CROSS JOIN b) AS j;");

        assertEquals(0, result.getFailures().size());
        List<Instruction> instructions = result.getResults().get(0).getInstructions();
        assertEquals(2, instructions.size());
        assertEquals("a", instructions.get(0).getFrom());
        assertEquals("b", instructions.get(1).getFrom());
    }

    @Test
    public void optionalModeKeepsInsertTableEdge() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE t (c1 INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional())
                .scan("INSERT INTO t(c1) SELECT * FROM missing;");

        assertEquals(0, result.getFailures().size());
        assertTrue(result.getGraph().getReverseGraph().containsVertex(new Column("t.*")));
        Set<Column> sources = result.getGraph().getSources(new Column("t.*"));
        assertTrue(sources.contains(new Column("missing.*")));
    }

    @Test
    public void optionalModeToleratesPartialTemporaryColumns() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE t (c1 INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional())
                .scan("WITH q AS (SELECT *, c1 FROM missing) SELECT q.* FROM q;"
                        + " SELECT c1 FROM t;");

        assertEquals(2, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertTrue(result.getGraph().getReverseGraph().containsVertex(new Column("missing.*")));
    }

    @Test
    public void repeatedOutputNamesStayWildcard() {
        ScanResult result = LineageScanner.withoutMetadata(optional())
                .scan("WITH q AS (SELECT A.c1,B.c1 FROM A,B) SELECT q.* FROM q;");

        assertEquals(0, result.getFailures().size());
        List<Instruction> instructions = result.getResults().get(0).getInstructions();
        assertEquals(OperationType.COLUMNS_INJECT,
                instructions.get(instructions.size() - 1).getOperation());
        assertFalse(result.getResults().get(0).getTargetOutputs()
                .getOrDefault("temp0", java.util.Collections.<String>emptyList())
                .contains("col2"));
    }

    @Test
    public void wildcardAndQuotedStarColumnAreDistinctVertices() {
        Column wildcard = new Column("target.*");
        Column quoted = new Column("target.\"*\"");
        assertTrue(wildcard.isWildcard());
        assertFalse(quoted.isWildcard());
        assertFalse(wildcard.equals(quoted));
        assertEquals("target.*", wildcard.toString());
        assertEquals("target.\"*\"", quoted.toString());
    }

    @Test
    public void optionalModeKeepsUsingMergeForKnownColumns() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE a (k INT); CREATE TABLE b (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional())
                .scan("SELECT * FROM a FULL JOIN b USING(k);");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(
                new Column("a.k"), new Column("b.k"))),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.k")));
        assertFalse(result.getGraph().getReverseGraph().containsVertex(
                Column.temporary("stmt0.temp0.col2")));
    }

    @Test
    public void optionalModeKeepsUnionNamesForKnownColumns() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE a (k INT); CREATE TABLE b (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional())
                .scan("SELECT a.k AS q FROM a UNION ALL SELECT * FROM b;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(
                new Column("a.k"), new Column("b.k"))),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.q")));
        assertFalse(result.getGraph().getReverseGraph().containsVertex(
                Column.temporary("stmt0.temp0.col1")));
    }

    @Test
    public void optionalModeKeepsTargetTableEdgeForMixedProjection() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE target (c1 INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional())
                .scan("INSERT INTO target(c1) SELECT c1,* FROM missing;");

        assertEquals(0, result.getFailures().size());
        assertTrue(result.getGraph().getReverseGraph().containsVertex(new Column("target.*")));
        Set<Column> sources = result.getGraph().getSources(new Column("target.*"));
        assertTrue(sources.contains(new Column("missing.*")));
    }

    @Test
    public void degradedTemporaryIsNotReexpandedIntoColumnNames() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE a (k INT); CREATE TABLE b (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional())
                .scan("WITH q AS (SELECT a.k AS c,b.k AS c FROM a,b) SELECT q.* FROM q;");

        assertEquals(0, result.getFailures().size());
        for (Instruction instruction : result.getResults().get(0).getInstructions()) {
            assertFalse("degraded temporary re-expanded: " + instruction,
                    "temp0.col1".equals(instruction.getTo())
                            || "temp0.col2".equals(instruction.getTo()));
        }
        assertTrue(result.getGraph().getReverseGraph().containsVertex(
                Column.temporary("stmt0.q.*")));
        assertTrue(result.getGraph().getReverseGraph().containsVertex(
                Column.temporary("stmt0.temp0.*")));
    }

    @Test
    public void tableNameWithParenthesesKeepsItsIdentity() {
        ScanResult result = LineageScanner.withoutMetadata(optional())
                .scan("SELECT * FROM \"X(y)\";");

        assertEquals(0, result.getFailures().size());
        assertTrue(result.getGraph().getReverseGraph().containsVertex(
                new Column("\"X(y)\".*")));
        assertFalse(result.getGraph().getReverseGraph().containsVertex(
                new Column("X.*")));
    }

    @Test
    public void learnedMetadataNarrowsUnknownSourceNextToCatalog() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE a (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), inferring())
                .scan("SELECT missing.c2 FROM missing; SELECT k FROM a,missing;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("a.k"))),
                result.getGraph().getSources(Column.temporary("stmt1.temp0.k")));
    }

    @Test
    public void learnedMetadataIsUsedAfterUseSwitch() {
        ScanResult result = LineageScanner.withoutMetadata(inferring()).scan(
                "SELECT A.c2,B.c1 FROM A,B; USE db2;"
                        + " SELECT A.c1,B.c2 FROM A,B; SELECT c1 FROM A,B;");

        assertNull(result.getResults().get(3).getError());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("A.c1"))),
                result.getGraph().getSources(Column.temporary("stmt3.temp0.c1")));
    }

    @Test
    public void learnedMetadataIsScopedToTheCurrentSearchPath() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE dummy (x INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), inferring()).scan(
                "SET search_path TO s1; SELECT p.c1,q.c2 FROM p,q;"
                        + " SET search_path TO s2; SELECT c1 FROM p,q;");

        assertTrue(result.getResults().get(3).getError()
                instanceof AmbiguousColumnException);
    }

    @Test
    public void partialTemporaryWithTrailingProjectionDoesNotFailScan() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE a (k INT); CREATE TABLE b (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "WITH q AS (SELECT a.k AS c,b.k AS c FROM a,b) SELECT q.*,1 AS d FROM q;"
                        + " SELECT k FROM a;");

        assertEquals(2, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertTrue(result.getGraph().getReverseGraph().containsVertex(
                Column.temporary("stmt0.temp0.*")));
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("a.k"))),
                result.getGraph().getSources(Column.temporary("stmt1.temp0.k")));
    }

    @Test
    public void declaredStarAliasesRemainResolvable() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE a (k INT, v INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional())
                .scan("WITH q(z,v) AS (SELECT * FROM a) SELECT q.z FROM q;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("a.k"))),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.z")));
    }

    @Test
    public void unionStarKeepsLockedNameResolvable() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE a (k INT, v INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "WITH q AS (SELECT k AS z,v FROM a UNION ALL SELECT * FROM a)"
                        + " SELECT q.z FROM q;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("a.k"))),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.z")));
    }

    @Test
    public void insertColumnListWithoutCatalogKeepsTableVertex() {
        ScanResult result = LineageScanner.withoutMetadata(optional())
                .scan("INSERT INTO t(c1) SELECT * FROM missing;");

        assertEquals(0, result.getFailures().size());
        assertTrue(result.getGraph().getReverseGraph().containsVertex(new Column("t.*")));
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("missing.*"))),
                result.getGraph().getSources(new Column("t.*")));
    }

    @Test
    public void mergedUsingOutputIsNotAmbiguousWithUnknownSide() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE a (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional())
                .scan("SELECT k FROM missing RIGHT JOIN a USING(k);");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("a.k"))),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.k")));
    }

    @Test
    public void learnedMetadataIsScopedToTheWholeSearchPath() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE dummy (x INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), inferring()).scan(
                "SET search_path TO first,s1; SELECT p.c1,q.c2 FROM p,q;"
                        + " SET search_path TO first,s2; SELECT c1 FROM p,q;");

        assertTrue(result.getResults().get(3).getError()
                instanceof AmbiguousColumnException);
    }

    @Test
    public void unionKnownBranchDoesNotHideUnknownWildcardInInsert() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE a (k INT); CREATE TABLE t (c1 INT);");
        for (String sql : new String[] {
                "INSERT INTO t(c1) SELECT * FROM missing UNION ALL SELECT k FROM a;",
                "INSERT INTO t(c1) SELECT k FROM a UNION ALL SELECT * FROM missing;" }) {
            ScanResult result = LineageScanner.withMetadata(
                    MetadataContext.of(catalog), optional()).scan(sql);

            assertEquals(0, result.getFailures().size());
            assertTrue("missing table edge lost: " + sql,
                    result.getGraph().getReverseGraph().containsVertex(new Column("t.*")));
            assertTrue(result.getGraph().getSources(new Column("t.*"))
                    .contains(new Column("missing.*")));
        }
    }

    @Test
    public void usingWithNameUnknownSideIsNotAmbiguous() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE a (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "WITH x AS (SELECT * FROM missing UNION ALL SELECT k AS z FROM a)"
                        + " SELECT k FROM x RIGHT JOIN a USING(k);");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("a.k"))),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.k")));
    }

    @Test
    public void searchPathSeparatorsArePreserved() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE dummy (x INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), inferring()).scan(
                "SET search_path TO \"s1,s2\"; SELECT p.c1,q.c2 FROM p,q;"
                        + " SET search_path TO s1,s2; SELECT c1 FROM p,q;");

        assertTrue(result.getResults().get(3).getError()
                instanceof AmbiguousColumnException);
    }

    @Test
    public void unboundStarAliasIsRejected() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE a (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional())
                .scan("SELECT z.* FROM a; SELECT k FROM a;");

        assertEquals(2, result.getResults().size());
        assertTrue(result.getResults().get(0).getError()
                instanceof MetadataNotFoundException);
        assertTrue(result.getResults().get(0).getInstructions().isEmpty());
        assertNull(result.getResults().get(1).getError());
    }

    @Test
    public void columnAfterUnknownWildcardIsNotPositioned() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE t (c1 INT, c2 INT, c3 INT);");
        ScanResult afterStar = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional())
                .scan("INSERT INTO t(c1,c2,c3) SELECT *,k FROM missing;");

        assertEquals(0, afterStar.getFailures().size());
        for (Instruction instruction : afterStar.getResults().get(0).getInstructions()) {
            assertFalse("guessed position after wildcard: " + instruction,
                    "t.c2".equals(instruction.getTo()));
        }
        assertTrue(afterStar.getGraph().getReverseGraph().containsVertex(new Column("t.*")));

        ScanResult beforeStar = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional())
                .scan("INSERT INTO t(c1,c2,c3) SELECT k,* FROM missing;");
        assertEquals(0, beforeStar.getFailures().size());
        assertTrue(beforeStar.getGraph().getSources(new Column("t.c1"))
                .contains(new Column("missing.k")));
        assertTrue(beforeStar.getGraph().getReverseGraph().containsVertex(new Column("t.*")));
    }

    @Test
    public void usingMergeKeepsUnknownSideWildcard() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE a (k INT, v INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional())
                .scan("SELECT * FROM missing RIGHT JOIN a USING(k);");

        assertEquals(0, result.getFailures().size());
        Set<Column> wildcardSources = result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.*"));
        assertTrue(wildcardSources.contains(new Column("missing.*")));
        assertTrue(wildcardSources.contains(new Column("a.*")));
        // The positions of merged columns cannot be determined: no column edges.
        assertFalse(result.getGraph().getReverseGraph().containsVertex(
                Column.temporary("stmt0.temp0.v")));
    }

    @Test
    public void quotedSearchPathKeepsCaseInTheLearningKey() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE dummy (x INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), inferring()).scan(
                "SET search_path TO \"S1\"; SELECT p.c1,q.c2 FROM p,q;"
                        + " SET search_path TO s1; SELECT c1 FROM p,q;");

        assertTrue(result.getResults().get(3).getError()
                instanceof AmbiguousColumnException);
    }

    @Test
    public void unboundStarAliasWithoutCatalogIsRejected() {
        ScanResult result = LineageScanner.withoutMetadata(optional())
                .scan("SELECT z.* FROM a; SELECT k FROM a;");

        assertEquals(2, result.getResults().size());
        assertTrue(result.getResults().get(0).getError()
                instanceof MetadataNotFoundException);
        assertTrue(result.getResults().get(0).getInstructions().isEmpty());
        assertNull(result.getResults().get(1).getError());
    }

    @Test
    public void unknownRemainderKeepsFollowingInsertPosition() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE a (k INT, v INT);"
                + " CREATE TABLE t (c1 INT, c2 INT, c3 INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t(c1,c2,c3) SELECT * FROM missing RIGHT JOIN a USING(k);");

        assertEquals(0, result.getFailures().size());
        Set<Column> wildcardSources = result.getGraph().getSources(new Column("t.*"));
        assertTrue(wildcardSources.contains(new Column("missing.*")));
        assertTrue(wildcardSources.contains(new Column("a.*")));
        // No column position can be determined, so attributions stay table-level.
        assertOnlyWildcardSources(result.getGraph(), new Column("t.c2"));
        assertOnlyWildcardSources(result.getGraph(), new Column("t.c3"));
    }

    @Test
    public void usingMergeKeepsUnknownRemainderSide() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE a (k INT, v INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional())
                .scan("SELECT * FROM missing LEFT JOIN a USING(k);");

        assertEquals(0, result.getFailures().size());
        Set<Column> wildcardSources = result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.*"));
        assertTrue(wildcardSources.contains(new Column("missing.*")));
        assertTrue(wildcardSources.contains(new Column("a.*")));
        assertFalse(result.getGraph().getReverseGraph().containsVertex(
                Column.temporary("stmt0.temp0.v")));
    }

    @Test
    public void qualifiedTableReferencesKeepSchemaIdentity() {
        ScanResult result = LineageScanner.withoutMetadata(optional()).scan(
                "INSERT INTO t SELECT * FROM s1.a; INSERT INTO u SELECT * FROM s2.a;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("s1.a.*"))),
                result.getGraph().getSources(new Column("t.*")));
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("s2.a.*"))),
                result.getGraph().getSources(new Column("u.*")));
    }

    @Test
    public void unboundStarWithoutFromIsRejected() {
        ScanResult result = LineageScanner.withoutMetadata(optional())
                .scan("SELECT z.*; SELECT k FROM a;");

        assertEquals(2, result.getResults().size());
        assertTrue(result.getResults().get(0).getError()
                instanceof MetadataNotFoundException);
        assertTrue(result.getResults().get(0).getInstructions().isEmpty());
        assertNull(result.getResults().get(1).getError());
    }

    @Test
    public void unionBranchOrderKeepsWildcardAndKnownColumns() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE a (k INT, v INT);"
                + " CREATE TABLE b (k INT, q INT, v INT);"
                + " CREATE TABLE t (c1 INT, c2 INT, c3 INT);");
        for (String sql : new String[] {
                "INSERT INTO t(c1,c2,c3) SELECT k,q,v FROM b"
                        + " UNION ALL SELECT * FROM missing RIGHT JOIN a USING(k);",
                "INSERT INTO t(c1,c2,c3) SELECT * FROM missing RIGHT JOIN a USING(k)"
                        + " UNION ALL SELECT k,q,v FROM b;" }) {
            ScanResult result = LineageScanner.withMetadata(
                    MetadataContext.of(catalog), optional()).scan(sql);

            assertEquals(0, result.getFailures().size());
            Set<Column> wildcardSources = result.getGraph().getSources(new Column("t.*"));
            assertTrue("wildcard edge lost: " + sql,
                    wildcardSources.contains(new Column("missing.*")));
            // The second position is explicitly supplied by the known UNION branch.
            assertTrue("known branch column edge lost: " + sql,
                    result.getGraph().getSources(new Column("t.c2"))
                            .contains(new Column("b.q")));
        }
    }

    @Test
    public void twoUnknownRemaindersDoNotGuessColumnPositions() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE a (k INT, v INT);"
                + " CREATE TABLE t (c1 INT, c2 INT, c3 INT, c4 INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t(c1,c2,c3,c4)"
                        + " SELECT * FROM m1 RIGHT JOIN a USING(k) LEFT JOIN m2 USING(k);");

        assertEquals(0, result.getFailures().size());
        assertOnlyWildcardSources(result.getGraph(), new Column("t.c2"));
        assertTrue(result.getGraph().getSources(new Column("t.*"))
                .contains(new Column("m1.*")));
        assertTrue(result.getGraph().getSources(new Column("t.*"))
                .contains(new Column("m2.*")));
    }

    @Test
    public void aliasedQualifiedTablesKeepPhysicalIdentity() {
        ScanResult result = LineageScanner.withoutMetadata(optional()).scan(
                "INSERT INTO t SELECT x.* FROM s1.a AS x;"
                        + " INSERT INTO u SELECT x.* FROM s2.a AS x;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("s1.a.*"))),
                result.getGraph().getSources(new Column("t.*")));
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("s2.a.*"))),
                result.getGraph().getSources(new Column("u.*")));
    }

    @Test
    public void strictModeKeepsUnqualifiedTableIdentity() {
        ScanResult result = new LineageScanner(DbType.postgresql)
                .scan("SELECT k FROM s.a;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("a.k"))),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.k")));
    }

    @Test
    public void contractThroughDerivedUnion() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE a (k INT, v INT);"
                + " CREATE TABLE b (k INT, q INT, v INT);"
                + " CREATE TABLE t (c1 INT, c2 INT, c3 INT);");

        ScanResult unknownNamesFirst = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t(c1,c2,c3) SELECT q.* FROM ("
                        + "SELECT * FROM missing RIGHT JOIN a USING(k)"
                        + " UNION ALL SELECT k,q,v FROM b) AS q;");
        assertEquals(0, unknownNamesFirst.getFailures().size());
        Set<Column> sources = unknownNamesFirst.getGraph()
                .getSources(new Column("t.*"));
        assertTrue(sources.contains(new Column("missing.*")));
        Column target = new Column("t.c2");
        for (Column source : unknownNamesFirst.getGraph().getSources(target)) {
            if (source.equals(target)) {
                continue;
            }
            assertTrue("unexpected position guess: " + source,
                    source.isWildcard() || source.equals(new Column("b.q")));
        }

        ScanResult knownNamesFirst = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t(c1,c2,c3) SELECT q.* FROM ("
                        + "SELECT k,q,v FROM b"
                        + " UNION ALL SELECT * FROM missing RIGHT JOIN a USING(k)) AS q;");
        assertEquals(0, knownNamesFirst.getFailures().size());
        assertTrue(knownNamesFirst.getGraph().getSources(new Column("t.c1"))
                .contains(new Column("b.k")));
        assertTrue(knownNamesFirst.getGraph().getSources(new Column("t.c2"))
                .contains(new Column("b.q")));
        // The unknown UNION branch still contributes a table-level edge.
        assertTrue(knownNamesFirst.getGraph().getSources(new Column("t.*"))
                .contains(new Column("missing.*")));
    }

    @Test
    public void contractThroughKnownUnionBranch() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT); CREATE TABLE t (c1 INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t(c1) SELECT q.* FROM ("
                        + "SELECT k FROM b UNION ALL SELECT * FROM missing) AS q;");

        assertEquals(0, result.getFailures().size());
        assertTrue(result.getGraph().getSources(new Column("t.c1"))
                .contains(new Column("b.k")));
        assertTrue(result.getGraph().getSources(new Column("t.*"))
                .contains(new Column("missing.*")));
    }

    @Test
    public void wildcardAfterUnknownStarStaysTableLevel() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE a (v INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional())
                .scan("SELECT missing.*, a.* FROM missing, a;");

        assertEquals(0, result.getFailures().size());
        Set<Column> sources = result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.*"));
        assertTrue(sources.contains(new Column("missing.*")));
        assertTrue(sources.contains(new Column("a.*")));
        assertFalse(result.getGraph().getReverseGraph()
                .containsVertex(Column.temporary("stmt0.temp0.v")));
    }

    @Test
    public void explicitAliasAfterUnknownWildcardResolvesWithoutGuessing() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE a (v INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "WITH q AS (SELECT *, a.v AS z FROM a, missing) SELECT q.z FROM q;");

        assertEquals(0, result.getFailures().size());
        Set<Column> sources = result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.z"));
        assertEquals(new HashSet<>(java.util.Arrays.asList(
                Column.temporary("stmt0.q.z"))), sources);
        assertFalse(sources.contains(new Column("a.v")));
        Set<Column> wildcards = result.getGraph()
                .getSources(Column.temporary("stmt0.q.*"));
        assertTrue(wildcards.contains(new Column("missing.*")));
    }

    @Test
    public void quotedAndQualifiedTableIdentitiesStayDistinct() {
        ScanResult result = LineageScanner.withoutMetadata(optional()).scan(
                "INSERT INTO t SELECT * FROM \"s.a\"; INSERT INTO u SELECT * FROM s.a;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("\"s.a\".*"))),
                result.getGraph().getSources(new Column("t.*")));
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("s.a.*"))),
                result.getGraph().getSources(new Column("u.*")));
    }

    @Test
    public void strictInsertKeepsLegacyProjectionPositions() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "INSERT INTO t(c1,c2,c3) SELECT a.*,b.k FROM a,b;");

        assertEquals(0, result.getFailures().size());
        List<Instruction> instructions = result.getResults().get(0).getInstructions();
        assertEquals(3, instructions.size());
        assertEquals(OperationType.COLUMNS_INJECT, instructions.get(0).getOperation());
        assertEquals("a", instructions.get(0).getFrom());
        assertEquals(OperationType.COLUMN_MAPPING, instructions.get(1).getOperation());
        assertEquals("b.k", instructions.get(1).getFrom());
        assertEquals(1, instructions.get(1).getOrdinal());
    }

    @Test
    public void joinAliasExplicitColumnAfterUnknownWildcardResolves() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE a (v INT); CREATE TABLE b (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "WITH q AS (SELECT *, a.v AS z FROM a, missing)"
                        + " SELECT j.z FROM (q CROSS JOIN b) AS j;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(
                Column.temporary("stmt0.q.z"))),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.z")));
        assertTrue(result.getGraph().getSources(Column.temporary("stmt0.q.*"))
                .contains(new Column("missing.*")));
    }

    @Test
    public void explicitDerivedColumnKeepsWildcardContribution() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT); CREATE TABLE t (c1 INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t(c1) SELECT q.k FROM ("
                        + "SELECT k FROM b UNION ALL SELECT * FROM missing) AS q;");

        assertEquals(0, result.getFailures().size());
        assertTrue(result.getGraph().getSources(new Column("t.c1"))
                .contains(new Column("b.k")));
        assertTrue(result.getGraph().getSources(new Column("t.*"))
                .contains(new Column("missing.*")));
    }

    @Test
    public void optionalInsertTargetMissingFromCatalogKeepsEdge() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional())
                .scan("INSERT INTO t SELECT * FROM missing;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("missing.*"))),
                result.getGraph().getSources(new Column("t.*")));
    }

    @Test
    public void quotedInsertTargetMatchesLaterRead() {
        ScanResult result = LineageScanner.withoutMetadata(optional()).scan(
                "INSERT INTO \"s.a\" SELECT * FROM missing; SELECT * FROM \"s.a\";");

        assertEquals(0, result.getFailures().size());
        assertTrue(result.getGraph().getSources(Column.temporary("stmt1.temp0.*"))
                .contains(new Column("missing.*")));
    }

    @Test
    public void contributionEdgeStaysTableLevel() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT, v INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT q.v, 0 AS z FROM ("
                        + "SELECT k, v FROM b UNION ALL SELECT * FROM missing) AS q;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("b.v"))),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.v")));
        assertTrue(result.getGraph().getSources(Column.temporary("stmt0.temp0.*"))
                .contains(new Column("missing.*")));
    }

    @Test
    public void joinAliasReadKeepsContribution() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT, v INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "WITH q AS (SELECT k FROM b UNION ALL SELECT * FROM missing)"
                        + " SELECT j.k FROM (q CROSS JOIN (SELECT 1 AS v) AS z) AS j;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("b.k"))),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.k")));
        assertTrue(result.getGraph().getSources(Column.temporary("stmt0.temp0.*"))
                .contains(new Column("missing.*")));
    }

    @Test
    public void chainedContributionPropagates() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT, v INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "WITH q AS (SELECT k FROM b UNION ALL SELECT * FROM missing),"
                        + " r AS (SELECT k FROM q) SELECT k FROM r;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("b.k"))),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.k")));
        assertTrue(result.getGraph().getSources(Column.temporary("stmt0.temp0.*"))
                .contains(new Column("missing.*")));
    }

    @Test
    public void quotedExplicitColumnChainStaysConnected() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT, v INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO \"s.a\" SELECT k FROM b;"
                        + " SELECT k FROM \"s.a\";");

        assertEquals(0, result.getFailures().size());
        assertTrue(result.getGraph().getSources(Column.temporary("stmt1.temp0.k"))
                .contains(new Column("b.k")));
    }

    @Test
    public void wildcardAttachmentHonorsInsertColumns() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE t (c1 INT, c2 INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional())
                .scan("INSERT INTO t(c1) SELECT * FROM missing; SELECT c2 FROM t;");

        assertEquals(0, result.getFailures().size());
        assertFalse(result.getGraph().getSources(new Column("t.c2"))
                .contains(new Column("missing.*")));
        assertTrue(result.getGraph().getSources(new Column("t.*"))
                .contains(new Column("missing.*")));
    }

    @Test
    public void contributionSurvivesUnknownStarBeforeJoinAliasRead() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "WITH q AS (SELECT k FROM b UNION ALL SELECT * FROM missing)"
                        + " SELECT u.*, j.k FROM u"
                        + " CROSS JOIN (q CROSS JOIN (SELECT 1 AS z) AS x) AS j;");

        assertEquals(0, result.getFailures().size());
        assertTrue(result.getGraph().getSources(Column.temporary("stmt0.temp0.*"))
                .contains(new Column("missing.*")));
        assertFalse(result.getGraph().getReverseGraph()
                .containsVertex(Column.temporary("stmt0.temp0.k")));
    }

    @Test
    public void quotedSingleSegmentIdentityMatchesReads() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO \"A\" SELECT k FROM b; SELECT k FROM \"A\";");

        assertEquals(0, result.getFailures().size());
        assertTrue(result.getGraph().getSources(Column.temporary("stmt1.temp0.k"))
                .contains(new Column("b.k")));
    }

    @Test
    public void wildcardInsertsKeepDistinctColumnSources() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE t (c1 INT, c2 INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t(c1) SELECT * FROM m1;"
                        + " INSERT INTO t(c2) SELECT * FROM m2;"
                        + " SELECT c1, c2 FROM t;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("m1.*"))),
                result.getGraph().getSources(new Column("t.c1")));
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("m2.*"))),
                result.getGraph().getSources(new Column("t.c2")));
    }

    @Test
    public void readOnlyWildcardDoesNotOverrideColumnSource() {
        ScanResult result = LineageScanner.withoutMetadata(optional())
                .scan("SELECT a.k, a.* FROM a;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("a.k"))),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.k")));
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("a.*"))),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.*")));
    }

    @Test
    public void quotedLowercaseTableIsEquivalentToUnquoted() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO \"a\" SELECT k FROM b; SELECT k FROM a;");

        assertEquals(0, result.getFailures().size());
        assertTrue(result.getGraph().getSources(Column.temporary("stmt1.temp0.k"))
                .contains(new Column("b.k")));
    }

    @Test
    public void insertWildcardContributionOnlyAffectsUnknownPositions() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT, v INT);"
                + " CREATE TABLE t (c1 INT, c2 INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t(c1, c2) SELECT b.k, missing.* FROM b, missing;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("b.k"))),
                result.getGraph().getSources(new Column("t.c1")));
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("missing.*"))),
                result.getGraph().getSources(new Column("t.c2")));
        assertTrue(result.getGraph().getSources(new Column("t.*"))
                .contains(new Column("missing.*")));
    }

    @Test
    public void parenthesizedQuotedTableKeepsIdentity() {
        ScanResult result = LineageScanner.withoutMetadata(optional()).scan(
                "INSERT INTO \"x(y)\" SELECT * FROM missing;"
                        + " SELECT * FROM \"x(y)\";");

        assertEquals(0, result.getFailures().size());
        assertTrue(result.getGraph().getSources(Column.temporary("stmt1.temp0.*"))
                .contains(new Column("missing.*")));
        assertFalse(result.getGraph().getReverseGraph().containsVertex(new Column("x.*")));
    }

    @Test
    public void postgresUnquotedCaseIsEquivalent() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT, v INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO A SELECT k FROM b; SELECT k FROM a;");

        assertEquals(0, result.getFailures().size());
        assertTrue(result.getGraph().getSources(Column.temporary("stmt1.temp0.k"))
                .contains(new Column("b.k")));
    }

    @Test
    public void aliasReadUsesPhysicalIdentity() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT, v INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t1 SELECT k FROM b; SELECT x.k FROM t1 AS x;");

        assertEquals(0, result.getFailures().size());
        assertTrue(result.getGraph().getSources(Column.temporary("stmt1.temp0.k"))
                .contains(new Column("b.k")));
    }

    @Test
    public void repeatedWildcardWritesAccumulate() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE t (c1 INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t(c1) SELECT * FROM m1;"
                        + " INSERT INTO t(c1) SELECT * FROM m2;"
                        + " SELECT c1 FROM t;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(
                new Column("m1.*"), new Column("m2.*"))),
                result.getGraph().getSources(new Column("t.c1")));
    }

    @Test
    public void noCatalogInsertPrefixColumnKeepsPreciseSource() {
        ScanResult result = LineageScanner.withoutMetadata(optional()).scan(
                "INSERT INTO t(c1, c2) SELECT b.k, missing.* FROM b, missing;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("b.k"))),
                result.getGraph().getSources(new Column("t.c1")));
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("missing.*"))),
                result.getGraph().getSources(new Column("t.c2")));
    }

    @Test
    public void joinAliasReadAfterInsertUsesPhysicalIdentity() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t1 SELECT k FROM b;"
                        + " SELECT j.k FROM (t1 AS x LEFT JOIN b USING(k)) AS j;");

        assertEquals(0, result.getFailures().size());
        assertTrue(result.getGraph().getSources(Column.temporary("stmt1.temp0.k"))
                .contains(new Column("b.k")));
    }

    @Test
    public void temporaryPositionsAreScopedPerStatement() {
        ScanResult result = LineageScanner.withoutMetadata(optional()).scan(
                "INSERT INTO t(c1) SELECT * FROM missing;"
                        + " INSERT INTO u(c1) SELECT b.k FROM b;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("missing.*"))),
                result.getGraph().getSources(new Column("t.c1")));
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("b.k"))),
                result.getGraph().getSources(new Column("u.c1")));
    }

    @Test
    public void preciseAndWildcardWritesAccumulate() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE t (c1 INT); CREATE TABLE b (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t(c1) SELECT * FROM missing;"
                        + " INSERT INTO t(c1) SELECT k FROM b;"
                        + " SELECT c1 FROM t;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(
                new Column("missing.*"), new Column("b.k"))),
                result.getGraph().getSources(new Column("t.c1")));
    }

    @Test
    public void postgresUnquotedColumnCaseIsEquivalent() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t(C1) SELECT k FROM b; SELECT c1 FROM t;");

        assertEquals(0, result.getFailures().size());
        assertTrue(result.getGraph().getSources(Column.temporary("stmt1.temp0.c1"))
                .contains(new Column("b.k")));
    }

    @Test
    public void duplicateProjectionNamesKeepSeparatePositions() {
        ScanResult result = LineageScanner.withoutMetadata(optional()).scan(
                "INSERT INTO t(c1, c2) SELECT a.k, b.k FROM a, b;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("a.k"))),
                result.getGraph().getSources(new Column("t.c1")));
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("b.k"))),
                result.getGraph().getSources(new Column("t.c2")));
    }

    @Test
    public void constantPrefixColumnHasNoWildcardSource() {
        ScanResult result = LineageScanner.withoutMetadata(optional()).scan(
                "INSERT INTO t(c1, c2) SELECT 1 AS fixed, missing.* FROM missing;");

        assertEquals(0, result.getFailures().size());
        assertFalse(result.getGraph().getSources(new Column("t.c1"))
                .contains(new Column("missing.*")));
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("missing.*"))),
                result.getGraph().getSources(new Column("t.c2")));
    }

    @Test
    public void joinUsingColumnCaseIsCanonicalized() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t1(k) SELECT k FROM b;"
                        + " SELECT j.K FROM (t1 AS x LEFT JOIN b USING(K)) AS j;");

        assertEquals(0, result.getFailures().size());
        assertTrue(result.getGraph().getSources(Column.temporary("stmt1.temp0.K"))
                .contains(new Column("b.k")));
    }

    @Test
    public void tableLevelWriteCoversColumnsWithPreciseWrites() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE t (c1 INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t SELECT * FROM m1;"
                        + " INSERT INTO t(c1) SELECT * FROM m2;"
                        + " SELECT * FROM t;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(
                new Column("m1.*"), new Column("m2.*"))),
                result.getGraph().getSources(new Column("t.c1")));
    }

    @Test
    public void generatedTemporaryNamesAvoidExplicitAliases() {
        ScanResult result = LineageScanner.withoutMetadata(optional()).scan(
                "INSERT INTO t(c1, c2, c3)"
                        + " SELECT a.k, b.v AS col3, c.k FROM a, b, c;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("a.k"))),
                result.getGraph().getSources(new Column("t.c1")));
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("b.v"))),
                result.getGraph().getSources(new Column("t.c2")));
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("c.k"))),
                result.getGraph().getSources(new Column("t.c3")));
    }

    @Test
    public void explicitAliasKeepsItsOuterReference() {
        ScanResult result = LineageScanner.withoutMetadata(optional()).scan(
                "SELECT q.col2 FROM (SELECT a.k, b.k, b.v AS col2 FROM a, b) AS q;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("b.v"))),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.col2")));
    }

    @Test
    public void quotedCteColumnSurvivesJoinAliasRead() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "WITH q AS (SELECT k AS \"K\" FROM b)"
                        + " SELECT j.\"K\" FROM (q CROSS JOIN (SELECT 1 AS v) AS y) AS j;");

        assertEquals(0, result.getFailures().size());
        assertTrue(result.getGraph().getSources(Column.temporary("stmt0.temp0.K"))
                .contains(new Column("b.k")));
    }

    @Test
    public void wholeTableWriteDoesNotCoverOtherColumns() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE t (c1 INT, c2 INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t SELECT * FROM m1;"
                        + " INSERT INTO t(c1) SELECT * FROM m2;"
                        + " SELECT c2 FROM t;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("m1.*"))),
                result.getGraph().getSources(new Column("t.c2")));
        assertEquals(new HashSet<>(java.util.Arrays.asList(
                new Column("m1.*"), new Column("m2.*"))),
                result.getGraph().getSources(new Column("t.c1")));
    }

    @Test
    public void multiSourceAliasKeepsItsOuterReference() {
        ScanResult result = LineageScanner.withoutMetadata(optional()).scan(
                "SELECT q.col2 FROM (SELECT a.k, b.k, b.v + b.w AS col2 FROM a, b) AS q;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(
                new Column("b.v"), new Column("b.w"))),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.col2")));
    }

    @Test
    public void constantAliasIsPreservedAndHasNoSource() {
        ScanResult result = LineageScanner.withoutMetadata(optional()).scan(
                "SELECT q.col2 FROM (SELECT a.k, b.k, 1 AS col2 FROM a, b) AS q;");

        assertEquals(0, result.getFailures().size());
        assertFalse(result.getGraph().getSources(Column.temporary("stmt0.temp0.col2"))
                .contains(new Column("b.k")));
    }

    @Test
    public void wholeTableWriteHandlesCommaInColumnName() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE t (\"a,b\" INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t SELECT * FROM missing; SELECT \"a,b\" FROM t;");

        assertEquals(0, result.getFailures().size());
        assertTrue(result.getGraph().getSources(Column.temporary("stmt1.temp0.a,b"))
                .contains(new Column("missing.*")));
    }

    @Test
    public void wholeTableWriteRespectsKnownProjectionWidth() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE t (c1 INT, c2 INT); CREATE TABLE b (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t SELECT k FROM b UNION ALL SELECT * FROM missing;"
                        + " SELECT c2 FROM t;");

        assertEquals(0, result.getFailures().size());
        assertFalse(result.getGraph().getSources(new Column("t.c2"))
                .contains(new Column("missing.*")));
    }

    @Test
    public void generatedNamesDoNotTakeReservedGroupNames() {
        ScanResult result = LineageScanner.withoutMetadata(optional()).scan(
                "INSERT INTO t(c1,c2,c3,c4)"
                        + " SELECT a.k, b.k, a.v AS col2, b.v AS col2 FROM a, b;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("a.k"))),
                result.getGraph().getSources(new Column("t.c1")));
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("b.k"))),
                result.getGraph().getSources(new Column("t.c2")));
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("a.v"))),
                result.getGraph().getSources(new Column("t.c3")));
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("b.v"))),
                result.getGraph().getSources(new Column("t.c4")));
    }

    @Test
    public void wholeTableWriteUsesDeclaredUnionWidth() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE t (c1 INT, c2 INT, c3 INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t SELECT * FROM missing UNION ALL SELECT 1, 2;"
                        + " SELECT c2 FROM t;");

        assertEquals(0, result.getFailures().size());
        assertTrue(result.getGraph().getSources(new Column("t.c2"))
                .contains(new Column("missing.*")));
        if (result.getGraph().getReverseGraph().containsVertex(new Column("t.c3"))) {
            assertFalse(result.getGraph().getSources(new Column("t.c3"))
                    .contains(new Column("missing.*")));
        }
    }

    @Test
    public void wildcardWidthStartsAtItsProjectionPosition() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE t (c1 INT, c2 INT, c3 INT);"
                + " CREATE TABLE b (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t SELECT b.k, missing.* FROM b, missing"
                        + " UNION ALL SELECT 1, 2; SELECT c2 FROM t;");

        assertEquals(0, result.getFailures().size());
        assertTrue(result.getGraph().getSources(new Column("t.c2"))
                .contains(new Column("missing.*")));
    }

    @Test
    public void wildcardDoesNotCoverEarlierConstantColumn() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE t (c1 INT, c2 INT, c3 INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t(c1, c2) SELECT 1 AS fixed, missing.* FROM missing"
                        + " UNION ALL SELECT 2, 3;");

        assertEquals(0, result.getFailures().size());
        if (result.getGraph().getReverseGraph().containsVertex(new Column("t.c1"))) {
            assertFalse(result.getGraph().getSources(new Column("t.c1"))
                    .contains(new Column("missing.*")));
        }
        assertTrue(result.getGraph().getSources(new Column("t.c2"))
                .contains(new Column("missing.*")));
    }

    @Test
    public void derivedUnionWidthBoundsOuterWildcard() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE t (c1 INT, c2 INT, c3 INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t SELECT * FROM ("
                        + "SELECT * FROM missing UNION ALL SELECT 1, 2) AS q;"
                        + " SELECT c3 FROM t;");

        assertEquals(0, result.getFailures().size());
        if (result.getGraph().getReverseGraph().containsVertex(new Column("t.c3"))) {
            assertFalse(result.getGraph().getSources(new Column("t.c3"))
                    .contains(new Column("missing.*")));
        }
        assertTrue(result.getGraph().getSources(new Column("t.c2"))
                .contains(new Column("missing.*")));
    }

    @Test
    public void separateWildcardRangesKeepTheirOwnSources() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE t (c1 INT, c2 INT, c3 INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t(c1, c2) SELECT x.*, y.* FROM ("
                        + "SELECT * FROM m1 UNION ALL SELECT 1) AS x"
                        + " CROSS JOIN (SELECT * FROM m2 UNION ALL SELECT 2) AS y;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("m1.*"))),
                result.getGraph().getSources(new Column("t.c1")));
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("m2.*"))),
                result.getGraph().getSources(new Column("t.c2")));
    }

    @Test
    public void laterWildcardRangeKeepsItsSource() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE t (c1 INT, c2 INT, c3 INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t(c1, c2) SELECT x.*, y.* FROM ("
                        + "SELECT * FROM m1 UNION ALL SELECT 1) AS x"
                        + " CROSS JOIN m2 AS y;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("m2.*"))),
                result.getGraph().getSources(new Column("t.c2")));
    }

    @Test
    public void multiSourceStarWidthBoundsWholeTableWrite() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE t (c1 INT, c2 INT, c3 INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t SELECT * FROM ("
                        + "SELECT * FROM missing UNION ALL SELECT 1) AS q"
                        + " CROSS JOIN (SELECT 2) AS b;"
                        + " SELECT c3 FROM t;");

        assertEquals(0, result.getFailures().size());
        if (result.getGraph().getReverseGraph().containsVertex(new Column("t.c3"))) {
            assertFalse(result.getGraph().getSources(new Column("t.c3"))
                    .contains(new Column("missing.*")));
        }
    }

    @Test
    public void overlappingUnionWildcardRangesAccumulateSources() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE t (c1 INT, c2 INT, c3 INT);");
        for (String sql : new String[] {
                "INSERT INTO t(c1) SELECT * FROM m1"
                        + " UNION ALL SELECT * FROM m2 UNION ALL SELECT 1;",
                "INSERT INTO t(c1) SELECT * FROM m2"
                        + " UNION ALL SELECT * FROM m1 UNION ALL SELECT 1;" }) {
            ScanResult result = LineageScanner.withMetadata(
                    MetadataContext.of(catalog), optional()).scan(sql);
            assertEquals(0, result.getFailures().size());
            assertEquals("wildcard sources lost: " + sql,
                    new HashSet<>(java.util.Arrays.asList(
                            new Column("m1.*"), new Column("m2.*"))),
                    result.getGraph().getSources(new Column("t.c1")));
        }
    }

    @Test
    public void openRangeStartsAtItsOwnPosition() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE t (c1 INT, c2 INT, c3 INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t(c1,c2,c3) SELECT x.*, y.* FROM ("
                        + "SELECT * FROM m1 UNION ALL SELECT 1) AS x"
                        + " CROSS JOIN m2 AS y;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("m1.*"))),
                result.getGraph().getSources(new Column("t.c1")));
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("m2.*"))),
                result.getGraph().getSources(new Column("t.c3")));
    }

    @Test
    public void usingMergedWidthBoundsWholeTableWrite() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE t (c1 INT, c2 INT, c3 INT);"
                + " CREATE TABLE b (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t SELECT * FROM ("
                        + "SELECT * FROM missing UNION ALL SELECT 1) AS x"
                        + " JOIN b USING(k); SELECT c2 FROM t;");

        assertEquals(0, result.getFailures().size());
        if (result.getGraph().getReverseGraph().containsVertex(new Column("t.c2"))) {
            Set<Column> sources = result.getGraph().getSources(new Column("t.c2"));
            assertFalse(sources.contains(new Column("missing.*")));
            assertFalse(sources.contains(new Column("b.*")));
        }
    }

    @Test
    public void contributionDoesNotCoverNextConstant() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE t (c1 INT, c2 INT, c3 INT);"
                + " CREATE TABLE b (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t(c1, c2) SELECT q.k, 42 AS fixed FROM ("
                        + "SELECT k FROM b UNION ALL SELECT * FROM missing) AS q;");

        assertEquals(0, result.getFailures().size());
        if (result.getGraph().getReverseGraph().containsVertex(new Column("t.c2"))) {
            assertFalse(result.getGraph().getSources(new Column("t.c2"))
                    .contains(new Column("missing.*")));
        }
    }

    @Test
    public void partiallyOverlappingWildcardRegionsStaySeparate() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE t (c1 INT, c2 INT, c3 INT);");

        ScanResult shifted = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t(c1, c2) SELECT * FROM m1"
                        + " UNION ALL SELECT 1 AS fixed, m2.* FROM m2"
                        + " UNION ALL SELECT 2, 3;");
        assertEquals(0, shifted.getFailures().size());
        assertFalse(shifted.getGraph().getSources(new Column("t.c1"))
                .contains(new Column("m2.*")));
        assertTrue(shifted.getGraph().getSources(new Column("t.c2"))
                .contains(new Column("m2.*")));

        ScanResult wider = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t(c1, c2) SELECT m1.*, 0 FROM m1"
                        + " UNION ALL SELECT * FROM m2"
                        + " UNION ALL SELECT 1, 2;");
        assertEquals(0, wider.getFailures().size());
        assertTrue(wider.getGraph().getSources(new Column("t.c2"))
                .contains(new Column("m2.*")));
        assertFalse(wider.getGraph().getSources(new Column("t.c2"))
                .contains(new Column("m1.*")));
    }

    @Test
    public void bareStarOverWidthKnownSourcesKeepsPerSourcePositions() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE t (c1 INT, c2 INT, c3 INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t(c1, c2) SELECT * FROM ("
                        + "SELECT * FROM m1 UNION ALL SELECT 1) AS x"
                        + " CROSS JOIN (SELECT * FROM m2 UNION ALL SELECT 2) AS y;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("m1.*"))),
                result.getGraph().getSources(new Column("t.c1")));
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("m2.*"))),
                result.getGraph().getSources(new Column("t.c2")));
    }

    @Test
    public void usingLeftKeepsRightOnlyColumn() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE t (c1 INT, c2 INT, c3 INT);"
                + " CREATE TABLE b (k INT, w INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t SELECT * FROM ("
                        + "SELECT * FROM missing UNION ALL SELECT 1) AS x"
                        + " LEFT JOIN b USING(k); SELECT c2 FROM t;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("b.*"))),
                result.getGraph().getSources(new Column("t.c2")));
    }

    @Test
    public void derivedStarExcludesConstantTailPosition() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE t (c1 INT, c2 INT, c3 INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t SELECT q.* FROM ("
                        + "SELECT *, 42 AS tail FROM ("
                        + "SELECT * FROM missing UNION ALL SELECT 1) AS x"
                        + " CROSS JOIN (SELECT 2) AS y) AS q;"
                        + " SELECT c3 FROM t;");

        assertEquals(0, result.getFailures().size());
        assertFalse(result.getGraph().getSources(new Column("t.c3"))
                .contains(new Column("missing.*")));
        assertTrue(result.getGraph().getSources(new Column("t.c1"))
                .contains(new Column("missing.*")));
    }

    @Test
    public void outerUnionWidthExtendsDerivedOpenWildcard() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE t (c1 INT, c2 INT, c3 INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t(c1, c2) SELECT * FROM (SELECT * FROM missing) AS q"
                        + " UNION ALL SELECT 1, 2;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("missing.*"))),
                result.getGraph().getSources(new Column("t.c1")));
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("missing.*"))),
                result.getGraph().getSources(new Column("t.c2")));
    }

    @Test
    public void multiLayerDerivedStarKeepsPerSourcePositions() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE t (c1 INT, c2 INT, c3 INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t(c1, c2) SELECT q.* FROM (SELECT x.*, y.* FROM ("
                        + "SELECT * FROM m1 UNION ALL SELECT 1) AS x"
                        + " CROSS JOIN (SELECT * FROM m2 UNION ALL SELECT 2) AS y"
                        + ") AS q;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("m1.*"))),
                result.getGraph().getSources(new Column("t.c1")));
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("m2.*"))),
                result.getGraph().getSources(new Column("t.c2")));
    }

    @Test
    public void qualifiedStarAdvancesByItsOwnWidth() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE t (c1 INT, c2 INT, c3 INT);"
                + " CREATE TABLE b (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t(c1, c2, c3) SELECT q.*, b.* FROM ("
                        + "SELECT m1.*, 42 AS tail FROM m1"
                        + " UNION ALL SELECT 1, 2) AS q CROSS JOIN b;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("m1.*"))),
                result.getGraph().getSources(new Column("t.c1")));
        assertFalse(result.getGraph().getSources(new Column("t.c2"))
                .contains(new Column("m1.*")));
        // q has a known width, so b's position is certain and catalog metadata
        // expands b.* into its explicit column.
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("b.k"))),
                result.getGraph().getSources(new Column("t.c3")));
    }

    @Test
    public void joinPlanSkipsDerivedConstantPositions() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE t (c1 INT, c2 INT, c3 INT);"
                + " CREATE TABLE b (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t(c1, c2, c3) SELECT * FROM ("
                        + "SELECT m1.*, 42 AS tail FROM m1"
                        + " UNION ALL SELECT 1, 2) AS q CROSS JOIN b;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("m1.*"))),
                result.getGraph().getSources(new Column("t.c1")));
        assertFalse(result.getGraph().getSources(new Column("t.c2"))
                .contains(new Column("m1.*")));
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("b.*"))),
                result.getGraph().getSources(new Column("t.c3")));
    }

    @Test
    public void overlappingUnionSourcesShareDerivedPosition() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE t (c1 INT, c2 INT, c3 INT);"
                + " CREATE TABLE b (k INT, w INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t(c1, c2, c3) SELECT * FROM ("
                        + "SELECT * FROM m1 UNION ALL SELECT * FROM m2"
                        + " UNION ALL SELECT 1) AS q CROSS JOIN b;");

        assertEquals(0, result.getFailures().size());
        Set<Column> sources = result.getGraph().getSources(new Column("t.c1"));
        assertTrue(sources.contains(new Column("m1.*")));
        assertTrue(sources.contains(new Column("m2.*")));
    }

    @Test
    public void joinUsingMergedKeyDoesNotShiftDerivedWildcard() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE t (c1 INT, c2 INT, c3 INT);"
                + " CREATE TABLE b (k INT, w INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t(c1, c2, c3) SELECT * FROM ("
                        + "SELECT m1.*, 0 AS k FROM m1"
                        + " UNION ALL SELECT 2, 3 AS k) AS q"
                        + " LEFT JOIN b USING(k);");

        assertEquals(0, result.getFailures().size());
        assertFalse(result.getGraph().getSources(new Column("t.c1"))
                .contains(new Column("m1.*")));
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("m1.*"))),
                result.getGraph().getSources(new Column("t.c2")));
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("b.*"))),
                result.getGraph().getSources(new Column("t.c3")));
    }

    @Test
    public void nestedDerivedOpenWildcardSurvivesIntermediateProjection() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE t (c1 INT, c2 INT, c3 INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t(c1, c2) SELECT * FROM ("
                        + "SELECT * FROM (SELECT * FROM missing) AS q) AS r"
                        + " UNION ALL SELECT 1, 2;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("missing.*"))),
                result.getGraph().getSources(new Column("t.c1")));
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("missing.*"))),
                result.getGraph().getSources(new Column("t.c2")));
    }

    @Test
    public void duplicateUnionNamesDoNotLeakIntoConstantPosition() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE t (c1 INT, c2 INT, c3 INT);"
                + " CREATE TABLE b (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t(c1, c2, c3) SELECT * FROM ("
                        + "SELECT m1.*, 0 AS k FROM m1"
                        + " UNION ALL SELECT 1 AS k, 2 AS k) AS q CROSS JOIN b;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("m1.*"))),
                result.getGraph().getSources(new Column("t.c1")));
        assertFalse(result.getGraph().getSources(new Column("t.c2"))
                .contains(new Column("m1.*")));
        assertTrue(result.getGraph().getSources(new Column("t.c3"))
                .contains(new Column("b.*")));
    }

    @Test
    public void ambiguousUsingMergeKeepsTableLevelSources() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE t (c1 INT, c2 INT, c3 INT);"
                + " CREATE TABLE b (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t(c1, c2) SELECT * FROM (SELECT x.*, y.* FROM ("
                        + "SELECT * FROM m1 UNION ALL SELECT 1) AS x"
                        + " CROSS JOIN (SELECT * FROM m2 UNION ALL SELECT 2) AS y"
                        + " UNION ALL SELECT 3, 4) AS q LEFT JOIN b USING(k);");

        assertEquals(0, result.getFailures().size());
        Set<Column> first = result.getGraph().getSources(new Column("t.c1"));
        Set<Column> second = result.getGraph().getSources(new Column("t.c2"));
        // Which side owns the USING key is unknown, so both written positions keep the
        // same table-level contributions instead of a per-position guess.
        assertEquals(first, second);
        assertTrue(first.contains(new Column("m1.*")));
        assertTrue(first.contains(new Column("m2.*")));
        assertTrue(first.contains(new Column("b.*")));
    }

    @Test
    public void openWildcardBeforeQualifiedStarStaysTableLevel() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE t (c1 INT, c2 INT, c3 INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t(c1, c2, c3) SELECT a.*, q.* FROM a"
                        + " CROSS JOIN (SELECT * FROM m UNION ALL SELECT 1) AS q;");

        assertEquals(0, result.getFailures().size());
        // The estimated start position must not pin m.* to one column: the table-level
        // contribution covers the remaining written positions.
        assertTrue(result.getGraph().getSources(new Column("t.c2"))
                .contains(new Column("m.*")));
        assertTrue(result.getGraph().getSources(new Column("t.c3"))
                .contains(new Column("m.*")));
    }

    @Test
    public void duplicateNamesAfterUsingStillKeepTableLevelEdge() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT * FROM (SELECT m.*, 0 AS k, 1 AS v FROM m"
                        + " UNION ALL SELECT 2 AS v, 3 AS k, 4 AS v) AS q"
                        + " LEFT JOIN b USING(k);");

        assertEquals(0, result.getFailures().size());
        assertTrue(result.getGraph().getSources(
                Column.temporary("stmt0.temp0.*"))
                .contains(new Column("m.*")));
    }

    @Test
    public void ambiguousUsingKeepsTrailingProjectionPosition() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE t (c1 INT, c2 INT, c3 INT, c4 INT);"
                + " CREATE TABLE b (k INT, w INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t(c1, c2, c3, c4) SELECT *, b.w FROM ("
                        + "SELECT * FROM m UNION ALL SELECT 1, 2) AS q"
                        + " LEFT JOIN b USING(k);");

        assertEquals(0, result.getFailures().size());
        assertTrue(result.getGraph().getSources(new Column("t.c4"))
                .contains(new Column("b.w")));
        assertFalse(result.getGraph().getSources(new Column("t.c3"))
                .contains(new Column("b.w")));
    }

    @Test
    public void physicalStarAfterOpenWildcardStaysTableLevel() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE t (c1 INT, c2 INT, c3 INT, c4 INT);"
                + " CREATE TABLE b (k INT, w INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t(c1, c2, c3, c4) SELECT a.*, b.*"
                        + " FROM a CROSS JOIN b;");

        assertEquals(0, result.getFailures().size());
        // a has no catalog entry, so b's start is unknown: its contribution stays a
        // table-level edge covering the remaining written positions.
        assertTrue(result.getGraph().getSources(new Column("t.c2"))
                .contains(new Column("b.*")));
        assertTrue(result.getGraph().getSources(new Column("t.c4"))
                .contains(new Column("b.*")));
    }

    @Test
    public void ambiguousStarStopsAtKnownProjectionWidth() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE t (c1 INT, c2 INT, c3 INT);"
                + " CREATE TABLE b (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t(c1,c2,c3) SELECT *, 42 AS tail FROM ("
                        + "SELECT * FROM m UNION ALL SELECT 1,2) AS q"
                        + " LEFT JOIN b USING(k);");

        assertEquals(0, result.getFailures().size());
        assertTrue(result.getGraph().getSources(new Column("t.c2"))
                .contains(new Column("b.*")));
        // The star occupies exactly two columns; the constant tail must not inherit
        // its table-level sources.
        assertFalse(result.getGraph().getSources(new Column("t.c3"))
                .contains(new Column("b.*")));
        assertFalse(result.getGraph().getSources(new Column("t.c3"))
                .contains(new Column("q.*")));
    }

    @Test
    public void nestedUsingKeepsTrailingProjectionPosition() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE t (c1 INT, c2 INT, c3 INT);"
                + " CREATE TABLE b (k INT); CREATE TABLE c (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t(c1,c2,c3) SELECT *, b.k FROM (("
                        + "SELECT * FROM m UNION ALL SELECT 1,2) AS q"
                        + " LEFT JOIN b USING(k)) LEFT JOIN c USING(k);");

        assertEquals(0, result.getFailures().size());
        assertTrue(result.getGraph().getSources(new Column("t.c3"))
                .contains(new Column("b.k")));
    }

    @Test
    public void joinAliasStarAfterOpenWildcardStaysTableLevel() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE t (c1 INT, c2 INT, c3 INT, c4 INT);"
                + " CREATE TABLE b (k INT); CREATE TABLE c (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t(c1,c2,c3,c4) SELECT a.*, j.*"
                        + " FROM a CROSS JOIN (b CROSS JOIN c) AS j;");

        assertEquals(0, result.getFailures().size());
        // a has no catalog entry, so the join alias's start is unknown; its sources
        // stay table-level instead of being pinned to the estimated position.
        assertTrue(result.getGraph().getSources(new Column("t.c3"))
                .contains(new Column("b.*")));
        assertTrue(result.getGraph().getSources(new Column("t.c4"))
                .contains(new Column("c.*")));
    }

    @Test
    public void joinAliasStarAfterOpenWildcardInSubquery() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE t (c1 INT, c2 INT, c3 INT, c4 INT);"
                + " CREATE TABLE b (k INT); CREATE TABLE c (v INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t(c1,c2,c3,c4) SELECT a.*, j.* FROM a CROSS JOIN (("
                        + "SELECT * FROM m UNION ALL SELECT 1,2) AS q"
                        + " LEFT JOIN b USING(k)) AS j;");

        assertEquals(0, result.getFailures().size());
        // a has no catalog entry, so j's start is unknown; its ambiguous USING
        // merge must stay table level through the last written column.
        Set<Column> last = result.getGraph().getSources(new Column("t.c4"));
        assertTrue(last.contains(new Column("m.*")));
        assertTrue(last.contains(new Column("b.*")));
    }

    @Test
    public void derivedStarWithUnknownNamesUsesKnownWidth() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE t (c1 INT, c2 INT, c3 INT, c4 INT);"
                + " CREATE TABLE b (k INT); CREATE TABLE c (v INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t(c1,c2,c3,c4) SELECT r.*, c.* FROM ("
                        + "SELECT q.*, 42 AS tail FROM ("
                        + "SELECT * FROM m UNION ALL SELECT 1,2) AS q) AS r"
                        + " CROSS JOIN c;");

        assertEquals(0, result.getFailures().size());
        // r is three columns wide even though its third name is a constant; c.v
        // starts at the fourth position, not the third.
        assertTrue(result.getGraph().getSources(new Column("t.c4"))
                .contains(new Column("c.v")));
        assertFalse(result.getGraph().getSources(new Column("t.c3"))
                .contains(new Column("c.v")));
        assertFalse(result.getGraph().getSources(new Column("t.c3"))
                .contains(new Column("m.*")));
    }

    @Test
    public void unionBudgetAppliesOnlyToItsWildcard() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE t (c1 INT, c2 INT, c3 INT, c4 INT);"
                + " CREATE TABLE b (k INT); CREATE TABLE c (v INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t SELECT a.*, b.* FROM a CROSS JOIN b"
                        + " UNION ALL SELECT 1,2,3; SELECT c4 FROM t;");

        assertEquals(0, result.getFailures().size());
        // The UNION width pins a to two columns; b's single column lands on c3 and
        // must not stretch over the unwritten fourth position.
        assertTrue(result.getGraph().getSources(new Column("t.c3"))
                .contains(new Column("b.k")));
        assertFalse(result.getGraph().getSources(new Column("t.c4"))
                .contains(new Column("b.k")));
    }

    @Test
    public void multiSourceStarInsideUnionStaysWithinUnionWidth() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE t (c1 INT, c2 INT, c3 INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t SELECT * FROM a CROSS JOIN m"
                        + " UNION ALL SELECT 1,2; SELECT c3 FROM t;");

        assertEquals(0, result.getFailures().size());
        Set<Column> first = result.getGraph().getSources(new Column("t.c1"));
        assertTrue(first.contains(new Column("a.*")));
        assertTrue(first.contains(new Column("m.*")));
        // The union pins the star to two columns; the third target column keeps its
        // default and must not inherit those wildcards.
        assertFalse(result.getGraph().getSources(new Column("t.c3"))
                .contains(new Column("a.*")));
        assertFalse(result.getGraph().getSources(new Column("t.c3"))
                .contains(new Column("m.*")));
    }

    @Test
    public void usingJoinStarMatchesSingleColumnUnion() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT * FROM a JOIN b USING(k) UNION ALL SELECT 1;");

        assertEquals(0, result.getFailures().size());
        Set<Column> sources = result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.*"));
        assertTrue(sources.contains(new Column("a.*")));
        assertTrue(sources.contains(new Column("b.*")));
    }

    @Test
    public void derivedStarWithNoNamesKeepsKnownWidth() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE t (c1 INT, c2 INT, c3 INT);"
                + " CREATE TABLE b (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t(c1,c2,c3) SELECT * FROM ("
                        + "SELECT q.* FROM (SELECT * FROM m UNION ALL SELECT 1,2) AS q"
                        + ") AS r CROSS JOIN b;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("m.*"))),
                result.getGraph().getSources(new Column("t.c1")));
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("m.*"))),
                result.getGraph().getSources(new Column("t.c2")));
        assertTrue(result.getGraph().getSources(new Column("t.c3"))
                .contains(new Column("b.*")));
        assertFalse(result.getGraph().getSources(new Column("t.c3"))
                .contains(new Column("m.*")));
    }

    @Test
    public void derivedStarWithPartialNamesKeepsFullWidth() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE t (c1 INT, c2 INT, c3 INT, c4 INT);"
                + " CREATE TABLE b (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t(c1,c2,c3,c4) SELECT * FROM ("
                        + "SELECT 42 AS head, q.* FROM ("
                        + "SELECT * FROM m UNION ALL SELECT 1,2) AS q) AS r"
                        + " CROSS JOIN b;");

        assertEquals(0, result.getFailures().size());
        // r is three columns wide (head plus q's two), even though only `head`
        // was named.
        assertFalse(result.getGraph().getSources(new Column("t.c1"))
                .contains(new Column("m.*")));
        assertTrue(result.getGraph().getSources(new Column("t.c2"))
                .contains(new Column("m.*")));
        assertTrue(result.getGraph().getSources(new Column("t.c3"))
                .contains(new Column("m.*")));
        assertTrue(result.getGraph().getSources(new Column("t.c4"))
                .contains(new Column("b.*")));
    }

    @Test
    public void cteDerivedStarKeepsKnownWidth() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "WITH q AS (SELECT * FROM m UNION ALL SELECT 1,2),"
                        + " r AS (SELECT q.* FROM q)"
                        + " SELECT * FROM r CROSS JOIN b UNION ALL SELECT 3,4,5;");

        assertEquals(0, result.getFailures().size());
        Set<Column> sources = result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.*"));
        assertTrue(sources.contains(new Column("m.*")));
        assertTrue(sources.contains(new Column("b.*")));
    }

    @Test
    public void ctePartialNamesWidthDrivesUnionPrecheck() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "WITH q AS (SELECT * FROM m UNION ALL SELECT 1, 2),"
                        + " r AS (SELECT 42 AS head, q.* FROM q)"
                        + " SELECT * FROM n UNION ALL SELECT * FROM r;");

        assertEquals(0, result.getFailures().size());
        assertEquals(1, result.getResults().size());
        Set<Column> sources = result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.*"));
        assertTrue(sources.contains(new Column("n.*")));
        assertTrue(sources.contains(new Column("m.*")));
    }

    @Test
    public void knownBranchWidthBoundsEarlierStar() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE t (c1 INT, c2 INT);"
                + " CREATE TABLE b (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t SELECT * FROM m UNION ALL SELECT * FROM b;"
                        + " SELECT c2 FROM t;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("b.k"))),
                result.getGraph().getSources(new Column("t.c1")));
        // The union is one column wide; the second target column keeps its default.
        assertFalse(result.getGraph().getSources(new Column("t.c2"))
                .contains(new Column("m.*")));
    }

    @Test
    public void schemaQualifiedBranchWidthBoundsEarlierStar() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE t (c1 INT, c2 INT);"
                + " CREATE TABLE s.b (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t SELECT * FROM m UNION ALL SELECT * FROM s.b;"
                        + " SELECT c2 FROM t;");

        assertEquals(0, result.getFailures().size());
        assertTrue(result.getGraph().getSources(new Column("t.c1"))
                .contains(new Column("s.b.k")));
        assertFalse(result.getGraph().getSources(new Column("t.c2"))
                .contains(new Column("m.*")));
    }

    @Test
    public void qualifiedStarWidthUsesTheMatchingSchema() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE s1.b (k INT, w INT);"
                + " CREATE TABLE s2.b (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT * FROM m UNION ALL"
                        + " SELECT s2.b.* FROM s1.b CROSS JOIN s2.b;");

        assertEquals(0, result.getFailures().size());
        assertTrue(result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.*"))
                .contains(new Column("m.*")));
        assertEquals(new HashSet<>(java.util.Arrays.asList(
                new Column("s2.b.k"))),
                result.getGraph().getSources(
                        Column.temporary("stmt0.temp0.k")));
    }

    @Test
    public void quotedQualifiedStarMatchesBoundTable() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE t (c1 INT, c2 INT);"
                + " CREATE TABLE s.b (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t SELECT * FROM m UNION ALL"
                        + " SELECT \"s\".\"b\".* FROM s.b; SELECT c2 FROM t;");

        assertEquals(0, result.getFailures().size());
        assertTrue(result.getGraph().getSources(new Column("t.c1"))
                .contains(new Column("s.b.k")));
        assertFalse(result.getGraph().getSources(new Column("t.c2"))
                .contains(new Column("m.*")));
    }

    @Test
    public void searchPathQualifiedStarMatchesBoundTable() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE t (c1 INT, c2 INT);"
                + " CREATE TABLE s.b (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SET search_path TO s, public;"
                        + " INSERT INTO t SELECT * FROM m UNION ALL"
                        + " SELECT s.b.* FROM b; SELECT c2 FROM t;");

        assertEquals(0, result.getFailures().size());
        assertTrue(result.getGraph().getSources(new Column("t.c1"))
                .contains(new Column("s.b.k")));
        assertFalse(result.getGraph().getSources(new Column("t.c2"))
                .contains(new Column("m.*")));
    }

    @Test
    public void aliasedCteDoesNotShadowQualifiedTable() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE s.b (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "WITH b AS (SELECT 1, 2)"
                        + " SELECT * FROM m UNION ALL"
                        + " SELECT s.b.* FROM b AS x CROSS JOIN s.b;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(
                new Column("s.b.k"))),
                result.getGraph().getSources(
                        Column.temporary("stmt0.temp0.k")));
    }

    @Test
    public void quotedUppercaseQualifiedStarMatchesWrittenName() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE t (c1 INT, c2 INT);"
                + " CREATE TABLE s.\"B\" (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t SELECT * FROM m UNION ALL"
                        + " SELECT s.\"B\".* FROM s.\"B\"; SELECT c2 FROM t;");

        assertEquals(0, result.getFailures().size());
        assertTrue(result.getGraph().getSources(new Column("t.c1"))
                .contains(new Column("s.B.k")));
        assertFalse(result.getGraph().getSources(new Column("t.c2"))
                .contains(new Column("m.*")));
    }

    @Test
    public void cteLeafDoesNotTriggerCatalogAmbiguityInPrepass() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE s1.x (v INT);"
                + " CREATE TABLE s2.x (v INT); CREATE TABLE s.b (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "WITH x AS (SELECT 0)"
                        + " SELECT 1 UNION ALL"
                        + " SELECT s.b.* FROM x CROSS JOIN s.b;");

        assertEquals(0, result.getFailures().size());
        // The union takes its output name from the first branch, so the physical
        // column lands on that position.
        assertTrue(result.getGraph().getSources(
                Column.temporary("stmt0.temp0.\"1\""))
                .contains(new Column("s.b.k")));
    }

    @Test
    public void nestedCteLeafDoesNotTriggerCatalogAmbiguityInPrepass() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE s1.x (v INT);"
                + " CREATE TABLE s2.x (v INT); CREATE TABLE s.b (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT 1 UNION ALL SELECT * FROM ("
                        + "WITH x AS (SELECT 0)"
                        + " SELECT s.b.* FROM x CROSS JOIN s.b) AS q;");

        assertEquals(0, result.getFailures().size());
        assertTrue(result.getGraph().getSources(
                Column.temporary("stmt0.temp0.\"1\""))
                .contains(new Column("s.b.k")));
    }

    @Test
    public void recursiveCteSelfReferenceResolvesInPrepass() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE s1.x (v INT);"
                + " CREATE TABLE s2.x (v INT); CREATE TABLE s.b (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT 1 UNION ALL SELECT * FROM ("
                        + "WITH RECURSIVE x(k) AS (SELECT 0 UNION ALL"
                        + " SELECT s.b.* FROM x CROSS JOIN s.b WHERE FALSE)"
                        + " SELECT * FROM x) AS q;");

        assertEquals(0, result.getFailures().size());
        assertTrue(result.getGraph().getSources(
                Column.temporary("stmt0.temp0.\"1\""))
                .contains(new Column("s.b.k")));
    }

    @Test
    public void recursiveCteForwardReferenceResolvesInPrepass() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE s1.x (v INT);"
                + " CREATE TABLE s2.x (v INT); CREATE TABLE s.b (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT 1 UNION ALL SELECT * FROM ("
                        + "WITH RECURSIVE y AS (SELECT s.b.* FROM x CROSS JOIN s.b),"
                        + " x AS (SELECT 0)"
                        + " SELECT * FROM y) AS q;");

        assertEquals(0, result.getFailures().size());
        assertTrue(result.getGraph().getSources(
                Column.temporary("stmt0.temp0.\"1\""))
                .contains(new Column("s.b.k")));
    }

    @Test
    public void recursiveCteForwardColumnInfoIsVisible() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE a (v INT); CREATE TABLE b (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "WITH RECURSIVE y AS (SELECT k FROM x CROSS JOIN b),"
                        + " x AS (SELECT v FROM a)"
                        + " SELECT * FROM y;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("b.k"))),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.k")));
    }

    @Test
    public void recursiveCteForwardWidthBoundsEarlierStar() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE t (c1 INT, c2 INT);"
                + " CREATE TABLE b (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t SELECT * FROM m UNION ALL SELECT * FROM ("
                        + "WITH RECURSIVE y AS (SELECT * FROM x),"
                        + " x AS (SELECT k FROM b) SELECT * FROM y) AS q;"
                        + " SELECT c2 FROM t;");

        assertEquals(0, result.getFailures().size());
        assertTrue(result.getGraph().getSources(new Column("t.c1"))
                .contains(new Column("b.k")));
        assertFalse(result.getGraph().getSources(new Column("t.c2"))
                .contains(new Column("m.*")));
    }

    @Test
    public void recursiveCteUsingKeepsMergedOrder() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (v INT, k INT);"
                + " CREATE TABLE c (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "WITH RECURSIVE x AS (SELECT * FROM b JOIN c USING(k))"
                        + " SELECT * FROM x;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(
                new Column("b.k"), new Column("c.k"))),
                result.getGraph().getSources(
                        Column.temporary("stmt0.temp0.k")));
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("b.v"))),
                result.getGraph().getSources(
                        Column.temporary("stmt0.temp0.v")));
    }

    @Test
    public void recursiveCteDeclaredColumnsOverrideStaticNames() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "WITH RECURSIVE y AS (SELECT x.v FROM x), x(v) AS (SELECT k FROM b)"
                        + " SELECT * FROM y;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("b.k"))),
                result.getGraph().getSources(
                        Column.temporary("stmt0.temp0.v")));
    }

    @Test
    public void recursiveCteQuotedForwardColumnKeepsIdentity() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "WITH RECURSIVE y AS (SELECT x.\"K\" FROM x),"
                        + " x AS (SELECT k AS \"K\" FROM b) SELECT * FROM y;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("b.k"))),
                result.getGraph().getSources(
                        Column.temporary("stmt0.temp0.K")));
    }

    @Test
    public void recursiveCteConstantPositionKeepsKnownNames() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "WITH RECURSIVE y AS (SELECT k FROM x CROSS JOIN b),"
                        + " x AS (SELECT 1 AS v, 2) SELECT * FROM y;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("b.k"))),
                result.getGraph().getSources(
                        Column.temporary("stmt0.temp0.k")));
    }

    @Test
    public void recursiveCteNestedWithNamesResolveLocally() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (v INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "WITH RECURSIVE y AS (SELECT x.k FROM x),"
                        + " x AS (WITH b AS (SELECT 1 AS k) SELECT * FROM b)"
                        + " SELECT * FROM y;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(
                Column.temporary("stmt0.b.k"))),
                result.getGraph().getSources(
                        Column.temporary("stmt0.temp0.k")));
    }

    @Test
    public void threeLayerRecursiveForwardWidthBoundsEarlierStar() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE t (c1 INT, c2 INT);"
                + " CREATE TABLE b (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t SELECT * FROM m UNION ALL SELECT * FROM ("
                        + "WITH RECURSIVE y AS (SELECT * FROM x),"
                        + " x AS (SELECT * FROM z),"
                        + " z AS (SELECT k FROM b) SELECT * FROM y) AS q;"
                        + " SELECT c2 FROM t;");

        assertEquals(0, result.getFailures().size());
        assertTrue(result.getGraph().getSources(new Column("t.c1"))
                .contains(new Column("b.k")));
        assertFalse(result.getGraph().getSources(new Column("t.c2"))
                .contains(new Column("m.*")));
    }

    @Test
    public void recursiveCtePartialDeclaredColumnsKeepRemainingNames() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT, w INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "WITH RECURSIVE y AS (SELECT x.w FROM x),"
                        + " x(v) AS (SELECT k, w FROM b) SELECT * FROM y;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("b.w"))),
                result.getGraph().getSources(
                        Column.temporary("stmt0.temp0.w")));
    }

    @Test
    public void recursiveCteQuotedUsingMergesOnce() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (\"K\" INT);"
                + " CREATE TABLE c (\"K\" INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "WITH RECURSIVE y AS (SELECT * FROM x UNION ALL SELECT 1),"
                        + " x AS (SELECT * FROM b JOIN c USING(\"K\"))"
                        + " SELECT * FROM y;");

        assertEquals(0, result.getFailures().size());
        Set<Column> sources = result.getGraph().getSources(
                Column.temporary("stmt0.temp0.K"));
        assertTrue(sources.contains(new Column("b.K")));
        assertTrue(sources.contains(new Column("c.K")));
    }

    @Test
    public void recursiveCteAliasContainingAsKeywordResolves() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "WITH RECURSIVE y AS (SELECT x.\"a AS b\" FROM x),"
                        + " x AS (SELECT k AS \"a AS b\" FROM b) SELECT * FROM y;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("b.k"))),
                result.getGraph().getSources(
                        Column.temporary("stmt0.x.\"a AS b\"")));
    }

    @Test
    public void nestedNonRecursiveCteKeepsLocalVisibilityOrder() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT); CREATE TABLE c (v INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "WITH RECURSIVE out AS (SELECT x.k FROM x),"
                        + " x AS (WITH y AS (SELECT * FROM b), b AS (SELECT v FROM c)"
                        + " SELECT * FROM y) SELECT * FROM out;");

        assertEquals(0, result.getFailures().size());
        // `y` is defined before the local `b`, so it binds to the physical table.
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("b.k"))),
                result.getGraph().getSources(
                        Column.temporary("stmt0.temp0.k")));
    }

    @Test
    public void recursiveCteConstantNameDoesNotShadowExplicitAlias() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "WITH RECURSIVE x AS ("
                        + "SELECT 1, k AS col1 FROM b"
                        + " UNION ALL SELECT 2, k FROM b)"
                        + " SELECT x.col1 FROM x;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("b.k"))),
                result.getGraph().getSources(
                        Column.temporary("stmt0.x.col1")));
    }

    @Test
    public void nestedNonRecursiveCteSeesBothLocalEntries() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (v INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "WITH RECURSIVE y AS (SELECT x.k FROM x),"
                        + " x AS (WITH a AS (SELECT 0), b AS (SELECT 1 AS k)"
                        + " SELECT * FROM b) SELECT * FROM y;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(
                Column.temporary("stmt0.b.k"))),
                result.getGraph().getSources(
                        Column.temporary("stmt0.x.k")));
    }

    @Test
    public void partialDeclaredColumnsPadToKnownUnionWidth() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "WITH RECURSIVE y AS (SELECT * FROM x UNION ALL SELECT 3, 4),"
                        + " x(v) AS (SELECT * FROM m UNION ALL SELECT 1, 2)"
                        + " SELECT * FROM y;");

        assertEquals(0, result.getFailures().size());
        // The padded position has no known name, so the outer star keeps a table-level
        // claim instead of inventing a column mapping.
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("m.*"))),
                result.getGraph().getSources(
                        Column.temporary("stmt0.temp0.*")));
    }

    @Test
    public void recursiveCteAliasWithEscapedQuotesResolves() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "WITH RECURSIVE y AS (SELECT x.\"a\"\"b\" FROM x),"
                        + " x AS (SELECT k AS \"a\"\"b\" FROM b) SELECT * FROM y;");

        assertEquals(0, result.getFailures().size());
    }

    @Test
    public void recursiveCteDirectQuotedColumnKeepsKeyIdentity() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (\"K\" INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "WITH RECURSIVE y AS (SELECT x.\"K\" FROM x),"
                        + " x AS (SELECT \"K\" FROM b) SELECT * FROM y;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("b.K"))),
                result.getGraph().getSources(
                        Column.temporary("stmt0.temp0.K")));
    }

    @Test
    public void paddedDeclaredPositionKeepsTableLevelProvenance() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "WITH RECURSIVE y AS (SELECT * FROM x UNION ALL SELECT 3, 4),"
                        + " x(v) AS (SELECT * FROM m UNION ALL SELECT 1, 2)"
                        + " SELECT * FROM y;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("m.*"))),
                result.getGraph().getSources(
                        Column.temporary("stmt0.temp0.*")));
    }

    @Test
    public void paddedWidthUsesSiblingCteScope() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "WITH RECURSIVE y AS (SELECT * FROM x UNION ALL SELECT 3, 4),"
                        + " x(v) AS (SELECT * FROM m UNION ALL SELECT * FROM z),"
                        + " z AS (SELECT 1, 2) SELECT * FROM y;");

        assertEquals(0, result.getFailures().size());
        assertTrue(result.getGraph().getSources(
                Column.temporary("stmt0.temp0.*"))
                .contains(new Column("m.*")));
    }

    @Test
    public void unknownLocalCteShadowsPhysicalTableInStaticNames() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE t (v INT); CREATE TABLE b (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "WITH RECURSIVE y AS (SELECT k FROM x CROSS JOIN b),"
                        + " x AS (WITH t AS (SELECT * FROM m) SELECT * FROM t)"
                        + " SELECT * FROM y;");

        assertEquals(1, result.getResults().size());
        assertTrue(result.getResults().get(0).getError()
                instanceof AmbiguousColumnException);
    }

    @Test
    public void escapedAliasSurvivesOuterStarProjection() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "WITH RECURSIVE y AS (SELECT x.\"a\"\"b\" FROM x),"
                        + " x AS (SELECT k AS \"a\"\"b\" FROM b)"
                        + " SELECT * FROM y;");

        assertEquals(0, result.getFailures().size());
        Column output = null;
        for (Column vertex : result.getGraph().getReverseGraph().vertexSet()) {
            if (vertex.isTemporary() && "stmt0.temp0".equals(vertex.getTableName())
                    && "a\"b".equals(vertex.getColumnName())) {
                output = vertex;
            }
        }
        assertNotNull(output);
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("b.k"))),
                result.getGraph().getSources(output));
    }

    @Test
    public void forwardCteUsingMergeDoesNotDoubleCountWidth() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (v INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "WITH RECURSIVE y AS ("
                        + "SELECT * FROM x JOIN b USING(v) UNION ALL SELECT 3, 4),"
                        + " x(v) AS (SELECT * FROM m UNION ALL SELECT 1, 2)"
                        + " SELECT * FROM y;");

        assertEquals(0, result.getFailures().size());
    }

    @Test
    public void forwardCteWildcardKeepsConstantPosition() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE t (c1 INT, c2 INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t(c1, c2) SELECT * FROM ("
                        + "WITH RECURSIVE y AS (SELECT * FROM x),"
                        + " x(v) AS (SELECT 42 AS fixed, m.* FROM m"
                        + " UNION ALL SELECT 43, 2) SELECT * FROM y) AS q;");

        assertEquals(0, result.getFailures().size());
        assertFalse(result.getGraph().getSources(new Column("t.c1"))
                .contains(new Column("m.*")));
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("m.*"))),
                result.getGraph().getSources(new Column("t.c2")));
    }

    @Test
    public void forwardCteTableLevelContributionReachesOuter() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "WITH RECURSIVE y AS (SELECT * FROM x),"
                        + " x(v) AS (SELECT * FROM m UNION ALL SELECT 1)"
                        + " SELECT * FROM y;");

        assertEquals(0, result.getFailures().size());
        assertTrue(result.getGraph().getSources(
                Column.temporary("stmt0.temp0.*"))
                .contains(new Column("m.*")));
    }

    @Test
    public void declaredColumnsWithoutKnownWidthKeepUnionWidth() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "WITH RECURSIVE y AS (SELECT * FROM x UNION ALL SELECT 3, 4),"
                        + " x(v) AS (SELECT * FROM m)"
                        + " SELECT * FROM y;");

        assertEquals(0, result.getFailures().size());
        assertTrue(result.getGraph().getSources(
                Column.temporary("stmt0.temp0.*"))
                .contains(new Column("m.*")));
    }

    @Test
    public void stringLiteralDoesNotFakeCteDependency() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "WITH RECURSIVE y AS (SELECT * FROM x),"
                        + " x(v) AS (SELECT * FROM m WHERE 'y' = 'y'"
                        + " UNION ALL SELECT 1) SELECT * FROM y;");

        assertEquals(0, result.getFailures().size());
        assertTrue(result.getGraph().getSources(
                Column.temporary("stmt0.temp0.*"))
                .contains(new Column("m.*")));
    }

    @Test
    public void escapedQuotedCteDependencyIsDetected() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "WITH RECURSIVE y AS (SELECT * FROM \"a\"\"b\"),"
                        + " \"a\"\"b\"(v) AS (SELECT * FROM m UNION ALL SELECT 1)"
                        + " SELECT * FROM y;");

        assertEquals(0, result.getFailures().size());
        assertTrue(result.getGraph().getSources(
                Column.temporary("stmt0.temp0.*"))
                .contains(new Column("m.*")));
    }

    @Test
    public void nestedLocalCteDoesNotFakeOuterDependency() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "WITH RECURSIVE y AS (SELECT * FROM x),"
                        + " x(v) AS (WITH y AS (SELECT 1 AS k)"
                        + " SELECT * FROM m UNION ALL SELECT k FROM y)"
                        + " SELECT * FROM y;");

        assertEquals(0, result.getFailures().size());
        assertTrue(result.getGraph().getSources(
                Column.temporary("stmt0.temp0.*"))
                .contains(new Column("m.*")));
    }

    @Test
    public void existsSubqueryCteShadowsPhysicalTable() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT);"
                + " CREATE TABLE s1.z (v INT); CREATE TABLE s2.z (v INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT b.k FROM b"
                        + " WHERE EXISTS (WITH z AS (SELECT 1) SELECT * FROM z);");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("b.k"))),
                result.getGraph().getSources(
                        Column.temporary("stmt0.temp0.k")));
    }

    @Test
    public void lateralSubqueryResolvesForwardCte() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "WITH RECURSIVE y AS ("
                        + "SELECT * FROM LATERAL (SELECT * FROM x) AS q),"
                        + " x(v) AS (SELECT * FROM m UNION ALL SELECT 1)"
                        + " SELECT * FROM y;");

        assertEquals(0, result.getFailures().size());
        assertTrue(result.getGraph().getSources(
                Column.temporary("stmt0.temp0.*"))
                .contains(new Column("m.*")));
    }

    @Test
    public void correlatedLateralReadsLeftSideColumns() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT q.k FROM b CROSS JOIN"
                        + " LATERAL (SELECT b.k AS k) AS q;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("b.k"))),
                result.getGraph().getSources(
                        Column.temporary("stmt0.temp0.k")));
    }

    @Test
    public void correlatedLateralValidatesMissingColumn() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT); CREATE TABLE c (v INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT q.k FROM b CROSS JOIN"
                        + " LATERAL (SELECT b.missing AS k) AS q;");

        assertEquals(1, result.getResults().size());
        assertTrue(result.getResults().get(0).getError()
                instanceof ColumnNotFoundException);
    }

    @Test
    public void correlatedLateralExpressionKeepsLineage() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT); CREATE TABLE c (v INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT q.k FROM b CROSS JOIN"
                        + " LATERAL (SELECT b.k + 1 AS k) AS q;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("b.k"))),
                result.getGraph().getSources(
                        Column.temporary("stmt0.temp0.k")));
    }

    @Test
    public void correlatedLateralRejectsUnknownOwner() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT); CREATE TABLE c (v INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT q.k FROM b CROSS JOIN"
                        + " LATERAL (SELECT bad.k + 1 AS k) AS q;");

        assertEquals(1, result.getResults().size());
        assertTrue(result.getResults().get(0).getError()
                instanceof MetadataNotFoundException);
    }

    @Test
    public void lateralWithOwnFromSeesLeftSources() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT); CREATE TABLE c (v INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT q.k FROM b CROSS JOIN"
                        + " LATERAL (SELECT b.k FROM c) AS q;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("b.k"))),
                result.getGraph().getSources(
                        Column.temporary("stmt0.temp0.k")));
    }

    @Test
    public void lateralSeesJoinAliasColumns() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT); CREATE TABLE c (v INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT q.k FROM (b CROSS JOIN c) AS j"
                        + " CROSS JOIN LATERAL (SELECT j.k) AS q;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("b.k"))),
                result.getGraph().getSources(
                        Column.temporary("stmt0.temp0.k")));
    }

    @Test
    public void lateralAncestorJoinAliasAndHidingApply() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT); CREATE TABLE c (v INT);"
                + " CREATE TABLE d (k INT);");
        ScanResult visible = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT q.k FROM (b CROSS JOIN c) AS j"
                        + " CROSS JOIN LATERAL (SELECT j.k FROM d) AS q;");
        assertEquals(0, visible.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("b.k"))),
                visible.getGraph().getSources(
                        Column.temporary("stmt0.temp0.k")));

        ScanResult hidden = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT q.k FROM (b CROSS JOIN c) AS j"
                        + " CROSS JOIN LATERAL (SELECT b.k FROM d) AS q;");
        assertTrue(hidden.getResults().get(0).getError()
                instanceof MetadataNotFoundException);
    }

    @Test
    public void lateralDuplicateJoinAliasColumnsAreAmbiguous() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT); CREATE TABLE c (v INT);"
                + " CREATE TABLE d (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT q.k FROM (b CROSS JOIN d) AS j"
                        + " CROSS JOIN LATERAL (SELECT j.k) AS q;");

        assertTrue(result.getResults().get(0).getError()
                instanceof AmbiguousColumnException);
    }

    @Test
    public void lateralInListKeepsLineageAndValidatesOwner() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT); CREATE TABLE c (v INT);"
                + " CREATE TABLE d (k INT);");
        ScanResult traced = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT q.ok FROM b CROSS JOIN"
                        + " LATERAL (SELECT b.k IN (1, 2) AS ok) AS q;");
        assertEquals(0, traced.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("b.k"))),
                traced.getGraph().getSources(
                        Column.temporary("stmt0.temp0.ok")));

        ScanResult unbound = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT q.ok FROM b CROSS JOIN"
                        + " LATERAL (SELECT bad.k IN (1, 2) AS ok) AS q;");
        assertTrue(unbound.getResults().get(0).getError()
                instanceof MetadataNotFoundException);
    }

    @Test
    public void lateralUnqualifiedCorrelatedColumnIsAmbiguous() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT); CREATE TABLE c (v INT);"
                + " CREATE TABLE d (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT q.k FROM b CROSS JOIN d"
                        + " CROSS JOIN LATERAL (SELECT k) AS q;");

        assertTrue(result.getResults().get(0).getError()
                instanceof AmbiguousColumnException);
    }

    @Test
    public void lateralUsingAliasKeepsMergedColumns() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT); CREATE TABLE c (v INT);"
                + " CREATE TABLE d (k INT);");
        ScanResult merged = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT q.k FROM (b JOIN d USING(k)) AS j"
                        + " CROSS JOIN LATERAL (SELECT j.k) AS q;");
        assertEquals(0, merged.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(
                new Column("b.k"), new Column("d.k"))),
                merged.getGraph().getSources(
                        Column.temporary("stmt0.temp0.k")));

        ScanResult left = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT q.k FROM (b LEFT JOIN d USING(k))"
                        + " CROSS JOIN LATERAL (SELECT k) AS q;");
        assertEquals(0, left.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("b.k"))),
                left.getGraph().getSources(
                        Column.temporary("stmt0.temp0.k")));
    }

    @Test
    public void lateralUnqualifiedUnknownSourceRules() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT); CREATE TABLE c (v INT);"
                + " CREATE TABLE d (k INT);");
        ScanResult mixed = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT q.k FROM b CROSS JOIN missing"
                        + " CROSS JOIN LATERAL (SELECT k) AS q;");
        assertTrue(mixed.getResults().get(0).getError()
                instanceof AmbiguousColumnException);

        ScanResult onlyUnknown = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT q.k FROM missing CROSS JOIN LATERAL (SELECT k) AS q;");
        assertEquals(0, onlyUnknown.getFailures().size());
        assertTrue(onlyUnknown.getGraph().getSources(
                Column.temporary("stmt0.temp0.*"))
                .contains(new Column("missing.*")));
    }

    @Test
    public void lateralQuotedColumnAndAncestorFallback() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT); CREATE TABLE c (v INT);"
                + " CREATE TABLE d (k INT); CREATE TABLE e (\"K\" INT);");
        ScanResult quoted = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT q.v FROM e CROSS JOIN"
                        + " LATERAL (SELECT \"K\" AS v) AS q;");
        assertEquals(0, quoted.getFailures().size());
        assertTrue(quoted.getGraph().getSources(
                Column.temporary("stmt0.temp0.v"))
                .contains(new Column("e.K")));

        ScanResult ancestor = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT q.k FROM b CROSS JOIN"
                        + " LATERAL (SELECT k FROM c) AS q;");
        assertEquals(0, ancestor.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("b.k"))),
                ancestor.getGraph().getSources(
                        Column.temporary("stmt0.temp0.k")));
    }

    @Test
    public void lateralBindingOwnerPrecedence() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT); CREATE TABLE c (v INT);"
                + " CREATE TABLE d (k INT);");
        ScanResult invalid = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT q.k FROM (b CROSS JOIN c) AS j"
                        + " CROSS JOIN LATERAL (SELECT s.j.k) AS q;");
        assertTrue(invalid.getResults().get(0).getError()
                instanceof MetadataNotFoundException);

        ScanResult nearest = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT q.k FROM d AS j CROSS JOIN LATERAL ("
                        + "SELECT r.k FROM (b CROSS JOIN c) AS j"
                        + " CROSS JOIN LATERAL (SELECT j.k) AS r) AS q;");
        assertEquals(0, nearest.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("b.k"))),
                nearest.getGraph().getSources(
                        Column.temporary("stmt0.temp0.k")));
    }

    @Test
    public void lateralInSubqueryKeepsLeftOperand() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT); CREATE TABLE c (v INT);"
                + " CREATE TABLE d (k INT);");
        ScanResult traced = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT q.ok FROM b CROSS JOIN"
                        + " LATERAL (SELECT b.k IN (SELECT 1) AS ok) AS q;");
        assertEquals(0, traced.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("b.k"))),
                traced.getGraph().getSources(
                        Column.temporary("stmt0.temp0.ok")));

        ScanResult unbound = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT q.ok FROM b CROSS JOIN"
                        + " LATERAL (SELECT bad.k IN (SELECT 1) AS ok) AS q;");
        assertTrue(unbound.getResults().get(0).getError()
                instanceof MetadataNotFoundException);
    }

    @Test
    public void lateralCorrelatedStarExpandsKnownAndKeepsUnknown() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT); CREATE TABLE c (v INT);"
                + " CREATE TABLE d (k INT);");
        ScanResult known = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT q.k FROM b CROSS JOIN LATERAL (SELECT b.*) AS q;");
        assertEquals(0, known.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("b.k"))),
                known.getGraph().getSources(
                        Column.temporary("stmt0.temp0.k")));

        ScanResult unknown = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT q.* FROM missing"
                        + " CROSS JOIN LATERAL (SELECT missing.*) AS q;");
        assertEquals(0, unknown.getFailures().size());
        assertTrue(unknown.getGraph().getSources(
                Column.temporary("stmt0.temp0.*"))
                .contains(new Column("missing.*")));
    }

    @Test
    public void lateralExplicitDerivedReferenceKeepsContribution() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT); CREATE TABLE c (v INT);"
                + " CREATE TABLE d (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "WITH x AS (SELECT k FROM b UNION ALL SELECT * FROM missing)"
                        + " SELECT q.k FROM x CROSS JOIN"
                        + " LATERAL (SELECT x.k) AS q;");

        assertEquals(0, result.getFailures().size());
        assertTrue(result.getGraph().getSources(
                Column.temporary("stmt0.temp0.*"))
                .contains(new Column("missing.*")));
    }

    @Test
    public void lateralUnknownWidthStarKeepsUnionWidth() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT); CREATE TABLE c (v INT);"
                + " CREATE TABLE d (k INT); CREATE TABLE e (\"K\" INT);"
                + " CREATE TABLE f (k INT); CREATE TABLE t (c1 INT, c2 INT, c3 INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT q.* FROM missing CROSS JOIN LATERAL ("
                        + "SELECT missing.* UNION ALL SELECT 1,2) AS q;");

        assertEquals(0, result.getFailures().size());
        assertTrue(result.getGraph().getSources(
                Column.temporary("stmt0.temp0.*"))
                .contains(new Column("missing.*")));
    }

    @Test
    public void lateralUnknownStarKeepsFollowingPositionUncertain() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT); CREATE TABLE c (v INT);"
                + " CREATE TABLE d (k INT); CREATE TABLE e (\"K\" INT);"
                + " CREATE TABLE f (k INT); CREATE TABLE t (c1 INT, c2 INT, c3 INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t(c1,c2,c3) SELECT q.* FROM"
                        + " (missing CROSS JOIN b) CROSS JOIN LATERAL"
                        + " (SELECT missing.*, b.k) AS q;");

        assertEquals(0, result.getFailures().size());
        assertFalse(result.getGraph().getSources(new Column("t.c2"))
                .contains(new Column("b.k")));
    }

    @Test
    public void lateralBindingStarKeepsUnknownSide() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT); CREATE TABLE c (v INT);"
                + " CREATE TABLE d (k INT); CREATE TABLE e (\"K\" INT);"
                + " CREATE TABLE f (k INT); CREATE TABLE t (c1 INT, c2 INT, c3 INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT q.* FROM (missing RIGHT JOIN b USING(k)) AS j"
                        + " CROSS JOIN LATERAL (SELECT j.*) AS q;");

        assertEquals(0, result.getFailures().size());
        Set<Column> sources = result.getGraph().getSources(
                Column.temporary("stmt0.temp0.*"));
        assertTrue(sources.contains(new Column("missing.*")));
        assertTrue(sources.contains(new Column("b.*")));
    }

    @Test
    public void lateralQualifiedStarWithOwnFromResolvesLeft() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT); CREATE TABLE c (v INT);"
                + " CREATE TABLE d (k INT); CREATE TABLE e (\"K\" INT);"
                + " CREATE TABLE f (k INT); CREATE TABLE t (c1 INT, c2 INT, c3 INT);");
        ScanResult table = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT q.k FROM b CROSS JOIN"
                        + " LATERAL (SELECT b.* FROM c) AS q;");
        assertEquals(0, table.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("b.k"))),
                table.getGraph().getSources(
                        Column.temporary("stmt0.temp0.k")));

        ScanResult alias = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT q.k FROM (b CROSS JOIN c) AS j"
                        + " CROSS JOIN LATERAL (SELECT j.* FROM d) AS q;");
        assertEquals(0, alias.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("b.k"))),
                alias.getGraph().getSources(
                        Column.temporary("stmt0.temp0.k")));
    }

    @Test
    public void lateralUnqualifiedAncestorAmbiguityMatchesNormal() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT); CREATE TABLE c (v INT);"
                + " CREATE TABLE d (k INT); CREATE TABLE e (\"K\" INT);"
                + " CREATE TABLE f (k INT); CREATE TABLE t (c1 INT, c2 INT, c3 INT);");
        ScanResult merged = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT q.k FROM (missing RIGHT JOIN b USING(k))"
                        + " CROSS JOIN LATERAL (SELECT k) AS q;");
        assertEquals(0, merged.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("b.k"))),
                merged.getGraph().getSources(
                        Column.temporary("stmt0.temp0.k")));

        ScanResult extra = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT q.k FROM ((b JOIN d USING(k)) CROSS JOIN f)"
                        + " CROSS JOIN LATERAL (SELECT k FROM c) AS q;");
        assertTrue(extra.getResults().get(0).getError()
                instanceof AmbiguousColumnException);

        ScanResult unknown = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT q.k FROM (b CROSS JOIN missing)"
                        + " CROSS JOIN LATERAL (SELECT k FROM c) AS q;");
        assertTrue(unknown.getResults().get(0).getError()
                instanceof AmbiguousColumnException);
    }

    @Test
    public void nestedLateralUnqualifiedFallsBackToAncestor() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT); CREATE TABLE c (v INT);"
                + " CREATE TABLE d (k INT); CREATE TABLE e (\"K\" INT);"
                + " CREATE TABLE f (k INT); CREATE TABLE t (c1 INT, c2 INT, c3 INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT q.k FROM b CROSS JOIN LATERAL ("
                        + "SELECT r.k FROM c CROSS JOIN"
                        + " LATERAL (SELECT k) AS r) AS q;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("b.k"))),
                result.getGraph().getSources(
                        Column.temporary("stmt0.temp0.k")));
    }

    @Test
    public void lateralStarOutputKeepsQuotedAndDeclaredIdentity() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT); CREATE TABLE c (v INT);"
                + " CREATE TABLE d (k INT); CREATE TABLE e (\"K\" INT);"
                + " CREATE TABLE f (k INT); CREATE TABLE t (c1 INT, c2 INT, c3 INT);");
        ScanResult quoted = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT q.\"K\" FROM e CROSS JOIN"
                        + " LATERAL (SELECT e.*) AS q;");
        assertEquals(0, quoted.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("e.K"))),
                quoted.getGraph().getSources(
                        Column.temporary("stmt0.temp0.K")));

        ScanResult unquoted = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT q.k FROM e CROSS JOIN LATERAL (SELECT e.*) AS q;");
        assertTrue(unquoted.getResults().get(0).getError()
                instanceof ColumnNotFoundException);

        ScanResult renamed = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT q.renamed FROM b CROSS JOIN"
                        + " LATERAL (SELECT b.*) AS q(renamed);");
        assertEquals(0, renamed.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("b.k"))),
                renamed.getGraph().getSources(
                        Column.temporary("stmt0.temp0.renamed")));
    }

    @Test
    public void lateralAliasedPhysicalReadKeepsWrittenIdentity() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT); CREATE TABLE c (v INT);"
                + " CREATE TABLE d (k INT); CREATE TABLE e (\"K\" INT);"
                + " CREATE TABLE f (k INT); CREATE TABLE t (c1 INT, c2 INT, c3 INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO missing(k) SELECT k FROM b;"
                        + " SELECT q.k FROM missing AS m"
                        + " CROSS JOIN LATERAL (SELECT m.k) AS q;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("b.k"))),
                result.getGraph().getSources(
                        Column.temporary("stmt1.temp0.k")));
    }

    @Test
    public void lateralFollowingStarAfterUnknownStaysTableLevel() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT); CREATE TABLE c (v INT);"
                + " CREATE TABLE d (k INT); CREATE TABLE e (\"K\" INT);"
                + " CREATE TABLE t (c1 INT, c2 INT, c3 INT, c4 INT);");
        ScanResult select = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT q.* FROM missing CROSS JOIN b"
                        + " CROSS JOIN LATERAL (SELECT missing.*, b.*) AS q;");
        assertEquals(0, select.getFailures().size());
        Set<Column> sources = select.getGraph().getSources(
                Column.temporary("stmt0.temp0.*"));
        assertTrue(sources.contains(new Column("missing.*")));
        assertTrue(sources.contains(new Column("b.*")));

        ScanResult insert = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t(c1,c2,c3,c4) SELECT q.* FROM missing"
                        + " CROSS JOIN b CROSS JOIN LATERAL"
                        + " (SELECT missing.*, b.k) AS q;");
        assertEquals(0, insert.getFailures().size());
        assertFalse(insert.getGraph().getSources(new Column("t.c2"))
                .contains(new Column("b.k")));
        assertTrue(insert.getGraph().getSources(new Column("t.c4"))
                .contains(new Column("b.*")));
    }

    @Test
    public void lateralBindingStarKeepsPlanPositions() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT); CREATE TABLE c (v INT);"
                + " CREATE TABLE d (k INT); CREATE TABLE e (\"K\" INT);"
                + " CREATE TABLE t (c1 INT, c2 INT, c3 INT, c4 INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t(c1,c2,c3) SELECT q.* FROM (("
                        + "SELECT * FROM missing UNION ALL SELECT 1,2) AS x"
                        + " CROSS JOIN b) AS j"
                        + " CROSS JOIN LATERAL (SELECT j.*) AS q;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("missing.*"))),
                result.getGraph().getSources(new Column("t.c1")));
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("missing.*"))),
                result.getGraph().getSources(new Column("t.c2")));
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("b.*"))),
                result.getGraph().getSources(new Column("t.c3")));
    }

    @Test
    public void lateralUncertainPositionsStillValidate() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT); CREATE TABLE c (v INT);"
                + " CREATE TABLE d (k INT); CREATE TABLE e (\"K\" INT);"
                + " CREATE TABLE t (c1 INT, c2 INT, c3 INT, c4 INT);");
        ScanResult missing = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT q.* FROM missing CROSS JOIN b"
                        + " CROSS JOIN LATERAL (SELECT missing.*, b.bogus) AS q;");
        assertTrue(missing.getResults().get(0).getError()
                instanceof ColumnNotFoundException);

        ScanResult ambiguous = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT q.* FROM missing CROSS JOIN (b CROSS JOIN d) AS j"
                        + " CROSS JOIN LATERAL (SELECT missing.*, j.k) AS q;");
        assertTrue(ambiguous.getResults().get(0).getError()
                instanceof AmbiguousColumnException);
    }

    @Test
    public void lateralStarIdentityAcrossTempAndBinding() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT); CREATE TABLE c (v INT);"
                + " CREATE TABLE d (k INT); CREATE TABLE e (\"K\" INT);"
                + " CREATE TABLE t (c1 INT, c2 INT, c3 INT, c4 INT);");
        ScanResult temp = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "WITH x AS (SELECT \"K\" FROM e)"
                        + " SELECT q.\"K\" FROM x CROSS JOIN"
                        + " LATERAL (SELECT x.*) AS q;");
        assertEquals(0, temp.getFailures().size());
        assertTrue(temp.getGraph().getSources(
                Column.temporary("stmt0.temp0.K"))
                .contains(new Column("e.K")));

        ScanResult binding = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT q.\"K\" FROM (e CROSS JOIN c) AS j"
                        + " CROSS JOIN LATERAL (SELECT j.*) AS q;");
        assertEquals(0, binding.getFailures().size());
        assertTrue(binding.getGraph().getSources(
                Column.temporary("stmt0.temp0.K"))
                .contains(new Column("e.K")));

        ScanResult declared = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT q.w FROM b CROSS JOIN"
                        + " LATERAL (SELECT 1,b.*) AS q(v,w);");
        assertEquals(0, declared.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("b.k"))),
                declared.getGraph().getSources(
                        Column.temporary("stmt0.temp0.w")));
    }

    @Test
    public void ancestorUsingUnknownSideIsMerged() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT); CREATE TABLE c (v INT);"
                + " CREATE TABLE d (k INT); CREATE TABLE e (\"K\" INT);"
                + " CREATE TABLE t (c1 INT, c2 INT, c3 INT, c4 INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT q.k FROM (missing RIGHT JOIN b USING(k))"
                        + " CROSS JOIN LATERAL (SELECT k FROM c) AS q;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("b.k"))),
                result.getGraph().getSources(
                        Column.temporary("stmt0.temp0.k")));
    }

    @Test
    public void ancestorAliasWinsOverTableName() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT); CREATE TABLE c (v INT);"
                + " CREATE TABLE d (k INT); CREATE TABLE e (\"K\" INT);"
                + " CREATE TABLE t (c1 INT, c2 INT, c3 INT, c4 INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT q.k FROM b AS x CROSS JOIN d AS b"
                        + " CROSS JOIN LATERAL (SELECT b.k FROM c) AS q;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("d.k"))),
                result.getGraph().getSources(
                        Column.temporary("stmt0.temp0.k")));
    }

    @Test
    public void lateralPhysicalReadFoldsEquivalentColumn() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT); CREATE TABLE c (v INT);"
                + " CREATE TABLE d (k INT); CREATE TABLE e (\"K\" INT);"
                + " CREATE TABLE t (c1 INT, c2 INT, c3 INT, c4 INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO missing(c1) SELECT k FROM b;"
                        + " SELECT q.c1 FROM missing AS m"
                        + " CROSS JOIN LATERAL (SELECT m.C1 AS c1) AS q;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("b.k"))),
                result.getGraph().getSources(
                        Column.temporary("stmt1.temp0.c1")));
    }

    @Test
    public void lateralJoiningUnknownWidthKeepsAllPositions() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT); CREATE TABLE c (v INT);"
                + " CREATE TABLE t (c1 INT, c2 INT, c3 INT, c4 INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t(c1,c2,c3,c4) SELECT q.* FROM"
                        + " (missing CROSS JOIN b) AS j"
                        + " CROSS JOIN LATERAL (SELECT j.*) AS q;");

        assertEquals(0, result.getFailures().size());
        Set<Column> last = result.getGraph().getSources(new Column("t.c4"));
        assertTrue(last.contains(new Column("missing.*")));
        assertTrue(last.contains(new Column("b.*")));
    }

    @Test
    public void lateralKnownWidthBeforeFollowingStar() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT); CREATE TABLE c (v INT);"
                + " CREATE TABLE t (c1 INT, c2 INT, c3 INT, c4 INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t(c1,c2,c3,c4) SELECT q.* FROM (("
                        + "SELECT * FROM missing UNION ALL SELECT 1,2) AS x"
                        + " CROSS JOIN b) AS j CROSS JOIN c"
                        + " CROSS JOIN LATERAL (SELECT j.*, c.*) AS q;");

        assertEquals(0, result.getFailures().size());
        assertTrue(result.getGraph().getSources(new Column("t.c4"))
                .contains(new Column("c.v")));
        assertFalse(result.getGraph().getSources(new Column("t.c2"))
                .contains(new Column("c.v")));
    }

    @Test
    public void lateralTempStarKeepsConstantSlot() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT); CREATE TABLE c (v INT);"
                + " CREATE TABLE t (c1 INT, c2 INT, c3 INT, c4 INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t(c1,c2) SELECT q.* FROM (SELECT 42 AS head,"
                        + " missing.* FROM missing UNION ALL SELECT 1,2) AS x"
                        + " CROSS JOIN LATERAL (SELECT x.*) AS q;");

        assertEquals(0, result.getFailures().size());
        assertFalse(result.getGraph().getSources(new Column("t.c1"))
                .contains(new Column("missing.*")));
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("missing.*"))),
                result.getGraph().getSources(new Column("t.c2")));
    }

    @Test
    public void lateralBindingStarKeepsWildcardContribution() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT); CREATE TABLE c (v INT);"
                + " CREATE TABLE t (c1 INT, c2 INT, c3 INT, c4 INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT q.a FROM ((SELECT * FROM m1 UNION ALL SELECT 1) AS x"
                        + " CROSS JOIN (SELECT * FROM m2 UNION ALL SELECT 2) AS y)"
                        + " AS j CROSS JOIN LATERAL (SELECT j.*) AS q(a,b);");

        assertEquals(0, result.getFailures().size());
        Set<Column> sources = result.getGraph().getSources(
                Column.temporary("stmt0.temp0.*"));
        assertTrue(sources.contains(new Column("m1.*")));
        assertTrue(sources.contains(new Column("m2.*")));
    }

    @Test
    public void lateralUncertainQualifiedColumnUsesMatchedSource() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT); CREATE TABLE c (v INT);"
                + " CREATE TABLE t (c1 INT, c2 INT, c3 INT, c4 INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT q.* FROM missing CROSS JOIN (b CROSS JOIN c) AS j"
                        + " CROSS JOIN LATERAL (SELECT missing.*, j.k) AS q;");

        assertEquals(0, result.getFailures().size());
        Set<Column> sources = result.getGraph().getSources(
                Column.temporary("stmt0.temp0.*"));
        assertTrue(sources.contains(new Column("missing.*")));
        assertTrue(sources.contains(new Column("b.*")));
        assertFalse(sources.contains(new Column("c.*")));
    }

    @Test
    public void lateralUnknownStarMinWidthStillMismatchesUnion() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT); CREATE TABLE c (v INT);"
                + " CREATE TABLE t (c1 INT, c2 INT, c3 INT, c4 INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT q.* FROM missing CROSS JOIN t CROSS JOIN LATERAL"
                        + " (SELECT missing.*, t.* UNION ALL SELECT 1,2) AS q;");

        assertTrue(result.getResults().get(0).getError()
                instanceof IllegalStateException);
    }

    @Test
    public void lateralQuotedPhysicalColumnKeepsDistinctIdentity() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT); CREATE TABLE c (v INT);"
                + " CREATE TABLE t (c1 INT, c2 INT, c3 INT, c4 INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO missing(\"C1\") SELECT k FROM b;"
                        + " INSERT INTO missing(c1) SELECT v FROM c;"
                        + " SELECT q.out FROM missing AS m"
                        + " CROSS JOIN LATERAL (SELECT m.\"C1\" AS out) AS q;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("b.k"))),
                result.getGraph().getSources(
                        Column.temporary("stmt2.temp0.out")));
    }

    @Test
    public void lateralAliasedTableHidesOriginalName() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT); CREATE TABLE c (v INT);"
                + " CREATE TABLE t (c1 INT, c2 INT, c3 INT, c4 INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT q.k FROM b AS x CROSS JOIN"
                        + " LATERAL (SELECT b.k) AS q;");

        assertTrue(result.getResults().get(0).getError()
                instanceof MetadataNotFoundException);
    }

    @Test
    public void nestedLateralOpenSpanSurvives() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT); CREATE TABLE c (v INT);"
                + " CREATE TABLE t (c1 INT, c2 INT, c3 INT, c4 INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t(c1,c2) SELECT r.* FROM missing"
                        + " CROSS JOIN LATERAL (SELECT missing.*) AS q"
                        + " CROSS JOIN LATERAL (SELECT q.*) AS r"
                        + " UNION ALL SELECT 1,2;");

        assertEquals(0, result.getFailures().size());
        assertTrue(result.getGraph().getSources(new Column("t.c1"))
                .contains(new Column("missing.*")));
        assertTrue(result.getGraph().getSources(new Column("t.c2"))
                .contains(new Column("missing.*")));
    }

    @Test
    public void correlatedStarKeepsKnownColumnWithContribution() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT); CREATE TABLE c (v INT);"
                + " CREATE TABLE t (c1 INT, c2 INT, c3 INT, c4 INT);");
        ScanResult union = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "WITH x AS (SELECT k FROM b UNION ALL SELECT * FROM missing)"
                        + " SELECT q.k FROM x CROSS JOIN"
                        + " LATERAL (SELECT x.*) AS q;");
        assertEquals(0, union.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("b.k"))),
                union.getGraph().getSources(
                        Column.temporary("stmt0.temp0.k")));
        assertTrue(union.getGraph().getSources(
                Column.temporary("stmt0.temp0.*"))
                .contains(new Column("missing.*")));

        ScanResult partial = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT q.k FROM (SELECT b.k, m2.* FROM b CROSS JOIN m2"
                        + " UNION ALL SELECT 1,2) AS x"
                        + " CROSS JOIN LATERAL (SELECT x.*) AS q;");
        assertEquals(0, partial.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("b.k"))),
                partial.getGraph().getSources(
                        Column.temporary("stmt0.temp0.k")));
        assertTrue(partial.getGraph().getSources(
                Column.temporary("stmt0.temp0.*"))
                .contains(new Column("m2.*")));
    }

    @Test
    public void uncertainPositionAfterUnknownStarDoesNotPinKnownColumn() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT); CREATE TABLE c (v INT);"
                + " CREATE TABLE t (c1 INT, c2 INT, c3 INT, c4 INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t(c1,c2,c3,c4) SELECT missing.*, x.*"
                        + " FROM missing CROSS JOIN (SELECT b.k, m2.*"
                        + " FROM b CROSS JOIN m2 UNION ALL SELECT 1,2) AS x;");

        assertEquals(0, result.getFailures().size());
        assertFalse(result.getGraph().getSources(new Column("t.c2"))
                .contains(new Column("b.k")));
    }

    @Test
    public void ambiguousUsingBindingStarStaysTableLevel() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT); CREATE TABLE c (v INT);"
                + " CREATE TABLE t (c1 INT, c2 INT, c3 INT, c4 INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t(c1,c2) SELECT r.* FROM (("
                        + "SELECT x.*, y.* FROM ("
                        + "SELECT * FROM m1 UNION ALL SELECT 1) AS x"
                        + " CROSS JOIN (SELECT * FROM m2 UNION ALL SELECT 2) AS y"
                        + ") AS q LEFT JOIN b USING(k)) AS j"
                        + " CROSS JOIN LATERAL (SELECT j.*) AS r;");

        assertEquals(0, result.getFailures().size());
        Set<Column> first = result.getGraph().getSources(new Column("t.c1"));
        Set<Column> second = result.getGraph().getSources(new Column("t.c2"));
        assertEquals(first, second);
        assertTrue(first.contains(new Column("m1.*")));
        assertTrue(first.contains(new Column("m2.*")));
    }

    @Test
    public void uncertainFirstStarKeepsMinimumWidth() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT); CREATE TABLE c (v INT);"
                + " CREATE TABLE t (c1 INT, c2 INT, c3 INT, c4 INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT q.* FROM (missing CROSS JOIN t) AS j"
                        + " CROSS JOIN LATERAL"
                        + " (SELECT j.* UNION ALL SELECT 1,2) AS q;");

        assertTrue(result.getResults().get(0).getError()
                instanceof IllegalStateException);
    }

    @Test
    public void sameFrameUnaliasedSourceStillMatches() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE s1.b (k INT);"
                + " CREATE TABLE s2.b (k INT); CREATE TABLE c (v INT);"
                + " CREATE TABLE t (c1 INT, c2 INT, c3 INT, c4 INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT q.k FROM s1.b AS x CROSS JOIN s2.b"
                        + " CROSS JOIN LATERAL (SELECT b.k) AS q;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("s2.b.k"))),
                result.getGraph().getSources(
                        Column.temporary("stmt0.temp0.k")));
    }

    @Test
    public void correlatedColumnContributionStaysInItsOwnSlot() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT); CREATE TABLE c (v INT);"
                + " CREATE TABLE t (c1 INT, c2 INT, c3 INT, c4 INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t(c1,c2) SELECT q.* FROM (SELECT b.k, m1.*"
                        + " FROM b CROSS JOIN m1 UNION ALL SELECT 1,2) AS x"
                        + " CROSS JOIN m2 CROSS JOIN LATERAL"
                        + " (SELECT x.k, m2.*) AS q;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("m2.*"))),
                result.getGraph().getSources(new Column("t.c2")));
    }

    @Test
    public void outOfRangeSpanDoesNotWidenDerivedStar() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT); CREATE TABLE c (v INT);"
                + " CREATE TABLE t (c1 INT, c2 INT, c3 INT, c4 INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "WITH x AS (SELECT 0 AS k, missing.* FROM missing"
                        + " UNION ALL SELECT 1,2)"
                        + " SELECT r.* FROM x CROSS JOIN LATERAL"
                        + " (SELECT x.k) AS q CROSS JOIN LATERAL"
                        + " (SELECT q.* UNION ALL SELECT 3) AS r;");

        assertEquals(0, result.getFailures().size());
    }

    @Test
    public void unknownWidthTempStarKeepsKnownPrefix() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT); CREATE TABLE c (v INT);"
                + " CREATE TABLE t (c1 INT, c2 INT, c3 INT, c4 INT);");
        ScanResult traced = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT r.k FROM b CROSS JOIN missing CROSS JOIN LATERAL"
                        + " (SELECT b.k, missing.*) AS q CROSS JOIN LATERAL"
                        + " (SELECT q.*) AS r;");
        assertEquals(0, traced.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("b.k"))),
                traced.getGraph().getSources(
                        Column.temporary("stmt0.temp0.k")));

        ScanResult mismatch = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT r.* FROM t CROSS JOIN missing CROSS JOIN LATERAL"
                        + " (SELECT t.*, missing.*) AS q CROSS JOIN LATERAL"
                        + " (SELECT q.* UNION ALL SELECT 1,2) AS r;");
        assertTrue(mismatch.getResults().get(0).getError()
                instanceof IllegalStateException);
    }

    @Test
    public void lateralWithLocalFromRejectsHiddenAlias() {
        ScanResult result = LineageScanner.withoutMetadata(optional()).scan(
                "SELECT q.k FROM b AS x"
                        + " CROSS JOIN LATERAL (SELECT b.k FROM c) AS q;");

        assertTrue(result.getResults().get(0).getError()
                instanceof MetadataNotFoundException);
    }

    @Test
    public void sameScopeDuplicateQualifiersAreAmbiguous() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE s1.b (k INT);"
                + " CREATE TABLE s2.b (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT q.k FROM s1.b CROSS JOIN s2.b"
                        + " CROSS JOIN LATERAL (SELECT b.k) AS q;");

        assertTrue(result.getResults().get(0).getError()
                instanceof AmbiguousColumnException);
    }

    @Test
    public void quotedCorrelatedColumnUsesQuotedSpanKey() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT); CREATE TABLE c (v INT);"
                + " CREATE TABLE d (k INT); CREATE TABLE e (\"K\" INT);");
        ScanResult first = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "WITH x AS (SELECT b.k AS \"K\" FROM b"
                        + " UNION ALL SELECT * FROM missing)"
                        + " SELECT q.v FROM x CROSS JOIN"
                        + " LATERAL (SELECT x.\"K\" AS v) AS q;");
        assertEquals(0, first.getFailures().size());
        assertTrue(first.getGraph().getSources(
                Column.temporary("stmt0.temp0.*"))
                .contains(new Column("missing.*")));

        ScanResult second = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "WITH x AS (SELECT b.k AS \"K\", c.v AS k"
                        + " FROM b CROSS JOIN c"
                        + " UNION ALL SELECT 0, m2.* FROM m2)"
                        + " SELECT q.z FROM x CROSS JOIN"
                        + " LATERAL (SELECT x.\"K\" AS z) AS q;");
        assertEquals(0, second.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("b.k"))),
                second.getGraph().getSources(
                        Column.temporary("stmt0.temp0.z")));
    }

    @Test
    public void unknownWidthPrefixKeepsQuotedKeys() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT); CREATE TABLE c (v INT);"
                + " CREATE TABLE d (k INT); CREATE TABLE e (\"K\" INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT r.k FROM e CROSS JOIN b CROSS JOIN missing"
                        + " CROSS JOIN LATERAL (SELECT e.\"K\", b.k, missing.*) AS q"
                        + " CROSS JOIN LATERAL (SELECT q.*) AS r;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("b.k"))),
                result.getGraph().getSources(
                        Column.temporary("stmt0.temp0.k")));
    }

    @Test
    public void ancestorDuplicateQualifiersAreAmbiguous() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE s1.b (k INT);"
                + " CREATE TABLE s2.b (k INT); CREATE TABLE c (v INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT q.k FROM s1.b CROSS JOIN s2.b"
                        + " CROSS JOIN LATERAL (SELECT b.k FROM c) AS q;");

        assertTrue(result.getResults().get(0).getError()
                instanceof AmbiguousColumnException);
    }

    @Test
    public void quotedPhysicalTableRejectsUnquotedReference() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE s.\"B\" (k INT);"
                + " CREATE TABLE c (v INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT q.k FROM s.\"B\" CROSS JOIN"
                        + " LATERAL (SELECT s.b.k) AS q;");

        assertTrue(result.getResults().get(0).getError()
                instanceof MetadataNotFoundException);
    }

    @Test
    public void duplicateDerivedOutputNamesAreAmbiguous() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT); CREATE TABLE c (v INT);"
                + " CREATE TABLE d (k INT); CREATE TABLE e (\"K\" INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT q.k FROM (SELECT b.k, d.k FROM b CROSS JOIN d) AS x"
                        + " CROSS JOIN LATERAL (SELECT x.k) AS q;");

        assertTrue(result.getResults().get(0).getError()
                instanceof AmbiguousColumnException);
    }

    @Test
    public void quotedColumnThroughJoinAliasKeepsItsSpan() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT); CREATE TABLE c (v INT);"
                + " CREATE TABLE d (k INT); CREATE TABLE e (\"K\" INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "WITH x AS (SELECT b.k AS \"K\", c.v AS k"
                        + " FROM b CROSS JOIN c"
                        + " UNION ALL SELECT 0, m2.* FROM m2)"
                        + " SELECT q.z FROM (x CROSS JOIN d) AS j"
                        + " CROSS JOIN LATERAL (SELECT j.\"K\" AS z) AS q;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("b.k"))),
                result.getGraph().getSources(
                        Column.temporary("stmt0.temp0.z")));
    }

    @Test
    public void unknownWidthPrefixUsesDeclaredQuotedKeys() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT); CREATE TABLE c (v INT);"
                + " CREATE TABLE d (k INT); CREATE TABLE e (\"K\" INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT r.k FROM e CROSS JOIN b CROSS JOIN missing"
                        + " CROSS JOIN LATERAL (SELECT e.\"K\", b.k, missing.*) AS q"
                        + " CROSS JOIN LATERAL (SELECT q.*) AS r(\"K\", k);");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("b.k"))),
                result.getGraph().getSources(
                        Column.temporary("stmt0.temp0.k")));
    }

    @Test
    public void searchPathQuotedEquivalentTableBinds() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE s.b (k INT);"
                + " CREATE TABLE c (v INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SET search_path TO s, public;"
                        + " SELECT q.k FROM \"b\" CROSS JOIN"
                        + " LATERAL (SELECT s.b.k) AS q;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("s.b.k"))),
                result.getGraph().getSources(
                        Column.temporary("stmt1.temp0.k")));
    }

    @Test
    public void duplicateDerivedColumnsAmbiguousInAllPaths() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT); CREATE TABLE c (v INT);"
                + " CREATE TABLE d (k INT); CREATE TABLE e (\"K\" INT);");
        ScanResult withFrom = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT q.k FROM (SELECT b.k, d.k FROM b CROSS JOIN d) AS x"
                        + " CROSS JOIN LATERAL (SELECT x.k FROM c) AS q;");
        assertTrue(withFrom.getResults().get(0).getError()
                instanceof AmbiguousColumnException);

        ScanResult partialNames = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT q.k FROM (SELECT b.k, d.k, missing.*"
                        + " FROM b CROSS JOIN d CROSS JOIN missing) AS x"
                        + " CROSS JOIN LATERAL (SELECT x.k) AS q;");
        assertTrue(partialNames.getResults().get(0).getError()
                instanceof AmbiguousColumnException);
    }

    @Test
    public void duplicateStarSourceColumnsKeepDistinctPositions() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE b (k INT); CREATE TABLE c (v INT);"
                + " CREATE TABLE d (k INT); CREATE TABLE e (\"K\" INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT q.v FROM (SELECT b.k, d.k FROM b CROSS JOIN d) AS x"
                        + " CROSS JOIN LATERAL (SELECT x.*) AS q(u, v);");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("d.k"))),
                result.getGraph().getSources(
                        Column.temporary("stmt0.temp0.v")));
    }

    @Test
    public void wildcardVertexRendersWithoutQuotes() {
        Column wildcard = new Column("a.*");
        assertEquals("a", wildcard.getTableName());
        assertEquals("*", wildcard.getColumnName());
        assertEquals("a.*", wildcard.toString());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("a.*"))),
                new HashSet<>(java.util.Arrays.asList(wildcard)));
    }

    @Test
    public void tableLevelEdgeSurvivesWithoutAnyCatalog() {
        ScanResult result = LineageScanner.withoutMetadata(optional())
                .scan("SELECT * FROM a;");

        assertNull(result.getResults().get(0).getError());
        Set<Column> sources = result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.*"));
        assertNotNull(sources);
        assertEquals(1, sources.size());
        assertEquals(new Column("a.*"), sources.iterator().next());
    }
    @Test
    public void twoPassLearningKeepsOnlySupportedObservations() {
        LineageScanner scanner = LineageScanner.withoutMetadata(
                DbType.postgresql, inferring());
        scanner.scan("SELECT C.z, y FROM C; SELECT A.y, A.x, y FROM A,C;");

        // C.z was only reachable through a failing statement; C stays unknown.
        ScanResult rescan = scanner.scan("SELECT y FROM C;");
        assertEquals(0, rescan.getFailures().size());
    }

    @Test
    public void metadataDiscoveryWalksTheWholeExpression() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.postgresql, inferring()).scan(
                "SELECT k + A.k + B.y AS z FROM A,B;");

        assertEquals(0, result.getFailures().size());
    }

    @Test
    public void twoPassLearningDropsObservationsOfAFailingStatement() {
        LineageScanner scanner = LineageScanner.withoutMetadata(
                DbType.postgresql, inferring());
        ScanResult log = scanner.scan(
                "SELECT C.z, y FROM C; SELECT A.y, A.x, y FROM A,C;");
        assertTrue(log.getResults().get(1).getError()
                instanceof AmbiguousColumnException);

        // A.y/A.x came from the failing statement and must not block A.z.
        ScanResult rescan = scanner.scan("SELECT z FROM A;");
        assertEquals(0, rescan.getFailures().size());
    }

    @Test
    public void joinAliasIsNotLearnedAsAPhysicalTable() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE s (k INT); CREATE TABLE b (v INT);");
        LineageScanner scanner = LineageScanner.withMetadata(
                MetadataContext.of(catalog), inferring());
        assertEquals(0, scanner.scan(
                "SELECT j.k FROM (s CROSS JOIN b) AS j;").getFailures().size());
        assertEquals(0, scanner.scan(
                "SELECT j.y, o.y FROM j,o;").getFailures().size());

        ScanResult result = scanner.scan("SELECT k FROM j,o;");
        assertTrue(result.getResults().get(0).getError()
                instanceof ColumnNotFoundException);
    }

    @Test
    public void metadataDiscoverySeesReferencesInsideScalarSubqueries() {
        LineageScanner scanner = LineageScanner.withoutMetadata(
                DbType.postgresql, inferring());
        assertEquals(0, scanner.scan(
                "SELECT A.y, B.y FROM A,B;").getFailures().size());
        ScanResult result = scanner.scan(
                "SELECT k+(SELECT A.k FROM A) AS z FROM A,B;");

        assertEquals(0, result.getFailures().size());
    }

    @Test
    public void scalarLearningUsesItsOwnAliasScope() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.postgresql, inferring()).scan(
                "SELECT 1+(SELECT x.k FROM B AS x) AS z FROM A AS x;"
                        + " SELECT A.y, C.y FROM A,C;"
                        + " SELECT k FROM A,C;");

        assertNull(result.getResults().get(0).getError());
        assertNull(result.getResults().get(1).getError());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("b.k"))),
                result.getGraph().getSources(
                        Column.temporary("stmt0.temp0.z")));
        // The inner alias belongs to B, so A.k must never be learned from it.
        if (result.getResults().get(2).getError() == null) {
            assertFalse(result.getGraph().getSources(
                    Column.temporary("stmt2.temp0.k"))
                    .contains(new Column("a.k")));
        }
    }

    @Test
    public void metadataDiscoveryReadsInSubqueryBodies() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.postgresql, inferring()).scan(
                "SELECT k FROM A,B;"
                        + " SELECT 1 IN (SELECT A.k FROM A) FROM B;"
                        + " SELECT A.y, B.y FROM A,B;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("a.k"))),
                result.getGraph().getSources(
                        Column.temporary("stmt0.temp0.k")));
    }

    @Test
    public void unionBranchesKeepTheirOwnAliasesWhenLearning() {
        LineageScanner scanner = LineageScanner.withoutMetadata(
                DbType.postgresql, inferring());
        scanner.scan("SELECT 1 IN (SELECT x.k FROM A AS x"
                + " UNION ALL SELECT 0 FROM B AS x) FROM Z;"
                + " SELECT B.y, C.y FROM B,C;");
        ScanResult result = scanner.scan("SELECT k FROM B,C;");

        assertTrue(result.getResults().get(0).getError()
                instanceof ColumnNotFoundException);
    }

    @Test
    public void cteNamesAreNotLearnedAsPhysicalTables() {
        LineageScanner scanner = LineageScanner.withoutMetadata(
                DbType.postgresql, inferring());
        scanner.scan("SELECT 1 IN (WITH x AS (SELECT 1 AS k)"
                + " SELECT x.k FROM x) FROM Z;"
                + " SELECT x.y, C.y FROM x,C;");
        ScanResult result = scanner.scan("SELECT k FROM x,C;");

        assertTrue(result.getResults().get(0).getError()
                instanceof ColumnNotFoundException);
    }

    @Test
    public void metadataDiscoveryReadsDerivedTablesInSubqueries() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.postgresql, inferring()).scan(
                "SELECT k FROM A,B;"
                        + " SELECT 1 IN (SELECT q.k FROM (SELECT A.k FROM A) AS q)"
                        + " FROM B;"
                        + " SELECT A.y, B.y FROM A,B;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("a.k"))),
                result.getGraph().getSources(
                        Column.temporary("stmt0.temp0.k")));
    }

    @Test
    public void outerCteNamesAreNotLearnedAsPhysicalTables() {
        LineageScanner scanner = LineageScanner.withoutMetadata(
                DbType.postgresql, inferring());
        scanner.scan("WITH x AS (SELECT 1 AS k)"
                + " SELECT 1 IN (SELECT x.k FROM x) FROM Z;"
                + " SELECT x.y, C.y FROM x,C;");
        ScanResult result = scanner.scan("SELECT k FROM x,C;");

        assertTrue(result.getResults().get(0).getError()
                instanceof ColumnNotFoundException);
    }

    @Test
    public void nonRecursiveCteBodyStillSeesThePhysicalTable() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.postgresql, inferring()).scan(
                "SELECT k FROM A,B;"
                        + " SELECT 1 IN (WITH A AS (SELECT A.k FROM A)"
                        + " SELECT A.k FROM A) FROM Z;"
                        + " SELECT A.y, B.y FROM A,B;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("a.k"))),
                result.getGraph().getSources(
                        Column.temporary("stmt0.temp0.k")));
    }

    @Test
    public void joinAliasHidesChildAliasesWhileLearning() {
        LineageScanner scanner = LineageScanner.withoutMetadata(
                DbType.postgresql, inferring());
        scanner.scan("SELECT 1 IN (SELECT x.k FROM"
                + " (B AS x CROSS JOIN D) AS j) FROM A AS x;"
                + " SELECT B.y, C.y FROM B,C;");
        ScanResult result = scanner.scan("SELECT k FROM B,C;");

        assertTrue(result.getResults().get(0).getError()
                instanceof ColumnNotFoundException);
    }

    @Test
    public void joinOnReferencesAreDiscovered() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.postgresql, inferring()).scan(
                "SELECT k FROM A,B;"
                        + " SELECT 1 IN (SELECT 1 FROM A JOIN B ON A.k=B.y) FROM Z;"
                        + " SELECT A.y, B.y FROM A,B;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("a.k"))),
                result.getGraph().getSources(
                        Column.temporary("stmt0.temp0.k")));
    }

    @Test
    public void quotedOuterCteNamesAreNotLearnedAsTables() {
        LineageScanner scanner = LineageScanner.withoutMetadata(
                DbType.postgresql, inferring());
        scanner.scan("WITH \"X\" AS (SELECT 1 AS k)"
                + " SELECT 1 IN (SELECT \"X\".k FROM \"X\") FROM Z;"
                + " SELECT \"X\".y, C.y FROM \"X\", C;");
        ScanResult result = scanner.scan("SELECT k FROM \"X\", C;");

        assertTrue(result.getResults().get(0).getError()
                instanceof ColumnNotFoundException);
    }

    @Test
    public void physicalAliasSharingACteNameIsStillLearned() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.postgresql, inferring()).scan(
                "SELECT k FROM A,B;"
                        + " SELECT 1 IN (WITH x AS (SELECT 0)"
                        + " SELECT x.k FROM A AS x) FROM Z;"
                        + " SELECT A.y, B.y FROM A,B;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("a.k"))),
                result.getGraph().getSources(
                        Column.temporary("stmt0.temp0.k")));
    }

    @Test
    public void shortNameStaysInsideItsSchemaInLearning() {
        LineageScanner scanner = LineageScanner.withoutMetadata(
                DbType.postgresql, inferring());
        assertEquals(0, scanner.scan(
                "SELECT 1 IN (SELECT a.k FROM s2.a) FROM s1.a;").getFailures().size());
        scanner.scan("SELECT s1.a.y, C.y FROM s1.a,C;");
        ScanResult result = scanner.scan("SELECT k FROM s1.a,C;");

        assertTrue(result.getResults().get(0).getError()
                instanceof ColumnNotFoundException);
    }

    @Test
    public void aliasedTableDoesNotExposeItsShortNameToLearning() {
        LineageScanner scanner = LineageScanner.withoutMetadata(
                DbType.postgresql, inferring());
        scanner.scan("SELECT 1 IN (SELECT a.k FROM s2.a AS x) FROM s1.a AS a;"
                + " SELECT s2.a.y, C.y FROM s2.a,C;");
        ScanResult result = scanner.scan("SELECT k FROM s2.a,C;");

        assertTrue(result.getResults().get(0).getError()
                instanceof ColumnNotFoundException);
    }

    @Test
    public void innerShortNameShadowsAnInheritedOne() {
        LineageScanner scanner = LineageScanner.withoutMetadata(
                DbType.postgresql, inferring());
        scanner.scan("SELECT 1 IN (SELECT 1 IN"
                + " (SELECT a.k FROM s2.a) FROM s1.a) FROM Z;"
                + " SELECT s1.a.y, C.y FROM s1.a,C;");
        ScanResult result = scanner.scan("SELECT k FROM s1.a,C;");

        assertTrue(result.getResults().get(0).getError()
                instanceof ColumnNotFoundException);
    }

    @Test
    public void duplicateLocalShortNamesLearnNothing() {
        LineageScanner scanner = LineageScanner.withoutMetadata(
                DbType.postgresql, inferring());
        scanner.scan("SELECT 1 IN (SELECT a.k FROM"
                + " s1.a CROSS JOIN s2.a) FROM Z;"
                + " SELECT s1.a.y, C.y FROM s1.a,C;");
        ScanResult result = scanner.scan("SELECT k FROM s1.a,C;");

        assertTrue(result.getResults().get(0).getError()
                instanceof ColumnNotFoundException);
    }

    @Test
    public void shortNameConflictIsIndependentOfFromOrder() {
        LineageScanner scanner = LineageScanner.withoutMetadata(
                DbType.postgresql, inferring());
        scanner.scan("SELECT 1 IN (SELECT a.k FROM s1.a CROSS JOIN a) FROM Z;"
                + " SELECT a.y, C.y FROM a,C;");
        ScanResult result = scanner.scan("SELECT k FROM a,C;");

        assertTrue(result.getResults().get(0).getError()
                instanceof ColumnNotFoundException);
    }

    @Test
    public void nestedJoinConditionSeesInheritedAliases() {
        LineageScanner scanner = LineageScanner.withoutMetadata(
                DbType.postgresql, inferring());
        scanner.scan("SELECT 1 IN (SELECT 1 IN"
                + " (SELECT 1 FROM B JOIN D ON q.k=B.y) FROM A AS q) FROM Z;"
                + " SELECT q.y, C.y FROM q,C;");
        ScanResult result = scanner.scan("SELECT k FROM q,C;");

        // q.k refers to the outer A AS q, never to a physical table q.
        assertTrue(result.getResults().get(0).getError()
                instanceof ColumnNotFoundException);
    }

    @Test
    public void unusedCteDoesNotHideAnOuterAlias() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.postgresql, inferring()).scan(
                "SELECT k FROM A,C;"
                        + " SELECT 1 IN (SELECT 1 IN"
                        + " (WITH q AS (SELECT 1) SELECT q.k) FROM A AS q) FROM Z;"
                        + " SELECT A.y, C.y FROM A,C;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("a.k"))),
                result.getGraph().getSources(
                        Column.temporary("stmt0.temp0.k")));
    }

    @Test
    public void cteReadThroughAliasIsNotLearnedAsATable() {
        LineageScanner scanner = LineageScanner.withoutMetadata(
                DbType.postgresql, inferring());
        scanner.scan("SELECT 1 IN (WITH q AS (SELECT 1 AS k)"
                + " SELECT x.k FROM q AS x) FROM Z;"
                + " SELECT q.y, C.y FROM q,C;");
        ScanResult result = scanner.scan("SELECT k FROM q,C;");

        assertTrue(result.getResults().get(0).getError()
                instanceof ColumnNotFoundException);
    }

    @Test
    public void laterCteBodyReferenceKeepsTheCteName() {
        LineageScanner scanner = LineageScanner.withoutMetadata(
                DbType.postgresql, inferring());
        scanner.scan("SELECT 1 IN (WITH q AS (SELECT 1 AS k),"
                + " w AS (SELECT q.k FROM q) SELECT w.k FROM w) FROM Z;"
                + " SELECT q.y, C.y FROM q,C;");
        ScanResult result = scanner.scan("SELECT k FROM q,C;");

        assertTrue(result.getResults().get(0).getError()
                instanceof ColumnNotFoundException);
    }

    @Test
    public void aliasedJoinConditionSeesItsChildAliases() {
        LineageScanner scanner = LineageScanner.withoutMetadata(
                DbType.postgresql, inferring());
        scanner.scan("SELECT 1 IN (SELECT 1 FROM"
                + " (A AS x JOIN D ON x.k=D.y) AS j) FROM Z;"
                + " SELECT x.y, C.y FROM x,C;");
        ScanResult result = scanner.scan("SELECT k FROM x,C;");

        assertTrue(result.getResults().get(0).getError()
                instanceof ColumnNotFoundException);
    }

    @Test
    public void cteShadowingIsPerUnionBranch() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.postgresql, inferring()).scan(
                "SELECT k FROM A,C;"
                        + " SELECT 1 IN (SELECT 1 IN (WITH q AS (SELECT 1 AS k)"
                        + " SELECT q.k UNION ALL SELECT q.k FROM q)"
                        + " FROM A AS q) FROM Z;"
                        + " SELECT A.y, C.y FROM A,C;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("a.k"))),
                result.getGraph().getSources(
                        Column.temporary("stmt0.temp0.k")));
    }

    @Test
    public void unusedOuterCteDoesNotHideARealAlias() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.postgresql, inferring()).scan(
                "SELECT k FROM A,C;"
                        + " WITH q AS (SELECT 1) SELECT 1+(SELECT q.k) FROM A AS q;"
                        + " SELECT A.y, C.y FROM A,C;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("a.k"))),
                result.getGraph().getSources(
                        Column.temporary("stmt0.temp0.k")));
    }

    @Test
    public void derivedTableDoesNotSeeSiblingAliases() {
        LineageScanner scanner = LineageScanner.withoutMetadata(
                DbType.postgresql, inferring());
        scanner.scan("SELECT 1 IN (SELECT d.k FROM B AS q"
                + " CROSS JOIN (SELECT q.k) AS d) FROM A AS q;"
                + " SELECT B.y, C.y FROM B,C;");
        ScanResult result = scanner.scan("SELECT k FROM B,C;");

        assertTrue(result.getResults().get(0).getError()
                instanceof ColumnNotFoundException);
    }

    @Test
    public void innerJoinConditionDoesNotSeeOuterJoinSibling() {
        LineageScanner scanner = LineageScanner.withoutMetadata(
                DbType.postgresql, inferring());
        scanner.scan("SELECT 1 IN (SELECT 1 FROM"
                + " (B JOIN D ON q.k=B.y) CROSS JOIN E AS q) FROM A AS q;"
                + " SELECT E.y, C.y FROM E,C;");
        ScanResult result = scanner.scan("SELECT k FROM E,C;");

        assertTrue(result.getResults().get(0).getError()
                instanceof ColumnNotFoundException);
    }

    @Test
    public void unboundCteReferenceIsNotLearnedAsATable() {
        LineageScanner scanner = LineageScanner.withoutMetadata(
                DbType.postgresql, inferring());
        scanner.scan("WITH q AS (SELECT 1 AS k)"
                + " SELECT 1 IN (SELECT q.k) FROM Z;"
                + " SELECT q.y, C.y FROM q, C;");
        ScanResult result = scanner.scan("SELECT k FROM q, C;");

        assertTrue(result.getResults().get(0).getError()
                instanceof ColumnNotFoundException);
    }

    @Test
    public void standaloneJoinConditionIsDiscovered() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.postgresql, inferring()).scan(
                "SELECT k FROM A, C;"
                        + " SELECT 1 FROM A AS q JOIN D ON q.k = D.y;"
                        + " SELECT A.y, C.y FROM A, C;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("a.k"))),
                result.getGraph().getSources(
                        Column.temporary("stmt0.temp0.k")));
    }

    @Test
    public void derivedBodyDoesNotLearnFromSiblingSources() {
        LineageScanner scanner = LineageScanner.withoutMetadata(
                DbType.postgresql, inferring());
        scanner.scan("SELECT (SELECT 1 FROM B AS q"
                + " CROSS JOIN (SELECT q.k) AS d LIMIT 1) FROM A AS q;"
                + " SELECT B.y, C.y FROM B, C;");
        ScanResult result = scanner.scan("SELECT k FROM B, C;");

        assertTrue(result.getResults().get(0).getError()
                instanceof ColumnNotFoundException);
    }

    @Test
    public void joinBlockingCoversQualifiedShortNames() {
        LineageScanner scanner = LineageScanner.withoutMetadata(
                DbType.postgresql, inferring());
        scanner.scan("SELECT (SELECT 1 FROM"
                + " (B JOIN D ON e.k = B.y) CROSS JOIN s.e LIMIT 1) FROM A AS e;"
                + " SELECT s.e.y, C.y FROM s.e, C;");
        ScanResult result = scanner.scan("SELECT k FROM s.e, C;");

        assertTrue(result.getResults().get(0).getError()
                instanceof ColumnNotFoundException);
    }

    @Test
    public void joinConditionSeesTheEnclosingAlias() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.postgresql, inferring()).scan(
                "SELECT k FROM A, C;"
                        + " UPDATE t SET v = (SELECT 1 FROM"
                        + " (B JOIN D ON q.k = B.y) CROSS JOIN E AS q LIMIT 1)"
                        + " FROM A AS q;"
                        + " SELECT A.y, C.y FROM A, C;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("a.k"))),
                result.getGraph().getSources(
                        Column.temporary("stmt0.temp0.k")));
    }

    @Test
    public void noFromBlockStillDiscoversItsSubqueries() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.postgresql, inferring()).scan(
                "SELECT k FROM A, C;"
                        + " SELECT 1 IN (SELECT A.k FROM A);"
                        + " SELECT A.y, C.y FROM A, C;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("a.k"))),
                result.getGraph().getSources(
                        Column.temporary("stmt0.temp0.k")));
    }

    @Test
    public void noFromBlockSeesItsEnclosingFrame() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.postgresql, inferring()).scan(
                "SELECT k FROM A, C;"
                        + " UPDATE t SET v = (SELECT 1 WHERE q.k = 1) FROM A AS q;"
                        + " SELECT A.y, C.y FROM A, C;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("a.k"))),
                result.getGraph().getSources(
                        Column.temporary("stmt0.temp0.k")));
    }

    @Test
    public void blockProjectionSeesAnInheritedAlias() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.postgresql, inferring()).scan(
                "SELECT k FROM A, C;"
                        + " UPDATE t SET v = (SELECT 1 FROM Z"
                        + " WHERE q.k = 1 LIMIT 1) FROM A AS q;"
                        + " SELECT A.y, C.y FROM A, C;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("a.k"))),
                result.getGraph().getSources(
                        Column.temporary("stmt0.temp0.k")));
    }

    @Test
    public void nearestOuterBindingWins() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.postgresql, inferring()).scan(
                "SELECT k FROM A, C;"
                        + " UPDATE t SET v = (SELECT (SELECT 1 FROM Z JOIN W"
                        + " ON q.k = 1 LIMIT 1) FROM B AS q LIMIT 1)"
                        + " FROM A AS q;"
                        + " SELECT A.y, C.y FROM A, C;");

        // q.k belongs to the nearest B AS q, never to the farther A AS q.
        assertTrue(result.getResults().get(0).getError()
                instanceof ColumnNotFoundException);
    }

    @Test
    public void outerAliasesHideTheirTableNames() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.postgresql, inferring()).scan(
                "SELECT k FROM A, C;"
                        + " UPDATE t SET v = (SELECT 1 FROM Z JOIN W"
                        + " ON A.k = 1 LIMIT 1) FROM A AS q;"
                        + " SELECT A.y, C.y FROM A, C;");

        assertTrue(result.getResults().get(0).getError()
                instanceof ColumnNotFoundException);
    }

    @Test
    public void outerDuplicateShortNamesLearnNothing() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.postgresql, inferring()).scan(
                "SELECT k FROM s2.a, C;"
                        + " UPDATE t SET v = (SELECT 1 FROM Z JOIN W"
                        + " ON a.k = 1 LIMIT 1) FROM s1.a CROSS JOIN s2.a;"
                        + " SELECT s2.a.y, C.y FROM s2.a, C;");

        assertTrue(result.getResults().get(0).getError()
                instanceof ColumnNotFoundException);
    }

    @Test
    public void joinAliasLearningUsesItsVisiblePlan() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.postgresql, inferring()).scan(
                "SELECT k FROM B, C;"
                        + " SELECT 1 FROM ((SELECT 1 AS k) AS d CROSS JOIN B) AS j"
                        + " WHERE j.k = 1;"
                        + " SELECT B.y, C.y FROM B, C;");

        // j.k is the derived constant, not a column of B.
        assertTrue(result.getResults().get(0).getError()
                instanceof ColumnNotFoundException);
    }

    @Test
    public void updateFramesKeepJoinAliasHiding() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.postgresql, inferring()).scan(
                "SELECT k FROM B, C;"
                        + " UPDATE t SET v = (SELECT (SELECT 1 WHERE q.k = 1)"
                        + " FROM (B AS q CROSS JOIN D) AS j LIMIT 1) FROM A AS q;"
                        + " SELECT A.k, B.y, C.y FROM A, B, C;");

        // j hides B AS q, so q.k cannot become B.k.
        assertTrue(result.getResults().get(0).getError()
                instanceof ColumnNotFoundException);
    }

    @Test
    public void nestedBlockKeepsInheritedJoinPlan() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.postgresql, inferring()).scan(
                "SELECT k FROM A, C;"
                        + " UPDATE t SET v = (SELECT 1 FROM Z"
                        + " WHERE j.k = 1 LIMIT 1) FROM (A JOIN B USING (k)) AS j;"
                        + " SELECT A.y, C.y FROM A, C;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("a.k"))),
                result.getGraph().getSources(
                        Column.temporary("stmt0.temp0.k")));
    }

    @Test
    public void localJoinAliasShadowsAnInheritedPlan() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.postgresql, inferring()).scan(
                "SELECT k FROM A, C;"
                        + " SELECT 1 FROM (A JOIN B USING (k)) AS j"
                        + " WHERE 1 IN (SELECT 1 FROM"
                        + " ((SELECT 1 AS k) AS d CROSS JOIN D) AS j"
                        + " WHERE j.k = 1);"
                        + " SELECT A.y, C.y FROM A, C;");

        assertTrue(result.getResults().get(0).getError()
                instanceof ColumnNotFoundException);
    }

    @Test
    public void quotedUsingKeyIsLearnedWithItsQuotes() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.postgresql, inferring()).scan(
                "SELECT k FROM A, C;"
                        + " SELECT \"K\" FROM A, C;"
                        + " SELECT 1 FROM (A JOIN B USING (\"K\")) AS j"
                        + " WHERE j.\"K\" = 1;"
                        + " SELECT A.y, C.y FROM A, C;");

        // Quoted K and unquoted k are distinct columns.
        assertTrue(result.getResults().get(0).getError()
                instanceof ColumnNotFoundException);
        assertEquals(0, result.getResults().get(1).getError() == null ? 0 : 1);
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("a.K"))),
                result.getGraph().getSources(
                        Column.temporary("stmt1.temp0.K")));
    }

    @Test
    public void innerJoinConditionPrefersTheEnclosingPlan() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.postgresql, inferring()).scan(
                "SELECT k FROM D, C;"
                        + " UPDATE t SET v = (SELECT 1 FROM"
                        + " (Z JOIN W ON j.k = 1)"
                        + " CROSS JOIN (D JOIN E USING (k)) AS j LIMIT 1)"
                        + " FROM (A JOIN B USING (k)) AS j;"
                        + " SELECT D.y, C.y FROM D, C;");

        // The same-named sibling join alias is not visible to the ON condition.
        assertTrue(result.getResults().get(0).getError()
                instanceof ColumnNotFoundException);
    }

    @Test
    public void nestedDerivedJoinConditionUsesTheEnclosingPlan() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.postgresql, inferring()).scan(
                "SELECT k FROM A, C;"
                        + " SELECT 1 FROM (A JOIN B USING (k)) AS j"
                        + " WHERE 1 IN (SELECT 1 FROM"
                        + " (Z JOIN W ON j.k = 1) AS j);"
                        + " SELECT A.y, C.y FROM A, C;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("a.k"))),
                result.getGraph().getSources(
                        Column.temporary("stmt0.temp0.k")));
    }

    @Test
    public void staticUsingAliasAttributesItsSides() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.postgresql, inferring()).scan(
                "SELECT k FROM A, C;"
                        + " SELECT 1 IN (SELECT 1 FROM"
                        + " (A JOIN B USING (k)) AS j WHERE j.k = 1);"
                        + " SELECT A.y, C.y FROM A, C;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("a.k"))),
                result.getGraph().getSources(
                        Column.temporary("stmt0.temp0.k")));
    }

    @Test
    public void ambiguousJoinOutputIsNotLearned() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.postgresql, inferring()).scan(
                "SELECT k FROM A, C;"
                        + " SELECT 1 FROM ((A JOIN B USING (k))"
                        + " CROSS JOIN (D JOIN E USING (k))) AS j"
                        + " WHERE j.k = 1;"
                        + " SELECT A.y, C.y FROM A, C;");

        assertTrue(result.getResults().get(0).getError()
                instanceof ColumnNotFoundException);
    }

    @Test
    public void usingAliasKeepsItsCteSide() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.postgresql, inferring()).scan(
                "SELECT k FROM x, C;"
                        + " WITH x AS (SELECT 1 AS k)"
                        + " SELECT 1 FROM (x JOIN B USING (k)) AS j WHERE j.k = 1;"
                        + " SELECT x.y, C.y FROM x, C;");

        assertTrue(result.getResults().get(0).getError()
                instanceof ColumnNotFoundException);
    }

    @Test
    public void compositeUsingSideDoesNotAttributeItsTables() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.postgresql, inferring()).scan(
                "SELECT k FROM X, C;"
                        + " SELECT 1 FROM ((A CROSS JOIN X) JOIN B USING (k)) AS j"
                        + " WHERE j.k = 1;"
                        + " SELECT A.k, X.y, B.k, C.y FROM A, X, B, C;");

        assertTrue(result.getResults().get(0).getError()
                instanceof ColumnNotFoundException);
    }

    @Test
    public void localJoinPlanSurvivesAliasComposition() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.postgresql, inferring()).scan(
                "SELECT k FROM A, C;"
                        + " SELECT 1 FROM ((A JOIN B USING (k))"
                        + " JOIN (SELECT 1 AS v) AS d USING (v)) AS j"
                        + " WHERE j.k = 1;"
                        + " SELECT A.y, C.y FROM A, C;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("a.k"))),
                result.getGraph().getSources(
                        Column.temporary("stmt0.temp0.k")));
    }

    @Test
    public void localJoinPlanSurvivesAJoinCondition() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.postgresql, inferring()).scan(
                "SELECT k FROM A, C;"
                        + " SELECT 1 FROM ((A JOIN B USING (k))"
                        + " CROSS JOIN (SELECT 1 AS v) AS d) AS j"
                        + " JOIN Z ON j.k = 1;"
                        + " SELECT A.y, C.y FROM A, C;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(new Column("a.k"))),
                result.getGraph().getSources(
                        Column.temporary("stmt0.temp0.k")));
    }

    @Test
    public void usingMarkerSurvivesSpecialCharacters() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.postgresql, inferring()).scan(
                "SELECT \"a=b\" FROM A, C;"
                        + " SELECT 1 IN (SELECT 1 FROM"
                        + " (A JOIN B USING (\"a=b\")) AS j"
                        + " WHERE j.\"a=b\" = 1);"
                        + " SELECT A.y, C.y FROM A, C;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(
                new Column("a.\"a=b\""))),
                result.getGraph().getSources(
                        Column.temporary("stmt0.temp0.\"a=b\"")));
    }

    @Test
    public void joinConditionTokenUsesItsOwnSubtree() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.postgresql, inferring()).scan(
                "SELECT k FROM A, C;"
                        + " SELECT 1 FROM (((SELECT 1 AS k) AS d"
                        + " CROSS JOIN D) AS j JOIN Z ON j.k = 1) AS p"
                        + " CROSS JOIN (A JOIN B USING (k)) AS j;"
                        + " SELECT A.y, C.y FROM A, C;");

        // The ON condition resolves the inner j, not the same-named sibling.
        assertTrue(result.getResults().get(0).getError()
                instanceof ColumnNotFoundException);
    }

    @Test
    public void compositeUsingSideWithDerivedTableAttributesNothing() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.postgresql, inferring()).scan(
                "SELECT k FROM X, C;"
                        + " SELECT 1 IN (SELECT 1 FROM (((SELECT 1 AS k) AS d"
                        + " CROSS JOIN X) JOIN B USING (k)) AS j WHERE j.k = 1);"
                        + " SELECT X.y, C.y FROM X, C;");

        assertTrue(result.getResults().get(0).getError()
                instanceof ColumnNotFoundException);
    }

    @Test
    public void hiddenJoinAliasDoesNotTakeTheVisibleToken() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.postgresql, inferring()).scan(
                "SELECT k FROM A, C;"
                        + " SELECT 1 FROM ((A JOIN B USING (k)) AS j CROSS JOIN Z)"
                        + " AS p CROSS JOIN ((SELECT 1 AS k) AS d CROSS JOIN D) AS j"
                        + " WHERE j.k = 1;"
                        + " SELECT A.y, C.y FROM A, C;");

        // The visible j is the derived-constant join, never the hidden A/B one.
        assertTrue(result.getResults().get(0).getError()
                instanceof ColumnNotFoundException);
    }

    @Test
    public void schemaQualifiedSingleTableStillAttributesUsingKey() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.postgresql, inferring()).scan(
                "SELECT k FROM s.a, C;"
                        + " SELECT 1 IN (SELECT 1 FROM"
                        + " (s.a JOIN B USING (k)) AS j WHERE j.k = 1);"
                        + " SELECT s.a.y, C.y FROM s.a, C;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(java.util.Arrays.asList(
                new Column("s.a.k"))),
                result.getGraph().getSources(
                        Column.temporary("stmt0.temp0.k")));
    }

}

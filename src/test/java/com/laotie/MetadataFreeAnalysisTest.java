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
        assertOnlyWildcardSources(unknownNamesFirst.getGraph(), new Column("t.c2"));

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
}

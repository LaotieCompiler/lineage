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

package com.laotie;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

import com.alibaba.druid.DbType;
import com.laotie.model.LineageGraph;
import com.laotie.model.LineageResult;
import com.laotie.model.metadata.Column;
import com.laotie.model.metadata.MetadataCatalog;
import com.laotie.model.metadata.MetadataContext;

/**
 * Specification for merging many parsed statements into one global lineage graph (the production
 * use case: scanning SQL logs for a whole database).
 *
 * <p>
 * Every statement reuses temp0 locally, so {@code LineageGraph.buildByResults} must namespace the
 * temporary targets per statement: {@code stmt0.temp0.c1}, {@code stmt1.temp0.c1}, ...
 * Temporary nodes and physical nodes must have different identities even when their display
 * strings collide (for example a real MySQL column {@code stmt0.temp0.c1}). Metadata-resolved
 * source columns keep their fully qualified table name, so same-named tables in different
 * instances/databases/schemas never collapse into one vertex.
 *
 * <p>
 * {@code buildByResults} must also expand {@code COLUMNS_INJECT} using each result's metadata
 * context, otherwise an implementation that ignores wildcards would pass.
 *
 * <p>
 * Expected to be red until {@code Column#temporary}, {@code LineageResult#getContext} and
 * {@code LineageGraph#buildByResults} exist.
 */
public class GlobalLineageTest {

    @Test
    public void temporaryTargetsAreNamespacedPerStatement() {
        MetadataContext context = mysqlContext(
                "CREATE TABLE db1.A (c1 INT); CREATE TABLE db2.A (c1 INT);");

        List<LineageResult> results = new DmlLineage(context).parseScript(
                "SELECT c1 FROM db1.A; SELECT c1 FROM db2.A;");
        assertEquals(2, results.size());

        LineageGraph graph = new LineageGraph();
        graph.buildByResults(results);

        assertEquals(columns("db1.A.c1"),
                graph.getSources(Column.temporary("stmt0.temp0.c1")));
        assertEquals(columns("db2.A.c1"),
                graph.getSources(Column.temporary("stmt1.temp0.c1")));
    }

    @Test
    public void temporaryColumnDoesNotCollideWithPhysicalColumn() {
        Column physical = new Column("stmt0.temp0.c1");
        Column temporary = Column.temporary("stmt0.temp0.c1");

        assertNotEquals(physical, temporary);
        assertTrue(temporary.isTemporary());

        Set<Column> columns = new HashSet<>();
        columns.add(physical);
        columns.add(temporary);
        assertEquals(2, columns.size());
    }

    @Test
    public void wildcardInsertChainsThroughToTheFinalPhysicalTarget() {
        MetadataContext context = mysqlContext(
                "CREATE TABLE db1.A (c1 INT);"
                        + "CREATE TABLE db1.B (c1 INT);"
                        + "CREATE TABLE db1.C (c1 INT);");

        List<LineageResult> results = new DmlLineage(context).parseScript(
                "INSERT INTO db1.B(c1) SELECT * FROM db1.A;"
                        + "INSERT INTO db1.C(c1) SELECT c1 FROM db1.B;");

        LineageGraph graph = new LineageGraph();
        graph.buildByResults(results);

        // db1.A.c1 -> temp -> db1.B.c1 -> temp -> db1.C.c1
        assertEquals(columns("db1.A.c1"), graph.getSources(new Column("db1.B.c1")));
        assertEquals(columns("db1.A.c1"), graph.getSources(new Column("db1.C.c1")));
    }

    @Test
    public void physicalColumnNamedLikeATemporaryDoesNotGetMerged() {
        MetadataContext context = mysqlContext(
                "CREATE TABLE db1.A (c1 INT);"
                        + "CREATE TABLE stmt0.temp0 (c1 INT);");

        List<LineageResult> results = new DmlLineage(context).parseScript(
                "SELECT c1 FROM db1.A; SELECT c1 FROM stmt0.temp0;");

        LineageGraph graph = new LineageGraph();
        graph.buildByResults(results);

        // The temporary target of the first statement and the real column of the second
        // statement share the display string "stmt0.temp0.c1" but must stay distinct.
        assertEquals(columns("db1.A.c1"),
                graph.getSources(Column.temporary("stmt0.temp0.c1")));
        assertEquals(columns("stmt0.temp0.c1"),
                graph.getSources(Column.temporary("stmt1.temp0.c1")));
        assertTrue(graph.getReverseGraph().containsVertex(new Column("stmt0.temp0.c1")));
    }

    @Test
    public void consecutiveStarProjectionsLandInSeparateTargetColumns() {
        MetadataContext context = mysqlContext(
                "CREATE TABLE db1.A (c1 INT);"
                        + "CREATE TABLE db2.B (c1 INT);"
                        + "CREATE TABLE db1.T (x INT, y INT);");

        List<LineageResult> results = new DmlLineage(context).parseScript(
                "INSERT INTO db1.T(x,y) SELECT A.*, B.* FROM db1.A, db2.B;");

        LineageGraph graph = new LineageGraph();
        graph.buildByResults(results);

        assertEquals(columns("db1.A.c1"), graph.getSources(new Column("db1.T.x")));
        assertEquals(columns("db2.B.c1"), graph.getSources(new Column("db1.T.y")));
    }

    @Test
    public void eachResultExpandsWithItsOwnSnapshot() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        // Register both physical targets before either snapshot is created.
        catalog.loadDdl("CREATE TABLE db1.A (c1 INT);"
                + "CREATE TABLE db1.X (c1 INT);"
                + "CREATE TABLE db1.Y (c2 INT);");
        MetadataContext firstContext = MetadataContext.of(catalog);
        LineageResult first = new DmlLineage(firstContext)
                .parse("INSERT INTO db1.X(c1) SELECT * FROM db1.A;");
        assertNull(first.getError());
        assertEquals(1, first.getMetadataVersion());

        catalog.loadDdl("CREATE TABLE db1.A (c2 INT);");
        MetadataContext secondContext = MetadataContext.of(catalog);
        LineageResult second = new DmlLineage(secondContext)
                .parse("INSERT INTO db1.Y(c2) SELECT * FROM db1.A;");
        assertNull(second.getError());
        assertEquals(2, second.getMetadataVersion());
        assertEquals(firstContext.getMetadataChecksum(), first.getMetadataChecksum());
        assertEquals(secondContext.getMetadataChecksum(), second.getMetadataChecksum());

        LineageGraph graph = new LineageGraph();
        graph.buildByResults(Arrays.asList(first, second));

        assertEquals(columns("db1.A.c1"), graph.getSources(new Column("db1.X.c1")));
        assertEquals(columns("db1.A.c2"), graph.getSources(new Column("db1.Y.c2")));
    }

    @Test
    public void starAndAliasProjectionDoNotCrossTargets() {
        MetadataContext context = mysqlContext(
                "CREATE TABLE db1.A (c1 INT);"
                        + "CREATE TABLE db2.B (c1 INT);"
                        + "CREATE TABLE db1.T (x INT, y INT);");

        List<LineageResult> results = new DmlLineage(context).parseScript(
                "INSERT INTO db1.T(x,y) SELECT A.*, B.c1 AS col1 FROM db1.A, db2.B;");

        LineageGraph graph = new LineageGraph();
        graph.buildByResults(results);

        assertEquals(columns("db1.A.c1"), graph.getSources(new Column("db1.T.x")));
        assertEquals(columns("db2.B.c1"), graph.getSources(new Column("db1.T.y")));
    }

    @Test
    public void eachQualifierLevelDefinesADistinctTable() {
        String[][] pairs = {
            {"ins1.db1.sch1.A", "ins2.db1.sch1.A"},
            {"ins1.db1.sch1.A", "ins1.db2.sch1.A"},
            {"ins1.db1.sch1.A", "ins1.db1.sch2.A"},
        };
        for (String[] pair : pairs) {
            assertDistinctSources(pair[0], pair[1]);
        }
    }

    @Test
    public void failedStatementsDoNotContributeVertices() {
        MetadataContext context = mysqlContext("CREATE TABLE db1.A (c1 INT);");

        List<LineageResult> results = new DmlLineage(context)
                .parseScript("SELECT c1, missing FROM db1.A;");

        assertEquals(1, results.size());
        assertTrue(results.get(0).getError() != null);

        LineageGraph graph = new LineageGraph();
        graph.buildByResults(results);

        assertTrue(graph.getReverseGraph().vertexSet().isEmpty());
    }

    private static void assertDistinctSources(String firstTable, String secondTable) {
        MetadataContext context = mysqlContext(
                "CREATE TABLE " + firstTable + " (c1 INT);"
                        + "CREATE TABLE " + secondTable + " (c1 INT);");

        List<LineageResult> results = new DmlLineage(context).parseScript(
                "SELECT c1 FROM " + firstTable + "; SELECT c1 FROM " + secondTable + ";");

        LineageGraph graph = new LineageGraph();
        graph.buildByResults(results);

        // Directly assert the two source vertices differ, so a lost level cannot be hidden by
        // constructing both expectations through the same buggy Column instance.
        assertNotEquals(new Column(firstTable + ".c1"), new Column(secondTable + ".c1"));

        assertEquals(columns(firstTable + ".c1"),
                graph.getSources(Column.temporary("stmt0.temp0.c1")));
        assertEquals(columns(secondTable + ".c1"),
                graph.getSources(Column.temporary("stmt1.temp0.c1")));
    }

    private static MetadataContext mysqlContext(String ddl) {
        return LineageTestSupport.mysql(ddl);
    }

    private static Set<Column> columns(String... names) {
        return LineageTestSupport.columns(names);
    }
}

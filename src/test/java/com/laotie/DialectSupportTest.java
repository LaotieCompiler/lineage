package com.laotie;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import com.alibaba.druid.DbType;
import com.laotie.model.LineageGraph;
import com.laotie.model.ScanResult;
import com.laotie.model.metadata.Column;
import com.laotie.model.metadata.MetadataCatalog;
import com.laotie.model.metadata.MetadataContext;
import com.laotie.model.metadata.MetadataMode;

/**
 * Hive and Spark statements use the same bounded metadata-free contract as the
 * other dialects: parsing is dialect-aware, unknown wildcards stay table-level.
 */
public class DialectSupportTest {

    private static LineageOptions optional() {
        return LineageOptions.of(MetadataMode.OPTIONAL, false);
    }

    private static void assertWildcardEdge(DbType dialect) {
        ScanResult result = LineageScanner.withoutMetadata(dialect, optional())
                .scan("INSERT INTO t SELECT * FROM a;");

        assertNull(result.getResults().get(0).getError());
        LineageGraph graph = result.getGraph();
        assertEquals(1, graph.getSources(new Column("t.*")).size());
        assertEquals(new Column("a.*"),
                graph.getSources(new Column("t.*")).iterator().next());
    }

    @Test
    public void hiveWildcardStaysTableLevel() {
        assertWildcardEdge(DbType.hive);
    }

    @Test
    public void sparkWildcardStaysTableLevel() {
        assertWildcardEdge(DbType.antspark);
    }

    @Test
    public void hiveUsesCatalogMetadata() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.hive);
        catalog.loadDdl("CREATE TABLE a (c1 INT, c2 INT);"
                + " CREATE TABLE t (c1 INT, c2 INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional())
                .scan("INSERT INTO t SELECT c2 FROM a;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new java.util.HashSet<>(java.util.Arrays.asList(
                new Column("a.c2"))),
                result.getGraph().getSources(new Column("t.c1")));
    }

    @Test
    public void sparkUsesCatalogMetadata() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.antspark);
        catalog.loadDdl("CREATE TABLE a (c1 INT); CREATE TABLE t (c1 INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional())
                .scan("INSERT INTO t SELECT c1 FROM a;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new java.util.HashSet<>(java.util.Arrays.asList(
                new Column("a.c1"))),
                result.getGraph().getSources(new Column("t.c1")));
    }
}

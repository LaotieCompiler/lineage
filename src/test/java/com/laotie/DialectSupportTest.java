package com.laotie;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashSet;

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

    @Test
    public void sparkInsertOverwriteUsesCatalogMetadata() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.antspark);
        catalog.loadDdl("CREATE TABLE a (c1 INT, c2 INT);"
                + " CREATE TABLE t (c1 INT, c2 INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional())
                .scan("INSERT OVERWRITE TABLE t SELECT c2 FROM a;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("a.c2"))),
                result.getGraph().getSources(new Column("t.c1")));
    }

    @Test
    public void hiveInsertOverwriteMapsDynamicPartitionColumns() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.hive);
        catalog.loadDdl("CREATE TABLE a (c1 INT, c2 INT, dt STRING);"
                + " CREATE TABLE t (c1 INT, c2 INT)"
                + " PARTITIONED BY (dt STRING);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT OVERWRITE TABLE t PARTITION (dt)"
                        + " SELECT c1, c2, dt AS src_day FROM a;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("a.c1"))),
                result.getGraph().getSources(new Column("t.c1")));
        assertEquals(new HashSet<>(Arrays.asList(new Column("a.c2"))),
                result.getGraph().getSources(new Column("t.c2")));
        assertEquals(new HashSet<>(Arrays.asList(new Column("a.dt"))),
                result.getGraph().getSources(new Column("t.dt")));
    }

    @Test
    public void mysqlPartitionSelectorIsNotATargetColumn() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.loadDdl("CREATE TABLE t (y INT)"
                + " PARTITION BY HASH(y) PARTITIONS 2;");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t PARTITION (p0) SELECT * FROM a;");

        assertEquals(0, result.getFailures().size());
        assertFalse(result.getGraph().getReverseGraph()
                .containsVertex(new Column("t.p0")));
        assertTrue(result.getGraph().getReverseGraph()
                .containsVertex(new Column("t.y")));
    }

    @Test
    public void quotedCommaTargetKeepsItsDynamicPartitionValue() {
        for (DbType dialect : Arrays.asList(DbType.hive, DbType.antspark)) {
            MetadataCatalog catalog = new MetadataCatalog(dialect);
            catalog.loadDdl("CREATE TABLE a (x INT, src_day STRING);"
                    + " CREATE TABLE t (`a,b` INT)"
                    + " PARTITIONED BY (dt STRING);");
            ScanResult result = LineageScanner.withMetadata(
                    MetadataContext.of(catalog), optional()).scan(
                    "INSERT OVERWRITE TABLE t PARTITION (dt)"
                            + " SELECT x, src_day FROM a;");

            assertEquals(0, result.getFailures().size());
            assertEquals(new HashSet<>(Arrays.asList(new Column("a.x"))),
                    result.getGraph().getSources(
                            new Column("t.\"a,b\"")));
            assertEquals(new HashSet<>(Arrays.asList(
                    new Column("a.src_day"))),
                    result.getGraph().getSources(new Column("t.dt")));
        }
    }

    @Test
    public void sparkRawStringPrefixKeepsStatementBoundary() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.antspark);
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT r'abc\\'; SELECT x FROM a;");

        assertEquals(2, result.getResults().size());
        assertNull(result.getResults().get(1).getError());
    }

    @Test
    public void quotedPartitionKeysKeepTheStaticColumnOut() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.antspark);
        catalog.loadDdl("CREATE TABLE a (x INT, src_day STRING) USING parquet;"
                + " CREATE TABLE t (x INT, `region` STRING, `dt` STRING)"
                + " USING parquet PARTITIONED BY (`region`, `dt`);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT OVERWRITE TABLE t"
                        + " PARTITION (`region` = 'US', `dt`)"
                        + " SELECT x, src_day FROM a;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("a.x"))),
                result.getGraph().getSources(new Column("t.x")));
        assertEquals(new HashSet<>(Arrays.asList(
                new Column("a.src_day"))),
                result.getGraph().getSources(new Column("t.dt")));
        assertFalse(result.getGraph().getReverseGraph()
                .containsVertex(new Column("t.region")));
    }

    @Test
    public void rawLiteralAdjacentToOrdinaryLiteralKeepsBoundary() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.antspark);
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT r'a''it\\'s;ok'; SELECT x FROM a;");

        assertEquals(2, result.getResults().size());
        assertNull(result.getResults().get(1).getError());
    }

    @Test
    public void quotedPartitionNameStaysDistinctFromADataColumn() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.antspark);
        catalog.loadDdl("CREATE TABLE a (x INT, src_region STRING,"
                + " src_day STRING) USING parquet;"
                + " CREATE TABLE t (x INT, region STRING, `\"REGION\"` STRING,"
                + " dt STRING) USING parquet"
                + " PARTITIONED BY (`\"REGION\"`, dt);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT OVERWRITE TABLE t"
                        + " PARTITION (`\"REGION\"` = 'US', dt)"
                        + " SELECT x, src_region, src_day FROM a;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(
                new Column("a.src_region"))),
                result.getGraph().getSources(new Column("t.region")));
        assertEquals(new HashSet<>(Arrays.asList(
                new Column("a.src_day"))),
                result.getGraph().getSources(new Column("t.dt")));
    }

    @Test
    public void hivePartitionColumnsAreReadableFields() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.hive);
        catalog.loadDdl("CREATE TABLE a (x INT, src_day STRING);"
                + " CREATE TABLE t (x INT) PARTITIONED BY (dt STRING)"
                + " STORED AS PARQUET;");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT OVERWRITE TABLE t PARTITION (dt)"
                        + " SELECT x, src_day FROM a;"
                        + " SELECT dt FROM t;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(
                new Column("a.src_day"))),
                result.getGraph().getSources(new Column("t.dt")));
        assertTrue(result.getGraph().getReverseGraph().containsEdge(
                Column.temporary("stmt1.temp0.dt"), new Column("t.dt")));
    }

    @Test
    public void turkishLocaleDoesNotChangePartitionFolding() {
        java.util.Locale previous = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(new java.util.Locale("tr", "TR"));
            MetadataCatalog catalog = new MetadataCatalog(DbType.antspark);
            catalog.loadDdl("CREATE TABLE a (x INT, src_day STRING)"
                    + " USING parquet;"
                    + " CREATE TABLE t (x INT, id STRING, dt STRING)"
                    + " USING parquet PARTITIONED BY (id, dt);");
            ScanResult result = LineageScanner.withMetadata(
                    MetadataContext.of(catalog), optional()).scan(
                    "INSERT OVERWRITE TABLE t"
                            + " PARTITION (ID = '1', dt)"
                            + " SELECT x, src_day FROM a;");

            assertEquals(0, result.getFailures().size());
            assertEquals(new HashSet<>(Arrays.asList(
                    new Column("a.src_day"))),
                    result.getGraph().getSources(new Column("t.dt")));
            assertFalse(result.getGraph().getReverseGraph()
                    .containsVertex(new Column("t.id")));
        } finally {
            java.util.Locale.setDefault(previous);
        }
    }

    @Test
    public void sparkQuotedReadResolvesCaseInsensitively() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.antspark);
        catalog.loadDdl("CREATE TABLE a (x INT, src_day STRING)"
                + " USING parquet;"
                + " CREATE TABLE t (x INT, day STRING)"
                + " USING parquet PARTITIONED BY (day);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT OVERWRITE TABLE t PARTITION (day)"
                        + " SELECT x, src_day FROM a;"
                        + " SELECT `DAY` AS copied FROM t;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(
                new Column("a.src_day"))),
                result.getGraph().getSources(
                        Column.temporary("stmt1.temp0.copied")));
        assertFalse(result.getGraph().getReverseGraph()
                .containsVertex(new Column("t.DAY")));
    }

    @Test
    public void sparkDerivedQuotedReadIsCaseInsensitive() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.antspark);
        catalog.loadDdl("CREATE TABLE t (day STRING) USING parquet;");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT q.`DAY` AS copied"
                        + " FROM (SELECT day AS `day` FROM t) AS q;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("t.day"))),
                result.getGraph().getSources(
                        Column.temporary("stmt0.temp0.copied")));
    }

    @Test
    public void unicodePartitionKeyMatchesCaseInsensitively() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.antspark);
        catalog.loadDdl("CREATE TABLE a (x INT, src_day STRING)"
                + " USING parquet;"
                + " CREATE TABLE t (x INT, id STRING, dt STRING)"
                + " USING parquet PARTITIONED BY (id, dt);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT OVERWRITE TABLE t"
                        + " PARTITION (`\u0130D` = '1', dt)"
                        + " SELECT x, src_day FROM a;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(
                new Column("a.src_day"))),
                result.getGraph().getSources(new Column("t.dt")));
        assertFalse(result.getGraph().getReverseGraph()
                .containsVertex(new Column("t.id")));
    }

    @Test
    public void hiveAndSparkSplitEscapedSemicolonLiterals() {
        for (DbType dialect : Arrays.asList(DbType.hive, DbType.antspark)) {
            MetadataCatalog catalog = new MetadataCatalog(dialect);
            catalog.loadDdl("CREATE TABLE a (x STRING);"
                    + " CREATE TABLE t (x STRING);");
            ScanResult result = LineageScanner.withMetadata(
                    MetadataContext.of(catalog), optional()).scan(
                    "INSERT OVERWRITE TABLE t"
                            + " SELECT concat(x, 'it\\'s;ok') FROM a;"
                            + " SELECT x FROM a;");

            assertEquals(0, result.getFailures().size());
            assertEquals(new HashSet<>(Arrays.asList(new Column("a.x"))),
                    result.getGraph().getSources(new Column("t.x")));
        }
    }

    @Test
    public void sparkOverwriteHonorsStaticAndDynamicPartitions() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.antspark);
        catalog.loadDdl("CREATE TABLE a (payload INT, src_day STRING)"
                + " USING parquet;"
                + " CREATE TABLE t (payload INT, region STRING, dt STRING)"
                + " USING parquet PARTITIONED BY (region, dt);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT OVERWRITE TABLE t PARTITION (region = 'US', dt)"
                        + " SELECT payload, src_day FROM a;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("a.payload"))),
                result.getGraph().getSources(new Column("t.payload")));
        assertEquals(new HashSet<>(Arrays.asList(new Column("a.src_day"))),
                result.getGraph().getSources(new Column("t.dt")));
        assertFalse(result.getGraph().getReverseGraph()
                .containsVertex(new Column("t.region")));
    }

    @Test
    public void hiveBacktickQualifiedNamesUseCatalog() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.hive);
        catalog.loadDdl("CREATE TABLE `db`.`a` (`c1` INT, `c2` INT);"
                + " CREATE TABLE `db`.`t` (`c1` INT, `c2` INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO `db`.`t` SELECT `c2` FROM `db`.`a`;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("db.a.c2"))),
                result.getGraph().getSources(new Column("db.t.c1")));
    }

    @Test
    public void sparkBacktickQualifiedNamesUseCatalog() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.antspark);
        catalog.loadDdl("CREATE TABLE `db`.`a` (`c1` INT, `c2` INT);"
                + " CREATE TABLE `db`.`t` (`c1` INT, `c2` INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO `db`.`t` SELECT `c2` FROM `db`.`a`;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("db.a.c2"))),
                result.getGraph().getSources(new Column("db.t.c1")));
    }

    @Test
    public void hiveStoredAsDdlUsesCatalog() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.hive);
        catalog.loadDdl("CREATE TABLE `db`.`a` (`c1` INT, `c2` STRING)"
                + " STORED AS PARQUET;"
                + " CREATE TABLE `db`.`t` (`c1` STRING) STORED AS PARQUET;");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO `db`.`t` SELECT `c2` FROM `db`.`a`;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("db.a.c2"))),
                result.getGraph().getSources(new Column("db.t.c1")));
    }

    @Test
    public void sparkUsingDdlUsesCatalog() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.antspark);
        catalog.loadDdl("CREATE TABLE `db`.`a` (`c1` INT, `c2` STRING)"
                + " USING parquet;"
                + " CREATE TABLE `db`.`t` (`c1` STRING) USING parquet;");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO `db`.`t` SELECT `c2` FROM `db`.`a`;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("db.a.c2"))),
                result.getGraph().getSources(new Column("db.t.c1")));
    }
}

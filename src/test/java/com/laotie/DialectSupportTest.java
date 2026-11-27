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
    public void sparkJoinUsingQuotedUppercaseColumns() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.antspark);
        catalog.loadDdl("CREATE TABLE a (`DAY` STRING) USING parquet;"
                + " CREATE TABLE b (`DAY` STRING) USING parquet;");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT * FROM a JOIN b USING (`DAY`);");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(
                new Column("a.DAY"), new Column("b.DAY"))),
                result.getGraph().getSources(
                        Column.temporary("stmt0.temp0.DAY")));
    }

    @Test
    public void supplementaryUnicodePartitionKeyMatches() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.antspark);
        catalog.loadDdl("CREATE TABLE a (x INT, src_day STRING)"
                + " USING parquet;"
                + " CREATE TABLE t (x INT, `\uD801\uDC28` STRING, dt STRING)"
                + " USING parquet PARTITIONED BY (`\uD801\uDC28`, dt);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT OVERWRITE TABLE t"
                        + " PARTITION (`\uD801\uDC00` = '1', dt)"
                        + " SELECT x, src_day FROM a;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(
                new Column("a.src_day"))),
                result.getGraph().getSources(new Column("t.dt")));
        assertFalse(result.getGraph().getReverseGraph().containsVertex(
                new Column("t.\uD801\uDC28")));
    }

    @Test
    public void sparkJoinUsingLiteralQuotedCatalogNames() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.antspark);
        catalog.loadDdl("CREATE TABLE a (`\"KEY\"` STRING) USING parquet;"
                + " CREATE TABLE b (`\"KEY\"` STRING) USING parquet;");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT * FROM a JOIN b USING (`\"KEY\"`);");

        assertEquals(0, result.getFailures().size());
        Column target = new Column("stmt0.temp0", "\"KEY\"");
        target.setTemporary(true);
        assertEquals(new HashSet<>(Arrays.asList(
                new Column("a", "\"KEY\""), new Column("b", "\"KEY\""))),
                result.getGraph().getSources(target));
    }

    @Test
    public void supplementaryUnicodeDirectReadMatchesJoin() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.antspark);
        catalog.loadDdl("CREATE TABLE a (`\uD801\uDC28` STRING)"
                + " USING parquet;"
                + " CREATE TABLE b (`\uD801\uDC28` STRING)"
                + " USING parquet;");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT a.`\uD801\uDC00` AS copied FROM a;"
                        + " SELECT * FROM a JOIN b USING (`\uD801\uDC00`);");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(
                new Column("a", "\uD801\uDC28"))),
                result.getGraph().getSources(
                        Column.temporary("stmt0.temp0.copied")));
        Column joined = new Column("stmt1.temp0", "\uD801\uDC28");
        joined.setTemporary(true);
        assertEquals(new HashSet<>(Arrays.asList(
                new Column("a", "\uD801\uDC28"),
                new Column("b", "\uD801\uDC28"))),
                result.getGraph().getSources(joined));
    }

    @Test
    public void sparkDerivedLiteralQuotedNameStaysDistinct() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.antspark);
        catalog.loadDdl("CREATE TABLE a (k STRING, `\"k\"` STRING)"
                + " USING parquet;");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SELECT q.k AS regular, q.`\"k\"` AS quoted"
                        + " FROM (SELECT * FROM a) AS q;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("a.k"))),
                result.getGraph().getSources(
                        Column.temporary("stmt0.temp0.regular")));
        assertEquals(new HashSet<>(Arrays.asList(
                new Column("a", "\"k\""))),
                result.getGraph().getSources(
                        Column.temporary("stmt0.temp0.quoted")));
    }

    @Test
    public void postgresUnquotedSupplementaryDoesNotMatchQuotedCase() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE a (\"\uD801\uDC00\" INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog),
                LineageOptions.of(MetadataMode.STRICT, false)).scan(
                "SELECT a.\uD801\uDC28 AS copied FROM a;");

        assertTrue(result.getResults().get(0).getError()
                instanceof com.laotie.exception.ColumnNotFoundException);
    }

    @Test
    public void turkishLocalePostgresFoldsIdentifiersWithRoot() {
        java.util.Locale previous = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(new java.util.Locale("tr", "TR"));
            MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
            catalog.loadDdl("CREATE TABLE a (\"id\" INT);");
            ScanResult result = LineageScanner.withMetadata(
                    MetadataContext.of(catalog), optional()).scan(
                    "SELECT a.ID AS copied FROM a;");

            assertEquals(0, result.getFailures().size());
            assertEquals(new HashSet<>(Arrays.asList(new Column("a.id"))),
                    result.getGraph().getSources(
                            Column.temporary("stmt0.temp0.copied")));
        } finally {
            java.util.Locale.setDefault(previous);
        }
    }

    @Test
    public void sparkUncatalogedLiteralQuotedNameStaysDistinct() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.antspark, optional()).scan(
                "SELECT a.k AS regular, a.`\"k\"` AS quoted FROM a;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("a.k"))),
                result.getGraph().getSources(
                        Column.temporary("stmt0.temp0.regular")));
        assertEquals(new HashSet<>(Arrays.asList(
                new Column("a", "\"k\""))),
                result.getGraph().getSources(
                        Column.temporary("stmt0.temp0.quoted")));
    }

    @Test
    public void turkishLocaleUncatalogedWriteAndReadAgree() {
        java.util.Locale previous = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(new java.util.Locale("tr", "TR"));
            ScanResult result = LineageScanner.withoutMetadata(
                    DbType.postgresql, optional()).scan(
                    "INSERT INTO t (ID) SELECT a.ID FROM a;"
                            + " SELECT t.id AS copied FROM t;");

            assertEquals(0, result.getFailures().size());
            assertEquals(new HashSet<>(Arrays.asList(new Column("a.id"))),
                    result.getGraph().getSources(
                            Column.temporary("stmt1.temp0.copied")));
        } finally {
            java.util.Locale.setDefault(previous);
        }
    }

    @Test
    public void sparkUncatalogedQuotedWriteAndReadAgree() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.antspark, optional()).scan(
                "INSERT OVERWRITE TABLE t (`\"k\"`)"
                        + " SELECT a.`\"k\"` FROM a;"
                        + " SELECT t.`\"k\"` AS copied FROM t;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(
                new Column("a", "\"k\""))),
                result.getGraph().getSources(
                        Column.temporary("stmt1.temp0.copied")));
    }

    @Test
    public void quotedInsertTargetUsesCatalogSpelling() {
        for (DbType dialect : Arrays.asList(DbType.hive, DbType.antspark)) {
            MetadataCatalog catalog = new MetadataCatalog(dialect);
            catalog.loadDdl("CREATE TABLE t (k STRING) USING parquet;");
            ScanResult result = LineageScanner.withMetadata(
                    MetadataContext.of(catalog), optional()).scan(
                    "INSERT OVERWRITE TABLE t (`K`)"
                            + " SELECT * FROM missing;"
                            + " SELECT t.k AS copied FROM t;");

            assertEquals(0, result.getFailures().size());
            assertEquals(new HashSet<>(Arrays.asList(
                    new Column("missing.*"))),
                    result.getGraph().getSources(
                            Column.temporary("stmt1.temp0.copied")));
            assertFalse(result.getGraph().getReverseGraph()
                    .containsVertex(new Column("t.K")));
        }
    }

    @Test
    public void dynamicPartitionTargetUsesCatalogSpelling() {
        for (DbType dialect : Arrays.asList(DbType.hive, DbType.antspark)) {
            MetadataCatalog catalog = new MetadataCatalog(dialect);
            catalog.loadDdl("CREATE TABLE t (k STRING, dt STRING)"
                    + " USING parquet PARTITIONED BY (dt);");
            ScanResult result = LineageScanner.withMetadata(
                    MetadataContext.of(catalog), optional()).scan(
                    "INSERT OVERWRITE TABLE t PARTITION (`DT`)"
                            + " SELECT * FROM missing;"
                            + " SELECT t.dt AS copied FROM t;");

            assertEquals(0, result.getFailures().size());
            assertEquals(new HashSet<>(Arrays.asList(
                    new Column("missing.*"))),
                    result.getGraph().getSources(
                            Column.temporary("stmt1.temp0.copied")));
            assertFalse(result.getGraph().getReverseGraph()
                    .containsVertex(new Column("t.DT")));
        }
    }

    @Test
    public void sparkReorderedPartitionTargetsFollowTableOrder() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.antspark);
        catalog.loadDdl("CREATE TABLE a (x STRING, src_region STRING,"
                + " src_day STRING) USING parquet;"
                + " CREATE TABLE t (k STRING, region STRING, dt STRING)"
                + " USING parquet PARTITIONED BY (region, dt);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT OVERWRITE TABLE t PARTITION (dt, region)"
                        + " SELECT x, src_region, src_day FROM a;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("a.x"))),
                result.getGraph().getSources(new Column("t.k")));
        assertEquals(new HashSet<>(Arrays.asList(
                new Column("a.src_region"))),
                result.getGraph().getSources(new Column("t.region")));
        assertEquals(new HashSet<>(Arrays.asList(
                new Column("a.src_day"))),
                result.getGraph().getSources(new Column("t.dt")));
    }

    @Test
    public void sparkUncatalogedCaseVariantWriteReadAgree() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.antspark, optional()).scan(
                "INSERT OVERWRITE TABLE t (`K`) SELECT a.y FROM a;"
                        + " SELECT t.k AS copied FROM t;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("a.y"))),
                result.getGraph().getSources(
                        Column.temporary("stmt1.temp0.copied")));
        assertFalse(result.getGraph().getReverseGraph()
                .containsVertex(new Column("t.K")));
    }

    @Test
    public void uncatalogedCaseVariantResolvesInScalarAndJoin() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.antspark, optional()).scan(
                "INSERT OVERWRITE TABLE t (`K`) SELECT a.y FROM a;"
                        + " SELECT (SELECT t.K) AS copied FROM t;"
                        + " SELECT j.K AS joined"
                        + " FROM (t JOIN b USING (K)) AS j;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("a.y"))),
                result.getGraph().getSources(
                        Column.temporary("stmt1.temp0.copied")));
        assertEquals(new HashSet<>(Arrays.asList(
                new Column("a.y"), new Column("b.k"))),
                result.getGraph().getSources(
                        Column.temporary("stmt2.temp0.joined")));
    }

    @Test
    public void uncatalogedBacktickedCaseVariantResolvesInScalarAndJoin() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.antspark, optional()).scan(
                "INSERT OVERWRITE TABLE t (`K`) SELECT a.y FROM a;"
                        + " SELECT (SELECT t.`K`) AS copied FROM t;"
                        + " SELECT j.K AS joined"
                        + " FROM (t JOIN b USING (`K`)) AS j;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("a.y"))),
                result.getGraph().getSources(
                        Column.temporary("stmt1.temp0.copied")));
        assertEquals(new HashSet<>(Arrays.asList(
                new Column("a.y"), new Column("b.k"))),
                result.getGraph().getSources(
                        Column.temporary("stmt2.temp0.joined")));
    }

    @Test
    public void uncatalogedLiteralQuotedNameResolvesInScalarAndJoin() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.antspark, optional()).scan(
                "INSERT OVERWRITE TABLE t (`\"k\"`)"
                        + " SELECT a.y FROM a;"
                        + " SELECT (SELECT t.`\"k\"`) AS copied FROM t;"
                        + " SELECT j.`\"k\"` AS joined"
                        + " FROM (t JOIN b USING (`\"k\"`)) AS j;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("a.y"))),
                result.getGraph().getSources(
                        Column.temporary("stmt1.temp0.copied")));
        assertEquals(new HashSet<>(Arrays.asList(
                new Column("a.y"), new Column("b", "\"k\""))),
                result.getGraph().getSources(
                        Column.temporary("stmt2.temp0.joined")));
    }

    @Test
    public void spacePaddedQuotedNameSurvivesScalarAndJoin() {
        for (DbType dialect : Arrays.asList(DbType.hive, DbType.antspark)) {
            ScanResult result = LineageScanner.withoutMetadata(
                    dialect, optional()).scan(
                    "INSERT OVERWRITE TABLE t (k, ` k `)"
                            + " SELECT a.x, a.y FROM a;"
                            + " SELECT t.` k ` AS direct FROM t;"
                            + " SELECT (SELECT t.` k `) AS copied FROM t;"
                            + " SELECT j.` k ` AS joined"
                            + " FROM (t JOIN b USING (` k `)) AS j;");

            assertEquals(0, result.getFailures().size());
            assertEquals(new HashSet<>(Arrays.asList(new Column("a.y"))),
                    result.getGraph().getSources(
                            Column.temporary("stmt1.temp0.direct")));
            assertEquals(new HashSet<>(Arrays.asList(new Column("a.y"))),
                    result.getGraph().getSources(
                            Column.temporary("stmt2.temp0.copied")));
            assertEquals(new HashSet<>(Arrays.asList(
                    new Column("a.y"), new Column("b", " k "))),
                    result.getGraph().getSources(
                            Column.temporary("stmt3.temp0.joined")));
        }
    }

    @Test
    public void literalBackticksInDoubleQuotedNameSurviveScalar() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.antspark, optional()).scan(
                "SET spark.sql.ansi.enabled = true;"
                        + " SET spark.sql.ansi.doubleQuotedIdentifiers = true;"
                        + " SELECT a.\"`K`\" AS direct,"
                        + " (SELECT a.\"`K`\") AS copied FROM a;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(
                new Column("a", "`k`"))),
                result.getGraph().getSources(
                        Column.temporary("stmt2.temp0.direct")));
        assertEquals(new HashSet<>(Arrays.asList(
                new Column("a", "`k`"))),
                result.getGraph().getSources(
                        Column.temporary("stmt2.temp0.copied")));
    }

    @Test
    public void ansiDoubleQuotedInsertColumnIsAnIdentifier() {
        for (DbType dialect : Arrays.asList(DbType.hive, DbType.antspark)) {
            ScanResult result = LineageScanner.withoutMetadata(
                    dialect, optional()).scan(
                    "SET spark.sql.ansi.enabled = true;"
                            + " SET spark.sql.ansi.doubleQuotedIdentifiers = true;"
                            + " INSERT OVERWRITE TABLE t (\"K\")"
                            + " SELECT a.y FROM a;"
                            + " SELECT t.\"K\" AS copied FROM t;");

            assertEquals(0, result.getFailures().size());
            assertEquals(new HashSet<>(Arrays.asList(new Column("a.y"))),
                    result.getGraph().getSources(
                            Column.temporary("stmt3.temp0.copied")));
        }
    }

    @Test
    public void ansiDoubleQuotedLiteralNameSurvivesInsert() {
        String prefix = "SET spark.sql.ansi.enabled = true;"
                + " SET spark.sql.ansi.doubleQuotedIdentifiers = true;";
        for (String column : Arrays.asList("\"`K`\"", "\" K \"")) {
            ScanResult result = LineageScanner.withoutMetadata(
                    DbType.antspark, optional()).scan(
                    prefix + " INSERT OVERWRITE TABLE t (" + column + ")"
                            + " SELECT a.y FROM a;"
                            + " SELECT t." + column + " AS copied FROM t;");

            assertEquals(0, result.getFailures().size());
            assertEquals(new HashSet<>(Arrays.asList(new Column("a.y"))),
                    result.getGraph().getSources(
                            Column.temporary("stmt3.temp0.copied")));
        }
    }

    @Test
    public void singleQuotedInsertColumnIsRejected() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.antspark, optional()).scan(
                "INSERT OVERWRITE TABLE t ('K') SELECT a.y FROM a;"
                        + " SELECT t.k AS copied FROM t;");

        assertTrue(result.getResults().get(0).getError()
                instanceof com.laotie.exception.ColumnNotFoundException);
    }

    @Test
    public void singleQuotedInsertColumnIsRejectedInAnsiMode() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.antspark, optional()).scan(
                "SET spark.sql.ansi.enabled = true;"
                        + " SET spark.sql.ansi.doubleQuotedIdentifiers = true;"
                        + " INSERT OVERWRITE TABLE t ('K')"
                        + " SELECT a.y FROM a;"
                        + " SELECT t.k AS copied FROM t;");

        assertTrue(result.getResults().get(2).getError()
                instanceof com.laotie.exception.ColumnNotFoundException);
    }

    @Test
    public void doubleQuotedInsertColumnRequiresAnsiMode() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.antspark, optional()).scan(
                "SET spark.sql.ansi.enabled = false;"
                        + " SET spark.sql.ansi.doubleQuotedIdentifiers = true;"
                        + " INSERT OVERWRITE TABLE t (\"K\")"
                        + " SELECT a.y FROM a;"
                        + " SELECT t.k AS copied FROM t;");

        assertTrue(result.getResults().get(2).getError()
                instanceof com.laotie.exception.ColumnNotFoundException);
    }

    @Test
    public void commentDoesNotSupplyInsertQuoteStyle() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.antspark, optional()).scan(
                "SET spark.sql.ansi.enabled = true;"
                        + " SET spark.sql.ansi.doubleQuotedIdentifiers = true;"
                        + " INSERT OVERWRITE TABLE t ('K')"
                        + " /* (\"fake\") */ SELECT a.y FROM a;"
                        + " SELECT t.k AS copied FROM t;");

        assertTrue(result.getResults().get(2).getError()
                instanceof com.laotie.exception.ColumnNotFoundException);
    }

    @Test
    public void leadingCteKeepsDoubleQuotedInsertTarget() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.antspark, optional()).scan(
                "SET spark.sql.ansi.enabled = true;"
                        + " SET spark.sql.ansi.doubleQuotedIdentifiers = true;"
                        + " WITH q AS (SELECT a.y FROM a)"
                        + " INSERT INTO t (\"K\") SELECT q.y FROM q;"
                        + " SELECT t.k AS copied FROM t;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("a.y"))),
                result.getGraph().getSources(
                        Column.temporary("stmt3.temp0.copied")));
    }

    @Test
    public void commentInsideTargetListKeepsQuoteStyle() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.antspark, optional()).scan(
                "SET spark.sql.ansi.enabled = true;"
                        + " SET spark.sql.ansi.doubleQuotedIdentifiers = true;"
                        + " INSERT OVERWRITE TABLE t (/* target */ \"K\")"
                        + " SELECT a.y FROM a;"
                        + " SELECT t.k AS copied FROM t;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("a.y"))),
                result.getGraph().getSources(
                        Column.temporary("stmt3.temp0.copied")));
    }

    @Test
    public void parenthesizedSourceQueryDoesNotReplaceTargetList() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.antspark, optional()).scan(
                "SET spark.sql.ansi.enabled = true;"
                        + " SET spark.sql.ansi.doubleQuotedIdentifiers = true;"
                        + " INSERT INTO t (\"K\") (SELECT a.y FROM a);"
                        + " SELECT t.k AS copied FROM t;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("a.y"))),
                result.getGraph().getSources(
                        Column.temporary("stmt3.temp0.copied")));
    }

    @Test
    public void partitionKeywordWordDoesNotHideTargetList() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.antspark, optional()).scan(
                "SET spark.sql.ansi.enabled = true;"
                        + " SET spark.sql.ansi.doubleQuotedIdentifiers = true;"
                        + " SET spark.sql.ansi.enforceReservedKeywords = false;"
                        + " INSERT OVERWRITE TABLE t"
                        + " PARTITION (with='fixed') (\"K\")"
                        + " SELECT a.y FROM a;"
                        + " SELECT t.k AS copied FROM t;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("a.y"))),
                result.getGraph().getSources(
                        Column.temporary("stmt4.temp0.copied")));
    }

    @Test
    public void doublyParenthesizedSourceQueryKeepsTargetList() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.antspark, optional()).scan(
                "SET spark.sql.ansi.enabled = true;"
                        + " SET spark.sql.ansi.doubleQuotedIdentifiers = true;"
                        + " SET spark.sql.ansi.enforceReservedKeywords = false;"
                        + " INSERT INTO t (\"K\") ((SELECT a.y FROM a));"
                        + " SELECT t.k AS copied FROM t;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("a.y"))),
                result.getGraph().getSources(
                        Column.temporary("stmt4.temp0.copied")));
    }

    @Test
    public void tableNamedPartitionKeepsItsTargetList() {
        String prefix = "SET spark.sql.ansi.enabled = true;"
                + " SET spark.sql.ansi.doubleQuotedIdentifiers = true;"
                + " SET spark.sql.ansi.enforceReservedKeywords = false;";
        for (String table : Arrays.asList("partition", "s.partition")) {
            ScanResult result = LineageScanner.withoutMetadata(
                    DbType.antspark, optional()).scan(
                    prefix + " INSERT INTO " + table + " (\"K\")"
                            + " SELECT a.y FROM a;"
                            + " SELECT k AS copied FROM " + table + ";");

            assertEquals(0, result.getFailures().size());
            assertEquals(new HashSet<>(Arrays.asList(new Column("a.y"))),
                    result.getGraph().getSources(
                            Column.temporary("stmt4.temp0.copied")));
        }
    }

    @Test
    public void insertHeaderHandlesQuotedPartsAndModifierNames() {
        String prefix = "SET spark.sql.ansi.enabled = true;"
                + " SET spark.sql.ansi.doubleQuotedIdentifiers = true;"
                + " SET spark.sql.ansi.enforceReservedKeywords = false;";
        String[] bodies = {
            "INSERT INTO s.`t` (\"K\")"
                    + " SELECT max(a.y) AS y FROM a;"
                    + " SELECT k AS copied FROM s.`t`;",
            "INSERT OVERWRITE TABLE ignore"
                    + " PARTITION (with='fixed') (\"K\")"
                    + " SELECT a.y FROM a;"
                    + " SELECT k AS copied FROM ignore;",
            "INSERT OVERWRITE `t` PARTITION (with='fixed') (\"K\")"
                    + " SELECT a.y FROM a;"
                    + " SELECT k AS copied FROM `t`;"
        };
        for (String body : bodies) {
            ScanResult result = LineageScanner.withoutMetadata(
                    DbType.antspark, optional()).scan(prefix + " " + body);

            assertEquals(0, result.getFailures().size());
            assertEquals(new HashSet<>(Arrays.asList(new Column("a.y"))),
                    result.getGraph().getSources(
                            Column.temporary("stmt4.temp0.copied")));
        }
    }

    @Test
    public void underscoreCteNameDoesNotHideInsert() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.antspark, optional()).scan(
                "SET spark.sql.ansi.enabled = true;"
                        + " SET spark.sql.ansi.doubleQuotedIdentifiers = true;"
                        + " WITH _insert AS (SELECT a.y FROM a)"
                        + " INSERT INTO t (\"K\") SELECT y FROM _insert;"
                        + " SELECT k AS copied FROM t;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("a.y"))),
                result.getGraph().getSources(
                        Column.temporary("stmt3.temp0.copied")));
    }

    @Test
    public void unrelatedSettingsDoNotEnableAnsiQuoting() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.antspark, optional()).scan(
                "SET spark.sql.ansi.enabled = false;"
                        + " SET spark.sql.ansi.doubleQuotedIdentifiers = false;"
                        + " SET other.ansi.enabled = true;"
                        + " SET other.doubleQuotedIdentifiers = true;"
                        + " INSERT OVERWRITE TABLE t (\"K\")"
                        + " SELECT a.y FROM a;"
                        + " SELECT k AS copied FROM t;");

        assertTrue(result.getResults().get(4).getError()
                instanceof com.laotie.exception.ColumnNotFoundException);
    }

    @Test
    public void underscoreTableNameKeepsItsTargetList() {
        String prefix = "SET spark.sql.ansi.enabled = true;"
                + " SET spark.sql.ansi.doubleQuotedIdentifiers = true;";
        String[] bodies = {
            "INSERT INTO _t (\"K\")"
                    + " SELECT a.y FROM a WHERE (a.y IS NOT NULL);"
                    + " SELECT k AS copied FROM _t;",
            "INSERT INTO s._t (\"K\") SELECT a.y FROM a;"
                    + " SELECT k AS copied FROM s._t;"
        };
        for (String body : bodies) {
            ScanResult result = LineageScanner.withoutMetadata(
                    DbType.antspark, optional()).scan(prefix + " " + body);

            assertEquals(0, result.getFailures().size());
            assertEquals(new HashSet<>(Arrays.asList(new Column("a.y"))),
                    result.getGraph().getSources(
                            Column.temporary("stmt3.temp0.copied")));
        }
    }

    @Test
    public void digitLeadingTableNameKeepsItsTargetList() {
        String prefix = "SET spark.sql.ansi.enabled = true;"
                + " SET spark.sql.ansi.doubleQuotedIdentifiers = true;";
        String[] bodies = {
            "INSERT INTO 2t (\"K\")"
                    + " SELECT a.y FROM a WHERE (a.y IS NOT NULL);"
                    + " SELECT k AS copied FROM 2t;",
            "INSERT INTO s.2t (\"K\") SELECT a.y FROM a;"
                    + " SELECT k AS copied FROM s.2t;"
        };
        for (String body : bodies) {
            ScanResult result = LineageScanner.withoutMetadata(
                    DbType.antspark, optional()).scan(prefix + " " + body);

            assertEquals(0, result.getFailures().size());
            assertEquals(new HashSet<>(Arrays.asList(new Column("a.y"))),
                    result.getGraph().getSources(
                            Column.temporary("stmt3.temp0.copied")));
        }
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

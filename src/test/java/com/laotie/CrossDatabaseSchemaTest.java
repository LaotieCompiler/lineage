package com.laotie;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.HashSet;

import org.junit.Test;

import com.alibaba.druid.DbType;
import com.laotie.model.ScanResult;
import com.laotie.model.metadata.Column;
import com.laotie.model.metadata.MetadataCatalog;
import com.laotie.model.metadata.MetadataContext;
import com.laotie.model.metadata.MetadataMode;

/**
 * Lineage across databases and schemas, including default-session resolution.
 */
public class CrossDatabaseSchemaTest {

    private static LineageOptions optional() {
        return LineageOptions.of(MetadataMode.OPTIONAL, false);
    }

    @Test
    public void mysqlJoinsAcrossDatabases() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.loadDdl("CREATE TABLE db1.a (c1 INT);"
                + " CREATE TABLE db2.b (c2 INT);"
                + " CREATE TABLE db3.t (c1 INT, c2 INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO db3.t SELECT db1.a.c1, db2.b.c2"
                        + " FROM db1.a JOIN db2.b ON db1.a.c1 = db2.b.c2;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("db1.a.c1"))),
                result.getGraph().getSources(new Column("db3.t.c1")));
        assertEquals(new HashSet<>(Arrays.asList(new Column("db2.b.c2"))),
                result.getGraph().getSources(new Column("db3.t.c2")));
    }

    @Test
    public void postgresJoinsAcrossSchemas() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE s1.a (c1 INT);"
                + " CREATE TABLE s2.b (c2 INT);"
                + " CREATE TABLE s3.t (c1 INT, c2 INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO s3.t SELECT s1.a.c1, s2.b.c2"
                        + " FROM s1.a JOIN s2.b ON s1.a.c1 = s2.b.c2;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("s1.a.c1"))),
                result.getGraph().getSources(new Column("s3.t.c1")));
        assertEquals(new HashSet<>(Arrays.asList(new Column("s2.b.c2"))),
                result.getGraph().getSources(new Column("s3.t.c2")));
    }

    @Test
    public void defaultDatabaseResolvesUnqualifiedNames() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.loadDdl("CREATE TABLE db1.a (c1 INT);"
                + " CREATE TABLE db1.t (c1 INT);");
        catalog.setDefaultDatabase("db1");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO t SELECT c1 FROM a;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("db1.a.c1"))),
                result.getGraph().getSources(new Column("db1.t.c1")));
    }

    @Test
    public void useSwitchesTheDefaultDatabase() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.loadDdl("CREATE TABLE db1.a (c1 INT);"
                + " CREATE TABLE db2.a (c1 INT);"
                + " CREATE TABLE db2.t (c1 INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "USE db2; INSERT INTO t SELECT c1 FROM a;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("db2.a.c1"))),
                result.getGraph().getSources(new Column("db2.t.c1")));
    }

    @Test
    public void searchPathSwitchesTheDefaultSchema() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE s1.a (c1 INT);"
                + " CREATE TABLE s2.a (c1 INT);"
                + " CREATE TABLE s2.t (c1 INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "SET search_path TO s2; INSERT INTO t SELECT c1 FROM a;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("s2.a.c1"))),
                result.getGraph().getSources(new Column("s2.t.c1")));
    }

    @Test
    public void mysqlUseDoesNotBlockExplicitDatabase() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.loadDdl("CREATE TABLE db2.b (c2 INT);"
                + " CREATE TABLE db3.t (v INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog),
                LineageOptions.of(MetadataMode.STRICT, false)).scan(
                "USE db1; INSERT INTO db3.t SELECT b.* FROM db2.b AS b;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("db2.b.c2"))),
                result.getGraph().getSources(new Column("db3.t.v")));
    }

    @Test
    public void useOverridesCatalogDefaultDatabase() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.loadDdl("CREATE TABLE db1.a (c INT);"
                + " CREATE TABLE db2.a (c INT);");
        catalog.setDefaultDatabase("db1");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog),
                LineageOptions.of(MetadataMode.STRICT, false)).scan(
                "USE db2; SELECT c FROM a;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("db2.a.c"))),
                result.getGraph().getSources(
                        Column.temporary("stmt1.temp0.c")));
    }

    @Test
    public void searchPathOverridesCatalogDefaultSchema() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE s1.a (c INT);"
                + " CREATE TABLE s2.a (c INT);");
        catalog.setDefaultSchema("s1");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog),
                LineageOptions.of(MetadataMode.STRICT, false)).scan(
                "SET search_path TO s2; SELECT c FROM a;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("s2.a.c"))),
                result.getGraph().getSources(
                        Column.temporary("stmt1.temp0.c")));
    }

    @Test
    public void hiveUseResolvesUnqualifiedTable() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.hive);
        catalog.loadDdl("CREATE TABLE db1.a (c INT);"
                + " CREATE TABLE db2.a (c INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog),
                LineageOptions.of(MetadataMode.STRICT, false)).scan(
                "USE db2; SELECT c FROM a;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("db2.a.c"))),
                result.getGraph().getSources(
                        Column.temporary("stmt1.temp0.c")));
    }

    @Test
    public void quotedSearchPathKeepsCaseDistinctSchema() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE s.a (k INT);"
                + " CREATE TABLE \"S\".a (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog),
                LineageOptions.of(MetadataMode.STRICT, false)).scan(
                "SET search_path TO \"S\"; SELECT k FROM a;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("S.a.k"))),
                result.getGraph().getSources(
                        Column.temporary("stmt1.temp0.k")));
    }

    @Test
    public void useSeparatesUncatalogedTargets() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.mysql, optional()).scan(
                "USE db1;"
                        + " INSERT INTO t (k) SELECT a.x FROM a;"
                        + " USE db2;"
                        + " INSERT INTO t (k) SELECT b.y FROM b;"
                        + " SELECT t.k AS copied FROM t;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("db2.b.y"))),
                result.getGraph().getSources(
                        Column.temporary("stmt4.temp0.copied")));
    }

    @Test
    public void hiveJoinsAcrossDatabases() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.hive);
        catalog.loadDdl("CREATE TABLE db1.a (c1 INT);"
                + " CREATE TABLE db2.b (c2 INT);"
                + " CREATE TABLE db3.t (c1 INT, c2 INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "INSERT INTO db3.t SELECT db1.a.c1, db2.b.c2"
                        + " FROM db1.a JOIN db2.b ON db1.a.c1 = db2.b.c2;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("db1.a.c1"))),
                result.getGraph().getSources(new Column("db3.t.c1")));
        assertEquals(new HashSet<>(Arrays.asList(new Column("db2.b.c2"))),
                result.getGraph().getSources(new Column("db3.t.c2")));
    }
}

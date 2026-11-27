package com.laotie;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashSet;

import org.junit.Test;

import com.alibaba.druid.DbType;
import com.laotie.model.ScanResult;
import com.laotie.model.metadata.Column;
import com.laotie.model.metadata.MetadataCatalog;
import com.laotie.model.metadata.MetadataContext;
import com.laotie.model.metadata.MetadataMode;
import com.laotie.model.metadata.TableLocation;

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
    public void explicitQuotedSchemaIsNotAmbiguous() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE s.a (k INT);"
                + " CREATE TABLE \"S\".a (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog),
                LineageOptions.of(MetadataMode.STRICT, false)).scan(
                "SELECT k FROM \"S\".a;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("S.a.k"))),
                result.getGraph().getSources(
                        Column.temporary("stmt0.temp0.k")));
    }

    @Test
    public void hiveUseOverridesCatalogDefaultSchema() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.hive);
        catalog.loadDdl("CREATE TABLE db1.a (c INT);"
                + " CREATE TABLE db2.a (c INT);");
        catalog.setDefaultSchema("db1");
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
    public void hiveCatalogDefaultDatabaseResolvesAsSchema() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.hive);
        catalog.loadDdl("CREATE TABLE db1.a (c INT);"
                + " CREATE TABLE db2.a (c INT);");
        catalog.setDefaultDatabase("db2");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog),
                LineageOptions.of(MetadataMode.STRICT, false)).scan(
                "SELECT c FROM a;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("db2.a.c"))),
                result.getGraph().getSources(
                        Column.temporary("stmt0.temp0.c")));
    }

    @Test
    public void mysqlUseKeepsQuotedDatabaseIdentity() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.mysql, optional()).scan(
                "USE `db.one`;"
                        + " INSERT INTO t (k) SELECT a.x FROM a;"
                        + " SELECT k AS copied FROM `db.one`.t;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(
                new Column("`db.one`.a.x"))),
                result.getGraph().getSources(
                        Column.temporary("stmt2.temp0.copied")));
    }

    @Test
    public void mysqlReplaceValuesUsesSessionDatabase() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.loadDdl("CREATE TABLE db1.a (x INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional()).scan(
                "USE db1;"
                        + " REPLACE INTO t(k) VALUES ((SELECT x FROM a));"
                        + " SELECT t.k AS copied FROM t;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("db1.a.x"))),
                result.getGraph().getSources(
                        Column.temporary("stmt2.temp0.copied")));
    }

    @Test
    public void searchPathSeparatesUncatalogedTargets() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.postgresql, optional()).scan(
                "SET search_path TO s1;"
                        + " INSERT INTO t(k) SELECT a.x FROM a;"
                        + " SET search_path TO s2;"
                        + " INSERT INTO t(k) SELECT b.y FROM b;"
                        + " SELECT t.k AS copied FROM t;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("s2.b.y"))),
                result.getGraph().getSources(
                        Column.temporary("stmt4.temp0.copied")));
    }

    @Test
    public void sqlServerBracketSchemaIsCaseInsensitive() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.sqlserver);
        catalog.loadDdl("CREATE TABLE [dbo].[a] ([c] INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog),
                LineageOptions.of(MetadataMode.STRICT, false)).scan(
                "SELECT [c] FROM [DBO].[a];");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("dbo.a.c"))),
                result.getGraph().getSources(
                        Column.temporary("stmt0.temp0.c")));
    }

    @Test
    public void postgresCaseDistinctTableWithinSchema() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE s.a (k INT);"
                + " CREATE TABLE s.\"A\" (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog),
                LineageOptions.of(MetadataMode.STRICT, false)).scan(
                "SET search_path TO s; SELECT k FROM \"A\";"
                        + " SELECT k FROM s.\"A\";");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("s.A.k"))),
                result.getGraph().getSources(
                        Column.temporary("stmt1.temp0.k")));
        assertEquals(new HashSet<>(Arrays.asList(new Column("s.A.k"))),
                result.getGraph().getSources(
                        Column.temporary("stmt2.temp0.k")));
    }

    @Test
    public void quotedPublicDoesNotMatchDefaultSchema() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE a (k INT);"
                + " CREATE TABLE \"PUBLIC\".a (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog),
                LineageOptions.of(MetadataMode.STRICT, false)).scan(
                "SET search_path TO \"PUBLIC\"; SELECT k FROM a;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("PUBLIC.a.k"))),
                result.getGraph().getSources(
                        Column.temporary("stmt1.temp0.k")));
    }

    @Test
    public void stringLiteralSearchPathEntryIsUsed() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE s1.a (k INT);"
                + " CREATE TABLE s2.a (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog),
                LineageOptions.of(MetadataMode.STRICT, false)).scan(
                "SET search_path TO 's2', s1; SELECT k FROM a;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("s2.a.k"))),
                result.getGraph().getSources(
                        Column.temporary("stmt1.temp0.k")));
    }

    @Test
    public void quotedTableAbsentFromExactCaseIsMissing() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE s.a (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog),
                LineageOptions.of(MetadataMode.STRICT, false)).scan(
                "SELECT k FROM s.\"A\";");

        assertTrue(result.getResults().get(0).getError()
                instanceof com.laotie.exception.MetadataNotFoundException);
    }

    @Test
    public void searchPathOrderBeatsLowercasePreference() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE s1.A (k INT);"
                + " CREATE TABLE s2.a (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog),
                LineageOptions.of(MetadataMode.STRICT, false)).scan(
                "SET search_path TO s1, s2; SELECT k FROM a;"
                        + " SET search_path TO s1; SELECT k FROM a;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("s1.A.k"))),
                result.getGraph().getSources(
                        Column.temporary("stmt1.temp0.k")));
        assertEquals(new HashSet<>(Arrays.asList(new Column("s1.A.k"))),
                result.getGraph().getSources(
                        Column.temporary("stmt3.temp0.k")));
    }

    @Test
    public void caseDistinctInsertTargetKeepsCatalogOrder() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE s.t (a INT, b INT);"
                + " CREATE TABLE s.\"T\" (b INT, a INT);"
                + " CREATE TABLE s.src (x INT, y INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog),
                LineageOptions.of(MetadataMode.STRICT, false)).scan(
                "INSERT INTO s.\"T\" SELECT x, y FROM s.src;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("s.src.x"))),
                result.getGraph().getSources(new Column("s.T.b")));
        assertEquals(new HashSet<>(Arrays.asList(new Column("s.src.y"))),
                result.getGraph().getSources(new Column("s.T.a")));
    }

    @Test
    public void explicitPublicMatchesImplicitSchema() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE a (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog),
                LineageOptions.of(MetadataMode.STRICT, false)).scan(
                "SET search_path TO public; SELECT k FROM a;"
                        + " SELECT k FROM public.a;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("a.k"))),
                result.getGraph().getSources(
                        Column.temporary("stmt1.temp0.k")));
        assertEquals(new HashSet<>(Arrays.asList(new Column("a.k"))),
                result.getGraph().getSources(
                        Column.temporary("stmt2.temp0.k")));
    }

    @Test
    public void publicFallbackKeepsExplicitDatabase() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE db2.public.a (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog),
                LineageOptions.of(MetadataMode.STRICT, false)).scan(
                "SELECT k FROM db1.public.a;");

        assertTrue(result.getResults().get(0).getError()
                instanceof com.laotie.exception.MetadataNotFoundException);

        MetadataCatalog matching = new MetadataCatalog(DbType.postgresql);
        matching.loadDdl("CREATE TABLE db1.public.a (k INT);");
        ScanResult found = LineageScanner.withMetadata(
                MetadataContext.of(matching),
                LineageOptions.of(MetadataMode.STRICT, false)).scan(
                "SELECT k FROM db1.public.a;");
        assertEquals(new HashSet<>(Arrays.asList(
                new Column("db1.public.a.k"))),
                found.getGraph().getSources(
                        Column.temporary("stmt0.temp0.k")));
    }

    @Test
    public void quotedUppercaseTableDoesNotShadowLowercase() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE s1.\"A\" (k INT);"
                + " CREATE TABLE s2.a (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog),
                LineageOptions.of(MetadataMode.STRICT, false)).scan(
                "SET search_path TO s1, s2; SELECT k FROM a;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("s2.a.k"))),
                result.getGraph().getSources(
                        Column.temporary("stmt1.temp0.k")));
    }

    @Test
    public void quotedPublicDatabaseQualifierResolves() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE db1.public.a (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog),
                LineageOptions.of(MetadataMode.STRICT, false)).scan(
                "SELECT k FROM \"db1\".public.a;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(
                new Column("db1.public.a.k"))),
                result.getGraph().getSources(
                        Column.temporary("stmt0.temp0.k")));
    }

    @Test
    public void ddlRoundTripPreservesQuotedTableIdentity() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE s1.\"A\" (k INT);"
                + " CREATE TABLE s2.a (k INT);");
        MetadataCatalog reloaded = new MetadataCatalog(DbType.postgresql);
        reloaded.loadDdl(catalog.toDdl());

        assertEquals(catalog.getChecksum(), reloaded.getChecksum());
        for (MetadataCatalog target : Arrays.asList(catalog, reloaded)) {
            ScanResult result = LineageScanner.withMetadata(
                    MetadataContext.of(target),
                    LineageOptions.of(MetadataMode.STRICT, false)).scan(
                    "SET search_path TO s1, s2; SELECT k FROM a;");
            assertEquals(0, result.getFailures().size());
            assertEquals(new HashSet<>(Arrays.asList(new Column("s2.a.k"))),
                    result.getGraph().getSources(
                            Column.temporary("stmt1.temp0.k")));
        }
    }

    @Test
    public void jdbcScannedTableKeepsQuotedIdentity() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.addScannedTable(null, null, "s1", "A",
                Arrays.asList(new Column("A", "k")));
        catalog.addScannedTable(null, null, "s2", "a",
                Arrays.asList(new Column("a", "k")));
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog),
                LineageOptions.of(MetadataMode.STRICT, false)).scan(
                "SET search_path TO s1, s2; SELECT k FROM a;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("s2.a.k"))),
                result.getGraph().getSources(
                        Column.temporary("stmt1.temp0.k")));
    }

    @Test
    public void quotedDatabaseQualifierIsExact() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE db1.public.a (k INT);");
        ScanResult missing = LineageScanner.withMetadata(
                MetadataContext.of(catalog),
                LineageOptions.of(MetadataMode.STRICT, false)).scan(
                "SELECT k FROM \"DB1\".public.a;");
        assertTrue(missing.getResults().get(0).getError()
                instanceof com.laotie.exception.MetadataNotFoundException);

        ScanResult found = LineageScanner.withMetadata(
                MetadataContext.of(catalog),
                LineageOptions.of(MetadataMode.STRICT, false)).scan(
                "SELECT k FROM db1.public.a;");
        assertEquals(0, found.getFailures().size());
    }

    @Test
    public void addTablePreservesQuotedTableIdentity() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.addTable("s1.\"A\"", Arrays.asList(new Column("k")));
        catalog.addTable("s2.a", Arrays.asList(new Column("k")));
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog),
                LineageOptions.of(MetadataMode.STRICT, false)).scan(
                "SET search_path TO s1, s2; SELECT k FROM a;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("s2.a.k"))),
                result.getGraph().getSources(
                        Column.temporary("stmt1.temp0.k")));
    }

    @Test
    public void ddlRoundTripPreservesEmbeddedQuote() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE s.\"a\"\"b\" (k INT);");
        MetadataCatalog reloaded = new MetadataCatalog(DbType.postgresql);
        reloaded.loadDdl(catalog.toDdl());

        assertEquals(catalog.getChecksum(), reloaded.getChecksum());
        for (MetadataCatalog target : Arrays.asList(catalog, reloaded)) {
            ScanResult result = LineageScanner.withMetadata(
                    MetadataContext.of(target),
                    LineageOptions.of(MetadataMode.STRICT, false)).scan(
                    "SELECT k FROM s.\"a\"\"b\";");
            assertEquals(0, result.getFailures().size());
            assertTrue(!result.getGraph().getSources(
                    Column.temporary("stmt0.temp0.k")).isEmpty());
        }
    }

    @Test
    public void sqlServerBackslashInsideBracketsIsLiteral() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.sqlserver);
        catalog.loadDdl("CREATE TABLE [dbo].[a\\z] ([k] INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog),
                LineageOptions.of(MetadataMode.STRICT, false)).scan(
                "SELECT [k] FROM [dbo].[a\\z];");

        assertEquals(0, result.getFailures().size());
        assertTrue(!result.getGraph().getSources(
                Column.temporary("stmt0.temp0.k")).isEmpty());

        MetadataCatalog reloaded = new MetadataCatalog(DbType.sqlserver);
        reloaded.loadDdl(catalog.toDdl());
        assertEquals(catalog.getChecksum(), reloaded.getChecksum());
    }

    @Test
    public void postgresQuotedSpacesInTableNameAreSignificant() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE s.\" spaced \" (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog),
                LineageOptions.of(MetadataMode.STRICT, false)).scan(
                "SELECT k FROM s.\" spaced \";");

        assertEquals(0, result.getFailures().size());
        assertTrue(!result.getGraph().getSources(
                Column.temporary("stmt0.temp0.k")).isEmpty());
    }

    @Test
    public void sqlServerClosingBracketNameRoundTrips() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.sqlserver);
        catalog.loadDdl("CREATE TABLE dbo.\"a]b\" (k INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog),
                LineageOptions.of(MetadataMode.STRICT, false)).scan(
                "SELECT k FROM dbo.\"a]b\";");
        assertEquals(0, result.getFailures().size());

        MetadataCatalog reloaded = new MetadataCatalog(DbType.sqlserver);
        reloaded.loadDdl(catalog.toDdl());
        assertEquals(catalog.getChecksum(), reloaded.getChecksum());
    }

    @Test
    public void findTableTrimsWhitespaceOutsideQuotes() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE s.\"a\" (plain_k INT);"
                + " CREATE TABLE s.\" a \" (padded_k INT);");
        TableLocation location = catalog.findTable("s. \"a\" ");

        assertEquals("a", location.getTable().getName());
        assertEquals("plain_k",
                location.getTable().getColumns().get(0).getColumnName());
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

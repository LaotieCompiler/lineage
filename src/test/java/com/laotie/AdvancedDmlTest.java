package com.laotie;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

import com.alibaba.druid.DbType;
import com.laotie.model.ScanResult;
import com.laotie.exception.AmbiguousColumnException;
import com.laotie.exception.ColumnNotFoundException;
import com.laotie.exception.MetadataNotFoundException;
import com.laotie.model.metadata.Column;
import com.laotie.model.metadata.MetadataCatalog;
import com.laotie.model.metadata.MetadataContext;

/**
 * DML forms that appear in production SQL logs.
 *
 * <p>
 * {@code REPLACE INTO}, {@code MERGE INTO}, {@code TRUNCATE} and scalar subqueries are
 * common in real logs; each must be analyzed without failing the statement and must keep
 * the column lineage it actually expresses.
 */
public class AdvancedDmlTest {

    private static LineageOptions optional() {
        return LineageTestSupport.optional();
    }

    private static MetadataContext catalog(String ddl) {
        return LineageTestSupport.postgres(ddl);
    }

    private static Set<Column> sources(ScanResult result, String column) {
        return result.getGraph().getSources(new Column(column));
    }

    @Test
    public void replaceIntoWritesLikeAnInsert() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.loadDdl(
                "CREATE TABLE s (c1 INT, c2 INT); CREATE TABLE t (c1 INT, c2 INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional())
                .scan("REPLACE INTO t (c1, c2) SELECT c1, c2 FROM s;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("s.c1"))),
                sources(result, "t.c1"));
        assertEquals(new HashSet<>(Arrays.asList(new Column("s.c2"))),
                sources(result, "t.c2"));
    }

    @Test
    public void truncateIsAcceptedWithoutColumnLineage() {
        MetadataContext context = catalog("CREATE TABLE t (c1 INT);");
        ScanResult result = LineageScanner.withMetadata(context, optional())
                .scan("TRUNCATE TABLE t;");

        assertEquals(1, result.getResults().size());
        assertNull(result.getResults().get(0).getError());
        assertTrue(result.getResults().get(0).getInstructions().isEmpty());
    }

    @Test
    public void mergeUpdateAndInsertClausesAreTraced() {
        MetadataContext context = catalog(
                "CREATE TABLE s (c1 INT, c2 INT); CREATE TABLE t (c1 INT, c2 INT);");
        ScanResult result = LineageScanner.withMetadata(context, optional()).scan(
                "MERGE INTO t USING s ON (t.c1 = s.c1)"
                        + " WHEN MATCHED THEN UPDATE SET t.c2 = s.c1"
                        + " WHEN NOT MATCHED THEN INSERT (c1, c2) VALUES (s.c1, s.c2);");

        assertEquals(0, result.getFailures().size());
        // The insert clause writes s.c1 and s.c2 into the target columns.
        assertEquals(new HashSet<>(Arrays.asList(new Column("s.c1"))),
                sources(result, "t.c1"));
        // t.c2 receives either the updated s.c1 or the inserted s.c2.
        Set<Column> second = sources(result, "t.c2");
        assertTrue(second.contains(new Column("s.c1")));
        assertTrue(second.contains(new Column("s.c2")));
    }

    @Test
    public void scalarSubqueryProjectionKeepsItsInnerSource() {
        MetadataContext context = catalog(
                "CREATE TABLE a (c1 INT); CREATE TABLE b (c1 INT);"
                        + " CREATE TABLE t (c1 INT);");
        ScanResult result = LineageScanner.withMetadata(context, optional())
                .scan("INSERT INTO t(c1) SELECT (SELECT max(c1) FROM b) FROM a;");

        assertEquals(0, result.getFailures().size());
        // The projected value comes from the scalar subquery, not from the outer table.
        assertEquals(new HashSet<>(Arrays.asList(new Column("b.c1"))),
                sources(result, "t.c1"));
    }

    @Test
    public void scalarSubqueryInUpdateSetIsTraced() {
        MetadataContext context = catalog(
                "CREATE TABLE a (c1 INT, c2 INT); CREATE TABLE b (c1 INT);");
        ScanResult result = LineageScanner.withMetadata(context, optional())
                .scan("UPDATE a SET c2 = (SELECT max(c1) FROM b);");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("b.c1"))),
                sources(result, "a.c2"));
    }

    @Test
    public void scalarSubqueryWithAliasKeepsItsName() {
        MetadataContext context = catalog(
                "CREATE TABLE a (c1 INT); CREATE TABLE b (c1 INT);"
                        + " CREATE TABLE t (c1 INT);");
        ScanResult result = LineageScanner.withMetadata(context, optional())
                .scan("INSERT INTO t(c1) SELECT v FROM a,"
                        + " LATERAL (SELECT max(b.c1) AS v FROM b) l;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("b.c1"))),
                sources(result, "t.c1"));
    }

    @Test
    public void mergeUpdateExpressionReadsTheTargetTable() {
        MetadataContext context = catalog(
                "CREATE TABLE s (c1 INT, c2 INT); CREATE TABLE t (c1 INT, c2 INT);");
        ScanResult result = LineageScanner.withMetadata(context, optional())
                .scan("MERGE INTO t USING s ON t.c1 = s.c1"
                        + " WHEN MATCHED THEN UPDATE SET c2 = t.c1 + s.c2;");

        assertEquals(0, result.getFailures().size());
        Set<Column> sources = sources(result, "t.c2");
        assertTrue(sources.contains(new Column("t.c1")));
        assertTrue(sources.contains(new Column("s.c2")));
    }

    @Test
    public void mergeInsertColumnIsValidatedAgainstTheCatalog() {
        MetadataContext context = catalog(
                "CREATE TABLE s (c1 INT, c2 INT); CREATE TABLE t (c1 INT, c2 INT);");
        ScanResult result = LineageScanner.withMetadata(context, optional())
                .scan("MERGE INTO t USING s ON t.c1 = s.c1"
                        + " WHEN NOT MATCHED THEN INSERT (bogus) VALUES (s.c1);");

        assertTrue(result.getResults().get(0).getError()
                instanceof ColumnNotFoundException);
    }

    @Test
    public void mergeWithoutColumnListUsesTheCatalogColumns() {
        MetadataContext context = catalog(
                "CREATE TABLE s (c1 INT, c2 INT);"
                        + " CREATE TABLE t (c1 INT, c2 INT, c3 INT, c4 INT);");
        ScanResult result = LineageScanner.withMetadata(context, optional())
                .scan("MERGE INTO t USING s ON t.c1 = s.c1"
                        + " WHEN NOT MATCHED THEN INSERT VALUES (s.c1, s.c2, 0, 0);");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("s.c1"))),
                sources(result, "t.c1"));
        assertEquals(new HashSet<>(Arrays.asList(new Column("s.c2"))),
                sources(result, "t.c2"));
    }

    @Test
    public void scalarSubqueryKeepsTheRestOfTheExpression() {
        MetadataContext context = catalog(
                "CREATE TABLE a (c1 INT, c2 INT); CREATE TABLE b (c1 INT);");
        ScanResult result = LineageScanner.withMetadata(context, optional())
                .scan("UPDATE a SET c2 = (SELECT c1 FROM b) + a.c1;");

        assertEquals(0, result.getFailures().size());
        Set<Column> sources = sources(result, "a.c2");
        assertTrue(sources.contains(new Column("b.c1")));
        assertTrue(sources.contains(new Column("a.c1")));
    }

    @Test
    public void scalarSubqueryDoesNotBreakLaterAssignments() {
        MetadataContext context = catalog(
                "CREATE TABLE a (c1 INT, c2 INT); CREATE TABLE b (c1 INT);");
        ScanResult result = LineageScanner.withMetadata(context, optional())
                .scan("UPDATE a SET c1 = (SELECT c1 FROM b), c2 = a.c1;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("b.c1"))),
                sources(result, "a.c1"));
        // The second assignment still reads a.c1, so the edge survives; a.c2 then
        // reaches b.c1 transitively through the updated a.c1.
        assertTrue(result.getGraph().getReverseGraph()
                .containsEdge(new Column("a.c2"), new Column("a.c1")));
        assertEquals(new HashSet<>(Arrays.asList(new Column("b.c1"))),
                sources(result, "a.c2"));
    }

    @Test
    public void scalarAfterUnknownWidthWildcardKeepsNoColumnEdge() {
        MetadataContext context = catalog(
                "CREATE TABLE b (c1 INT);"
                        + " CREATE TABLE t (c1 INT, c2 INT, c3 INT, c4 INT);");
        ScanResult result = LineageScanner.withMetadata(context, optional())
                .scan("INSERT INTO t(c1,c2,c3) SELECT missing.*,"
                        + " (SELECT c1 FROM b) FROM missing;");

        assertEquals(0, result.getFailures().size());
        // The scalar follows an unknown-width wildcard, so no column may be pinned.
        assertFalse(sources(result, "t.c2").contains(new Column("b.c1")));
    }

    @Test
    public void scalarBeforeUnknownWidthWildcardKeepsUnionWidth() {
        MetadataContext context = catalog(
                "CREATE TABLE b (c1 INT);"
                        + " CREATE TABLE t (c1 INT, c2 INT, c3 INT, c4 INT);");
        ScanResult result = LineageScanner.withMetadata(context, optional())
                .scan("INSERT INTO t SELECT (SELECT c1 FROM b), missing.*"
                        + " FROM missing UNION ALL SELECT 1,2,3; SELECT c4 FROM t;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("b.c1"))),
                sources(result, "t.c1"));
        // The union is three columns wide, so the fourth target column is untouched.
        assertFalse(sources(result, "t.c4").contains(new Column("missing.*")));
    }

    @Test
    public void scalarSubqueryWithoutOuterFromIsAnalyzed() {
        MetadataContext context = catalog(
                "CREATE TABLE b (c1 INT); CREATE TABLE t (c1 INT);");
        ScanResult result = LineageScanner.withMetadata(context, optional())
                .scan("INSERT INTO t(c1) SELECT (SELECT c1 FROM b);");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("b.c1"))),
                sources(result, "t.c1"));
    }

    @Test
    public void scalarAggregateKeepsItsInnerSource() {
        MetadataContext context = catalog(
                "CREATE TABLE a (c1 INT); CREATE TABLE b (c1 INT);"
                        + " CREATE TABLE t (c1 INT);");
        ScanResult result = LineageScanner.withMetadata(context, optional())
                .scan("INSERT INTO t(c1) SELECT (SELECT max(b.c1) FROM b) FROM a;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("b.c1"))),
                sources(result, "t.c1"));
    }

    @Test
    public void replaceWithValuesKeepsExpressionLineage() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.loadDdl("CREATE TABLE b (c1 INT); CREATE TABLE t (c1 INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional())
                .scan("REPLACE INTO t(c1) VALUES ((SELECT MAX(c1) FROM b));");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("b.c1"))),
                sources(result, "t.c1"));
    }

    @Test
    public void mergeInsertValuesReadTheSourceOnly() {
        MetadataContext context = catalog(
                "CREATE TABLE s (c1 INT, c2 INT); CREATE TABLE t (c1 INT, c2 INT);");
        ScanResult result = LineageScanner.withMetadata(context, optional())
                .scan("MERGE INTO t USING s ON t.c1 = s.c1"
                        + " WHEN NOT MATCHED THEN INSERT (c2) VALUES (c1);");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("s.c1"))),
                sources(result, "t.c2"));
    }

    @Test
    public void mergeUsingJoinAliasIsVisible() {
        MetadataContext context = catalog(
                "CREATE TABLE s (c1 INT, c2 INT); CREATE TABLE b (c1 INT, c2 INT);"
                        + " CREATE TABLE t (c1 INT, c2 INT);");
        ScanResult result = LineageScanner.withMetadata(context, optional())
                .scan("MERGE INTO t USING (s JOIN b USING(c1)) AS src"
                        + " ON t.c1 = src.c1"
                        + " WHEN MATCHED THEN UPDATE SET c2 = src.c1;");

        assertEquals(0, result.getFailures().size());
        assertTrue(sources(result, "t.c2").contains(new Column("s.c1")));
        assertTrue(sources(result, "t.c2").contains(new Column("b.c1")));
    }

    @Test
    public void mergeQuotedDottedColumnKeepsItsIdentity() {
        MetadataContext context = catalog(
                "CREATE TABLE s (c1 INT, c2 INT);"
                        + " CREATE TABLE t (c1 INT, \"a.b\" INT);");
        ScanResult result = LineageScanner.withMetadata(context, optional())
                .scan("MERGE INTO t USING s ON t.c1 = s.c1"
                        + " WHEN MATCHED THEN UPDATE SET \"a.b\" = s.c1;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("s.c1"))),
                sources(result, "t.\"a.b\""));
    }

    @Test
    public void nestedScalarWithoutOuterFromIsAnalyzed() {
        MetadataContext context = catalog(
                "CREATE TABLE b (c1 INT); CREATE TABLE t (c1 INT);");
        ScanResult cast = LineageScanner.withMetadata(context, optional())
                .scan("INSERT INTO t(c1) SELECT CAST((SELECT c1 FROM b) AS int);");
        assertEquals(0, cast.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("b.c1"))),
                sources(cast, "t.c1"));

        ScanResult arithmetic = LineageScanner.withMetadata(context, optional())
                .scan("INSERT INTO t(c1) SELECT 1+(SELECT c1 FROM b);");
        assertEquals(0, arithmetic.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("b.c1"))),
                sources(arithmetic, "t.c1"));
    }

    @Test
    public void scalarAliasWithDotKeepsItsIdentity() {
        MetadataContext context = catalog(
                "CREATE TABLE b (c1 INT); CREATE TABLE t (c1 INT);");
        ScanResult result = LineageScanner.withMetadata(context, optional())
                .scan("INSERT INTO t(c1) SELECT (SELECT c1 AS \"A.B\" FROM b);");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("b.c1"))),
                sources(result, "t.c1"));
    }

    @Test
    public void replaceCompositeValueKeepsLineage() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.loadDdl("CREATE TABLE b (c1 INT); CREATE TABLE t (c1 INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional())
                .scan("REPLACE INTO t(c1) VALUES (1+(SELECT c1 FROM b));");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("b.c1"))),
                sources(result, "t.c1"));
    }

    @Test
    public void replaceLiteralValueHasNoSource() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.loadDdl("CREATE TABLE b (c1 INT); CREATE TABLE t (c1 INT, c2 INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional())
                .scan("REPLACE INTO t(c1,c2) VALUES (1,(SELECT c1 FROM b));");

        assertEquals(0, result.getFailures().size());
        assertFalse(result.getGraph().getReverseGraph()
                .containsVertex(new Column("t.c1")));
        assertEquals(new HashSet<>(Arrays.asList(new Column("b.c1"))),
                sources(result, "t.c2"));
    }

    @Test
    public void mergeUsingAliasHidesUnderlyingTables() {
        MetadataContext context = catalog(
                "CREATE TABLE s (c1 INT, c2 INT); CREATE TABLE b (c1 INT, c2 INT);"
                        + " CREATE TABLE t (c1 INT, c2 INT);");
        ScanResult hidden = LineageScanner.withMetadata(context, optional())
                .scan("MERGE INTO t USING (s JOIN b USING(c1)) AS src"
                        + " ON t.c1 = src.c1"
                        + " WHEN MATCHED THEN UPDATE SET c2 = s.c2;");
        assertTrue(hidden.getResults().get(0).getError()
                instanceof MetadataNotFoundException);

        ScanResult alias = LineageScanner.withMetadata(context, optional())
                .scan("MERGE INTO t USING (s JOIN b USING(c1)) AS src"
                        + " ON t.c1 = src.c1"
                        + " WHEN MATCHED THEN UPDATE SET c2 = src.c1;");
        assertEquals(0, alias.getFailures().size());
        Set<Column> sources = sources(alias, "t.c2");
        assertTrue(sources.contains(new Column("s.c1")));
        assertTrue(sources.contains(new Column("b.c1")));
    }

    @Test
    public void replaceWithoutColumnListUsesCatalogOrder() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.loadDdl("CREATE TABLE b (c1 INT); CREATE TABLE t (c1 INT, c2 INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional())
                .scan("REPLACE INTO t VALUES (0, (SELECT c1 FROM b));");

        assertEquals(0, result.getFailures().size());
        assertFalse(result.getGraph().getReverseGraph()
                .containsVertex(new Column("t.c1")));
        assertEquals(new HashSet<>(Arrays.asList(new Column("b.c1"))),
                sources(result, "t.c2"));
    }

    @Test
    public void replaceQuotedDottedColumnKeepsItsIdentity() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.loadDdl("CREATE TABLE b (c1 INT); CREATE TABLE t (`a.b` INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional())
                .scan("REPLACE INTO t(`a.b`) VALUES ((SELECT c1 FROM b));");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("b.c1"))),
                sources(result, "t.`a.b`"));
    }

    @Test
    public void unnamedScalarUpdateClaimsOnlyTheWrittenColumn() {
        MetadataContext context = catalog("CREATE TABLE t (c1 INT, c2 INT);");
        ScanResult result = LineageScanner.withMetadata(context, optional())
                .scan("UPDATE t SET c1 = (SELECT * FROM sch.missing);"
                        + " SELECT c2 FROM t;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("sch.missing.*"))),
                sources(result, "t.c1"));
        assertFalse(sources(result, "t.c2").contains(new Column("sch.missing.*")));
    }

    @Test
    public void unnamedScalarReplaceClaimsOnlyTheWrittenColumn() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.loadDdl("CREATE TABLE t (c1 INT, c2 INT);");
        ScanResult result = LineageScanner.withMetadata(
                MetadataContext.of(catalog), optional())
                .scan("REPLACE INTO t(c1) VALUES ((SELECT * FROM missing));"
                        + " SELECT c2 FROM t;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("missing.*"))),
                sources(result, "t.c1"));
        assertFalse(sources(result, "t.c2").contains(new Column("missing.*")));
    }

    @Test
    public void scalarWithUnknownWildcardAndKnownTableIsRejected() {
        MetadataContext context = catalog(
                "CREATE TABLE b (c1 INT, c2 INT); CREATE TABLE t (c1 INT);");
        ScanResult result = LineageScanner.withMetadata(context, optional())
                .scan("INSERT INTO t(c1) SELECT"
                        + " (SELECT missing.*, b.* FROM missing CROSS JOIN b);");

        assertTrue(result.getResults().get(0).getError()
                instanceof IllegalStateException);
    }

    @Test
    public void mergeUnqualifiedUsingKeyIsAmbiguousWithTarget() {
        MetadataContext context = catalog(
                "CREATE TABLE s (c1 INT, c2 INT); CREATE TABLE b (c1 INT, c2 INT);"
                        + " CREATE TABLE t (c1 INT, c2 INT);");
        ScanResult result = LineageScanner.withMetadata(context, optional())
                .scan("MERGE INTO t USING (s JOIN b USING(c1)) AS src"
                        + " ON t.c1 = src.c1"
                        + " WHEN MATCHED THEN UPDATE SET c2 = c1;");

        assertTrue(result.getResults().get(0).getError()
                instanceof AmbiguousColumnException);
    }

    @Test
    public void scalarSubqueryMustReturnOneColumn() {
        MetadataContext context = catalog(
                "CREATE TABLE b (c1 INT, c2 INT); CREATE TABLE t (c1 INT);");
        ScanResult result = LineageScanner.withMetadata(context, optional())
                .scan("INSERT INTO t(c1) SELECT (SELECT c1,c2 FROM b);");

        assertTrue(result.getResults().get(0).getError()
                instanceof IllegalStateException);
    }

    @Test
    public void unnamedScalarSubqueryKeepsTableLevelLineage() {
        MetadataContext context = catalog("CREATE TABLE t (c1 INT);");
        ScanResult result = LineageScanner.withMetadata(context, optional())
                .scan("INSERT INTO t(c1) SELECT (SELECT * FROM missing);");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("missing.*"))),
                sources(result, "t.c1"));
    }

    @Test
    public void scalarSubqueryKeepsItsOwnSource() {
        MetadataContext context = catalog(
                "CREATE TABLE a (c1 INT); CREATE TABLE t (c1 INT);");
        ScanResult result = LineageScanner.withMetadata(context, optional())
                .scan("INSERT INTO t(c1) SELECT (SELECT max(c1) FROM missing) FROM a;");

        assertEquals(0, result.getFailures().size());
        // The subquery reads an explicitly named column of an unknown table, which
        // keeps its own identity; the outer table contributes nothing to it.
        assertEquals(new HashSet<>(Arrays.asList(new Column("missing.c1"))),
                sources(result, "t.c1"));
        assertFalse(sources(result, "t.c1").contains(new Column("a.c1")));
    }
    @Test
    public void quotedScalarUpdateTargetKeepsItsIdentity() {
        MetadataContext context = catalog("CREATE TABLE t (\"a.b\" INT, c2 INT);");
        ScanResult result = LineageScanner.withMetadata(context, optional())
                .scan("UPDATE t SET \"a.b\" = (SELECT * FROM sch.missing);"
                        + " SELECT \"a.b\" FROM t;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("sch.missing.*"))),
                sources(result, "t.\"a.b\""));
    }

    @Test
    public void compositeScalarWithoutOuterFromKeepsTableLevel() {
        MetadataContext context = catalog("CREATE TABLE t (c1 INT);");
        ScanResult result = LineageScanner.withMetadata(context, optional())
                .scan("INSERT INTO t(c1) SELECT 1+(SELECT * FROM sch.missing);");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("sch.missing.*"))),
                sources(result, "t.c1"));
    }

    @Test
    public void mergeScalarUsingKeyMergesItsSides() {
        MetadataContext context = catalog(
                "CREATE TABLE t (c2 INT); CREATE TABLE s (c1 INT);"
                        + " CREATE TABLE b (c1 INT);");
        ScanResult result = LineageScanner.withMetadata(context, optional())
                .scan("MERGE INTO t USING (s JOIN b USING(c1)) AS src ON true"
                        + " WHEN MATCHED THEN UPDATE SET c2 = (SELECT c1);");

        assertEquals(0, result.getFailures().size());
        Set<Column> sources = sources(result, "t.c2");
        assertTrue(sources.contains(new Column("s.c1")));
        assertTrue(sources.contains(new Column("b.c1")));
    }

    @Test
    public void compositeScalarWithOuterFromKeepsTableLevel() {
        MetadataContext context = catalog(
                "CREATE TABLE t (c1 INT); CREATE TABLE b (c1 INT);");
        ScanResult result = LineageScanner.withMetadata(context, optional())
                .scan("INSERT INTO t(c1) SELECT"
                        + " 1+(SELECT * FROM sch.missing) AS v FROM b;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("sch.missing.*"))),
                sources(result, "t.c1"));
    }

    @Test
    public void mergeScalarCorrelatesToUsingScopeWithLocalFrom() {
        MetadataContext context = catalog(
                "CREATE TABLE t (c2 INT); CREATE TABLE s (c1 INT);"
                        + " CREATE TABLE b (c1 INT); CREATE TABLE other (c3 INT);");
        ScanResult result = LineageScanner.withMetadata(context, optional())
                .scan("MERGE INTO t USING (s JOIN b USING(c1)) AS src ON true"
                        + " WHEN MATCHED THEN UPDATE SET c2 = (SELECT c1 FROM other);");

        assertEquals(0, result.getFailures().size());
        Set<Column> sources = sources(result, "t.c2");
        assertTrue(sources.contains(new Column("s.c1")));
        assertTrue(sources.contains(new Column("b.c1")));
    }

    @Test
    public void mergeScalarNestedAliasHidesItsTables() {
        MetadataContext context = catalog(
                "CREATE TABLE t (c2 INT); CREATE TABLE s (c1 INT);"
                        + " CREATE TABLE b (c1 INT); CREATE TABLE d (c3 INT);");
        ScanResult hidden = LineageScanner.withMetadata(context, optional())
                .scan("MERGE INTO t USING s ON true WHEN MATCHED THEN UPDATE SET"
                        + " c2=(SELECT (SELECT b.c1) FROM (b CROSS JOIN d) AS j);");
        assertTrue(hidden.getResults().get(0).getError()
                instanceof MetadataNotFoundException);

        ScanResult alias = LineageScanner.withMetadata(context, optional())
                .scan("MERGE INTO t USING s ON true WHEN MATCHED THEN UPDATE SET"
                        + " c2=(SELECT (SELECT j.c1) FROM (b CROSS JOIN d) AS j);");
        assertEquals(0, alias.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("b.c1"))),
                sources(alias, "t.c2"));
    }

    @Test
    public void mergeScalarUsesTheNearestUsingScope() {
        MetadataContext context = catalog(
                "CREATE TABLE t (v INT); CREATE TABLE s (k INT); CREATE TABLE b (k INT);"
                        + " CREATE TABLE c (k INT); CREATE TABLE d (k INT);"
                        + " CREATE TABLE o (u INT);");
        ScanResult result = LineageScanner.withMetadata(context, optional())
                .scan("MERGE INTO t USING (s JOIN b USING(k)) AS src ON true"
                        + " WHEN MATCHED THEN UPDATE SET v=("
                        + "SELECT (SELECT k FROM o) FROM (c JOIN d USING(k)) AS j);");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(
                new Column("c.k"), new Column("d.k"))), sources(result, "t.v"));
    }

    @Test
    public void mergeDuplicateUsingOutputsAreAmbiguous() {
        MetadataContext context = catalog(
                "CREATE TABLE t (v INT); CREATE TABLE s (k INT); CREATE TABLE b (k INT);"
                        + " CREATE TABLE c (k INT); CREATE TABLE d (k INT);"
                        + " CREATE TABLE o (u INT);");
        ScanResult result = LineageScanner.withMetadata(context, optional())
                .scan("MERGE INTO t USING ((s JOIN b USING(k))"
                        + " CROSS JOIN (c JOIN d USING(k))) AS src ON true"
                        + " WHEN MATCHED THEN UPDATE SET v = (SELECT k FROM o);");

        assertTrue(result.getResults().get(0).getError()
                instanceof AmbiguousColumnException);
    }

    @Test
    public void outerJoinUsingKeyIsNotAmbiguous() {
        MetadataContext context = catalog(
                "CREATE TABLE s (k INT); CREATE TABLE b (k INT);"
                        + " CREATE TABLE t (v INT);");
        ScanResult select = LineageScanner.withMetadata(context, optional())
                .scan("SELECT k FROM s LEFT JOIN b USING(k);");
        assertEquals(0, select.getFailures().size());

        ScanResult merge = LineageScanner.withMetadata(context, optional())
                .scan("MERGE INTO t USING (s LEFT JOIN b USING(k)) AS src"
                        + " ON true WHEN MATCHED THEN UPDATE SET v = k;");
        assertEquals(0, merge.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("s.k"))),
                sources(merge, "t.v"));
    }

}

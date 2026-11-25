package com.laotie;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.junit.After;
import org.junit.Test;

import com.alibaba.druid.DbType;
import com.laotie.exception.ColumnNotFoundException;
import com.laotie.exception.MetadataNotFoundException;
import com.laotie.model.Instruction;
import com.laotie.model.Instruction.OperationType;
import com.laotie.model.ScanResult;
import com.laotie.model.metadata.Column;
import com.laotie.model.metadata.MetadataCatalog;
import com.laotie.model.metadata.MetadataContext;
import com.laotie.model.metadata.MetadataContextHolder;

public class LineageScannerTest {

    @After
    public void tearDown() {
        MetadataContextHolder.clear();
    }

    @Test
    public void scansLogIntoGlobalLineage() {
        MetadataContext context = mysqlContext(
                "CREATE TABLE db1.A (c1 INT);"
                        + "CREATE TABLE db1.B (c1 INT);"
                        + "CREATE TABLE db1.C (c1 INT);");

        ScanResult result = new LineageScanner(context).scan(
                "INSERT INTO db1.B(c1) SELECT * FROM db1.A;"
                        + "INSERT INTO db1.C(c1) SELECT c1 FROM db1.B;");

        assertEquals(2, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("db1.A.c1"), result.getGraph().getSources(new Column("db1.B.c1")));
        assertEquals(columns("db1.A.c1"), result.getGraph().getSources(new Column("db1.C.c1")));
    }

    @Test
    public void failedStatementDoesNotAbortTheScan() {
        MetadataContext context = mysqlContext(
                "CREATE TABLE db1.A (c1 INT);"
                        + "CREATE TABLE db1.B (c1 INT);"
                        + "CREATE TABLE db1.C (c1 INT);");

        ScanResult result = new LineageScanner(context).scan(
                "INSERT INTO db1.B(c1) SELECT * FROM db1.A;"
                        + "THIS IS NOT SQL;"
                        + "INSERT INTO db1.C(c1) SELECT c1 FROM db1.B;");

        assertEquals(3, result.getResults().size());
        assertEquals(1, result.getFailures().size());
        assertEquals(2, result.getSuccesses().size());
        assertTrue(result.getResults().get(1).getError() != null);
        assertTrue(result.getResults().get(1).getSql().contains("THIS IS NOT SQL"));
        assertTrue(result.getResults().get(1).getInstructions().isEmpty());
        assertEquals(columns("db1.A.c1"), result.getGraph().getSources(new Column("db1.C.c1")));
    }

    @Test
    public void partialMappingsFromFailedStatementsDoNotPolluteTheGraph() {
        MetadataContext context = mysqlContext("CREATE TABLE db1.A (c1 INT);");

        ScanResult result = new LineageScanner(context).scan(
                "SELECT c1 FROM db1.A;"
                        + "SELECT c1, missing FROM db1.A;"
                        + "SELECT c1 FROM db1.A;");

        assertEquals(3, result.getResults().size());
        assertEquals(1, result.getFailures().size());
        assertTrue(result.getResults().get(1).getError() != null);
        assertTrue(result.getResults().get(1).getInstructions().isEmpty());
        assertEquals(columns("db1.A.c1"),
                result.getGraph().getSources(Column.temporary("stmt2.temp0.c1")));
        for (Column vertex : result.getGraph().getReverseGraph().vertexSet()) {
            assertTrue(!vertex.toString().startsWith("stmt1."));
        }
    }

    @Test
    public void physicalTableNamedLikeTempDoesNotLoop() {
        MetadataContextHolder.clear();

        ScanResult result = new LineageScanner().scan(
                "SELECT c1 FROM temp0; SELECT c1 FROM A;");

        assertEquals(2, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("temp0.c1"),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.c1")));
        assertEquals(columns("A.c1"),
                result.getGraph().getSources(Column.temporary("stmt1.temp0.c1")));
    }

    @Test
    public void scansWithoutMetadataContext() {
        MetadataContextHolder.clear();

        ScanResult result = new LineageScanner()
                .scan("SELECT c1 FROM A; SELECT c1 FROM A;");

        assertEquals(2, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertTrue(result.getGraph().getReverseGraph().vertexSet().size() > 0);
    }

    @Test
    public void mixedCaseColumnsChainAcrossStatements() {
        MetadataContext context = mysqlContext(
                "CREATE TABLE db1.A (C1 INT);"
                        + "CREATE TABLE db1.B (C1 INT);"
                        + "CREATE TABLE db1.C (c1 INT);");

        ScanResult result = new LineageScanner(context).scan(
                "INSERT INTO B SELECT * FROM A;"
                        + "INSERT INTO C(c1) SELECT c1 FROM B;");

        assertEquals(2, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("db1.A.C1"),
                result.getGraph().getSources(new Column("db1.C.c1")));
    }

    @Test
    public void ctePreallocatedTempNameDoesNotClashWithAutoTemp() {
        MetadataContext context = pgContext(
                "CREATE TABLE A (c1 INT); CREATE TABLE B (c1 INT); CREATE TABLE C (c1 INT);");

        ScanResult result = new LineageScanner(context).scan(
                "WITH temp1 AS (SELECT c1 FROM A)"
                        + " INSERT INTO B(c1) SELECT c1 FROM temp1;"
                        + " SELECT c1 FROM C;");

        assertEquals(2, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("A.c1"),
                result.getGraph().getSources(new Column("B.c1")));
    }

    @Test
    public void nestedCteDoesNotLeakIntoOuterScope() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH X AS (SELECT c1 FROM A)"
                        + " SELECT X.c1 FROM"
                        + " (WITH X AS (SELECT c1 FROM B) SELECT c1 FROM X) d, X;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        // The outer qualified X refers to the outer CTE (A), not the inner shadowing CTE (B).
        assertEquals(columns("A.c1"),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.c1")));
    }

    @Test
    public void updateSelfDependencyDoesNotAbortTheScan() {
        ScanResult result = new LineageScanner(DbType.mysql).scan(
                "UPDATE A SET c1 = c1 + 1; SELECT c1 FROM B;");

        assertEquals(2, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("B.c1"),
                result.getGraph().getSources(Column.temporary("stmt1.temp0.c1")));
    }

    @Test
    public void nestedPostgresBlockCommentKeepsFollowingStatement() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "/* outer /* inner */ still outer */ SELECT c1 FROM A;"
                        + " SELECT c1 FROM B;");

        assertEquals(2, result.getResults().size());
        assertTrue(result.getResults().get(0).getSql().contains("still outer"));
        assertNull(result.getResults().get(1).getError());
    }

    @Test
    public void sqlServerBracketIdentifierKeepsSemicolon() {
        ScanResult result = new LineageScanner(DbType.sqlserver).scan(
                "SELECT [a;b] FROM A; SELECT c1 FROM B;");

        assertEquals(2, result.getResults().size());
        assertTrue(result.getResults().get(0).getSql().contains("[a;b]"));
        assertNull(result.getResults().get(1).getError());
    }

    @Test
    public void mysqlBacktickColumnsRemainCaseInsensitive() {
        MetadataContext context = mysqlContext(
                "CREATE TABLE A (C1 INT); CREATE TABLE B (C1 INT);");

        ScanResult select = new LineageScanner(context).scan("SELECT `c1` FROM A;");
        assertEquals(0, select.getFailures().size());
        assertEquals(columns("A.C1"),
                select.getGraph().getSources(Column.temporary("stmt0.temp0.c1")));

        ScanResult insert = new LineageScanner(context)
                .scan("INSERT INTO B(`c1`) SELECT `c1` FROM A;");
        assertEquals(0, insert.getFailures().size());
        assertEquals(columns("A.C1"), insert.getGraph().getSources(new Column("B.C1")));
    }

    @Test
    public void quotedCteAliasesAreDistinctInPostgres() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH \"Q\" AS (SELECT c1 FROM a), q AS (SELECT c1 FROM b)"
                        + " SELECT c1 FROM \"Q\";");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("a.c1"),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.c1")));
    }

    @Test
    public void cteColumnAliasesChainToSource() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH q(v) AS (SELECT c1 FROM a) SELECT v FROM q;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("a.c1"),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.v")));
    }

    @Test
    public void quotedAndUnquotedCteNamesStayDistinctInOneQuery() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH \"Q\" AS (SELECT c1 FROM a), q AS (SELECT c1 FROM b)"
                        + " SELECT \"Q\".c1 AS high, q.c1 AS low"
                        + " FROM \"Q\" CROSS JOIN q;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("a.c1"),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.high")));
        assertEquals(columns("b.c1"),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.low")));
    }

    @Test
    public void schemaQualifiedReferenceIsNotAQuotedCte() {
        MetadataContext context = pgContext(
                "CREATE TABLE public.q (c1 INT); CREATE TABLE b (c1 INT);");

        ScanResult result = new LineageScanner(context).scan(
                "WITH \"public.q\" AS (SELECT c1 FROM b) SELECT c1 FROM public.q;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("public.q.c1"),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.c1")));
    }

    @Test
    public void cteColumnAliasesApplyToWildcard() {
        MetadataContext context = pgContext("CREATE TABLE a (c1 INT, c2 INT);");

        ScanResult result = new LineageScanner(context).scan(
                "WITH q(v,w) AS (SELECT * FROM a) SELECT v,w FROM q;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("a.c1"),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.v")));
        assertEquals(columns("a.c2"),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.w")));
    }

    @Test
    public void cteUnquotedAliasFoldsInPostgres() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH q(V) AS (SELECT c1 FROM a) SELECT v FROM q;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("a.c1"),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.v")));
    }

    @Test
    public void cteQuotedAndUnquotedAliasesStayDistinct() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH q(\"V\", v) AS (SELECT \"C1\", c1 FROM a)"
                        + " SELECT \"V\", v FROM q;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("a.C1"),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.V")));
        assertEquals(columns("a.c1"),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.v")));
    }

    @Test
    public void quotedSingleSegmentAndSchemaQualifiedReferencesStayDistinct() {
        MetadataContext context = pgContext(
                "CREATE TABLE public.q (c1 INT); CREATE TABLE a (c1 INT);");

        ScanResult result = new LineageScanner(context).scan(
                "WITH \"public.q\" AS (SELECT c1 FROM a)"
                        + " SELECT \"public.q\".c1 AS cte_col, public.q.c1 AS phys_col"
                        + " FROM \"public.q\" CROSS JOIN public.q;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("a.c1"),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.cte_col")));
        assertEquals(columns("public.q.c1"),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.phys_col")));
    }

    @Test
    public void quotedTableAliasResolvesToItsSource() {
        MetadataContext context = pgContext(
                "CREATE TABLE a (c1 INT); CREATE TABLE b (c1 INT);");

        ScanResult result = new LineageScanner(context).scan(
                "SELECT \"Q\".c1 FROM a AS \"Q\" CROSS JOIN b AS q;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("a.c1"),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.c1")));
    }

    @Test
    public void cteDeclaredColumnsFeedDownstreamWildcardInsert() {
        MetadataContext context = pgContext(
                "CREATE TABLE a (c1 INT, c2 INT);"
                        + "CREATE TABLE outtab (c1 INT, c2 INT);");

        ScanResult result = new LineageScanner(context).scan(
                "WITH q(v,w) AS (SELECT * FROM a)"
                        + " INSERT INTO outtab SELECT * FROM q;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("a.c1"), result.getGraph().getSources(new Column("outtab.c1")));
        assertEquals(columns("a.c2"), result.getGraph().getSources(new Column("outtab.c2")));
    }

    @Test
    public void cteDeclaredColumnsApplyAcrossMultipleStarProjections() {
        MetadataContext context = pgContext(
                "CREATE TABLE a (c1 INT, c2 INT); CREATE TABLE b (c1 INT);");

        ScanResult result = new LineageScanner(context).scan(
                "WITH q(v,w,x) AS (SELECT a.*, b.* FROM a, b) SELECT v,w,x FROM q;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("a.c1"),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.v")));
        assertEquals(columns("a.c2"),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.w")));
        assertEquals(columns("b.c1"),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.x")));
    }

    @Test
    public void ctePartialColumnAliasesKeepRemainingColumns() {
        MetadataContext context = pgContext("CREATE TABLE a (c1 INT, c2 INT);");

        ScanResult result = new LineageScanner(context).scan(
                "WITH q(v) AS (SELECT * FROM a) SELECT v, c2 FROM q;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("a.c1"),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.v")));
        assertEquals(columns("a.c2"),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.c2")));
    }

    @Test
    public void cteDeclaredAliasLookupFoldsUnquotedReference() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH q(v) AS (SELECT c1 FROM a) SELECT V FROM q;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("a.c1"),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.V")));
    }

    @Test
    public void quotedAndUnquotedPhysicalTablesStayDistinct() {
        MetadataContext context = pgContext(
                "CREATE TABLE public.\"public.q\" (c1 INT);"
                        + "CREATE TABLE public.q (c1 INT);");

        ScanResult result = new LineageScanner(context).scan(
                "SELECT \"public.q\".c1 AS quoted_col, public.q.c1 AS plain_col"
                        + " FROM \"public.q\" CROSS JOIN public.q;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("public.\"public.q\".c1"),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.quoted_col")));
        assertEquals(columns("public.q.c1"),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.plain_col")));
    }

    @Test
    public void crossTableWildcardUsesPerSourceAliasSlices() {
        MetadataContext context = pgContext(
                "CREATE TABLE a (c1 INT, c2 INT);"
                        + "CREATE TABLE b (c1 INT);"
                        + "CREATE TABLE outtab (v INT, w INT, x INT);");

        ScanResult result = new LineageScanner(context).scan(
                "WITH q(v,w,x) AS (SELECT * FROM a CROSS JOIN b)"
                        + " INSERT INTO outtab SELECT * FROM q;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("a.c1"), result.getGraph().getSources(new Column("outtab.v")));
        assertEquals(columns("a.c2"), result.getGraph().getSources(new Column("outtab.w")));
        assertEquals(columns("b.c1"), result.getGraph().getSources(new Column("outtab.x")));
    }

    @Test
    public void nestedCteWildcardUsesDeclaredWidth() {
        MetadataContext context = pgContext("CREATE TABLE a (c1 INT, c2 INT);");

        ScanResult result = new LineageScanner(context).scan(
                "WITH r(v,w) AS (SELECT * FROM a),"
                        + " q(x,y) AS (SELECT r.* FROM r) SELECT x,y FROM q;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("a.c1"),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.x")));
        assertEquals(columns("a.c2"),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.y")));
    }

    @Test
    public void nestedCtePartialDeclarationUsesActualWidth() {
        MetadataContext context = pgContext("CREATE TABLE a (c1 INT, c2 INT);");

        ScanResult result = new LineageScanner(context).scan(
                "WITH r(v) AS (SELECT * FROM a),"
                        + " q(x,y) AS (SELECT r.* FROM r) SELECT x,y FROM q;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("a.c1"),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.x")));
        assertEquals(columns("a.c2"),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.y")));
    }

    @Test
    public void nestedCteWithoutDeclarationUsesActualWidth() {
        MetadataContext context = pgContext("CREATE TABLE a (c1 INT, c2 INT);");

        ScanResult result = new LineageScanner(context).scan(
                "WITH r AS (SELECT * FROM a),"
                        + " q(x,y) AS (SELECT r.* FROM r) SELECT x,y FROM q;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("a.c1"),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.x")));
        assertEquals(columns("a.c2"),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.y")));
    }

    @Test
    public void derivedTableWildcardUsesActualWidth() {
        MetadataContext context = pgContext("CREATE TABLE a (c1 INT, c2 INT);");

        ScanResult result = new LineageScanner(context).scan(
                "SELECT c1,c2 FROM (SELECT r.* FROM (SELECT * FROM a) r) q;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("a.c1"),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.c1")));
        assertEquals(columns("a.c2"),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.c2")));
    }

    @Test
    public void joinUsingStarUsesSqlOutputOrder() {
        MetadataContext context = pgContext(
                "CREATE TABLE a (c1 INT, c2 INT);"
                        + "CREATE TABLE b (c1 INT, c3 INT);");

        ScanResult result = new LineageScanner(context).scan(
                "WITH q(v,w,x) AS (SELECT * FROM a LEFT JOIN b USING(c1))"
                        + " SELECT v,w,x FROM q;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("a.c1"),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.v")));
        assertEquals(columns("a.c2"),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.w")));
        assertEquals(columns("b.c3"),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.x")));
    }

    @Test
    public void fullJoinUsingMergedColumnHasBothSources() {
        MetadataContext context = pgContext(
                "CREATE TABLE a (c1 INT, c2 INT);"
                        + "CREATE TABLE b (c1 INT, c3 INT);");

        ScanResult result = new LineageScanner(context).scan(
                "WITH q(v,w,x) AS (SELECT * FROM a FULL JOIN b USING(c1))"
                        + " SELECT v,w,x FROM q;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("a.c1", "b.c1"),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.v")));
        assertEquals(columns("a.c2"),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.w")));
        assertEquals(columns("b.c3"),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.x")));
    }

    @Test
    public void usingDoesNotRemoveUnrelatedSameNameColumns() {
        MetadataContext context = pgContext(
                "CREATE TABLE a (c1 INT, c2 INT);"
                        + "CREATE TABLE b (c1 INT, c3 INT);"
                        + "CREATE TABLE c (c1 INT, c4 INT);");

        ScanResult result = new LineageScanner(context).scan(
                "WITH q(v,w,x,y,z) AS"
                        + " (SELECT * FROM (a LEFT JOIN b USING(c1)) CROSS JOIN c)"
                        + " SELECT v,w,x,y,z FROM q;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("a.c1"),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.v")));
        assertEquals(columns("a.c2"),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.w")));
        assertEquals(columns("b.c3"),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.x")));
        assertEquals(columns("c.c1"),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.y")));
        assertEquals(columns("c.c4"),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.z")));
    }

    @Test
    public void usingDoesNotRemoveQuotedSameNameInPostgres() {
        MetadataContext context = pgContext(
                "CREATE TABLE a (\"C1\" INT, c1 INT, c2 INT);"
                        + "CREATE TABLE b (c1 INT, c3 INT);");

        ScanResult result = new LineageScanner(context).scan(
                "WITH q(v,w,x,y) AS"
                        + " (SELECT * FROM a LEFT JOIN b USING(c1))"
                        + " SELECT v,w,x,y FROM q;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("a.c1"),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.v")));
        assertEquals(columns("a.C1"),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.w")));
        assertEquals(columns("a.c2"),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.x")));
        assertEquals(columns("b.c3"),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.y")));
    }

    @Test
    public void unqualifiedColumnMatchesTemporaryOutputColumns() {
        MetadataContext context = pgContext(
                "CREATE TABLE a (c2 INT); CREATE TABLE b (c1 INT);");

        ScanResult first = new LineageScanner(context).scan(
                "WITH q AS (SELECT c2 FROM a) SELECT c2 FROM b CROSS JOIN q;");
        assertEquals(0, first.getFailures().size());
        assertEquals(columns("a.c2"),
                first.getGraph().getSources(Column.temporary("stmt0.temp0.c2")));

        ScanResult second = new LineageScanner(context).scan(
                "WITH q AS (SELECT c2 FROM a) SELECT c2 FROM q CROSS JOIN b;");
        assertEquals(0, second.getFailures().size());
        assertEquals(columns("a.c2"),
                second.getGraph().getSources(Column.temporary("stmt0.temp0.c2")));
    }

    @Test
    public void nestedJoinUsingUsesVisibleJoinedOutput() {
        MetadataContext context = pgContext(
                "CREATE TABLE a (c1 INT, c2 INT);"
                        + "CREATE TABLE b (c1 INT, c3 INT);"
                        + "CREATE TABLE c (c1 INT, c4 INT);");

        ScanResult result = new LineageScanner(context).scan(
                "SELECT c1 FROM (a LEFT JOIN b USING(c1)) LEFT JOIN c USING(c1);");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("a.c1"),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.c1")));
    }

    @Test
    public void crossJoinMakesUsingColumnAmbiguous() {
        MetadataContext context = pgContext(
                "CREATE TABLE a (c1 INT, c2 INT);"
                        + "CREATE TABLE b (c1 INT, c3 INT);"
                        + "CREATE TABLE c (c1 INT, c4 INT);");

        ScanResult result = new LineageScanner(context).scan(
                "SELECT c1 FROM (a LEFT JOIN b USING(c1)) CROSS JOIN c;");

        assertEquals(1, result.getResults().size());
        assertTrue(result.getResults().get(0).getError()
                instanceof com.laotie.exception.AmbiguousColumnException);
    }

    @Test
    public void quotedTemporaryAliasResolves() {
        MetadataContext context = pgContext("CREATE TABLE a (c1 INT);");

        ScanResult result = new LineageScanner(context).scan(
                "WITH q AS (SELECT c1 AS \"K\" FROM a) SELECT \"K\" FROM q;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("a.c1"),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.K")));
    }

    @Test
    public void missingQualifiedTemporaryColumnFails() {
        MetadataContext context = pgContext("CREATE TABLE a (c2 INT);");

        ScanResult result = new LineageScanner(context).scan(
                "WITH q AS (SELECT c2 FROM a) SELECT q.missing FROM q;");

        assertEquals(1, result.getResults().size());
        assertTrue(result.getResults().get(0).getError()
                instanceof ColumnNotFoundException);
        assertTrue(result.getResults().get(0).getInstructions().isEmpty());
        assertTrue(result.getGraph().getReverseGraph().vertexSet().isEmpty());
    }

    @Test
    public void qualifiedStarRegistersOnlyItsSourceColumns() {
        MetadataContext context = pgContext(
                "CREATE TABLE a (c1 INT, c2 INT);"
                        + "CREATE TABLE b (c1 INT, c3 INT);");

        ScanResult result = new LineageScanner(context).scan(
                "WITH q AS (SELECT a.* FROM a LEFT JOIN b USING(c1))"
                        + " SELECT q.c1,q.c2 FROM q;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("a.c1"),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.c1")));
        assertEquals(columns("a.c2"),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.c2")));
    }

    @Test
    public void qualifiedStarDoesNotExposeOtherJoinColumns() {
        MetadataContext context = pgContext(
                "CREATE TABLE a (c1 INT, c2 INT);"
                        + "CREATE TABLE b (c1 INT, c3 INT);");

        ScanResult result = new LineageScanner(context).scan(
                "WITH q AS (SELECT a.* FROM a LEFT JOIN b USING(c1))"
                        + " SELECT q.c3 FROM q; SELECT c1 FROM b;");

        assertEquals(2, result.getResults().size());
        assertTrue(result.getResults().get(0).getError()
                instanceof ColumnNotFoundException);
        assertTrue(result.getResults().get(0).getInstructions().isEmpty());
        assertNull(result.getResults().get(1).getError());
        assertEquals(columns("b.c1"),
                result.getGraph().getSources(Column.temporary("stmt1.temp0.c1")));
    }

    @Test
    public void usingKeyMissingOnOneSideFailsTheStatement() {
        MetadataContext context = pgContext(
                "CREATE TABLE a (c2 INT); CREATE TABLE b (c1 INT);");

        ScanResult result = new LineageScanner(context).scan(
                "SELECT c2 FROM a LEFT JOIN b USING(c2); SELECT c1 FROM b;");

        assertEquals(2, result.getResults().size());
        assertTrue(result.getResults().get(0).getError()
                instanceof ColumnNotFoundException);
        assertTrue(result.getResults().get(0).getInstructions().isEmpty());
        assertNull(result.getResults().get(1).getError());
        assertEquals(columns("b.c1"),
                result.getGraph().getSources(Column.temporary("stmt1.temp0.c1")));
    }

    @Test
    public void usingRequiresExactlyOneMatchPerSide() {
        MetadataContext context = pgContext(
                "CREATE TABLE a (c1 INT, c2 INT);"
                        + "CREATE TABLE b (c1 INT, c3 INT);"
                        + "CREATE TABLE c (c1 INT, c4 INT);");

        ScanResult result = new LineageScanner(context).scan(
                "SELECT a.c2 FROM (a CROSS JOIN b) LEFT JOIN c USING(c1);"
                        + " SELECT c1 FROM c;");

        assertEquals(2, result.getResults().size());
        assertTrue(result.getResults().get(0).getError()
                instanceof ColumnNotFoundException);
        assertTrue(result.getResults().get(0).getInstructions().isEmpty());
        assertNull(result.getResults().get(1).getError());
        assertEquals(columns("c.c1"),
                result.getGraph().getSources(Column.temporary("stmt1.temp0.c1")));
    }

    @Test
    public void duplicateUsingKeyIsRejected() {
        MetadataContext context = pgContext(
                "CREATE TABLE a (c1 INT); CREATE TABLE b (c1 INT);");

        ScanResult result = new LineageScanner(context)
                .scan("SELECT c1 FROM a JOIN b USING(c1,c1);");

        assertEquals(1, result.getResults().size());
        assertTrue(result.getResults().get(0).getError()
                instanceof ColumnNotFoundException);
        assertTrue(result.getResults().get(0).getInstructions().isEmpty());
    }

    @Test
    public void quotedStarFieldIsNotAWildcard() {
        MetadataContext context = pgContext(
                "CREATE TABLE d (\"*\" INT, c1 INT);"
                        + "CREATE TABLE b (c1 INT, c3 INT);");

        ScanResult result = new LineageScanner(context).scan(
                "WITH q AS (SELECT d.\"*\" AS picked FROM d LEFT JOIN b USING(c1))"
                        + " SELECT q.picked FROM q;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        Set<Column> sources = result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.picked"));
        assertEquals(1, sources.size());
        Column source = sources.iterator().next();
        assertEquals("d", source.getTableName());
        assertEquals("*", source.getColumnName());
    }

    @Test
    public void unknownQualifiedStarFails() {
        MetadataContext context = pgContext(
                "CREATE TABLE a (c1 INT, c2 INT);"
                        + "CREATE TABLE b (c1 INT, c3 INT);");

        ScanResult result = new LineageScanner(context).scan(
                "SELECT z.* FROM a LEFT JOIN b USING(c1); SELECT c1 FROM b;");

        assertEquals(2, result.getResults().size());
        assertTrue(result.getResults().get(0).getError()
                instanceof MetadataNotFoundException);
        assertTrue(result.getResults().get(0).getInstructions().isEmpty());
        assertNull(result.getResults().get(1).getError());
        assertEquals(columns("b.c1"),
                result.getGraph().getSources(Column.temporary("stmt1.temp0.c1")));
    }

    @Test
    public void mysqlUsingMatchesQuotedColumnCaseInsensitively() {
        MetadataContext context = mysqlContext(
                "CREATE TABLE a (`C1` INT, c2 INT);"
                        + "CREATE TABLE b (c1 INT, c3 INT);");

        ScanResult result = new LineageScanner(context)
                .scan("SELECT * FROM a LEFT JOIN b USING(c1);");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("a.C1"),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.C1")));
    }

    @Test
    public void derivedTableColumnAliasesApplyToUsing() {
        MetadataContext context = pgContext(
                "CREATE TABLE a (c1 INT); CREATE TABLE b (k INT, c3 INT);");

        ScanResult result = new LineageScanner(context).scan(
                "SELECT * FROM (SELECT c1 FROM a) d(k) LEFT JOIN b USING(k);");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("a.c1"),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.k")));
    }

    @Test
    public void qualifiedStarWithoutFromFails() {
        MetadataContext context = pgContext("CREATE TABLE a (c1 INT);");

        ScanResult result = new LineageScanner(context).scan(
                "SELECT a.*; SELECT c1 FROM a;");

        assertEquals(2, result.getResults().size());
        assertTrue(result.getResults().get(0).getError()
                instanceof MetadataNotFoundException);
        assertTrue(result.getResults().get(0).getInstructions().isEmpty());
        assertNull(result.getResults().get(1).getError());
        assertEquals(columns("a.c1"),
                result.getGraph().getSources(Column.temporary("stmt1.temp0.c1")));
    }

    @Test
    public void nestedFullJoinMergedColumnIsOneVisibleColumn() {
        MetadataContext context = pgContext(
                "CREATE TABLE a (c1 INT, c2 INT);"
                        + "CREATE TABLE b (c1 INT, c3 INT);"
                        + "CREATE TABLE c (c1 INT, c4 INT);");

        ScanResult result = new LineageScanner(context).scan(
                "SELECT c1 FROM (a FULL JOIN b USING(c1)) LEFT JOIN c USING(c1);");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("a.c1", "b.c1"),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.c1")));
    }

    @Test
    public void derivedUnionPartialAliasesKeepFirstBranchNames() {
        MetadataContext context = pgContext(
                "CREATE TABLE a (c1 INT, c2 INT); CREATE TABLE b (c1 INT, c3 INT);");

        ScanResult result = new LineageScanner(context).scan(
                "SELECT d.c2 FROM"
                        + " (SELECT c1,c2 FROM a UNION ALL SELECT c1,c3 FROM b) d(k);");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("a.c2", "b.c3"),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.c2")));
    }

    @Test
    public void derivedUnionStarKeepsFirstBranchNames() {
        MetadataContext context = pgContext(
                "CREATE TABLE a (c1 INT, c2 INT); CREATE TABLE b (c1 INT, c3 INT);");

        ScanResult result = new LineageScanner(context).scan(
                "SELECT * FROM"
                        + " (SELECT c1,c2 FROM a UNION ALL SELECT c1,c3 FROM b) d(k);");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("a.c1", "b.c1"),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.k")));
        assertEquals(columns("a.c2", "b.c3"),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.c2")));
    }

    @Test
    public void unionConstantFirstBranchKeepsColumnNames() {
        MetadataContext context = pgContext("CREATE TABLE b (c1 INT, c3 INT);");

        ScanResult explicit = new LineageScanner(context).scan(
                "SELECT d.label FROM"
                        + " (SELECT 0 AS flag, 1 AS label"
                        + " UNION ALL SELECT c1,c3 FROM b) d(k);");
        assertEquals(0, explicit.getFailures().size());
        assertEquals(columns("b.c3"),
                explicit.getGraph().getSources(Column.temporary("stmt0.temp0.label")));

        ScanResult star = new LineageScanner(context).scan(
                "SELECT * FROM"
                        + " (SELECT 0 AS flag, 1 AS label"
                        + " UNION ALL SELECT c1,c3 FROM b) d(k);");
        assertEquals(0, star.getFailures().size());
        assertEquals(columns("b.c1"),
                star.getGraph().getSources(Column.temporary("stmt0.temp0.k")));
        assertEquals(columns("b.c3"),
                star.getGraph().getSources(Column.temporary("stmt0.temp0.label")));
    }

    @Test
    public void unionConstantBranchAtTheEndKeepsFirstBranchNames() {
        MetadataContext context = pgContext("CREATE TABLE b (c1 INT, c3 INT);");

        ScanResult result = new LineageScanner(context).scan(
                "SELECT * FROM"
                        + " (SELECT c1,c3 FROM b"
                        + " UNION ALL SELECT 0 AS flag, 1 AS label) d(k);");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("b.c1"),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.k")));
        assertEquals(columns("b.c3"),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.c3")));
    }

    @Test
    public void unionQuotedConstantBranchFirstKeepsNamesOnce() {
        MetadataContext context = pgContext("CREATE TABLE b (c1 INT, c3 INT);");

        ScanResult result = new LineageScanner(context).scan(
                "SELECT * FROM"
                        + " (SELECT 0 AS flag, 1 AS \"display name\""
                        + " UNION ALL SELECT c1,c3 FROM b) d;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("b.c3"),
                result.getGraph().getSources(
                        Column.temporary("stmt0.temp0.display name")));
        assertEquals(columns("b.c1"),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.flag")));
        for (Instruction instruction : result.getResults().get(0).getInstructions()) {
            assertFalse("double-quoted output name: " + instruction,
                    instruction.getTo().contains("\"\""));
        }
    }

    @Test
    public void unionQuotedConstantBranchLastKeepsFirstBranchNames() {
        MetadataContext context = pgContext("CREATE TABLE b (c1 INT, c3 INT);");

        ScanResult result = new LineageScanner(context).scan(
                "SELECT * FROM"
                        + " (SELECT c1,c3 FROM b"
                        + " UNION ALL SELECT 0 AS flag, 1 AS \"display name\") d;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("b.c1"),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.c1")));
        assertEquals(columns("b.c3"),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.c3")));
        for (Instruction instruction : result.getResults().get(0).getInstructions()) {
            assertFalse("double-quoted output name: " + instruction,
                    instruction.getTo().contains("\"\""));
        }
    }

    @Test
    public void unknownDerivedWidthDoesNotBecomeKnown() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "SELECT * FROM (SELECT * FROM unknown_a) d"
                        + " UNION ALL SELECT c1,c3 FROM b;"
                        + " SELECT c1 FROM b;");

        assertEquals(2, result.getResults().size());
        assertNull(result.getResults().get(0).getError());
        assertNull(result.getResults().get(1).getError());
    }

    @Test
    public void unknownCteWidthDoesNotBecomeKnown() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH q AS (SELECT * FROM unknown_a) SELECT * FROM q"
                        + " UNION ALL SELECT c1,c3 FROM b;"
                        + " SELECT c1 FROM b;");

        assertEquals(2, result.getResults().size());
        assertNull(result.getResults().get(0).getError());
        assertNull(result.getResults().get(1).getError());
    }

    @Test
    public void knownUnionWidthSurvivesAnUnknownMiddleBranch() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "SELECT * FROM (SELECT c1 FROM a"
                        + " UNION ALL SELECT * FROM unknown_b"
                        + " UNION ALL SELECT c1,c3 FROM b) d;"
                        + " SELECT c1 FROM b;");

        assertEquals(2, result.getResults().size());
        assertTrue(result.getResults().get(0).getError() != null);
        assertTrue(result.getResults().get(0).getInstructions().isEmpty());
        assertNull(result.getResults().get(1).getError());
    }

    @Test
    public void unknownCteOutputAllowsQualifiedColumn() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH q AS (SELECT * FROM a) SELECT q.c1 FROM q;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertTrue(hasMapping(result, "q.c1", "temp0.c1"));
    }

    @Test
    public void unknownDerivedStarAllowsQualifiedColumn() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "SELECT d.c1 FROM (SELECT a.* FROM a) d;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertTrue(hasMapping(result, "d.c1", "temp0.c1"));
    }

    @Test
    public void knownCteOutputRejectsMissingColumn() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH q AS (SELECT c1 FROM a) SELECT q.missing FROM q;"
                        + " SELECT c1 FROM a;");

        assertEquals(2, result.getResults().size());
        assertTrue(result.getResults().get(0).getError() instanceof ColumnNotFoundException);
        assertTrue(result.getResults().get(0).getInstructions().isEmpty());
        assertNull(result.getResults().get(1).getError());
    }

    @Test
    public void unknownNameUnionWithKnownWidthAllowsQualifiedColumn() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH q AS (SELECT * FROM a"
                        + " UNION ALL SELECT c1,c3 FROM b)"
                        + " SELECT q.c1 FROM q;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertTrue(hasMapping(result, "q.c1", "temp0.c1"));
    }

    @Test
    public void unqualifiedColumnBindsToKnownCteOutput() {
        String ctes = "WITH x AS (SELECT c2 FROM a), y AS (SELECT c1 FROM b) ";

        ScanResult xFirst = new LineageScanner(DbType.postgresql)
                .scan(ctes + "SELECT c1 FROM x CROSS JOIN y;");
        assertEquals(0, xFirst.getFailures().size());
        assertTrue(hasMapping(xFirst, "y.c1", "temp0.c1"));

        ScanResult yFirst = new LineageScanner(DbType.postgresql)
                .scan(ctes + "SELECT c1 FROM y CROSS JOIN x;");
        assertEquals(0, yFirst.getFailures().size());
        assertTrue(hasMapping(yFirst, "y.c1", "temp0.c1"));
    }

    @Test
    public void knownCteOutputAmbiguityFails() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH x AS (SELECT c1 FROM a), y AS (SELECT c1 FROM b)"
                        + " SELECT c1 FROM x CROSS JOIN y;"
                        + " SELECT c1 FROM b;");

        assertEquals(2, result.getResults().size());
        assertTrue(result.getResults().get(0).getError()
                instanceof com.laotie.exception.AmbiguousColumnException);
        assertTrue(result.getResults().get(0).getInstructions().isEmpty());
        assertNull(result.getResults().get(1).getError());
    }

    @Test
    public void knownCteJoinUsingBindsMergedColumnToBothSides() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH x AS (SELECT c1, c2 FROM a), y AS (SELECT c1, c3 FROM b)"
                        + " SELECT c1 FROM x JOIN y USING(c1);");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("a.c1", "b.c1"), result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.c1")));
    }

    @Test
    public void nestedUsingVisibleOutputBindsOnlyLeftSources() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH x AS (SELECT c1 FROM a), y AS (SELECT c1 FROM b),"
                        + " z AS (SELECT c1 FROM c)"
                        + " SELECT c1 FROM (x LEFT JOIN y USING(c1))"
                        + " LEFT JOIN z USING(c1);");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("a.c1"), result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.c1")));
    }

    @Test
    public void nestedUsingWithOuterCrossJoinIsAmbiguous() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH x AS (SELECT c1 FROM a), y AS (SELECT c1 FROM b),"
                        + " z AS (SELECT c1 FROM c)"
                        + " SELECT c1 FROM (x LEFT JOIN y USING(c1))"
                        + " CROSS JOIN z;");

        assertEquals(1, result.getResults().size());
        assertTrue(result.getResults().get(0).getError()
                instanceof com.laotie.exception.AmbiguousColumnException);
        assertTrue(result.getResults().get(0).getInstructions().isEmpty());
    }

    @Test
    public void tooManyCteColumnAliasesFail() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH q(x,y,z) AS (SELECT * FROM a"
                        + " UNION ALL SELECT c1,c3 FROM b)"
                        + " SELECT q.y FROM q;"
                        + " SELECT c1 FROM b;");

        assertEquals(2, result.getResults().size());
        assertTrue(result.getResults().get(0).getError()
                instanceof IllegalStateException);
        assertTrue(result.getResults().get(0).getInstructions().isEmpty());
        assertNull(result.getResults().get(1).getError());
    }

    @Test
    public void tooManyDerivedColumnAliasesFail() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "SELECT d.y FROM (SELECT * FROM a"
                        + " UNION ALL SELECT c1,c3 FROM b) d(x,y,z);"
                        + " SELECT c1 FROM b;");

        assertEquals(2, result.getResults().size());
        assertTrue(result.getResults().get(0).getError()
                instanceof IllegalStateException);
        assertTrue(result.getResults().get(0).getInstructions().isEmpty());
        assertNull(result.getResults().get(1).getError());
    }

    @Test
    public void knownCteAmbiguitySurvivesAnUnknownSource() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH x AS (SELECT c1 FROM a), y AS (SELECT c1 FROM b)"
                        + " SELECT c1 FROM x CROSS JOIN y CROSS JOIN z;"
                        + " SELECT c1 FROM b;");

        assertEquals(2, result.getResults().size());
        assertTrue(result.getResults().get(0).getError()
                instanceof com.laotie.exception.AmbiguousColumnException);
        assertTrue(result.getResults().get(0).getInstructions().isEmpty());
        assertNull(result.getResults().get(1).getError());
    }

    @Test
    public void mysqlRejectsTooFewCteColumnAliases() {
        ScanResult result = new LineageScanner(DbType.mysql).scan(
                "WITH q(x) AS (SELECT c1,c2 FROM a) SELECT q.x FROM q;"
                        + " SELECT c1 FROM a;");

        assertEquals(2, result.getResults().size());
        assertTrue(result.getResults().get(0).getError()
                instanceof IllegalStateException);
        assertTrue(result.getResults().get(0).getInstructions().isEmpty());
        assertNull(result.getResults().get(1).getError());
    }

    @Test
    public void postgresAllowsPartialCteColumnAliases() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH q(x) AS (SELECT c1,c2 FROM a) SELECT q.x FROM q;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("a.c1"), result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.x")));
    }

    @Test
    public void explicitOutputNameIsAmbiguousDespiteUnknownSiblingColumns() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH x AS (SELECT a.*, 1 AS needle FROM a),"
                        + " y AS (SELECT c1 AS needle FROM b)"
                        + " SELECT needle FROM y CROSS JOIN x;"
                        + " SELECT c1 FROM b;");

        assertEquals(2, result.getResults().size());
        assertTrue(result.getResults().get(0).getError()
                instanceof com.laotie.exception.AmbiguousColumnException);
        assertTrue(result.getResults().get(0).getInstructions().isEmpty());
        assertNull(result.getResults().get(1).getError());
    }

    @Test
    public void mysqlDeclaredCteWidthConstrainsOuterUnion() {
        ScanResult result = new LineageScanner(DbType.mysql).scan(
                "WITH q(x,y) AS (SELECT * FROM a)"
                        + " SELECT q.* FROM q UNION ALL SELECT c1 FROM b;"
                        + " SELECT c1 FROM b;");

        assertEquals(2, result.getResults().size());
        assertTrue(result.getResults().get(0).getError() != null);
        assertTrue(result.getResults().get(0).getInstructions().isEmpty());
        assertNull(result.getResults().get(1).getError());
    }

    @Test
    public void mysqlDeclaredCteNamesRejectUnknownColumn() {
        ScanResult result = new LineageScanner(DbType.mysql).scan(
                "WITH q(x,y) AS (SELECT * FROM a) SELECT q.z FROM q;");

        assertEquals(1, result.getResults().size());
        assertTrue(result.getResults().get(0).getError()
                instanceof ColumnNotFoundException);
        assertTrue(result.getResults().get(0).getInstructions().isEmpty());
    }

    @Test
    public void wildcardPlaceholderDoesNotCollideWithExplicitAlias() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH q AS (SELECT a.*, 1 AS col1 FROM a)"
                        + " SELECT col1 FROM q;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertTrue(hasMapping(result, "q.col1", "temp0.col1"));
    }

    @Test
    public void mysqlDeclaredAliasShorterThanKnownMinimumFails() {
        ScanResult result = new LineageScanner(DbType.mysql).scan(
                "WITH q(x) AS (SELECT a.c1, a.c2, a.* FROM a)"
                        + " SELECT q.x FROM q;"
                        + " SELECT c1 FROM a;");

        assertEquals(2, result.getResults().size());
        assertTrue(result.getResults().get(0).getError()
                instanceof IllegalStateException);
        assertTrue(result.getResults().get(0).getInstructions().isEmpty());
        assertNull(result.getResults().get(1).getError());
    }

    @Test
    public void declaredWidthPlacesColumnsAfterUnknownWildcard() {
        ScanResult result = new LineageScanner(DbType.mysql).scan(
                "WITH q(x,y,z) AS (SELECT a.*, b.c1 FROM a CROSS JOIN b)"
                        + " SELECT q.y, q.z FROM q;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("b.c1"), result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.z")));
        assertFalse(result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.y"))
                .contains(new com.laotie.model.metadata.Column("b.c1")));
    }

    @Test
    public void mysqlDeclaredAliasShorterThanNestedMinimumFails() {
        ScanResult result = new LineageScanner(DbType.mysql).scan(
                "WITH t AS (SELECT a.c1, a.c2, a.* FROM a),"
                        + " q(x) AS (SELECT t.* FROM t)"
                        + " SELECT q.x FROM q;"
                        + " SELECT c1 FROM a;");

        assertEquals(2, result.getResults().size());
        assertTrue(result.getResults().get(0).getError()
                instanceof IllegalStateException);
        assertTrue(result.getResults().get(0).getInstructions().isEmpty());
        assertNull(result.getResults().get(1).getError());
    }

    @Test
    public void declaredWidthPlacesTrailingColumnAfterMultipleWildcards() {
        ScanResult result = new LineageScanner(DbType.mysql).scan(
                "WITH q(w,x,y,z) AS (SELECT a.*, b.*, a.c1 FROM a CROSS JOIN b)"
                        + " SELECT q.y, q.z FROM q;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("a.c1"), result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.z")));
        assertFalse(result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.y"))
                .contains(new com.laotie.model.metadata.Column("a.c1")));
    }

    @Test
    public void noMetadataJoinUsingUsesVisiblePlan() {
        ScanResult result = new LineageScanner(DbType.mysql).scan(
                "WITH x AS (SELECT c1,c2 FROM a), y AS (SELECT c1,c3 FROM b),"
                        + " q(u,v,w) AS (SELECT * FROM x JOIN y USING(c1))"
                        + " SELECT q.u, q.w FROM q;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("a.c1", "b.c1"), result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.u")));
        assertEquals(columns("b.c3"), result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.w")));
    }

    @Test
    public void unionKnownWidthConflictsWithOtherBranchMinimum() {
        ScanResult result = new LineageScanner(DbType.mysql).scan(
                "SELECT c1 FROM b"
                        + " UNION ALL SELECT a.c1, a.c2, a.* FROM a;"
                        + " SELECT c1 FROM b;");

        assertEquals(2, result.getResults().size());
        assertTrue(result.getResults().get(0).getError()
                instanceof IllegalStateException);
        assertTrue(result.getResults().get(0).getInstructions().isEmpty());
        assertNull(result.getResults().get(1).getError());
    }

    @Test
    public void uncertainProjectionBetweenUnknownWildcardsEmitsNoMapping() {
        ScanResult result = new LineageScanner(DbType.mysql).scan(
                "WITH q(w,x,y,z) AS"
                        + " (SELECT a.*, b.c1, c.* FROM a CROSS JOIN b CROSS JOIN c)"
                        + " SELECT q.x, q.y FROM q;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        for (Instruction instruction : result.getResults().get(0).getInstructions()) {
            assertFalse("positional guess leaked: " + instruction,
                    "b.c1".equals(instruction.getFrom()));
        }
    }

    @Test
    public void unionWildcardRightBranchKeepsUsingSources() {
        ScanResult result = new LineageScanner(DbType.mysql).scan(
                "WITH x AS (SELECT c1 FROM b), y AS (SELECT c1 FROM c),"
                        + " q AS (SELECT c1 AS k FROM a"
                        + " UNION ALL SELECT * FROM x JOIN y USING(c1))"
                        + " SELECT q.k FROM q;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("a.c1", "b.c1", "c.c1"), result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.k")));
    }

    @Test
    public void unionKnownWidthAnchorsTrailingColumnOfUnknownBranch() {
        String knownFirst = "WITH q AS (SELECT c1 AS x, c2 AS y, c3 AS z FROM c"
                + " UNION ALL SELECT a.*, b.c1 FROM a CROSS JOIN b)"
                + " SELECT q.y, q.z FROM q;";
        ScanResult first = new LineageScanner(DbType.mysql).scan(knownFirst);
        assertEquals(0, first.getFailures().size());
        assertEquals(columns("c.c3", "b.c1"), first.getGraph()
                .getSources(Column.temporary("stmt0.temp0.z")));
        assertFalse(first.getGraph()
                .getSources(Column.temporary("stmt0.temp0.y"))
                .contains(new com.laotie.model.metadata.Column("b.c1")));

        String unknownFirst = "WITH q(x,y,z) AS (SELECT a.*, b.c1 FROM a CROSS JOIN b"
                + " UNION ALL SELECT c1 AS x2, c2 AS y2, c3 AS z2 FROM c)"
                + " SELECT q.y, q.z FROM q;";
        ScanResult second = new LineageScanner(DbType.mysql).scan(unknownFirst);
        assertEquals(0, second.getFailures().size());
        assertEquals(columns("c.c3", "b.c1"), second.getGraph()
                .getSources(Column.temporary("stmt0.temp0.z")));
        assertFalse(second.getGraph()
                .getSources(Column.temporary("stmt0.temp0.y"))
                .contains(new com.laotie.model.metadata.Column("b.c1")));

        String unknownFirstNoDeclaration = "WITH q AS (SELECT a.*, b.c1 FROM a CROSS JOIN b"
                + " UNION ALL SELECT c1 AS x2, c2 AS y2, c3 AS z2 FROM c)"
                + " SELECT q.y, q.z FROM q;";
        ScanResult third = new LineageScanner(DbType.mysql)
                .scan(unknownFirstNoDeclaration);
        assertEquals(0, third.getFailures().size());
        boolean sawTrailing = false;
        for (Instruction instruction : third.getResults().get(0).getInstructions()) {
            if ("b.c1".equals(instruction.getFrom())) {
                assertEquals(2, instruction.getOrdinal());
                sawTrailing = true;
            }
        }
        assertTrue(sawTrailing);
    }

    @Test
    public void cteStarWidthAnchorsTrailingColumnInUnion() {
        String cte = "WITH t AS (SELECT c1 AS x, c2 AS y, c3 AS z FROM c), ";
        String knownFirst = cte + "q AS (SELECT t.* FROM t"
                + " UNION ALL SELECT a.*, b.c1 FROM a CROSS JOIN b)"
                + " SELECT q.y, q.z FROM q;";
        ScanResult first = new LineageScanner(DbType.mysql).scan(knownFirst);
        assertEquals(0, first.getFailures().size());
        assertEquals(columns("c.c3", "b.c1"), first.getGraph()
                .getSources(Column.temporary("stmt0.temp0.z")));
        assertFalse(first.getGraph()
                .getSources(Column.temporary("stmt0.temp0.y"))
                .contains(new com.laotie.model.metadata.Column("b.c1")));

        String unknownFirst = cte + "q(x,y,z) AS (SELECT a.*, b.c1 FROM a CROSS JOIN b"
                + " UNION ALL SELECT t.* FROM t)"
                + " SELECT q.y, q.z FROM q;";
        ScanResult second = new LineageScanner(DbType.mysql).scan(unknownFirst);
        assertEquals(0, second.getFailures().size());
        assertEquals(columns("c.c3", "b.c1"), second.getGraph()
                .getSources(Column.temporary("stmt0.temp0.z")));
        assertFalse(second.getGraph()
                .getSources(Column.temporary("stmt0.temp0.y"))
                .contains(new com.laotie.model.metadata.Column("b.c1")));
    }

    @Test
    public void multiSourceCteStarWidthAnchorsTailInUnion() {
        ScanResult result = new LineageScanner(DbType.mysql).scan(
                "WITH t AS (SELECT c1,c2 FROM c),"
                        + " q AS (SELECT a.*, b.c1 AS tail FROM a CROSS JOIN b"
                        + " UNION ALL SELECT t.*, d.c1 FROM t CROSS JOIN d)"
                        + " SELECT q.tail FROM q;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("b.c1", "d.c1"), result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.tail")));
    }

    @Test
    public void cteStarOwnerMatchingFoldsCaseInPostgres() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH t AS (SELECT c1,c2,c3 FROM c),"
                        + " q AS (SELECT a.*, b.c1 AS tail FROM a CROSS JOIN b"
                        + " UNION ALL SELECT T.* FROM t)"
                        + " SELECT q.tail FROM q;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("b.c1", "c.c3"), result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.tail")));
    }

    @Test
    public void aliasShadowsOriginalTableNameInStaticWidth() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH t AS (SELECT c1,c2 FROM c),"
                        + " s AS (SELECT c1,c2,c3 FROM d),"
                        + " q AS (SELECT a.*, b.c1 AS tail FROM a CROSS JOIN b"
                        + " UNION ALL SELECT t.* FROM t AS u CROSS JOIN s AS t)"
                        + " SELECT q.tail FROM q;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("b.c1", "d.c3"), result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.tail")));
        assertTailOrdinal(result, 2);
    }

    @Test
    public void innerWithShadowsOuterCteInStaticWidth() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH t AS (SELECT c1,c2 FROM c),"
                        + " q AS (SELECT a.*, b.c1 AS tail FROM a CROSS JOIN b"
                        + " UNION ALL SELECT * FROM ("
                        + "   WITH t AS (SELECT c1,c2,c3 FROM d)"
                        + "   SELECT * FROM t) s)"
                        + " SELECT q.tail FROM q;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("b.c1", "d.c3"), result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.tail")));
        assertTailOrdinal(result, 2);
    }

    private static void assertTailOrdinal(ScanResult result, int ordinal) {
        boolean seen = false;
        for (Instruction instruction : result.getResults().get(0).getInstructions()) {
            if ("b.c1".equals(instruction.getFrom())) {
                assertEquals(ordinal, instruction.getOrdinal());
                seen = true;
            }
        }
        assertTrue(seen);
    }

    @Test
    public void quotedAliasIsDistinctFromUnquotedInStaticWidth() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH t AS (SELECT c1,c2 FROM c),"
                        + " s AS (SELECT c1,c2,c3 FROM d),"
                        + " q AS (SELECT a.*, b.c1 AS tail FROM a CROSS JOIN b"
                        + " UNION ALL SELECT \"T\".* FROM t AS t CROSS JOIN s AS \"T\")"
                        + " SELECT q.tail FROM q;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("b.c1", "d.c3"), result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.tail")));
        assertTailOrdinal(result, 2);
    }

    @Test
    public void derivedTableQualifiedStarContributesStaticWidth() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH q AS (SELECT a.*, b.c1 AS tail FROM a CROSS JOIN b"
                        + " UNION ALL SELECT s.* FROM (SELECT c1,c2,c3 FROM d) s)"
                        + " SELECT q.tail FROM q;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("b.c1", "d.c3"), result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.tail")));
        assertTailOrdinal(result, 2);
    }

    @Test
    public void joinAliasQualifiedStarExpandsJoinOutputs() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH x AS (SELECT c1, c2 FROM c),"
                        + " y AS (SELECT c3 FROM d),"
                        + " q AS (SELECT a.*, b.c1 AS tail FROM a CROSS JOIN b"
                        + " UNION ALL SELECT j.* FROM (x CROSS JOIN y) AS j)"
                        + " SELECT q.tail FROM q;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("b.c1", "d.c3"), result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.tail")));
        assertTailOrdinal(result, 2);
    }

    @Test
    public void joinAliasStarUsesOnlyItsSubtree() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH x AS (SELECT c1 FROM a), y AS (SELECT c2 FROM b),"
                        + " z AS (SELECT c3 FROM c)"
                        + " SELECT j.* FROM (x CROSS JOIN y) AS j CROSS JOIN z;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("a.c1"), result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.c1")));
        assertEquals(columns("b.c2"), result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.c2")));
        for (Instruction instruction : result.getResults().get(0).getInstructions()) {
            assertFalse("outer source leaked into j.*: " + instruction,
                    "temp0.c3".equals(instruction.getTo()));
        }
    }

    @Test
    public void joinAliasStarUsesMergedUsingWidth() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH x AS (SELECT c1,c2 FROM a), y AS (SELECT c1,c3 FROM b)"
                        + " SELECT j.* FROM (x JOIN y USING(c1)) AS j"
                        + " UNION ALL SELECT c1,c2,c3 FROM d;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("a.c1", "b.c1", "d.c1"), result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.c1")));
        assertEquals(columns("b.c3", "d.c3"), result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.c3")));
    }

    @Test
    public void joinAliasHidesInnerAliases() {
        MetadataContext context = pgContext(
                "CREATE TABLE a (c1 INT); CREATE TABLE b (c2 INT);");

        ScanResult result = new LineageScanner(context)
                .scan("SELECT j.* FROM (a AS j CROSS JOIN b) AS j;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("a.c1"), result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.c1")));
        assertEquals(columns("b.c2"), result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.c2")));

        ScanResult hidden = new LineageScanner(context).scan(
                "SELECT a.* FROM (a CROSS JOIN b) AS j;"
                        + " SELECT c1 FROM a;");
        assertEquals(2, hidden.getResults().size());
        assertTrue(hidden.getResults().get(0).getError()
                instanceof MetadataNotFoundException);
        assertTrue(hidden.getResults().get(0).getInstructions().isEmpty());
        assertNull(hidden.getResults().get(1).getError());
    }

    @Test
    public void joinAliasBindsQualifiedColumn() {
        MetadataContext context = pgContext(
                "CREATE TABLE a (c1 INT, c2 INT); CREATE TABLE b (c1 INT, c3 INT);");

        ScanResult single = new LineageScanner(context)
                .scan("SELECT j.c1 FROM (a JOIN b USING(c1)) AS j;");
        assertEquals(0, single.getFailures().size());
        assertEquals(columns("a.c1", "b.c1"), single.getGraph()
                .getSources(Column.temporary("stmt0.temp0.c1")));

        ScanResult ambiguous = new LineageScanner(context)
                .scan("SELECT j.c2 FROM (a CROSS JOIN a) AS j;"
                        + " SELECT c1 FROM a;");
        assertEquals(2, ambiguous.getResults().size());
        assertTrue(ambiguous.getResults().get(0).getError()
                instanceof com.laotie.exception.AmbiguousColumnException);
        assertNull(ambiguous.getResults().get(1).getError());
    }

    @Test
    public void joinAliasStarDeduplicatesTargetNames() {
        MetadataContext context = pgContext(
                "CREATE TABLE a (c1 INT); CREATE TABLE b (c1 INT);");

        ScanResult result = new LineageScanner(context)
                .scan("SELECT j.* FROM (a CROSS JOIN b) AS j;");

        assertEquals(0, result.getFailures().size());
        assertEquals(columns("a.c1"), result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.c1")));
        assertEquals(columns("b.c1"), result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.col2")));
    }

    @Test
    public void joinUsingStructuralWidthWithoutCompleteNames() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH x AS (SELECT * FROM a UNION ALL SELECT c1,c2 FROM b),"
                        + " y AS (SELECT c1 FROM c)"
                        + " SELECT j.* FROM (x JOIN y USING(c1)) AS j"
                        + " UNION ALL SELECT c1,c2 FROM d;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
    }

    @Test
    public void outerLeafAliasShadowsInnerJoinAlias() {
        MetadataContext context = pgContext(
                "CREATE TABLE a (c1 INT); CREATE TABLE b (c2 INT);"
                        + " CREATE TABLE c (c3 INT); CREATE TABLE d (c4 INT);");

        ScanResult result = new LineageScanner(context).scan(
                "SELECT j.* FROM ((a CROSS JOIN b) AS j CROSS JOIN c) AS k"
                        + " CROSS JOIN d AS j;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("d.c4"), result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.c4")));
        for (Instruction instruction : result.getResults().get(0).getInstructions()) {
            assertFalse("inner join alias leaked: " + instruction,
                    "temp0.c1".equals(instruction.getTo()));
        }
    }

    @Test
    public void joinAliasColumnKeepsQuotedIdentity() {
        MetadataContext context = pgContext(
                "CREATE TABLE a (\"C1\" INT, c1 INT); CREATE TABLE b (c2 INT);");

        ScanResult result = new LineageScanner(context).scan(
                "SELECT j.\"C1\" AS picked FROM (a CROSS JOIN b) AS j;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("a.C1"), result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.picked")));
    }

    @Test
    public void joinAliasUnknownColumnSkipsKnownMissingSources() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH x AS (SELECT c1 FROM a)"
                        + " SELECT j.c2 FROM (x CROSS JOIN b) AS j;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("b.c2"), result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.c2")));
    }

    @Test
    public void joinAliasStarDedupNameAvoidsSecondCollision() {
        MetadataContext context = pgContext(
                "CREATE TABLE a (col3 INT, c1 INT); CREATE TABLE b (c1 INT);");

        ScanResult result = new LineageScanner(context)
                .scan("SELECT j.* FROM (a CROSS JOIN b) AS j;");

        assertEquals(0, result.getFailures().size());
        assertEquals(columns("a.col3"), result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.col3")));
        assertEquals(columns("a.c1"), result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.c1")));
        assertEquals(columns("b.c1"), result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.col4")));
    }

    @Test
    public void nestedUsingIsSubtractedOncePerLevel() {
        MetadataContext context = pgContext(
                "CREATE TABLE a (c1 INT, c2 INT); CREATE TABLE b (c1 INT, c3 INT);"
                        + " CREATE TABLE c (c4 INT);"
                        + " CREATE TABLE d (c1 INT, c2 INT, c3 INT, c4 INT);");

        ScanResult result = new LineageScanner(context).scan(
                "SELECT k.* FROM ((a JOIN b USING(c1)) AS j CROSS JOIN c) AS k"
                        + " UNION ALL SELECT c1,c2,c3,c4 FROM d;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
    }

    @Test
    public void joinUsingIncompleteNamesKeepsStructuralPositions() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH x AS (SELECT * FROM a UNION ALL SELECT c1,c2 FROM b),"
                        + " y AS (SELECT c1 FROM c)"
                        + " SELECT j.* FROM (x JOIN y USING(c1)) AS j"
                        + " UNION ALL SELECT c1,c2 FROM d;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("c.c1", "d.c1"), result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.c1")));
        // The second position's name is not derivable from the first UNION branch,
        // so the sibling branch still lands on position 1 without a stable name.
        boolean uncertainWildcard = false;
        boolean secondPosition = false;
        for (Instruction instruction : result.getResults().get(0).getInstructions()) {
            assertFalse("phantom third position: " + instruction,
                    "temp0.c3".equals(instruction.getTo()));
            if (instruction.getOperation() == OperationType.COLUMNS_INJECT
                    && "x".equals(instruction.getFrom())) {
                uncertainWildcard = true;
            }
            if ("d.c2".equals(instruction.getFrom())) {
                assertEquals(1, instruction.getOrdinal());
                secondPosition = true;
            }
        }
        assertTrue(uncertainWildcard);
        assertTrue(secondPosition);
    }

    @Test
    public void outerJoinAliasHidesInnerJoinAliasWithoutLeafShadow() {
        MetadataContext context = pgContext(
                "CREATE TABLE a (c1 INT); CREATE TABLE b (c2 INT);"
                        + " CREATE TABLE c (c3 INT);");

        ScanResult result = new LineageScanner(context).scan(
                "SELECT j.* FROM ((a CROSS JOIN b) AS j CROSS JOIN c) AS k;"
                        + " SELECT c1 FROM a;");

        assertEquals(2, result.getResults().size());
        assertTrue(result.getResults().get(0).getError()
                instanceof MetadataNotFoundException);
        assertTrue(result.getResults().get(0).getInstructions().isEmpty());
        assertNull(result.getResults().get(1).getError());
    }

    @Test
    public void usingFallbackDoesNotConsumeKnownColumns() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH x AS (SELECT p.c1 AS marker, p.* FROM p),"
                        + " y AS (SELECT c1 FROM c)"
                        + " SELECT j.* FROM (x JOIN y USING(c1)) AS j;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("p.c1"), result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.marker")));
    }

    @Test
    public void rightJoinUsingBindsMergedColumnFromRightSide() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH y AS (SELECT c1 FROM b)"
                        + " SELECT j.c1 FROM (a RIGHT JOIN y USING(c1)) AS j;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("b.c1"), result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.c1")));
    }

    @Test
    public void usingKeyMissingOnKnownSideFails() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH x AS (SELECT c2 FROM b)"
                        + " SELECT j.c1 FROM (x LEFT JOIN a USING(c1)) AS j;"
                        + " SELECT c2 FROM b;");

        assertEquals(2, result.getResults().size());
        assertTrue(result.getResults().get(0).getError()
                instanceof ColumnNotFoundException);
        assertTrue(result.getResults().get(0).getInstructions().isEmpty());
        assertNull(result.getResults().get(1).getError());
    }

    @Test
    public void innerJoinAliasDoesNotReplaceOuterBinding() {
        MetadataContext context = pgContext(
                "CREATE TABLE a (c1 INT); CREATE TABLE b (c2 INT);"
                        + " CREATE TABLE c (c3 INT); CREATE TABLE d (c4 INT);"
                        + " CREATE TABLE e (c5 INT);");

        ScanResult result = new LineageScanner(context).scan(
                "SELECT j.* FROM (a CROSS JOIN b) AS j"
                        + " CROSS JOIN ((c CROSS JOIN d) AS j CROSS JOIN e) AS k;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("a.c1"), result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.c1")));
        assertEquals(columns("b.c2"), result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.c2")));
        for (Instruction instruction : result.getResults().get(0).getInstructions()) {
            assertFalse("inner alias replaced outer binding: " + instruction,
                    "temp0.c3".equals(instruction.getTo())
                            || "temp0.c4".equals(instruction.getTo()));
        }
    }

    @Test
    public void multiUsingKeyKeepsPositionsWhenOneSideUnknown() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH y AS (SELECT c1,c2,c3 FROM b)"
                        + " SELECT j.* FROM (a LEFT JOIN y USING(c1,c2)) AS j"
                        + " UNION ALL SELECT c1,c2,c3 FROM d;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("a.c2", "d.c2"), result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.c2")));
        assertFalse(result.getGraph().getSources(Column.temporary("stmt0.temp0.c2"))
                .contains(new com.laotie.model.metadata.Column("b.c2")));
        assertEquals(columns("b.c3", "d.c3"), result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.c3")));
    }

    @Test
    public void rightJoinKeepsRightSideSourcesWhenLeftUnknown() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH y AS (SELECT c1,c2,c3 FROM b)"
                        + " SELECT j.* FROM (a RIGHT JOIN y USING(c1,c2)) AS j"
                        + " UNION ALL SELECT c1,c2,c3 FROM d;");

        assertEquals(0, result.getFailures().size());
        assertEquals(columns("b.c1", "d.c1"), result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.c1")));
        assertEquals(columns("b.c2", "d.c2"), result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.c2")));
    }

    @Test
    public void bothUnknownUsingKeysKeepTheirPositions() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "SELECT j.*, 42 AS tail FROM (a JOIN b USING(c1,c2)) AS j;");

        assertEquals(0, result.getFailures().size());
        boolean tailAtSecond = false;
        for (Instruction instruction : result.getResults().get(0).getInstructions()) {
            if ("temp0.tail".equals(instruction.getTo())) {
                assertEquals(2, instruction.getOrdinal());
                tailAtSecond = true;
            }
        }
        assertTrue(tailAtSecond);
    }

    @Test
    public void nestedUsingKnownOutputCountsAsDefinite() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH x AS (SELECT k FROM c), z AS (SELECT k FROM d)"
                        + " SELECT j.* FROM (((a JOIN b USING(k)) CROSS JOIN x)"
                        + " JOIN z USING(k)) AS j;"
                        + " SELECT c1 FROM a;");

        assertEquals(2, result.getResults().size());
        assertTrue(result.getResults().get(0).getError()
                instanceof com.laotie.exception.AmbiguousColumnException);
        assertTrue(result.getResults().get(0).getInstructions().isEmpty());
        assertNull(result.getResults().get(1).getError());
    }

    @Test
    public void usingKeyPrefersKnownNameOverPlaceholder() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH x AS (SELECT c1 AS col1 FROM b),"
                        + " y AS (SELECT col1 FROM c)"
                        + " SELECT j.* FROM ((a CROSS JOIN x) LEFT JOIN y USING(col1)) AS j;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("b.c1"), result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.col1")));
    }

    @Test
    public void definiteUsingAmbiguityFailsDespiteUnknownSibling() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH x AS (SELECT c1 AS k FROM a),"
                        + " y AS (SELECT c1 AS k FROM b),"
                        + " z AS (SELECT k FROM c)"
                        + " SELECT j.* FROM ((x CROSS JOIN y CROSS JOIN u)"
                        + " JOIN z USING(k)) AS j;"
                        + " SELECT c1 FROM a;");

        assertEquals(2, result.getResults().size());
        assertTrue(result.getResults().get(0).getError()
                instanceof com.laotie.exception.AmbiguousColumnException);
        assertTrue(result.getResults().get(0).getInstructions().isEmpty());
        assertNull(result.getResults().get(1).getError());
    }

    @Test
    public void qualifiedJoinAliasColumnSeesMergedAmbiguity() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH x AS (SELECT k FROM c)"
                        + " SELECT j.k FROM ((a JOIN b USING(k)) CROSS JOIN x) AS j;"
                        + " SELECT c1 FROM a;");

        assertEquals(2, result.getResults().size());
        assertTrue(result.getResults().get(0).getError()
                instanceof com.laotie.exception.AmbiguousColumnException);
        assertTrue(result.getResults().get(0).getInstructions().isEmpty());
        assertNull(result.getResults().get(1).getError());
    }

    @Test
    public void joinUsingRaisesMinimumWidthForUnionCheck() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "SELECT j.* FROM (a JOIN b USING(c1,c2)) AS j"
                        + " UNION ALL SELECT c1 FROM d;"
                        + " SELECT c1 FROM a;");

        assertEquals(2, result.getResults().size());
        assertTrue(result.getResults().get(0).getError()
                instanceof IllegalStateException);
        assertTrue(result.getResults().get(0).getInstructions().isEmpty());
        assertNull(result.getResults().get(1).getError());
    }

    @Test
    public void joinAliasCompletePlanNamesFlowToCte() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH x AS (SELECT * FROM a UNION ALL SELECT c1 FROM b),"
                        + " q AS (SELECT j.* FROM (x JOIN (SELECT c1 FROM c) AS y"
                        + " USING(c1)) AS j)"
                        + " SELECT q.missing FROM q;"
                        + " SELECT c1 FROM a;");

        assertEquals(2, result.getResults().size());
        assertTrue(result.getResults().get(0).getError()
                instanceof ColumnNotFoundException);
        assertTrue(result.getResults().get(0).getInstructions().isEmpty());
        assertNull(result.getResults().get(1).getError());
    }

    @Test
    public void joinAliasRejectsMissingColumnWhenNamesComplete() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH x AS (SELECT * FROM a UNION ALL SELECT c1 FROM b)"
                        + " SELECT j.missing FROM (x JOIN (SELECT c1 FROM c) AS y"
                        + " USING(c1)) AS j;"
                        + " SELECT c1 FROM a;");

        assertEquals(2, result.getResults().size());
        assertTrue(result.getResults().get(0).getError()
                instanceof ColumnNotFoundException);
        assertTrue(result.getResults().get(0).getInstructions().isEmpty());
        assertNull(result.getResults().get(1).getError());
    }

    @Test
    public void bareStarUsesJoinStructuralWidth() {
        ScanResult merged = new LineageScanner(DbType.postgresql).scan(
                "WITH x AS (SELECT * FROM a UNION ALL SELECT c1 FROM b)"
                        + " SELECT * FROM x JOIN (SELECT c1 FROM c) AS y USING(c1)"
                        + " UNION ALL SELECT c1 FROM d;");
        assertEquals(1, merged.getResults().size());
        assertEquals(0, merged.getFailures().size());

        ScanResult mismatch = new LineageScanner(DbType.postgresql).scan(
                "SELECT * FROM a JOIN b USING(c1,c2)"
                        + " UNION ALL SELECT c1 FROM d;"
                        + " SELECT c1 FROM a;");
        assertEquals(2, mismatch.getResults().size());
        assertTrue(mismatch.getResults().get(0).getError()
                instanceof IllegalStateException);
        assertTrue(mismatch.getResults().get(0).getInstructions().isEmpty());
        assertNull(mismatch.getResults().get(1).getError());
    }

    @Test
    public void usingKeysExceedingKnownWidthFail() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH x AS (SELECT * FROM a UNION ALL SELECT c1 FROM b)"
                        + " SELECT j.* FROM (x JOIN (SELECT c1,c2 FROM c) AS y"
                        + " USING(c1,c2)) AS j;"
                        + " SELECT c1 FROM a;");

        assertEquals(2, result.getResults().size());
        assertTrue(result.getResults().get(0).getError()
                instanceof ColumnNotFoundException);
        assertTrue(result.getResults().get(0).getInstructions().isEmpty());
        assertNull(result.getResults().get(1).getError());
    }

    @Test
    public void bareStarPositionUsesAllMergedUsingKeys() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "SELECT *, 42 AS tail FROM a JOIN b USING(c1,c2,c3);");

        assertEquals(0, result.getFailures().size());
        boolean tailAtThird = false;
        for (Instruction instruction : result.getResults().get(0).getInstructions()) {
            if ("temp0.tail".equals(instruction.getTo())) {
                assertEquals(3, instruction.getOrdinal());
                tailAtThird = true;
            }
        }
        assertTrue(tailAtThird);
    }

    @Test
    public void bareStarNamesFlowToCte() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH x AS (SELECT * FROM a UNION ALL SELECT c1 FROM b),"
                        + " q AS (SELECT * FROM x JOIN (SELECT c1 FROM c) AS y"
                        + " USING(c1))"
                        + " SELECT q.missing FROM q;"
                        + " SELECT c1 FROM a;");

        assertEquals(2, result.getResults().size());
        assertTrue(result.getResults().get(0).getError()
                instanceof ColumnNotFoundException);
        assertTrue(result.getResults().get(0).getInstructions().isEmpty());
        assertNull(result.getResults().get(1).getError());
    }

    @Test
    public void outerUsingCannotUseKeyMissingFromInnerJoin() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH x AS (SELECT * FROM a UNION ALL SELECT c1 FROM b)"
                        + " SELECT * FROM (x JOIN (SELECT c1 FROM c) AS y USING(c1))"
                        + " JOIN (SELECT c2 FROM d) AS z USING(c2);"
                        + " SELECT c1 FROM a;");

        assertEquals(2, result.getResults().size());
        assertTrue(result.getResults().get(0).getError()
                instanceof ColumnNotFoundException);
        assertTrue(result.getResults().get(0).getInstructions().isEmpty());
        assertNull(result.getResults().get(1).getError());
    }

    @Test
    public void nestedLeftJoinMergedColumnKeepsLeftOrigin() {
        ScanResult withoutMetadata = new LineageScanner(DbType.postgresql).scan(
                "SELECT k FROM (a LEFT JOIN b USING(k)) LEFT JOIN c USING(k);");
        assertEquals(0, withoutMetadata.getFailures().size());
        assertEquals(columns("a.k"), withoutMetadata.getGraph()
                .getSources(Column.temporary("stmt0.temp0.k")));

        MetadataContext context = pgContext(
                "CREATE TABLE a (k INT); CREATE TABLE b (k INT); CREATE TABLE c (k INT);");
        ScanResult withMetadata = new LineageScanner(context).scan(
                "SELECT k FROM (a LEFT JOIN b USING(k)) LEFT JOIN c USING(k);");
        assertEquals(0, withMetadata.getFailures().size());
        assertEquals(columns("a.k"), withMetadata.getGraph()
                .getSources(Column.temporary("stmt0.temp0.k")));
    }

    @Test
    public void unqualifiedUsingSkipsUnknownSourceIdentity() {
        MetadataContext context = pgContext(
                "CREATE TABLE a (other INT); CREATE TABLE b (k INT);"
                        + " CREATE TABLE c (k INT);");
        ScanResult withMetadata = new LineageScanner(context)
                .scan("SELECT k FROM (a CROSS JOIN b) LEFT JOIN c USING(k);");

        assertEquals(0, withMetadata.getFailures().size());
        assertEquals(columns("b.k"), withMetadata.getGraph()
                .getSources(Column.temporary("stmt0.temp0.k")));

        ScanResult withoutMetadata = new LineageScanner(DbType.postgresql)
                .scan("SELECT k FROM (a CROSS JOIN b) LEFT JOIN c USING(k);");
        assertEquals(0, withoutMetadata.getFailures().size());
        for (Instruction instruction : withoutMetadata.getResults().get(0)
                .getInstructions()) {
            assertFalse("unknown source guessed: " + instruction,
                    "a.k".equals(instruction.getFrom()));
        }
    }

    @Test
    public void unqualifiedMissingColumnRejectedWithCompleteJoinNames() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH x AS (SELECT * FROM a UNION ALL SELECT c1 FROM b)"
                        + " SELECT missing FROM x JOIN (SELECT c1 FROM c) AS y"
                        + " USING(c1);"
                        + " SELECT c1 FROM a;");

        assertEquals(2, result.getResults().size());
        assertTrue(result.getResults().get(0).getError()
                instanceof ColumnNotFoundException);
        assertTrue(result.getResults().get(0).getInstructions().isEmpty());
        assertNull(result.getResults().get(1).getError());
    }

    @Test
    public void nestedUsingKeyRaisesMinimumWidth() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "SELECT * FROM (a JOIN b USING(c1,c2)) JOIN c USING(c3)"
                        + " UNION ALL SELECT c1,c2 FROM d;"
                        + " SELECT c1 FROM a;");

        assertEquals(2, result.getResults().size());
        assertTrue(result.getResults().get(0).getError()
                instanceof IllegalStateException);
        assertTrue(result.getResults().get(0).getInstructions().isEmpty());
        assertNull(result.getResults().get(1).getError());
    }

    @Test
    public void completeUsingNamesDoNotGuessUnknownSource() {
        String sql = "WITH x AS (SELECT * FROM a UNION ALL SELECT 0),"
                + " y AS (SELECT * FROM b UNION ALL SELECT 0)"
                + " SELECT k1 FROM (x CROSS JOIN y)"
                + " LEFT JOIN (SELECT k1,k2 FROM c) AS z USING(k1,k2);";

        ScanResult withoutMetadata = new LineageScanner(DbType.postgresql).scan(sql);
        assertEquals(0, withoutMetadata.getFailures().size());
        for (Instruction instruction : withoutMetadata.getResults().get(0)
                .getInstructions()) {
            assertFalse("unknown source guessed: " + instruction,
                    "x.k1".equals(instruction.getFrom())
                            || "y.k1".equals(instruction.getFrom()));
        }

        MetadataContext context = pgContext(
                "CREATE TABLE a (k2 INT); CREATE TABLE b (k1 INT);"
                        + " CREATE TABLE c (k1 INT, k2 INT);");
        ScanResult withMetadata = new LineageScanner(context).scan(sql);
        assertEquals(0, withMetadata.getFailures().size());
        assertEquals(columns("b.k1"), withMetadata.getGraph()
                .getSources(Column.temporary("stmt0.temp0.k1")));
    }

    @Test
    public void minimumWidthCountsDuplicateOutputPositions() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH p AS (SELECT v FROM a), q AS (SELECT v FROM b)"
                        + " SELECT * FROM (p CROSS JOIN q CROSS JOIN t)"
                        + " JOIN (SELECT k FROM c) AS s USING(k)"
                        + " UNION ALL SELECT k,v FROM d;"
                        + " SELECT v FROM a;");

        assertEquals(2, result.getResults().size());
        assertTrue(result.getResults().get(0).getError()
                instanceof IllegalStateException);
        assertTrue(result.getResults().get(0).getInstructions().isEmpty());
        assertNull(result.getResults().get(1).getError());
    }

    @Test
    public void usingKeyNamedLikePlaceholderStillBindsSingleSource() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "SELECT col1 FROM a LEFT JOIN b USING(col1);");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("a.col1"), result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.col1")));
    }

    @Test
    public void ctePlaceholderKeyIsNotConfirmedIdentity() {
        String sql = "WITH x AS (SELECT * FROM a UNION ALL SELECT s1,s2 FROM b)"
                + " SELECT col1 FROM x LEFT JOIN c USING(col1);";

        ScanResult withoutMetadata = new LineageScanner(DbType.postgresql).scan(sql);
        assertEquals(0, withoutMetadata.getFailures().size());
        for (Instruction instruction : withoutMetadata.getResults().get(0)
                .getInstructions()) {
            if (instruction.getOperation() == OperationType.COLUMN_MAPPING) {
                assertFalse("unconfirmed key guessed: " + instruction,
                        "temp0.col1".equals(instruction.getTo()));
            }
        }

        MetadataContext context = pgContext(
                "CREATE TABLE a (other INT, col1 INT);"
                        + " CREATE TABLE b (s1 INT, s2 INT);"
                        + " CREATE TABLE c (col1 INT);");
        ScanResult withMetadata = new LineageScanner(context).scan(sql);
        assertEquals(0, withMetadata.getFailures().size());
        assertEquals(columns("a.col1", "b.s2"), withMetadata.getGraph()
                .getSources(Column.temporary("stmt0.temp0.col1")));
    }

    @Test
    public void unionWidthPositionsTrailingJoinColumn() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH y AS (SELECT k,v FROM b)"
                        + " SELECT * FROM a LEFT JOIN y USING(k)"
                        + " UNION ALL SELECT k,q,v FROM d;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        boolean sawTrailing = false;
        for (Instruction instruction : result.getResults().get(0).getInstructions()) {
            if ("y.v".equals(instruction.getFrom())) {
                assertEquals(2, instruction.getOrdinal());
                sawTrailing = true;
            }
        }
        assertTrue(sawTrailing);
        assertEquals(columns("b.v", "d.v"), result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.v")));
    }

    @Test
    public void unionWidthKeepsLeftRemainderBeforeRightUnknownSlots() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH y AS (SELECT k,v FROM b)"
                        + " SELECT * FROM y LEFT JOIN a USING(k)"
                        + " UNION ALL SELECT k,v,q FROM d;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        boolean sawRemainder = false;
        for (Instruction instruction : result.getResults().get(0).getInstructions()) {
            if ("y.v".equals(instruction.getFrom())) {
                assertEquals(1, instruction.getOrdinal());
                sawRemainder = true;
            }
        }
        assertTrue(sawRemainder);
        assertEquals(columns("b.v", "d.v"), result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.v")));
    }

    @Test
    public void unionWidthBudgetExcludesOtherProjections() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH y AS (SELECT k,v FROM b)"
                        + " SELECT *, 42 AS tail FROM a LEFT JOIN y USING(k)"
                        + " UNION ALL SELECT k,q,v,tail FROM d;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        boolean tailAtThird = false;
        for (Instruction instruction : result.getResults().get(0).getInstructions()) {
            if ("temp0.tail".equals(instruction.getTo())) {
                assertEquals(3, instruction.getOrdinal());
                tailAtThird = true;
            }
        }
        assertTrue(tailAtThird);
        assertEquals(columns("b.v", "d.v"), result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.v")));
    }

    @Test
    public void nestedJoinExpansionUsesInnerBoundary() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH y AS (SELECT k,v FROM b), z AS (SELECT w FROM c)"
                        + " SELECT * FROM (a LEFT JOIN y USING(k)) CROSS JOIN z"
                        + " UNION ALL SELECT k,q,v,w FROM d;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        boolean sawRemainder = false;
        for (Instruction instruction : result.getResults().get(0).getInstructions()) {
            if ("y.v".equals(instruction.getFrom())) {
                assertEquals(2, instruction.getOrdinal());
                sawRemainder = true;
            }
        }
        assertTrue(sawRemainder);
        assertEquals(columns("b.v", "d.v"), result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.v")));
    }

    @Test
    public void qualifiedStarExpansionTargetsItsSubtree() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH y AS (SELECT k,v FROM b)"
                        + " SELECT j.* FROM (a LEFT JOIN y USING(k)) AS j"
                        + " CROSS JOIN (SELECT 1) AS z"
                        + " UNION ALL SELECT k,q,v FROM d;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        boolean sawRemainder = false;
        for (Instruction instruction : result.getResults().get(0).getInstructions()) {
            if ("y.v".equals(instruction.getFrom())) {
                assertEquals(2, instruction.getOrdinal());
                sawRemainder = true;
            }
        }
        assertTrue(sawRemainder);
        assertEquals(columns("b.v", "d.v"), result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.v")));
    }

    @Test
    public void ambiguousWidthAllocationProducesNoGuessedEdges() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH y AS (SELECT k,v FROM b)"
                        + " SELECT * FROM (a LEFT JOIN y USING(k)) CROSS JOIN c"
                        + " UNION ALL SELECT k,q,v,w FROM d;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        for (Instruction instruction : result.getResults().get(0).getInstructions()) {
            if (instruction.getOperation() == OperationType.COLUMN_MAPPING) {
                assertFalse("unstable position mapped: " + instruction,
                        "y.v".equals(instruction.getFrom())
                                || "temp0.v".equals(instruction.getTo()));
            }
        }
    }

    @Test
    public void twoUnknownSourcesDoNotFixTrailingPosition() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH y AS (SELECT k,v FROM b), z AS (SELECT l FROM c)"
                        + " SELECT * FROM ((a LEFT JOIN y USING(k))"
                        + " CROSS JOIN (z LEFT JOIN t USING(l)))"
                        + " CROSS JOIN (SELECT 1 AS e) AS u"
                        + " UNION ALL SELECT k,q,v,l,e FROM d;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        for (Instruction instruction : result.getResults().get(0).getInstructions()) {
            if (instruction.getOperation() == OperationType.COLUMN_MAPPING) {
                assertFalse("ambiguous position mapped: " + instruction,
                        "y.v".equals(instruction.getFrom())
                                || "temp0.v".equals(instruction.getTo()));
            }
        }
    }

    @Test
    public void unknownSideWithoutPlaceholderInsertsBeforeTrailingKnown() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH y AS (SELECT k,v FROM b), z AS (SELECT w FROM c)"
                        + " SELECT * FROM (y LEFT JOIN a USING(k)) CROSS JOIN z"
                        + " UNION ALL SELECT k,v,q,w FROM d;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("c.w", "d.w"), result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.w")));
    }

    @Test
    public void qualifiedStarKeepsQuotedColumnIdentity() {
        MetadataContext context = pgContext(
                "CREATE TABLE a (\"K\" INT); CREATE TABLE b (v INT);");

        ScanResult result = new LineageScanner(context).scan(
                "WITH q AS (SELECT j.* FROM (a CROSS JOIN b) AS j)"
                        + " SELECT q.\"K\" FROM q;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("a.K"), result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.K")));
    }

    @Test
    public void cteInternalWildcardKeepsTrailingExplicitColumn() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH x AS (SELECT a.*, a.v AS tail FROM a)"
                        + " SELECT * FROM x LEFT JOIN (SELECT k FROM b) AS y USING(k)"
                        + " UNION ALL SELECT k,q,v,tail FROM d;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        boolean sawTail = false;
        for (Instruction instruction : result.getResults().get(0).getInstructions()) {
            if ("x.tail".equals(instruction.getFrom())) {
                assertEquals(3, instruction.getOrdinal());
                sawTail = true;
            }
        }
        assertTrue(sawTail);
        assertEquals(columns("a.v", "d.tail"), result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.tail")));
    }

    @Test
    public void unknownRightSubtreeWithoutPlaceholderKeepsTrailingKnown() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH y AS (SELECT k,v FROM b), z AS (SELECT w FROM c)"
                        + " SELECT * FROM (SELECT 1 AS h) AS u"
                        + " CROSS JOIN ((y LEFT JOIN a USING(k)) CROSS JOIN z)"
                        + " UNION ALL SELECT h,k,v,q,w FROM d;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("c.w", "d.w"), result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.w")));
    }

    @Test
    public void ambiguousDistributionKeepsFixedSuffix() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH y AS (SELECT k,v FROM b), z AS (SELECT w FROM e)"
                        + " SELECT * FROM ((a LEFT JOIN y USING(k)) CROSS JOIN c)"
                        + " CROSS JOIN z"
                        + " UNION ALL SELECT k,q,v,r,w FROM d;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("e.w", "d.w"), result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.w")));
        for (Instruction instruction : result.getResults().get(0).getInstructions()) {
            if (instruction.getOperation() == OperationType.COLUMN_MAPPING) {
                assertFalse("ambiguous position mapped: " + instruction,
                        "y.v".equals(instruction.getFrom())
                                || "temp0.v".equals(instruction.getTo()));
            }
        }
    }

    @Test
    public void bareStarCrossJoinUsesPositionalPlan() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "SELECT * FROM a CROSS JOIN (SELECT v FROM b) AS y"
                        + " UNION ALL SELECT q,v FROM d;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("b.v", "d.v"), result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.v")));
    }

    @Test
    public void cteExplicitColumnBeforeWildcardKeepsPosition() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH x AS (SELECT a.v AS head, a.* FROM a)"
                        + " SELECT * FROM x LEFT JOIN (SELECT k FROM b) AS y USING(k)"
                        + " UNION ALL SELECT k,head,q,v FROM d;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        boolean sawHead = false;
        for (Instruction instruction : result.getResults().get(0).getInstructions()) {
            if ("x.head".equals(instruction.getFrom())) {
                assertEquals(1, instruction.getOrdinal());
                sawHead = true;
            }
        }
        assertTrue(sawHead);
        assertEquals(columns("a.v", "d.head"), result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.head")));
    }

    @Test
    public void explicitColumnBetweenUnknownRegionsStaysUnstable() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH x AS (SELECT 1 AS l, a.* FROM a),"
                        + " y AS (SELECT b.v AS r, b.* FROM b)"
                        + " SELECT * FROM x CROSS JOIN y"
                        + " UNION ALL SELECT l,k,q,r,v FROM d;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        for (Instruction instruction : result.getResults().get(0).getInstructions()) {
            if (instruction.getOperation() == OperationType.COLUMN_MAPPING) {
                assertFalse("unstable position mapped: " + instruction,
                        "y.r".equals(instruction.getFrom())
                                || "temp0.r".equals(instruction.getTo()));
            }
        }
    }

    @Test
    public void qualifiedStarPlainSourceUsesPositionalPlan() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH x AS (SELECT a.*, a.v AS tail FROM a)"
                        + " SELECT x.* FROM x"
                        + " UNION ALL SELECT k,q,v,tail FROM d;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        boolean sawTail = false;
        for (Instruction instruction : result.getResults().get(0).getInstructions()) {
            if ("x.tail".equals(instruction.getFrom())) {
                assertEquals(3, instruction.getOrdinal());
                sawTail = true;
            }
        }
        assertTrue(sawTail);
        assertEquals(columns("a.v", "d.tail"), result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.tail")));
    }

    @Test
    public void usingColumnsAfterWildcardKeepHeadPosition() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH x AS (SELECT a.v AS head, a.*, a.k AS j1, a.q AS j2 FROM a)"
                        + " SELECT * FROM x JOIN (SELECT j1,j2 FROM b) AS y"
                        + " USING(j1,j2)"
                        + " UNION ALL SELECT j1,j2,head,k,q,v FROM d;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        boolean sawHead = false;
        for (Instruction instruction : result.getResults().get(0).getInstructions()) {
            if ("x.head".equals(instruction.getFrom())) {
                assertEquals(2, instruction.getOrdinal());
                sawHead = true;
            }
        }
        assertTrue(sawHead);
        assertEquals(columns("a.v", "d.head"), result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.head")));
    }

    @Test
    public void multipleWildcardRegionsKeepMiddleColumnUnstable() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH x AS (SELECT a.*, a.v AS mid, b.* FROM a CROSS JOIN b)"
                        + " SELECT x.* FROM x"
                        + " UNION ALL SELECT k,q,v,mid,r FROM d;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        for (Instruction instruction : result.getResults().get(0).getInstructions()) {
            if (instruction.getOperation() == OperationType.COLUMN_MAPPING) {
                assertFalse("unstable middle mapped: " + instruction,
                        "temp0.mid".equals(instruction.getTo()));
            }
        }
    }

    @Test
    public void qualifiedStarPlainSourceWithExtraFromUsesPositionPlan() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH x AS (SELECT a.*, a.v AS tail FROM a)"
                        + " SELECT x.* FROM x CROSS JOIN (SELECT 1) AS z"
                        + " UNION ALL SELECT k,q,v,tail FROM d;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        boolean sawTail = false;
        for (Instruction instruction : result.getResults().get(0).getInstructions()) {
            if ("x.tail".equals(instruction.getFrom())) {
                assertEquals(3, instruction.getOrdinal());
                sawTail = true;
            }
        }
        assertTrue(sawTail);
        assertEquals(columns("a.v", "d.tail"), result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.tail")));
    }

    @Test
    public void outerJoinDoesNotMutateQualifiedStarPlan() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH x AS (SELECT k FROM b), y AS (SELECT v FROM c)"
                        + " SELECT j.* FROM a CROSS JOIN (x CROSS JOIN y) AS j;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("b.k"), result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.k")));
        assertEquals(columns("c.v"), result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.v")));
    }

    @Test
    public void repeatedWildcardOfSameSourceKeepsEqualWidths() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH x AS (SELECT a.*, a.v AS mid, a.* FROM a)"
                        + " SELECT x.* FROM x"
                        + " UNION ALL SELECT k,v,mid,k2,v2 FROM d;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        boolean sawMid = false;
        for (Instruction instruction : result.getResults().get(0).getInstructions()) {
            if ("x.mid".equals(instruction.getFrom())) {
                assertEquals(2, instruction.getOrdinal());
                sawMid = true;
            }
        }
        assertTrue(sawMid);
        assertEquals(columns("a.v", "d.mid"), result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.mid")));
    }

    @Test
    public void repeatedWildcardRunsUsePerWildcardWidth() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH x AS (SELECT a.*, a.*, a.*, a.v AS mid, a.* FROM a)"
                        + " SELECT x.* FROM x"
                        + " UNION ALL SELECT c1,c2,c3,c4,c5,c6,c7,c8,c9 FROM d;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        boolean sawMid = false;
        for (Instruction instruction : result.getResults().get(0).getInstructions()) {
            if ("x.mid".equals(instruction.getFrom())) {
                assertEquals(6, instruction.getOrdinal());
                sawMid = true;
            }
        }
        assertTrue(sawMid);
        assertEquals(columns("a.v", "d.c7"), result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.mid")));
    }

    @Test
    public void repeatedWildcardGroupsSurviveStarPassthrough() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH x AS (SELECT a.*, a.v AS mid, b.* FROM a CROSS JOIN b),"
                        + " y AS (SELECT x.* FROM x)"
                        + " SELECT y.* FROM y"
                        + " UNION ALL SELECT k,q,v,mid,r FROM d;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        for (Instruction instruction : result.getResults().get(0).getInstructions()) {
            if (instruction.getOperation() == OperationType.COLUMN_MAPPING) {
                assertFalse("passthrough guessed group: " + instruction,
                        "temp0.mid".equals(instruction.getTo()));
            }
        }
    }

    @Test
    public void repeatedWildcardWidthWithoutIntegerSolutionFails() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH x AS (SELECT a.*, a.v AS mid, a.* FROM a)"
                        + " SELECT x.* FROM x"
                        + " UNION ALL SELECT k,v,mid,k2 FROM d;"
                        + " SELECT c1 FROM a;");

        assertEquals(2, result.getResults().size());
        assertTrue(result.getResults().get(0).getError()
                instanceof IllegalStateException);
        assertTrue(result.getResults().get(0).getInstructions().isEmpty());
        assertNull(result.getResults().get(1).getError());
    }

    @Test
    public void repeatedWildcardThroughUsingKeepsReorderedPositions() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH x AS (SELECT a.*, a.v AS mid, a.*, a.k AS j1, a.v AS j2 FROM a)"
                        + " SELECT * FROM x JOIN (SELECT j1,j2 FROM b) AS y"
                        + " USING(j1,j2)"
                        + " UNION ALL SELECT j1,j2,k,v,mid,k2,v2 FROM d;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        boolean sawMid = false;
        for (Instruction instruction : result.getResults().get(0).getInstructions()) {
            if ("x.mid".equals(instruction.getFrom())) {
                assertEquals(4, instruction.getOrdinal());
                sawMid = true;
            }
        }
        assertTrue(sawMid);
        assertEquals(columns("a.v", "d.mid"), result.getGraph()
                .getSources(Column.temporary("stmt0.temp0.mid")));
    }

    @Test
    public void adjacentRepeatedWildcardWithoutSolutionFails() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH x AS (SELECT a.*, a.* FROM a)"
                        + " SELECT x.* FROM x"
                        + " UNION ALL SELECT k,q,v FROM d;"
                        + " SELECT c1 FROM a;");

        assertEquals(2, result.getResults().size());
        assertTrue(result.getResults().get(0).getError()
                instanceof IllegalStateException);
        assertTrue(result.getResults().get(0).getInstructions().isEmpty());
        assertNull(result.getResults().get(1).getError());
    }

    @Test
    public void declaredCteAliasesCompleteKnownUnionWidth() {
        String cte = "WITH q(x,y) AS (SELECT * FROM a"
                + " UNION ALL SELECT c1,c3 FROM b) ";
        ScanResult resolved = new LineageScanner(DbType.postgresql)
                .scan(cte + "SELECT q.y FROM q;");

        assertEquals(1, resolved.getResults().size());
        assertEquals(0, resolved.getFailures().size());
        assertEquals(columns("b.c3"), resolved.getGraph()
                .getSources(Column.temporary("stmt0.temp0.y")));

        ScanResult missing = new LineageScanner(DbType.postgresql).scan(
                cte + "SELECT q.missing FROM q;"
                        + " SELECT c1 FROM b;");
        assertEquals(2, missing.getResults().size());
        assertTrue(missing.getResults().get(0).getError()
                instanceof ColumnNotFoundException);
        assertTrue(missing.getResults().get(0).getInstructions().isEmpty());
        assertNull(missing.getResults().get(1).getError());
    }

    @Test
    public void unknownStarWidthDoesNotFailUnion() {
        ScanResult result = new LineageScanner(DbType.mysql).scan(
                "SELECT * FROM a UNION ALL SELECT c1,c3 FROM b;"
                        + " SELECT c1 FROM b;");

        assertEquals(2, result.getResults().size());
        assertNull(result.getResults().get(0).getError());
        assertNull(result.getResults().get(1).getError());
        assertEquals(columns("b.c1"),
                result.getGraph().getSources(Column.temporary("stmt1.temp0.c1")));
    }

    @Test
    public void unionColumnCountMismatchFails() {
        MetadataContext context = pgContext(
                "CREATE TABLE a (c1 INT); CREATE TABLE b (c1 INT, c3 INT);");

        ScanResult result = new LineageScanner(context).scan(
                "SELECT d.* FROM (SELECT c1 FROM a"
                        + " UNION ALL SELECT c1,c3 FROM b) d(k);"
                        + " SELECT c1 FROM a;");

        assertEquals(2, result.getResults().size());
        assertTrue(result.getResults().get(0).getError() != null);
        assertTrue(result.getResults().get(0).getInstructions().isEmpty());
        assertNull(result.getResults().get(1).getError());
        assertEquals(columns("a.c1"),
                result.getGraph().getSources(Column.temporary("stmt1.temp0.c1")));
    }

    @Test
    public void missingInsertTargetColumnFailsWithoutGraphPollution() {
        MetadataContext context = mysqlContext(
                "CREATE TABLE b (c1 INT); CREATE TABLE q (c1 INT);");

        ScanResult result = new LineageScanner(context).scan(
                "INSERT INTO b(missing) SELECT c1 FROM q; SELECT c1 FROM q;");

        assertEquals(2, result.getResults().size());
        assertEquals(1, result.getFailures().size());
        assertTrue(result.getResults().get(0).getError() instanceof ColumnNotFoundException);
        assertTrue(result.getResults().get(0).getInstructions().isEmpty());
        for (Column vertex : result.getGraph().getReverseGraph().vertexSet()) {
            assertFalse("b".equalsIgnoreCase(vertex.getTableName()));
        }
        assertEquals(columns("q.c1"),
                result.getGraph().getSources(Column.temporary("stmt1.temp0.c1")));
    }

    @Test
    public void outerPhysicalTableIsNotShadowedByInnerCteScope() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "SELECT q.c1 FROM"
                        + " (WITH q AS (SELECT c1 FROM B) SELECT c1 FROM q) d"
                        + " CROSS JOIN q;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("q.c1"),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.c1")));
    }

    @Test
    public void nonRecursiveCteBodySeesThePhysicalTable() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "WITH q AS (SELECT c1 FROM q) SELECT c1 FROM q;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("q.c1"),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.c1")));
    }

    @Test
    public void derivedAliasNamedTempDoesNotLoop() {
        MetadataContextHolder.clear();

        ScanResult result = new LineageScanner(DbType.mysql).scan(
                "SELECT c1 FROM (SELECT c1 FROM A) temp0; SELECT c1 FROM B;");

        assertEquals(2, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("A.c1"),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.c1")));
        assertEquals(columns("B.c1"),
                result.getGraph().getSources(Column.temporary("stmt1.temp0.c1")));
    }

    @Test
    public void cteAliasNamedTempDoesNotLoop() {
        MetadataContextHolder.clear();

        ScanResult result = new LineageScanner(DbType.mysql).scan(
                "WITH temp0 AS (SELECT c1 FROM A) SELECT c1 FROM temp0;");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("A.c1"),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.c1")));
    }

    @Test
    public void dollarTagInsideIdentifierIsNotADollarQuote() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "SELECT c1 FROM foo$tag$; SELECT 1 AS c;");

        assertEquals(2, result.getResults().size());
        assertEquals(0, result.getFailures().size());
    }

    @Test
    public void postgresEscapeStringKeepsSemicolon() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "SELECT E'a\\'b;c' AS c FROM A; SELECT c1 FROM A;");

        assertEquals(2, result.getResults().size());
        assertNull(result.getResults().get(1).getError());
    }

    @Test
    public void mysqlDoubleMinusWithoutSpaceIsNotAComment() {
        ScanResult result = new LineageScanner(DbType.mysql).scan(
                "SELECT c1--1 FROM A; SELECT c1 FROM A;");

        assertEquals(2, result.getResults().size());
        assertNull(result.getResults().get(1).getError());
    }

    @Test
    public void handlesMysqlHashCommentsAndTrailingComments() {
        MetadataContext context = mysqlContext("CREATE TABLE db1.A (c1 INT);");

        ScanResult result = new LineageScanner(context).scan(
                "SELECT c1 FROM db1.A; # hash ; comment\n"
                        + "SELECT c1 FROM db1.A; -- final ; comment\n");

        assertEquals(2, result.getResults().size());
        assertEquals(0, result.getFailures().size());
    }

    @Test
    public void handlesPostgresDollarQuotedSemicolons() {
        ScanResult result = new LineageScanner(DbType.postgresql).scan(
                "SELECT $$a;b$$ AS c; SELECT 1 AS c;");

        // The semicolon inside $$...$$ must not be treated as a statement separator.
        assertEquals(2, result.getResults().size());
        assertTrue(result.getResults().get(0).getSql().contains("$$a;b$$"));
        // Druid 1.2.23's PostgreSQL parser does not support dollar-quoted strings; the
        // statement is reported as an error, but the following statement still parses.
        assertTrue(result.getResults().get(0).getError()
                instanceof com.alibaba.druid.sql.parser.ParserException);
        assertNull(result.getResults().get(1).getError());
    }

    @Test
    public void handlesMysqlBackslashEscapedQuotes() {
        ScanResult result = new LineageScanner(DbType.mysql).scan(
                "SELECT 'a\\'b;c' AS c FROM A; SELECT c1 FROM A;");

        assertEquals(2, result.getResults().size());
        assertEquals(0, result.getFailures().size());
    }

    @Test
    public void ignoresTrailingCommentOnlyStatement() {
        ScanResult result = new LineageScanner(DbType.mysql)
                .scan("SELECT c1 FROM A; -- trailing ; comment\n");

        assertEquals(1, result.getResults().size());
        assertEquals(0, result.getFailures().size());
    }

    @Test
    public void scansAnIterableOfLogLines() {
        MetadataContext context = mysqlContext("CREATE TABLE db1.A (c1 INT);");

        ScanResult result = new LineageScanner(context).scan(Arrays.asList(
                "SELECT c1", "FROM db1.A;", "-- comment", "SELECT c1 FROM db1.A;"));

        assertEquals(2, result.getResults().size());
        assertEquals(0, result.getFailures().size());
        assertEquals(columns("db1.A.c1"),
                result.getGraph().getSources(Column.temporary("stmt0.temp0.c1")));
        assertEquals(columns("db1.A.c1"),
                result.getGraph().getSources(Column.temporary("stmt1.temp0.c1")));
    }

    private static MetadataContext mysqlContext(String ddl) {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.loadDdl(ddl);
        return MetadataContext.of(catalog);
    }

    private static MetadataContext pgContext(String ddl) {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl(ddl);
        return MetadataContext.of(catalog);
    }

    private static boolean hasMapping(ScanResult result, String from, String to) {
        for (Instruction instruction : result.getResults().get(0).getInstructions()) {
            if (from.equals(instruction.getFrom()) && to.equals(instruction.getTo())) {
                return true;
            }
        }
        return false;
    }

    private static Set<com.laotie.model.metadata.Column> columns(String... names) {
        Set<com.laotie.model.metadata.Column> columns = new HashSet<>();
        for (String name : names) {
            columns.add(new com.laotie.model.metadata.Column(name));
        }
        return columns;
    }
}

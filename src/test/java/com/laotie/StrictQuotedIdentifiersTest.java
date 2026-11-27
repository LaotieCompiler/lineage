package com.laotie;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashSet;

import org.junit.Test;

import com.alibaba.druid.DbType;
import com.laotie.exception.UnquotedIdentifierException;
import com.laotie.model.ScanResult;
import com.laotie.model.metadata.Column;
import com.laotie.model.metadata.MetadataMode;

/**
 * Strict quoted syntax: tables, columns, aliases and CTE names must be quoted.
 */
public class StrictQuotedIdentifiersTest {

    private static LineageOptions strict() {
        return LineageOptions.of(MetadataMode.OPTIONAL, false, true);
    }

    private static LineageOptions optional() {
        return LineageOptions.of(MetadataMode.OPTIONAL, false);
    }

    @Test
    public void quotedQueryProducesLineage() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.mysql, strict()).scan("SELECT `c1` FROM `t`;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("t.c1"))),
                result.getGraph().getSources(
                        Column.temporary("stmt0.temp0.c1")));
    }

    @Test
    public void unquotedColumnIsRejected() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.mysql, strict()).scan("SELECT c1 FROM `t`;");

        assertTrue(result.getResults().get(0).getError()
                instanceof UnquotedIdentifierException);
    }

    @Test
    public void unquotedTableIsRejected() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.mysql, strict()).scan("SELECT `c1` FROM t;");

        assertTrue(result.getResults().get(0).getError()
                instanceof UnquotedIdentifierException);
    }

    @Test
    public void unquotedAliasIsRejected() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.mysql, strict()).scan(
                "SELECT `t`.`c1` FROM `a` AS t;");

        assertTrue(result.getResults().get(0).getError()
                instanceof UnquotedIdentifierException);
    }

    @Test
    public void quotedAliasIsAccepted() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.mysql, strict()).scan(
                "SELECT `t`.`c1` FROM `a` AS `t`;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("a.c1"))),
                result.getGraph().getSources(
                        Column.temporary("stmt0.temp0.c1")));
    }

    @Test
    public void unquotedCteIsRejected() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.mysql, strict()).scan(
                "WITH q AS (SELECT `c1` FROM `a`)"
                        + " SELECT `q`.`c1` FROM `q`;");

        assertTrue(result.getResults().get(0).getError()
                instanceof UnquotedIdentifierException);
    }

    @Test
    public void quotedCteIsAccepted() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.mysql, strict()).scan(
                "WITH `q` AS (SELECT `c1` FROM `a`)"
                        + " SELECT `q`.`c1` FROM `q`;");

        assertEquals(0, result.getFailures().size());
    }

    @Test
    public void unquotedInsertColumnIsRejected() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.mysql, strict()).scan(
                "INSERT INTO `t` (c1) SELECT `c1` FROM `a`;");

        assertTrue(result.getResults().get(0).getError()
                instanceof UnquotedIdentifierException);
    }

    @Test
    public void quotedInsertColumnIsAccepted() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.mysql, strict()).scan(
                "INSERT INTO `t` (`c1`) SELECT `c1` FROM `a`;");

        assertEquals(0, result.getFailures().size());
        assertEquals(new HashSet<>(Arrays.asList(new Column("a.c1"))),
                result.getGraph().getSources(new Column("t.c1")));
    }

    @Test
    public void unquotedQualifiedTableSegmentIsRejected() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.mysql, strict()).scan(
                "SELECT `c1` FROM db.`t`;");

        assertTrue(result.getResults().get(0).getError()
                instanceof UnquotedIdentifierException);
    }

    @Test
    public void quotedQualifiedTableIsAccepted() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.mysql, strict()).scan(
                "SELECT `c1` FROM `db`.`t`;");

        assertEquals(0, result.getFailures().size());
    }

    @Test
    public void oracleQuotedInsertIsAccepted() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.oracle, strict()).scan(
                "INSERT INTO \"t\" (\"c\") SELECT \"c\" FROM \"a\";");

        assertEquals(0, result.getFailures().size());
    }

    @Test
    public void postgresReturningColumnMustBeQuoted() {
        assertTrue(LineageScanner.withoutMetadata(DbType.postgresql, strict())
                .scan("UPDATE \"t\" SET \"c\" = 1 RETURNING c;")
                .getResults().get(0).getError()
                instanceof UnquotedIdentifierException);
        assertEquals(0, LineageScanner.withoutMetadata(DbType.postgresql, strict())
                .scan("UPDATE \"t\" SET \"c\" = 1 RETURNING \"c\";")
                .getFailures().size());
    }

    @Test
    public void mysqlDuplicateKeyColumnMustBeQuoted() {
        assertTrue(LineageScanner.withoutMetadata(DbType.mysql, strict())
                .scan("INSERT INTO `t` (`c`) SELECT `c` FROM `s`"
                        + " ON DUPLICATE KEY UPDATE c = 1;")
                .getResults().get(0).getError()
                instanceof UnquotedIdentifierException);
        assertEquals(0, LineageScanner.withoutMetadata(DbType.mysql, strict())
                .scan("INSERT INTO `t` (`c`) SELECT `c` FROM `s`"
                        + " ON DUPLICATE KEY UPDATE `c` = 1;")
                .getFailures().size());
    }

    @Test
    public void derivedColumnDeclarationMustBeQuoted() {
        assertTrue(LineageScanner.withoutMetadata(DbType.postgresql, strict())
                .scan("SELECT \"q\".\"k\" FROM"
                        + " (SELECT \"c\" FROM \"t\") AS \"q\"(k);")
                .getResults().get(0).getError()
                instanceof UnquotedIdentifierException);
        assertEquals(0, LineageScanner.withoutMetadata(DbType.postgresql, strict())
                .scan("SELECT \"q\".\"k\" FROM"
                        + " (SELECT \"c\" FROM \"t\") AS \"q\"(\"k\");")
                .getFailures().size());
    }

    @Test
    public void extractUnitIsNotAColumnReference() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.postgresql, strict()).scan(
                "SELECT EXTRACT(YEAR FROM \"c\") FROM \"t\";");

        assertEquals(0, result.getFailures().size());
    }

    @Test
    public void sqlServerQuotedInsertIsAccepted() {
        assertEquals(0, LineageScanner.withoutMetadata(DbType.sqlserver, strict())
                .scan("INSERT INTO [t] ([c]) SELECT [c] FROM [s];")
                .getFailures().size());
        assertTrue(LineageScanner.withoutMetadata(DbType.sqlserver, strict())
                .scan("INSERT INTO [t] (c) SELECT [c] FROM [s];")
                .getResults().get(0).getError()
                instanceof UnquotedIdentifierException);
    }

    @Test
    public void oracleTableAliasMustBeQuoted() {
        assertTrue(LineageScanner.withoutMetadata(DbType.oracle, strict())
                .scan("SELECT \"c\" FROM \"t\" a;")
                .getResults().get(0).getError()
                instanceof UnquotedIdentifierException);
        assertEquals(0, LineageScanner.withoutMetadata(DbType.oracle, strict())
                .scan("SELECT \"c\" FROM \"t\" \"a\";")
                .getFailures().size());
    }

    @Test
    public void postgresOnConflictColumnsMustBeQuoted() {
        String bare = "INSERT INTO \"t\" (\"c\") SELECT \"c\" FROM \"s\""
                + " ON CONFLICT (c) DO UPDATE SET c = 1;";
        String quoted = "INSERT INTO \"t\" (\"c\") SELECT \"c\" FROM \"s\""
                + " ON CONFLICT (\"c\") DO UPDATE SET \"c\" = 1;";
        assertTrue(LineageScanner.withoutMetadata(DbType.postgresql, strict())
                .scan(bare).getResults().get(0).getError()
                instanceof UnquotedIdentifierException);
        assertEquals(0, LineageScanner.withoutMetadata(DbType.postgresql, strict())
                .scan(quoted).getFailures().size());
    }

    @Test
    public void schemaQualifiedExtractIsNotAUnit() {
        assertTrue(LineageScanner.withoutMetadata(DbType.postgresql, strict())
                .scan("SELECT \"s\".extract(c, \"x\") FROM \"t\";")
                .getResults().get(0).getError()
                instanceof UnquotedIdentifierException);
        assertEquals(0, LineageScanner.withoutMetadata(DbType.postgresql, strict())
                .scan("SELECT \"s\".extract(\"c\", \"x\") FROM \"t\";")
                .getFailures().size());
    }

    @Test
    public void timestampdiffUnitIsNotAColumn() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.mysql, strict()).scan(
                "SELECT TIMESTAMPDIFF(DAY, `x`, `y`) FROM `t`;");

        assertEquals(0, result.getFailures().size());
    }

    @Test
    public void postgresInsertWithoutConflictListsIsAccepted() {
        assertEquals(0, LineageScanner.withoutMetadata(DbType.postgresql, strict())
                .scan("INSERT INTO \"t\" (\"c\") SELECT \"c\" FROM \"s\";")
                .getFailures().size());
        assertEquals(0, LineageScanner.withoutMetadata(DbType.postgresql, strict())
                .scan("INSERT INTO \"t\" (\"c\") SELECT \"c\" FROM \"s\""
                        + " ON CONFLICT (\"c\") DO NOTHING;")
                .getFailures().size());
    }

    @Test
    public void schemaQualifiedTimestampdiffIsNotAUnit() {
        assertTrue(LineageScanner.withoutMetadata(DbType.mysql, strict())
                .scan("SELECT `s`.timestampdiff(c, `x`, `y`) FROM `t`;")
                .getResults().get(0).getError()
                instanceof UnquotedIdentifierException);
        assertEquals(0, LineageScanner.withoutMetadata(DbType.mysql, strict())
                .scan("SELECT `s`.timestampdiff(`c`, `x`, `y`) FROM `t`;")
                .getFailures().size());
    }

    @Test
    public void sqlServerDateaddUnitIsNotAColumn() {
        assertEquals(0, LineageScanner.withoutMetadata(DbType.sqlserver, strict())
                .scan("SELECT DATEADD(DAY, 1, [c]) FROM [t];")
                .getFailures().size());
    }

    @Test
    public void datediffArgumentsAreColumnsOutsideSqlServer() {
        for (DbType dialect : Arrays.asList(DbType.mysql, DbType.mariadb,
                DbType.hive, DbType.antspark)) {
            assertTrue(LineageScanner.withoutMetadata(dialect, strict())
                    .scan("SELECT DATEDIFF(c, `d`) FROM `t`;")
                    .getResults().get(0).getError()
                    instanceof UnquotedIdentifierException);
            assertEquals(0, LineageScanner.withoutMetadata(dialect, strict())
                    .scan("SELECT DATEDIFF(`c`, `d`) FROM `t`;")
                    .getFailures().size());
        }
    }

    @Test
    public void sqlServerDateBucketUnitIsNotAColumn() {
        assertEquals(0, LineageScanner.withoutMetadata(DbType.sqlserver, strict())
                .scan("SELECT DATE_BUCKET(DAY, 1, [c]) FROM [t];")
                .getFailures().size());
        assertEquals(0, LineageScanner.withoutMetadata(DbType.sqlserver, strict())
                .scan("SELECT DATEDIFF(DAY, [c], [d]) FROM [t];")
                .getFailures().size());
    }

    @Test
    public void sybaseAndH2DateaddUnitIsNotAColumn() {
        for (DbType dialect : Arrays.asList(DbType.sybase, DbType.h2)) {
            assertEquals(0, LineageScanner.withoutMetadata(dialect, strict())
                    .scan("SELECT DATEADD(DAY, 1, \"c\") FROM \"t\";")
                    .getFailures().size());
        }
    }

    @Test
    public void timestampdiffIsAUnitOnlyInMysqlFamily() {
        assertTrue(LineageScanner.withoutMetadata(DbType.postgresql, strict())
                .scan("SELECT timestampdiff(c, \"x\", \"y\") FROM \"t\";")
                .getResults().get(0).getError()
                instanceof UnquotedIdentifierException);
        assertEquals(0, LineageScanner.withoutMetadata(DbType.postgresql, strict())
                .scan("SELECT timestampdiff(\"c\", \"x\", \"y\") FROM \"t\";")
                .getFailures().size());
    }

    @Test
    public void getFormatTypeIsNotAColumn() {
        for (DbType dialect : Arrays.asList(DbType.mysql, DbType.mariadb)) {
            assertEquals(0, LineageScanner.withoutMetadata(dialect, strict())
                    .scan("SELECT GET_FORMAT(DATE, `c`) FROM `t`;")
                    .getFailures().size());
        }
    }

    @Test
    public void h2AndOdbsTimestampaddUnitIsNotAColumn() {
        assertEquals(0, LineageScanner.withoutMetadata(DbType.h2, strict())
                .scan("SELECT TIMESTAMPADD(DAY, 1, \"c\") FROM \"t\";")
                .getFailures().size());
        assertEquals(0, LineageScanner.withoutMetadata(DbType.sqlserver, strict())
                .scan("SELECT {fn TIMESTAMPADD(SQL_TSI_DAY, 1, [c])}"
                        + " FROM [t];")
                .getFailures().size());
    }

    @Test
    public void h2DateBucketIsAnOrdinaryFunction() {
        assertTrue(LineageScanner.withoutMetadata(DbType.h2, strict())
                .scan("SELECT DATE_BUCKET(c, 1) FROM \"numbers\";")
                .getResults().get(0).getError()
                instanceof UnquotedIdentifierException);
    }

    @Test
    public void derbyAndHsqlTimestampaddUnitIsNotAColumn() {
        for (DbType dialect : Arrays.asList(DbType.derby, DbType.hsql)) {
            assertEquals(0, LineageScanner.withoutMetadata(dialect, strict())
                    .scan("SELECT {fn TIMESTAMPADD(SQL_TSI_DAY, 1, \"c\")}"
                            + " FROM \"t\";")
                    .getFailures().size());
        }
    }

    @Test
    public void h2DatetruncIsAnOrdinaryFunction() {
        assertTrue(LineageScanner.withoutMetadata(DbType.h2, strict())
                .scan("SELECT DATETRUNC(c, 1) FROM \"numbers\";")
                .getResults().get(0).getError()
                instanceof UnquotedIdentifierException);
        assertEquals(0, LineageScanner.withoutMetadata(DbType.sqlserver, strict())
                .scan("SELECT DATETRUNC(DAY, [c]) FROM [t];")
                .getFailures().size());
    }

    @Test
    public void h2DateTruncUnitIsNotAColumn() {
        assertEquals(0, LineageScanner.withoutMetadata(DbType.h2, strict())
                .scan("SELECT DATE_TRUNC(DAY, \"c\") FROM \"t\";")
                .getFailures().size());
    }

    @Test
    public void timestampaddExemptionRequiresAUnitKeyword() {
        assertTrue(LineageScanner.withoutMetadata(DbType.derby, strict())
                .scan("SELECT TIMESTAMPADD(c, 1) FROM \"t\";")
                .getResults().get(0).getError()
                instanceof UnquotedIdentifierException);
        assertEquals(0, LineageScanner.withoutMetadata(DbType.derby, strict())
                .scan("SELECT {fn TIMESTAMPADD(SQL_TSI_DAY, 1, \"c\")}"
                        + " FROM \"t\";")
                .getFailures().size());
    }

    @Test
    public void commonDatePartSpellingsAreNotColumns() {
        assertEquals(0, LineageScanner.withoutMetadata(DbType.sqlserver, strict())
                .scan("SELECT DATEADD(dd, 1, [c]) FROM [t];")
                .getFailures().size());
        assertEquals(0, LineageScanner.withoutMetadata(DbType.postgresql, strict())
                .scan("SELECT EXTRACT(EPOCH FROM \"c\") FROM \"t\";")
                .getFailures().size());
    }

    @Test
    public void jdbcTimestampUnitsMustUseSqlTsiNames() {
        assertTrue(LineageScanner.withoutMetadata(DbType.derby, strict())
                .scan("SELECT TIMESTAMPADD(day, 1) FROM \"t\";")
                .getResults().get(0).getError()
                instanceof UnquotedIdentifierException);
        assertTrue(LineageScanner.withoutMetadata(DbType.derby, strict())
                .scan("SELECT TIMESTAMPADD(sql_tsi_counter, 1) FROM \"t\";")
                .getResults().get(0).getError()
                instanceof UnquotedIdentifierException);
        assertEquals(0, LineageScanner.withoutMetadata(DbType.derby, strict())
                .scan("SELECT {fn TIMESTAMPADD(SQL_TSI_DAY, 1, \"c\")}"
                        + " FROM \"t\";")
                .getFailures().size());
    }

    @Test
    public void hsqlTimestampUnitsAreKeywords() {
        assertEquals(0, LineageScanner.withoutMetadata(DbType.hsql, strict())
                .scan("SELECT TIMESTAMPADD(DAY, 1, \"c\") FROM \"t\";")
                .getFailures().size());
        assertEquals(0, LineageScanner.withoutMetadata(DbType.hsql, strict())
                .scan("SELECT TIMESTAMPADD(SQL_TSI_MILLI_SECOND, 1, \"c\")"
                        + " FROM \"t\";")
                .getFailures().size());
    }

    @Test
    public void sqlServerDatePartNamesAreKeywords() {
        assertEquals(0, LineageScanner.withoutMetadata(DbType.sqlserver, strict())
                .scan("SELECT DATEPART(weekday, [c]) FROM [t];")
                .getFailures().size());
        assertEquals(0, LineageScanner.withoutMetadata(DbType.sqlserver, strict())
                .scan("SELECT DATEPART(dayofyear, [c]) FROM [t];")
                .getFailures().size());
    }

    @Test
    public void derbyPlainTimestampaddIsNotAnEscape() {
        assertTrue(LineageScanner.withoutMetadata(DbType.derby, strict())
                .scan("SELECT TIMESTAMPADD(sql_tsi_day, 1) FROM \"t\";")
                .getResults().get(0).getError()
                instanceof UnquotedIdentifierException);
    }

    @Test
    public void strictQuotingIsOffByDefault() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.mysql, optional()).scan("SELECT c1 FROM t;");

        assertNull(result.getResults().get(0).getError());
    }
}

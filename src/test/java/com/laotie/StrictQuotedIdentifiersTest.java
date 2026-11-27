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
    public void strictQuotingIsOffByDefault() {
        ScanResult result = LineageScanner.withoutMetadata(
                DbType.mysql, optional()).scan("SELECT c1 FROM t;");

        assertNull(result.getResults().get(0).getError());
    }
}

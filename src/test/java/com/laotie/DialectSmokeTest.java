package com.laotie;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.alibaba.druid.DbType;
import com.laotie.model.LineageResult;
import com.laotie.model.ScanResult;
import com.laotie.model.metadata.MetadataMode;

/**
 * Statement-level smoke coverage for dialects that the supported-database list claims
 * but that previously had no end-to-end regression test (SQLite, DB2, Sybase, Oracle).
 */
public class DialectSmokeTest {

    private static LineageOptions optional() {
        return LineageOptions.of(MetadataMode.OPTIONAL, false);
    }

    private static ScanResult scan(DbType dialect, String sql) {
        return LineageScanner.withoutMetadata(dialect, optional()).scan(sql);
    }

    private static void assertBasicStatements(DbType dialect) {
        ScanResult result = scan(dialect,
                "SELECT c1 FROM t;"
                        + " INSERT INTO t (c1) SELECT c1 FROM s;"
                        + " UPDATE t SET c1 = (SELECT c2 FROM s);");

        assertEquals(3, result.getResults().size());
        for (LineageResult statement : result.getResults()) {
            assertNull(dialect + ": " + statement.getSql() + " -> " + statement.getError(),
                    statement.getError());
        }
        assertTrue(dialect + " select: "
                + result.getResults().get(0).getInstructions(),
                result.getResults().get(0).getInstructions().toString()
                        .contains("t.c1 -> "));
        assertTrue(dialect + " insert: "
                + result.getResults().get(1).getInstructions(),
                result.getResults().get(1).getInstructions().toString()
                        .contains("s.c1 -> "));
        assertTrue(dialect + " update: "
                + result.getResults().get(2).getInstructions(),
                result.getResults().get(2).getInstructions().toString()
                        .contains("t.c1"));
    }

    @Test
    public void sqliteSupportsBasicStatements() {
        assertBasicStatements(DbType.sqlite);
    }

    @Test
    public void db2SupportsBasicStatements() {
        assertBasicStatements(DbType.db2);
    }

    @Test
    public void sybaseSupportsBasicStatements() {
        assertBasicStatements(DbType.sybase);
    }

    @Test
    public void oracleSupportsBasicStatements() {
        assertBasicStatements(DbType.oracle);
    }

    @Test
    public void sqliteDoubleQuotedIdentifiersAreAccepted() {
        ScanResult result = scan(DbType.sqlite, "SELECT \"c1\" FROM \"t\";");

        assertNull(result.getResults().get(0).getError());
        assertTrue(result.getResults().get(0).getInstructions().toString()
                .contains("c1"));
    }

    @Test
    public void sybaseBracketIdentifiersAreAccepted() {
        ScanResult result = scan(DbType.sybase, "SELECT [c1] FROM [t];");

        assertNull(result.getResults().get(0).getError());
        assertTrue(result.getResults().get(0).getInstructions().toString()
                .contains("[c1]"));
    }

    @Test
    public void oracleQuotedIdentifiersAreAccepted() {
        ScanResult result = scan(DbType.oracle, "SELECT \"c1\" FROM \"t\";");

        assertNull(result.getResults().get(0).getError());
        assertTrue(result.getResults().get(0).getInstructions().toString()
                .contains("c1"));
    }
}

package com.laotie;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.alibaba.druid.DbType;
import com.laotie.exception.ColumnNotFoundException;
import com.laotie.exception.MetadataNotFoundException;
import com.laotie.model.Instruction;
import com.laotie.model.Instruction.OperationType;
import com.laotie.model.LineageResult;
import com.laotie.model.ScanResult;
import com.laotie.model.metadata.MetadataContext;
import com.laotie.model.metadata.MetadataMode;

/**
 * DDL in a scanned script: CTAS, views, DROP and ALTER. A relation created by DDL must
 * be the target of its lineage and must be visible to the statements that follow it.
 */
public class DdlLineageTest {

    private static LineageOptions strict() {
        return LineageTestSupport.strict();
    }

    private static MetadataContext catalog(String ddl) {
        return LineageTestSupport.postgres(ddl);
    }

    private static MetadataContext base() {
        return catalog("CREATE TABLE s (c INT, d INT);"
                + " CREATE TABLE t (c INT, d INT);");
    }

    private static Instruction injectInto(LineageResult result, String table) {
        for (Instruction instruction : result.getInstructions()) {
            if (instruction.getOperation() == OperationType.COLUMNS_INJECT
                    && instruction.getTo().startsWith(table)) {
                return instruction;
            }
        }
        return null;
    }

    @Test
    public void ctasWritesIntoTheCreatedTable() {
        LineageResult result = LineageScanner.withMetadata(base(), strict())
                .scan("CREATE TABLE x AS SELECT c, d FROM s;").getResults().get(0);

        assertNull(result.getError());
        Instruction inject = injectInto(result, "x");
        assertTrue("expected a COLUMNS_INJECT into x: " + result.getInstructions(),
                inject != null);
        assertTrue(inject.getFrom().startsWith("temp"));
        assertTrue(result.getInstructions().toString().contains("s.c -> "));
    }

    @Test
    public void ctasDeclaredColumnsNameTheTarget() {
        ScanResult result = LineageScanner.withMetadata(base(), strict()).scan(
                "CREATE TABLE x (a, b) AS SELECT c, d FROM s;"
                        + " SELECT a, b FROM x;");

        assertNull(result.getResults().get(0).getError());
        Instruction inject = injectInto(result.getResults().get(0), "x(a,b)");
        assertTrue("expected the declared column list: "
                + result.getResults().get(0).getInstructions(), inject != null);
        assertNull(result.getResults().get(1).getError());
        List<Instruction> reads = result.getResults().get(1).getInstructions();
        assertEquals(2, reads.size());
        assertTrue(reads.toString().contains("x.a"));
        assertTrue(reads.toString().contains("x.b"));
    }

    @Test
    public void ctasDeclaredColumnsHideTheProjectionNames() {
        ScanResult result = LineageScanner.withMetadata(base(), strict()).scan(
                "CREATE TABLE x (a, b) AS SELECT c, d FROM s;"
                        + " SELECT c FROM x;");

        assertTrue(result.getResults().get(1).getError()
                instanceof ColumnNotFoundException);
    }

    @Test
    public void createdTableIsVisibleToLaterStatements() {
        ScanResult result = LineageScanner.withMetadata(base(), strict()).scan(
                "CREATE TABLE x AS SELECT c, d FROM s;"
                        + " INSERT INTO t(c) SELECT c FROM x;");

        assertNull(result.getResults().get(0).getError());
        assertNull(result.getResults().get(1).getError());
        assertTrue(result.getResults().get(1).getInstructions().toString()
                .contains("x.c -> "));
    }

    @Test
    public void plainDdlColumnsAreVisibleToLaterStatements() {
        ScanResult result = LineageScanner.withMetadata(base(), strict()).scan(
                "CREATE TABLE x (c INT); SELECT c FROM x;");

        assertNull(result.getResults().get(0).getError());
        assertNull(result.getResults().get(1).getError());
        assertTrue(result.getResults().get(1).getInstructions().toString()
                .contains("x.c -> "));
    }

    @Test
    public void viewProducesLineageAndIsReadable() {
        ScanResult result = LineageScanner.withMetadata(base(), strict()).scan(
                "CREATE VIEW v AS SELECT c FROM s;"
                        + " INSERT INTO t(c) SELECT c FROM v;");

        assertNull(result.getResults().get(0).getError());
        assertTrue(injectInto(result.getResults().get(0), "v") != null);
        assertNull(result.getResults().get(1).getError());
        assertTrue(result.getResults().get(1).getInstructions().toString()
                .contains("v.c -> "));
    }

    @Test
    public void createOrReplaceViewReplacesItsColumns() {
        ScanResult result = LineageScanner.withMetadata(base(), strict()).scan(
                "CREATE VIEW v AS SELECT c FROM s;"
                        + " CREATE OR REPLACE VIEW v AS SELECT d FROM s;"
                        + " SELECT c FROM v;");

        assertNull(result.getResults().get(1).getError());
        assertTrue(result.getResults().get(2).getError()
                instanceof ColumnNotFoundException);
    }

    @Test
    public void dropTableHidesItFromLaterStatements() {
        ScanResult result = LineageScanner.withMetadata(base(), strict()).scan(
                "DROP TABLE t; SELECT c FROM t;");

        assertNull(result.getResults().get(0).getError());
        assertTrue(result.getResults().get(1).getError()
                instanceof MetadataNotFoundException);
    }

    @Test
    public void alterAddColumnIsVisible() {
        ScanResult result = LineageScanner.withMetadata(base(), strict()).scan(
                "ALTER TABLE t ADD COLUMN e INT; SELECT e FROM t;");

        assertNull(result.getResults().get(0).getError());
        assertNull(result.getResults().get(1).getError());
        assertTrue(result.getResults().get(1).getInstructions().toString()
                .contains("t.e -> "));
    }

    @Test
    public void alterDropColumnIsHidden() {
        ScanResult result = LineageScanner.withMetadata(base(), strict()).scan(
                "ALTER TABLE t DROP COLUMN d; SELECT d FROM t;");

        assertNull(result.getResults().get(0).getError());
        assertTrue(result.getResults().get(1).getError()
                instanceof ColumnNotFoundException);
    }

    @Test
    public void deleteAndTruncateEmitNoColumnLineage() {
        ScanResult result = LineageScanner.withMetadata(base(), strict()).scan(
                "DELETE FROM t WHERE c IN (SELECT c FROM s);"
                        + " TRUNCATE TABLE t, s;");

        assertNull(result.getResults().get(0).getError());
        assertNull(result.getResults().get(1).getError());
        assertTrue(result.getResults().get(0).getInstructions().isEmpty());
        assertTrue(result.getResults().get(1).getInstructions().isEmpty());
    }

    @Test
    public void deleteUsingJoinEmitsNoColumnLineage() {
        ScanResult result = LineageScanner.withMetadata(base(), strict())
                .scan("DELETE FROM t USING s WHERE t.c = s.c;");

        assertNull(result.getResults().get(0).getError());
        assertTrue(result.getResults().get(0).getInstructions().isEmpty());
    }

    @Test
    public void deleteMissingTableStillFailsInStrictMode() {
        ScanResult result = LineageScanner.withMetadata(base(), strict())
                .scan("DELETE FROM missing WHERE c = 1;");

        assertTrue(result.getResults().get(0).getError()
                instanceof MetadataNotFoundException);
    }

    @Test
    public void ctasWritesToTheCreatedTableAcrossDialects() {
        DbType[] dialects = {DbType.hive, DbType.antspark, DbType.mysql, DbType.oracle};
        for (DbType dialect : dialects) {
            ScanResult result = LineageScanner.withoutMetadata(dialect,
                    LineageOptions.of(MetadataMode.OPTIONAL, false))
                    .scan("CREATE TABLE x AS SELECT c FROM s;");
            LineageResult statement = result.getResults().get(0);
            assertNull(dialect + ": " + statement.getError(), statement.getError());
            assertTrue(dialect + ": " + statement.getInstructions(),
                    injectInto(statement, "x") != null);
        }
    }

    @Test
    public void ctasWorksWithoutACatalog() {
        ScanResult result = LineageScanner.withoutMetadata(DbType.postgresql,
                LineageOptions.of(MetadataMode.OPTIONAL, false)).scan(
                "CREATE TABLE x AS SELECT c FROM s;"
                        + " INSERT INTO t(c) SELECT c FROM x;");

        assertNull(result.getResults().get(0).getError());
        assertTrue(injectInto(result.getResults().get(0), "x") != null);
        assertNull(result.getResults().get(1).getError());
    }
}

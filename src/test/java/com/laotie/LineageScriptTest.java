package com.laotie;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.After;
import org.junit.Test;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.parser.ParserException;
import com.laotie.exception.ColumnNotFoundException;
import com.laotie.model.Instruction;
import com.laotie.model.Instruction.OperationType;
import com.laotie.model.LineageResult;
import com.laotie.model.metadata.MetadataCatalog;
import com.laotie.model.metadata.MetadataContext;
import com.laotie.model.metadata.MetadataContextHolder;

/**
 * Specification for multi-statement scripts and session context switching.
 *
 * <p>
 * {@code DmlLineage.parseScript(sql)} splits the script on semicolons that are outside quotes and
 * comments (it must not rely on Druid's batch parser for PostgreSQL {@code SET} statements), then
 * returns one {@link LineageResult} per statement, in order. A failing statement is reported via
 * {@link LineageResult#getError()} and does not stop the following statements.
 *
 * <p>
 * {@code USE} / {@code SET search_path} / {@code SET CURRENT SCHEMA} produce a result with empty
 * instructions but the effective database/schema after applying the statement. Each statement uses
 * a fresh temporary-table scope (temp0).
 *
 * <p>
 * Expected to be red until {@code parseScript}, the statement splitter, USE/SET handling,
 * {@code LineageResult#getError} and the effective-context accessors exist.
 */
public class LineageScriptTest {

    @After
    public void tearDown() {
        MetadataContextHolder.clear();
    }

    @Test
    public void useStatementSwitchesDatabaseForSubsequentStatements() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.loadDdl("CREATE TABLE db1.A (c1 INT); CREATE TABLE db2.A (c2 INT);");
        MetadataContext context = MetadataContext.of(catalog);

        List<LineageResult> results = new DmlLineage(context).parseScript(
                "USE db1; SELECT c1 FROM A; USE db2; SELECT c2 FROM A;");

        assertEquals(4, results.size());
        assertNull(results.get(0).getError());
        assertNull(results.get(1).getError());
        assertNull(results.get(2).getError());
        assertNull(results.get(3).getError());

        assertTrue(results.get(0).getSql().toUpperCase().contains("USE"));
        assertTrue(results.get(0).getInstructions().isEmpty());
        assertEquals("db1", results.get(0).getDatabase());

        assertEquals(1, results.get(1).getInstructions().size());
        assertInstruction(results.get(1).getInstructions().get(0),
                OperationType.COLUMN_MAPPING, "db1.A.c1", "temp0.c1");
        assertEquals("db1", results.get(1).getDatabase());

        assertTrue(results.get(2).getInstructions().isEmpty());
        assertEquals("db2", results.get(2).getDatabase());

        assertEquals(1, results.get(3).getInstructions().size());
        assertInstruction(results.get(3).getInstructions().get(0),
                OperationType.COLUMN_MAPPING, "db2.A.c2", "temp0.c2");
        assertEquals("db2", results.get(3).getDatabase());
    }

    @Test
    public void setSearchPathSwitchesSchema() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE sch1.A (c1 INT); CREATE TABLE sch2.A (c2 INT);");
        MetadataContext context = MetadataContext.of(catalog);

        List<LineageResult> results = new DmlLineage(context).parseScript(
                "SET search_path TO sch1; SELECT c1 FROM A;");

        // Druid's batch parser merges the SET and the SELECT into one AST; the script splitter
        // must keep them as two statements.
        assertEquals(2, results.size());
        assertTrue(results.get(0).getInstructions().isEmpty());
        assertEquals("sch1", results.get(0).getSchema());
        assertEquals("sch1", results.get(1).getSchema());

        assertEquals(1, results.get(1).getInstructions().size());
        assertInstruction(results.get(1).getInstructions().get(0),
                OperationType.COLUMN_MAPPING, "sch1.A.c1", "temp0.c1");
    }

    @Test
    public void setSearchPathFallsThroughToLaterSchemaForMissingTable() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE sch1.A (c1 INT); CREATE TABLE public.B (c2 INT);");
        MetadataContext context = MetadataContext.of(catalog);

        List<LineageResult> results = new DmlLineage(context).parseScript(
                "SET search_path TO sch1, public; SELECT c2 FROM B;");

        assertEquals(2, results.size());
        assertEquals(Arrays.asList("sch1", "public"), results.get(0).getSchemas());
        assertEquals(Arrays.asList("sch1", "public"), results.get(1).getSchemas());

        // sch1 has no B, so PostgreSQL-style lookup continues to public.
        assertInstruction(results.get(1).getInstructions().get(0),
                OperationType.COLUMN_MAPPING, "public.B.c2", "temp0.c2");
    }

    @Test
    public void setSearchPathStopsAtFirstMatchingTable() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE sch1.A (c1 INT); CREATE TABLE public.A (c2 INT);");
        MetadataContext context = MetadataContext.of(catalog);

        List<LineageResult> results = new DmlLineage(context).parseScript(
                "SET search_path TO sch1, public; SELECT c2 FROM A;");

        assertEquals(2, results.size());
        // The relation A is found in sch1; its missing column is an error, not a fallthrough.
        assertNotNull(results.get(1).getError());
        assertTrue(results.get(1).getError() instanceof ColumnNotFoundException);
        assertTrue(results.get(1).getInstructions().isEmpty());
    }

    @Test
    public void setCurrentSchemaIsSupported() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.db2);
        catalog.loadDdl("CREATE TABLE sch1.A (c1 INT);");
        MetadataContext context = MetadataContext.of(catalog);

        List<LineageResult> results = new DmlLineage(context).parseScript(
                "SET CURRENT SCHEMA = sch1; SELECT c1 FROM A;");

        assertEquals(2, results.size());
        assertTrue(results.get(0).getInstructions().isEmpty());
        assertEquals("sch1", results.get(0).getSchema());
        assertInstruction(results.get(1).getInstructions().get(0),
                OperationType.COLUMN_MAPPING, "sch1.A.c1", "temp0.c1");
    }

    @Test
    public void useWorksWithoutMetadataContext() {
        MetadataContextHolder.clear();

        List<LineageResult> results = new DmlLineage().parseScript(
                "USE db1; SELECT 1 AS c FROM A;");

        assertEquals(2, results.size());
        assertEquals("db1", results.get(0).getDatabase());
        assertTrue(results.get(0).getInstructions().isEmpty());
        assertEquals(0, results.get(0).getMetadataVersion());
        assertNull(results.get(0).getMetadataChecksum());

        assertEquals("db1", results.get(1).getDatabase());
        assertEquals(1, results.get(1).getInstructions().size());
        assertEquals(0, results.get(1).getMetadataVersion());
        assertNull(results.get(1).getMetadataChecksum());
    }

    @Test
    public void scriptResultsShareTheSameMetadataSnapshot() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.loadDdl("CREATE TABLE db1.A (c1 INT);");
        MetadataContext context = MetadataContext.of(catalog);

        List<LineageResult> results = new DmlLineage(context).parseScript(
                "SELECT 1 AS c FROM A; USE db1; SELECT 2 AS c FROM A;");

        assertEquals(3, results.size());
        for (LineageResult result : results) {
            assertNotNull(result.getSql());
            assertEquals(context.getMetadataVersion(), result.getMetadataVersion());
            assertEquals(context.getMetadataChecksum(), result.getMetadataChecksum());
        }
    }

    @Test
    public void originalContextIsNotMutatedByScript() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.loadDdl("CREATE TABLE db1.A (c1 INT); CREATE TABLE db2.A (c2 INT);");
        MetadataContext context = MetadataContext.of(catalog, null, "db1", null);
        DmlLineage lineage = new DmlLineage(context);

        lineage.parseScript("USE db2; SELECT 1 AS c FROM A;");
        assertEquals("db1", context.getDatabase());

        // Reusing the same parser starts again from the caller's original context.
        List<LineageResult> secondRun = lineage.parseScript("SELECT 1 AS c FROM A;");
        assertEquals("db1", secondRun.get(0).getDatabase());
    }

    @Test
    public void quotedSemicolonIsNotTreatedAsStatementSeparator() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.loadDdl("CREATE TABLE db1.A (c1 INT);");
        MetadataContext context = MetadataContext.of(catalog);

        List<LineageResult> results = new DmlLineage(context).parseScript(
                "SELECT 'a;b' AS c FROM A; SELECT c1 FROM A;");

        assertEquals(2, results.size());
        assertNull(results.get(0).getError());
        assertNull(results.get(1).getError());
        assertEquals(1, results.get(0).getInstructions().size());
        assertInstruction(results.get(1).getInstructions().get(0),
                OperationType.COLUMN_MAPPING, "db1.A.c1", "temp0.c1");
    }

    @Test
    public void semicolonInsideCommentIsNotTreatedAsStatementSeparator() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.loadDdl("CREATE TABLE db1.A (c1 INT);");
        MetadataContext context = MetadataContext.of(catalog);

        List<LineageResult> results = new DmlLineage(context).parseScript(
                "-- comment ; still a comment\nSELECT c1 FROM A;");

        assertEquals(1, results.size());
        assertNull(results.get(0).getError());
        assertInstruction(results.get(0).getInstructions().get(0),
                OperationType.COLUMN_MAPPING, "db1.A.c1", "temp0.c1");
    }

    @Test
    public void bindingErrorIsRecordedAndScriptContinues() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.loadDdl("CREATE TABLE db1.A (c1 INT);");
        MetadataContext context = MetadataContext.of(catalog);

        List<LineageResult> results = new DmlLineage(context).parseScript(
                "USE db1; SELECT missing FROM A; SELECT c1 FROM A;");

        assertEquals(3, results.size());
        assertNotNull(results.get(1).getError());
        assertTrue(results.get(1).getError() instanceof ColumnNotFoundException);
        assertEquals("db1", results.get(1).getDatabase());
        assertTrue(results.get(1).getSql().contains("missing"));
        assertTrue(results.get(1).getInstructions().isEmpty());

        // The context survives a failed statement.
        assertEquals("db1", results.get(2).getDatabase());
        assertInstruction(results.get(2).getInstructions().get(0),
                OperationType.COLUMN_MAPPING, "db1.A.c1", "temp0.c1");
    }

    @Test
    public void partialColumnsAreRolledBackOnBindingError() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.loadDdl("CREATE TABLE db1.A (c1 INT);");
        MetadataContext context = MetadataContext.of(catalog);

        List<LineageResult> results = new DmlLineage(context)
                .parseScript("SELECT c1, missing FROM A;");

        assertEquals(1, results.size());
        assertTrue(results.get(0).getError() instanceof ColumnNotFoundException);
        // c1 was resolved before "missing" failed, but the whole statement is discarded.
        assertTrue(results.get(0).getInstructions().isEmpty());
    }

    @Test
    public void syntaxErrorIsLocalizedAndScriptContinues() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.loadDdl("CREATE TABLE db1.A (c1 INT);");
        MetadataContext context = MetadataContext.of(catalog);

        List<LineageResult> results = new DmlLineage(context).parseScript(
                "SELECT c1 FROM A; SELEC bad; SELECT c1 FROM A;");

        assertEquals(3, results.size());
        assertNull(results.get(0).getError());
        assertNotNull(results.get(1).getError());
        assertTrue(results.get(1).getError() instanceof ParserException);
        assertTrue(results.get(1).getSql().contains("SELEC"));
        assertTrue(results.get(1).getInstructions().isEmpty());
        assertNull(results.get(1).getDatabase());
        assertNull(results.get(2).getError());
    }

    @Test
    public void unsupportedStatementIsRecordedAndScriptContinues() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.loadDdl("CREATE TABLE db1.A (c1 INT);");
        MetadataContext context = MetadataContext.of(catalog);

        List<LineageResult> results = new DmlLineage(context).parseScript(
                "GRANT SELECT ON A TO u; SELECT c1 FROM A;");

        assertEquals(2, results.size());
        assertTrue(results.get(0).getError() instanceof UnsupportedOperationException);
        assertTrue(results.get(0).getSql().contains("GRANT"));
        assertTrue(results.get(0).getInstructions().isEmpty());
        assertNull(results.get(0).getDatabase());
        assertNull(results.get(1).getError());
    }

    private static void assertInstruction(Instruction instruction, OperationType operation,
            String from, String to) {
        LineageTestSupport.assertInstruction(instruction, operation, from, to);
    }
}

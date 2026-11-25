package com.laotie;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import org.junit.After;
import org.junit.Test;

import com.alibaba.druid.DbType;
import com.laotie.exception.ColumnNotFoundException;
import com.laotie.exception.MetadataNotFoundException;
import com.laotie.model.Instruction;
import com.laotie.model.Instruction.OperationType;
import com.laotie.model.LineageResult;
import com.laotie.model.metadata.MetadataCatalog;
import com.laotie.model.metadata.MetadataContext;
import com.laotie.model.metadata.MetadataContextHolder;

/**
 * Specification for carrying the metadata snapshot (version + checksum) into lineage results,
 * including the precedence between an explicit context and the ThreadLocal context.
 *
 * <p>
 * A context must hold an immutable metadata snapshot. Mutating the catalog after a context was
 * created must not change how that context resolves columns.
 *
 * <p>
 * Expected to be red until {@code MetadataContext}, {@code MetadataContextHolder},
 * {@code LineageResult} and {@code DmlLineage#parse} exist.
 */
public class LineageMetadataResultTest {

    @After
    public void tearDown() {
        MetadataContextHolder.clear();
    }

    @Test
    public void lineageResultCarriesSnapshotVersionAndChecksum() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.loadDdl("CREATE TABLE db1.A (c1 INT);");
        MetadataContext context = MetadataContext.of(catalog);

        LineageResult result = new DmlLineage(context).parse("select c1 from A");

        assertEquals(1, result.getInstructions().size());
        assertInstruction(result.getInstructions().get(0),
                OperationType.COLUMN_MAPPING, "db1.A.c1", "temp0.c1");
        assertEquals(context.getMetadataVersion(), result.getMetadataVersion());
        assertEquals(context.getMetadataChecksum(), result.getMetadataChecksum());
    }

    @Test
    public void lineageResultKeepsSnapshotAfterCatalogChanges() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.loadDdl("CREATE TABLE db1.A (c1 INT);");
        MetadataContext firstContext = MetadataContext.of(catalog);

        LineageResult first = new DmlLineage(firstContext).parse("select c1 from A");
        assertEquals(1, first.getMetadataVersion());

        // Replace the table definition: c1 disappears, c2 appears.
        catalog.loadDdl("CREATE TABLE db1.A (c2 INT);");
        MetadataContext secondContext = MetadataContext.of(catalog);

        // The old context still sees c1 and no c2.
        assertEquals(1, new DmlLineage(firstContext).parse("select c1 from A")
                .getInstructions().size());
        assertThrows(ColumnNotFoundException.class,
                () -> new DmlLineage(firstContext).parse("select c2 from A"));

        // The new context sees c2 and no c1.
        LineageResult second = new DmlLineage(secondContext).parse("select c2 from A");
        assertEquals(2, second.getMetadataVersion());
        assertThrows(ColumnNotFoundException.class,
                () -> new DmlLineage(secondContext).parse("select c1 from A"));

        assertEquals(firstContext.getMetadataChecksum(), first.getMetadataChecksum());
        assertEquals(secondContext.getMetadataChecksum(), second.getMetadataChecksum());
        assertNotEquals(first.getMetadataChecksum(), second.getMetadataChecksum());
    }

    @Test
    public void threadLocalContextIsUsedWhenNoExplicitContext() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.loadDdl("CREATE TABLE db1.A (c1 INT);");
        MetadataContext context = MetadataContext.of(catalog);

        MetadataContextHolder.runWith(context, () -> {
            LineageResult result = new DmlLineage().parse("select c1 from A");
            assertEquals(1, result.getInstructions().size());
            assertInstruction(result.getInstructions().get(0),
                    OperationType.COLUMN_MAPPING, "db1.A.c1", "temp0.c1");
            assertEquals(context.getMetadataVersion(), result.getMetadataVersion());
            assertEquals(context.getMetadataChecksum(), result.getMetadataChecksum());
            return null;
        });
    }

    @Test
    public void explicitContextOverridesThreadLocalContext() {
        MetadataCatalog threadLocalCatalog = new MetadataCatalog(DbType.mysql);
        threadLocalCatalog.loadDdl("CREATE TABLE db1.A (c1 INT);");
        MetadataContext threadLocalContext = MetadataContext.of(threadLocalCatalog);

        MetadataCatalog explicitCatalog = new MetadataCatalog(DbType.mysql);
        explicitCatalog.loadDdl("CREATE TABLE db2.A (c1 INT);");
        MetadataContext explicitContext = MetadataContext.of(explicitCatalog);

        MetadataContextHolder.runWith(threadLocalContext, () -> {
            LineageResult result = new DmlLineage(explicitContext).parse("select c1 from A");
            assertInstruction(result.getInstructions().get(0),
                    OperationType.COLUMN_MAPPING, "db2.A.c1", "temp0.c1");
            assertEquals(explicitContext.getMetadataVersion(), result.getMetadataVersion());
            assertEquals(explicitContext.getMetadataChecksum(), result.getMetadataChecksum());
            return null;
        });
    }

    @Test
    public void explicitContextDoesNotFallBackToThreadLocalWhenTableMissing() {
        MetadataCatalog threadLocalCatalog = new MetadataCatalog(DbType.mysql);
        threadLocalCatalog.loadDdl("CREATE TABLE db1.A (c1 INT);");
        MetadataContext threadLocalContext = MetadataContext.of(threadLocalCatalog);

        MetadataCatalog explicitCatalog = new MetadataCatalog(DbType.mysql);
        explicitCatalog.loadDdl("CREATE TABLE db2.B (c1 INT);");
        MetadataContext explicitContext = MetadataContext.of(explicitCatalog);

        MetadataContextHolder.runWith(threadLocalContext, () -> {
            assertThrows(MetadataNotFoundException.class,
                    () -> new DmlLineage(explicitContext).parse("select c1 from A"));
            return null;
        });
    }

    @Test
    public void lineageResultWithoutMetadataHasZeroVersionAndNullChecksum() {
        MetadataContextHolder.clear();

        LineageResult result = new DmlLineage().parse("select 1 as c from A");

        assertEquals(1, result.getInstructions().size());
        assertEquals(0, result.getMetadataVersion());
        assertNull(result.getMetadataChecksum());
    }

    private static void assertInstruction(Instruction instruction, OperationType operation,
            String from, String to) {
        assertNotNull("instruction should not be null", instruction);
        assertEquals("operation", operation, instruction.getOperation());
        assertEquals("from", from, instruction.getFrom());
        assertEquals("to", to, instruction.getTo());
    }
}

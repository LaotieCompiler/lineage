package com.laotie;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;

import java.util.List;

import org.junit.After;
import org.junit.Test;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.parser.ParserException;
import com.laotie.model.Instruction;
import com.laotie.model.Instruction.OperationType;
import com.laotie.model.LineageResult;
import com.laotie.model.metadata.MetadataCatalog;
import com.laotie.model.metadata.MetadataContext;
import com.laotie.model.metadata.MetadataContextHolder;

/**
 * Specification for the public {@link DmlLineage} API after metadata support was added.
 *
 * <p>
 * Java cannot overload on return type, so the old AST-only {@code parse(String)} is renamed to
 * {@code parseStatement(String)}, while {@code parse(String)} returns a {@link LineageResult}.
 * The existing {@code getLineage(...)} methods stay for backward compatibility.
 *
 * <p>
 * Resolution rules:
 * <ul>
 *   <li>Metadata context: explicit context &gt; ThreadLocal context &gt; none.</li>
 *   <li>Parsing dialect: explicit context's catalog dialect &gt; explicit {@code DbType}
 *       constructor &gt; ThreadLocal context's catalog dialect &gt; mysql.</li>
 * </ul>
 * In particular, {@code new DmlLineage(DbType.postgresql)} uses PostgreSQL for parsing but still
 * takes metadata from the ThreadLocal context when one is set.
 *
 * <p>
 * Expected to be red until the AST entry point is renamed and {@code parse} returns a result.
 */
public class DmlLineageApiTest {

    @After
    public void tearDown() {
        MetadataContextHolder.clear();
    }

    @Test
    public void parseStatementReturnsTheAst() {
        SQLStatement statement = new DmlLineage().parseStatement("select 1 as c from A");

        assertNotNull(statement);
    }

    @Test
    public void parseReturnsALineageResult() {
        LineageResult result = new DmlLineage().parse("select 1 as c from A");

        assertNotNull(result);
        assertEquals(1, result.getInstructions().size());
        assertEquals(0, result.getMetadataVersion());
    }

    @Test
    public void explicitDbTypeGovernsParsingWhileThreadLocalSuppliesMetadata() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.loadDdl("CREATE TABLE db1.A (c1 INT);");
        MetadataContext context = MetadataContext.of(catalog);

        MetadataContextHolder.runWith(context, () -> {
            // ::int is PostgreSQL-only syntax; MySQL rejects it.
            LineageResult result = new DmlLineage(DbType.postgresql)
                    .parse("SELECT c1::int FROM A");
            assertInstruction(result.getInstructions().get(0),
                    OperationType.COLUMN_MAPPING, "db1.A.c1", "temp0.c1");
            return null;
        });
    }

    @Test
    public void explicitMysqlDbTypeRejectsPostgresSyntax() {
        assertThrows(ParserException.class,
                () -> new DmlLineage(DbType.mysql).parseStatement("SELECT c1::int FROM A"));
    }

    @Test
    public void threadLocalDialectIsUsedWhenNoExplicitDbType() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE sch1.A (c1 INT);");
        MetadataContext context = MetadataContext.of(catalog);

        MetadataContextHolder.runWith(context, () -> {
            List<LineageResult> results = new DmlLineage()
                    .parseScript("SET search_path TO sch1; SELECT c1 FROM A;");
            assertEquals(2, results.size());
            assertEquals("sch1", results.get(1).getSchema());
            return null;
        });
    }

    private static void assertInstruction(Instruction instruction, OperationType operation,
            String from, String to) {
        assertNotNull("instruction should not be null", instruction);
        assertEquals("operation", operation, instruction.getOperation());
        assertEquals("from", from, instruction.getFrom());
        assertEquals("to", to, instruction.getTo());
    }
}

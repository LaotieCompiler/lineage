package com.laotie;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.laotie.model.Instruction;
import com.laotie.model.Instruction.OperationType;
import com.laotie.model.LineageResult;
import com.laotie.model.metadata.MetadataContext;

/**
 * Acceptance for metadata-driven wildcard expansion, replacing the old MetaBinder path.
 *
 * <p>
 * Expected to be red until {@code MetadataContext},
 * {@code Instruction#toColumnInstructions(List, MetadataContext)} and
 * {@code DmlLineage#parse(String)} exist.
 */
public class LineageWithLineageTest {

    @Test
    public void importMetadataTest() {
        MetadataContext context = mysqlContext(
                "CREATE TABLE db1.TA (col1 INT, col2 INT);"
                        + "CREATE TABLE db2.TB (cola INT, colb INT, colc INT);");

        LineageResult result = new DmlLineage(context)
                .parse("INSERT INTO TB(colb,cola) SELECT * FROM TA;");

        List<Instruction> expanded = Instruction.toColumnInstructions(
                result.getInstructions(), context);

        assertEquals(4, expanded.size());
        assertInstruction(expanded.get(0), OperationType.COLUMN_MAPPING,
                "db1.TA.col1", "temp1.col1");
        assertInstruction(expanded.get(1), OperationType.COLUMN_MAPPING,
                "db1.TA.col2", "temp1.col2");
        assertInstruction(expanded.get(2), OperationType.COLUMN_MAPPING,
                "temp1.col1", "db2.TB.colb");
        assertInstruction(expanded.get(3), OperationType.COLUMN_MAPPING,
                "temp1.col2", "db2.TB.cola");
    }

    @Test
    public void secendTest() {
        MetadataContext context = mysqlContext(
                "CREATE TABLE db1.TA (col1 INT, col2 INT);"
                        + "CREATE TABLE db2.TB (cola INT, colb INT, colc INT);");

        LineageResult result = new DmlLineage(context)
                .parse("INSERT INTO TB SELECT *, now() as col3 FROM TA tt;");

        List<Instruction> expanded = Instruction.toColumnInstructions(
                result.getInstructions(), context);

        // TABLE_MAPPING alias entries are preserved but are not column lineage.
        int columnMappings = 0;
        for (Instruction instruction : expanded) {
            if (instruction.getOperation() != OperationType.TABLE_MAPPING) {
                columnMappings++;
            }
        }
        assertEquals(6, columnMappings);

        assertContains(expanded, OperationType.COLUMN_MAPPING, "db1.TA.col1", "temp1.col1");
        assertContains(expanded, OperationType.COLUMN_MAPPING, "db1.TA.col2", "temp1.col2");
        assertContains(expanded, OperationType.EXPR_MAPPING, "now()", "temp1.col3");
        assertContains(expanded, OperationType.COLUMN_MAPPING, "temp1.col1", "db2.TB.cola");
        assertContains(expanded, OperationType.COLUMN_MAPPING, "temp1.col2", "db2.TB.colb");
        assertContains(expanded, OperationType.COLUMN_MAPPING, "temp1.col3", "db2.TB.colc");
    }

    private static MetadataContext mysqlContext(String ddl) {
        return LineageTestSupport.mysql(ddl);
    }

    private static void assertInstruction(Instruction instruction, OperationType operation,
            String from, String to) {
        LineageTestSupport.assertInstruction(instruction, operation, from, to);
    }

    private static void assertContains(List<Instruction> instructions, OperationType operation,
            String from, String to) {
        for (Instruction instruction : instructions) {
            if (instruction.getOperation() == operation
                    && from.equals(instruction.getFrom())
                    && to.equals(instruction.getTo())) {
                return;
            }
        }
        assertTrue("missing instruction: " + from + " -> " + to, false);
    }
}

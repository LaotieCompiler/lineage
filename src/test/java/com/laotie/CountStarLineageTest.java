package com.laotie;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.laotie.model.Instruction;
import com.laotie.model.ScanResult;
import com.laotie.model.metadata.MetadataContext;

/**
 * {@code COUNT(*)} counts rows; it is not a {@code SELECT *} projection. Treating it
 * as a wildcard used to emit every source column and to make the projection's output
 * positions ambiguous.
 */
public class CountStarLineageTest {

    private static List<Instruction> instructions(String sql) {
        MetadataContext context = LineageTestSupport.mysql(
                "CREATE TABLE t (a INT, b INT)");
        ScanResult result = new LineageScanner(context).scan(sql);
        assertEquals(0, result.getFailures().size());
        return result.getSuccesses().get(0).getInstructions();
    }

    @Test
    public void countStarDoesNotProjectEveryColumn() {
        List<Instruction> instructions = instructions("SELECT COUNT(*) AS c FROM t");
        for (Instruction instruction : instructions) {
            assertNotEquals(Instruction.OperationType.COLUMNS_INJECT,
                    instruction.getOperation());
            assertFalse(instruction.getFrom() != null
                    && (instruction.getFrom().endsWith(".a")
                            || instruction.getFrom().endsWith(".b")));
        }
        assertTrue(instructions.stream().anyMatch(
                instruction -> instruction.getTo() != null
                        && instruction.getTo().endsWith(".c")));
    }

    @Test
    public void groupByKeepsRealColumnsOnly() {
        List<Instruction> instructions = instructions(
                "SELECT a, COUNT(*) AS c FROM t GROUP BY a");
        assertTrue(instructions.stream().anyMatch(
                instruction -> "t.a".equals(instruction.getFrom())));
        assertFalse(instructions.stream().anyMatch(
                instruction -> instruction.getFrom() != null
                        && instruction.getFrom().endsWith(".b")));
        assertTrue(instructions.stream().anyMatch(
                instruction -> instruction.getTo() != null
                        && instruction.getTo().endsWith(".c")));
    }
}

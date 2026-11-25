package com.laotie;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import com.laotie.model.Instruction;
import com.laotie.model.Instruction.OperationType;
import com.laotie.model.LineageGraph;
import com.laotie.model.MetaBinder;
import com.laotie.model.metadata.Column;
import com.laotie.model.metadata.Table;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;

public class LineageWithLineageTest {

    private static void assertInstruction(Instruction instruction, OperationType operation,
            String from, String to) {
        assertNotNull("instruction should not be null", instruction);
        assertEquals("operation", operation, instruction.getOperation());
        assertEquals("from", from, instruction.getFrom());
        assertEquals("to", to, instruction.getTo());
    }

    @Test
    public void importMetadataTest() {
        MetaBinder metaBinder = new MetaBinder();

        metaBinder.setColumns(new Table("TA"), Arrays.asList(new Column("col1"), new Column("col2")));

        String sqlStr = "INSERT INTO TB(colb,cola)  SELECT * FROM TA;";

        SQLStatement stat = SQLUtils.parseSingleStatement(sqlStr, DbType.mysql);
        DmlLineage selectLineage = new DmlLineage();
        List<Instruction> instructions = selectLineage.getLineage(stat);
        System.out.println("instructions:");
        for (Instruction instruct : instructions) {
            System.out.println("  " + instruct);
        }
        assertEquals(2, instructions.size());
        assertInstruction(instructions.get(0), OperationType.COLUMNS_INJECT, "TA", "temp1");
        assertInstruction(instructions.get(1), OperationType.COLUMNS_INJECT, "temp1", "TB(colb,cola)");

        List<Instruction> colInstructions = Instruction.toColumnInstructions(instructions, metaBinder);

        System.out.println("column level instructions:");
        for (Instruction instruction : colInstructions) {
            System.out.println("  " + instruction);
        }
        assertEquals(4, colInstructions.size());
        assertInstruction(colInstructions.get(0), OperationType.COLUMN_MAPPING, "TA.col1", "temp1.col1");
        assertInstruction(colInstructions.get(1), OperationType.COLUMN_MAPPING, "TA.col2", "temp1.col2");
        assertInstruction(colInstructions.get(2), OperationType.COLUMN_MAPPING, "temp1.col1", "TB.colb");
        assertInstruction(colInstructions.get(3), OperationType.COLUMN_MAPPING, "temp1.col2", "TB.cola");
    }

    @Test
    public void secendTest() {
        MetaBinder metaBinder = new MetaBinder();

        metaBinder.setColumns(new Table("TA"), Arrays.asList(new Column("col1"), new Column("col2")));
        metaBinder.setColumns(new Table("TB"), Arrays.asList(new Column("cola"), new Column("colb"), new Column("colc")));

        String sqlStr = "INSERT INTO TB  SELECT *, now() as col3 FROM TA tt;";

        SQLStatement stat = SQLUtils.parseSingleStatement(sqlStr, DbType.mysql);
        DmlLineage selectLineage = new DmlLineage();
        List<Instruction> instructions = selectLineage.getLineage(stat);
        System.out.println("instructions:");
        for (Instruction instruct : instructions) {
            System.out.println("  " + instruct);
        }
        assertEquals(4, instructions.size());
        assertInstruction(instructions.get(0), OperationType.TABLE_MAPPING, "TA", "tt");
        assertInstruction(instructions.get(1), OperationType.COLUMNS_INJECT, "tt", "temp1");
        assertInstruction(instructions.get(2), OperationType.EXPR_MAPPING, "now()", "temp1.col3");
        assertInstruction(instructions.get(3), OperationType.COLUMNS_INJECT, "temp1", "TB");

        List<Instruction> colInstructions = Instruction.toColumnInstructions(instructions, metaBinder);

        System.out.println("column level instructions:");
        for (Instruction instruction : colInstructions) {
            System.out.println("  " + instruction);
        }
        assertEquals(7, colInstructions.size());
        assertInstruction(colInstructions.get(0), OperationType.TABLE_MAPPING, "TA", "tt");
        assertInstruction(colInstructions.get(1), OperationType.COLUMN_MAPPING, "tt.col1", "temp1.col1");
        assertInstruction(colInstructions.get(2), OperationType.COLUMN_MAPPING, "tt.col2", "temp1.col2");
        assertInstruction(colInstructions.get(3), OperationType.EXPR_MAPPING, "now()", "temp1.col3");
        assertInstruction(colInstructions.get(4), OperationType.COLUMN_MAPPING, "temp1.col1", "TB.cola");
        assertInstruction(colInstructions.get(5), OperationType.COLUMN_MAPPING, "temp1.col2", "TB.colb");
        assertInstruction(colInstructions.get(6), OperationType.COLUMN_MAPPING, "temp1.col3", "TB.colc");
    }
}

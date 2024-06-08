package com.laotie;

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

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;

public class LineageWithLineageTest {

    @Test
    public void importMetadataTest() throws JSQLParserException {
        MetaBinder metaBinder = new MetaBinder();

        metaBinder.setColumns(new Table("TA"), Arrays.asList(new Column("col1"), new Column("col2")));

        String sqlStr = "INSERT INTO TB(colb,cola)  SELECT * FROM TA;";

        Statement stat = (Statement) CCJSqlParserUtil.parse(sqlStr);
        DmlLineage selectLineage = new DmlLineage();
        List<Instruction> instructions = selectLineage.getLineage((Statement) stat);
        System.out.println("instructions:");
        for (Instruction instruct : instructions) {
            System.out.println("  " + instruct);
        }

        List<Instruction> colInstructions = Instruction.toColumnInstructions(instructions, metaBinder);

        System.out.println("column level instructions:");
        for (Instruction instruction : colInstructions) {
            System.out.println("  " + instruction);
        }
    }

    @Test
    public void secendTest() throws JSQLParserException {
        MetaBinder metaBinder = new MetaBinder();

        metaBinder.setColumns(new Table("TA"), Arrays.asList(new Column("col1"), new Column("col2")));
        metaBinder.setColumns(new Table("TB"), Arrays.asList(new Column("cola"), new Column("colb"), new Column("colc")));

        String sqlStr = "INSERT INTO TB  SELECT *, now() as col3 FROM TA tt;";

        Statement stat = (Statement) CCJSqlParserUtil.parse(sqlStr);
        DmlLineage selectLineage = new DmlLineage();
        List<Instruction> instructions = selectLineage.getLineage((Statement) stat);
        System.out.println("instructions:");
        for (Instruction instruct : instructions) {
            System.out.println("  " + instruct);
        }

        List<Instruction> colInstructions = Instruction.toColumnInstructions(instructions, metaBinder);

        System.out.println("column level instructions:");
        for (Instruction instruction : colInstructions) {
            System.out.println("  " + instruction);
        }
    }
}

package com.laotie;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.laotie.model.Instruction;
import com.laotie.model.Instruction.OperationType;
import com.laotie.model.LineageGraph;
import com.laotie.model.MetaBinder;
import com.laotie.model.metadata.Column;

/**
 * Unit test for simple App.
 */
public class SelectLineageTest {

    private static void assertInstruction(Instruction instruction, OperationType operation,
            String from, String to) {
        assertNotNull("instruction should not be null", instruction);
        assertEquals("operation", operation, instruction.getOperation());
        assertEquals("from", from, instruction.getFrom());
        assertEquals("to", to, instruction.getTo());
    }

    private static Set<Column> columns(String... names) {
        Set<Column> columns = new HashSet<>();
        for (String name : names) {
            columns.add(new Column(name));
        }
        return columns;
    }
    /**
     * Rigorous Test :-)
     */
    @Test
    public void allColumnTest() {
        String sqlStr = "SELECT TA.* FROM DB.A AS TA;";

        SQLStatement statHandle = SQLUtils.parseSingleStatement(sqlStr, DbType.mysql);
        assertTrue(statHandle instanceof SQLSelectStatement);

        DmlLineage selectLineage = new DmlLineage();
        List<Instruction> instructions = selectLineage.getLineage(statHandle);
        System.out.println("instructions:");
        for (Instruction instruct : instructions) {
            System.out.println(instruct);
        }

        assertEquals(2, instructions.size());
        assertInstruction(instructions.get(0), OperationType.TABLE_MAPPING, "DB.A", "TA");
        assertInstruction(instructions.get(1), OperationType.COLUMNS_INJECT, "TA", "temp0");
        System.out.println("well done.");
    }

    @Test
    public void joinTest() {
        String sqlStr = "SELECT TB.id as bid, TC.id as cid, id as aid\n" + //
                "FROM TA\n" + //
                "    Left Join (\n" + //
                "        select id, ba1 b1, ba2 b2\n" + //
                "        from TBA\n" + //
                "    ) TB ON TA.id = TB.id\n" + //
                "    Right Join TC ON TB.id = TC.id\n" + //
                "WHERE\n" + //
                "    condition;";

        SQLStatement statHandle = SQLUtils.parseSingleStatement(sqlStr, DbType.mysql);
        assertTrue(statHandle instanceof SQLSelectStatement);

        DmlLineage selectLineage = new DmlLineage();
        List<Instruction> instructions = selectLineage.getLineage(statHandle);
        System.out.println("instructions:");
        for (Instruction instruct : instructions) {
            System.out.println(instruct);
        }

        assertEquals(6, instructions.size());
        assertInstruction(instructions.get(0), OperationType.COLUMN_MAPPING, "TBA.id", "TB.id");
        assertInstruction(instructions.get(1), OperationType.COLUMN_MAPPING, "TBA.ba1", "TB.b1");
        assertInstruction(instructions.get(2), OperationType.COLUMN_MAPPING, "TBA.ba2", "TB.b2");
        assertInstruction(instructions.get(3), OperationType.COLUMN_MAPPING, "TB.id", "temp0.bid");
        assertInstruction(instructions.get(4), OperationType.COLUMN_MAPPING, "TC.id", "temp0.cid");
        assertInstruction(instructions.get(5), OperationType.COLUMN_MAPPING, "TA.id", "temp0.aid");
        System.out.println("well done.");
    }

    @Test
    public void subqueryTest() {
        String sqlStr = "SELECT max(Col1) as Mcol1, Col2, Col3, 1 as Col4\n" + //
                "FROM (\n" + //
                "        SELECT A1 Col1, A2 Col2, A3 Col3\n" + //
                "        FROM (\n" + //
                "                SELECT B1 + B2 as A1, B1 + B2 * B3 as A2, B3 A3\n" + //
                "                FROM (\n" + //
                "                        SELECT 1 B1, 2 B2, 3 B3\n" + //
                "                    )\n" + //
                "            )\n" + //
                "    ) TA \n" + //
                "WHERE\n" + //
                "    condition;";

        SQLStatement statHandle = SQLUtils.parseSingleStatement(sqlStr, DbType.mysql);
        assertTrue(statHandle instanceof SQLSelectStatement);

        DmlLineage selectLineage = new DmlLineage();
        List<Instruction> instructions = selectLineage.getLineage(statHandle);
        System.out.println("instructions:");
        for (Instruction instruct : instructions) {
            System.out.println(instruct);
        }

        assertEquals(13, instructions.size());
        assertInstruction(instructions.get(0), OperationType.COLUMN_MAPPING, "temp2.B1", "temp1.A1");
        assertInstruction(instructions.get(1), OperationType.COLUMN_MAPPING, "temp2.B2", "temp1.A1");
        assertInstruction(instructions.get(2), OperationType.COLUMN_MAPPING, "temp2.B1", "temp1.A2");
        assertInstruction(instructions.get(3), OperationType.COLUMN_MAPPING, "temp2.B2", "temp1.A2");
        assertInstruction(instructions.get(4), OperationType.COLUMN_MAPPING, "temp2.B3", "temp1.A2");
        assertInstruction(instructions.get(5), OperationType.COLUMN_MAPPING, "temp2.B3", "temp1.A3");
        assertInstruction(instructions.get(6), OperationType.COLUMN_MAPPING, "temp1.A1", "TA.Col1");
        assertInstruction(instructions.get(7), OperationType.COLUMN_MAPPING, "temp1.A2", "TA.Col2");
        assertInstruction(instructions.get(8), OperationType.COLUMN_MAPPING, "temp1.A3", "TA.Col3");
        assertInstruction(instructions.get(9), OperationType.COLUMN_MAPPING, "TA.Col1", "temp0.Mcol1");
        assertInstruction(instructions.get(10), OperationType.COLUMN_MAPPING, "TA.Col2", "temp0.Col2");
        assertInstruction(instructions.get(11), OperationType.COLUMN_MAPPING, "TA.Col3", "temp0.Col3");
        assertInstruction(instructions.get(12), OperationType.EXPR_MAPPING, "1", "temp0.Col4");

        LineageGraph lineageGraph = new LineageGraph();
        lineageGraph.buildByInstructions(instructions);
        Set<Column> sources = lineageGraph.getSources(new Column("temp0.Mcol1"));
        System.out.println("sources:");
        for (Column source : sources) {
            System.out.println(source);
        }
        assertEquals(columns("temp2.B1", "temp2.B2"), sources);
        System.out.println("well done.");
    }

    @Test
    public void batchSourceText() {
        String sqlStr = "SELECT C + D as A, D as B\n" + //
                "From (\n" + //
                "    SELECT E as C, F as D\n" + //
                "    From T\n" + //
                ")";

        DmlLineage selectLineage = new DmlLineage();
        SQLStatement statHandle = SQLUtils.parseSingleStatement(sqlStr, DbType.mysql);

        List<Instruction> instructions = selectLineage.getLineage(statHandle);
        System.out.println("instructions:");
        for (Instruction instruct : instructions) {
            System.out.println(instruct);
        }
        assertEquals(5, instructions.size());
        assertInstruction(instructions.get(0), OperationType.COLUMN_MAPPING, "T.E", "temp1.C");
        assertInstruction(instructions.get(1), OperationType.COLUMN_MAPPING, "T.F", "temp1.D");
        assertInstruction(instructions.get(2), OperationType.COLUMN_MAPPING, "temp1.C", "temp0.A");
        assertInstruction(instructions.get(3), OperationType.COLUMN_MAPPING, "temp1.D", "temp0.A");
        assertInstruction(instructions.get(4), OperationType.COLUMN_MAPPING, "temp1.D", "temp0.B");

        LineageGraph lineageGraph = new LineageGraph();
        lineageGraph.buildByInstructions(instructions);
        Set<Column> targets = new HashSet<>();
        targets.add(new Column("temp0.A"));
        targets.add(new Column("temp0.B"));
        Map<Column, Set<Column>> shorttenLineage = lineageGraph.getSources(targets);
        for (Column target : shorttenLineage.keySet()) {
            System.out.println("target: " + target);
            for (Column source : shorttenLineage.get(target)) {
                System.out.println("  " + source);
            }
        }
        assertEquals(columns("T.E", "T.F"), shorttenLineage.get(new Column("temp0.A")));
        assertEquals(columns("T.F"), shorttenLineage.get(new Column("temp0.B")));
        System.out.println("well done.\n");

        sqlStr = "SELECT C + D as A, D as B, C + TB.C1 as A1, TB.D1 as B1\n" + //
                "From (\n" + //
                "    SELECT E as C, F as D\n" + //
                "    From T\n" + //
                ") as TA \n" + //
                "INNER JOIN TB on TA.aid = TB.bid";
        statHandle = SQLUtils.parseSingleStatement(sqlStr, DbType.mysql);
        instructions = selectLineage.getLineage(statHandle);
        System.out.println("instructions:");
        for (Instruction instruct : instructions) {
            System.out.println(instruct);
        }
        assertEquals(8, instructions.size());
        assertInstruction(instructions.get(0), OperationType.COLUMN_MAPPING, "T.E", "TA.C");
        assertInstruction(instructions.get(1), OperationType.COLUMN_MAPPING, "T.F", "TA.D");
        assertInstruction(instructions.get(2), OperationType.COLUMN_MAPPING, "TA.C", "temp2.A");
        assertInstruction(instructions.get(3), OperationType.COLUMN_MAPPING, "TA.D", "temp2.A");
        assertInstruction(instructions.get(4), OperationType.COLUMN_MAPPING, "TA.D", "temp2.B");
        assertInstruction(instructions.get(5), OperationType.COLUMN_MAPPING, "TA.C", "temp2.A1");
        assertInstruction(instructions.get(6), OperationType.COLUMN_MAPPING, "TB.C1", "temp2.A1");
        assertInstruction(instructions.get(7), OperationType.COLUMN_MAPPING, "TB.D1", "temp2.B1");

        lineageGraph.buildByInstructions(instructions);
        targets = new HashSet<>();
        targets.add(new Column("temp2.A"));
        targets.add(new Column("temp2.B"));
        targets.add(new Column("temp2.A1"));
        targets.add(new Column("temp2.B1"));
        shorttenLineage = lineageGraph.getSources(targets);
        for (Column target : shorttenLineage.keySet()) {
            System.out.println("target: " + target);
            for (Column source : shorttenLineage.get(target)) {
                System.out.println("  " + source);
            }
        }
        assertEquals(columns("T.E", "T.F"), shorttenLineage.get(new Column("temp2.A")));
        assertEquals(columns("T.F"), shorttenLineage.get(new Column("temp2.B")));
        assertEquals(columns("TB.C1", "T.E"), shorttenLineage.get(new Column("temp2.A1")));
        assertEquals(columns("TB.D1"), shorttenLineage.get(new Column("temp2.B1")));

        System.out.println("well done.\n");
    }

    @Test
    public void insertTest() {
        long startMillis = System.currentTimeMillis();
        long round = 1;
        for (int i = 0; i < round; i++) {
            String sqlStr = "INSERT INTO\n" + //
                    "    TTT (col1, col2, col3, col4, col5)\n" + //
                    // " TTT\n" + //
                    "SELECT C + D as A, D as B, C + TB.C1 as A1, D, C*D \n" + //
                    "From (\n" + //
                    "        SELECT E as C, F as D\n" + //
                    "        From T\n" + //
                    "    ) as TA\n" + //
                    "    INNER JOIN TB on TA.aid = TB.bid";
            SQLStatement stat = SQLUtils.parseSingleStatement(sqlStr, DbType.mysql);
            DmlLineage selectLineage = new DmlLineage();
            // get instructions
            List<Instruction> instructions = selectLineage.getLineage(stat);
            System.out.println("instructions:");
            for (Instruction instruct : instructions) {
                System.out.println("  " + instruct);
            }
            assertEquals(11, instructions.size());
            assertInstruction(instructions.get(0), OperationType.COLUMN_MAPPING, "T.E", "TA.C");
            assertInstruction(instructions.get(1), OperationType.COLUMN_MAPPING, "T.F", "TA.D");
            assertInstruction(instructions.get(2), OperationType.COLUMN_MAPPING, "TA.C", "temp1.A");
            assertInstruction(instructions.get(3), OperationType.COLUMN_MAPPING, "TA.D", "temp1.A");
            assertInstruction(instructions.get(4), OperationType.COLUMN_MAPPING, "TA.D", "temp1.B");
            assertInstruction(instructions.get(5), OperationType.COLUMN_MAPPING, "TA.C", "temp1.A1");
            assertInstruction(instructions.get(6), OperationType.COLUMN_MAPPING, "TB.C1", "temp1.A1");
            assertInstruction(instructions.get(7), OperationType.COLUMN_MAPPING, "TA.D", "temp1.D");
            assertInstruction(instructions.get(8), OperationType.COLUMN_MAPPING, "TA.C", "temp1.C * D");
            assertInstruction(instructions.get(9), OperationType.COLUMN_MAPPING, "TA.D", "temp1.C * D");
            assertInstruction(instructions.get(10), OperationType.COLUMNS_INJECT, "temp1",
                    "TTT(col1,col2,col3,col4,col5)");

            // get related tables
            Set<String> tables = selectLineage.getTables();
            System.out.println("tables: ");
            System.out.println("  " + tables);
            assertEquals(new HashSet<>(Arrays.asList("T", "TTT", "TB")), tables);

            // parse column instructions by metadata
            MetaBinder metaBinder = new MetaBinder();
            List<Instruction> colInstructions = Instruction.toColumnInstructions(instructions, metaBinder);
            System.out.println("column instructions:");
            for (Instruction instruct : colInstructions) {
                System.out.println("  " + instruct);
            }
            assertEquals(15, colInstructions.size());
            assertInstruction(colInstructions.get(2), OperationType.COLUMN_MAPPING, "TA.C", "temp1.A");
            assertInstruction(colInstructions.get(10), OperationType.COLUMN_MAPPING, "temp1.A", "TTT.col1");
            assertInstruction(colInstructions.get(11), OperationType.COLUMN_MAPPING, "temp1.B", "TTT.col2");
            assertInstruction(colInstructions.get(12), OperationType.COLUMN_MAPPING, "temp1.A1", "TTT.col3");
            assertInstruction(colInstructions.get(13), OperationType.COLUMN_MAPPING, "temp1.D", "TTT.col4");
            assertInstruction(colInstructions.get(14), OperationType.COLUMN_MAPPING, "temp1.C * D", "TTT.col5");

            // build lineage graph
            LineageGraph lineageGraph = new LineageGraph();
            lineageGraph.buildByInstructions(colInstructions);

            // get shortten lineage (ignore the middle temporary table, just from source to target directly)
            Set<Column> targets = new HashSet<>();
            targets.add(new Column("TTT.col1"));
            targets.add(new Column("TTT.col2"));
            targets.add(new Column("TTT.col3"));
            targets.add(new Column("TTT.col4"));
            targets.add(new Column("TTT.col5"));
            Map<Column, Set<Column>> sources = lineageGraph.getSources(targets);
            System.out.println("Shortten lineage:");
            for (Column target : sources.keySet()) {
                System.out.println("  target: " + target);
                for (Column source : sources.get(target)) {
                    System.out.println("    " + source);
                }
            }
            assertEquals(columns("T.E", "T.F"), sources.get(new Column("TTT.col1")));
            assertEquals(columns("T.F"), sources.get(new Column("TTT.col2")));
            assertEquals(columns("TB.C1", "T.E"), sources.get(new Column("TTT.col3")));
            assertEquals(columns("T.F"), sources.get(new Column("TTT.col4")));
            assertEquals(columns("T.E", "T.F"), sources.get(new Column("TTT.col5")));
        }
        long endMillis = System.currentTimeMillis();
        System.out.println(String.format("Duration [ms]: %d, Avg [ms]: %.2f", (endMillis - startMillis), (float) (endMillis - startMillis) / round));
    }

}

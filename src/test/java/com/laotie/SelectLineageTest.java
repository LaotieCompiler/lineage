package com.laotie;

import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import com.laotie.model.Instruction;
import com.laotie.model.LineageGraph;
import com.laotie.model.metadata.Column;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.Statement;

/**
 * Unit test for simple App.
 */
public class SelectLineageTest {
    /**
     * Rigorous Test :-)
     */
    @Test
    public void allColumnTest() {
        String sqlStr = "SELECT TA.* FROM DB.A AS TA;";

        Statement statHandle;
        Select select;
        try {
            statHandle = (Statement) CCJSqlParserUtil.parse(sqlStr);
            if (statHandle instanceof Select) {
                select = (Select) statHandle;

                DmlLineage selectLineage = new DmlLineage();
                List<Instruction> instructions = selectLineage.getLineage((Statement) select);
                System.out.println("instructions:");
                for (Instruction instruct : instructions) {
                    System.out.println(instruct);
                }
            }
            System.out.println("well done.");
        } catch (JSQLParserException e) {
            e.printStackTrace();
        }
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

        Statement statHandle;
        Select select;
        try {
            statHandle = (Statement) CCJSqlParserUtil.parse(sqlStr);
            if (statHandle instanceof Select) {
                select = (Select) statHandle;

                DmlLineage selectLineage = new DmlLineage();
                List<Instruction> instructions = selectLineage.getLineage((Statement) select);
                System.out.println("instructions:");
                for (Instruction instruct : instructions) {
                    System.out.println(instruct);
                }
            }
            System.out.println("well done.");
        } catch (JSQLParserException e) {
            e.printStackTrace();
        }
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

        Statement statHandle;
        Select select;
        try {
            statHandle = (Statement) CCJSqlParserUtil.parse(sqlStr);
            if (statHandle instanceof Select) {
                select = (Select) statHandle;

                DmlLineage selectLineage = new DmlLineage();
                List<Instruction> instructions = selectLineage.getLineage((Statement) select);
                System.out.println("instructions:");
                for (Instruction instruct : instructions) {
                    System.out.println(instruct);
                }

                LineageGraph lineageGraph = new LineageGraph();
                lineageGraph.buildByInstructions(instructions);
                Set<Column> sources = lineageGraph.getSources(new Column("temp0.Mcol1"));
                System.out.println("sources:");
                for (Column source : sources) {
                    System.out.println(source);
                }
            }
            System.out.println("well done.");
        } catch (JSQLParserException e) {
            e.printStackTrace();
        }
    }

    @Test
    public void batchSourceText() {
        String sqlStr = "SELECT C + D as A, D as B\n" + //
                "From (\n" + //
                "    SELECT E as C, F as D\n" + //
                "    From T\n" + //
                ")";

        DmlLineage selectLineage = new DmlLineage();
        try {
            Statement statHandle = (Statement) CCJSqlParserUtil.parse(sqlStr);

            List<Instruction> instructions = selectLineage.getLineage((Statement) statHandle);
            System.out.println("instructions:");
            for (Instruction instruct : instructions) {
                System.out.println(instruct);
            }

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
            assertTrue(shorttenLineage.containsKey(new Column("temp0.A")));
            assertTrue(shorttenLineage.get(new Column("temp0.A")).contains(new Column("T.E")));
            assertTrue(shorttenLineage.get(new Column("temp0.A")).contains(new Column("T.F")));

            assertTrue(shorttenLineage.containsKey(new Column("temp0.B")));
            assertTrue(shorttenLineage.get(new Column("temp0.B")).contains(new Column("T.F")));
            System.out.println("well done.\n\n");

            sqlStr = "SELECT C + D as A, D as B, C + TB.C1 as A1, TB.D1 as B1\n" + //
                                "From (\n" + //
                                "    SELECT E as C, F as D\n" + //
                                "    From T\n" + //
                                ") as TA \n" + //
                                "INNER JOIN TB on TA.aid = TB.bid";
            statHandle = (Statement) CCJSqlParserUtil.parse(sqlStr);
            instructions = selectLineage.getLineage((Statement) statHandle);
            System.out.println("instructions:");
            for (Instruction instruct : instructions) {
                System.out.println(instruct);
            }

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
            assertTrue(shorttenLineage.containsKey(new Column("temp2.A1")));
            assertTrue(shorttenLineage.get(new Column("temp2.A1")).contains(new Column("TB.C1")));
            assertTrue(shorttenLineage.get(new Column("temp2.A1")).contains(new Column("T.E")));

            assertTrue(shorttenLineage.containsKey(new Column("temp2.B1")));
            assertTrue(shorttenLineage.get(new Column("temp2.B1")).contains(new Column("TB.D1")));

            System.out.println("well done.\n\n");
        } catch ( JSQLParserException e) {
            e.printStackTrace();
        }
    }

    @Test
    public void insertTest() throws JSQLParserException {
        String sqlStr = "INSERT INTO\n" + //
                        "    TTT (col1, col2, col3, col4)\n" + //
                        // "    TTT\n" + //
                        "SELECT C + D as A, D as B, C + TB.C1 as A1, TB.D1 as B1\n" + //
                        "From (\n" + //
                        "        SELECT E as C, F as D\n" + //
                        "        From T\n" + //
                        "    ) as TA\n" + //
                        "    INNER JOIN TB on TA.aid = TB.bid";
        Statement stat = (Statement) CCJSqlParserUtil.parse(sqlStr);
        DmlLineage selectLineage = new DmlLineage();
        List<Instruction> instructions = selectLineage.getLineage((Statement) stat);
        for(Instruction instruct : instructions) {
            System.out.println(instruct);
        }
        // LineageGraph lineageGraph = new LineageGraph();
        // lineageGraph.buildByInstructions(instructions);
        // Set<Column> targets = new HashSet<>();
        // targets.add(new Column("TTT.col1"));
        // targets.add(new Column("TTT.col2"));
        // targets.add(new Column("TTT.col3"));
        // targets.add(new Column("TTT.col4"));
        // Map<Column,Set<Column>> sources = lineageGraph.getSources(targets);
        // for (Column target : sources.keySet()) {
        //     System.out.println("target: " + target);
        //     for (Column source : sources.get(target)) {
        //         System.out.println("  " + source);
        //     }
        // }
    }

}

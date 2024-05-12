package com.laotie;

import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.Statement;

/**
 * Unit test for simple App.
 */
public class SelectLineageTest 
{
    /**
     * Rigorous Test :-)
     */
    @Test
    public void allColumnTest()
    {
        String sqlStr = "SELECT TA.* FROM A AS TA;";

        Statement statHandle;
        Select select;
        try {
            statHandle = (Statement) CCJSqlParserUtil.parse(sqlStr);
            if (statHandle instanceof Select) {
                select = (Select) statHandle;

                SelectLineage selectLineage = new SelectLineage();
                List<String> instructions = selectLineage.getLineage((Statement) select);
                System.out.println("instructions:");
                for (String instruct : instructions) {
                    System.out.println(instruct);
                }
            }
            System.out.println("well done.");
        } catch (JSQLParserException e) {
            e.printStackTrace();
        }
    }

    @Test
    public void joinTest()
    {
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

                SelectLineage selectLineage = new SelectLineage();
                List<String> instructions = selectLineage.getLineage((Statement) select);
                System.out.println("instructions:");
                for (String instruct : instructions) {
                    System.out.println(instruct);
                }
            }
            System.out.println("well done.");
        } catch (JSQLParserException e) {
            e.printStackTrace();
        }
    }

    @Test
    public void subqueryTest()
    {
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

                SelectLineage selectLineage = new SelectLineage();
                List<String> instructions = selectLineage.getLineage((Statement) select);
                System.out.println("instructions:");
                for (String instruct : instructions) {
                    System.out.println(instruct);
                }
            }
            System.out.println("well done.");
        } catch (JSQLParserException e) {
            e.printStackTrace();
        }
    }

}

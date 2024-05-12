package com.laotie;

import static org.junit.Assert.assertTrue;
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
    public void shouldAnswerWithTrue()
    {
        String sqlStr = "SELECT max(Col1) as Mcol1, Col2, Col3, 1 as Col4  FROM(" + //
                            "SELECT A1 Col1,A2 Col2,A3 Col3 FROM (" + //
                                "SELECT B1+B2 as A1,B1+B2*B3 as A2,B3 A3 FROM ("+
                                    "SELECT 1 B1, 2 B2, 3 B3"+
                                ") " + //
                            ") " + //
                        ") WHERE condition;";

        Statement statHandle;
        Select select;
        try {
            statHandle = (Statement) CCJSqlParserUtil.parse(sqlStr);
            if (statHandle instanceof Select) {
                select = (Select) statHandle;

                SelectLineage selectLineage = new SelectLineage();
                selectLineage.getLineage((Statement) select, "targetTable");
            }
            System.out.println("well done.");
        } catch (JSQLParserException e) {
            e.printStackTrace();
        }
    }
}

package com.laotie;

import java.util.Set;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;

/**
 * Hello world!
 *
 */
public class App {
    int tableNum = 0;

    public static void main(String[] args) {
        String sqlStr = "SELECT max(Col1) as Mcol1, Col2, Col3  FROM(" + //
                            "SELECT A1 Col1,A2 Col2,A3 Col3 FROM (" + //
                                "SELECT B1+B2 as A1,B2 A2,B3 A3 FROM TB1 " + //
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

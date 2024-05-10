package com.laotie;
import java.util.Set;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.util.TablesNamesFinder;

/**
 * Hello world!
 *
 */
public class App 
{
    int tableNum = 0;
    public static void main(String[] args) {
        // TODO: support: insert/insert overwrite/upsert/replace/update/merge/forien key
        // String sqlStr = "-- insert into c1\r\n" + //
        //                 "select * from(\r\n" + //
        //                 "\tselect * from db1.a1 \r\n" + //
        //                 "\tunion \r\n" + //
        //                 "\tselect * from db1.a2\r\n" + //
        //                 "\tunion \r\n" + //
        //                 "\tselect * from db1.a3\r\n" + //
        //                 ") aa left join db2.b1 as bb1 on a1.id = bb1.aid\r\n" + //
        //                 "-- left join db2.b2 bb2 on a1.id = bb2.aid\r\n" + //
        //                 "where bi.aid is not null;\r\n";
        String sqlStr = "SELECT Col1, Col2, Col3  FROM"+// 
                        "(SELECT A1 Col1,A2 Col2,A3 Col3 FROM ("+ //
                            "SELECT B1 A1,B2 A2,B3 A3 FROM TB1 "+ //
                        ")TB ) TA1 WHERE condition;";

        Statement statHandle;
        Select select;
        Insert insert;
        App app = new App();
        try {
            statHandle = (Statement) CCJSqlParserUtil.parse(sqlStr);
            if (statHandle instanceof Select){
                select = (Select) statHandle;
                app.parseLineage((PlainSelect) select);
            }
            System.out.println("well done.");
        } catch (JSQLParserException e) {
            e.printStackTrace();
        }
    }



    private void parseLineage(PlainSelect select){
        FromItem fromItem = select.getFromItem();

        if (fromItem instanceof Select) {
            ParenthesedSelect parenthesedSelect = (ParenthesedSelect) fromItem;
            parseLineage((PlainSelect)parenthesedSelect.getSelect());
        }
        String tableName = fromItem.toString();
        if (null!=fromItem.getAlias()){
            System.out.println("alias: table"+String.valueOf(tableNum)+" : "+fromItem.getAlias());
            tableName = fromItem.getAlias().getName();
            tableNum++;
        }
        for(SelectItem col: select.getSelectItems()){
            System.out.println("from: "+ tableName + "." + col.getExpression().toString() + " to: table" + String.valueOf(tableNum)+"."+(null == col.getAlias()?col.getExpression().toString():col.getAlias()));
        }
    }

}

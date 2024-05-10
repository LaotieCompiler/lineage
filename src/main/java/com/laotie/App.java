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
public class App {
    int tableNum = 0;

    public static void main(String[] args) {
        String sqlStr = "SELECT Col1, Col2, Col3  FROM(" + //
                            "SELECT A1 Col1,A2 Col2,A3 Col3 FROM (" + //
                                "SELECT B1 A1,B2 A2,B3 A3 FROM TB1 " + //
                            ") TB " + //
                        ") WHERE condition;";

        Statement statHandle;
        Select select;
        App app = new App();
        try {
            statHandle = (Statement) CCJSqlParserUtil.parse(sqlStr);
            if (statHandle instanceof Select) {
                select = (Select) statHandle;
                String alias = String.format("table%d", app.getTableNum());
                app.incrementTableNum();
                app.parseLineage((PlainSelect) select,alias);
            }
            System.out.println("well done.");
        } catch (JSQLParserException e) {
            e.printStackTrace();
        }
    }

    public int getTableNum(){
        return tableNum;
    }

    public void incrementTableNum() {
        this.tableNum++;
    }

    /**
     * 查询 SELECT 语句的血缘信息
     * 
     * 采用后根法遍历语法树，先处理内层 FROM 语句（子节点），再处理 SELECT 语句（根节点）
     */
    private void parseLineage(PlainSelect select, String targetAlias) {
        FromItem fromItem = select.getFromItem();

        String fromAlias = fromItem.toString();
        if (fromItem.getAlias() != null) {
            fromAlias = fromItem.getAlias().getName();
        }else if (fromItem instanceof Table){
            fromAlias = ((Table) fromItem).getName();
        }else{
            fromAlias = String.format("table%d", getTableNum());
            incrementTableNum();
        }

        // 处理子节点：FROM 子查询。(TODO: Join 子查询)
        if (fromItem instanceof Select) {
            ParenthesedSelect parenthesedSelect = (ParenthesedSelect) fromItem;
            parseLineage((PlainSelect) parenthesedSelect.getSelect(), fromAlias);
        }

        // String tableName = fromItem.toString();
        // if (null != fromItem.getAlias()) {
        //     System.out.println("alias: table" + String.valueOf(tableNum) + " : " + fromItem.getAlias());
        //     tableName = fromItem.getAlias().getName();
        //     tableNum++;
        // }

        for (SelectItem col : select.getSelectItems()) {
            System.out.println("from: " + fromAlias + "." + col.getExpression().toString() + " to: "
                    + targetAlias + "."
                    + (null == col.getAlias() ? col.getExpression().toString() : col.getAlias()));
        }
    }

}

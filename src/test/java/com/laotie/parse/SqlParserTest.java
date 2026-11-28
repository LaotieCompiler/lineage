package com.laotie.parse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.statement.SQLInsertStatement;

public class SqlParserTest {

    @Test
    public void sparkOverwriteFallsBackToHive() {
        SQLStatement statement = SqlParser.parse(
                "INSERT OVERWRITE TABLE t SELECT c FROM a;",
                DbType.antspark);
        assertTrue(statement instanceof SQLInsertStatement);
    }

    @Test
    public void originalDialectErrorIsKeptWhenBothFail() {
        try {
            SqlParser.parse("INSERT OVERWRITE TABLE (", DbType.antspark);
            fail("expected a parser error");
        } catch (RuntimeException expected) {
            assertTrue(expected.getMessage().contains("pos"));
        }
    }

    @Test
    public void ordinaryStatementUsesItsDialect() {
        SQLStatement statement = SqlParser.parse(
                "SELECT `c` FROM `t`", DbType.mysql);
        assertEquals("SELECT `c`\nFROM `t`", statement.toString().trim());
    }
}

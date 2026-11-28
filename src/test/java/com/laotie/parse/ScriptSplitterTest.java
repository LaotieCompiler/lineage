package com.laotie.parse;

import static org.junit.Assert.assertEquals;

import java.util.List;

import org.junit.Test;

import com.alibaba.druid.DbType;

public class ScriptSplitterTest {

    @Test
    public void postgresDollarQuotesHideSemicolons() {
        List<String> statements = ScriptSplitter.split(
                "SELECT $$a;b$$; SELECT 'c;d';", DbType.postgresql);
        assertEquals(2, statements.size());
        assertEquals("SELECT $$a;b$$", statements.get(0));
        assertEquals("SELECT 'c;d'", statements.get(1));
    }

    @Test
    public void mysqlCommentsAndStringsHideSemicolons() {
        List<String> statements = ScriptSplitter.split(
                "# one;comment\nSELECT 'a;b'; -- two;\nSELECT 1;",
                DbType.mysql);
        assertEquals(2, statements.size());
        assertEquals("# one;comment\nSELECT 'a;b'", statements.get(0));
        assertEquals("-- two;\nSELECT 1", statements.get(1));
    }

    @Test
    public void sparkRawStringsDoNotEscapeQuotes() {
        List<String> statements = ScriptSplitter.split(
                "SELECT r'a\\'; SELECT 1;", DbType.antspark);
        assertEquals(2, statements.size());
        assertEquals("SELECT r'a\\'", statements.get(0));
    }

    @Test
    public void sqlServerBracketsHideSemicolons() {
        List<String> statements = ScriptSplitter.split(
                "SELECT [a;b] FROM t; SELECT 1;", DbType.sqlserver);
        assertEquals(2, statements.size());
        assertEquals("SELECT [a;b] FROM t", statements.get(0));
    }

    @Test
    public void commentOnlyStatementsAreDropped() {
        List<String> statements = ScriptSplitter.split(
                "-- only\n; /* block */ ; SELECT 1;", DbType.postgresql);
        assertEquals(1, statements.size());
        assertEquals("SELECT 1", statements.get(0));
    }
}

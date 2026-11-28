package com.laotie.dialect;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.alibaba.druid.DbType;
import com.laotie.identifier.Identifier;

public class DialectRulesTest {

    private static Identifier unquoted(String name) {
        return Identifier.of(name);
    }

    private static Identifier quoted(String name) {
        return Identifier.of("\"" + name + "\"");
    }

    @Test
    public void mysqlFoldsEverything() {
        DialectRules rules = DialectRules.of(DbType.mysql);
        assertEquals("abc", rules.key(unquoted("AbC")));
        assertEquals("abc", rules.key(quoted("AbC")));
        assertEquals("AbC", rules.canonical(unquoted("AbC")));
        assertEquals("AbC", rules.canonical(quoted("AbC")));
        assertEquals("`a`", rules.quoteForLearning("a"));
    }

    @Test
    public void postgresKeepsQuotedCase() {
        DialectRules rules = DialectRules.of(DbType.postgresql);
        assertEquals("abc", rules.key(unquoted("AbC")));
        assertEquals("AbC", rules.key(quoted("AbC")));
        assertEquals("abc", rules.canonical(unquoted("AbC")));
        assertEquals("AbC", rules.canonical(quoted("AbC")));
        assertEquals("\"a\"", rules.quoteForLearning("a"));
        assertEquals("\"a\\\\\"\"b\"",
                rules.quoteForLearning("a\\\"b"));
    }

    @Test
    public void hiveFoldsEvenQuotedNames() {
        DialectRules rules = DialectRules.of(DbType.hive);
        assertEquals("abc", rules.key(unquoted("AbC")));
        assertEquals("abc", rules.key(quoted("AbC")));
        assertEquals("AbC", rules.canonical(quoted("AbC")));
        assertEquals("ABC", rules.canonical(unquoted("abc")));
    }

    @Test
    public void oracleKeepsQuotedCase() {
        DialectRules rules = DialectRules.of(DbType.oracle);
        assertEquals("ABC", rules.key(unquoted("AbC")));
        assertEquals("AbC", rules.key(quoted("AbC")));
        assertEquals("ABC", rules.canonical(unquoted("abc")));
        assertEquals("AbC", rules.canonical(quoted("AbC")));
    }

    @Test
    public void unitFunctionsFollowDialect() {
        assertTrue(DialectRules.of(DbType.postgresql)
                .allowsUnitFunction("EXTRACT"));
        assertFalse(DialectRules.of(DbType.postgresql)
                .allowsUnitFunction("TIMESTAMPDIFF"));
        assertTrue(DialectRules.of(DbType.mysql)
                .allowsUnitFunction("TIMESTAMPDIFF"));
        assertTrue(DialectRules.of(DbType.hive)
                .allowsUnitFunction("TIMESTAMPADD"));
        assertTrue(DialectRules.of(DbType.derby)
                .allowsUnitFunction("TIMESTAMPADD"));
        assertTrue(DialectRules.of(DbType.h2)
                .allowsUnitFunction("DATE_TRUNC"));
        assertFalse(DialectRules.of(DbType.h2)
                .allowsUnitFunction("DATE_BUCKET"));
        assertTrue(DialectRules.of(DbType.sqlserver)
                .allowsUnitFunction("DATE_BUCKET"));
        assertFalse(DialectRules.of(DbType.sqlserver)
                .allowsUnitFunction("GET_FORMAT"));
        assertTrue(DialectRules.of(DbType.mysql)
                .allowsUnitFunction("GET_FORMAT"));
    }

    @Test
    public void unitKeywordsAndJdbcUnits() {
        DialectRules rules = DialectRules.of(DbType.sqlserver);
        assertTrue(rules.isUnitKeyword("day"));
        assertTrue(rules.isJdbcUnitKeyword("SQL_TSI_DAY"));
        assertFalse(rules.isUnitKeyword("sql_tsi_counter"));
        assertTrue(DialectRules.of(DbType.derby)
                .requiresJdbcTimestampUnit());
        assertFalse(DialectRules.of(DbType.hsql)
                .requiresJdbcTimestampUnit());
    }

    @Test
    public void nullIdentifiersStayNull() {
        assertNull(Identifier.of(null));
        assertNull(DialectRules.of(null).key(null));
        assertNull(DialectRules.of(null).canonical(null));
    }
}

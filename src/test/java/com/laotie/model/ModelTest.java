package com.laotie.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

import com.laotie.model.metadata.Column;

public class ModelTest {
    @Test
    public void columnTest(){
        Column column = new Column("ta", "ca");
        assertEquals("case 1", "ta", column.getTableName());
        assertEquals("case 1", "ca", column.getColumnName());

        column = new Column("ta.ca");
        assertEquals("case 2", "ta", column.getTableName());
        assertEquals("case 2", "ca", column.getColumnName());

        column = new Column("ca");
        assertEquals("case 3", null, column.getTableName());
        assertEquals("case 3", "ca", column.getColumnName());
    }

    @Test
    public void columnSetTest(){
        Set<Column> columns = new HashSet<>();
        columns.add(new Column("ta.ca"));
        columns.add(new Column("ta","ca"));
        columns.add(new Column("ta","cb"));
        assertEquals(2, columns.size());
    }

    @Test
    public void qualifiedColumnKeepsFullTableName(){
        Column column = new Column("db1.A.c1");
        assertEquals("db1.A", column.getTableName());
        assertEquals("c1", column.getColumnName());

        // Same table name in different databases must stay distinct.
        Column other = new Column("db2.A.c1");
        assertNotEquals(column, other);

        Set<Column> columns = new HashSet<>();
        columns.add(column);
        columns.add(other);
        assertEquals(2, columns.size());
    }
}

package com.laotie.model.metadata;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TableTest {

    @Test
    void constructorWithNameAndColumnsTest() {
        List<Column> columns = Arrays.asList(new Column("col1"), new Column("col2"));
        Table table = new Table("table1", columns);
        assertEquals("table1(col1,col2)", table.toString());
    }

    @Test
    void constructorWithNameAndColumnsAndOrderedColumnsTest() {
        List<Column> columns = Arrays.asList(new Column("col1"), new Column("col2"));
        Table table = new Table("table1", columns, true);
        assertEquals("table1(col1,col2)", table.toString());
    }

    @Test
    void constructorWithNameTest() {
        Table table = new Table("table1");
        assertEquals("table1(_)", table.toString());
    }

    @Test
    void addColumn() {
        Table table = new Table("table1");
        table.addColumn(new Column("col1"));
        table.addColumn(new Column("col2"));
        assertEquals("table1(col1,col2)", table.toString());
    }

    @Test
    void addAllColumn() {
        Table table = new Table("table1");
        List<Column> columns = Arrays.asList(new Column("col1"), new Column("col2"));
        table.addAllColumn(columns);
        assertEquals("table1(col1,col2)", table.toString());
    }

    @Test
    void checkColumnUnique() {
        List<Column> columns = Arrays.asList(new Column("col1"), new Column("col2"), new Column("col1"));
        assertThrows(IllegalArgumentException.class, () -> new Table("table1", columns));
    }

    @Test
    void equalsTest() {
        Table table1 = new Table("table1", Arrays.asList(new Column("col1"), new Column("col2")));
        Table table2 = new Table("table1", Arrays.asList(new Column("col1"), new Column("col2")));
        assertEquals(table1, table2);
    }

    @Test
    void hashCodeTest() {
        Table table1 = new Table("table1", Arrays.asList(new Column("col1"), new Column("col2")));
        Table table2 = new Table("table1", Arrays.asList(new Column("col1"), new Column("col2")));
        assertEquals(table1.hashCode(), table2.hashCode());
    }
}

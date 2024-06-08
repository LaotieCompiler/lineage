package com.laotie.model;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.laotie.model.metadata.Column;
import com.laotie.model.metadata.Table;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MetaBinderTest {
    private MetaBinder metaBinder;

    @BeforeEach
    void setUp() {
        metaBinder = new MetaBinder();
    }

    @Test
    void testColumns2Table() {
        List<Column> columns = new ArrayList<>();
        columns.add(new Column("table1", "col1"));
        columns.add(new Column("table1", "col2"));
        columns.add(new Column("table2", "col3"));

        metaBinder.columns2Table(columns);

        Map<Table, List<Column>> expected = new HashMap<>();
        expected.put(new Table("table1"), Arrays.asList(new Column("table1", "col1"), new Column("table1", "col2")));
        expected.put(new Table("table2"), Arrays.asList(new Column("table2", "col3")));

        assertEquals(expected, metaBinder.getTableColumnsMap());
    }

    @Test
    void testSetColumns() {
        Table table = new Table("table1");
        List<Column> columns = Arrays.asList(new Column("table1", "col1"), new Column("table1", "col2"));

        metaBinder.setColumns(table, columns);

        assertEquals(columns, metaBinder.getColumns(table));
    }

    @Test
    void testColumnsMatched() {
        List<Column> a = Arrays.asList(new Column("table1", "col1"), new Column("table1", "col2"));
        List<Column> b = Arrays.asList(new Column("table1", "col1"), new Column("table1", "col2"));
        List<Column> c = Arrays.asList(new Column("table1", "col1"), new Column("table2", "col2"));

        assertTrue(metaBinder.columnsMatched(a, b));
        Collections.reverse(b);
        assertFalse(metaBinder.columnsMatched(a, b));
        assertFalse(metaBinder.columnsMatched(a, c));
    }
}


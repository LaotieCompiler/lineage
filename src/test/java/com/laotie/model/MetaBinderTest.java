package com.laotie.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import com.laotie.model.metadata.Column;
import com.laotie.model.metadata.Table;

public class MetaBinderTest {
    private MetaBinder metaBinder;

    @Before
    public void setUp() {
        metaBinder = new MetaBinder();
    }

    @Test
    public void testColumns2Table() {
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
    public void testSetColumns() {
        Table table = new Table("table1");
        List<Column> columns = Arrays.asList(new Column("table1", "col1"), new Column("table1", "col2"));

        metaBinder.setColumns(table, columns);

        assertEquals(columns, metaBinder.getColumns(table));
    }

    @Test
    public void testColumnsMatched() {
        List<Column> a = Arrays.asList(new Column("table1", "col1"), new Column("table1", "col2"));
        List<Column> b = Arrays.asList(new Column("table1", "col1"), new Column("table1", "col2"));
        List<Column> c = Arrays.asList(new Column("table1", "col1"), new Column("table2", "col2"));

        assertTrue(metaBinder.columnsMatched(a, b));
        Collections.reverse(b);
        assertFalse(metaBinder.columnsMatched(a, b));
        assertFalse(metaBinder.columnsMatched(a, c));
    }
}

package com.laotie;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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
}

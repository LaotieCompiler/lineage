package com.laotie.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.laotie.exception.MetadataNotFoundException;
import com.laotie.model.metadata.Column;
import com.laotie.model.metadata.Table;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MetaBinder {
    private Map<Table, List<Column>> tableColumnsMap;

    public MetaBinder() {
        tableColumnsMap = new HashMap<>();
    }

    public void columns2Table(List<Column> columns) {
        for (Column column : columns) {
            Table table = new Table(column.getTableName());
            tableColumnsMap.putIfAbsent(table, new ArrayList<>());  
            if (tableColumnsMap.get(table).contains(column)) {
                continue;
            }
            tableColumnsMap.get(table).add(column);
        }
    }

    public Map<Table, List<Column>> getTableColumnsMap() {
        return tableColumnsMap;
    }

    public void setColumns(Table table, List<Column> columns){
        tableColumnsMap.put(table, columns);
    }

    public List<Column> getColumns(Table table){
        return tableColumnsMap.get(table);
    }

    public void appendColumns(Table table, List<Column> columns){
        tableColumnsMap.putIfAbsent(table, new ArrayList<>());  
        // add columns keep unique
        for (Column column : columns) {
            if (tableColumnsMap.get(table).contains(column)) {
                continue;
            }
            tableColumnsMap.get(table).add(column);
        }
    }

    public Boolean tableMatched(Table a, List<Column> b){
        if (tableColumnsMap.get(a) == null) {
            throw new MetadataNotFoundException(a.getName());
        }
        return tableColumnsMap.get(a).equals(b);
    }

    public Boolean tableInclude(Table a, List<Column> b){
        if (tableColumnsMap.get(a) == null) {
            throw new MetadataNotFoundException(a.getName());
        }
        return tableColumnsMap.get(a).containsAll(b);
    }

    public static Boolean columnsMatched(List<Column> a, List<Column> b){
        return a.equals(b);
    }

    public static Boolean columnsIncluded(List<Column> a, List<Column> b){
        return a.containsAll(b);
    }
}

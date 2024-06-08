package com.laotie.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    public Boolean columnsMatched(List<Column> a, List<Column> b){
        return a.equals(b);
    }

}

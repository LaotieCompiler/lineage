package com.laotie.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.laotie.model.metadata.Column;
import com.laotie.model.metadata.Table;

public class MetaBinder {
    private Map<Table, List<Column>> tableColumnsMap = new HashMap<>();

    public MetaBinder() {
    }

    public void columns2Table(List<Column> columns) {
        for (Column column : columns) {
            Table table = new Table(column.getTableName());
            tableColumnsMap.putIfAbsent(table, new ArrayList<>());  
            tableColumnsMap.get(table).add(column);
        }
    }


}

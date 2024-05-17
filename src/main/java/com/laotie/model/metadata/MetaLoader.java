package com.laotie.model.metadata;

import java.util.HashMap;
import java.util.Map;

public class MetaLoader {
    private Map<String, Table> tables;

    public MetaLoader() {
        this.tables = new HashMap<>();
    }

    public void loadTable(Table table){
        String tableName = table.getName();

        if(!tables.containsKey(tableName)){
            tables.put(tableName, table);
        }
    }

}

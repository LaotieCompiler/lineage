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
/**
 * The `MetaBinder` class is responsible for managing the mapping between tables and their corresponding columns.
 * It provides methods to add columns to tables, retrieve columns for a given table, and perform various operations
 * on the table-column mapping, such as checking if a table matches a list of columns or if a table includes a list
 * of columns.
 */
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

    /**
     * 检查表a是否包与列表b中所有列完全匹配。
     * 
     * 此方法用于验证给定表是否完全匹配指定列表中的所有列。
     * 如果找不到对应的表，则抛出MetadataNotFoundException。
     * 
     * @param a 表对象，表示要检查的表。
     * @param b 列列表，表示要检查的列集合。
     * @return 如果表a与列表b中的所有列完全匹配，则返回true；否则返回false。
     * @throws MetadataNotFoundException 如果无法找到表a的元数据，则抛出此异常。
     */
    public Boolean tableMatched(Table a, List<Column> b){
        if (tableColumnsMap.get(a) == null) {
            throw new MetadataNotFoundException(a.getName());
        }
        return tableColumnsMap.get(a).equals(b);
    }

    /**
     * 检查表a是否包含列表b中的所有列。
     * 
     * 此方法用于验证给定表是否包含指定列表中的所有列。
     * 如果找不到对应的表，则抛出MetadataNotFoundException。
     * 
     * @param a 表对象，表示要检查的表。
     * @param b 列列表，表示要检查的列集合。
     * @return 如果表a包含列表b中的所有列，则返回true；否则返回false。
     * @throws MetadataNotFoundException 如果无法找到表a的元数据，则抛出此异常。
     */
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

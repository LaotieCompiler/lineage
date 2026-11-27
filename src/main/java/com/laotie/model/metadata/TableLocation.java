package com.laotie.model.metadata;

import java.util.ArrayList;
import java.util.List;

import com.alibaba.druid.DbType;

/**
 * Fully qualified location of a table inside the metadata hierarchy:
 * Instance -> Database -> Schema -> Table.
 */
public class TableLocation {
    private final String instance;
    private final String database;
    private final String schema;
    private final Table table;
    private final DbType dialect;

    public TableLocation(String instance, String database, String schema, Table table,
            DbType dialect) {
        this.instance = instance;
        this.database = database;
        this.schema = schema;
        this.table = table;
        this.dialect = dialect;
    }

    public String getInstance() {
        return instance;
    }

    public String getDatabase() {
        return database;
    }

    public String getSchema() {
        return schema;
    }

    public Table getTable() {
        return table;
    }

    /**
     * Non-null path in hierarchy order, with dialect-specific quoting when needed.
     */
    public String getQualifiedName() {
        return getDisplayName();
    }

    public String getDisplayName() {
        List<String> parts = new ArrayList<>();
        addPart(parts, instance);
        addPart(parts, database);
        addPart(parts, schema);
        addPart(parts, table.getName());
        return String.join(".", parts);
    }

    private void addPart(List<String> parts, String value) {
        if (value != null) {
            parts.add(quote(value));
        }
    }

    private String quote(String identifier) {
        if (identifier.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return identifier;
        }
        if (isMySqlFamily(dialect)) {
            return "`" + identifier.replace("`", "``") + "`";
        }
        String inner = identifier.replace("\\", "\\\\")
                .replace("\"", "\"\"");
        return "\"" + inner + "\"";
    }

    private static boolean isMySqlFamily(DbType dbType) {
        return dbType == DbType.mysql || dbType == DbType.mariadb;
    }

    @Override
    public String toString() {
        return getQualifiedName();
    }
}

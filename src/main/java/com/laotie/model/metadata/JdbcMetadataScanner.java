package com.laotie.model.metadata;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.alibaba.druid.DbType;

/**
 * Load metadata by scanning a target database through JDBC.
 *
 * <p>
 * The JDBC catalog/schema columns are mapped onto the
 * Instance -> Database -> Schema -> Table -> Field hierarchy:
 * MySQL/MariaDB put the JDBC catalog at the Database level, the other dialects keep
 * catalog=database and schema=schema.
 */
public class JdbcMetadataScanner {
    private static final Set<String> MYSQL_SYSTEM_CATALOGS = new HashSet<>(Arrays.asList(
            "information_schema", "performance_schema", "mysql", "sys"));

    private final DbType dialect;
    private final String instanceName;

    public JdbcMetadataScanner(DbType dialect) {
        this(dialect, null);
    }

    public JdbcMetadataScanner(DbType dialect, String instanceName) {
        this.dialect = dialect == null ? DbType.mysql : dialect;
        this.instanceName = instanceName;
    }

    public MetadataCatalog scan(Connection connection) throws SQLException {
        MetadataCatalog catalog = new MetadataCatalog(dialect);
        DatabaseMetaData metaData = connection.getMetaData();
        String connectionCatalog = emptyToNull(connection.getCatalog());

        String[] tableTypes = dialect == DbType.postgresql
                ? new String[] {"TABLE", "VIEW", "PARTITIONED TABLE"}
                : new String[] {"TABLE", "VIEW"};
        try (ResultSet tables = metaData.getTables(null, null, "%", tableTypes)) {
            while (tables.next()) {
                String rawCatalog = emptyToNull(tables.getString("TABLE_CAT"));
                String tableCatalog = rawCatalog != null ? rawCatalog : connectionCatalog;
                String tableSchema = emptyToNull(tables.getString("TABLE_SCHEM"));
                String tableName = tables.getString("TABLE_NAME");

                if (isSystemNamespace(tableCatalog, tableSchema)) {
                    continue;
                }

                String database = tableCatalog;
                String schema = isMySqlFamily() ? null : tableSchema;

                List<Column> columns = readColumns(metaData, tableCatalog, rawCatalog,
                        tableSchema, tableName);
                // A table without columns cannot contribute column lineage.
                if (!columns.isEmpty()) {
                    catalog.addScannedTable(instanceName, database, schema, tableName, columns);
                }
            }
        }
        return catalog;
    }

    private List<Column> readColumns(DatabaseMetaData metaData, String queryCatalog,
            String expectedCatalog, String schemaName, String tableName) throws SQLException {
        List<Column> columns = new ArrayList<>();
        String escape = metaData.getSearchStringEscape();
        String schemaPattern = escapePattern(schemaName, escape);
        String tablePattern = escapePattern(tableName, escape);

        try (ResultSet resultSet = metaData.getColumns(queryCatalog, schemaPattern, tablePattern,
                "%")) {
            while (resultSet.next()) {
                // JDBC treats schema/table arguments as patterns; keep only the exact object.
                if (!Objects.equals(expectedCatalog,
                        emptyToNull(resultSet.getString("TABLE_CAT")))
                        || !Objects.equals(schemaName, emptyToNull(resultSet.getString("TABLE_SCHEM")))
                        || !Objects.equals(tableName, resultSet.getString("TABLE_NAME"))) {
                    continue;
                }
                // Preserve dots in column names; a qualified-name constructor would split them.
                String columnName = resultSet.getString("COLUMN_NAME");
                Column column = new Column(null, columnName);
                column.setDataType(resultSet.getString("TYPE_NAME"));
                column.setQuoted(inferQuoted(columnName));
                columns.add(column);
            }
        }
        return columns;
    }

    private static String escapePattern(String value, String escape) {
        if (value == null) {
            return null;
        }
        String escapeChar = escape == null || escape.isEmpty() ? "\\" : escape;
        StringBuilder escaped = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '%' || ch == '_' || escapeChar.indexOf(ch) >= 0) {
                escaped.append(escapeChar);
            }
            escaped.append(ch);
        }
        return escaped.toString();
    }

    private boolean isSystemNamespace(String catalogName, String schemaName) {
        if (isMySqlFamily()) {
            return catalogName != null
                    && MYSQL_SYSTEM_CATALOGS.contains(catalogName.toLowerCase());
        }
        String schema = schemaName == null ? null : schemaName.toLowerCase();
        if (dialect == DbType.postgresql) {
            return schema != null && (schema.equals("information_schema")
                    || schema.startsWith("pg_") || schema.equals("pg_catalog"));
        }
        if (dialect == DbType.h2) {
            return schema != null && schema.equals("information_schema");
        }
        if (dialect == DbType.sqlserver) {
            return schema != null && (schema.equals("information_schema")
                    || schema.equals("sys"));
        }
        // Other dialects: only the common information_schema is treated as system.
        return schema != null && schema.equals("information_schema");
    }

    private boolean inferQuoted(String columnName) {
        if (columnName == null || isMySqlFamily()) {
            return false;
        }
        if (dialect == DbType.postgresql) {
            // Unquoted PostgreSQL identifiers fold to lower case; upper case implies quoting.
            return !columnName.equals(columnName.toLowerCase());
        }
        // Oracle/H2/SQL Server/DB2 fold unquoted names to upper case.
        return !columnName.equals(columnName.toUpperCase());
    }

    private boolean isMySqlFamily() {
        return dialect == DbType.mysql || dialect == DbType.mariadb;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}

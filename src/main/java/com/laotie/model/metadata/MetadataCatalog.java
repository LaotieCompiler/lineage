package com.laotie.model.metadata;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.statement.SQLColumnDefinition;
import com.alibaba.druid.sql.ast.statement.SQLCreateDatabaseStatement;
import com.alibaba.druid.sql.ast.statement.SQLCreateTableStatement;
import com.alibaba.druid.sql.ast.statement.SQLTableElement;
import com.laotie.exception.AmbiguousTableException;
import com.laotie.exception.MetadataNotFoundException;
import com.laotie.exception.MetadataPersistenceException;

/**
 * In-memory metadata catalog with the hierarchy
 * Instance -> Database -> Schema -> Table -> Field.
 *
 * <p>
 * Missing levels are stored as {@code null}; the literal name "default" is a normal identifier.
 * Two-part names are dialect aware: MySQL/MariaDB mean database.table, the other dialects mean
 * schema.table.
 */
public class MetadataCatalog {
    private static final Logger LOGGER = Logger.getLogger(MetadataCatalog.class.getName());

    private final DbType dialect;
    private final Map<String, Instance> instances = new LinkedHashMap<>();

    private String defaultInstance;
    private String defaultDatabase;
    private String defaultSchema;

    private long version;
    private String cachedChecksum;
    private boolean readOnly;

    public MetadataCatalog() {
        this(DbType.mysql);
    }

    public MetadataCatalog(DbType dialect) {
        this.dialect = dialect == null ? DbType.mysql : dialect;
    }

    public DbType getDialect() {
        return dialect;
    }

    /* ------------------------------------------------------------------ */
    /* hierarchy access                                                   */
    /* ------------------------------------------------------------------ */

    public Collection<Instance> getInstances() {
        return instances.values();
    }

    public Map<String, Instance> getInstanceMap() {
        return instances;
    }

    public Instance getInstance(String instanceName) {
        return instances.get(instanceName);
    }

    public Instance getOrCreateInstance(String instanceName) {
        checkMutable();
        return instances.computeIfAbsent(instanceName, Instance::new);
    }

    public Database getDatabase(String instanceName, String databaseName) {
        Instance instance = getInstance(instanceName);
        return instance == null ? null : instance.getDatabase(databaseName);
    }

    public Schema getSchema(String instanceName, String databaseName, String schemaName) {
        Database database = getDatabase(instanceName, databaseName);
        return database == null ? null : database.getSchema(schemaName);
    }

    public boolean isEmpty() {
        return instances.isEmpty();
    }

    /* ------------------------------------------------------------------ */
    /* defaults                                                           */
    /* ------------------------------------------------------------------ */

    public void setDefaultInstance(String defaultInstance) {
        checkMutable();
        this.defaultInstance = defaultInstance;
    }

    public void setDefaultDatabase(String defaultDatabase) {
        checkMutable();
        this.defaultDatabase = defaultDatabase;
    }

    public void setDefaultSchema(String defaultSchema) {
        checkMutable();
        this.defaultSchema = defaultSchema;
    }

    public String getDefaultInstance() {
        return defaultInstance;
    }

    public String getDefaultDatabase() {
        return defaultDatabase;
    }

    public String getDefaultSchema() {
        return defaultSchema;
    }

    /* ------------------------------------------------------------------ */
    /* DDL loading                                                        */
    /* ------------------------------------------------------------------ */

    public synchronized void loadDdl(String ddl) {
        checkMutable();
        if (ddl == null || ddl.trim().isEmpty()) {
            return;
        }

        final List<SQLStatement> statements;
        try {
            statements = SQLUtils.parseStatements(ddl, dialect);
        } catch (RuntimeException e) {
            throw new MetadataPersistenceException("invalid DDL: " + e.getMessage(), e);
        }

        List<TableMutation> tableMutations = new ArrayList<>();
        List<String[]> databaseMutations = new ArrayList<>();
        for (SQLStatement statement : statements) {
            if (statement instanceof SQLCreateTableStatement) {
                tableMutations.add(parseCreateTable((SQLCreateTableStatement) statement));
            } else if (statement instanceof SQLCreateDatabaseStatement) {
                databaseMutations.add(parseCreateDatabase((SQLCreateDatabaseStatement) statement));
            }
        }

        // Validate everything before touching the catalog (atomic load).
        for (TableMutation mutation : tableMutations) {
            if (!isRepresentable(mutation.instance, mutation.database, mutation.schema)) {
                throw new MetadataPersistenceException("table path cannot be represented as DDL: "
                        + path(mutation.instance, mutation.database, mutation.schema,
                                mutation.table.getName()));
            }
        }

        if (tableMutations.isEmpty() && databaseMutations.isEmpty()) {
            return;
        }

        for (String[] database : databaseMutations) {
            getOrCreateInstance(database[0])
                    .getOrCreateDatabase(database[1])
                    .getOrCreateSchema(database[2]);
        }
        for (TableMutation mutation : tableMutations) {
            getOrCreateInstance(mutation.instance)
                    .getOrCreateDatabase(mutation.database)
                    .getOrCreateSchema(mutation.schema)
                    .putTable(mutation.table);
        }

        version++;
        cachedChecksum = null;
    }

    private TableMutation parseCreateTable(SQLCreateTableStatement createTable) {
        String qualifiedName = createTable.getTableSource().getExpr().toString();
        String[] parts = resolveTableParts(splitQualifiedName(qualifiedName));
        List<Column> columns = new ArrayList<>();
        for (SQLTableElement element : createTable.getTableElementList()) {
            if (element instanceof SQLColumnDefinition) {
                SQLColumnDefinition definition = (SQLColumnDefinition) element;
                // A column definition name is already the bare field name; do not split dots.
                String rawColumnName = definition.getColumnName();
                Column column = new Column(null, normalizeIdentifier(rawColumnName));
                column.setQuoted(isQuotedIdentifier(rawColumnName));
                if (definition.getDataType() != null) {
                    column.setDataType(definition.getDataType().toString());
                }
                columns.add(column);
            }
        }
        Table table = new Table(parts[3], columns);
        return new TableMutation(parts[0], parts[1], parts[2], table);
    }

    private String[] parseCreateDatabase(SQLCreateDatabaseStatement createDatabase) {
        String[] parts = splitQualifiedName(createDatabase.getName().toString());
        String instance = null;
        String database;
        String schema = null;
        if (parts.length >= 3) {
            instance = parts[parts.length - 3];
            database = parts[parts.length - 2];
            schema = parts[parts.length - 1];
        } else if (parts.length == 2) {
            database = parts[0];
            schema = parts[1];
        } else {
            database = parts[0];
        }
        return new String[] {instance, database, schema};
    }

    /* ------------------------------------------------------------------ */
    /* table registration                                                 */
    /* ------------------------------------------------------------------ */

    public TableLocation addTable(String qualifiedName, List<Column> columns) {
        String[] parts = splitQualifiedName(qualifiedName);
        if (parts.length == 0) {
            throw new MetadataPersistenceException("table name is empty");
        }
        return addTable(parts, columns);
    }

    public TableLocation addTable(String[] parts, List<Column> columns) {
        String[] resolved = resolveTableParts(parts);
        return addTable(resolved[0], resolved[1], resolved[2], resolved[3], columns);
    }

    public synchronized TableLocation addTable(String instance, String database, String schema,
            String tableName, List<Column> columns) {
        return addTableInternal(instance, database, schema, tableName, columns, false);
    }

    /**
     * Register a table discovered from JDBC. Such paths may not be representable as DDL text
     * (for example a MySQL table with an instance name and no schema); they remain usable for
     * lineage and snapshots, while {@link #toDdl()} rejects the catalog.
     */
    public synchronized TableLocation addScannedTable(String instance, String database,
            String schema, String tableName, List<Column> columns) {
        return addTableInternal(instance, database, schema, tableName, columns, true);
    }

    private TableLocation addTableInternal(String instance, String database, String schema,
            String tableName, List<Column> columns, boolean allowNonRepresentable) {
        checkMutable();
        if (tableName == null || tableName.isEmpty()) {
            throw new MetadataPersistenceException("table name is empty");
        }
        if (!allowNonRepresentable && !isRepresentable(instance, database, schema)) {
            throw new MetadataPersistenceException("table path cannot be represented as DDL: "
                    + path(instance, database, schema, tableName));
        }

        Table table = new Table(tableName, columns);
        getOrCreateInstance(instance)
                .getOrCreateDatabase(database)
                .getOrCreateSchema(schema)
                .putTable(table);

        version++;
        cachedChecksum = null;
        return new TableLocation(instance, database, schema, table, dialect);
    }

    private String[] resolveTableParts(String[] parts) {
        if (parts == null || parts.length == 0) {
            throw new MetadataPersistenceException("table name is empty");
        }
        String instance = null;
        String database = null;
        String schema = null;
        String table;
        if (parts.length >= 4) {
            instance = parts[parts.length - 4];
            database = parts[parts.length - 3];
            schema = parts[parts.length - 2];
            table = parts[parts.length - 1];
        } else if (parts.length == 3) {
            database = parts[0];
            schema = parts[1];
            table = parts[2];
        } else if (parts.length == 2) {
            if (isMySqlFamily()) {
                database = parts[0];
            } else {
                schema = parts[0];
            }
            table = parts[1];
        } else {
            table = parts[0];
        }
        return new String[] {instance, database, schema, table};
    }

    private boolean isRepresentable(String instance, String database, String schema) {
        if (instance != null) {
            return database != null && schema != null;
        }
        if (database != null) {
            return isMySqlFamily() || schema != null;
        }
        if (schema != null) {
            return !isMySqlFamily();
        }
        return true;
    }

    private boolean isMySqlFamily() {
        return dialect == DbType.mysql || dialect == DbType.mariadb;
    }

    /* ------------------------------------------------------------------ */
    /* lookup                                                             */
    /* ------------------------------------------------------------------ */

    public List<TableLocation> findAllTables(String reference) {
        String[] pattern = referencePattern(reference);
        List<TableLocation> result = new ArrayList<>();
        for (Instance instance : instances.values()) {
            for (Database database : instance.getDatabases()) {
                for (Schema schema : database.getSchemas()) {
                    for (Table table : schema.getTables()) {
                        if (matches(pattern[0], instance.getName())
                                && matches(pattern[1], database.getName())
                                && matches(pattern[2], schema.getName())
                                && matches(pattern[3], table.getName())) {
                            result.add(new TableLocation(instance.getName(), database.getName(),
                                    schema.getName(), table, dialect));
                        }
                    }
                }
            }
        }
        return result;
    }

    public TableLocation findTable(String reference) {
        List<TableLocation> matches = findAllTables(reference);
        if (matches.isEmpty()) {
            throw new MetadataNotFoundException(reference);
        }
        if (matches.size() > 1) {
            List<String> candidates = new ArrayList<>();
            for (TableLocation match : matches) {
                candidates.add(match.getQualifiedName());
            }
            throw new AmbiguousTableException(reference, candidates);
        }
        return matches.get(0);
    }

    /**
     * Find exactly one table by its full display name. Internal code uses this when a reference
     * is already a structured path (for example an instance-qualified MySQL name).
     */
    public TableLocation findTableByDisplayName(String display) {
        List<TableLocation> exact = findAllByDisplayName(display);
        if (exact.isEmpty()) {
            throw new MetadataNotFoundException(display);
        }
        if (exact.size() > 1) {
            List<String> candidates = new ArrayList<>();
            for (TableLocation match : exact) {
                candidates.add(match.getQualifiedName());
            }
            throw new AmbiguousTableException(display, candidates);
        }
        return exact.get(0);
    }

    /**
     * Find tables whose display name is exactly the given reference. This disambiguates
     * instance-qualified MySQL paths, which cannot be parsed positionally.
     */
    public List<TableLocation> findAllByDisplayName(String reference) {
        List<TableLocation> result = new ArrayList<>();
        if (reference == null) {
            return result;
        }
        for (Instance instance : instances.values()) {
            for (Database database : instance.getDatabases()) {
                for (Schema schema : database.getSchemas()) {
                    for (Table table : schema.getTables()) {
                        TableLocation location = new TableLocation(instance.getName(),
                                database.getName(), schema.getName(), table, dialect);
                        if (location.getDisplayName().equalsIgnoreCase(reference)) {
                            result.add(location);
                        }
                    }
                }
            }
        }
        return result;
    }

    private String[] referencePattern(String reference) {
        String[] parts = splitQualifiedName(reference);
        String[] pattern = new String[4];
        if (parts.length >= 4) {
            pattern[0] = parts[parts.length - 4];
            pattern[1] = parts[parts.length - 3];
            pattern[2] = parts[parts.length - 2];
            pattern[3] = parts[parts.length - 1];
        } else if (parts.length == 3) {
            pattern[1] = parts[0];
            pattern[2] = parts[1];
            pattern[3] = parts[2];
        } else if (parts.length == 2) {
            if (isMySqlFamily()) {
                pattern[1] = parts[0];
            } else {
                pattern[2] = parts[0];
            }
            pattern[3] = parts[1];
        } else {
            pattern[3] = parts[0];
        }
        if (pattern[0] == null) {
            pattern[0] = defaultInstance;
        }
        if (pattern[1] == null) {
            pattern[1] = defaultDatabase;
        }
        if (pattern[2] == null) {
            pattern[2] = defaultSchema;
        }
        return pattern;
    }

    private static boolean matches(String pattern, String actual) {
        return pattern == null || (actual != null && pattern.equalsIgnoreCase(actual));
    }

    /* ------------------------------------------------------------------ */
    /* version / checksum / persistence                                   */
    /* ------------------------------------------------------------------ */

    public synchronized long getVersion() {
        return version;
    }

    public synchronized String getChecksum() {
        if (cachedChecksum == null) {
            cachedChecksum = sha256Hex(canonicalBody(false));
        }
        return cachedChecksum;
    }

    public synchronized String toDdl() {
        StringBuilder ddl = new StringBuilder();
        ddl.append("-- lineage-metadata\n");
        ddl.append("-- dialect: ").append(dialect).append('\n');
        ddl.append("-- version: ").append(version).append('\n');
        ddl.append("-- checksum: ").append(getChecksum()).append('\n');
        ddl.append(canonicalBody(true));
        return ddl.toString();
    }

    public void saveDdl(Path path) throws IOException {
        Files.write(path, toDdl().getBytes(StandardCharsets.UTF_8));
    }

    public static MetadataCatalog fromDdlFile(Path path) throws IOException {
        String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        String[] lines = content.split("\n", -1);

        String dialectName = null;
        Long headerVersion = null;
        String headerChecksum = null;
        boolean marker = false;
        int bodyStart = 0;
        for (; bodyStart < lines.length; bodyStart++) {
            String line = lines[bodyStart];
            if (!line.startsWith("-- ")) {
                break;
            }
            if ("-- lineage-metadata".equals(line)) {
                marker = true;
            } else if (line.startsWith("-- dialect: ")) {
                if (dialectName != null) {
                    throw new MetadataPersistenceException("duplicate dialect header");
                }
                dialectName = line.substring("-- dialect: ".length()).trim();
            } else if (line.startsWith("-- version: ")) {
                if (headerVersion != null) {
                    throw new MetadataPersistenceException("duplicate version header");
                }
                try {
                    headerVersion = Long.parseLong(line.substring("-- version: ".length()).trim());
                } catch (NumberFormatException e) {
                    throw new MetadataPersistenceException("invalid version header", e);
                }
            } else if (line.startsWith("-- checksum: ")) {
                if (headerChecksum != null) {
                    throw new MetadataPersistenceException("duplicate checksum header");
                }
                headerChecksum = line.substring("-- checksum: ".length()).trim();
            }
        }

        if (!marker || dialectName == null) {
            throw new MetadataPersistenceException("missing metadata header");
        }

        String body = String.join("\n", Arrays.copyOfRange(lines, bodyStart, lines.length));
        MetadataCatalog catalog = new MetadataCatalog(parseDialect(dialectName));
        catalog.loadDdl(body);

        if (headerChecksum != null && !headerChecksum.equals(catalog.getChecksum())) {
            LOGGER.warning(String.format(
                    "metadata checksum mismatch: header=%s recomputed=%s",
                    headerChecksum, catalog.getChecksum()));
        }
        if (headerVersion != null) {
            catalog.version = headerVersion;
        }
        return catalog;
    }

    public static MetadataCatalog fromDdlFile(Path path, DbType dialect) throws IOException {
        String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        MetadataCatalog catalog = new MetadataCatalog(dialect);
        catalog.loadDdl(content);
        return catalog;
    }

    private static DbType parseDialect(String name) {
        for (DbType dbType : DbType.values()) {
            if (dbType.name().equalsIgnoreCase(name)) {
                return dbType;
            }
        }
        throw new MetadataPersistenceException("unknown dialect: " + name);
    }

    /**
     * Capture an immutable snapshot of this catalog.
     */
    public synchronized MetadataCatalog snapshot() {
        MetadataCatalog copy = new MetadataCatalog(dialect);
        copy.defaultInstance = defaultInstance;
        copy.defaultDatabase = defaultDatabase;
        copy.defaultSchema = defaultSchema;
        for (Map.Entry<String, Instance> entry : instances.entrySet()) {
            copy.instances.put(entry.getKey(), entry.getValue().snapshot());
        }
        copy.version = version;
        copy.cachedChecksum = getChecksum();
        copy.readOnly = true;
        return copy;
    }

    private String canonicalBody(boolean forDdl) {
        List<String> databaseLines = new ArrayList<>();
        List<String> tableLines = new ArrayList<>();

        for (Instance instance : instances.values()) {
            for (Database database : instance.getDatabases()) {
                boolean hasTable = false;
                for (Schema schema : database.getSchemas()) {
                    for (Table table : schema.getTables()) {
                        hasTable = true;
                        tableLines.add("CREATE TABLE " + canonicalPath(forDdl,
                                instance.getName(), database.getName(), schema.getName(),
                                table.getName()) + " (" + columnDefinitions(table) + ");");
                    }
                }
                if (!hasTable) {
                    databaseLines.add("CREATE DATABASE " + canonicalDatabasePath(forDdl,
                            instance.getName(), database.getName()) + ";");
                }
            }
        }

        databaseLines.sort(String::compareTo);
        tableLines.sort(String::compareTo);

        StringBuilder body = new StringBuilder();
        for (String line : databaseLines) {
            body.append(line).append('\n');
        }
        for (String line : tableLines) {
            body.append(line).append('\n');
        }
        return body.toString();
    }

    private String canonicalPath(boolean forDdl, String instance, String database, String schema,
            String table) {
        if (isRepresentable(instance, database, schema)) {
            return path(instance, database, schema, table);
        }
        if (forDdl) {
            throw new MetadataPersistenceException("table path cannot be represented as DDL: "
                    + pathWithNullMarkers(instance, database, schema, table));
        }
        return pathWithNullMarkers(instance, database, schema, table);
    }

    private String canonicalDatabasePath(boolean forDdl, String instance, String database) {
        if (isRepresentableDatabase(instance, database)) {
            return path(instance, database);
        }
        if (forDdl) {
            throw new MetadataPersistenceException("database path cannot be represented as DDL: "
                    + pathWithNullMarkers(instance, database, null, null));
        }
        return pathWithNullMarkers(instance, database, null, null);
    }

    private boolean isRepresentableDatabase(String instance, String database) {
        // An instance-qualified empty database would need a schema to be representable,
        // and the canonical empty-database line does not carry one.
        return instance == null && database != null;
    }

    private String pathWithNullMarkers(String... parts) {
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            result.add(part == null ? "<null>" : quote(part));
        }
        return String.join(".", result);
    }

    private String columnDefinitions(Table table) {
        List<String> definitions = new ArrayList<>();
        for (Column column : table.getFields()) {
            String dataType = column.getDataType() == null ? "VARCHAR" : column.getDataType();
            definitions.add(quote(column.getColumnName()) + " " + dataType);
        }
        return String.join(", ", definitions);
    }

    private String path(String... parts) {
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            if (part != null) {
                result.add(quote(part));
            }
        }
        return String.join(".", result);
    }

    private String quote(String identifier) {
        if (identifier.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return identifier;
        }
        char quote = isMySqlFamily() ? '`' : '"';
        return quote + identifier.replace(String.valueOf(quote), String.valueOf(quote) + quote)
                + quote;
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private void checkMutable() {
        if (readOnly) {
            throw new UnsupportedOperationException("metadata snapshot is read-only");
        }
    }

    /* ------------------------------------------------------------------ */
    /* names                                                              */
    /* ------------------------------------------------------------------ */

    public static boolean isQuotedIdentifier(String identifier) {
        if (identifier == null || identifier.length() < 2) {
            return false;
        }
        char first = identifier.charAt(0);
        char last = identifier.charAt(identifier.length() - 1);
        return (first == '`' && last == '`')
                || (first == '"' && last == '"')
                || (first == '[' && last == ']');
    }

    /**
     * Resolve a possibly quoted column reference to the canonical field name of the table.
     * Returns null when the field does not exist.
     */
    public static String resolveColumnName(Table table, String rawName, DbType dialect) {
        if (table == null || rawName == null) {
            return normalizeIdentifier(rawName);
        }
        boolean quoted = isQuotedIdentifier(rawName);
        String name = normalizeIdentifier(rawName);
        Column column;
        if (dialect == DbType.mysql || dialect == DbType.mariadb) {
            // MySQL/MariaDB column names are case-insensitive even when quoted.
            column = table.getColumnIgnoreCase(name);
        } else if (quoted) {
            column = table.getColumnExact(name);
        } else if (dialect == DbType.postgresql) {
            column = table.getColumnExact(name.toLowerCase());
            if (column == null) {
                column = table.getColumnIgnoreCase(name);
            }
        } else {
            column = table.getColumnExact(name.toUpperCase());
            if (column == null) {
                column = table.getColumnIgnoreCase(name);
            }
        }
        return column == null ? null : column.getColumnName();
    }

    public static String normalizeIdentifier(String identifier) {
        if (identifier == null) {
            return null;
        }
        String value = identifier.trim();
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '`' && last == '`')
                    || (first == '"' && last == '"')
                    || (first == '[' && last == ']')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    /**
     * Split a qualified name on dots outside quotes, keeping the quote characters in each part.
     */
    public static String[] splitQualifiedNamePreservingQuotes(String qualifiedName) {
        if (qualifiedName == null) {
            return new String[0];
        }
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < qualifiedName.length(); i++) {
            char ch = qualifiedName.charAt(i);
            if (quote != 0) {
                current.append(ch);
                if (ch == quote) {
                    quote = 0;
                }
            } else if (ch == '`' || ch == '"') {
                quote = ch;
                current.append(ch);
            } else if (ch == '[') {
                quote = ']';
                current.append(ch);
            } else if (ch == '.') {
                parts.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        String last = current.toString().trim();
        if (!last.isEmpty() || !parts.isEmpty()) {
            parts.add(last);
        }
        return parts.toArray(new String[0]);
    }

    public static String[] splitQualifiedName(String qualifiedName) {
        if (qualifiedName == null) {
            return new String[0];
        }
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < qualifiedName.length(); i++) {
            char ch = qualifiedName.charAt(i);
            if (quote != 0) {
                if (ch == quote) {
                    quote = 0;
                } else {
                    current.append(ch);
                }
            } else if (ch == '`' || ch == '"') {
                quote = ch;
            } else if (ch == '[') {
                quote = ']';
            } else if (ch == '.') {
                parts.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        String last = current.toString().trim();
        if (!last.isEmpty() || !parts.isEmpty()) {
            parts.add(last);
        }
        return parts.toArray(new String[0]);
    }

    private static final class TableMutation {
        private final String instance;
        private final String database;
        private final String schema;
        private final Table table;

        private TableMutation(String instance, String database, String schema, Table table) {
            this.instance = instance;
            this.database = database;
            this.schema = schema;
            this.table = table;
        }
    }
}

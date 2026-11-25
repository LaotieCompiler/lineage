package com.laotie.model.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Set;

import org.junit.Test;

import com.alibaba.druid.DbType;
import com.laotie.LineageScanner;
import com.laotie.exception.MetadataPersistenceException;
import com.laotie.model.Instruction;
import com.laotie.model.ScanResult;

public class JdbcMetadataScannerTest {

    @Test
    public void scansTablesColumnsAndViews() throws Exception {
        try (Connection connection = h2("scan")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE A (c1 INT, c2 VARCHAR(10))");
                statement.execute("CREATE TABLE B (c3 BIGINT)");
                statement.execute("CREATE VIEW V AS SELECT c1 FROM A");
            }

            MetadataCatalog catalog = new JdbcMetadataScanner(DbType.h2).scan(connection);
            String database = connection.getCatalog();

            TableLocation a = catalog.findTable(database + ".PUBLIC.A");
            assertEquals("INTEGER", a.getTable().getColumn("c1").getDataType());
            assertEquals("CHARACTER VARYING", a.getTable().getColumn("c2").getDataType());

            TableLocation b = catalog.findTable(database + ".PUBLIC.B");
            assertEquals("BIGINT", b.getTable().getColumn("c3").getDataType());

            assertEquals("A", a.getTable().getName());
            assertEquals("B", b.getTable().getName());

            TableLocation view = catalog.findTable(database + ".PUBLIC.V");
            assertEquals("V", view.getTable().getName());
            assertEquals("C1", view.getTable().getColumns().get(0).getColumnName());
        }
    }

    @Test
    public void similarTableNamesDoNotBleedColumns() throws Exception {
        try (Connection connection = h2("similar")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE A_B (c1 INT)");
                statement.execute("CREATE TABLE AXB (c2 INT)");
            }

            MetadataCatalog catalog = new JdbcMetadataScanner(DbType.h2).scan(connection);
            String database = connection.getCatalog();

            TableLocation a = catalog.findTable(database + ".PUBLIC.A_B");
            assertEquals(1, a.getTable().getFields().size());
            assertTrue(a.getTable().hasColumn("C1"));
            assertFalse(a.getTable().hasColumn("C2"));

            TableLocation axb = catalog.findTable(database + ".PUBLIC.AXB");
            assertEquals(1, axb.getTable().getFields().size());
            assertTrue(axb.getTable().hasColumn("C2"));
        }
    }

    @Test
    public void sameTableNameInDifferentSchemasIsKeptSeparate() throws Exception {
        try (Connection connection = h2("schemas")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA S1");
                statement.execute("CREATE SCHEMA S2");
                statement.execute("CREATE TABLE S1.A (c1 INT)");
                statement.execute("CREATE TABLE S2.A (c2 INT)");
            }

            MetadataCatalog catalog = new JdbcMetadataScanner(DbType.h2).scan(connection);
            String database = connection.getCatalog();

            assertTrue(catalog.findTable(database + ".S1.A").getTable().hasColumn("C1"));
            assertTrue(catalog.findTable(database + ".S2.A").getTable().hasColumn("C2"));
        }
    }

    @Test
    public void dottedIdentifiersArePreserved() throws Exception {
        try (Connection connection = h2("dotted")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE \"dot.table\" (\"dot.column\" INT)");
            }

            MetadataCatalog catalog = new JdbcMetadataScanner(DbType.h2).scan(connection);
            String database = connection.getCatalog();

            TableLocation location = catalog.findTable(
                    database + ".PUBLIC.\"dot.table\"");
            assertEquals("dot.table", location.getTable().getName());
            assertNotNull(location.getTable().getColumn("dot.column"));
        }
    }

    @Test
    public void systemSchemasAreFiltered() throws Exception {
        try (Connection connection = h2("system")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE A (c1 INT)");
            }

            MetadataCatalog catalog = new JdbcMetadataScanner(DbType.h2).scan(connection);
            for (TableLocation location : catalog.findAllTables("A")) {
                assertFalse("INFORMATION_SCHEMA".equalsIgnoreCase(location.getSchema()));
            }
            for (Instance instance : catalog.getInstances()) {
                for (Database database : instance.getDatabases()) {
                    for (Schema schema : database.getSchemas()) {
                        assertFalse("system schema leaked: " + schema.getName(),
                                "INFORMATION_SCHEMA".equalsIgnoreCase(schema.getName()));
                    }
                }
            }
        }
    }

    @Test
    public void userSchemaNamedSystemIsNotFiltered() throws Exception {
        try (Connection connection = h2("user_system")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA \"system\"");
                statement.execute("CREATE TABLE \"system\".A (c1 INT)");
            }

            MetadataCatalog catalog = new JdbcMetadataScanner(DbType.h2).scan(connection);
            String database = connection.getCatalog();

            TableLocation location = catalog.findTable(database + ".system.A");
            assertEquals("system", location.getSchema());
            assertNotNull(location.getTable().getColumn("C1"));
        }
    }

    @Test
    public void repeatedScansProduceTheSameChecksum() throws Exception {
        try (Connection connection = h2("repeat")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE A (c1 INT)");
            }

            JdbcMetadataScanner scanner = new JdbcMetadataScanner(DbType.h2);
            String first = scanner.scan(connection).getChecksum();
            String second = scanner.scan(connection).getChecksum();
            assertEquals(first, second);
        }
    }

    @Test
    public void mySqlFamilySupportsInstanceNameButCannotPersistAsDdl() throws Exception {
        for (DbType dialect : new DbType[] {DbType.mysql, DbType.mariadb}) {
            try (Connection connection = h2("mysql_" + dialect.name())) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("CREATE TABLE A (c1 INT)");
                }

                MetadataCatalog catalog = new JdbcMetadataScanner(dialect, "my-instance")
                        .scan(connection);
                String database = connection.getCatalog();

                List<TableLocation> matches = catalog.findAllTables("A");
                assertEquals(1, matches.size());
                TableLocation location = matches.get(0);
                assertEquals("my-instance", location.getInstance());
                assertEquals(database, location.getDatabase());
                assertNull(location.getSchema());
                assertNotNull(location.getTable().getColumn("C1"));

                // MySQL with an instance and no schema has no DDL representation.
                assertThrows(MetadataPersistenceException.class, catalog::toDdl);
            }
        }
    }

    @Test
    public void dottedIdentifiersReachTheGlobalGraph() throws Exception {
        try (Connection connection = h2("dotted_graph")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE \"dot.table\" (\"dot.column\" INT)");
            }

            MetadataCatalog catalog = new JdbcMetadataScanner(DbType.h2).scan(connection);
            String database = connection.getCatalog();
            MetadataContext context = MetadataContext.of(catalog, null, database, "PUBLIC");

            ScanResult result = new LineageScanner(context)
                    .scan("SELECT \"dot.column\" FROM \"dot.table\";");

            assertEquals(1, result.getResults().size());
            assertEquals(0, result.getFailures().size());

            Instruction instruction = result.getResults().get(0).getInstructions().get(0);
            assertEquals(database + ".PUBLIC.\"dot.table\".\"dot.column\"",
                    instruction.getFrom());
            assertEquals("temp0.\"dot.column\"", instruction.getTo());

            Set<Column> sources = result.getGraph()
                    .getSources(Column.temporary("stmt0.temp0.\"dot.column\""));
            assertEquals(1, sources.size());
            Column source = sources.iterator().next();
            assertEquals(database + ".PUBLIC.\"dot.table\"", source.getTableName());
            assertEquals("dot.column", source.getColumnName());
        }
    }

    @Test
    public void instanceQualifiedWildcardChainKeepsLineage() throws Exception {
        try (Connection connection = h2("instance_chain")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE \"A\" (\"c1\" INT)");
                statement.execute("CREATE TABLE \"B\" (\"c1\" INT)");
                statement.execute("CREATE TABLE \"C\" (\"c1\" INT)");
            }

            MetadataCatalog catalog = new JdbcMetadataScanner(DbType.mysql, "my_instance")
                    .scan(connection);
            String database = connection.getCatalog();
            MetadataContext context = MetadataContext.of(catalog, "my_instance", database, null);

            ScanResult result = new LineageScanner(context).scan(
                    "INSERT INTO B(c1) SELECT * FROM A;"
                            + "INSERT INTO C(c1) SELECT c1 FROM B;");

            assertEquals(2, result.getResults().size());
            assertEquals(0, result.getFailures().size());

            String prefix = "my_instance." + database + ".";
            Set<Column> sources = result.getGraph()
                    .getSources(new Column(prefix + "C.c1"));
            assertEquals(1, sources.size());
            assertTrue(sources.contains(new Column(prefix + "A.c1")));
        }
    }

    @Test
    public void postgresJdbcColumnQuotingIsInferred() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:lineage_pgquoted_" + System.nanoTime()
                        + ";DATABASE_TO_LOWER=TRUE")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE a (\"C1\" INT, c1 INT)");
                statement.execute("CREATE TABLE b (c1 INT, c3 INT)");
            }

            MetadataCatalog catalog = new JdbcMetadataScanner(DbType.postgresql)
                    .scan(connection);
            Table table = catalog.findAllTables("a").get(0).getTable();
            assertTrue(table.getColumnExact("C1").isQuoted());
            assertFalse(table.getColumnExact("c1").isQuoted());

            MetadataContext context = MetadataContext.of(catalog, null,
                    connection.getCatalog(), "public");
            ScanResult scan = new LineageScanner(context)
                    .scan("SELECT c1 FROM a LEFT JOIN b USING(c1);");

            assertEquals(0, scan.getFailures().size());
            Set<Column> sources = scan.getGraph()
                    .getSources(Column.temporary("stmt0.temp0.c1"));
            assertEquals(1, sources.size());
            assertEquals(connection.getCatalog() + ".public.a",
                    sources.iterator().next().getTableName());
            assertEquals("c1", sources.iterator().next().getColumnName());
        }
    }

    @Test
    public void scannedMetadataFeedsTheLineageScannerEndToEnd() throws Exception {
        try (Connection connection = h2("e2e")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE A (c1 INT)");
            }

            MetadataCatalog catalog = new JdbcMetadataScanner(DbType.h2).scan(connection);
            String database = connection.getCatalog();
            MetadataContext context = MetadataContext.of(catalog, null, database, "PUBLIC");

            ScanResult result = new LineageScanner(context).scan("SELECT c1 FROM A;");

            assertEquals(1, result.getResults().size());
            assertEquals(0, result.getFailures().size());
            assertEquals(database + ".PUBLIC.A.C1",
                    result.getResults().get(0).getInstructions().get(0).getFrom());
            assertEquals(catalog.getVersion(), context.getMetadataVersion());
            assertEquals(catalog.getChecksum(), context.getMetadataChecksum());
        }
    }

    private static Connection h2(String name) throws Exception {
        return DriverManager.getConnection(
                "jdbc:h2:mem:lineage_" + name + "_" + System.nanoTime());
    }
}

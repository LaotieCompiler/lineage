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

import org.junit.Assume;
import org.junit.Test;

import com.alibaba.druid.DbType;
import com.laotie.LineageScanner;
import com.laotie.exception.MetadataPersistenceException;
import com.laotie.model.ScanResult;

/**
 * Integration tests against real database instances started with Docker.
 *
 * <p>
 * They are skipped unless the matching system properties are provided. Run them with
 * {@code scripts/run-jdbc-docker-tests.sh}. Each test creates and drops uniquely named objects,
 * so it never deletes pre-existing tables on the target database.
 *
 * <p>
 * When a SOCKS proxy is configured in the JVM, pass
 * {@code -DsocksProxyHost= -Dhttp.proxyHost= -Dhttps.proxyHost=} to the test JVM; the
 * PostgreSQL driver would otherwise route localhost connections through the proxy.
 */
public class JdbcMetadataScannerDockerTest {

    @Test
    public void scansMySqlInstance() throws Exception {
        String url = System.getProperty("lineage.mysql.url");
        Assume.assumeTrue("set -Dlineage.mysql.url to enable the MySQL test", url != null);
        String user = System.getProperty("lineage.mysql.user", "root");
        String password = System.getProperty("lineage.mysql.password", "lineage");

        String suffix = Long.toString(System.nanoTime());
        String tableA = "lineage_it_a_" + suffix;
        String tableB = "lineage_it_b_" + suffix;
        String view = "lineage_it_v_" + suffix;
        String chainA = "lineage_it_ca_" + suffix;
        String chainB = "lineage_it_cb_" + suffix;
        String chainC = "lineage_it_cc_" + suffix;

        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            try {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("CREATE TABLE " + tableA
                            + " (c1 INT, c2 VARCHAR(10), doc JSON)");
                    statement.execute("CREATE TABLE " + tableB + " (c3 BIGINT)");
                    statement.execute("CREATE VIEW " + view + " AS SELECT c1 FROM " + tableA);
                    statement.execute("CREATE TABLE " + chainA + " (`C1` INT)");
                    statement.execute("CREATE TABLE " + chainB + " (`C1` INT)");
                    statement.execute("CREATE TABLE " + chainC + " (c1 INT)");
                }

                MetadataCatalog catalog = new JdbcMetadataScanner(DbType.mysql, "mysql_docker")
                        .scan(connection);
                String database = connection.getCatalog();

                List<TableLocation> matches = catalog.findAllTables(tableA);
                assertEquals(1, matches.size());
                TableLocation a = matches.get(0);
                assertEquals("mysql_docker", a.getInstance());
                assertEquals(database, a.getDatabase());
                assertNull(a.getSchema());
                assertNotNull(a.getTable().getColumn("c1"));
                assertEquals("JSON", a.getTable().getColumn("doc").getDataType());

                List<TableLocation> viewMatches = catalog.findAllTables(view);
                assertEquals(1, viewMatches.size());
                assertNotNull(viewMatches.get(0).getTable().getColumn("c1"));

                assertNoSystemNamespace(catalog);

                MetadataContext context = MetadataContext.of(catalog, "mysql_docker", database,
                        null);
                ScanResult scan = new LineageScanner(context).scan(
                        "INSERT INTO " + chainB + " SELECT * FROM " + chainA + ";"
                                + "INSERT INTO " + chainC + "(c1) SELECT `c1` FROM " + chainB + ";");
                assertEquals(2, scan.getResults().size());
                assertEquals(0, scan.getFailures().size());
                Set<Column> sources = scan.getGraph().getSources(new Column(
                        "mysql_docker." + database + "." + chainC + ".c1"));
                assertEquals(1, sources.size());
                assertTrue(sources.contains(new Column(
                        "mysql_docker." + database + "." + chainA + ".C1")));

                // MySQL with an instance and no schema has no DDL representation.
                assertThrows(MetadataPersistenceException.class, catalog::toDdl);
            } finally {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("DROP VIEW IF EXISTS " + view);
                    statement.execute("DROP TABLE IF EXISTS " + tableA);
                    statement.execute("DROP TABLE IF EXISTS " + tableB);
                    statement.execute("DROP TABLE IF EXISTS " + chainA);
                    statement.execute("DROP TABLE IF EXISTS " + chainB);
                    statement.execute("DROP TABLE IF EXISTS " + chainC);
                }
            }
        }
    }

    @Test
    public void scansPostgresInstanceWithInstanceName() throws Exception {
        String url = System.getProperty("lineage.postgres.url");
        Assume.assumeTrue("set -Dlineage.postgres.url to enable the PostgreSQL test", url != null);
        String user = System.getProperty("lineage.postgres.user", "postgres");
        String password = System.getProperty("lineage.postgres.password", "lineage");

        String suffix = Long.toString(System.nanoTime());
        String tableA = "lineage_it_a_" + suffix;
        String tableB = "lineage_it_b_" + suffix;
        String view = "lineage_it_v_" + suffix;
        String parent = "lineage_it_p_" + suffix;
        String child = "lineage_it_pc_" + suffix;

        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            try {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("CREATE TABLE " + tableA
                            + " (c1 INT, c2 VARCHAR(10), doc JSONB)");
                    statement.execute("CREATE TABLE " + tableB + " (c3 BIGINT)");
                    statement.execute("CREATE VIEW " + view + " AS SELECT c1 FROM " + tableA);
                    statement.execute("CREATE TABLE " + parent
                            + " (id INT, v VARCHAR(10)) PARTITION BY RANGE (id)");
                    statement.execute("CREATE TABLE " + child + " PARTITION OF " + parent
                            + " FOR VALUES FROM (0) TO (10)");
                }

                MetadataCatalog catalog = new JdbcMetadataScanner(DbType.postgresql, "pg-docker")
                        .scan(connection);
                String database = connection.getCatalog();

                TableLocation a = catalog.findTable(
                        "pg-docker." + database + ".public." + tableA);
                assertEquals("pg-docker", a.getInstance());
                assertEquals(database, a.getDatabase());
                assertEquals("public", a.getSchema());
                assertNotNull(a.getTable().getColumn("c1"));
                assertEquals("jsonb", a.getTable().getColumn("doc").getDataType());

                // A two-part reference uses schema.table and still resolves to the full path.
                TableLocation twoPart = catalog.findTable("public." + tableA);
                assertEquals("public", twoPart.getSchema());
                assertEquals(database, twoPart.getDatabase());
                assertEquals("pg-docker", twoPart.getInstance());

                TableLocation viewLocation = catalog.findTable(
                        "pg-docker." + database + ".public." + view);
                assertNotNull(viewLocation.getTable().getColumn("c1"));

                TableLocation parentLocation = catalog.findTable(
                        "pg-docker." + database + ".public." + parent);
                assertNotNull(parentLocation.getTable().getColumn("id"));
                assertNotNull(parentLocation.getTable().getColumn("v"));
                TableLocation childLocation = catalog.findTable(
                        "pg-docker." + database + ".public." + child);
                assertNotNull(childLocation.getTable().getColumn("id"));

                assertNoSystemNamespace(catalog);
                // PostgreSQL paths with instance + database + schema are DDL representable.
                assertNotNull(catalog.toDdl());
            } finally {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("DROP VIEW IF EXISTS " + view);
                    statement.execute("DROP TABLE IF EXISTS " + parent + " CASCADE");
                    statement.execute("DROP TABLE IF EXISTS " + child + " CASCADE");
                    statement.execute("DROP TABLE IF EXISTS " + tableA + " CASCADE");
                    statement.execute("DROP TABLE IF EXISTS " + tableB + " CASCADE");
                }
            }
        }
    }

    private static void assertNoSystemNamespace(MetadataCatalog catalog) {
        for (Instance instance : catalog.getInstances()) {
            for (Database database : instance.getDatabases()) {
                String name = database.getName() == null ? "" : database.getName().toLowerCase();
                assertFalse("system database leaked: " + name,
                        "information_schema".equals(name) || "performance_schema".equals(name)
                                || "mysql".equals(name) || "sys".equals(name));
                for (Schema schema : database.getSchemas()) {
                    String schemaName = schema.getName() == null
                            ? "" : schema.getName().toLowerCase();
                    assertFalse("system schema leaked: " + schemaName,
                            "information_schema".equals(schemaName)
                                    || "pg_catalog".equals(schemaName)
                                    || "pg_toast".equals(schemaName)
                                    || "sys".equals(schemaName)
                                    || "system".equals(schemaName));
                }
            }
        }
    }
}

package com.laotie.model.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.alibaba.druid.DbType;
import com.laotie.exception.MetadataPersistenceException;

/**
 * Specification for persisting metadata as DDL text.
 *
 * <p>
 * The saved file is self-describing:
 *
 * <pre>
 * -- lineage-metadata
 * -- dialect: mysql
 * -- version: 1
 * -- checksum: &lt;sha-256 hex of the DDL body&gt;
 * CREATE TABLE ...
 * </pre>
 *
 * <p>
 * Canonical body rules (used for the checksum):
 * <ul>
 *   <li>one {@code CREATE TABLE <path> (<col> <type>, ...);} per table, one per line</li>
 *   <li>databases with no tables are emitted as {@code CREATE DATABASE <path>;}; tables imply
 *       their database</li>
 *   <li>database lines come first, then table lines; each group sorted by non-null path</li>
 *   <li>columns kept in their declared order</li>
 *   <li>identifiers that are not simple names are quoted: backticks for MySQL/MariaDB,
 *       double quotes for the other dialects</li>
 *   <li>body ends with a newline; the checksum covers exactly the body, not the header</li>
 * </ul>
 * For example, {@code CREATE TABLE db1.A (c1 INT, c2 VARCHAR(10));} followed by a newline has
 * SHA-256 {@code 1c96eab7602f368493946dd7e5c97b9539e4a204a714c3e91a9f0f34d8f31d3d}.
 *
 * <p>
 * Expected to be red until the catalog gains versioning, DDL persistence and strict loading.
 */
public class MetadataPersistenceTest {

    private static final String SHA256_DB1_A = "1c96eab7602f368493946dd7e5c97b9539e4a204a714c3e91a9f0f34d8f31d3d";
    private static final String SHA256_DB1_A_DB2_B =
            "8b04565abab5beb29fb04365f70ae6ad194419b28b9c4e8ce1b224408cb78bfe";
    private static final String SHA256_EMPTY =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final String SHA256_EMPTY_DATABASE =
            "ef5bfafad4d0db3653598c4d397518a516cd6b229accd336236eb736cfb24355";

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    /* ------------------------------------------------------------------ */
    /* round trip                                                         */
    /* ------------------------------------------------------------------ */

    @Test
    public void toDdlRoundTripsHierarchyFieldsAndDataTypes() {
        MetadataCatalog original = new MetadataCatalog(DbType.mysql);
        original.loadDdl("CREATE TABLE db1.A (c1 INT, c2 VARCHAR(10));"
                + "CREATE TABLE db1.sch1.B (x DECIMAL(10,2));"
                + "CREATE TABLE ins1.db1.sch1.C (y BIGINT);");

        String ddl = original.toDdl();

        MetadataCatalog loaded = new MetadataCatalog(DbType.mysql);
        loaded.loadDdl(ddl);

        assertEquals(original.getChecksum(), loaded.getChecksum());

        Table tableA = loaded.findTable("db1.A").getTable();
        assertEquals("INT", tableA.getColumn("c1").getDataType());
        assertEquals("VARCHAR(10)", tableA.getColumn("c2").getDataType());
        assertEquals("DECIMAL(10, 2)", loaded.findTable("db1.sch1.B").getTable()
                .getColumn("x").getDataType());
        assertEquals("BIGINT", loaded.findTable("ins1.db1.sch1.C").getTable()
                .getColumn("y").getDataType());
    }

    @Test
    public void saveAndLoadFileRoundTripPreservesSnapshotVersion() throws IOException {
        MetadataCatalog original = new MetadataCatalog(DbType.mysql);
        original.loadDdl("CREATE TABLE db1.A (c1 INT);");
        original.loadDdl("CREATE TABLE db2.B (c2 INT);");

        Path file = temporaryFolder.newFile("metadata.sql").toPath();
        original.saveDdl(file);

        MetadataCatalog loaded = MetadataCatalog.fromDdlFile(file);

        assertEquals(original.getChecksum(), loaded.getChecksum());
        assertEquals(original.getVersion(), loaded.getVersion());
        assertEquals("db1", loaded.findTable("db1.A").getDatabase());
    }

    @Test
    public void postgresTwoPartNameRoundTripsAsSchemaTable() throws IOException {
        MetadataCatalog original = new MetadataCatalog(DbType.postgresql);
        original.loadDdl("CREATE TABLE public.A (c1 INT);");

        Path file = temporaryFolder.newFile("pg.sql").toPath();
        original.saveDdl(file);

        MetadataCatalog loaded = MetadataCatalog.fromDdlFile(file);
        TableLocation location = loaded.findTable("public.A");
        assertNull(location.getDatabase());
        assertEquals("public", location.getSchema());
        assertEquals(original.getChecksum(), loaded.getChecksum());
    }

    @Test
    public void quotedIdentifiersWithSpacesAndDotsRoundTrip() throws IOException {
        MetadataCatalog original = new MetadataCatalog(DbType.postgresql);
        original.loadDdl("CREATE TABLE \"a b\".\"A.B\" (c1 INT);");

        Path file = temporaryFolder.newFile("quoted.sql").toPath();
        original.saveDdl(file);

        MetadataCatalog loaded = MetadataCatalog.fromDdlFile(file);
        TableLocation location = loaded.findTable("\"a b\".\"A.B\"");

        assertEquals("a b", location.getSchema());
        assertEquals("A.B", location.getTable().getName());
        assertEquals("\"a b\".\"A.B\"", location.getDisplayName());
        assertEquals(original.getChecksum(), loaded.getChecksum());
    }

    @Test
    public void explicitDefaultNamesSurviveRoundTripWithoutBecomingNull() {
        MetadataCatalog original = new MetadataCatalog(DbType.mysql);
        original.loadDdl("CREATE TABLE A (c1 INT);"
                + "CREATE TABLE default.A (c1 INT);"
                + "CREATE TABLE db1.default.A (c2 INT);"
                + "CREATE TABLE default.default.default.A (c3 INT);");

        MetadataCatalog loaded = new MetadataCatalog(DbType.mysql);
        loaded.loadDdl(original.toDdl());

        assertEquals(original.getChecksum(), loaded.getChecksum());

        List<TableLocation> matches = loaded.findAllTables("A");
        assertEquals(4, matches.size());
        assertEquals(displayNames("A", "default.A", "db1.default.A", "default.default.default.A"),
                displayNamesOf(matches));
    }

    /* ------------------------------------------------------------------ */
    /* checksum                                                           */
    /* ------------------------------------------------------------------ */

    @Test
    public void checksumMatchesKnownSha256ForCanonicalDdl() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.loadDdl("CREATE TABLE db1.A (c1 INT, c2 VARCHAR(10));");

        assertEquals(SHA256_DB1_A, catalog.getChecksum());
    }

    @Test
    public void checksumSortsTablesIndependentlyOfLoadOrder() {
        MetadataCatalog oneCall = new MetadataCatalog(DbType.mysql);
        oneCall.loadDdl("CREATE TABLE db2.B (x BIGINT); CREATE TABLE db1.A (c1 INT, c2 VARCHAR(10));");

        MetadataCatalog twoCalls = new MetadataCatalog(DbType.mysql);
        twoCalls.loadDdl("CREATE TABLE db1.A (c1 INT, c2 VARCHAR(10));");
        twoCalls.loadDdl("CREATE TABLE db2.B (x BIGINT);");

        assertEquals(SHA256_DB1_A_DB2_B, oneCall.getChecksum());
        assertEquals(oneCall.getChecksum(), twoCalls.getChecksum());
        assertEquals(1, oneCall.getVersion());
        assertEquals(2, twoCalls.getVersion());
    }

    @Test
    public void emptyCatalogHasKnownEmptyChecksum() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        assertEquals(SHA256_EMPTY, catalog.getChecksum());
        assertEquals(0, catalog.getVersion());
    }

    @Test
    public void fileBodySha256MatchesCatalogAndHeaderChecksum() throws Exception {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.loadDdl("CREATE TABLE db1.A (c1 INT, c2 VARCHAR(10));"
                + "CREATE TABLE db2.B (x BIGINT);");

        Path file = temporaryFolder.newFile("checksum.sql").toPath();
        catalog.saveDdl(file);
        String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);

        // Strip the leading "-- ..." header lines; the checksum covers the body only.
        String body = content.replaceAll("(?s)\\A(-- [^\\n]*\\n)+", "");
        String bodyHash = sha256Hex(body);
        assertEquals(catalog.getChecksum(), bodyHash);
        assertEquals(catalog.getChecksum(), headerChecksum(content));
    }

    @Test
    public void savedFileHasStrictHeaderLayout() throws IOException {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE public.A (c1 INT);");

        Path file = temporaryFolder.newFile("header.sql").toPath();
        catalog.saveDdl(file);
        String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        String[] lines = content.split("\n", -1);

        assertEquals("-- lineage-metadata", lines[0]);
        assertEquals("-- dialect: postgresql", lines[1]);
        assertTrue(lines[2].matches("-- version: \\d+"));
        assertTrue(lines[3].matches("-- checksum: [0-9a-f]{64}"));
        assertTrue(lines[4].startsWith("CREATE TABLE"));
        assertEquals("-- version: " + catalog.getVersion(), lines[2]);
        assertEquals("-- checksum: " + catalog.getChecksum(), lines[3]);
    }

    @Test
    public void checksumMismatchLogsWarningAndStillLoads() throws IOException {
        MetadataCatalog original = new MetadataCatalog(DbType.mysql);
        original.loadDdl("CREATE TABLE db1.A (c1 INT);");

        Path file = temporaryFolder.newFile("tampered.sql").toPath();
        original.saveDdl(file);

        String originalContent = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        // Change the body without updating the checksum in the header.
        String tampered = originalContent.replace("c1 INT", "c1 BIGINT");
        assertNotEquals(originalContent, tampered);
        Files.write(file, tampered.getBytes(StandardCharsets.UTF_8));

        List<LogRecord> records = new ArrayList<>();
        Logger logger = Logger.getLogger(MetadataCatalog.class.getName());
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        handler.setLevel(Level.WARNING);
        logger.addHandler(handler);
        try {
            MetadataCatalog loaded = MetadataCatalog.fromDdlFile(file);

            assertEquals("BIGINT", loaded.findTable("db1.A").getTable()
                    .getColumn("c1").getDataType());
            assertNotEquals(original.getChecksum(), loaded.getChecksum());
            // The header still carries the original snapshot checksum.
            assertEquals(original.getChecksum(), headerChecksum(tampered));
        } finally {
            logger.removeHandler(handler);
        }

        boolean warned = false;
        for (LogRecord record : records) {
            if (record.getLevel() == Level.WARNING
                    && record.getMessage().toLowerCase().contains("checksum")) {
                warned = true;
            }
        }
        assertTrue("expected a checksum mismatch warning", warned);
    }

    /* ------------------------------------------------------------------ */
    /* failure modes                                                      */
    /* ------------------------------------------------------------------ */

    @Test
    public void emptyFileIsRejected() throws IOException {
        Path file = temporaryFolder.newFile("empty.sql").toPath();
        assertThrows(MetadataPersistenceException.class, () -> MetadataCatalog.fromDdlFile(file));
    }

    @Test
    public void fileWithoutHeaderRequiresExplicitDialect() throws IOException {
        Path file = temporaryFolder.newFile("noheader.sql").toPath();
        Files.write(file, "CREATE TABLE db1.A (c1 INT);".getBytes(StandardCharsets.UTF_8));

        assertThrows(MetadataPersistenceException.class, () -> MetadataCatalog.fromDdlFile(file));

        MetadataCatalog loaded = MetadataCatalog.fromDdlFile(file, DbType.mysql);
        assertEquals("db1", loaded.findTable("db1.A").getDatabase());
    }

    @Test
    public void duplicateHeaderFieldIsRejected() throws IOException {
        Path file = temporaryFolder.newFile("dup.sql").toPath();
        // Empty body so the checksum is valid; the duplicate dialect header is the only error.
        Files.write(file, ("-- lineage-metadata\n"
                + "-- dialect: mysql\n"
                + "-- dialect: postgresql\n"
                + "-- version: 1\n"
                + "-- checksum: " + SHA256_EMPTY + "\n").getBytes(StandardCharsets.UTF_8));

        assertThrows(MetadataPersistenceException.class, () -> MetadataCatalog.fromDdlFile(file));
    }

    @Test
    public void invalidDdlIsRejectedAtomically() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.loadDdl("CREATE TABLE db1.A (c1 INT);");

        assertThrows(MetadataPersistenceException.class,
                () -> catalog.loadDdl("CREATE TABLE db2.B (c2 INT); THIS IS NOT SQL"));

        // The failed batch must not leave a partial db2.B behind.
        assertTrue(catalog.findAllTables("db2.B").isEmpty());
        assertEquals(1, catalog.getVersion());
    }

    @Test
    public void emptyDatabaseRoundTrips() throws IOException {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.loadDdl("CREATE DATABASE db1;");

        assertEquals(SHA256_EMPTY_DATABASE, catalog.getChecksum());

        Path file = temporaryFolder.newFile("emptydb.sql").toPath();
        catalog.saveDdl(file);
        MetadataCatalog loaded = MetadataCatalog.fromDdlFile(file);

        assertNotNull(loaded.getInstance(null).getDatabase("db1"));
        assertEquals(catalog.getChecksum(), loaded.getChecksum());
        assertEquals(catalog.getVersion(), loaded.getVersion());
    }

    @Test
    public void mysqlPathWithSchemaButNoDatabaseIsRejectedAtRegistration() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.loadDdl("CREATE TABLE db9.K (k INT);");
        long version = catalog.getVersion();
        String checksum = catalog.getChecksum();
        String ddl = catalog.toDdl();
        assertNull(catalog.getInstance(null).getDatabase(null));

        // MySQL two-part names mean database.table; an unset database with an explicit schema
        // cannot be represented in DDL text.
        assertThrows(MetadataPersistenceException.class,
                () -> catalog.addTable(new String[] {null, null, "sch1", "A"},
                        Arrays.asList(new Column("c1"))));

        assertTrue(catalog.findAllTables("A").isEmpty());
        assertUnchanged(catalog, version, checksum, ddl);
        assertNull(catalog.getInstance(null).getDatabase(null));
    }

    @Test
    public void mysqlPathWithInstanceButNoSchemaIsRejectedAtRegistration() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.loadDdl("CREATE TABLE db9.K (k INT);");
        long version = catalog.getVersion();
        String checksum = catalog.getChecksum();
        String ddl = catalog.toDdl();

        // A four-part path needs all four levels.
        assertThrows(MetadataPersistenceException.class,
                () -> catalog.addTable("ins1", "db1", null, "A",
                        Arrays.asList(new Column("c1"))));

        assertTrue(catalog.findAllTables("A").isEmpty());
        assertUnchanged(catalog, version, checksum, ddl);
        assertNull(catalog.getInstance("ins1"));
    }

    @Test
    public void postgresPathWithDatabaseButNoSchemaIsRejectedAtRegistration() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE db9.K (k INT);");
        long version = catalog.getVersion();
        String checksum = catalog.getChecksum();
        String ddl = catalog.toDdl();

        // PostgreSQL three-part names need a schema between database and table.
        assertThrows(MetadataPersistenceException.class,
                () -> catalog.addTable(null, "db1", null, "A",
                        Arrays.asList(new Column("c1"))));

        assertTrue(catalog.findAllTables("A").isEmpty());
        assertUnchanged(catalog, version, checksum, ddl);
        assertNull(catalog.getInstance(null).getDatabase("db1"));
    }

    private static void assertUnchanged(MetadataCatalog catalog, long version,
            String checksum, String ddl) {
        assertEquals(version, catalog.getVersion());
        assertEquals(checksum, catalog.getChecksum());
        assertEquals(ddl, catalog.toDdl());
    }

    /* ------------------------------------------------------------------ */
    /* version semantics                                                  */
    /* ------------------------------------------------------------------ */

    @Test
    public void versionStartsAtZeroAndIncrementsOncePerLoadCall() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        assertEquals(0, catalog.getVersion());

        catalog.loadDdl("CREATE TABLE db1.A (c1 INT); CREATE TABLE db2.B (c2 INT);");
        assertEquals(1, catalog.getVersion());

        catalog.loadDdl("CREATE TABLE db3.C (c3 INT);");
        assertEquals(2, catalog.getVersion());
    }

    @Test
    public void ignoredStatementsDoNotIncrementVersion() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.loadDdl("INSERT INTO A VALUES (1);");
        assertEquals(0, catalog.getVersion());
    }

    @Test
    public void failedLoadDoesNotIncrementVersion() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.loadDdl("CREATE TABLE db1.A (c1 INT);");
        long before = catalog.getVersion();

        assertThrows(MetadataPersistenceException.class, () -> catalog.loadDdl("BROKEN SQL"));

        assertEquals(before, catalog.getVersion());
    }

    @Test
    public void duplicateLoadStillCountsAsAMutation() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.loadDdl("CREATE TABLE db1.A (c1 INT);");
        catalog.loadDdl("CREATE TABLE db1.A (c1 INT);");
        assertEquals(2, catalog.getVersion());
    }

    @Test
    public void saveThenMutateKeepsTheSavedSnapshot() throws IOException {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.loadDdl("CREATE TABLE db1.A (c1 INT);");

        Path file = temporaryFolder.newFile("snapshot.sql").toPath();
        catalog.saveDdl(file);
        long savedVersion = catalog.getVersion();
        String savedChecksum = catalog.getChecksum();

        catalog.loadDdl("CREATE TABLE db2.B (c2 INT);");
        assertNotEquals(savedChecksum, catalog.getChecksum());

        MetadataCatalog loaded = MetadataCatalog.fromDdlFile(file);
        assertEquals(savedVersion, loaded.getVersion());
        assertEquals(savedChecksum, loaded.getChecksum());
    }

    /* ------------------------------------------------------------------ */
    /* helpers                                                            */
    /* ------------------------------------------------------------------ */

    private static String sha256Hex(String text) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(String.format("%02x", b & 0xff));
        }
        return hex.toString();
    }

    private static String headerChecksum(String content) {
        for (String line : content.split("\n")) {
            if (line.startsWith("-- checksum: ")) {
                return line.substring("-- checksum: ".length()).trim();
            }
        }
        return null;
    }

    private static Set<String> displayNames(String... names) {
        return new HashSet<>(Arrays.asList(names));
    }

    private static Set<String> displayNamesOf(List<TableLocation> locations) {
        Set<String> names = new HashSet<>();
        for (TableLocation location : locations) {
            names.add(location.getDisplayName());
        }
        return names;
    }
}

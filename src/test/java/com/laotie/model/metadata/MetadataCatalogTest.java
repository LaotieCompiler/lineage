package com.laotie.model.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.alibaba.druid.DbType;
import com.laotie.exception.AmbiguousTableException;
import com.laotie.exception.MetadataNotFoundException;

/**
 * Specification for the metadata catalog.
 *
 * <p>
 * Hierarchy: Instance -> Database -> Schema -> Table -> Field.
 *
 * <p>
 * Qualified name mapping:
 * <ul>
 *   <li>1 part: table</li>
 *   <li>2 parts: dialect aware
 *     <ul>
 *       <li>MySQL/MariaDB: database.table, schema is unset</li>
 *       <li>PostgreSQL/Oracle/SQL Server/Hive: schema.table, database is unset</li>
 *     </ul>
 *   </li>
 *   <li>3 parts: database.schema.table, instance is unset</li>
 *   <li>4 parts: instance.database.schema.table</li>
 * </ul>
 *
 * <p>
 * Missing levels are stored as {@code null}, never as the literal {@code "default"}.
 * A real object may therefore be named "default" without colliding with an unset level.
 * Lookups treat unset reference levels as wildcards unless a default instance/database/schema
 * is configured, in which case the default narrows the match.
 *
 * <p>
 * NOTE: this test targets the yet-to-be-refactored API ({@code Instance}/{@code Database}/
 * {@code Schema} nesting, null levels and default narrowing). It is expected to be red until
 * that refactor is implemented.
 */
public class MetadataCatalogTest {

    /* ------------------------------------------------------------------ */
    /* hierarchy                                                          */
    /* ------------------------------------------------------------------ */

    @Test
    public void loadDdlBuildsInstanceDatabaseSchemaTableFieldHierarchyForMySql() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.loadDdl("CREATE TABLE db1.A (c1 INT, c2 VARCHAR(10));");

        Instance instance = catalog.getInstance(null);
        assertNotNull(instance);

        // MySQL two-part name means database.table; schema is unset.
        Database database = instance.getDatabase("db1");
        assertNotNull(database);
        Schema schema = database.getSchema(null);
        assertNotNull(schema);

        Table table = schema.getTable("A");
        assertNotNull(table);
        assertEquals(2, table.getFields().size());
        assertTrue(table.hasColumn("c1"));
        assertTrue(table.hasColumn("C2"));
        assertEquals("INT", table.getColumn("c1").getDataType());
        assertEquals("VARCHAR(10)", table.getColumn("c2").getDataType());
    }

    @Test
    public void fourPartNameMapsToInstanceDatabaseSchemaTable() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.loadDdl("CREATE TABLE ins1.db1.sch1.C (x DECIMAL(10,2));");

        TableLocation location = catalog.findTable("ins1.db1.sch1.C");
        assertEquals("ins1", location.getInstance());
        assertEquals("db1", location.getDatabase());
        assertEquals("sch1", location.getSchema());
        assertEquals("C", location.getTable().getName());
        assertEquals("ins1.db1.sch1.C", location.getQualifiedName());
        assertEquals("ins1.db1.sch1.C", location.getDisplayName());
    }

    @Test
    public void threePartNameMapsToDatabaseSchemaTable() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.loadDdl("CREATE TABLE db1.sch1.C (x INT);");

        TableLocation location = catalog.findTable("db1.sch1.C");
        assertNull(location.getInstance());
        assertEquals("db1", location.getDatabase());
        assertEquals("sch1", location.getSchema());
        assertEquals("C", location.getTable().getName());
        assertEquals("db1.sch1.C", location.getDisplayName());
    }

    @Test
    public void twoPartNameIsDatabaseTableInMySql() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.loadDdl("CREATE TABLE db1.A (c1 INT);");

        TableLocation location = catalog.findTable("db1.A");
        assertNull(location.getInstance());
        assertEquals("db1", location.getDatabase());
        assertNull(location.getSchema());
        assertEquals("A", location.getTable().getName());
        assertEquals("db1.A", location.getDisplayName());
    }

    @Test
    public void twoPartNameIsSchemaTableInPostgres() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE public.A (c1 INT);");

        TableLocation location = catalog.findTable("public.A");
        assertNull(location.getInstance());
        assertNull(location.getDatabase());
        assertEquals("public", location.getSchema());
        assertEquals("A", location.getTable().getName());
        assertEquals("public.A", location.getDisplayName());
    }

    @Test
    public void twoPartNameIsSchemaTableInHive() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.hive);
        catalog.loadDdl("CREATE TABLE analytics.A (c1 INT);");

        TableLocation location = catalog.findTable("analytics.A");
        assertNull(location.getDatabase());
        assertEquals("analytics", location.getSchema());
        assertEquals("analytics.A", location.getDisplayName());
    }

    @Test
    public void twoPartNameIsDatabaseTableInMariaDb() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mariadb);
        catalog.loadDdl("CREATE TABLE db1.A (c1 INT);");

        TableLocation location = catalog.findTable("db1.A");
        assertEquals("db1", location.getDatabase());
        assertNull(location.getSchema());
    }

    @Test
    public void twoPartNameIsSchemaTableInOracle() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.oracle);
        catalog.loadDdl("CREATE TABLE sch1.A (c1 INT);");

        TableLocation location = catalog.findTable("sch1.A");
        assertNull(location.getDatabase());
        assertEquals("sch1", location.getSchema());
    }

    @Test
    public void twoPartNameIsSchemaTableInSqlServer() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.sqlserver);
        catalog.loadDdl("CREATE TABLE [dbo].[A] (c1 INT);");

        TableLocation location = catalog.findTable("dbo.A");
        assertNull(location.getDatabase());
        assertEquals("dbo", location.getSchema());
    }

    @Test
    public void threePartNameIsDatabaseSchemaTableInPostgres() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE sales.public.A (c1 INT);");

        TableLocation location = catalog.findTable("sales.public.A");
        assertNull(location.getInstance());
        assertEquals("sales", location.getDatabase());
        assertEquals("public", location.getSchema());
        assertEquals("sales.public.A", location.getDisplayName());
    }

    @Test
    public void createDatabaseStatementCreatesDatabaseNode() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.loadDdl("CREATE DATABASE db9;");

        Database database = catalog.getInstance(null).getDatabase("db9");
        assertNotNull(database);
    }

    /* ------------------------------------------------------------------ */
    /* null levels vs real "default" names                                */
    /* ------------------------------------------------------------------ */

    @Test
    public void realDatabaseNamedDefaultDoesNotCollideWithUnsetDatabase() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.loadDdl("CREATE TABLE A (c1 INT);"
                + "CREATE TABLE default.A (c1 INT);");

        List<TableLocation> all = catalog.findAllTables("A");
        assertEquals(2, all.size());

        TableLocation unsetDatabase = findByDatabase(all, null);
        TableLocation namedDefault = findByDatabase(all, "default");
        assertNotNull(unsetDatabase);
        assertNotNull(namedDefault);
        assertEquals("A", unsetDatabase.getDisplayName());
        assertEquals("default.A", namedDefault.getDisplayName());

        // "default.A" is an explicit database named default, not the unset level.
        assertEquals(1, catalog.findAllTables("default.A").size());
    }

    @Test
    public void realSchemaNamedDefaultDoesNotCollideWithUnsetSchema() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE A (c1 INT);"
                + "CREATE TABLE default.A (c1 INT);");

        List<TableLocation> all = catalog.findAllTables("A");
        assertEquals(2, all.size());

        TableLocation unsetSchema = findBySchema(all, null);
        TableLocation namedDefault = findBySchema(all, "default");
        assertNotNull(unsetSchema);
        assertNotNull(namedDefault);
        assertEquals("A", unsetSchema.getDisplayName());
        assertEquals("default.A", namedDefault.getDisplayName());
    }

    @Test
    public void quotedDefaultSchemaInPostgresIsExplicit() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE \"default\".A (c1 INT);");

        TableLocation location = catalog.findTable("default.A");
        assertNull(location.getDatabase());
        assertEquals("default", location.getSchema());
        assertEquals("default.A", location.getDisplayName());
    }

    @Test
    public void realInstanceNamedDefaultDoesNotCollideWithUnsetInstance() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.loadDdl("CREATE TABLE A (c1 INT);"
                + "CREATE TABLE default.default.default.A (c1 INT);");

        List<TableLocation> all = catalog.findAllTables("A");
        assertEquals(2, all.size());

        TableLocation unsetInstance = findByInstance(all, null);
        TableLocation namedDefault = findByInstance(all, "default");
        assertNotNull(unsetInstance);
        assertNotNull(namedDefault);
        assertEquals("A", unsetInstance.getDisplayName());
        assertEquals("default.default.default.A", namedDefault.getDisplayName());

        assertEquals(1, catalog.findAllTables("default.default.default.A").size());
    }

    /* ------------------------------------------------------------------ */
    /* lookup                                                             */
    /* ------------------------------------------------------------------ */

    @Test
    public void findAllTablesMatchesByWildcardOnUnsetLevels() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.loadDdl("CREATE TABLE db1.A (c1 INT);"
                + "CREATE TABLE db2.A (c1 INT);"
                + "CREATE TABLE db1.B (c2 INT);");

        assertEquals(2, catalog.findAllTables("A").size());
        // MySQL: database.table
        assertEquals(1, catalog.findAllTables("db1.A").size());
        assertEquals("A", catalog.findAllTables("db1.A").get(0).getTable().getName());
        // Unset schema is not the real name "default".
        assertEquals(0, catalog.findAllTables("db1.default.A").size());
        assertEquals(0, catalog.findAllTables("default.A").size());
    }

    @Test
    public void findTableRejectsAmbiguousReference() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.loadDdl("CREATE TABLE db1.A (c1 INT); CREATE TABLE db2.A (c1 INT);");
        assertThrows(AmbiguousTableException.class, () -> catalog.findTable("A"));
    }

    @Test
    public void findTableRejectsMissingReference() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.loadDdl("CREATE TABLE db1.A (c1 INT);");
        assertThrows(MetadataNotFoundException.class, () -> catalog.findTable("db3.X"));
    }

    @Test
    public void instanceIsPartOfTheIdentity() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.loadDdl("CREATE TABLE ins1.db1.sch1.A (c1 INT);"
                + "CREATE TABLE ins2.db1.sch1.A (c1 INT);");

        assertEquals(2, catalog.findAllTables("db1.sch1.A").size());
        assertEquals(1, catalog.findAllTables("ins1.db1.sch1.A").size());
        assertEquals("ins1", catalog.findTable("ins1.db1.sch1.A").getInstance());
    }

    /* ------------------------------------------------------------------ */
    /* default configuration narrows unset levels                         */
    /* ------------------------------------------------------------------ */

    @Test
    public void defaultDatabaseNarrowsUnqualifiedLookup() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.setDefaultDatabase("db1");
        catalog.loadDdl("CREATE TABLE db1.A (c1 INT); CREATE TABLE db2.A (c1 INT);");

        List<TableLocation> matches = catalog.findAllTables("A");
        assertEquals(1, matches.size());
        assertEquals("db1", matches.get(0).getDatabase());
        assertEquals("db1", catalog.findTable("A").getDatabase());

        // An explicit qualifier always wins over the configured default.
        assertEquals("db2", catalog.findTable("db2.A").getDatabase());
    }

    @Test
    public void defaultSchemaNarrowsUnqualifiedLookup() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.setDefaultSchema("sch1");
        catalog.loadDdl("CREATE TABLE sch1.A (c1 INT); CREATE TABLE public.A (c1 INT);");

        List<TableLocation> matches = catalog.findAllTables("A");
        assertEquals(1, matches.size());
        assertEquals("sch1", matches.get(0).getSchema());

        // An explicit qualifier always wins over the configured default.
        assertEquals("public", catalog.findTable("public.A").getSchema());
    }

    @Test
    public void defaultInstanceNarrowsUnqualifiedLookup() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.setDefaultInstance("ins1");
        catalog.loadDdl("CREATE TABLE ins1.db1.sch1.A (c1 INT);"
                + "CREATE TABLE ins2.db1.sch1.A (c1 INT);");

        List<TableLocation> matches = catalog.findAllTables("A");
        assertEquals(1, matches.size());
        assertEquals("ins1", matches.get(0).getInstance());

        // Unset instance in a three-part reference is narrowed by the default.
        assertEquals(1, catalog.findAllTables("db1.sch1.A").size());
    }

    @Test
    public void defaultsApplyToAllUnsetLevelsTogether() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.setDefaultInstance("ins1");
        catalog.setDefaultDatabase("db1");
        catalog.setDefaultSchema("sch1");
        catalog.loadDdl("CREATE TABLE ins1.db1.sch1.A (c1 INT);"
                + "CREATE TABLE ins2.db1.sch1.A (c1 INT);"
                + "CREATE TABLE ins1.db2.sch1.A (c1 INT);"
                + "CREATE TABLE ins1.db1.sch2.A (c1 INT);");

        List<TableLocation> matches = catalog.findAllTables("A");
        assertEquals(1, matches.size());
        assertEquals("ins1", matches.get(0).getInstance());
        assertEquals("db1", matches.get(0).getDatabase());
        assertEquals("sch1", matches.get(0).getSchema());
    }

    @Test
    public void defaultWithoutCandidatesDoesNotFallBackToWildcard() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.setDefaultDatabase("db3");
        catalog.loadDdl("CREATE TABLE db1.A (c1 INT);");

        assertTrue(catalog.findAllTables("A").isEmpty());
        assertThrows(MetadataNotFoundException.class, () -> catalog.findTable("A"));
    }

    @Test
    public void explicitInstanceOverridesDefaultInstance() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.setDefaultInstance("ins1");
        catalog.loadDdl("CREATE TABLE ins1.db1.sch1.A (c1 INT);"
                + "CREATE TABLE ins2.db1.sch1.A (c1 INT);");

        assertEquals("ins2", catalog.findTable("ins2.db1.sch1.A").getInstance());
    }

    /* ------------------------------------------------------------------ */
    /* names                                                              */
    /* ------------------------------------------------------------------ */

    @Test
    public void qualifiedNamesAreNormalizedAndCaseInsensitive() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.loadDdl("CREATE TABLE `db1`.`A` (`c1` INT);");

        TableLocation location = catalog.findTable("DB1.a");
        assertNull(location.getInstance());
        assertEquals("db1", location.getDatabase());
        assertNull(location.getSchema());
        assertEquals("A", location.getTable().getName());
        assertTrue(location.getTable().hasColumn("C1"));
    }

    @Test
    public void splitQualifiedNameRespectsQuotes() {
        String[] parts = MetadataCatalog.splitQualifiedName("`db.1`.`A`.`c1`");
        assertEquals(3, parts.length);
        assertEquals("db.1", parts[0]);
        assertEquals("A", parts[1]);
        assertEquals("c1", parts[2]);
    }

    @Test
    public void splitQualifiedNameHandlesSpacesAndDotsInsideQuotes() {
        String[] parts = MetadataCatalog.splitQualifiedName("\"a b\".\"A.B\"");
        assertEquals(2, parts.length);
        assertEquals("a b", parts[0]);
        assertEquals("A.B", parts[1]);
    }

    @Test
    public void normalizeIdentifierStripsAllQuoteStyles() {
        assertEquals("A", MetadataCatalog.normalizeIdentifier("`A`"));
        assertEquals("A", MetadataCatalog.normalizeIdentifier("\"A\""));
        assertEquals("A", MetadataCatalog.normalizeIdentifier("[A]"));
        assertEquals("A", MetadataCatalog.normalizeIdentifier("A"));
    }

    /* ------------------------------------------------------------------ */
    /* edge cases                                                         */
    /* ------------------------------------------------------------------ */

    @Test
    public void duplicateTableDefinitionReplacesFields() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.loadDdl("CREATE TABLE db1.A (c1 INT);");
        catalog.loadDdl("CREATE TABLE db1.A (c2 INT);");

        assertEquals(1, catalog.findAllTables("db1.A").size());
        Table table = catalog.findAllTables("db1.A").get(0).getTable();
        assertEquals(1, table.getFields().size());
        assertTrue(table.hasColumn("c2"));
        assertFalse(table.hasColumn("c1"));
    }

    @Test
    public void loadDdlIgnoresUnsupportedStatements() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.loadDdl("CREATE DATABASE db1; INSERT INTO A VALUES (1);");

        assertNotNull(catalog.getInstance(null).getDatabase("db1"));
        assertTrue(catalog.findAllTables("A").isEmpty());
        // CREATE DATABASE is a mutation; INSERT is ignored.
        assertEquals(1, catalog.getVersion());
    }

    /* ------------------------------------------------------------------ */
    /* helpers                                                            */
    /* ------------------------------------------------------------------ */

    private static TableLocation findByDatabase(List<TableLocation> locations, String database) {
        for (TableLocation location : locations) {
            if (database == null ? location.getDatabase() == null
                    : database.equals(location.getDatabase())) {
                return location;
            }
        }
        return null;
    }

    private static TableLocation findBySchema(List<TableLocation> locations, String schema) {
        for (TableLocation location : locations) {
            if (schema == null ? location.getSchema() == null
                    : schema.equals(location.getSchema())) {
                return location;
            }
        }
        return null;
    }

    private static TableLocation findByInstance(List<TableLocation> locations, String instance) {
        for (TableLocation location : locations) {
            if (instance == null ? location.getInstance() == null
                    : instance.equals(location.getInstance())) {
                return location;
            }
        }
        return null;
    }
}

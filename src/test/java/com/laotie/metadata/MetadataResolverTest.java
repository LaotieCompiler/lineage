package com.laotie.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

import com.alibaba.druid.DbType;
import com.laotie.exception.MetadataNotFoundException;
import com.laotie.model.metadata.MetadataCatalog;
import com.laotie.model.metadata.MetadataContext;
import com.laotie.model.metadata.TableLocation;

public class MetadataResolverTest {

    private static MetadataResolver resolver(MetadataCatalog catalog,
            DbType dialect, String database, String rawDatabase,
            java.util.List<String> rawSchemas, boolean detached) {
        return new MetadataResolver(MetadataContext.of(catalog), dialect,
                database, rawDatabase, Collections.<String>emptyList(),
                rawSchemas, detached);
    }

    @Test
    public void quotedSearchPathKeepsCaseDistinctSchema() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE s.a (k INT);"
                + " CREATE TABLE \"S\".a (k INT);");
        TableLocation location = resolver(catalog, DbType.postgresql, null,
                null, Arrays.asList("\"S\""), false).resolve("a");

        assertEquals("S", location.getSchema());
    }

    @Test
    public void sessionDatabaseOverridesCatalogDefault() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.loadDdl("CREATE TABLE db1.a (c INT);"
                + " CREATE TABLE db2.a (c INT);");
        catalog.setDefaultDatabase("db1");
        TableLocation location = resolver(catalog, DbType.mysql, "db2",
                "db2", Collections.<String>emptyList(), false).resolve("a");

        assertEquals("db2", location.getDatabase());
    }

    @Test
    public void hiveUseResolvesDatabaseAsSchema() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.hive);
        catalog.loadDdl("CREATE TABLE db1.a (c INT);"
                + " CREATE TABLE db2.a (c INT);");
        TableLocation location = resolver(catalog, DbType.hive, "db2",
                null, Collections.<String>emptyList(), false).resolve("a");

        assertEquals("db2", location.getSchema());
    }

    @Test
    public void quotedDatabaseMustMatchExactly() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        catalog.loadDdl("CREATE TABLE db1.public.a (k INT);");
        MetadataResolver resolver = resolver(catalog, DbType.postgresql,
                "db1", null, Collections.<String>emptyList(), false);

        assertEquals("db1", resolver.resolve("db1.public.a").getDatabase());
        try {
            resolver.resolve("\"DB1\".public.a");
            fail("quoted DB1 must not match db1");
        } catch (MetadataNotFoundException expected) {
            // expected
        }
    }

    @Test
    public void detachedMissingTableReturnsNull() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        TableLocation location = resolver(catalog, DbType.mysql, null, null,
                Collections.<String>emptyList(), true).resolve("missing");
        assertNull(location);
    }

    @Test
    public void physicalReferenceUsesSessionPrefix() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        MetadataResolver resolver = resolver(catalog, DbType.postgresql, null,
                null, Arrays.asList("s2"), true);
        assertEquals("s2.t", resolver.physicalReference("t"));

        MetadataResolver noSession = resolver(catalog, DbType.postgresql, null,
                null, Collections.<String>emptyList(), true);
        assertEquals("t", noSession.physicalReference("t"));
        assertTrue(noSession.physicalReference("A").equals("a"));
    }
}

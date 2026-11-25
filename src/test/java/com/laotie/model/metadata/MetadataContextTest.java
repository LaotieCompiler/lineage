package com.laotie.model.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.After;
import org.junit.Test;

import com.alibaba.druid.DbType;

/**
 * Specification for the metadata context and its ThreadLocal holder.
 *
 * <p>
 * A context captures an immutable metadata snapshot (version + checksum) at creation time.
 * Mutating the catalog afterwards must not change an existing context, including the catalog view
 * reachable from that context.
 *
 * <p>
 * Expected to be red until {@code MetadataContext}/{@code MetadataContextHolder} exist.
 */
public class MetadataContextTest {

    @After
    public void tearDown() {
        MetadataContextHolder.clear();
    }

    @Test
    public void contextCapturesSnapshotVersionAndChecksum() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.loadDdl("CREATE TABLE db1.A (c1 INT);");

        MetadataContext context = MetadataContext.of(catalog);
        long snapshotVersion = context.getMetadataVersion();
        String snapshotChecksum = context.getMetadataChecksum();

        assertEquals(1, snapshotVersion);
        assertEquals(catalog.getChecksum(), snapshotChecksum);

        catalog.loadDdl("CREATE TABLE db2.B (c2 INT);");

        // The context keeps the snapshot captured at creation time, including its catalog view.
        assertEquals(snapshotVersion, context.getMetadataVersion());
        assertEquals(snapshotChecksum, context.getMetadataChecksum());
        assertNotEquals(catalog.getChecksum(), context.getMetadataChecksum());
        assertNotNull(context.getCatalog());
        assertEquals(snapshotChecksum, context.getCatalog().getChecksum());
        assertEquals(1, context.getCatalog().getVersion());
        assertNull(context.getCatalog().getInstance(null).getDatabase("db2"));
    }

    @Test
    public void contextCarriesDefaultInstanceDatabaseAndSchema() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.loadDdl("CREATE TABLE ins1.db1.sch1.A (c1 INT);");

        MetadataContext context = MetadataContext.of(catalog, "ins1", "db1", "sch1");

        assertEquals("ins1", context.getInstance());
        assertEquals("db1", context.getDatabase());
        assertEquals("sch1", context.getSchema());
        assertNotNull(context.getCatalog());
    }

    @Test
    public void clearRemovesCurrentContext() {
        MetadataContext context = MetadataContext.of(defaultCatalog("db1.A"));

        MetadataContextHolder.set(context);
        assertSame(context, MetadataContextHolder.current());

        MetadataContextHolder.clear();
        assertNull(MetadataContextHolder.current());
    }

    @Test
    public void newThreadStartsWithNoContext() throws InterruptedException {
        MetadataContext context = MetadataContext.of(defaultCatalog("db1.A"));

        MetadataContextHolder.runWith(context, () -> {
            final Throwable[] failure = new Throwable[1];
            Thread thread = new Thread(() -> {
                try {
                    assertNull(MetadataContextHolder.current());
                } catch (Throwable t) {
                    failure[0] = t;
                }
            });
            thread.start();
            try {
                thread.join(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
            assertFalse("child thread did not finish", thread.isAlive());
            if (failure[0] != null) {
                throw new AssertionError(failure[0]);
            }
            return null;
        });

        assertNull(MetadataContextHolder.current());
    }

    @Test
    public void holderSetsCurrentContextAndClearsAfterwards() {
        MetadataContext context = MetadataContext.of(defaultCatalog("db1.A"));

        MetadataContextHolder.runWith(context, () -> {
            assertSame(context, MetadataContextHolder.current());
            return null;
        });

        assertNull(MetadataContextHolder.current());
    }

    @Test
    public void nestedContextsRestoreThePreviousContext() {
        MetadataContext outer = MetadataContext.of(defaultCatalog("db1.A"));
        MetadataContext inner = MetadataContext.of(defaultCatalog("db2.B"));

        MetadataContextHolder.runWith(outer, () -> {
            assertSame(outer, MetadataContextHolder.current());
            MetadataContextHolder.runWith(inner, () -> {
                assertSame(inner, MetadataContextHolder.current());
                return null;
            });
            assertSame(outer, MetadataContextHolder.current());
            return null;
        });

        assertNull(MetadataContextHolder.current());
    }

    @Test
    public void innerFailureRestoresOuterContext() {
        MetadataContext outer = MetadataContext.of(defaultCatalog("db1.A"));
        MetadataContext inner = MetadataContext.of(defaultCatalog("db2.B"));

        MetadataContextHolder.runWith(outer, () -> {
            try {
                MetadataContextHolder.runWith(inner, () -> {
                    throw new IllegalStateException("inner");
                });
                fail("expected IllegalStateException");
            } catch (IllegalStateException expected) {
                // expected
            }
            assertSame(outer, MetadataContextHolder.current());
            return null;
        });

        assertNull(MetadataContextHolder.current());
    }

    @Test
    public void holderClearsContextWhenActionThrows() {
        MetadataContext context = MetadataContext.of(defaultCatalog("db1.A"));

        try {
            MetadataContextHolder.runWith(context, () -> {
                throw new IllegalStateException("boom");
            });
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            // expected
        }

        assertNull(MetadataContextHolder.current());
    }

    @Test
    public void publishedSnapshotIsImmutable() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.loadDdl("CREATE TABLE db1.A (c1 INT);");
        MetadataContext context = MetadataContext.of(catalog);

        assertThrows(UnsupportedOperationException.class,
                () -> context.getCatalog().loadDdl("CREATE TABLE db9.X (c1 INT);"));
    }

    @Test
    public void publishedSnapshotCollectionsAndColumnsAreImmutable() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.loadDdl("CREATE TABLE db1.A (c1 INT);");
        MetadataContext context = MetadataContext.of(catalog);
        Table table = context.getCatalog().findTable("db1.A").getTable();

        assertThrows(UnsupportedOperationException.class,
                () -> table.getFields().add(new Column("c2")));
        assertThrows(UnsupportedOperationException.class,
                () -> table.getColumn("c1").setColumnName("c2"));

        // The snapshot still reports its original content and labels.
        assertEquals(context.getMetadataChecksum(), context.getCatalog().getChecksum());
        assertEquals(1, table.getFields().size());
        assertEquals("c1", table.getFields().get(0).getColumnName());
    }

    @Test
    public void failedTaskOnWorkerDoesNotLeakContext() throws Exception {
        MetadataContext context = MetadataContext.of(defaultCatalog("db1.A"));

        AtomicBoolean callbackRan = new AtomicBoolean(false);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> failing = pool.submit(() -> MetadataContextHolder.runWith(context, () -> {
                assertSame(context, MetadataContextHolder.current());
                callbackRan.set(true);
                throw new IllegalStateException("boom");
            }));
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> failing.get(30, TimeUnit.SECONDS));
            assertTrue(callbackRan.get());
            assertTrue(failure.getCause() instanceof IllegalStateException);
            assertEquals("boom", failure.getCause().getMessage());

            Future<?> emptyTask = pool.submit(() -> {
                assertNull(MetadataContextHolder.current());
                return null;
            });
            emptyTask.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void threadPoolWorkerDoesNotLeakContextBetweenTasks() throws Exception {
        MetadataContext first = MetadataContext.of(defaultCatalog("db1.A"));
        MetadataContext second = MetadataContext.of(defaultCatalog("db2.B"));

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> firstTask = pool.submit(() -> MetadataContextHolder.runWith(first, () -> {
                assertSame(first, MetadataContextHolder.current());
                return null;
            }));
            firstTask.get(30, TimeUnit.SECONDS);

            Future<?> secondTask = pool.submit(() -> MetadataContextHolder.runWith(second, () -> {
                assertSame(second, MetadataContextHolder.current());
                return null;
            }));
            secondTask.get(30, TimeUnit.SECONDS);

            Future<?> emptyTask = pool.submit(() -> {
                assertNull(MetadataContextHolder.current());
                return null;
            });
            emptyTask.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertNull(MetadataContextHolder.current());
    }

    private static MetadataCatalog defaultCatalog(String createTable) {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.loadDdl("CREATE TABLE " + createTable + " (c1 INT);");
        return catalog;
    }
}

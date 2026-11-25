package com.laotie;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import com.alibaba.druid.DbType;
import com.laotie.model.Instruction;
import com.laotie.model.LineageResult;
import com.laotie.model.metadata.MetadataCatalog;
import com.laotie.model.metadata.MetadataContext;
import com.laotie.model.metadata.Table;

/**
 * Stability and reproducibility at production scale (whole-database SQL log scanning).
 *
 * <p>
 * These are correctness tests, not latency benchmarks: same content in a different load order must
 * produce the same checksum; the same log batch must produce the same instructions on every run;
 * and a published (immutable) context must be safe to parse from multiple threads.
 *
 * <p>
 * Expected to be red until the metadata catalog and context APIs exist.
 */
public class MetadataScaleTest {

    private static final int TABLE_COUNT = 500;
    private static final int DATABASE_COUNT = 5;
    private static final int COLUMN_COUNT = 10;

    @Test
    public void largeCatalogChecksumIsIndependentOfLoadOrder() {
        List<String> statements = ddlStatements(TABLE_COUNT);

        MetadataCatalog forward = new MetadataCatalog(DbType.mysql);
        forward.loadDdl(String.join("\n", statements));

        List<String> reversed = new ArrayList<>(statements);
        Collections.reverse(reversed);
        MetadataCatalog backward = new MetadataCatalog(DbType.mysql);
        backward.loadDdl(String.join("\n", reversed));

        assertEquals(forward.getChecksum(), backward.getChecksum());
        assertEquals(TABLE_COUNT, countTables(forward));
        assertEquals(1, forward.getVersion());
        assertEquals(1, backward.getVersion());
    }

    @Test
    public void batchParsingIsDeterministic() {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.loadDdl(String.join("\n", ddlStatements(100)));
        MetadataContext context = MetadataContext.of(catalog);

        String batch = batchSql(100);
        List<LineageResult> first = new DmlLineage(context).parseScript(batch);
        List<LineageResult> second = new DmlLineage(context).parseScript(batch);

        assertEquals(100, first.size());
        assertResultsEqual(first, second);

        for (int i = 0; i < first.size(); i++) {
            assertEquals(1, first.get(i).getInstructions().size());
            assertEquals(String.format("db%d.T%d.c0", i % DATABASE_COUNT, i),
                    first.get(i).getInstructions().get(0).getFrom());
            assertEquals("temp0.c0", first.get(i).getInstructions().get(0).getTo());
        }
    }

    @Test
    public void concurrentReadOnlyParsingIsStable() throws Exception {
        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.loadDdl(String.join("\n", ddlStatements(50)));
        MetadataContext context = MetadataContext.of(catalog);

        final String sql = "SELECT c0 FROM db0.T0";
        final String expected = "db0.T0.c0 -> temp0.c0";

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<String>> tasks = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                tasks.add(() -> {
                    LineageResult result = new DmlLineage(context).parse(sql);
                    assertEquals(1, result.getInstructions().size());
                    return result.getInstructions().get(0).toString();
                });
            }
            List<Future<String>> futures = pool.invokeAll(tasks, 30, TimeUnit.SECONDS);
            for (Future<String> future : futures) {
                assertEquals(expected, future.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void readersObserveInitialAndPostWriteCommits() throws Exception {
        String c1Ddl = "CREATE TABLE db1.A (c1 INT);";
        String c2Ddl = "CREATE TABLE db1.A (c2 INT);";

        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.loadDdl(c1Ddl);
        String c1Checksum = catalog.getChecksum();

        MetadataCatalog c2Catalog = new MetadataCatalog(DbType.mysql);
        c2Catalog.loadDdl(c2Ddl);
        String c2Checksum = c2Catalog.getChecksum();

        CountDownLatch readerSawV1 = new CountDownLatch(1);
        CountDownLatch writerWroteV2 = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> reader = pool.submit(() -> {
                try {
                    assertSnapshotTriple(MetadataContext.of(catalog), 1, c1Checksum, "c1");
                    readerSawV1.countDown();
                    if (!writerWroteV2.await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError("writer did not commit");
                    }
                    assertSnapshotTriple(MetadataContext.of(catalog), 2, c2Checksum, "c2");
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            });

            Future<?> writer = pool.submit(() -> {
                try {
                    if (!readerSawV1.await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError("reader did not observe v1");
                    }
                    catalog.loadDdl(c2Ddl);
                    writerWroteV2.countDown();
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            });

            reader.get(30, TimeUnit.SECONDS);
            writer.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
    }

    @Test
    public void freeRunningConcurrentSnapshotsAreConsistent() throws Exception {
        String c1Ddl = "CREATE TABLE db1.A (c1 INT);";
        String c2Ddl = "CREATE TABLE db1.A (c2 INT);";

        MetadataCatalog catalog = new MetadataCatalog(DbType.mysql);
        catalog.loadDdl(c1Ddl);
        String c1Checksum = catalog.getChecksum();

        MetadataCatalog c2Catalog = new MetadataCatalog(DbType.mysql);
        c2Catalog.loadDdl(c2Ddl);
        String c2Checksum = c2Catalog.getChecksum();

        ExecutorService pool = Executors.newFixedThreadPool(5);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            Future<?> writer = pool.submit(() -> {
                try {
                    start.await(10, TimeUnit.SECONDS);
                    for (int i = 0; i < 100; i++) {
                        // v1 is c1; every following commit alternates c2, c1, c2, ...
                        catalog.loadDdl(i % 2 == 0 ? c2Ddl : c1Ddl);
                        Thread.yield();
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            });

            List<Future<?>> readers = new ArrayList<>();
            for (int r = 0; r < 4; r++) {
                readers.add(pool.submit(() -> {
                    try {
                        start.await(10, TimeUnit.SECONDS);
                        for (int i = 0; i < 500; i++) {
                            MetadataContext context = MetadataContext.of(catalog);
                            long version = context.getMetadataVersion();
                            String expectedChecksum = version % 2 == 1 ? c1Checksum : c2Checksum;
                            String expectedColumn = version % 2 == 1 ? "c1" : "c2";
                            assertSnapshotTriple(context, version, expectedChecksum,
                                    expectedColumn);
                        }
                    } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                    }
                }));
            }

            start.countDown();
            writer.get(30, TimeUnit.SECONDS);
            for (Future<?> reader : readers) {
                reader.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
    }

    private static void assertSnapshotTriple(MetadataContext context, long version,
            String checksum, String expectedColumn) {
        MetadataCatalog snapshot = context.getCatalog();
        assertEquals(version, context.getMetadataVersion());
        assertEquals(version, snapshot.getVersion());
        assertEquals(checksum, context.getMetadataChecksum());
        assertEquals(checksum, snapshot.getChecksum());

        Table table = snapshot.findTable("db1.A").getTable();
        assertTrue(table.hasColumn(expectedColumn));
        String otherColumn = "c1".equals(expectedColumn) ? "c2" : "c1";
        assertFalse(table.hasColumn(otherColumn));
    }

    private static List<String> ddlStatements(int tableCount) {
        List<String> statements = new ArrayList<>(tableCount);
        for (int i = 0; i < tableCount; i++) {
            StringBuilder ddl = new StringBuilder();
            ddl.append("CREATE TABLE db").append(i % DATABASE_COUNT)
                    .append(".T").append(i).append(" (");
            for (int c = 0; c < COLUMN_COUNT; c++) {
                if (c > 0) {
                    ddl.append(", ");
                }
                ddl.append("c").append(c).append(" INT");
            }
            ddl.append(");");
            statements.add(ddl.toString());
        }
        return statements;
    }

    private static String batchSql(int statementCount) {
        StringBuilder batch = new StringBuilder();
        for (int i = 0; i < statementCount; i++) {
            if (i > 0) {
                batch.append(';');
            }
            batch.append("SELECT c0 FROM db").append(i % DATABASE_COUNT)
                    .append(".T").append(i);
        }
        return batch.toString();
    }

    private static void assertResultsEqual(List<LineageResult> expected,
            List<LineageResult> actual) {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            LineageResult e = expected.get(i);
            LineageResult a = actual.get(i);
            assertNotNull(e.getSql());
            assertEquals(e.getSql().trim(), a.getSql().trim());
            assertEquals(e.getMetadataVersion(), a.getMetadataVersion());
            assertEquals(e.getMetadataChecksum(), a.getMetadataChecksum());
            assertEquals(errorType(e), errorType(a));
            assertEquals(e.getInstructions().size(), a.getInstructions().size());
            for (int j = 0; j < e.getInstructions().size(); j++) {
                Instruction ei = e.getInstructions().get(j);
                Instruction ai = a.getInstructions().get(j);
                assertEquals(ei.getOperation(), ai.getOperation());
                assertEquals(ei.getFrom(), ai.getFrom());
                assertEquals(ei.getTo(), ai.getTo());
            }
        }
    }

    private static String errorType(LineageResult result) {
        return result.getError() == null ? null : result.getError().getClass().getName();
    }

    private static int countTables(MetadataCatalog catalog) {
        Set<String> names = new HashSet<>();
        for (int i = 0; i < TABLE_COUNT; i++) {
            names.add(catalog.findTable(String.format("db%d.T%d", i % DATABASE_COUNT, i))
                    .getQualifiedName());
        }
        assertEquals(TABLE_COUNT, names.size());
        return names.size();
    }
}

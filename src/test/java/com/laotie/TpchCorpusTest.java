package com.laotie;

import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import com.alibaba.druid.DbType;
import com.laotie.model.Instruction;
import com.laotie.model.LineageResult;
import com.laotie.model.ScanResult;
import com.laotie.model.metadata.MetadataCatalog;
import com.laotie.model.metadata.MetadataContext;
import com.laotie.model.metadata.MetadataMode;

/**
 * Parse and analyze the 22 TPC-H queries against the standard schema.
 *
 * <p>
 * The queries are not committed (TPC license); place them in {@code target/tpch/queries}.
 * When they are absent the test is skipped so an offline build stays green.
 * </p>
 */
public class TpchCorpusTest {

    private static final Path QUERIES = Paths.get("target/tpch/queries");

    private static MetadataContext context;

    @BeforeClass
    public static void loadSchema() throws Exception {
        MetadataCatalog catalog = new MetadataCatalog(DbType.postgresql);
        InputStream schema = TpchCorpusTest.class.getResourceAsStream("/tpch/schema.sql");
        assertTrue("missing /tpch/schema.sql", schema != null);
        catalog.loadDdl(read(schema));
        context = MetadataContext.of(catalog);
    }

    private static String read(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    @Test
    public void allQueriesAnalyzeDeterministically() throws Exception {
        Assume.assumeTrue("place TPC-H queries in target/tpch/queries",
                Files.isDirectory(QUERIES));

        List<String> failures = new ArrayList<>();
        for (int i = 1; i <= 22; i++) {
            Path file = QUERIES.resolve(String.format("q%02d.sql", i));
            assertTrue("missing " + file, Files.isRegularFile(file));
            String sql = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);

            ScanResult first = scan(sql);
            ScanResult second = scan(sql);
            LineageResult result = first.getResults().get(0);
            if (result.getError() != null) {
                failures.add(file.getFileName() + ": " + result.getError());
                continue;
            }
            if (result.getInstructions().isEmpty()) {
                failures.add(file.getFileName() + ": produced no lineage");
            }
            if (!render(first).equals(render(second))) {
                failures.add(file.getFileName() + ": analysis is not deterministic");
            }
        }
        assertTrue("TPC-H corpus failures:\n" + String.join("\n", failures),
                failures.isEmpty());
    }

    private static ScanResult scan(String sql) {
        return LineageScanner.withMetadata(context,
                LineageOptions.of(MetadataMode.STRICT, false)).scan(sql);
    }

    private static String render(ScanResult scan) {
        StringBuilder rendered = new StringBuilder();
        for (LineageResult result : scan.getResults()) {
            rendered.append(result.getError()).append('\n');
            for (Instruction instruction : result.getInstructions()) {
                rendered.append(instruction).append('\n');
            }
        }
        return rendered.toString();
    }
}

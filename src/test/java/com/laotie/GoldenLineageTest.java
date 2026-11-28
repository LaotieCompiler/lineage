package com.laotie;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.Test;

import com.alibaba.druid.DbType;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.laotie.model.Instruction;
import com.laotie.model.LineageResult;
import com.laotie.model.ScanResult;
import com.laotie.model.metadata.Column;
import com.laotie.model.metadata.MetadataCatalog;
import com.laotie.model.metadata.MetadataContext;
import com.laotie.model.metadata.MetadataMode;

/**
 * Data-driven golden corpus. Each JSON case declares a dialect, analysis options, an
 * optional catalog, a SQL script and the expected per-statement result.
 *
 * <p>
 * Run with {@code -Dgolden.update=true} to regenerate the {@code expect} arrays from the
 * current analyzer; the resulting diff must be reviewed before it is committed.
 * </p>
 */
public class GoldenLineageTest {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting().disableHtmlEscaping().create();

    private final List<String> failures = new ArrayList<>();

    @Test
    public void goldenCorpus() throws Exception {
        // Prefer the source tree so -Dgolden.update=true writes the files under review
        // instead of the copy in target/test-classes.
        Path root = Paths.get("src/test/resources/golden");
        if (!Files.isDirectory(root)) {
            root = Paths.get(GoldenLineageTest.class.getResource("/golden").toURI());
        }
        final List<Path> files;
        try (Stream<Path> stream = Files.walk(root)) {
            files = stream
                    .filter(path -> path.toString().endsWith(".json"))
                    .sorted()
                    .collect(Collectors.toList());
        }
        assertTrue("no golden corpus files under " + root, !files.isEmpty());

        boolean update = Boolean.getBoolean("golden.update");
        int cases = 0;
        for (Path file : files) {
            JsonElement parsed = JsonParser.parseString(
                    new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            JsonArray group = parsed.isJsonArray() ? parsed.getAsJsonArray() : new JsonArray();
            if (!parsed.isJsonArray()) {
                group.add(parsed);
            }
            for (JsonElement element : group) {
                runCase(element.getAsJsonObject(), update);
                cases++;
            }
            if (update) {
                write(file, GSON.toJson(group));
            }
        }
        if (update) {
            System.out.println("Regenerated expectations for " + cases + " golden cases");
            return;
        }
        assertTrue("golden corpus failures:\n" + String.join("\n", failures),
                failures.isEmpty());
    }

    private void runCase(JsonObject testCase, boolean update) {
        String name = string(testCase, "name", "<unnamed>");
        DbType dialect = DbType.valueOf(string(testCase, "dialect", "postgresql"));
        String catalogDdl = string(testCase, "catalog", null);
        MetadataMode mode = "optional".equals(string(testCase, "mode",
                catalogDdl == null ? "optional" : "strict"))
                ? MetadataMode.OPTIONAL : MetadataMode.STRICT;
        LineageOptions options = LineageOptions.of(mode,
                bool(testCase, "infer", false), bool(testCase, "strictQuoting", false));

        LineageScanner scanner;
        if (catalogDdl == null) {
            scanner = LineageScanner.withoutMetadata(dialect, options);
        } else {
            MetadataCatalog catalog = new MetadataCatalog(dialect);
            catalog.loadDdl(catalogDdl);
            scanner = LineageScanner.withMetadata(MetadataContext.of(catalog), options);
        }
        ScanResult result = scanner.scan(string(testCase, "script", ""));

        if (update) {
            testCase.add("expect", expectationsOf(result));
            return;
        }

        JsonArray expected = testCase.has("expect") && testCase.get("expect").isJsonArray()
                ? testCase.getAsJsonArray("expect") : new JsonArray();
        if (expected.size() != result.getResults().size()) {
            failures.add(name + ": expected " + expected.size() + " statements, got "
                    + result.getResults().size());
            return;
        }
        for (int i = 0; i < expected.size(); i++) {
            compareStatement(name, i, expected.get(i).getAsJsonObject(),
                    result.getResults().get(i), result);
        }
    }

    private JsonArray expectationsOf(ScanResult result) {
        JsonArray expectations = new JsonArray();
        for (LineageResult statement : result.getResults()) {
            JsonObject expected = new JsonObject();
            if (statement.getError() == null) {
                expected.add("error", JsonNull.INSTANCE);
            } else {
                expected.addProperty("error", statement.getError().getClass().getSimpleName());
            }
            JsonArray instructions = new JsonArray();
            for (Instruction instruction : statement.getInstructions()) {
                instructions.add(instruction.toString());
            }
            expected.add("instructions", instructions);
            expectations.add(expected);
        }
        return expectations;
    }

    private void compareStatement(String caseName, int index, JsonObject expected,
            LineageResult actual, ScanResult scan) {
        String where = caseName + " statement " + index;
        String expectedError = expected.has("error") && !expected.get("error").isJsonNull()
                ? expected.get("error").getAsString() : null;
        String actualError = actual.getError() == null ? null
                : actual.getError().getClass().getSimpleName();
        if (expectedError == null ? actualError != null : !expectedError.equals(actualError)) {
            failures.add(where + ": expected error " + expectedError + ", got "
                    + actualError + describe(actual.getError()));
        }

        if (expected.has("instructions")) {
            List<String> expectedInstructions = strings(expected.getAsJsonArray("instructions"));
            List<String> actualInstructions = new ArrayList<>();
            for (Instruction instruction : actual.getInstructions()) {
                actualInstructions.add(instruction.toString());
            }
            if (!expectedInstructions.equals(actualInstructions)) {
                failures.add(where + ": instructions differ\n  expected " + expectedInstructions
                        + "\n  actual   " + actualInstructions);
            }
        }

        if (expected.has("sources")) {
            JsonObject sources = expected.getAsJsonObject("sources");
            for (String target : sources.keySet()) {
                Set<Column> expectedSources = new HashSet<>();
                for (String source : strings(sources.getAsJsonArray(target))) {
                    expectedSources.add(new Column(source));
                }
                Set<Column> actualSources = scan.getGraph().getSources(column(target));
                if (!expectedSources.equals(actualSources)) {
                    failures.add(where + ": sources of " + target + " differ\n  expected "
                            + expectedSources + "\n  actual   " + actualSources);
                }
            }
        }
    }

    private static Column column(String target) {
        return target.startsWith("stmt") && target.contains(".temp")
                ? Column.temporary(target) : new Column(target);
    }

    private static String describe(Throwable error) {
        return error == null ? "" : " (" + error.getMessage() + ")";
    }

    private static List<String> strings(JsonArray array) {
        List<String> values = new ArrayList<>();
        for (JsonElement element : array) {
            values.add(element.getAsString());
        }
        return values;
    }

    private static String string(JsonObject object, String key, String fallback) {
        return object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsString() : fallback;
    }

    private static boolean bool(JsonObject object, String key, boolean fallback) {
        return object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsBoolean() : fallback;
    }

    private static void write(Path file, String content) throws IOException {
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }
}

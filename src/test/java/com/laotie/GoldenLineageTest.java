package com.laotie;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
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
        List<Path> files = corpusFiles();
        boolean update = Boolean.getBoolean("golden.update");
        int cases = 0;
        for (Path file : files) {
            List<JsonObject> group = casesOf(file);
            for (JsonObject testCase : group) {
                runCase(testCase, update);
                cases++;
            }
            if (update) {
                write(file, GSON.toJson(toArray(group)));
            }
        }
        if (update) {
            System.out.println("Regenerated expectations for " + cases + " golden cases");
            return;
        }
        assertTrue("golden corpus failures:\n" + String.join("\n", failures),
                failures.isEmpty());
    }

    /**
     * Malformed SQL must never escape the scanner: every input still produces a result,
     * and a broken statement does not abort the rest of the script.
     */
    @Test
    public void mutatedScriptsDoNotEscapeTheScanner() throws Exception {
        int mutations = 0;
        for (Path file : corpusFiles()) {
            for (JsonObject testCase : casesOf(file)) {
                String name = string(testCase, "name", "<unnamed>");
                String script = string(testCase, "script", "");
                Random random = new Random(script.hashCode());
                LineageScanner scanner = scannerFor(testCase);
                for (int i = 0; i < 5; i++) {
                    String mutated = mutate(script, random);
                    if (mutated.trim().isEmpty()) {
                        continue;
                    }
                    try {
                        ScanResult result = scanner.scan(mutated);
                        assertTrue(name + " mutation produced no result: " + mutated,
                                !result.getResults().isEmpty());
                    } catch (Throwable error) {
                        failures.add(name + " mutation escaped the scanner: " + mutated
                                + "\n  " + error);
                    }
                    mutations++;
                }
            }
        }
        assertTrue("no mutations were exercised", mutations > 0);
        assertTrue("robustness failures:\n" + String.join("\n", failures),
                failures.isEmpty());
    }

    private static List<Path> corpusFiles() throws Exception {
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
        return files;
    }

    private static List<JsonObject> casesOf(Path file) throws IOException {
        JsonElement parsed = JsonParser.parseString(
                new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        JsonArray group = parsed.isJsonArray() ? parsed.getAsJsonArray() : new JsonArray();
        if (!parsed.isJsonArray()) {
            group.add(parsed);
        }
        List<JsonObject> cases = new ArrayList<>();
        for (JsonElement element : group) {
            cases.add(element.getAsJsonObject());
        }
        return cases;
    }

    private static JsonArray toArray(List<JsonObject> cases) {
        JsonArray array = new JsonArray();
        for (JsonObject testCase : cases) {
            array.add(testCase);
        }
        return array;
    }

    private static LineageScanner scannerFor(JsonObject testCase) {
        DbType dialect = DbType.valueOf(string(testCase, "dialect", "postgresql"));
        String catalogDdl = string(testCase, "catalog", null);
        MetadataMode mode = "optional".equals(string(testCase, "mode",
                catalogDdl == null ? "optional" : "strict"))
                ? MetadataMode.OPTIONAL : MetadataMode.STRICT;
        LineageOptions options = LineageOptions.of(mode,
                bool(testCase, "infer", false), bool(testCase, "strictQuoting", false));
        if (catalogDdl == null) {
            return LineageScanner.withoutMetadata(dialect, options);
        }
        MetadataCatalog catalog = new MetadataCatalog(dialect);
        catalog.loadDdl(catalogDdl);
        return LineageScanner.withMetadata(MetadataContext.of(catalog), options);
    }

    private static String mutate(String sql, Random random) {
        List<String> tokens = new ArrayList<>(Arrays.asList(sql.split("(?<=\\s)|(?=\\s)")));
        if (tokens.isEmpty()) {
            return sql;
        }
        String[] replacements = {"SELECT", "FROM", "WHERE", "JOIN", "ON", "UNION",
            "WITH", "GROUP BY", "*", "(", ")", ",", "'", "--", "?"};
        int index = random.nextInt(tokens.size());
        switch (random.nextInt(4)) {
            case 0:
                tokens.remove(index);
                break;
            case 1:
                tokens.add(index, tokens.get(index));
                break;
            case 2:
                tokens.set(index, replacements[random.nextInt(replacements.length)]);
                break;
            default:
                tokens.add(index, "(");
                break;
        }
        return String.join("", tokens);
    }

    private void runCase(JsonObject testCase, boolean update) {
        String name = string(testCase, "name", "<unnamed>");
        ScanResult result = scannerFor(testCase).scan(string(testCase, "script", ""));

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

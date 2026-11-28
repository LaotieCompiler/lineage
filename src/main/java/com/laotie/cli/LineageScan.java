package com.laotie.cli;

import com.alibaba.druid.DbType;
import com.laotie.LineageOptions;
import com.laotie.LineageScanner;
import com.laotie.model.Instruction;
import com.laotie.model.LineageGraph;
import com.laotie.model.LineageResult;
import com.laotie.model.RelationHop;
import com.laotie.model.RelationType;
import com.laotie.model.ScanResult;
import com.laotie.identifier.Identifiers;
import com.laotie.model.metadata.Column;
import com.laotie.model.metadata.MetadataCatalog;
import com.laotie.model.metadata.MetadataContext;
import com.laotie.model.metadata.MetadataMode;
import com.laotie.model.metadata.Table;
import com.laotie.model.metadata.TableLocation;
import com.laotie.parse.ScriptSplitter;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultEdge;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * Command-line scanner: analyzes a tree of DDL and SQL files against a metadata catalog and
 * writes table- and column-level lineage artifacts as CSV.
 */
public class LineageScan {
    static DbType D;
    static final Pattern VAR_DEF = Pattern.compile("(?s)^\\s*@([A-Za-z_][A-Za-z0-9_]*)\\s*:=\\s*(.*)$");
    static final Pattern COL_MISSING = Pattern.compile("column '([^']+)' not found in tables: (.*)");
    static final Pattern TBL_MISSING = Pattern.compile("table not found: (.*)");

    static final class Stmt {
        final String file; final String name; final int index; final LineageResult result;
        Stmt(String file, String name, int index, LineageResult result) {
            this.file = file; this.name = name; this.index = index; this.result = result;
        }
    }
    static final class SqlFile {
        final String file; final String name; final String sql;
        SqlFile(String file, String name, String sql) { this.file = file; this.name = name; this.sql = sql; }
    }
    static final class Preprocessed { String sql = ""; int inlined = 0; }

    public static void main(String[] args) throws Exception {
        Options o = Options.parse(args);
        D = o.dialect;
        Path ddlDir = o.ddlDir;
        List<Path> sqlDirs = o.sqlDirs;
        Path outDir = o.outDir;
        Files.createDirectories(outDir);

        System.out.println("Dialect:  " + D);
        System.out.println("DDL:      " + ddlDir);
        for (Path dir : sqlDirs) System.out.println("SQL:      " + dir);
        System.out.println("Output:   " + outDir);

        // ---- 1. DDL catalog ----
        MetadataCatalog baseCatalog = new MetadataCatalog(D);
        int ddlFiles = 0, ddlStmtFail = 0;
        List<String> ddlErrors = new ArrayList<>();
        List<Path> ddlPaths = listFiles(ddlDir, o.extensions, o.recursive);
        if (ddlPaths.isEmpty()) {
            error("no DDL files (" + extensionLabel(o.extensions) + ") in " + ddlDir);
        }
        for (Path p : ddlPaths) {
            ddlFiles++;
            String ddl = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            try {
                baseCatalog.loadDdl(ddl);
            } catch (RuntimeException e) {
                for (String statement : ScriptSplitter.split(ddl, D)) {
                    if (statement.trim().isEmpty()) continue;
                    try {
                        baseCatalog.loadDdl(statement);
                    } catch (RuntimeException e2) {
                        ddlStmtFail++;
                        ddlErrors.add(p.getFileName() + ": " + firstLine(e2.getMessage()));
                    }
                }
            }
        }
        int catalogTables = baseCatalog.toDdl().split("CREATE TABLE").length - 1;
        System.out.printf("DDL: %d files, %d tables, %d failed loads%n",
                ddlFiles, catalogTables, ddlStmtFail);

        // ---- 2. preprocess SQL ----
        List<Path> sqlPaths = new ArrayList<>();
        for (Path dir : sqlDirs) {
            List<Path> here = listFiles(dir, o.extensions, o.recursive);
            if (here.isEmpty()) System.err.println("warning: no SQL files in " + dir);
            sqlPaths.addAll(here);
        }
        if (sqlPaths.isEmpty()) {
            error("no SQL files (" + extensionLabel(o.extensions) + ") found");
        }
        List<SqlFile> files = new ArrayList<>();
        int inlinedVars = 0;
        for (Path p : sqlPaths) {
            String raw = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            Preprocessed pp;
            if (o.preprocess) {
                pp = preprocess(raw);
            } else {
                pp = new Preprocessed();
                pp.sql = raw;
            }
            inlinedVars += pp.inlined;
            files.add(new SqlFile(p.getFileName().toString(), extractName(raw), pp.sql));
        }

        // ---- 3. baseline scan (DDL authoritative) ----
        ScanOutcome baseline = scanAll(baseCatalog, files, o);
        int baselineOk = baseline.okCount();

        // ---- 4. schema reconciliation: add explicitly referenced but missing columns ----
        MetadataCatalog repaired = baseCatalog.mutableCopy();
        List<String> repairLog = new ArrayList<>();
        Set<String> added = new TreeSet<>();
        ScanOutcome finalOutcome = baseline;
        if (o.repair) {
        for (int round = 1; round <= 8; round++) {
            boolean changed = false;
            for (Stmt s : baselineStmts(baseline)) {
                if (s.result.getError() == null) continue;
                Matcher m = COL_MISSING.matcher(String.valueOf(s.result.getError().getMessage()));
                if (!m.find()) continue;
                String columnName = m.group(1);
                for (String rawTable : m.group(2).split(",")) {
                    String tableName = strip(rawTable.trim());
                    if (tableName.isEmpty()) continue;
                    List<TableLocation> matches;
                    try { matches = repaired.findAllTables(tableName); }
                    catch (RuntimeException e) { continue; }
                    if (matches.size() != 1) continue;
                    Table table = matches.get(0).getTable();
                    if (table.getColumn(columnName) != null) continue;
                    table.addColumn(new Column(table.getName(), columnName));
                    String key = table.getName() + "." + columnName;
                    if (added.add(key)) {
                        repairLog.add(s.file + "#" + s.index + "," + table.getName() + "," + columnName);
                        changed = true;
                    }
                }
            }
            if (!changed) break;
            finalOutcome = scanAll(repaired, files, o);
            baseline = finalOutcome;
        }
        }
        finalOutcome = scanAll(repaired, files, o);

        List<Stmt> stmts = baselineStmts(finalOutcome);
        long failed = stmts.stream().filter(s -> s.result.getError() != null).count();
        System.out.printf("%nStatements: %d, script variables inlined: %d%n", stmts.size(), inlinedVars);
        System.out.printf("Baseline (explicit DDL): %d ok / %d fail%n", baselineOk, stmts.size() - baselineOk);
        System.out.printf("Reconciled catalog:      %d ok / %d fail, %d columns added to schema%n",
                stmts.size() - failed, failed, added.size());

        // ---- 5. graph + artifacts ----
        List<LineageResult> allResults = new ArrayList<>();
        for (Stmt s : stmts) allResults.add(s.result);
        LineageGraph graph = new LineageGraph();
        graph.buildByResults(allResults);

        writeStatements(outDir.resolve("statements.csv"), stmts);
        writeColumnEdges(outDir.resolve("column_edges.csv"), stmts);
        writeColumnLineage(outDir.resolve("column_lineage.csv"), stmts, graph);
        writeTableDependencies(outDir.resolve("table_dependencies.csv"), stmts, graph);
        writeTableLineage(outDir.resolve("table_lineage.csv"), outDir.resolve("column_lineage.csv"));
        writeRepairs(outDir.resolve("schema_repairs.csv"), repairLog);
        writeMissingTables(outDir.resolve("missing_tables.csv"), stmts, repaired);
        writeRunInfo(outDir.resolve("run-info.txt"), o, stmts.size(), failed,
                added.size(), catalogTables, inlinedVars);
        writeLineageData(outDir.resolve("lineage-data.js"), outDir);
        writeReport(outDir.resolve("lineage-report.html"));

        System.out.println("\n=== failed statements ===");
        for (Stmt s : stmts) {
            if (s.result.getError() == null) continue;
            System.out.printf("%s #%d %s: %s%n", s.file, s.index,
                    s.result.getError().getClass().getSimpleName(),
                    firstLine(s.result.getError().getMessage()));
        }
        System.out.println("\nSchema repairs: " + added.size() + " (see schema_repairs.csv)");
        if (ddlErrors.isEmpty()) System.out.println("DDL parse errors: none");
        else for (String d : ddlErrors) System.out.println("DDL error: " + d);
        System.out.println("Graph: " + graph.getReverseGraph().vertexSet().size() + " vertices, "
                + graph.getReverseGraph().edgeSet().size() + " edges");
        System.out.println("Report: " + outDir.resolve("lineage-report.html").toAbsolutePath());
        System.out.println("\nArtifacts written to " + outDir.toAbsolutePath());
    }

    // ------------------------------------------------------------------
    static final class Options {
        DbType dialect = DbType.hive;
        Path ddlDir;
        List<Path> sqlDirs = new ArrayList<>();
        Path outDir = Paths.get("output");
        List<String> extensions = new ArrayList<>(Collections.singletonList("sql"));
        List<String> order = new ArrayList<>(Arrays.asList(
                "dwd", "dws", "ads", "bi", "ods", "dim", "other"));
        boolean recursive = false;
        boolean repair = true;
        boolean strict = false;
        boolean infer = true;
        boolean preprocess = true;

        static Options parse(String[] args) {
            if (args.length == 0) {
                usage("missing arguments");
            }
            Options o = new Options();
            if (!args[0].startsWith("-")) {
                // Legacy positional form: <dialect> <ddl-dir> <sql-dir>... <out-dir>
                if (args.length < 4) {
                    usage("expected: <dialect> <ddl-dir> <sql-dir>... <out-dir>");
                }
                o.dialect = dialect(args[0]);
                o.ddlDir = Paths.get(args[1]);
                for (int i = 2; i < args.length - 1; i++) {
                    o.sqlDirs.add(Paths.get(args[i]));
                }
                o.outDir = Paths.get(args[args.length - 1]);
                o.resolve(null);
                return o;
            }
            Path input = null;
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                switch (a) {
                    case "-h":
                    case "--help":
                        usage(null);
                        return o;
                    case "-i":
                    case "--input":
                        input = Paths.get(value(args, ++i, a));
                        break;
                    case "-d":
                    case "--ddl":
                        o.ddlDir = Paths.get(value(args, ++i, a));
                        break;
                    case "-s":
                    case "--sql":
                        o.sqlDirs.add(Paths.get(value(args, ++i, a)));
                        break;
                    case "-o":
                    case "--output":
                        o.outDir = Paths.get(value(args, ++i, a));
                        break;
                    case "--dialect":
                        o.dialect = dialect(value(args, ++i, a));
                        break;
                    case "--ext":
                        o.extensions = splitList(value(args, ++i, a));
                        break;
                    case "--order":
                        o.order = splitList(value(args, ++i, a));
                        break;
                    case "--recursive":
                        o.recursive = true;
                        break;
                    case "--no-repair":
                        o.repair = false;
                        break;
                    case "--strict":
                        o.strict = true;
                        break;
                    case "--no-infer":
                        o.infer = false;
                        break;
                    case "--no-preprocess":
                        o.preprocess = false;
                        break;
                    default:
                        usage("unknown option: " + a);
                }
            }
            o.resolve(input);
            return o;
        }

        private void resolve(Path input) {
            if (input != null) {
                input = input.toAbsolutePath().normalize();
            }
            if (ddlDir == null) {
                if (input == null) {
                    error("--ddl or --input is required");
                }
                Path candidate = input.resolve("ddl").toAbsolutePath().normalize();
                ddlDir = hasFiles(candidate, extensions, recursive)
                        ? candidate
                        : findNamedDir(input, "ddl", extensions, recursive);
                if (ddlDir == null) {
                    error("cannot find a DDL directory under " + input + " (use --ddl)");
                }
            } else {
                ddlDir = ddlDir.toAbsolutePath().normalize();
            }
            if (sqlDirs.isEmpty()) {
                if (input == null) {
                    error("--sql or --input is required");
                }
                sqlDirs = discoverSqlDirs(input, ddlDir, extensions, recursive, order);
                if (sqlDirs.isEmpty()) {
                    error("no SQL directories with " + extensionLabel(extensions)
                            + " files under " + input);
                }
            } else {
                List<Path> resolved = new ArrayList<>();
                for (Path dir : sqlDirs) {
                    resolved.add(dir.toAbsolutePath().normalize());
                }
                sqlDirs = resolved;
            }
            outDir = outDir.toAbsolutePath().normalize();
        }
    }

    static String value(String[] args, int i, String option) {
        if (i >= args.length) {
            usage("missing value for " + option);
            return "";
        }
        return args[i];
    }

    static DbType dialect(String raw) {
        try {
            return DbType.valueOf(raw.trim().toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            usage("unknown dialect: " + raw);
            return DbType.hive;
        }
    }

    static List<String> splitList(String raw) {
        List<String> values = new ArrayList<>();
        for (String part : raw.split(",")) {
            String value = part.trim();
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        if (values.isEmpty()) {
            usage("empty list value");
        }
        return values;
    }

    static String extensionLabel(List<String> extensions) {
        StringBuilder label = new StringBuilder();
        for (String extension : extensions) {
            if (label.length() > 0) {
                label.append('/');
            }
            label.append('.').append(extension);
        }
        return label.toString();
    }

    static List<Path> listFiles(Path dir, List<String> extensions, boolean recursive) {
        List<Path> files = new ArrayList<>();
        if (dir == null || !Files.isDirectory(dir)) {
            return files;
        }
        if (recursive) {
            try (java.util.stream.Stream<Path> stream = Files.walk(dir)) {
                stream.filter(Files::isRegularFile)
                        .filter(path -> hasExtension(path, extensions))
                        .forEach(files::add);
            } catch (Exception e) {
                throw new RuntimeException("cannot list " + dir + ": " + e.getMessage(), e);
            }
        } else {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                for (Path path : stream) {
                    if (Files.isRegularFile(path) && hasExtension(path, extensions)) {
                        files.add(path);
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException("cannot list " + dir + ": " + e.getMessage(), e);
            }
        }
        files.sort(Comparator.comparing(Path::toString));
        return files;
    }

    static boolean hasExtension(Path path, List<String> extensions) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        for (String extension : extensions) {
            if (name.endsWith("." + extension.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    static boolean hasFiles(Path dir, List<String> extensions, boolean recursive) {
        return !listFiles(dir, extensions, recursive).isEmpty();
    }

    static Path findNamedDir(Path root, String name, List<String> extensions,
            boolean recursive) {
        if (root == null || !Files.isDirectory(root)) {
            return null;
        }
        List<Path> candidates = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.walk(root, 4)) {
            stream.filter(Files::isDirectory)
                    .filter(path -> path.getFileName() != null
                            && path.getFileName().toString().equalsIgnoreCase(name))
                    .filter(path -> hasFiles(path, extensions, recursive))
                    .forEach(candidates::add);
        } catch (Exception ignored) {
            return null;
        }
        candidates.sort(Comparator.comparing(Path::toString));
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    static List<Path> discoverSqlDirs(Path input, Path ddlDir, List<String> extensions,
            boolean recursive, List<String> order) {
        Set<Path> dirs = new LinkedHashSet<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(input)) {
            for (Path path : stream) {
                if (Files.isDirectory(path) && !path.equals(ddlDir)
                        && hasFiles(path, extensions, recursive)) {
                    dirs.add(path.toAbsolutePath().normalize());
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("cannot list " + input + ": " + e.getMessage(), e);
        }
        if (dirs.isEmpty()) {
            // A project root whose SQL lives one or two levels down (e.g. scripts/dwd).
            try (java.util.stream.Stream<Path> stream = Files.walk(input, 3)) {
                stream.filter(Files::isDirectory)
                        .filter(path -> !path.equals(input) && !path.equals(ddlDir))
                        .filter(path -> hasFiles(path, extensions, recursive))
                        .forEach(path -> dirs.add(path.toAbsolutePath().normalize()));
            } catch (Exception ignored) {
                // keep whatever the immediate scan found
            }
        }
        if (dirs.isEmpty() && !input.equals(ddlDir)
                && hasFiles(input, extensions, recursive)) {
            dirs.add(input);
        }
        Map<String, Integer> ranks = new HashMap<>();
        for (int i = 0; i < order.size(); i++) {
            ranks.put(order.get(i).toLowerCase(Locale.ROOT), i);
        }
        List<Path> sorted = new ArrayList<>(dirs);
        sorted.sort(Comparator
                .comparingInt((Path path) -> ranks.getOrDefault(
                        path.getFileName().toString().toLowerCase(Locale.ROOT),
                        order.size()))
                .thenComparing(path -> path.getFileName().toString()));
        return sorted;
    }

    /**
     * Write the data consumed by the static HTML report. The page loads this file with a
     * plain script tag, so the report works from {@code file://} without a server.
     */
    static void writeLineageData(Path file, Path outDir) throws IOException {
        String[] names = {"statements", "tableLineage", "tableDependencies",
                "columnLineage", "repairs"};
        Path[] sources = {outDir.resolve("statements.csv"),
                outDir.resolve("table_lineage.csv"),
                outDir.resolve("table_dependencies.csv"),
                outDir.resolve("column_lineage.csv"),
                outDir.resolve("schema_repairs.csv")};
        try (java.io.Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write("window.LINEAGE_DATA = {");
            w.write("\"generatedAt\":");
            w.write(json(java.time.OffsetDateTime.now().toString()));
            w.write(",\"sourceDir\":");
            w.write(json(outDir.toAbsolutePath().normalize().toString()));
            for (int i = 0; i < names.length; i++) {
                w.write(",");
                w.write(json(names[i]));
                w.write(":[");
                List<Map<String, String>> rows = readCsv(sources[i]);
                for (int r = 0; r < rows.size(); r++) {
                    if (r > 0) w.write(",");
                    w.write("{");
                    int c = 0;
                    for (Map.Entry<String, String> e : rows.get(r).entrySet()) {
                        if (c++ > 0) w.write(",");
                        w.write(json(e.getKey()));
                        w.write(":");
                        w.write(jsonValue(e.getKey(), e.getValue()));
                    }
                    w.write("}");
                }
                w.write("]");
            }
            w.write("};\n");
        }
    }

    static String jsonValue(String key, String value) {
        if ("statement".equals(key) || "instructions".equals(key)
                || "target_columns".equals(key) || "column_edges".equals(key)) {
            try {
                return String.valueOf(Integer.parseInt(value));
            } catch (NumberFormatException e) {
                return "0";
            }
        }
        if ("source_is_wildcard".equals(key) || "truncated".equals(key)) {
            return String.valueOf("true".equalsIgnoreCase(value));
        }
        return json(value);
    }

    static String json(String value) {
        if (value == null) return "null";
        StringBuilder out = new StringBuilder(value.length() + 2);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                default:
                    if (ch < 0x20 || ch == 0x2028 || ch == 0x2029) {
                        out.append(String.format("\\u%04x", (int) ch));
                    } else {
                        out.append(ch);
                    }
            }
        }
        out.append('"');
        return out.toString();
    }

    static List<Map<String, String>> readCsv(Path file) throws IOException {
        List<Map<String, String>> rows = new ArrayList<>();
        if (!Files.isRegularFile(file)) {
            return rows;
        }
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        List<List<String>> parsed = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (ch == '\n') {
                row.add(field.toString());
                field.setLength(0);
                parsed.add(row);
                row = new ArrayList<>();
            } else if (ch != '\r') {
                field.append(ch);
            }
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            parsed.add(row);
        }
        if (parsed.isEmpty()) {
            return rows;
        }
        List<String> header = parsed.get(0);
        for (int r = 1; r < parsed.size(); r++) {
            List<String> values = parsed.get(r);
            boolean blank = true;
            for (String value : values) {
                if (!value.trim().isEmpty()) {
                    blank = false;
                    break;
                }
            }
            if (blank) continue;
            Map<String, String> map = new LinkedHashMap<>();
            for (int c = 0; c < header.size(); c++) {
                map.put(header.get(c).trim(), c < values.size() ? values.get(c).trim() : "");
            }
            rows.add(map);
        }
        return rows;
    }

    static void writeReport(Path file) throws IOException {
        try (InputStream in = LineageScan.class.getResourceAsStream("lineage-report.html")) {
            if (in == null) {
                throw new IOException("report template is missing from the classpath");
            }
            Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static void writeRunInfo(Path p, Options o, int statements, long failed,
            int repairs, int tables, int inlined) throws Exception {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(p, StandardCharsets.UTF_8))) {
            w.println("dialect=" + o.dialect);
            w.println("ddl=" + o.ddlDir);
            for (Path dir : o.sqlDirs) {
                w.println("sql=" + dir);
            }
            w.println("output=" + o.outDir);
            w.println("extensions=" + String.join(",", o.extensions));
            w.println("recursive=" + o.recursive);
            w.println("metadata_mode=" + (o.strict ? "STRICT" : "OPTIONAL"));
            w.println("infer_metadata=" + o.infer);
            w.println("schema_repair=" + o.repair);
            w.println("preprocess=" + o.preprocess);
            w.println("tables=" + tables);
            w.println("statements=" + statements);
            w.println("failed=" + failed);
            w.println("repairs=" + repairs);
            w.println("inlined_script_vars=" + inlined);
            w.println("generated=" + java.time.OffsetDateTime.now());
        }
    }

    static void error(String message) {
        System.err.println("error: " + message);
        System.err.println();
        printUsage(System.err);
        System.exit(2);
    }

    static void usage(String message) {
        if (message != null) {
            System.err.println("error: " + message);
            System.err.println();
            printUsage(System.err);
            System.exit(2);
        }
        printUsage(System.out);
        System.exit(0);
    }

    static void printUsage(java.io.PrintStream out) {
        out.println("Usage:");
        out.println("  run.sh --input <project-dir> [--output <dir>] [options]");
        out.println("  run.sh --ddl <dir> --sql <dir> [--sql <dir> ...] [--output <dir>]");
        out.println("  run.sh <dialect> <ddl-dir> <sql-dir>... <out-dir>   (legacy positional)");
        out.println();
        out.println("Options:");
        out.println("  -i, --input <dir>     project root; auto-discovers <dir>/ddl and SQL subdirectories");
        out.println("  -d, --ddl <dir>       DDL directory (default: <input>/ddl)");
        out.println("  -s, --sql <dir>       SQL directory, repeatable");
        out.println("  -o, --output <dir>    output directory (default: ./output)");
        out.println("      --dialect <name>  Druid dialect (default: hive)");
        out.println("      --ext <list>      comma-separated extensions (default: sql)");
        out.println("      --order <list>    auto-discovery order (default: dwd,dws,ads,bi,ods,dim,other)");
        out.println("      --recursive       scan subdirectories recursively");
        out.println("      --strict          STRICT metadata mode (default: OPTIONAL)");
        out.println("      --no-infer        disable learned metadata");
        out.println("      --no-repair       disable schema reconciliation");
        out.println("      --no-preprocess   disable ${var} substitution and @var inlining");
        out.println("  -h, --help            show this help");
        out.println();
        out.println("Examples:");
        out.println("  run.sh --input /data/warehouse/scripts --output output");
        out.println("  run.sh --ddl /data/ddl --sql /data/dwd --sql /data/dws --output output");
    }

    static ScanOutcome scanAll(MetadataCatalog catalog, List<SqlFile> files, Options o) {
        ScanOutcome outcome = new ScanOutcome();
        LineageOptions options = LineageOptions.of(
                o.strict ? MetadataMode.STRICT : MetadataMode.OPTIONAL, o.infer);
        LineageScanner scanner = LineageScanner.withMetadata(MetadataContext.of(catalog), options);
        for (SqlFile f : files) {
            ScanResult scan = scanner.scan(f.sql);
            List<LineageResult> results = scan.getResults();
            for (int i = 0; i < results.size(); i++) {
                outcome.stmts.add(new Stmt(f.file, f.name, i + 1, results.get(i)));
            }
        }
        return outcome;
    }

    static final class ScanOutcome {
        final List<Stmt> stmts = new ArrayList<>();
        int okCount() { int n = 0; for (Stmt s : stmts) if (s.result.getError() == null) n++; return n; }
    }

    static List<Stmt> baselineStmts(ScanOutcome o) { return o.stmts; }

    static Preprocessed preprocess(String raw) {
        Preprocessed out = new Preprocessed();
        String text = raw.replaceAll("\\$\\{[^}]+\\}", "20210101");
        List<String> segments = ScriptSplitter.split(text, D);
        Map<String, String> vars = new LinkedHashMap<>();
        List<String> body = new ArrayList<>();
        for (String seg : segments) {
            String bare = stripLeadingComments(seg);
            Matcher m = VAR_DEF.matcher(bare);
            if (m.matches()) {
                String name = m.group(1);
                String value = substitute(m.group(2).trim(), vars);
                vars.put(name, value);
                out.inlined++;
            } else if (!seg.trim().isEmpty()) {
                body.add(seg);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String seg : body) sb.append(substitute(seg, vars)).append(";\n");
        out.sql = sb.toString();
        return out;
    }

    static String substitute(String sql, Map<String, String> vars) {
        String result = sql;
        boolean changed = true;
        int guard = 0;
        while (changed && guard++ < 10) {
            changed = false;
            for (Map.Entry<String, String> e : vars.entrySet()) {
                Pattern p = Pattern.compile("(?<![A-Za-z0-9_@])@" + Pattern.quote(e.getKey()) + "\\b");
                Matcher m = p.matcher(result);
                if (m.find()) {
                    result = m.replaceAll(Matcher.quoteReplacement("(" + e.getValue() + ")"));
                    changed = true;
                }
            }
        }
        return result;
    }

    static String stripLeadingComments(String s) {
        String t = s.trim();
        while (true) {
            if (t.startsWith("--")) {
                int nl = t.indexOf('\n');
                if (nl < 0) return "";
                t = t.substring(nl + 1).trim();
            } else if (t.startsWith("/*")) {
                int end = t.indexOf("*/");
                if (end < 0) return "";
                t = t.substring(end + 2).trim();
            } else return t;
        }
    }

    static String extractName(String raw) {
        Matcher m = Pattern.compile("(?m)^\\s*--\\s*name\\s*:\\s*(\\S+)").matcher(raw);
        return m.find() ? m.group(1) : "";
    }

    static List<Instruction> expanded(Stmt s) {
        if (s.result.getError() != null) return Collections.emptyList();
        return Instruction.toColumnInstructions(s.result.getInstructions(), s.result.getContext(),
                s.result.getTargetOutputs());
    }

    static String tableOf(String ref) {
        if (ref == null) return "";
        String r = ref.trim();
        int lp = r.indexOf('(');
        if (lp > 0) r = r.substring(0, lp);
        if (r.endsWith(".*")) return r.substring(0, r.length() - 2);
        int dot = r.lastIndexOf('.');
        return dot > 0 ? r.substring(0, dot) : r;
    }

    static String declaredTarget(String sql) {
        if (sql == null) return "";
        Matcher m = Pattern.compile("(?is)\\bINSERT\\s+(?:OVERWRITE\\s+|INTO\\s+)?(?:TABLE\\s+)?([`\"\\[]?[A-Za-z_][A-Za-z0-9_.]*[`\"\\]]?)").matcher(sql);
        if (!m.find()) return "";
        String name = m.group(1).trim();
        int dot = name.lastIndexOf('.');
        if (dot >= 0) name = name.substring(dot + 1);
        return strip(name);
    }

    static boolean isColumnOp(Instruction i) {
        return i.getOperation() == Instruction.OperationType.COLUMN_MAPPING
                || i.getOperation() == Instruction.OperationType.COLUMNS_INJECT;
    }

    static boolean isTableRef(String ref) {
        return ref != null && !ref.startsWith("\u0000") && ref.indexOf('.') > 0;
    }

    static void writeStatements(Path p, List<Stmt> stmts) throws Exception {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(p, StandardCharsets.UTF_8))) {
            w.println("file,statement,name,status,error_class,error_message,instructions,target_tables,source_tables,declared_target,sql_preview");
            for (Stmt s : stmts) {
                Set<String> targets = new TreeSet<>(), sources = new TreeSet<>();
                for (Instruction i : expanded(s)) {
                    if (!isColumnOp(i)) continue;
                    if (!i.isToTemporary() && isTableRef(i.getTo())) targets.add(tableOf(i.getTo()));
                    if (!i.isFromTemporary() && isTableRef(i.getFrom())) sources.add(tableOf(i.getFrom()));
                }
                Throwable e = s.result.getError();
                w.println(csv(s.file) + "," + s.index + "," + csv(s.name) + ","
                        + (e == null ? "OK" : "FAIL") + ","
                        + csv(e == null ? "" : e.getClass().getSimpleName()) + ","
                        + csv(e == null ? "" : firstLine(e.getMessage())) + ","
                        + (e == null ? s.result.getInstructions().size() : 0) + ","
                        + csv(String.join(";", targets)) + ","
                        + csv(String.join(";", sources)) + ","
                        + csv(declaredTarget(s.result.getSql())) + ","
                        + csv(preview(s.result.getSql())));
            }
        }
    }

    static void writeColumnEdges(Path p, List<Stmt> stmts) throws Exception {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(p, StandardCharsets.UTF_8))) {
            w.println("file,statement,operation,source,target,ordinal,source_temporary,target_temporary,"
                    + "relation,function,operator,expression");
            for (Stmt s : stmts) {
                for (Instruction i : expanded(s)) {
                    w.println(csv(s.file) + "," + s.index + "," + i.getOperation() + ","
                            + csv(i.getFrom()) + "," + csv(i.getTo()) + "," + i.getOrdinal() + ","
                            + i.isFromTemporary() + "," + i.isToTemporary() + ","
                            + csv(relationOf(i)) + "," + csv(i.getFunctionName()) + ","
                            + csv(i.getOperator()) + "," + csv(shorten(i.getExpression())));
                }
            }
        }
    }

    static String relationOf(Instruction i) {
        return i.getRelation() == null ? "" : i.getRelation().name();
    }

    /**
     * One hop as {@code RELATION} or {@code RELATION(detail)}, so two paths that share
     * a relation kind but use different functions/operators stay distinguishable.
     */
    static String relationHop(RelationHop hop) {
        String relation = hop.getRelation().name();
        String detail = hop.getFunctionName() != null
                ? hop.getFunctionName() : hop.getOperator();
        if (detail == null || detail.isEmpty()
                || detail.equalsIgnoreCase(relation)) {
            return relation;
        }
        return relation + "(" + escapeDetail(detail) + ")";
    }

    /**
     * Keep the hop format unambiguous when a quoted function name contains a
     * separator, parenthesis or percent sign.
     */
    static String escapeDetail(String detail) {
        StringBuilder out = new StringBuilder(detail.length());
        for (int i = 0; i < detail.length(); i++) {
            char ch = detail.charAt(i);
            if (ch == '%' || ch == '(' || ch == ')' || ch == ';' || ch == '>'
                    || ch == '\\' || ch == '\n' || ch == '\r') {
                out.append('%');
                String hex = Integer.toHexString(ch).toUpperCase();
                if (hex.length() < 2) out.append('0');
                out.append(hex);
            } else {
                out.append(ch);
            }
        }
        return out.toString();
    }

    static final int MAX_RELATION_PATH_CHARS = 512;
    static final int MAX_JOIN_CHARS = 512;

    /** A capped string plus whether this specific value was shortened. */
    static final class Capped {
        final String text;
        final boolean truncated;

        Capped(String text, boolean truncated) {
            this.text = text;
            this.truncated = truncated;
        }
    }

    static Capped relationPath(List<RelationHop> path) {
        if (path == null || path.isEmpty()) return new Capped("", false);
        StringBuilder out = new StringBuilder();
        boolean truncated = false;
        for (RelationHop hop : path) {
            String token = relationHop(hop);
            if (out.length() == 0) {
                if (token.length() > MAX_RELATION_PATH_CHARS) {
                    out.append(safeToken(token, MAX_RELATION_PATH_CHARS));
                    truncated = true;
                    break;
                }
                out.append(token);
            } else if (out.length() + token.length() + 1 > MAX_RELATION_PATH_CHARS) {
                if (out.length() + 2 <= MAX_RELATION_PATH_CHARS) {
                    out.append(">…");
                }
                // With only one character left there is no room for a separator plus
                // an ellipsis; keep the complete prefix and rely on truncated=true.
                truncated = true;
                break;
            } else {
                out.append('>').append(token);
            }
        }
        return new Capped(out.toString(), truncated);
    }

    /**
     * A compact relation chain that drops plain copies and repeated wildcard hops,
     * so the report shows the transformations that actually change the value.
     */
    static Capped relationSummary(List<RelationHop> path) {
        if (path == null || path.isEmpty()) return new Capped("", false);
        Set<String> notable = new LinkedHashSet<>();
        for (RelationHop hop : path) {
            RelationType relation = hop.getRelation();
            if (relation == RelationType.NONE || relation == RelationType.COLUMN_COPY) {
                continue;
            }
            notable.add(relationHop(hop));
        }
        if (notable.isEmpty()) {
            return new Capped(RelationType.COLUMN_COPY.name(), false);
        }
        if (notable.size() > 1) {
            notable.removeIf(hop -> hop.startsWith(RelationType.WILDCARD.name()));
        }
        if (notable.isEmpty()) {
            return new Capped(RelationType.WILDCARD.name(), false);
        }
        return joinSummary(notable, MAX_JOIN_CHARS);
    }

    private static Capped joinSummary(Set<String> tokens, int maxChars) {
        StringBuilder out = new StringBuilder();
        boolean truncated = false;
        for (String token : tokens) {
            if (out.length() == 0) {
                String safe = safeToken(token, maxChars);
                out.append(safe);
                truncated |= safe.length() < token.length();
            } else if (out.length() + token.length() + 1 > maxChars) {
                if (out.length() + 2 <= maxChars) {
                    out.append(">…");
                }
                truncated = true;
                break;
            } else {
                out.append('>').append(token);
            }
        }
        return new Capped(out.toString(), truncated);
    }

    static Capped joinPaths(List<List<RelationHop>> paths, boolean summary) {
        Set<String> texts = new LinkedHashSet<>();
        boolean truncated = false;
        if (paths != null) {
            for (List<RelationHop> path : paths) {
                Capped text = summary ? relationSummary(path) : relationPath(path);
                if (!text.text.isEmpty()) {
                    texts.add(text.text);
                }
                truncated |= text.truncated;
            }
        }
        Capped joined = capValues(texts,
                summary ? MAX_JOIN_CHARS : MAX_RELATION_PATH_CHARS);
        return new Capped(joined.text, truncated || joined.truncated);
    }

    /** Join distinct alternatives with ';'. */
    static Capped capValues(Set<String> values, int maxChars) {
        StringBuilder out = new StringBuilder();
        boolean truncated = false;
        for (String value : values) {
            if (out.length() == 0) {
                if (value.length() > maxChars) {
                    out.append(safeCut(value, maxChars - 1)).append('…');
                    truncated = true;
                    break;
                }
                out.append(value);
            } else if (out.length() + value.length() + 1 > maxChars) {
                if (out.length() + 2 <= maxChars) {
                    out.append(";…");
                }
                truncated = true;
                break;
            } else {
                out.append(';').append(value);
            }
        }
        return new Capped(out.toString(), truncated);
    }

    /**
     * Truncate a single RELATION(detail) token without destroying its structure or a
     * percentile escape.
     */
    static String safeToken(String token, int max) {
        if (token.length() <= max) return token;
        int open = token.indexOf('(');
        if (open <= 0) return safeCut(token, max);
        String relation = token.substring(0, open);
        int close = token.endsWith(")") ? token.length() - 1 : token.length();
        String detail = token.substring(open + 1, close);
        int available = max - relation.length() - 3; // '(' + '…' + ')'
        if (available < 1) return relation;
        return relation + "(" + safeCutEscaped(detail, available) + "…)";
    }

    static String safeCutEscaped(String text, int max) {
        int cut = Math.min(max, text.length());
        int percent = text.lastIndexOf('%', Math.max(cut - 1, 0));
        if (percent >= 0 && cut - percent < 3) {
            cut = percent;
        }
        if (cut > 0 && cut < text.length()
                && Character.isHighSurrogate(text.charAt(cut - 1))) {
            cut--;
        }
        return text.substring(0, Math.max(cut, 0));
    }

    /**
     * Cut at a UTF-16 boundary without leaving a lone surrogate, which would make a
     * UTF-8 PrintWriter fail mid-record.
     */
    static String safeCut(String text, int max) {
        if (text.length() <= max) return text;
        int cut = Math.max(max, 0);
        if (cut > 0 && cut < text.length()
                && Character.isHighSurrogate(text.charAt(cut - 1))) {
            cut--;
        }
        return text.substring(0, cut);
    }

    static boolean isTruncated(List<List<RelationHop>> paths) {
        if (paths != null) {
            for (List<RelationHop> path : paths) {
                for (RelationHop hop : path) {
                    if (hop.isTruncated()) return true;
                }
            }
        }
        return false;
    }

    static Capped joinHops(List<List<RelationHop>> paths,
            java.util.function.Function<RelationHop, String> field) {
        Set<String> out = new LinkedHashSet<>();
        boolean truncated = false;
        if (paths != null) {
            for (List<RelationHop> path : paths) {
                for (RelationHop hop : path) {
                    String value = field.apply(hop);
                    if (value != null && !value.isEmpty()) {
                        boolean shortened = value.length() > 300;
                        String shortValue = shortened
                                ? safeCut(value, 300) + "…" : value;
                        truncated |= shortened;
                        out.add(shortValue);
                    }
                }
            }
        }
        Capped joined = capValues(out, MAX_JOIN_CHARS);
        return new Capped(joined.text, truncated || joined.truncated);
    }

    static String shorten(String text) {
        if (text == null) return null;
        return text.length() > 300 ? safeCut(text, 300) + "…" : text;
    }


    static Set<Column> collectTargets(List<Stmt> stmts) {
        // Compare the parts, not a joined string: a column named "a.b" must stay
        // distinct from a column named "b" in table "t.a".
        Set<Column> targets = new TreeSet<>(Comparator
                .comparing((Column c) -> c.getTableName() == null ? "" : c.getTableName())
                .thenComparing(c -> c.getColumnName() == null ? "" : c.getColumnName())
                .thenComparing(Column::isTemporary)
                .thenComparing(Column::isWildcard));
        for (Stmt s : stmts) {
            for (Instruction i : expanded(s)) {
                if (i.isToTemporary() || i.getTo() == null) continue;
                if (i.getOperation() == Instruction.OperationType.COLUMN_MAPPING
                        || i.getOperation() == Instruction.OperationType.EXPR_MAPPING) {
                    Column target = new Column(i.getTo());
                    if (target.getTableName() != null
                            && target.getColumnName() != null
                            && !target.getColumnName().isEmpty()) {
                        targets.add(target);
                    }
                } else if (i.getOperation() == Instruction.OperationType.COLUMNS_INJECT) {
                    String to = i.getTo();
                    int lp = columnListStart(to);
                    if (lp > 0) {
                        String t = to.substring(0, lp);
                        for (String c : splitIdentifiers(
                                to.substring(lp + 1, to.length() - 1))) {
                            if (!c.trim().isEmpty()) targets.add(new Column(t, strip(c.trim())));
                        }
                    }
                    // An expanded positional wildcard carries the written columns
                    // separately from the target reference.
                    if (i.getPopulatedColumns() != null) {
                        // `to` is a table identity here, not a `table.column` reference;
                        // do not apply tableOf(), which would split `prj.t` into `prj`.
                        String t = i.getWildcardTable() != null
                                ? i.getWildcardTable() : to;
                        int listStart = columnListStart(t);
                        if (listStart > 0) {
                            t = t.substring(0, listStart);
                        }
                        for (String c : splitIdentifiers(i.getPopulatedColumns())) {
                            if (!c.trim().isEmpty()) {
                                targets.add(new Column(t, strip(c.trim())));
                            }
                        }
                    }
                }
            }
        }
        return targets;
    }

    /**
     * Direct physical table dependencies: walk a target column through temporary
     * projections and stop at the first physical source. Unlike the end-to-end
     * column lineage, this keeps intermediate tables such as dwd_content visible.
     */
    static void writeTableDependencies(Path p, List<Stmt> stmts, LineageGraph graph) throws Exception {
        Graph<Column, DefaultEdge> g = graph.getReverseGraph();
        Map<String, Integer> counts = new TreeMap<>();
        for (Column target : collectTargets(stmts)) {
            if (target.getTableName() == null || !g.containsVertex(target)) continue;
            Set<Column> visited = new HashSet<>();
            Deque<Column> stack = new ArrayDeque<>();
            for (DefaultEdge e : g.outgoingEdgesOf(target)) stack.push(g.getEdgeTarget(e));
            while (!stack.isEmpty()) {
                Column c = stack.pop();
                if (!visited.add(c)) continue;
                if (c.isTemporary()) {
                    for (DefaultEdge e : g.outgoingEdgesOf(c)) stack.push(g.getEdgeTarget(e));
                } else if (c.getTableName() != null
                        && !c.getTableName().equals(target.getTableName())) {
                    counts.merge(c.getTableName() + "\u0001" + target.getTableName(), 1, Integer::sum);
                }
            }
        }
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(p, StandardCharsets.UTF_8))) {
            w.println("source_table,target_table,column_edges");
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                String[] k = e.getKey().split("\u0001", 2);
                w.println(csv(k[0]) + "," + csv(k[1]) + "," + e.getValue());
            }
        }
        System.out.println("Direct table dependencies: " + counts.size());
    }

    static void writeColumnLineage(Path p, List<Stmt> stmts, LineageGraph graph) throws Exception {
        Set<Column> targets = collectTargets(stmts);
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(p, StandardCharsets.UTF_8))) {
            w.println("target_table,target_column,source_table,source_column,source_is_wildcard,"
                    + "relation,relation_path,function,operator,expression,truncated");
            for (Column target : targets) {
                Set<Column> srcs;
                try { srcs = graph.getSources(target); } catch (RuntimeException e) { continue; }
                Map<Column, List<List<RelationHop>>> paths;
                try { paths = graph.relationPaths(target); }
                catch (RuntimeException e) { paths = Collections.emptyMap(); }
                List<Column> leaves = new ArrayList<>();
                for (Column c : srcs) {
                    if (c.isTemporary() || c.equals(target)) continue;
                    leaves.add(c);
                }
                leaves.sort(Comparator.comparing(LineageScan::colKey));
                for (Column c : leaves) {
                    List<List<RelationHop>> path = paths.get(c);
                    Capped relationText = joinPaths(path, true);
                    Capped pathText = joinPaths(path, false);
                    Capped functions = joinHops(path, RelationHop::getFunctionName);
                    Capped operators = joinHops(path, RelationHop::getOperator);
                    Capped expressions = joinHops(path, RelationHop::getExpression);
                    boolean truncated = isTruncated(path)
                            || relationText.truncated || pathText.truncated
                            || functions.truncated || operators.truncated
                            || expressions.truncated;
                    w.println(csv(target.getTableName()) + "," + csv(target.getColumnName()) + ","
                            + csv(c.getTableName()) + "," + csv(c.getColumnName()) + "," + c.isWildcard()
                            + "," + csv(relationText.text) + "," + csv(pathText.text)
                            + "," + csv(functions.text) + "," + csv(operators.text)
                            + "," + csv(expressions.text) + "," + truncated);
                }
            }
            if (w.checkError()) {
                throw new IllegalStateException("failed to write " + p);
            }
        }
    }

    static void writeTableLineage(Path p, Path columnLineage) throws Exception {
        Map<String, Set<String>> map = new TreeMap<>();
        aggregateTableLineage(columnLineage, map);
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(p, StandardCharsets.UTF_8))) {
            w.println("target_table,source_table,target_columns");
            for (Map.Entry<String, Set<String>> e : map.entrySet()) {
                String[] k = e.getKey().split("\u0001", 2);
                w.println(csv(k[0]) + "," + csv(k[1]) + "," + e.getValue().size());
            }
        }
    }

    /**
     * Stream the first three columns of column_lineage.csv. The expression column can
     * be very large, so the whole file must not be loaded into memory and later fields
     * are skipped instead of retained. Quoted newlines are still handled.
     */
    static void aggregateTableLineage(Path file, Map<String, Set<String>> map)
            throws Exception {
        try (java.io.PushbackReader reader = new java.io.PushbackReader(
                new java.io.BufferedReader(Files.newBufferedReader(file,
                        StandardCharsets.UTF_8)), 2)) {
            String[] fields = new String[3];
            StringBuilder current = new StringBuilder();
            int field = 0;
            boolean quoted = false;
            boolean header = true;
            int read;
            while ((read = reader.read()) != -1) {
                char ch = (char) read;
                if (quoted) {
                    if (ch == '"') {
                        int next = reader.read();
                        if (next == '"') {
                            if (field < 3) current.append('"');
                        } else {
                            quoted = false;
                            if (next != -1) reader.unread(next);
                        }
                    } else if (field < 3) {
                        current.append(ch);
                    }
                    continue;
                }
                if (ch == '"') {
                    quoted = true;
                } else if (ch == ',') {
                    if (field < 3) fields[field] = current.toString();
                    current.setLength(0);
                    field++;
                } else if (ch == '\n') {
                    if (field < 3) fields[field] = current.toString();
                    current.setLength(0);
                    if (!header) addTableLineageEdge(map, fields);
                    header = false;
                    fields = new String[3];
                    field = 0;
                } else if (ch != '\r') {
                    if (field < 3) current.append(ch);
                }
            }
            if (current.length() > 0 || field > 0) {
                if (field < 3) fields[field] = current.toString();
                if (!header) addTableLineageEdge(map, fields);
            }
        }
    }

    private static void addTableLineageEdge(Map<String, Set<String>> map,
            String[] fields) {
        if (fields[0] == null || fields[2] == null) return;
        map.computeIfAbsent(fields[0] + "\u0001" + fields[2], k -> new TreeSet<>())
                .add(fields[1] == null ? "" : fields[1]);
    }

    static void writeRepairs(Path p, List<String> repairs) throws Exception {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(p, StandardCharsets.UTF_8))) {
            w.println("statement,table,added_column");
            for (String r : repairs) w.println(r);
        }
    }

    static void writeMissingTables(Path p, List<Stmt> stmts, MetadataCatalog catalog) throws Exception {
        Set<String> missing = new TreeSet<>();
        for (Stmt s : stmts) {
            if (s.result.getError() == null) continue;
            Matcher m = TBL_MISSING.matcher(String.valueOf(s.result.getError().getMessage()));
            if (m.find()) {
                String name = strip(m.group(1).trim());
                try { if (catalog.findAllTables(name).isEmpty()) missing.add(name); }
                catch (RuntimeException e) { missing.add(name); }
            }
        }
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(p, StandardCharsets.UTF_8))) {
            w.println("missing_table");
            for (String t : missing) w.println(csv(t));
        }
    }

    // ------------------------------------------------------------------
    static String colKey(Column c) {
        return (c.getTableName() == null ? "" : c.getTableName()) + "." + c.getColumnName();
    }

    /**
     * Start of a trailing top-level column list, or -1. Parentheses inside quoted
     * identifiers (for example a table named {@code "t(x"}) are not a column list.
     */
    static int columnListStart(String value) {
        if (value == null || !value.endsWith(")")) return -1;
        char quote = 0;
        int depth = 0;
        int candidate = -1;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (quote != 0) {
                if (ch == quote) quote = 0;
                continue;
            }
            if (ch == '"' || ch == '`') {
                quote = ch;
            } else if (ch == '[') {
                quote = ']';
            } else if (ch == '(') {
                if (depth == 0) candidate = i;
                depth++;
            } else if (ch == ')') {
                depth--;
            }
        }
        return depth == 0 ? candidate : -1;
    }

    static String strip(String s) {
        s = s.trim();
        if (s.length() >= 2) {
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            String inner = s.substring(1, s.length() - 1);
            if (first == '`' && last == '`') {
                return inner.replace("``", "`");
            }
            if (first == '"' && last == '"') {
                return Identifiers.decodeDoubleQuoted(inner);
            }
            if (first == '[' && last == ']') {
                return inner.replace("]]", "]");
            }
        }
        return s;
    }

    static String preview(String sql) {
        if (sql == null) return "";
        String p = sql.replaceAll("\\s+", " ").trim();
        return p.length() > 140 ? p.substring(0, 140) : p;
    }

    static String firstLine(String s) {
        if (s == null) return "";
        int nl = s.indexOf('\n');
        String line = nl >= 0 ? s.substring(0, nl) : s;
        return line.length() > 240 ? line.substring(0, 240) : line;
    }

    static String csv(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    /**
     * Split a column list on commas outside quotes. Hive quoted identifiers use
     * backticks, so a plain {@code split(",")} would break a column named {@code a,b}.
     */
    static List<String> splitIdentifiers(String list) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < list.length(); i++) {
            char ch = list.charAt(i);
            if (quote != 0) {
                cur.append(ch);
                if (ch == quote) {
                    if (i + 1 < list.length() && list.charAt(i + 1) == quote) {
                        cur.append(list.charAt(++i));
                    } else {
                        quote = 0;
                    }
                }
            } else if (ch == '`' || ch == '"' || ch == '\'') {
                quote = ch;
                cur.append(ch);
            } else if (ch == '[') {
                quote = ']';
                cur.append(ch);
            } else if (ch == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        out.add(cur.toString());
        return out;
    }

}

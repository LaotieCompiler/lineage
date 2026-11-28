import com.alibaba.druid.DbType;
import com.laotie.LineageOptions;
import com.laotie.LineageScanner;
import com.laotie.model.Instruction;
import com.laotie.model.LineageGraph;
import com.laotie.model.LineageResult;
import com.laotie.model.ScanResult;
import com.laotie.model.metadata.Column;
import com.laotie.model.metadata.MetadataCatalog;
import com.laotie.model.metadata.MetadataContext;
import com.laotie.model.metadata.MetadataMode;
import com.laotie.model.metadata.Table;
import com.laotie.model.metadata.TableLocation;
import com.laotie.parse.ScriptSplitter;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultEdge;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class OdpsLineage {
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
            if (D == DbType.hive) ddl = ddl.replaceAll("(?i)LIFECYCLE\\s+\\d+", "");
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
        System.out.println("\nArtifacts written to " + outDir.toAbsolutePath());
    }

    // ------------------------------------------------------------------
    static final class Options {
        DbType dialect = DbType.hive;
        Path ddlDir;
        List<Path> sqlDirs = new ArrayList<>();
        Path outDir = Paths.get("output");
        List<String> extensions = new ArrayList<>(Collections.singletonList("osql"));
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
        out.println("      --ext <list>      comma-separated extensions (default: osql)");
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
        out.println("  run.sh --ddl /data/ddl --sql /data/dwd --sql /data/dws --output output --no-render");
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
            w.println("file,statement,operation,source,target,ordinal,source_temporary,target_temporary");
            for (Stmt s : stmts) {
                for (Instruction i : expanded(s)) {
                    w.println(csv(s.file) + "," + s.index + "," + i.getOperation() + ","
                            + csv(i.getFrom()) + "," + csv(i.getTo()) + "," + i.getOrdinal() + ","
                            + i.isFromTemporary() + "," + i.isToTemporary());
                }
            }
        }
    }

    static Set<Column> collectTargets(List<Stmt> stmts) {
        Set<Column> targets = new TreeSet<>(Comparator.comparing(OdpsLineage::colKey));
        for (Stmt s : stmts) {
            for (Instruction i : expanded(s)) {
                if (i.isToTemporary() || i.getTo() == null) continue;
                if (i.getOperation() == Instruction.OperationType.COLUMN_MAPPING
                        || i.getOperation() == Instruction.OperationType.EXPR_MAPPING) {
                    String t = tableOf(i.getTo());
                    String c = i.getTo().substring(i.getTo().lastIndexOf('.') + 1);
                    if (!t.isEmpty() && !c.isEmpty()) targets.add(new Column(t, c));
                } else if (i.getOperation() == Instruction.OperationType.COLUMNS_INJECT) {
                    String to = i.getTo();
                    int lp = to.indexOf('(');
                    if (lp > 0 && to.endsWith(")")) {
                        String t = to.substring(0, lp);
                        for (String c : to.substring(lp + 1, to.length() - 1).split(",")) {
                            if (!c.trim().isEmpty()) targets.add(new Column(t, strip(c.trim())));
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
            w.println("target_table,target_column,source_table,source_column,source_is_wildcard");
            for (Column target : targets) {
                Set<Column> srcs;
                try { srcs = graph.getSources(target); } catch (RuntimeException e) { continue; }
                List<Column> leaves = new ArrayList<>();
                for (Column c : srcs) {
                    if (c.isTemporary() || c.equals(target)) continue;
                    leaves.add(c);
                }
                leaves.sort(Comparator.comparing(OdpsLineage::colKey));
                for (Column c : leaves) {
                    w.println(csv(target.getTableName()) + "," + csv(target.getColumnName()) + ","
                            + csv(c.getTableName()) + "," + csv(c.getColumnName()) + "," + c.isWildcard());
                }
            }
        }
    }

    static void writeTableLineage(Path p, Path columnLineage) throws Exception {
        Map<String, Set<String>> map = new TreeMap<>();
        List<String> lines = Files.readAllLines(columnLineage, StandardCharsets.UTF_8);
        for (int i = 1; i < lines.size(); i++) {
            String[] f = parseCsv(lines.get(i));
            if (f.length < 4) continue;
            map.computeIfAbsent(f[0] + "\u0001" + f[2], k -> new TreeSet<>()).add(f[1]);
        }
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(p, StandardCharsets.UTF_8))) {
            w.println("target_table,source_table,target_columns");
            for (Map.Entry<String, Set<String>> e : map.entrySet()) {
                String[] k = e.getKey().split("\u0001", 2);
                w.println(csv(k[0]) + "," + csv(k[1]) + "," + e.getValue().size());
            }
        }
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

    static String strip(String s) {
        s = s.trim();
        if (s.length() >= 2 && ((s.startsWith("`") && s.endsWith("`"))
                || (s.startsWith("\"") && s.endsWith("\"")))) {
            return s.substring(1, s.length() - 1);
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

    static String[] parseCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (q) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                    else q = false;
                } else cur.append(ch);
            } else if (ch == '"') q = true;
            else if (ch == ',') { out.add(cur.toString()); cur.setLength(0); }
            else cur.append(ch);
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }
}

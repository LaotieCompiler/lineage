# lineage

Column-level data lineage for SQL.

`lineage` parses SQL with [Alibaba Druid](https://github.com/alibaba/druid), resolves tables
and columns against a metadata catalog, and produces table- and column-level lineage for
scripts, logs, and schema changes. It is a Java 8 library with a command-line runner and a
generated, self-contained HTML report.

[中文](README_zh.md)

## Highlights

- Column-level edges with relation types: copy, expression, function, aggregate, cast,
  `CASE`, scalar subquery, wildcard, and constant.
- Whole-script analysis: `USE` / `SET`, temporary tables, CTEs, and relations created,
  altered, or dropped by DDL are visible to later statements in the same scan.
- Metadata comes from analyzed DDL or a serialized catalog file; no live database
  connection is required.
- `STRICT` and `OPTIONAL` metadata modes, plus cross-statement metadata learning.
- Dialects are provided by Druid: Oracle, SQL Server, PostgreSQL, MySQL/MariaDB, DB2,
  H2/HSQLDB/Derby, SQLite, Hive, and Spark.
- A CLI scanner that turns a directory of DDL and SQL into CSV artifacts and an interactive
  HTML report.

## Quick start

```java
MetadataCatalog catalog = new MetadataCatalog(DbType.hive);
catalog.loadDdl(createTableStatements);

LineageScanner scanner = LineageScanner.withMetadata(
        MetadataContext.of(catalog), LineageOptions.defaults());
ScanResult scan = scanner.scan(sqlScript);

for (LineageResult statement : scan.getResults()) {
    if (statement.getError() != null) {
        // A failing statement is reported here; the rest of the script still runs.
        continue;
    }
    for (Instruction edge : statement.getInstructions()) {
        // edge.getFrom() -> edge.getTo()
    }
}

LineageGraph graph = scan.getGraph();
```

To analyze without a metadata catalog, use `OPTIONAL` mode. Unresolved wildcards stay
table-level instead of inventing column names:

```java
ScanResult scan = LineageScanner.withoutMetadata(
        LineageOptions.of(MetadataMode.OPTIONAL, true)).scan(sqlScript);
```

## Metadata

### Sources

Metadata is loaded by analyzing DDL or by reading a serialized catalog file. A catalog can
be exported as a self-describing DDL text file whose header carries the dialect, version,
and checksum, then loaded back directly without re-analyzing the source scripts:

```java
MetadataCatalog catalog = new MetadataCatalog(DbType.hive);
catalog.loadDdl(createTableStatements);

catalog.saveDdl(Paths.get("metadata.sql"));

MetadataCatalog reloaded = MetadataCatalog.fromDdlFile(Paths.get("metadata.sql"));
```

### Modes

`LineageOptions` selects how missing metadata is treated:

| mode | behavior |
| --- | --- |
| `STRICT` (default) | metadata is authoritative: a table missing from the catalog throws `MetadataNotFoundException`, an unmatched column throws `ColumnNotFoundException`, and an ambiguous column throws `AmbiguousColumnException` |
| `OPTIONAL` | a table missing from the catalog is still analyzed: its wildcard stays a wildcard (`a.* -> t.*`) instead of being expanded into invented column names, and the table-level edge is kept in the graph. An ambiguous column still throws `AmbiguousColumnException` |

### Learned metadata

With inference enabled, `select A.c1, B.c2 from A,B` records that `c1` belongs to `A` and
`c2` belongs to `B`; a later `select c1 from A,B` resolves to `A.c1` instead of failing. A
scan runs two passes: the first discovers metadata from the whole log, and the second
performs column-level lineage against the completed picture, so a statement can be resolved
by knowledge that appears later in the same log. Learned metadata is scoped to the scanner
instance and is never used to expand a wildcard.

### Strict quoted identifiers

Pass `true` as the third argument to require every table and column reference, table alias,
and CTE name to be written with identifier quotes. An unquoted identifier throws
`UnquotedIdentifierException`:

```java
LineageScanner.withoutMetadata(
        LineageOptions.of(MetadataMode.OPTIONAL, false, true))
        .scan(sqlScript);
```

Strict quoting also validates table aliases and CTE names, and covers PostgreSQL
`RETURNING` / `ON CONFLICT`, MySQL `ON DUPLICATE KEY UPDATE`, and SQL Server date parts.

### Metadata-free contract

When a table's columns are unknown, the projection's column positions are unknown too. In
that case the analyzer keeps table-level edges and never guesses a column:

- `select * from missing` yields `missing.* -> t.*`, not an invented `missing.col1`;
- a wildcard over unknown tables inside a derived table or `UNION` propagates as a
  table-level edge through the outer query;
- explicit projections that follow such a wildcard have no determinable position, so no
  column-level edge is emitted for them;
- as soon as every participating source is fully known (catalog, DDL, or inferred), the
  projection is mapped column by column again, including `JOIN ... USING` merges and
  `UNION` output names.

A table function is identified by its call; its rendered text is never treated as a
relation name, so it cannot be resolved through the catalog or a CTE and never contributes
a static width.

## DDL support

- `CREATE TABLE ... AS SELECT` and `CREATE VIEW ... AS SELECT` write lineage into the new
  relation. A declared column list (`CREATE TABLE x (a, b) AS ...`) names the target
  columns.
- Relations created, replaced, altered, or dropped by DDL are visible to later statements in
  the same scanned script, including in strict mode.
- `ALTER TABLE ... ADD COLUMN` / `DROP COLUMN` updates the session view of the table.
- `DELETE` and `TRUNCATE` write no columns, so they produce no column-level edges; in strict
  mode a missing table still fails the statement.
- `CREATE INDEX`, `EXPLAIN`, and `GRANT` are not analyzed: the statement is recorded as a
  failure and the scan continues with the next statement.

## Command-line scanner

[`scripts/lineage-scan/run.sh`](scripts/lineage-scan/run.sh) scans a project of DDL and SQL
files and writes table- and column-level lineage, together with a static HTML report and the
data file it reads.

```bash
./scripts/lineage-scan/run.sh --input /path/to/project --output output
```

By default the scanner:

- uses `<input>/ddl` as the DDL directory;
- scans the other `<input>` subdirectories that contain `.sql` files, ordering them as
  `dwd,dws,ads,bi,ods,dim,other`;
- writes to `./output`, relative to the current working directory;
- also writes `lineage-data.js` and `lineage-report.html`; open the HTML file in a browser.

A recommended input layout:

```
project/
├── ddl/      # table definitions, *.sql
├── dwd/      # detail-layer SQL
├── dws/      # summary-layer SQL
├── ads/      # application-layer SQL
└── bi/       # BI-layer SQL
```

SQL directories are scanned in dependency order: columns observed in an earlier statement
are available to later statements.

Directories can also be passed explicitly:

```bash
./scripts/lineage-scan/run.sh \
  --ddl /data/project/ddl \
  --sql /data/project/dwd \
  --sql /data/project/dws \
  --sql /data/project/ads \
  --output output
```

The positional form `<dialect> <ddl-dir> <sql-dir>... <out-dir>` is still accepted.

### Options

| option | description |
| --- | --- |
| `-i, --input <dir>` | project root; auto-discovers the DDL and SQL directories |
| `-d, --ddl <dir>` | DDL directory; defaults to `<input>/ddl` |
| `-s, --sql <dir>` | SQL directory, repeatable |
| `-o, --output <dir>` | output directory, default `./output` |
| `--dialect <name>` | Druid dialect, default `hive`; for example `spark`, `mysql`, `postgresql` |
| `--ext <list>` | comma-separated extensions, default `sql`, for example `sql,ddl` |
| `--order <list>` | auto-discovery priority, default `dwd,dws,ads,bi,ods,dim,other` |
| `--recursive` | scan subdirectories recursively |
| `--strict` | `STRICT` metadata mode (default `OPTIONAL`, missing tables allowed) |
| `--no-infer` | disable cross-statement metadata inference |
| `--no-repair` | disable schema reconciliation |
| `--no-preprocess` | disable `${var}` substitution and `@var :=` inlining |
| `-h, --help` | show help |

Relative paths (`--input`, `--ddl`, `--sql`, `--output`) are resolved against the current
working directory, not the script directory.

### Outputs

Outputs land in `output/`:

| file | content |
| --- | --- |
| `statements.csv` | per-statement status, error, read/written tables, `declared_target`, SQL preview |
| `column_edges.csv` | parser instructions (including temporary tables) with `relation`, `function`, `operator`, and `expression` |
| `column_lineage.csv` | end-to-end lineage from target to physical source, with compressed `relation`, full `relation_path`, target expression, and `truncated` |
| `table_dependencies.csv` | direct table dependencies, stopping at the first physical table |
| `table_lineage.csv` | table-level aggregation of end-to-end column edges |
| `schema_repairs.csv` | columns added to reconcile SQL references with the DDL snapshot |
| `missing_tables.csv` | tables that were referenced but not cataloged |
| `run-info.txt` | inputs, dialect, switches, and counts for the run |
| `lineage-data.js` | data loaded by the HTML report |
| `lineage-report.html` | interactive visualization; reads `lineage-data.js` from the same directory |

### Relation types

`relation` describes how a source column reaches a target column:

| value | meaning |
| --- | --- |
| `COLUMN_COPY` | direct copy, or `SELECT *` expansion |
| `EXPRESSION` | arithmetic/comparison/boolean expression; `operator` is the nearest operator |
| `FUNCTION` | argument of a scalar function; `function` is the nearest function name |
| `AGGREGATE` | argument of an aggregate function such as `SUM` or `COUNT` |
| `CAST` | explicit type conversion |
| `CASE` | inside a `CASE WHEN` branch |
| `SUBQUERY` | from a scalar subquery |
| `WILDCARD` | table-level or positional wildcard injection |
| `CONSTANT` | literal expression with no source column |

`column_lineage.csv` compresses the chain end to end: pure copies and repeated wildcard
hops are removed, and each remaining hop is written as `FUNCTION(UPPER)` or
`EXPRESSION(+)`. The full chain is kept in `relation_path`. When one source column has
several derivation paths (for example a `UNION` branch that copies and another that
computes, or `UPPER` and `LOWER` branches), the paths are separated by `;` instead of
overwriting one another. Separators inside function names and operators are percent-escaped
(for example `FUNCTION(f%3Bg)`). Paths longer than 1024 hops keep the target-side hops and
set `truncated=true`; the report shows “path truncated” so an incomplete computation chain
is not mistaken for a plain copy.

The analyzer assigns the relation closest to the column: in `SUM(UPPER(a))` the relation of
`a` is `FUNCTION` with function `UPPER`, while `expression` keeps the whole projection text.

### HTML report

`lineage-report.html` loads only `lineage-data.js` from the same directory. It makes no
network requests and needs no server, CDN, or Node.js. `lineage-data.js` exposes five groups
— `statements`, `tableLineage`, `tableDependencies`, `columnLineage`, and `repairs` — on
`window.LINEAGE_DATA`, so a custom page can reuse the same data.

The report behavior:

- assigns tables to layers by name prefix: `ods/dim/mig` -> ODS/DIM, `dwd` -> DWD,
  `dws` -> DWS, `ads/bi` -> ADS/BI;
- orders each layer with three barycenter passes over neighbour positions to reduce edge
  crossings;
- draws edges from the right side of a source table to the left side of its target with
  cubic Bezier curves;
- highlights related nodes and edges on hover or focus, and shows upstream/downstream tables
  and written columns when a node is clicked;
- zooms around the pointer with the wheel, pans by dragging, and restores the full view with
  “fit window”;
- follows the system light/dark theme, with a manual toggle in the top-right corner.

Data sources:

- the table graph uses `table_dependencies.csv`, which walks each target column through
  temporary projections and stops at the first physical table, keeping intermediate
  DWD/DWS tables;
- the field table uses `column_lineage.csv`, the end-to-end closure ending at physical
  source columns;
- target tables of failed statements come from `declared_target` in `statements.csv` and are
  drawn with a dashed red border, so missing upstream data is not mistaken for “no
  upstream”.

### SQL preprocessing

- `${name}` scheduler placeholders are replaced with a fixed date (`20210101`) so scripts
  with template variables can be parsed;
- script variables `@name := SELECT …;` are recursively inlined as `(SELECT …)` derived
  tables.

Disable both with `--no-preprocess`.

### Schema reconciliation

A DDL snapshot can lag behind the SQL. The scanner iterates over
`ColumnNotFoundException` failures: when the listed table exists in the catalog, the
referenced column is added to the in-memory catalog copy used by this analysis. The original
DDL is not modified, and `schema_repairs.csv` records every addition. Disable it with
`--no-repair`.

## Architecture

`DmlLineage` is the public facade. It owns statement-level state, parses scripts, tracks the
session (`USE` / `SET`), and dispatches each statement; the analysis itself is split into
package-private collaborators:

| class | responsibility |
| --- | --- |
| `DmlLineage` | public API, session/script orchestration, statement dispatch, table and instruction registry |
| `QueryDispatcher` | `SELECT` / `UNION` / `WITH` dispatch and static shape scopes |
| `SelectAnalyzer` | projection analysis, outer references, scalar subqueries |
| `SourcePlanner` | `FROM` / `JOIN` source resolution and star plans |
| `ExpressionAnalyzer` | expression traversal, owner resolution, unqualified column resolution |
| `TargetShapeAnalyzer` | target output columns, wildcard spans, unknown column groups |
| `StarProjectionAnalyzer` | column mapping and wildcard emission for `SELECT *` |
| `StaticShapeAnalyzer` | static width and output-name inference |
| `DmlStatementAnalyzer` | `INSERT` / `REPLACE` / `UPDATE` / `DELETE` / `MERGE` / `TRUNCATE` |
| `LearningAnalyzer` | explicit-reference metadata discovery |
| `SourceShapeAnalyzer` | source width/name knownness queries |
| `TableCollector` | physical table collection without column analysis |
| `ColumnMappingAnalyzer` | column identity resolution and mapping edges |

Supporting packages hold the dialect strategy (`dialect`), identifier value objects
(`identifier`), statement splitting and parser fallback (`parse`), session/metadata
resolution (`metadata`), strict-quoting validation (`validation`), and the shared analysis
state and model types (`analysis`).

## Build and test

The project targets Java 8. Run the full suite with:

```bash
mvn test
```

JUnit 4 and JUnit 5 tests run together through Maven Surefire. Druid is pinned to 1.2.23
because its parser output is part of the tested contract.

### Golden corpus

`src/test/resources/golden` holds data-driven regression cases. Each JSON case declares a
dialect, analysis options, an optional catalog DDL, a SQL script, and the expected
per-statement instructions or error. `GoldenLineageTest` runs every case, then re-runs each
one under deterministic token mutations to prove malformed SQL never escapes the scanner.

After an intentional behavior change, regenerate the expectations and review the JSON diff:

```bash
mvn test -Dtest=GoldenLineageTest -Dgolden.update=true
```

### External TPC-H corpus

`TpchCorpusTest` analyzes the 22 TPC-H queries against `src/test/resources/tpch/schema.sql`
and asserts that every query produces lineage and is deterministic. The queries are not
committed (TPC license); download them first:

```bash
./scripts/fetch-tpch-corpus.sh
mvn test -Dtest=TpchCorpusTest
```

Without the download the test is skipped, so offline builds stay green.

### Differential check (optional)

[SQLGlot](https://github.com/tobymao/sqlglot) provides an independent implementation of
column lineage. It is a second opinion, not an oracle: the script compares single-statement
`SELECT` cases from the golden corpus with its lineage and reports differences and
unsupported constructs for manual review.

```bash
python3 -m venv target/sqlglot-venv
target/sqlglot-venv/bin/pip install -r scripts/sqlglot-requirements.txt
target/sqlglot-venv/bin/python scripts/differential-sqlglot.py
```

The check is not part of the Maven build or CI. Pass `--fail-on-diff` to make confirmed
differences exit non-zero.

### Continuous integration

GitHub Actions (`.github/workflows/ci.yml`) runs `mvn clean test` on every push and pull
request.

## Known limitations

- Cross-correlated `LATERAL` bodies, forward-referenced recursive CTEs, repeated derived
  output names, and unions that mix unknown-width wildcards with explicit projections are
  analyzed on a best-effort basis. When a position cannot be derived unambiguously, the
  analyzer keeps table-level edges instead of inventing a column.
- Nested derived tables whose `*` expands a `UNION` of cataloged and uncataloged branches
  are best-effort for the same reason.
- Statements using constructs the parser does not model — for example `LATERAL VIEW` output
  columns, table functions, and `VALUES` table constructors — are recorded as failures and
  produce no column edges. The report marks their target tables with a dashed red border.
- Derby JDBC escape syntax (`{fn TIMESTAMPADD(...)}`) is not represented in Druid's AST and
  carries no source span. Strict mode records escape provenance as the rendered call
  signature; a hand-edited AST that reproduces an equivalent call's arguments may inherit
  the unit-keyword exemption.
- Temporary tables and learned columns are scoped to one `LineageScanner` instance.

## License

This project is licensed under the Apache-2.0 license — see the [LICENSE](LICENSE) file for
details.

# lineage

A data lineage parser in columns level.

[中文](README_zh.md)

## AST Parser

The SQL is parsed into an AST (Abstract Syntax Tree) by [Alibaba Druid](https://github.com/alibaba/druid).

The AST is traversed in post-order: nested queries and derived tables are processed before the select list of the query that consumes them, then the lineage information in table and column level is collected.

## Code structure

`DmlLineage` is the public facade. It owns the statement-level state, parses scripts,
tracks the session (`USE` / `SET`), and dispatches each statement; the analysis itself is
split into package-private collaborators:

| Class | Responsibility |
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

## Order of Execution in SQL

| Clause         | Function                                                                                                          |
| -------------- | ----------------------------------------------------------------------------------------------------------------- |
| FROM / JOIN    | When you write any query, SQL starts by identifying the tables for the data retrieval and how they are connected. |
| WHERE          | It acts as a filter; it filters the record based on the conditions specified by the users.                        |
| GROUP BY       | The filtered data is grouped based on the specified condition.                                                    |
| HAVING         | It is similar to the WHERE clause but applied after grouping the data.                                            |
| SELECT         | The clause selects the columns to be included in the final result.                                                |
| DISTINCT       | Remove the duplicate rows from the result. Once you apply this clause, you are only left with distinct records.   |
| ORDER BY       | It sorts (increasing/decreasing/A->Z/Z->A) the results based on the specified condition.                          |
| LIMIT / OFFSET | It determines the number of records to return and from where to start.                                            |

## Tests

Run the full unit suite with `mvn test` (currently 937 tests; 2 are always skipped and the
optional TPC-H corpus is skipped until its queries are fetched).

The Docker-backed JDBC integration tests start local MySQL and PostgreSQL containers and
run `JdbcMetadataScannerDockerTest`:

```bash
./scripts/run-jdbc-docker-tests.sh
```

### golden corpus

`src/test/resources/golden` holds 41 data-driven regression cases. Each JSON case declares
a dialect, analysis options, an optional catalog DDL, a SQL script and the expected
per-statement instructions or error. `GoldenLineageTest` runs every case, then re-runs each
one under deterministic token mutations to prove malformed SQL never escapes the scanner.

After an intentional behavior change, regenerate the expectations and review the JSON diff
before committing it:

```bash
mvn test -Dtest=GoldenLineageTest -Dgolden.update=true
```

### external TPC-H corpus

`TpchCorpusTest` analyzes the 22 TPC-H queries against `src/test/resources/tpch/schema.sql`
and asserts that every query produces lineage and is deterministic. The queries are not
committed (TPC license); download them first:

```bash
./scripts/fetch-tpch-corpus.sh
mvn test -Dtest=TpchCorpusTest
```

Without the download the test is skipped, so offline builds stay green.

### build

The project targets Java 8. JUnit 4 and JUnit 5 tests run together through Maven Surefire.
Druid is pinned to 1.2.23 because its parser output is part of the tested contract; a Druid
upgrade is a separate change that must pass the full regression suite.

GitHub Actions (`.github/workflows/ci.yml`) runs `mvn clean test` and the Docker-backed
JDBC suite on every push and pull request.

## Feature

### Supported DB

    Oracle
    MS SQL Server and Sybase
    Postgres
    MySQL and MariaDB
    DB2
    H2 and HSQLDB and Derby
    SQLite
    Hive
    Spark (Hive-compatible statements)

### select parser

1. `insert into A (c1, c2) from select c1, c2`
2. `select 1, 2` // ignore constants
3. `select 1 as c1 from A` // ignore constants
4. `select now() as c1 from A`
5. `select c1+c2 from A`
6. `select max(c1) from A`
7. `select a1,a2 from (select b1, b2 from (select c1,c2 from C))`
8. `SELECT TB.id as bid, TC.id as cid, id as aid FROM TA Left Join (select id, ba1 b1, ba2 b2 from TBA )TB ON TA.id = TB.id`
9. `select * from A` // dependence on metadata

### DDL

- `CREATE TABLE ... AS SELECT` and `CREATE VIEW ... AS SELECT` write lineage into the new
  relation. A declared column list (`CREATE TABLE x (a, b) AS ...`) names the target
  columns.
- Relations created, replaced, altered or dropped by DDL are visible to later statements in
  the same scanned script, including strict mode.
- `ALTER TABLE ... ADD COLUMN` / `DROP COLUMN` updates the session view of the table.
- `DELETE` and `TRUNCATE` write no columns, so they produce no column-level edges; in
  strict mode a missing table still fails the statement.
- `CREATE INDEX`, `EXPLAIN` and `GRANT` remain unsupported: the statement is recorded as a
  failure and the scan continues with the next statement.

### metadata

1. Some column definitions are ambiguous, such as `*` and the same column name coming from multiple source tables. We therefore introduce metadata from the real database to determine the source table.
2. While parsing, the analyzer generates metadata for intermediate tables and keeps it for later checks.
3. The original instructions are executed with metadata to obtain the fully qualified column name for each column.

#### analysis modes

`LineageOptions` selects how missing metadata is treated:

| mode | behavior |
| --- | --- |
| `STRICT` (default) | metadata is authoritative: a table missing from the catalog throws `MetadataNotFoundException`, an unmatched column throws `ColumnNotFoundException`, and an ambiguous column throws `AmbiguousColumnException` |
| `OPTIONAL` | a table missing from the catalog is still analyzed: its wildcard stays a wildcard (`a.* -> t.*`) instead of being expanded into invented column names, and the table-level edge is kept in the graph. An ambiguous column still throws `AmbiguousColumnException` |

```java
// strict (default)
new LineageScanner(context).scan(sqlLog);

// tolerate missing tables, keep wildcards
LineageScanner.withoutMetadata(LineageOptions.of(MetadataMode.OPTIONAL, false))
        .scan(sqlLog);

// tolerate missing tables and learn columns from earlier statements
LineageScanner.withoutMetadata(LineageOptions.of(MetadataMode.OPTIONAL, true))
        .scan(sqlLog);
```
#### strict quoted identifiers

Pass `true` as the third argument to require every table and column reference, table alias,
and CTE name to be written with identifier quotes. An unquoted identifier throws
`UnquotedIdentifierException`:

```java
LineageScanner.withoutMetadata(
        LineageOptions.of(MetadataMode.OPTIONAL, false, true))
        .scan(sqlLog);
```
Strict quoting also validates table aliases and CTE names, and covers PostgreSQL
`RETURNING`/`ON CONFLICT`, MySQL `ON DUPLICATE KEY UPDATE`, and SQL Server date parts.

Permanent limitation (not planned): Derby JDBC escape syntax (`{fn TIMESTAMPADD(...)}`) is
not represented in Druid's AST and carries no source span.

Strict mode therefore records escape provenance as the rendered call signature on the parsed
call node. An untouched AST or a clone is handled correctly, but a hand-edited AST that
replaces an escape call's arguments with those of an equivalent ordinary call produces the
same signature and may inherit the unit exemption. The two requirements cannot be separated
without changing the contract: rejecting those edits would also reject untouched clones.
The strict-syntax review batch was closed after 22 failed rounds on this adversarial case;
ordinary SQL is unaffected.



With inference enabled, a statement such as `select A.c1, B.c2 from A,B` records that `c1`
belongs to `A` and `c2` belongs to `B`; a later `select c1 from A,B` resolves to `A.c1`
instead of failing. A scan runs two passes: the first walks the whole log to discover
metadata only, the second performs column-level lineage against the completed picture, so a
statement can be resolved by knowledge that appears later in the same log. Learned metadata
is scoped to the scanner instance and never used to expand a wildcard.

#### metadata-free contract

When a table's columns are unknown, the projection's column **positions** are unknown too.
In that case the analyzer keeps table-level edges and never guesses a column:

- `select * from missing` yields `missing.* -> t.*`, not invented `missing.col1`;
- a wildcard over unknown tables inside a derived table or `UNION` propagates as a
  table-level edge through the outer query;
- explicit projections that follow such a wildcard have no determinable position, so no
  column-level edge is emitted for them;
- as soon as every participating source is fully known (catalog, DDL, or inferred), the
  projection is mapped column by column again, including `JOIN ... USING` merges and
  `UNION` output names.

The contract is intentionally bounded. Correlated `LATERAL` bodies, forward-referenced
recursive CTEs, repeated derived output names, and unions that mix unknown-width wildcards
with explicit projections are analyzed on a best-effort basis: when a position cannot be
derived unambiguously the analyzer keeps table-level edges instead of inventing a column.
These combinations are covered by regression tests for the cases listed above, but are not
guaranteed to resolve every further nesting of the same features to column level.

#### Table-function identity

A table function is identified by its call only; its rendered call text is never treated as
a relation name. It is therefore never resolved through the catalog or the CTE scope, and
it never contributes a static width or output names. Two edge cases that used to violate
this rule are now covered by regression tests:

- a catalog table literally named `"generate_series(1, 3)"` no longer hijacks
  `SELECT generate_series.generate_series FROM generate_series(1, 3)`;
- an unused CTE with the same name no longer imposes its width on a `UNION` of table
  functions.

All other table-function learning and owner-resolution cases in this area are covered by
regression tests as well.

#### Review status

The Hive/Spark support batch was closed after 31 failed Codex review rounds. Every reported
finding was fixed before closure; the final fix was locale-independent matching of Spark
ANSI settings. No further adversarial review rounds were run for that batch.

#### Known limitation: nested derived/union output names

Cross-database resolution is covered by regression tests, but nested derived tables whose
`*` expands a UNION of quoted and uncataloged branches are best-effort. The review batch was
closed at 22 failed rounds when a requested case required a named column (`ext.missing.p`)
from a table that was explicitly left uncataloged; that column cannot be derived under the
metadata-free contract. When a branch's output names are unknown, the analyzer keeps
table-level edges instead of guessing.

### lineage graph

1. build lineage graph by direct relations
2. export global lineage for each target fields

## Implementation status

The former TODO list is complete:

- `select * from A,B`, `select c1,c2 from A,B`, and `select A.c1,B.c2 from A,B` are
  covered by `ColumnResolutionTest`, including ambiguity errors and metadata inference.
- Derived tables expose bare output names: `select * from (select A.c1, B.c2 from A,B) as T`
  maps to `T.c1` / `T.c2`.
- `where ... in (select ...)` collects source tables without emitting output mappings.
- Default database/schema selected via `USE` or `search_path` is covered by
  `CrossDatabaseSchemaTest`.
- `UPDATE` statements, including joins and scalar subqueries, are covered by
  `AdvancedDmlTest`.
- Metadata is imported from a real database (`JdbcMetadataScanner`), merged with
  DDL/inferred metadata, and consumed by the second analysis pass; global lineage is
  exported from `ScanResult`.

### support DB

Hive and Spark are covered by the catalog and wildcard regression tests. Spark
statements fall back to the Hive parser only when the Spark parser rejects them, so
Hive-compatible DML such as `INSERT OVERWRITE TABLE` is analyzed normally.

The remaining open items are the bounded-contract edge cases documented under
[Known limitations](#known-limitations). They are recorded deliberately and are not
tracked as TODOs.

## License

This project is licensed under the Apache-2.0 license - see the [LICENSE](LICENSE) file for details.

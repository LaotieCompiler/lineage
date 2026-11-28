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

Run the full unit suite with `mvn test` (currently 908 tests, 2 skipped).

The Docker-backed JDBC integration tests start local MySQL and PostgreSQL containers and
run `JdbcMetadataScannerDockerTest`:

```bash
./scripts/run-jdbc-docker-tests.sh
```

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

Known limitation: Derby JDBC escape syntax (`{fn TIMESTAMPADD(...)}`) is not represented in
Druid's AST. Strict mode records escape provenance on the parsed call node. An untouched
AST or a clone is handled correctly, but a hand-edited AST that replaces an escape call's
arguments with those of an equivalent ordinary call can be indistinguishable from the
escape and may inherit the unit exemption. The review batch for strict syntax was closed
after 22 failed rounds on this adversarial AST-editing case; ordinary SQL is unaffected.



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

#### Known limitations

The following table-function edge cases remain open after the metadata-learning review
batch exceeded 30 failed review rounds. They are recorded here instead of continuing an
open-ended fuzz loop:

- `resolveSource` still passes a table function's rendered call text to the catalog
  lookup. With `OPTIONAL` mode and a catalog table literally named
  `"generate_series(1, 3)"`, `SELECT generate_series.generate_series FROM
  generate_series(1, 3)` reports `ColumnNotFoundException`; it should resolve to the
  function output.
- `staticSourceWidth` still looks a rendered call text up in the CTE scope. An unused CTE
  named `"generate_series(1, 3)"` can impose its two-column width on a one-column table
  function in a `UNION`, producing `UNION column count mismatch: 2 vs 1`.

All other table-function learning and owner-resolution cases in this area are covered by
regression tests.

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

## TODO

### select parser

1. select * from A,B
2. select c1,c2 from A,B //c1 from A or B
3. select A.c1,B.c2 from A,B
4. (select A.c1,B.c2 from A,B) as T  // bug: output T.A.c1, use Column Object to represent the column or strict string pattern.
5. select c1,c2,c3 from A where ci in (select ...)
6. specify default database/schema
7. update ...

### metadata

Cross-database and cross-schema lineage, including default database/schema selected via
`USE` or `search_path`, is covered by `CrossDatabaseSchemaTest`.

#### steps

1. generate relations and tables from raw SQL query.
2. import metadata information from real database.
3. execute relations with metadata to get exactly relations. Meanwhile update metadata for middle tables.
4. build lineage map.

### support DB

Hive and Spark are covered by the catalog and wildcard regression tests. Spark
statements fall back to the Hive parser only when the Spark parser rejects them, so
Hive-compatible DML such as `INSERT OVERWRITE TABLE` is analyzed normally.

## License

This project is licensed under the Apache-2.0 license - see the [LICENSE](LICENSE) file for details.

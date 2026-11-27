# lineage

A data lineage parser in columns level.

[中文](README_zh.md)

## AST Parser

The SQL is parsed into an AST (Abstract Syntax Tree) by [Alibaba Druid](https://github.com/alibaba/druid).

The AST is traversed in post-order: nested queries and derived tables are processed before the select list of the query that consumes them, then the lineage information in table and column level is collected.

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

All the features and test cases in `SelectLineageTest.java`.

## Feature

### Supported DB

    Oracle
    MS SQL Server and Sybase
    Postgres
    MySQL and MariaDB
    DB2
    H2 and HSQLDB and Derby
    SQLite

### select parser

1. `insert into A (c1, c2) from select c1, c2`
4. `select c1, c2 from A as TA`
3. `select 1 as c1 from A` 
3. `select now() as c1 from A` 
4. `select c1+c2 from A`
5. `select max(c1) from A`
6. `select a1,a2 from (select b1, b2 from (select c1,c2 from C))`
7. `SELECT TB.id as bid, TC.id as cid, id as aid FROM TA Left Join (select id, ba1 b1, ba2 b2 from TBA )TB ON TA.id = TB.id`
1. `select * from A`  //dependence on metadata

### metadata

1. Some columns define are ambiguous such as * and same column name from multiple source table. So we need to introduce metadata from real DB to determine the source table.
2. In the progress of parsing, it will generate metadata for middle table, save the infomation for checking in later.
3. excute the origin instructions with metadata, get the full qualified column name for each column.

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

### lineage graph

1. build lineage graph by direct relations
2. export global lineage for each target fields

## TODO

### select parser

5. select * from A,B
6. select c1,c2 from A,B //c1 from A or B
7. select A.c1,B.c2 from A,B  
7. (select A.c1,B.c2 from A,B) as T  // bug: output T.A.c1, use Column Object to represent the column or strict string pattern.
8. select c1,c2,c3 from A where ci in (select ...)
9. specify default database/schema
8. update ...

### metadata

1. Strict syntax, all the talbe and colmuns must be quoated.
2. Cross multiple database/schema

### support DB

1. Hive
2. Spark

#### steps

1. generate relations and tables from raw SQL query.
2. import metadata information from real database.
3. execute relations with metadata to get exactly relations. Meanwhile update metadata for middle tables.
4. build lineage map.

## License

This project is licensed under the Apache-2.0 license - see the [LICENSE](LICENSE) file for details.

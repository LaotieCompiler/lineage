# lineage

A data lineage parser in columns level.

[中文](README_zh.md)

## Visitor Pattern

Postorder traversal the AST (Abstract Syntax Tree) of the SQL query and collect the lineage information using the Visitor pattern.

The code from `net.sf.jsqlparser.util.TablesNamesFinder` that can parse lineage in table level.

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

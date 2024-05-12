# lineage

A data lineage parser in columns level.

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

1. `select 1,2`  //ignore constants
2. `select 1 as c1 from A`  //ignore constnts
1. `select c1+c2 from A`
2. `select max(c1) from A`
3. `select a1,a2 from (select b1, b2 from (select c1,c2 from C))`
4. `SELECT TB.id as bid, TC.id as cid, id as aid FROM TA Left Join (select id, ba1 b1, ba2 b2 from TBA )TB ON TA.id = TB.id`

# TODO

## select parser

3. select * from A  //dependence on metadata
4. select c1, c2 from A as TA
5. select * from A,B
6. select c1,c2 from A,B //c1 from A or B
7. select A.c1,B.c2 from A,B 
8. specify default database/schema

## metadata

1. Some columns define are ambiguous such as * and same column name from multiple source table. So we need to introduce metadata from real DB to determine the source table.
2. In the progress of parsing, it will generate metadata for middle table, save the infomation for checking in later.
3. excute the origin instructions with metadata, get the full qualified column name for each column.
4. Cross multiple database/schema

### steps

1. generate relations and tables from raw SQL query.
2. import metadata information from real database.
3. execute relations with metadata to get exactly relations. Meanwhile update metadata for middle tables.
4. build lineage map.

## lineage map

1. build lineage map by direct relations
2. export global lineage for each target fields


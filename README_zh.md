# 数据血缘

一个列级别的数据血缘分析器。

[English](README.md)

## 访问者模式

使用访问者模式对SQL查询的抽象语法树(AST)进行后序遍历,并收集数据血缘信息。

来自 `net.sf.jsqlparser.util.TablesNamesFinder` 的代码可以解析表级别的数据血缘。

## SQL执行顺序

| 子句           | 功能                                                                                      |
| -------------- | ----------------------------------------------------------------------------------------- |
| FROM / JOIN    | 当你编写任何查询时,SQL首先确定用于数据检索的表以及它们之间的连接方式。                    |
| WHERE          | 它充当过滤器;它根据用户指定的条件过滤记录。                                               |
| GROUP BY       | 过滤后的数据根据指定的条件进行分组。                                                      |
| HAVING         | 它类似于WHERE子句,但是在对数据进行分组之后应用。                                          |
| SELECT         | 该子句选择要包含在最终结果中的列。                                                        |
| DISTINCT       | 从结果中删除重复行。应用此子句后,您只剩下不同的记录。                                     |
| ORDER BY       | 它根据指定的条件(递增/递减/A->Z/Z->A)对结果进行排序。                                     |
| LIMIT / OFFSET | 它确定要返回的记录数以及从何处开始。                                                      |

## 测试

所有功能和测试用例都在 `SelectLineageTest.java` 中。

## 功能

### 支持的数据库

    Oracle
    MS SQL Server and Sybase
    Postgres
    MySQL and MariaDB
    DB2
    H2 and HSQLDB and Derby
    SQLite

### 已支持的 SQL 语法

1. `insert into A (c1, c2) from select c1, c2`
2. `select 1,2`  //ignore constants
3. `select 1 as c1 from A`  //ignore constnts
4. `select c1+c2 from A`
5. `select max(c1) from A`
6. `select a1,a2 from (select b1, b2 from (select c1,c2 from C))`
7. `SELECT TB.id as bid, TC.id as cid, id as aid FROM TA Left Join (select id, ba1 b1, ba2 b2 from TBA )TB ON TA.id = TB.id`

### 血缘地图

1. 通过直接关系构建血缘地图
2. 为每个目标字段导出全局血缘

## TODO

### 待开发的 select 语法

1. select * from A  //dependence on metadata
4. select c1, c2 from A as TA
5. select * from A,B
6. select c1,c2 from A,B //c1 from A or B
7. select A.c1,B.c2 from A,B  
7. (select A.c1,B.c2 from A,B) as T  // bug: output T.A.c1, use Column Object to represent the column or strict string pattern.
8. select c1,c2,c3 from A where ci in (select ...)
9. specify default database/schema
8. update ...

### 元数据  

1. 一些列的定义是模糊的,比如`*`和来自多个源表的同名列。因此,我们需要引入来自真实数据库的元数据来确定表结构。
2. 在解析过程中,它将为中间表生成元数据,保存信息以供以后检查。
3. 使用元数据执行原始指令,获取每个列的明确列名。
4. 跨越多个数据库的语句

#### 步骤

1. 从原始SQL查询中生成列之间的直接关系和涉及的数据库表。
2. 从真实数据库导入元数据信息。
3. 使用元数据二次编译，以获取确切的列的关系。同时更新中间表的元数据。
4. 构建血缘地图。


## 许可证

本项目根据Apache-2.0许可证获得许可 - 有关详细信息,请参阅[LICENSE](LICENSE)文件。
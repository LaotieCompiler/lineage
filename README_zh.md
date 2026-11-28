# 数据血缘

一个列级别的数据血缘分析器。

[English](README.md)

## AST 解析器

SQL 由 [Alibaba Druid](https://github.com/alibaba/druid) 解析为抽象语法树(AST)。

对 AST 进行后序遍历:先处理内层查询和派生表,再处理消费它们的查询的 SELECT 列表,从而收集表和列级别的数据血缘信息。

## 代码结构

`DmlLineage` 是公共 facade。它持有语句级状态，负责脚本解析、会话跟踪（`USE` / `SET`）和
语句分发；具体分析拆分为包内协作类：

| 类 | 职责 |
| --- | --- |
| `DmlLineage` | 公共 API、会话/脚本编排、语句分发、表与指令注册 |
| `QueryDispatcher` | `SELECT` / `UNION` / `WITH` 调度与静态形状作用域 |
| `SelectAnalyzer` | 投影分析、外层引用、标量子查询 |
| `SourcePlanner` | `FROM` / `JOIN` 来源解析与 star 计划 |
| `ExpressionAnalyzer` | 表达式遍历、owner 解析、非限定列解析 |
| `TargetShapeAnalyzer` | 目标输出列、通配符范围、未知列分组 |
| `StarProjectionAnalyzer` | `SELECT *` 的列映射与通配符指令生成 |
| `StaticShapeAnalyzer` | 静态宽度与输出名推断 |
| `DmlStatementAnalyzer` | `INSERT` / `REPLACE` / `UPDATE` / `DELETE` / `MERGE` / `TRUNCATE` |
| `LearningAnalyzer` | 显式引用元数据发现 |
| `SourceShapeAnalyzer` | 来源宽度/列名已知性查询 |
| `TableCollector` | 仅收集物理表，不做列分析 |
| `ColumnMappingAnalyzer` | 列身份解析与映射边生成 |

支撑包分别承载方言策略（`dialect`）、标识符值对象（`identifier`）、语句切分与解析回退
（`parse`）、会话/元数据解析（`metadata`）、严格引用校验（`validation`），以及共享分析
状态与模型类型（`analysis`）。

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

运行完整单元测试：

```bash
mvn test
```

当前为 936 个测试；其中 2 个始终跳过，可选的 TPC-H 语料在未下载查询时也会跳过。

Docker JDBC 集成测试会启动本地 MySQL 与 PostgreSQL 容器并运行
`JdbcMetadataScannerDockerTest`：

```bash
./scripts/run-jdbc-docker-tests.sh
```

### 黄金语料

`src/test/resources/golden` 存放数据驱动的回归用例。每个 JSON 用例声明方言、分析选项、
可选的 catalog DDL、SQL 脚本以及每条语句的期望 instructions 或错误；`GoldenLineageTest`
会运行全部用例。

在有意的行为变更之后，重新生成期望并**审查 JSON diff** 后再提交：

```bash
mvn test -Dtest=GoldenLineageTest -Dgolden.update=true
```

### 外部 TPC-H 语料

`TpchCorpusTest` 会用 `src/test/resources/tpch/schema.sql` 分析 22 条 TPC-H 查询，断言每条
都产生血缘且结果确定。查询本身因 TPC 许可证不提交入库，先下载：

```bash
./scripts/fetch-tpch-corpus.sh
mvn test -Dtest=TpchCorpusTest
```

未下载时该测试自动跳过，离线构建仍然通过。

### 构建

项目目标为 Java 8；JUnit 4 与 JUnit 5 测试由 Maven Surefire 一起运行。Druid 固定为
1.2.23，因为其解析结果属于受测试约束的契约；升级 Druid 是独立的变更，必须通过全量回归
测试。

## 功能

### 支持的数据库

    Oracle
    MS SQL Server and Sybase
    Postgres
    MySQL and MariaDB
    DB2
    H2 and HSQLDB and Derby
    SQLite
    Hive
    Spark（Hive 兼容语句）

### 已支持的 SQL 语法

1. `insert into A (c1, c2) from select c1, c2`
2. `select 1,2`  //ignore constants
3. `select 1 as c1 from A`  //ignore constants
4. `select now() as c1 from A`
5. `select c1+c2 from A`
6. `select max(c1) from A`
7. `select a1,a2 from (select b1, b2 from (select c1,c2 from C))`
8. `SELECT TB.id as bid, TC.id as cid, id as aid FROM TA Left Join (select id, ba1 b1, ba2 b2 from TBA )TB ON TA.id = TB.id`
9. `select * from A` //依赖元数据

### DDL

- `CREATE TABLE ... AS SELECT` 与 `CREATE VIEW ... AS SELECT` 会把血缘写入新建关系；
  声明列列表（`CREATE TABLE x (a, b) AS ...`）会作为目标列名。
- DDL 创建、替换、修改或删除的关系，对同一脚本中后续语句可见（严格模式同样生效）。
- `ALTER TABLE ... ADD COLUMN` / `DROP COLUMN` 会更新该表的会话视图。
- `DELETE` 与 `TRUNCATE` 不写入列，因此不产生列级边；严格模式下目标表缺失仍会使该语句失败。
- `CREATE INDEX`、`EXPLAIN`、`GRANT` 仍不支持：该语句记录为失败，扫描继续处理下一条。

### 元数据

1. 一些列的定义是模糊的，比如 `*` 和来自多个源表的同名列。因此，我们需要引入来自真实数据库的元数据来确定表结构。
2. 在解析过程中，分析器会为中间表生成元数据，保存信息以供后续检查。
3. 使用元数据执行原始指令，获取每个列的明确列名。

#### 元数据分析模式

`LineageOptions` 控制元数据缺失时的行为：

| 模式 | 行为 |
| --- | --- |
| `STRICT`（默认） | 元数据为准：catalog 中不存在的表抛 `MetadataNotFoundException`，无法匹配的列抛 `ColumnNotFoundException`，歧义列抛 `AmbiguousColumnException` |
| `OPTIONAL` | catalog 中不存在的表仍参与分析：通配符保持通配符形态（`a.* -> t.*`），不编造列名，并在血缘图中保留表级边；歧义列仍抛 `AmbiguousColumnException` |

```java
// 严格模式（默认）
new LineageScanner(context).scan(sqlLog);

// 允许脱离元数据，通配符按通配符输出
LineageScanner.withoutMetadata(LineageOptions.of(MetadataMode.OPTIONAL, false))
        .scan(sqlLog);

// 允许脱离元数据，并从历史语句中学习列归属
LineageScanner.withoutMetadata(LineageOptions.of(MetadataMode.OPTIONAL, true))
        .scan(sqlLog);
```
#### 强制引用标识符

`LineageOptions.of` 的第三个参数为 `true` 时，所有表、列、表别名和 CTE 名都必须使用标识符
引号；未加引号的标识符会抛出 `UnquotedIdentifierException`：

```java
LineageScanner.withoutMetadata(
        LineageOptions.of(MetadataMode.OPTIONAL, false, true))
        .scan(sqlLog);
```
强制引用模式还会校验表别名与 CTE 名，并覆盖 PostgreSQL 的 `RETURNING`/`ON CONFLICT`、
MySQL 的 `ON DUPLICATE KEY UPDATE` 以及 SQL Server 的日期部分。

永久限制（不在修复计划内）：Druid 的 AST 不保留 Derby JDBC 转义语法
（`{fn TIMESTAMPADD(...)}`），也不携带源码位置。

严格模式因此以"渲染后的调用签名"记录转义来源。未修改的 AST 或其克隆可以正确处理，但
如果手工编辑 AST，把转义调用的参数替换为等价普通调用的参数，签名相同，二者无法区分，
可能继承单位关键字豁免。在不改变契约的前提下这两个要求无法同时满足：拒绝这类编辑也会
拒绝本应通过的未修改克隆。严格语法审核批次在该对抗性场景失败 22 轮后关闭；普通 SQL
不受影响。



开启推断后，`select A.c1, B.c2 from A,B` 会记录 `c1` 属于 `A`、`c2` 属于 `B`；之后
`select c1 from A,B` 可解析为 `A.c1` 而不报错。一次扫描分两趟：第一趟只遍历整段日志
发现元数据，第二趟在完整信息下做列级血缘，因此同一日志中后出现的语句也能帮助前面的语句
完成解析。学习结果只在当前 scanner 实例内生效，且不会用于展开通配符。

#### 无元数据契约

当表的列集合未知时，投影的列**位置**同样未知。此时分析器只保留表级边，绝不猜测列：

- `select * from missing` 产出 `missing.* -> t.*`，而不是编造 `missing.col1`；
- 派生表或 `UNION` 内部的未知通配会以表级边向上传播；
- 跟随在该通配之后的显式投影没有可确定的位置，因此不生成列级边；
- 一旦所有参与来源的列都已知（catalog、DDL 或学习所得），投影会恢复逐列映射，包括
  `JOIN ... USING` 合并列与 `UNION` 输出名。

该契约是有意收敛的。相关 `LATERAL` 子查询、前向引用的递归 CTE、派生表重复输出名，以及
未知宽度通配与显式投影混合的 `UNION`，按尽力而为分析：当位置无法唯一确定时保留表级边，
不编造列名。上述已列出的组合有回归测试覆盖，但不对同一特性的无限嵌套承诺逐列解析。

#### 表函数身份

表函数只通过其调用本身识别；渲染出的调用文本永远不会被当作关系名。因此它不会参与
catalog 或 CTE 作用域解析，也不会贡献静态宽度或输出名。以下两个曾经违反该规则的边界
情况现在都有回归测试覆盖：

- catalog 中存在字面名为 `"generate_series(1, 3)"` 的表时，不会再劫持
  `SELECT generate_series.generate_series FROM generate_series(1, 3)` 的解析；
- 未使用的同名 CTE 不会再把自己的宽度强加到表函数的 `UNION` 上。

该区域的其他表函数学习与 owner 解析场景也均有回归测试覆盖。

#### 审核状态

Hive/Spark 支持批次在 31 轮 Codex 审核失败后关闭。关闭前已修复所有已报告的问题，最后一次
修复是 Spark ANSI 设置的 locale 无关匹配。该批次不再继续对抗性审核轮次。


#### 已知限制：嵌套派生表/UNION 输出名

跨库解析已有回归测试覆盖，但派生表的 `*` 展开涉及“带引号分支 + 未入 catalog 分支”的
`UNION` 时按尽力而为处理。审核批次在 22 轮失败后关闭，最后一个用例要求从明确未加入
catalog 的表 `ext.missing` 中得到列名 `p`；在无元数据契约下该列无法推导。分支输出名未知
时，分析器保留表级边而不猜测。

### 血缘地图

1. 通过直接关系构建血缘地图
2. 为每个目标字段导出全局血缘

## 实现状态

原 TODO 清单已全部完成：

- `select * from A,B`、`select c1,c2 from A,B`、`select A.c1,B.c2 from A,B` 由
  `ColumnResolutionTest` 覆盖，包括歧义报错与元数据推导。
- 派生表输出裸列名：`select * from (select A.c1, B.c2 from A,B) as T` 映射为
  `T.c1` / `T.c2`。
- `where ... in (select ...)` 会收集源表，但不产生输出映射。
- 通过 `USE` 或 `search_path` 选择的默认数据库/schema 由 `CrossDatabaseSchemaTest`
  覆盖。
- `UPDATE` 语句（包括 join 与标量子查询）由 `AdvancedDmlTest` 覆盖。
- 元数据可从真实数据库导入（`JdbcMetadataScanner`），与 DDL/推导元数据合并后供第二轮
  分析使用；全局血缘从 `ScanResult` 导出。

### 支持的数据库

Hive 与 Spark 已有 catalog 与通配符回归测试覆盖。Spark 语句仅在 Spark 解析器拒绝时才回退到
Hive 解析器，因此 `INSERT OVERWRITE TABLE` 等 Hive 兼容 DML 可以正常分析。

目前仍然开放的事项是有界契约的边界情况，记录在"已知限制"一节；它们是有意保留的说明，
不再作为 TODO 跟踪。

## 许可证

本项目根据Apache-2.0许可证获得许可 - 有关详细信息,请参阅[LICENSE](LICENSE)文件。
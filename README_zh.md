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

当前为 908 个测试通过、2 个跳过。

Docker JDBC 集成测试会启动本地 MySQL 与 PostgreSQL 容器并运行
`JdbcMetadataScannerDockerTest`：

```bash
./scripts/run-jdbc-docker-tests.sh
```

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

已知限制：Druid 的 AST 不保留 Derby JDBC 转义语法（`{fn TIMESTAMPADD(...)}`）。严格模式
在解析后的调用节点上记录转义来源。未修改的 AST 或其克隆可以正确处理，但如果手工编辑
AST，把转义调用的参数替换为等价普通调用的参数，二者无法区分，可能继承单位关键字豁免。
严格语法的审核批次在该对抗性 AST 编辑场景失败 22 轮后关闭；普通 SQL 不受影响。



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

#### 已知限制

以下表函数（table function）的边界情况在元数据学习批次的审核失败超过 30 轮后仍然存在，
记录于此，不再进行无界的模糊测试循环：

- `resolveSource` 仍会把表函数的调用文本交给 catalog 查找。在 `OPTIONAL` 模式且 catalog
  中存在名为 `"generate_series(1, 3)"` 的表时，
  `SELECT generate_series.generate_series FROM generate_series(1, 3)` 会报告
  `ColumnNotFoundException`；正确行为是解析到函数输出。
- `staticSourceWidth` 仍会用调用文本在 CTE 作用域中查找宽度。一个未使用的、名为
  `"generate_series(1, 3)"` 的 CTE 可能把两列宽度施加到 `UNION` 中的单列表函数上，
  产生 `UNION column count mismatch: 2 vs 1`。

该区域的其他表函数学习与 owner 解析场景均有回归测试覆盖。

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

## TODO

### 待开发的 select 语法

1. select * from A,B
2. select c1,c2 from A,B //c1 from A or B
3. select A.c1,B.c2 from A,B
4. (select A.c1,B.c2 from A,B) as T  // bug: output T.A.c1, use Column Object to represent the column or strict string pattern.
5. select c1,c2,c3 from A where ci in (select ...)
6. specify default database/schema
7. update ...

### 元数据

跨数据库、跨 schema 的血缘（包括通过 `USE` 或 `search_path` 选择的默认数据库/schema）
由 `CrossDatabaseSchemaTest` 覆盖。

#### 步骤

1. 从原始SQL查询中生成列之间的直接关系和涉及的数据库表。
2. 从真实数据库导入元数据信息。
3. 使用元数据二次编译，以获取确切的列的关系。同时更新中间表的元数据。
4. 构建血缘地图。

### 支持的数据库

Hive 与 Spark 已有 catalog 与通配符回归测试覆盖。Spark 语句仅在 Spark 解析器拒绝时才回退到
Hive 解析器，因此 `INSERT OVERWRITE TABLE` 等 Hive 兼容 DML 可以正常分析。

## 许可证

本项目根据Apache-2.0许可证获得许可 - 有关详细信息,请参阅[LICENSE](LICENSE)文件。
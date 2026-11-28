# lineage 数据血缘

列级别的 SQL 数据血缘分析器。

`lineage` 使用 [Alibaba Druid](https://github.com/alibaba/druid) 解析 SQL，基于元数据目录
解析表和字段，为脚本、日志和 DDL 变更生成表级与字段级血缘。项目是一个面向 Java 8 的
库，内置命令行扫描器，并生成自包含的 HTML 报告。

[English](README.md)

## 特性

- 字段级血缘，带关系类型：字段拷贝、表达式、函数、聚合、类型转换、`CASE`、标量子查询、
  通配符和常量。
- 整段脚本分析：`USE` / `SET`、临时表、CTE，以及 DDL 创建/替换/修改/删除的关系，对同一
  次扫描中的后续语句可见。
- 元数据来自 DDL 分析或序列化后的 catalog 文件，无需连接真实数据库。
- 支持 `STRICT` 与 `OPTIONAL` 两种元数据模式，以及跨语句的元数据学习。
- 方言由 Druid 提供：Oracle、SQL Server、PostgreSQL、MySQL/MariaDB、DB2、
  H2/HSQLDB/Derby、SQLite、Hive 和 Spark。
- 命令行扫描器可以把一个 DDL + SQL 目录转换为 CSV 产物和交互式 HTML 报告。

## 快速开始

```java
MetadataCatalog catalog = new MetadataCatalog(DbType.hive);
catalog.loadDdl(createTableStatements);

LineageScanner scanner = LineageScanner.withMetadata(
        MetadataContext.of(catalog), LineageOptions.defaults());
ScanResult scan = scanner.scan(sqlScript);

for (LineageResult statement : scan.getResults()) {
    if (statement.getError() != null) {
        // 单条语句失败在这里报告，脚本其余部分继续分析。
        continue;
    }
    for (Instruction edge : statement.getInstructions()) {
        // edge.getFrom() -> edge.getTo()
    }
}

LineageGraph graph = scan.getGraph();
```

不提供元数据目录时，使用 `OPTIONAL` 模式。无法解析的通配符会保留为表级边，而不是编造
字段名：

```java
ScanResult scan = LineageScanner.withoutMetadata(
        LineageOptions.of(MetadataMode.OPTIONAL, true)).scan(sqlScript);
```

## 元数据

### 元数据来源

元数据通过分析 DDL 或读取序列化后的 catalog 文件获得。catalog 可以导出为自描述的 DDL
文本文件，文件头记录方言、版本和校验和；之后可以直接加载，无需重新分析源脚本：

```java
MetadataCatalog catalog = new MetadataCatalog(DbType.hive);
catalog.loadDdl(createTableStatements);

catalog.saveDdl(Paths.get("metadata.sql"));

MetadataCatalog reloaded = MetadataCatalog.fromDdlFile(Paths.get("metadata.sql"));
```

### 元数据模式

`LineageOptions` 控制元数据缺失时的行为：

| 模式 | 行为 |
| --- | --- |
| `STRICT`（默认） | 元数据为准：catalog 中不存在的表抛 `MetadataNotFoundException`，无法匹配的列抛 `ColumnNotFoundException`，歧义列抛 `AmbiguousColumnException` |
| `OPTIONAL` | catalog 中不存在的表仍参与分析：通配符保持通配符形态（`a.* -> t.*`），不编造列名，并在血缘图中保留表级边；歧义列仍抛 `AmbiguousColumnException` |

### 元数据学习

开启推断后，`select A.c1, B.c2 from A,B` 会记录 `c1` 属于 `A`、`c2` 属于 `B`；之后
`select c1 from A,B` 可解析为 `A.c1` 而不报错。一次扫描分两趟：第一趟从整段日志中发现
元数据，第二趟在完整信息下做列级血缘，因此同一日志中后出现的语句也能帮助前面的语句完成
解析。学习结果只在当前 scanner 实例内生效，且不会用于展开通配符。

### 强制引用标识符

`LineageOptions.of` 的第三个参数为 `true` 时，所有表、列、表别名和 CTE 名都必须使用
标识符引号；未加引号的标识符会抛出 `UnquotedIdentifierException`：

```java
LineageScanner.withoutMetadata(
        LineageOptions.of(MetadataMode.OPTIONAL, false, true))
        .scan(sqlScript);
```

强制引用模式还会校验表别名与 CTE 名，并覆盖 PostgreSQL 的 `RETURNING` / `ON CONFLICT`、
MySQL 的 `ON DUPLICATE KEY UPDATE` 以及 SQL Server 的日期部分。

### 无元数据契约

当表的列集合未知时，投影的列位置同样未知。此时分析器只保留表级边，绝不猜测列：

- `select * from missing` 产出 `missing.* -> t.*`，而不是编造 `missing.col1`；
- 派生表或 `UNION` 内部的未知通配会以表级边向上传播；
- 跟随在该通配之后的显式投影没有可确定的位置，因此不生成列级边；
- 一旦所有参与来源的列都已知（catalog、DDL 或学习所得），投影会恢复逐列映射，包括
  `JOIN ... USING` 合并列与 `UNION` 输出名。

表函数只通过其调用本身识别，渲染出的调用文本永远不会被当作关系名；因此它不会参与
catalog 或 CTE 作用域解析，也不会贡献静态宽度。

## DDL 支持

- `CREATE TABLE ... AS SELECT` 与 `CREATE VIEW ... AS SELECT` 会把血缘写入新建关系；
  声明列列表（`CREATE TABLE x (a, b) AS ...`）会作为目标列名。
- DDL 创建、替换、修改或删除的关系，对同一脚本中后续语句可见（严格模式同样生效）。
- `ALTER TABLE ... ADD COLUMN` / `DROP COLUMN` 会更新该表的会话视图。
- `DELETE` 与 `TRUNCATE` 不写入列，因此不产生列级边；严格模式下目标表缺失仍会使该语句
  失败。
- `CREATE INDEX`、`EXPLAIN`、`GRANT` 不做分析：该语句记录为失败，扫描继续处理下一条。

## 命令行扫描器

[`scripts/lineage-scan/run.sh`](scripts/lineage-scan/run.sh) 扫描一个包含 DDL 和 SQL 的
项目目录，输出表级与字段级血缘，同时生成静态 HTML 报告和该页面读取的数据文件。

```bash
./scripts/lineage-scan/run.sh --input /path/to/project --output output
```

默认行为：

- 使用 `<input>/ddl` 作为 DDL 目录；
- 扫描 `<input>` 下其余包含 `.sql` 的子目录，按 `dwd,dws,ads,bi,ods,dim,other` 排序；
- 输出到 `./output`（相对当前执行目录）；
- 同时生成 `lineage-data.js` 与 `lineage-report.html`，用浏览器打开 HTML 即可。

推荐的输入目录结构：

```
project/
├── ddl/      # 表结构，*.sql
├── dwd/      # 明细层 SQL
├── dws/      # 汇总层 SQL
├── ads/      # 应用层 SQL
└── bi/       # BI 层 SQL
```

SQL 目录按依赖顺序扫描：先出现语句里观察到的字段会用于后续语句的解析。

也可以显式指定目录：

```bash
./scripts/lineage-scan/run.sh \
  --ddl /data/project/ddl \
  --sql /data/project/dwd \
  --sql /data/project/dws \
  --sql /data/project/ads \
  --output output
```

旧的位置参数写法 `<dialect> <ddl-dir> <sql-dir>... <out-dir>` 仍兼容。

### 参数

| 参数 | 说明 |
| --- | --- |
| `-i, --input <dir>` | 项目根目录，自动发现 DDL 和 SQL 子目录 |
| `-d, --ddl <dir>` | DDL 目录；缺省为 `<input>/ddl` |
| `-s, --sql <dir>` | SQL 目录，可重复 |
| `-o, --output <dir>` | 输出目录，默认 `./output` |
| `--dialect <name>` | Druid 方言，默认 `hive`；可选 `spark`、`mysql`、`postgresql` 等 |
| `--ext <list>` | 逗号分隔的扩展名，默认 `sql`，例如 `sql,ddl` |
| `--order <list>` | 自动发现 SQL 子目录的优先级，默认 `dwd,dws,ads,bi,ods,dim,other` |
| `--recursive` | 递归扫描子目录 |
| `--strict` | `STRICT` 元数据模式（默认 `OPTIONAL`，允许缺失表） |
| `--no-infer` | 关闭跨语句字段推断 |
| `--no-repair` | 关闭 DDL 缺失字段的 Schema 对齐 |
| `--no-preprocess` | 关闭 `${var}` 替换和 `@var :=` 内联 |
| `-h, --help` | 显示帮助 |

相对路径（`--input`、`--ddl`、`--sql`、`--output`）都相对于执行命令时的当前目录，
不是脚本所在目录。

### 输出

| 文件 | 内容 |
| --- | --- |
| `statements.csv` | 每条语句的状态、错误、读写表、`declared_target`、SQL 预览 |
| `column_edges.csv` | 解析器产生的指令（含临时表），带 `relation` / `function` / `operator` / `expression` |
| `column_lineage.csv` | 端到端字段血缘：目标字段 ← 最终物理源字段，带压缩后的 `relation`、完整 `relation_path`、目标写入表达式和 `truncated` |
| `table_dependencies.csv` | 直接表依赖：穿过临时表后停在第一个物理表 |
| `table_lineage.csv` | 端到端字段边的表级聚合 |
| `schema_repairs.csv` | 为对齐 SQL 引用而补齐的字段 |
| `missing_tables.csv` | 被引用但未编目的表 |
| `run-info.txt` | 本次运行的输入路径、方言、开关和统计 |
| `lineage-data.js` | HTML 报告读取的数据文件 |
| `lineage-report.html` | 交互式可视化报告，从同目录读取 `lineage-data.js` |

### 字段关系类型

`relation` 说明源字段如何进入目标字段：

| 值 | 含义 |
| --- | --- |
| `COLUMN_COPY` | 直接字段拷贝，或 `SELECT *` 展开 |
| `EXPRESSION` | 参与算术/比较/布尔表达式，`operator` 给出最近的运算符 |
| `FUNCTION` | 作为标量函数的参数，`function` 给出最近的函数名 |
| `AGGREGATE` | 作为聚合函数的参数，例如 `SUM`、`COUNT` |
| `CAST` | 显式类型转换 |
| `CASE` | 位于 `CASE WHEN` 分支 |
| `SUBQUERY` | 来自标量子查询 |
| `WILDCARD` | 表级/位置通配符注入 |
| `CONSTANT` | 没有源字段的字面量表达式 |

`column_lineage.csv` 的 `relation` 是压缩后的端到端关系链：去掉纯拷贝和重复的通配符
跳，只保留真正改变值的关系；每一跳形如 `FUNCTION(UPPER)` 或 `EXPRESSION(+)`。完整链路
保留在 `relation_path`。同一源字段存在多条推导路径时（例如 UNION 两个分支一个拷贝、
一个计算，或 `UPPER` 与 `LOWER` 两个分支），不同路径用 `;` 分隔，关系不会互相覆盖。
函数名或运算符中的分隔符会做百分号转义（如 `FUNCTION(f%3Bg)`）。路径超过 1024 跳时
保留目标侧的跳并置 `truncated=true`，报告显示“路径截断”，避免把不完整的计算链误读为
纯拷贝。

分析器记录离该列最近的一层关系：例如 `SUM(UPPER(a))` 中 `a` 的关系是 `FUNCTION`，
函数名为 `UPPER`，完整表达式仍保留在 `expression` 中。

### HTML 报告

`lineage-report.html` 只读取同目录的 `lineage-data.js`，不发起网络请求，也不需要服务器、
CDN 或 Node.js。`lineage-data.js` 把 `statements`、`tableLineage`、`tableDependencies`、
`columnLineage` 和 `repairs` 五组数据挂到 `window.LINEAGE_DATA`，可以据此定制自己的页面。

报告的行为：

- 按表名前缀分层：`ods/dim/mig` → ODS/DIM，`dwd` → DWD，`dws` → DWS，
  `ads/bi` → ADS/BI；
- 每层内按上下游邻居的平均位置做 3 轮 barycenter 排序，减少交叉；
- 边从源表右侧连到目标表左侧，使用三次贝塞尔曲线；
- 悬停/聚焦高亮相关节点和边，点击节点在右侧面板展示上下游与写入字段；
- 滚轮以指针为中心缩放，拖拽平移，“适应窗口”恢复全景；
- 默认跟随系统明暗主题，右上角可手动切换并记忆。

数据口径：

- 表级图使用 `table_dependencies.csv`：从目标字段沿临时表追溯到第一个物理表，保留
  DWD/DWS 中间层；
- 字段表使用 `column_lineage.csv`：继续追溯到最终物理源字段，是端到端闭包；
- 失败语句的目标表从 `statements.csv` 的 `declared_target` 读取，用红色虚线标识，
  避免把“上游缺失”误读成“没有上游”。

### SQL 预处理

- `${name}` 等调度占位符替换为固定日期 `20210101`，使带模板变量的脚本可以解析；
- 脚本变量 `@name := SELECT …;` 递归内联为派生表 `(SELECT …)`。

用 `--no-preprocess` 可关闭以上步骤。

### Schema 对齐

DDL 快照可能落后于 SQL。扫描器会迭代处理 `ColumnNotFoundException`：如果错误信息中
列出的表在目录中存在，就把被引用字段补进**本次分析用的可变目录副本**。原始 DDL 不会
被修改，`schema_repairs.csv` 记录每次补齐。用 `--no-repair` 可关闭。

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

## 构建与测试

项目目标为 Java 8。运行完整测试：

```bash
mvn test
```

JUnit 4 与 JUnit 5 测试由 Maven Surefire 一起运行。Druid 固定为 1.2.23，因为其解析结果
属于受测试约束的契约。

### 黄金语料

`src/test/resources/golden` 存放数据驱动的回归用例。每个 JSON 用例声明方言、分析选项、
可选的 catalog DDL、SQL 脚本以及每条语句的期望 instructions 或错误。
`GoldenLineageTest` 会运行全部用例，并对每个用例做确定性的 token 变异，验证畸形 SQL
不会逃逸出扫描器。

在有意的行为变更之后，重新生成期望并审查 JSON diff：

```bash
mvn test -Dtest=GoldenLineageTest -Dgolden.update=true
```

### 外部 TPC-H 语料

`TpchCorpusTest` 会用 `src/test/resources/tpch/schema.sql` 分析 22 条 TPC-H 查询，断言
每条都产生血缘且结果确定。查询本身因 TPC 许可证不提交入库，先下载：

```bash
./scripts/fetch-tpch-corpus.sh
mvn test -Dtest=TpchCorpusTest
```

未下载时该测试自动跳过，离线构建仍然通过。

### 差分检查（可选）

[SQLGlot](https://github.com/tobymao/sqlglot) 提供了另一套独立的列级血缘实现。它只作为
第二意见而非绝对标准：脚本把黄金语料中的单语句 `SELECT` 用例与 SQLGlot 的结果对比，
输出差异和不支持的构造，供人工复核。

```bash
python3 -m venv target/sqlglot-venv
target/sqlglot-venv/bin/pip install -r scripts/sqlglot-requirements.txt
target/sqlglot-venv/bin/python scripts/differential-sqlglot.py
```

该检查不进入 Maven 构建或 CI。加 `--fail-on-diff` 可在出现确认差异时返回非零退出码。

### 持续集成

GitHub Actions（`.github/workflows/ci.yml`）会在每次 push 与 pull request 上运行
`mvn clean test`。

## 已知限制

- 相关 `LATERAL` 子查询、前向引用的递归 CTE、派生表重复输出名，以及未知宽度通配与显式
  投影混合的 `UNION`，按尽力而为分析：当位置无法唯一确定时保留表级边，不编造列名。
- 派生表的 `*` 展开同时涉及已编目与未编目分支的 `UNION` 时，同样按尽力而为处理。
- 解析器尚未建模的构造（例如 `LATERAL VIEW` 输出列、表函数、`VALUES` 表构造器）会使
  语句记录为失败且不产生字段边；报告会把这类语句写入的目标表标成红色虚线。
- Druid 的 AST 不保留 Derby JDBC 转义语法（`{fn TIMESTAMPADD(...)}`），也不携带源码
  位置。严格模式以渲染后的调用签名记录转义来源；手工编辑 AST 后若产生等价调用签名，
  可能继承单位关键字豁免。
- 临时表与学习到的列只在单个 `LineageScanner` 实例内生效。

## 许可证

本项目根据 Apache-2.0 许可证获得许可 - 有关详细信息，请参阅 [LICENSE](LICENSE) 文件。

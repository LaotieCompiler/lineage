# 通用 ODPS / MaxCompute 血缘扫描

读取一个项目目录下的 DDL 和 SQL，输出表级与字段级血缘 CSV，并在可用时自动渲染
HTML 报告。工具本身不绑定 yidoutang：输入目录、输出目录、方言、扩展名和扫描顺序
全部参数化。

## 快速开始

```bash
# 输入目录里按约定放 ddl/ 和若干 SQL 子目录
./scripts/odps-lineage/run.sh \
  --input /path/to/warehouse/scripts \
  --output output
```

默认行为：

- 自动发现 `<input>/ddl` 作为 DDL 目录；
- 自动扫描 `<input>` 下其余包含 `.osql` 的子目录，按 `dwd,dws,ads,bi,ods,dim,other` 排序；
- 结果写入 `./output`（相对当前执行目录）；
- 扫描完成后自动执行 `node scripts/lineage-report/render.js output`，生成 `output/lineage-report.html`。

推荐的输入目录结构：

```
scripts/
├── ddl/      # 表结构，*.osql
├── dwd/      # 明细层 SQL
├── dws/      # 汇总层 SQL
├── ads/      # 应用层 SQL
└── bi/       # BI 层 SQL
```

SQL 目录按依赖顺序扫描：先出现语句里观察到的字段会用于后续语句的解析。

## 显式指定目录

```bash
./scripts/odps-lineage/run.sh \
  --ddl /data/warehouse/ddl \
  --sql /data/warehouse/dwd \
  --sql /data/warehouse/dws \
  --sql /data/warehouse/ads \
  --output output \
  --no-render
```

旧的位置参数写法仍兼容：

```bash
./scripts/odps-lineage/run.sh hive <ddl-dir> <sql-dir>... <out-dir>
```

## 参数

| 参数 | 说明 |
| --- | --- |
| `-i, --input <dir>` | 项目根目录，自动发现 DDL 和 SQL 子目录 |
| `-d, --ddl <dir>` | DDL 目录；缺省为 `<input>/ddl` |
| `-s, --sql <dir>` | SQL 目录，可重复 |
| `-o, --output <dir>` | 输出目录，默认 `./output` |
| `--dialect <name>` | Druid 方言，默认 `hive`；可选 `odps`、`mysql` 等 |
| `--ext <list>` | 逗号分隔的扩展名，默认 `osql`，例如 `osql,sql` |
| `--order <list>` | 自动发现 SQL 子目录的优先级，默认 `dwd,dws,ads,bi,ods,dim,other` |
| `--recursive` | 递归扫描子目录 |
| `--strict` | STRICT 元数据模式（默认 OPTIONAL，允许缺失表） |
| `--no-infer` | 关闭跨语句字段推断 |
| `--no-repair` | 关闭 DDL 缺失字段的 Schema 对齐 |
| `--no-preprocess` | 关闭 `${var}` 替换和 `@var :=` 内联 |
| `--no-render` | 只产出 CSV，不渲染 HTML |
| `-h, --help` | 显示帮助 |

相对路径（`--input`、`--ddl`、`--sql`、`--output`）都相对于**执行命令时的当前目录**，
不是脚本所在目录。

## 输出

| 文件 | 内容 |
| --- | --- |
| `statements.csv` | 每语句状态、错误、读写表、`declared_target`、SQL 预览 |
| `column_edges.csv` | 解析器产生的原始指令（含临时表） |
| `column_lineage.csv` | 端到端字段血缘：目标字段 ← 最终物理源字段 |
| `table_dependencies.csv` | 直接表依赖：穿过临时表后停在第一个物理表 |
| `table_lineage.csv` | 端到端字段边的表级聚合 |
| `schema_repairs.csv` | 为对齐 SQL 引用而补齐的字段 |
| `missing_tables.csv` | 扫描中遇到的未编目表 |
| `run-info.txt` | 本次运行的输入路径、方言、开关、统计和生成时间 |
| `lineage-report.html` | 自动渲染的可视化报告（`--no-render` 时不生成） |

## ODPS 预处理

- `${bizdate}`、`${bdp.system.bizdate}` 等调度变量替换为固定日期 `20210101`；
- 脚本变量 `@name := SELECT …;` 递归内联为派生表 `(SELECT …)`；
- DDL 中的 `LIFECYCLE n` 在 `hive` 方言下剥离；`odps` 方言保留原生解析。

默认使用 **hive** 方言：在 Druid 1.2.23 下，`odps` 方言解析器对
`SELECT * FROM (SELECT * FROM t) x` 这类派生星号会丢失输出列名。

## Schema 对齐

DDL 快照可能落后于 SQL。扫描器会迭代处理 `ColumnNotFoundException`：如果错误信息中
列出的表在目录中存在，就把被引用字段补进**本次分析用的可变目录副本**。原始 DDL 不会
被修改，`schema_repairs.csv` 记录每次补齐。用 `--no-repair` 可关闭。

## 已知限制

未解析语句主要来自解析器尚未建模的 ODPS 构造：`LATERAL VIEW` 的输出列、
`TRANS_COLS` 表函数、`VALUES` 表构造器，以及嵌套作用域中重复的别名。这些语句会
记录失败且不产生字段边；报告中会把由失败语句写入的目标表标成红色虚线节点。

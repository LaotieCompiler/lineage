# 血缘报告渲染器

把 ODPS 血缘扫描输出的 CSV 渲染成一份**自包含**的 HTML 报告。只用 Node.js 标准库，
不需要安装依赖，也不需要联网。

## 用法

```bash
node scripts/lineage-report/render.js <artifacts-dir> [output.html]
```

默认输出到 `<artifacts-dir>/lineage-report.html`。

示例：

```bash
node scripts/lineage-report/render.js target/odps-lineage
open target/odps-lineage/lineage-report.html
```

## 输入文件

| 文件 | 必需 | 说明 |
| --- | --- | --- |
| `statements.csv` | 是 | 每条语句的状态、错误、目标表/源表、SQL 预览 |
| `column_lineage.csv` | 是 | 端到端字段血缘：目标表/字段 ← 最终物理源表/字段；可带 `relation`、`relation_path`、`function`、`operator`、`expression` |
| `table_lineage.csv` | 是 | 端到端表级聚合；`table_dependencies.csv` 缺失时作为图数据回退 |
| `table_dependencies.csv` | 否 | 直接表依赖（穿过临时表后停在第一个物理表），用于表级图 |
| `schema_repairs.csv` | 否 | 分析时为对齐 SQL 引用而补齐的字段 |

`statements.csv` 中的 `declared_target` 用于标记“写入语句解析失败”的目标表；这些节点在
图中以红色虚线显示，避免把缺失的上游误读为“没有上游”。

## 可视化方法

渲染分为两层，避免把分析逻辑和展示逻辑耦合：

1. **离线数据层**：`render.js` 读取 CSV，组装成一个 `data` 对象，序列化后替换
   `template.html` 里的 `/*__LINEAGE_DATA__*/null`，写出单个 HTML。
2. **浏览器表现层**：页面加载后，内嵌 JS 基于 `DATA` 构建 SVG 和表格，不需要
   后端，也不会再发起网络请求。

表级图：

- 按表名前缀分层：`ods/dim/mig` → ODS/DIM，`dwd` → DWD，`dws` → DWS，
  `ads/bi` → ADS/BI；
- 每层内按上下游邻居的平均位置做 3 轮 barycenter 排序，减少交叉；
- 边从源表右侧连到目标表左侧，使用三次贝塞尔曲线；
- 悬停/聚焦高亮相关节点和边，点击节点在右侧面板展示上下游与写入字段；
- 滚轮以指针为中心缩放，拖拽平移，“适应窗口”恢复全景。

数据口径：

- 图使用 `table_dependencies.csv`：从目标字段沿临时表追溯到第一个物理表，保留
  DWD/DWS 中间层；
- 字段表使用 `column_lineage.csv`：继续追溯到最终物理源字段，是端到端闭包；
- 失败语句的目标表从 `statements.csv` 的 `declared_target` 读取，用红色虚线标识，
  避免把“上游缺失”误读成“没有上游”。

主题与可读性：

- 层级是有序的，使用单色蓝序列而不是分类色；
- 每个节点都有文字标签，颜色不单独承载信息；
- 默认跟随系统明暗主题，右上角可手动切换并记忆；
- 数据里的表名/字段名用 `textContent` 写入 DOM，不拼 `innerHTML`。

## 报告内容

- **概览**：文件/语句/成功/失败/表/依赖边/字段边/Schema 修补。
- **表级血缘图**：按 ODS/DIM → DWD → DWS → ADS/BI 分层，barycenter 排序减少交叉；
  支持搜索、缩放、平移、悬停高亮、点击查看上下游和写入字段。
- **fan-in / fan-out**：直接上游最多、下游影响最广的 10 张表。
- **字段级血缘**：端到端字段边，可按表名/字段名/关系类型搜索、按目标表筛选；“说明”列
  用中文标签展示关系链（字段拷贝、表达式、函数、聚合等），悬停查看完整路径和表达式。
- **语句执行情况**：成功/失败筛选，失败原因直接展示。
- **Schema 修补**、**方法与限制**：说明口径和未解析的 ODPS 构造。

## 主题

默认跟随系统明暗主题，右上角按钮可手动切换，选择会保存在 `localStorage`。
配色取自已验证的蓝色序列（层级是有序的，不是分类色），节点同时有文字标签，
颜色不单独承载信息。

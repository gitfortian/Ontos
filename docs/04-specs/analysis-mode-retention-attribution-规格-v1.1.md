# analysis-mode · retention 与 attribution 规格

> **状态**：已冻结
> **版本**：v1.1
> **类型**：规格
> **负责人**：项目组（M6/T1.3 勘察后升版，待项目负责人评审确认）
> **最后更新**：2026-11-09
> **适用范围**：M6 编译器 retention / attribution 构造器；两种模式的 DSL Schema 与语义定档；funnel 顶层 filters 修订（M2 偏差 2 收口）
> **上游依据**：[完整可落地方案 v1.4 §6.3 / §9.5 / §9.6](../02-solutions/architecture/智能问数系统-完整可落地方案-v1.4.md)、[M6 实施规格 v1.0 §3.1 / 决策 2/5](../03-plans/stages/M6/M6-分析模式ScenarioSkill与复合意图-实施规格-v1.0.md)、[analysis-mode · funnel 与 yoy_mom 规格 v1](analysis-mode-funnel-yoy-mom-规格-v1.md)、[ADR-0004 2.4 时间口径](../01-decisions/ADR-0004-首发业务域与Scenario范围.md)、[ADR-0012 EventDef](../01-decisions/ADR-0012-EventDef事件判定映射扩展.md)、[清单 v11 时间模式](../../ontos-assets/first-release-v11.json)
> **历史版本**：[v1](../99-archive/specs/analysis-mode-retention-attribution-规格-v1.md)（已归档）
> **修订记录**：2026-11-09 v1 首次冻结（DSL 锚点、Schema、§9.5/§9.6 逐条定档、funnel filters）→ v1.1 修订：观察窗对齐已发布时间模式（「近 8 周」→「近 60 天」）、timeRange.field 由服务端按 EventDef 回填、维度收窄为主体表（funnel 同型）

> **v1.1 修订摘要（T1.3 勘察发现 2 项契约与实现可行性冲突）**：
> ① 已发布 `timeExpressionPatterns` 只有 `近 N 天 / 上个月 / 这个月 / 今年 / 上季度`，示例的「近 8 周」解析必失败（fail-closed）；
> `periodUnit` 是 cohort 聚合单位，与时间解析粒度相互独立。
> ② 「维度取 cohort 事件行」在步进 CTE 中会引入时序歧义（同主体多事件时取值不稳定）；
> 首发按 funnel 同型只支持**主体表维度**（`RETENTION_DIMENSION_UNSUPPORTED`），渠道等事件级维度声明后续支持。

本规格把 v1.4 §9.5/§9.6 要求「必须固定」的语义契约项逐条定档——构造器编码（W1/T1.3/T1.4）据此实现，
Golden 样本据此出题；契约项变更须发本规格新版本。DSL 字段名遵循 DslCodec camelCase 白名单契约
（snake_case 为概念示意，接入时按白名单映射，与 funnel/yoy_mom 规格同约定）。

## 1. retention 留存

### 1.1 DSL 锚点与 Schema

**事件锚点**（比照 FUNNEL 先例）：不要求 `metricId`；`DslValidator.validateMode` 走独立入口
（同 LOOKUP / LOOKUP_BATCH / FUNNEL），口径绑定 EventDef 对；构造器落 `SqlCompiler` 事件 CTE 分支。

```json
{
  "analysis_mode": "retention",
  "dimensions": ["dim.channel"],
  "time_expression_raw": "近 60 天",
  "analysis_params": {
    "cohort_event": "ORDER_PLACED",
    "return_event": "ORDER_PLACED",
    "period_unit": "WEEK",
    "periods": 4,
    "subject_property_id": "property.customer.customer_id"
  }
}
```

| 字段 | 约束 |
|---|---|
| `cohort_event` / `return_event` | event_type 值，必须命中已发布 EventDef（`RETENTION_EVENT_NOT_FOUND`）；二者可相同（复购留存） |
| `period_unit` | 封闭枚举 `WEEK` \| `MONTH`；周一 / 每月 1 日为周期起点（ADR-0004 §2.4） |
| `periods` | 整数 `[2, 12]`；输出 cohort 期 + 后续 `periods - 1` 个观察期 |
| `subject_property_id` | 主体标识（本体属性 ID），禁止自带物理列；回访判定按同主体匹配 |
| `dimensions` | 可选；首发只支持映射**主体表**的已发布维度（funnel 同型，违反即 `RETENTION_DIMENSION_UNSUPPORTED`）；取 cohort 时点的主体属性；渠道等事件级维度声明后续支持（§9.6 契约项 7 的取值时点由此固定） |
| `time_expression_raw` | 相对时间表达，界定 cohort 期集合的整体观察窗；**必须命中清单已发布 `timeExpressionPatterns`**（首发：`近 N 天` / `上个月` / `这个月` / `今年` / `上季度`），未命中即 `TIME_PARSE_FAILED`；服务端以 cohort 事件 `timeProperty` 回填 `timeRange.field`（客户端不提交时间维度 ID），绝对日期拒绝 |

### 1.2 语义契约定档（v1.4 §9.6 逐条）

| §9.6 契约项 | M6 定档 |
|---|---|
| cohort 起点事件 | 主体在 cohort 窗内的**首次** `cohort_event`（`occurred_at` 最早；同时刻按 `dedup_key` 升序取一） |
| 回访事件 | 后续周期内发生 `return_event` 的同主体即计为留存（不要求重复次数） |
| 周期 | 自然周（WEEK，周一起）或自然月（MONTH，1 日起）；cohort 期 = 首次事件所在周期 |
| 未完整结束周期 | **排除**：晚于「数据观察锚点（Asia/Shanghai 今日）」的未完整周期不产出结果行（不冒充完整数据）；cohort 窗内未完整期的主体不进入该期 cohort（其首次事件若落在更早的完整期仍正常计入）；**窗内无任何已完整结束的 cohort 期时整题拒绝**（`RETENTION_PERIOD_INCOMPLETE`） |
| 用户去重 | 按 `subject_property_id` distinct；一个主体在同一 cohort 期只计一次 |
| 时区与日期边界 | `Asia/Shanghai`，左闭右开 `[from, to)`（ADR-0004 §2.4 沿用） |
| 分组维度取值时点 | **cohort 时点值**：主体表维度按 cohort 期（主体首次事件所在期）归属，回访期不重取；事件级维度首发不支持 |

输出行语义：`(cohort_period, period_index, [dimensions], cohort_users, retained_users, retention_rate)`；
`retention_rate = retained_users / cohort_users`，`cohort_users = 0` 的 cohort 期不输出行（无除零路径）。

## 2. attribution 简单归因

### 2.1 DSL 锚点与 Schema

**聚合锚点**：`metricId` 必填（输出归因后的指标值），`metric.allowed_modes` 显式含 `ATTRIBUTION`
（清单 v11 为 `metric.gmv` / `metric.order_count` 扩展）；构造器落 `SqlCompiler` 聚合分支。

```json
{
  "analysis_mode": "attribution",
  "metric_id": "metric.gmv",
  "dimensions": ["dim.channel"],
  "time_expression_raw": "近 30 天",
  "analysis_params": {
    "conversion_event": "ORDER_PLACED",
    "touch_event": "CHANNEL_TOUCH",
    "attribution_window": "30d",
    "model": "last_touch",
    "subject_property_id": "property.customer.customer_id"
  }
}
```

| 字段 | 约束 |
|---|---|
| `conversion_event` | 转化事件 event_type（命中已发布 EventDef）；转化按事件行粒度逐笔归因（契约项 3） |
| `touch_event` | 触点事件 event_type（命中已发布 EventDef，首发为 `CHANNEL_TOUCH`）；触点渠道取 EventDef `channel_property_id` 映射列 → `dim.channel` |
| `attribution_window` | 封闭枚举 `7d` \| `14d` \| `30d`（转化时刻前 N 天内的触点才可归因）；超窗触点不参与 |
| `model` | 封闭枚举；首发仅 `last_touch`；`first_touch` / `linear` 写入契约**声明未实现**（引用时拒绝 `ATTRIBUTION_MODEL_NOT_IMPLEMENTED`，作为 `approximation_of` 机制的登记位） |
| `subject_property_id` | 主体标识；触点与转化按同主体关联 |
| `metric_id` | `allowed_modes` 含 ATTRIBUTION 的已发布指标；敏感指标沿 M4 敏感链路 |
| `dimensions` | 首发固定支撑 `dim.channel`（触点渠道维度）；其他已发布维度按映射可达性放行，取值时点 = 转化时点 |

### 2.2 语义契约定档（v1.4 §9.5 逐条）

| §9.5 契约项 | M6 定档 |
|---|---|
| 触点回溯窗口 | `attribution_window` 白名单枚举；窗口 = `[转化时刻 - window, 转化时刻)`，左闭右开 |
| 首触 / 末触 / 线性公式 | **末次触点（last_touch）**：窗口内 `occurred_at` 最晚的触点获得该笔转化 100% 权重；首触 / 线性声明未实现（见 §2.1 表） |
| 多次转化处理 | 每笔转化独立归因，互不影响（订单/事件粒度，不做主体级合并） |
| 触点与转化同一时间 | tie-break 全序显式：按 `(occurred_at, dedup_key)` **双降序**排序取首行（即同时刻取 dedup_key 最大者）；同输入同输出，Golden 依赖此序 |
| 无触点转化 | 归入显式 `UNATTRIBUTED` 分组行（维度值 `UNATTRIBUTED`），**不丢弃、不冒泡到其他渠道** |
| 匿名用户处理 | 无主体（participant 缺失）的触点/转化**排除**，不进 UNATTRIBUTED（该桶仅收「已知主体的无触点转化」） |
| 渠道合并规则 | 按 `dim.channel` 维度值聚合；触点渠道经 `event.channel_id → channel` 映射，禁止自由文本渠道 |
| 排序稳定性 | 全部排序键显式（`occurred_at` + `dedup_key`），同输入同输出（确定性验收项） |

输出行语义：`([dimensions], attributed_metric_value, converted_events, touched_events)`；
`UNATTRIBUTED` 行与其他渠道行同构输出。

## 3. funnel 顶层 filters 修订（M2 偏差 2 收口，M6 决策 5）

- **放开**：funnel 顶层 `filters` = **全局过滤**——每个步进 CTE 的 WHERE 追加同一组过滤条件（与 QUERY 语义一致：
  「在给定条件下」的漏斗）。
- **不引入**：步级过滤（每步独立 WHERE）——场景少、CTE 复杂度高，后续按需另发规格。
- **校验**：filters 字段复用既有 `FILTER` 结构（`fieldId/operator/values`）；`fieldId` 须为已发布维度且
  映射表经路径可达（与 QUERY 分组/过滤同规，`DslValidator` 维度白名单三条引用路径一体生效原则延续）。
- `time_expression_raw` / `cohort_window` 仍由 analysisParams 承担，filters 不承接时间语义（时间维度过滤继续拒绝，
  防「按日展开、期值重复」错位——M2 收口偏差 3 口径不变）。

## 4. 验收口径（对齐 M6 规格 E1）

- Golden：`M6-Golden-v1.json`——留存 ≥4 正例 + ≥2 负例、归因 ≥4 正例 + ≥2 负例；构造器生成 → 人工审阅 → 回填；逐字比对 100%。
- 负例必含：未知事件类型、periods 越界、未实现归因模型、未完整 cohort 期、维度映射不可达、绝对日期。
- 性能样例（M6 规格 G-3）：EXPLAIN 扫描量 / 成本上限沿用 M2 资源控制；大窗口（超长观察窗 / 超窗回溯）拒绝样例入边界集。
- M2 五模式 27 条 Golden 回归不回退；funnel filters 增量另出正/负例。

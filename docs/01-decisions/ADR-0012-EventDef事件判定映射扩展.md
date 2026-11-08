# ADR-0012 · EventDef 事件判定映射扩展与清单 v2

> **状态**：已冻结
> **版本**：v1
> **类型**：决策
> **负责人**：项目负责人（2026-11-08 选定方案 A）
> **最后更新**：2026-11-08
> **适用范围**：本体事件元模型（`EventDef`）、funnel 构造器的物理映射来源、首发清单 v2 的发布策略
> **上游依据**：[完整可落地方案 v1.4 §5 本体元模型 / §9.3 漏斗](../02-solutions/architecture/智能问数系统-完整可落地方案-v1.4.md)、[M2 实施规格 T3.3](../03-plans/stages/M2/M2-确定性编译与执行内核-实施规格-v1.md)、[analysis-mode funnel 规格 v1](../04-specs/analysis-mode-funnel-yoy-mom-规格-v1.md)、[ADR-0007](ADR-0007-jOOQ命名与schema映射规范.md)
> **修订记录**：2026-11-08 v1 首次冻结：登记 R-12 的处置方案；EventDef 增三个可选映射字段；清单 v1 不改、新增 v2；funnel 编译要求映射齐备否则 fail-closed

## 背景

M2/T3.3a 实现 funnel 时发现：首发清单 `2026.11.08-first` 的两个事件
（`event.order_placed` / `event.refund`）只有逻辑 `eventType`（`ORDER_PLACED` / `REFUND`）与
`timeProperty`（同为 `property.order.order_date`），**元模型没有字段表达「事件类型 → 物理过滤」**，
因此无法把 `steps` 映射为确定性 SQL 条件。该缺口登记为 **R-12**，T3.3a 一度暂缓。

复核物理 Query DS 后，事实与初次判断不同：

| 表 | 列 | 说明 |
|---|---|---|
| `event` | `id` / `tenant_id` / `customer_id` / `order_id` / `event_type` / `occurred_at` / `dedup_key` | `event_type` 取值即 `ORDER_PLACED` / `REFUND`；`occurred_at` 为事件发生时间 |
| `orders` | `status` 等 | `status` 只有 `PAID` / `CREATED`，**不能**用于退款事件判定 |

即：**物理判定列存在且取值与逻辑 `eventType` 一致，缺的只是元模型声明与属性发布**。
清单 v1 只发布了 `property.event.event_id`（`event.id`），`event_type` / `occurred_at` / `customer_id`
均未发布为属性。

## 决策

### 1. `EventDef` 增加三个可选映射字段

| 字段 | 含义 | 约束 |
|---|---|---|
| `eventTypeProperty` | 事件类型判定属性（Property ID） | 声明时必须指向已发布、`filterable=true` 的属性 |
| `eventTypeValue` | 该属性上代表本事件的值 | 可选；缺省取 `eventType`。声明时必须非空 |
| `participantProperty` | 事件表上的主体标识属性（Property ID） | 声明时必须指向已发布属性 |

`timeProperty` 继续表示事件发生时间，清单 v2 中改为 `property.event.occurred_at`。

**同一物理表约束**：`timeProperty` / `eventTypeProperty` / `participantProperty` 解析后的物理表
必须一致，否则拒绝发布。这一条保证 funnel 的步进 CTE 不需要跨表猜测连接。

### 2. 向后兼容与 fail-closed

- 不声明上述字段的旧清单**仍可发布/加载**（结构校验只校验「声明了就必须合法」）。
- funnel 编译要求 `timeProperty` + `eventTypeProperty` + `participantProperty` 齐备；
  缺任一即拒绝（`FUNNEL_EVENT_MAPPING_MISSING`），**不允许**从 `dedupKey` 字符串、
  `status` 列或命名习惯推断判定条件。
- `eventTypeValue` 缺省取 `eventType` 是唯一允许的缺省，且两者不等时必须显式声明。

### 3. 清单 v2 发布策略

- `2026.11.08-first` 按 AGENTS.md §4.4 不可原地改，继续作为 M1/M2 已发布锚点。
- 新增 `ontos-assets/first-release-v2.json`：在 v1 基础上补齐
  `property.event.event_type` / `occurred_at` / `customer_id` / `order_id`，
  并把两个 EventDef 的映射字段指向新属性；其余资产不变。
- 发布使用**新 manifestId**（建议 `2026.11.08-second`），由 `scripts/publish.mjs --assets ... --manifest-id ...` 完成。
- 在 v2 发布并激活前，funnel 在真实资产上不可用是预期状态；单测与 PoC 用 v2 资产文件或等价夹具。

### 4. funnel 的 cohort 窗口

`analysis_params.cohort_window.time_expression_raw` 由服务端时间解析器换算，
结果（`from` / `to` / `source` / `patternId` / `parserVersion`）写回 `CohortWindow` 并随
`dsl_json` 进审计。funnel 不接受客户端直接提交的绝对日期，规则与 query 的 timeRange 一致。

## 备选方案

| 备选 | 为何未选 |
|---|---|
| EventDef 增加通用 `condition` 表达式 | 引入第二套判定表达式求值面，与「Rule 是布尔判定唯一合法载体」冲突，且需要额外的表达式解析/校验/审计 |
| 把 `orders.status` 或命名习惯硬编码进编译器 | 物理假设进入编译器，违反确定性；实测 `orders.status` 也没有退款取值 |
| 只改清单、不改元模型（把 event_type 当普通维度用） | funnel 的 `steps` 是事件类型语义，用普通维度无法表达「每步首次发生 + 步窗」的契约 |
| 直接原地改 v1 清单 | 违反清单不可变与内容哈希锚定，已发布审计无法复现 |

## 影响

- **funnel**：T3.3a 解除阻塞，可按 analysis-mode 规格 v1 §1.3 实现步进 CTE；
  真实资产需 v2 清单发布后可用。
- **M2 E1**：构造器覆盖回到 5/5 的路径打通；E1 的「Golden 逐字比对」仍待 SQL 回填。
- **兼容性**：三个字段可选，不影响 v1 清单加载与既有测试。
- **后续**：技术选型/本体元模型下次升版时并入本 ADR；`eventTypeValue` 若出现
  「一属性多值映射同一事件」的需求，另发 ADR。

## 回退方案

若 funnel 实现发现三字段仍不足以表达（例如需要按 `dedup_key` 去重或跨事件表），
发新 ADR 取代本 ADR，并在进度快照登记；不得回退为「编译器内硬编码物理条件」。

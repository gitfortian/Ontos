# analysis-mode · funnel 与 yoy_mom 规格

> **状态**：已冻结
> **版本**：v1
> **类型**：规格
> **负责人**：项目组（M2/T3.0，待项目负责人评审确认）
> **最后更新**：2026-11-08
> **适用范围**：M2 编译器 funnel / yoy_mom 构造器；两种模式的 DSL Schema 与语义定档
> **上游依据**：[完整可落地方案 v1.4 §6.3.2 / §9.3 / §9.4](../02-solutions/architecture/智能问数系统-完整可落地方案-v1.4.md)、[M2 实施规格](../03-plans/stages/M2/M2-确定性编译与执行内核-实施规格-v1.md)、[ADR-0004 2.4 时间口径](../01-decisions/ADR-0004-首发业务域与Scenario范围.md)
> **修订记录**：2026-11-08 v1 首次冻结：funnel/yoy_mom 的 DSL Schema、§9.3/§9.4 契约项逐条定档

本规格把 v1.4 §9.3/§9.4 要求「必须固定」的语义契约项逐条定档——构造器编码（W3/T3.3）据此实现，
Golden 样本据此出题；契约项变更须发本规格新版本。

## 1. funnel 漏斗

### 1.1 DSL Schema（analysis_params）

```json
{
  "analysis_mode": "funnel",
  "root_concept_id": "concept.customer",
  "relation_path": ["<规划器填入>"],
  "dimensions": ["dim.region"],
  "analysis_params": {
    "steps": ["ORDER_PLACED", "REFUND"],
    "cohort_window": { "time_expression_raw": "近 30 天" },
    "step_window": "7d",
    "subject_property_id": "property.customer.customer_id",
    "deduplication": "first_occurrence",
    "conversion_denominator": "previous_step"
  }
}
```

| 字段 | 约束 |
|---|---|
| `steps` | ≥2 个事件类型标识（event.event_type 枚举值），**严格有序**；重复步骤拒绝 |
| `cohort_window` | 相对时间表达（time_expression_raw），由时间解析器换算 |
| `step_window` | 相邻步骤最大间隔（如 `7d`）；超窗不计转化 |
| `subject_property_id` | 主体标识（本体属性 ID），禁止自带物理 user_id |
| `deduplication` | `first_occurrence`（M2 定档：按首次事件计数） |
| `conversion_denominator` | `previous_step`（M2 定档） |

### 1.2 语义契约定档（v1.4 §9.3 逐条）

| §9.3 契约项 | M2 定档 |
|---|---|
| 分母 | **上一步**（`previous_step`） |
| 用户计数 | 按**首次事件**计数（first_occurrence） |
| 步骤有序性 | **严格有序**（step_time 单调递增） |
| 时间窗口 | 用户级 cohort 窗口（cohort_window 内发生首步）+ 步窗 step_window |
| 跨会话 | 允许（同一 customer 跨会话事件计入） |
| 同时事件排序 | event_time 相同按 event_type 字典序 |
| 分组维度取值 | **首次触点时值**（首步发生时的维度值） |

### 1.3 编译形态（Golden 基准）

步进 CTE：每个步骤一个 CTE（cohort 内客户 + 该步首次事件时间），逐步 LEFT JOIN 前一步，
`count(distinct subject)` per 步骤 + 维度分组；单条参数化 SQL（无字符串拼接）。

## 2. yoy_mom 同环比

### 2.1 DSL Schema（analysis_params）

```json
{
  "analysis_mode": "yoy_mom",
  "root_concept_id": "concept.order",
  "relation_path": ["<规划器填入>"],
  "metrics": ["metric.gmv"],
  "dimensions": ["dim.region"],
  "analysis_params": { "period": "MONTH" }
}
```

| 字段 | 约束 |
|---|---|
| `period` | `MONTH` \| `YEAR`（封闭枚举）；QUARTER 列 M6 |
| `metrics` | 恰 1 个指标（M2 定档：单指标同环比） |
| `dimensions` | 可选分组维度 |

### 2.2 语义契约定档（v1.4 §9.4 逐条）

| §9.4 契约项 | M2 定档 |
|---|---|
| 自然周期 vs 业务周期 | **自然日历周期**（自然月/自然年） |
| 时区 | Asia/Shanghai（ADR-0004 2.4） |
| 不完整周期 | **不允许比较**——当前不完整周期整期排除（产出行少一期并在元数据标注） |
| 缺失周期 | 输出该周期行、指标值为 null |
| 分母为零 | 环比值为 null（不输出 0、不输出 ∞） |
| 维度枚举一致 | 当前与对比周期**必须同一维度枚举**（并集取齐，缺失侧补 null 行） |
| 相对时间与解析器版本 | 解析器版本随清单；版本号进审计 |

### 2.3 编译形态（Golden 基准）

窗口函数：按周期分桶（date_trunc('month'/'year', time AT TIME ZONE 'Asia/Shanghai')），
`lag(value, period_lag) OVER (PARTITION BY dims ORDER BY period)` 产出对比列；
同比与环比各一列（yoy/mom），相对值为 null 安全除法。

## 3. 校验与验收对齐

- 两种模式的 `analysis_params` 校验进 W2 校验器完整化（steps 有序/period 枚举/字段封闭）；
- Golden 集 g-f01–g-f04、g-y01–g-y04 的预期 SQL 形状以本规格 §1.3/§2.3 为准；
- 语义契约项变更 = 本规格升版（v1.4 §9「必须固定」的刚性要求）。

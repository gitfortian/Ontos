# test 集 v4（主指标评测集）

> **状态**：已冻结
> **版本**：v4
> **类型**：验证
> **负责人**：项目组
> **最后更新**：2026-11-11
> **适用范围**：正式验收主指标（任务成功率）的**唯一分母**；`test` 集合见[正式评测集 v5](正式评测集-v5.md) §2
> **上游依据**：[完整可落地方案 v1.4 §13.2/§13.4](../../02-solutions/architecture/智能问数系统-完整可落地方案-v1.4.md)、[评测统计口径 v1](评测统计口径-v1.md)、[ADR-0005 评测门槛](../../01-decisions/ADR-0005-评测门槛与PoC执行责任.md)、[ADR-0016 品类粒度边界](../../01-decisions/ADR-0016-品类维度与订单事实指标的粒度边界.md)、[ADR-0018 E-5 订单项级指标实现口径](../../01-decisions/ADR-0018-E5订单项级指标实现口径.md)、[M7 实施规格 v1.0 §3.3](../../03-plans/stages/M7/M7-评测对抗防线与可观测-实施规格-v1.0.md)
> **历史版本**：[v3](../../99-archive/verification/test集-v3.md)（已归档）
> **修订记录**：2026-11-11 **v4**（M7/W1/T1.1，ADR-0018）——E-5 方案 A 落地：新增订单项销量指标 `metric.order_item_quantity` 与 2 题正例（QUERY / YOY_MOM）；原 2 题「订单数 × 品类」去掉 `skipReason`、转为**预期拒绝正例**进入分母（仍断言 `1002000014` fail-closed）；v3 其余 271 题零变化。机器可读文件 [`test集-v4.json`](test集-v4.json)（sha256 `8c6e8081433f1afb…`）

## 1. 定位与规模

- **这是主指标的分母**：任务成功率只按本集合报告（§13.4）；dev / validation 集的结果不得计入。
- **规模 273 题**：v3 的 271 题零变化（其中 2 题判据调整见 §3），新增 2 题订单项销量正例。
- **skip 0 题**：v3 的 2 题 E-5 哨兵已转预期拒绝正例（进入分母），不再 skip；有效分母 **273 题**。
- 题目语言为中文自然语言，期望为**结构化终态**（终态类型 + DSL 关键字段 / 期望原因码），不是自由文本答案。
- 冻结方式：整文件 sha256 由 runner 每次评测重算并与本文件 §8 比对；修订走升版（旧版归档），不得原地改。
- **执行路径口径**：runner 固定于**确定性降级路径**（`ontos.agent.enabled=false`，与 [smoke-runner v2](../../../scripts/verify/smoke-runner.mjs) 同口径）。多轮语义（维度增量合并 / 纠正覆盖 / 指代消解）的完整判定属 LLM 编排职责，LLM 路径评测见 M7/W2（E-7）。

## 2. 五维分布（评测统计口径 v1 枚举 + analysis_mode 附加维度）

| query_shape | 题量 | | analysis_mode（附加维度） | 题量 |
|---|---|---|---|---|
| aggregate | 155 | | QUERY | 137 |
| lookup_attribute | 34 | | LOOKUP | 47 |
| composite | 30 | | COMPOSITE | 30 |
| **scenario_mode** | **15** | | LOOKUP_BATCH | 14 |
| lookup_batch | 14 | | DISCOVERY | 12 |
| lookup_boolean | 13 | | **YOY_MOM** | **11** |
| discovery | 12 | | FUNNEL | 10 |
| | | | RETENTION / ATTRIBUTION | 6 / 6 |

| turn_type | 题量 | | risk_type | 题量 | | scenario_entry_mode | 题量 |
|---|---|---|---|---|---|---|---|
| single | 201 | | normal | 237 | | none | 258 |
| multi_turn_followup | 30 | | prompt_injection | 10 | | **manual** | **5** |
| multi_turn_incremental | 30 | | differential | 10 | | **cold_start** | **5** |
| correction | 12 | | cross_domain | 8 | | **inherit** | **5** |
| | | | existence_probe | 8 | | auto | 0（缺口 E-6） |

## 3. v4 变更（E-5）

| 题 ID | v3 状态 | v4 状态 | 说明 |
|---|---|---|---|
| `t-agg-single-17` | skip（E-5 哨兵） | **预期拒绝正例（进分母）** | 「今年各商品品类的订单数」——订单级指标 × 品类仍 fail-closed `1002000014`；验证拒绝而非重复计数 |
| `t-yoy-06` | skip（E-5 哨兵） | **预期拒绝正例（进分母）** | 「今年各品类订单数同比」——同上（YOY_MOM） |
| `t-agg-single-17b` | — | **新增正例** | 「今年各商品品类的销量」→ `metric.order_item_quantity` + `dim.product_category`（QUERY） |
| `t-yoy-06b` | — | **新增正例** | 「今年各品类销量同比变化」→ 同上（YOY_MOM，period=YEAR） |

- 订单项销量走 `order_item` 粒度：`order_item → product`（N:1 forward）+ `order_item → orders`（反向，基数翻转后 N:1），不扩散；
- **订单项金额**（quantity × price）未实现：`order_item` 无金额列且指标模型不支持表达式，登记后续（ADR-0018 决策 3）；
- `scenario.sales` 的 scope 不含 `concept.order_item` / `dim.product_category`，订单项销量**只在自由问数可用**（场景只收窄，ADR-0018 决策 5）。

## 4. 判据字段

每题包含：

- `expected`：终态类型（`query` / `clarification` / `out_of_capability` / `discovery` / `composite`）+ 关键字段：
  - QUERY / YOY：`metricId` / `dimensions` / `filters[]`（含 `operator`）/ `timeExpressionRaw`；
  - FUNNEL：`analysisParams.steps / cohortWindow.timeExpressionRaw / stepWindow / subjectPropertyId / deduplication / conversionDenominator`；
  - RETENTION：`analysisParams.cohortEvent / returnEvent / periodUnit / periods / subjectPropertyId` + 顶层 `timeExpressionRaw`；
  - ATTRIBUTION：`analysisParams.conversionEvent / touchEvent / attributionWindow / model / subjectPropertyId` + 顶层 `timeExpressionRaw`；
  - LOOKUP：`entityId` / `attributes` / `identity`（布尔另带 `booleanMetricId`）；
  - LOOKUP_BATCH：`entityId` / `identityPropertyId` / `attributes` / `identities` / `rowAligned`；
  - 负例：`errorCode`（如 E-5 的 `1002000014`）。**负例允许缺 `mode`**：runner 按 `period` 是否存在做确定性补全（有 → YOY_MOM，无 → QUERY）后构造被拒 DSL；判据仍是 `errorCode` 精确匹配，不做语义猜测。
- `necessary`（必需项）：缺一即 fail；
- `forbidden`（禁错项）：命中即 fail，不得用其他项的高分抵消（§13.4）；默认 = 自由 SQL / 物理表名 / LLM 产出绝对日期。
- `skipReason`（可选）：当前无 skip 题；未来如新增，必须写明依赖缺口。

## 5. 已知缺口（如实登记）

| 缺口 | 影响 | 闭合时点 |
|---|---|---|
| **E-5**：品类 × 订单事实指标 | 订单级指标 × 品类仍 fail-closed `1002000014`（**有意保留**）；订单项销量已可用，订单项金额未实现 | **部分关闭**（v4，ADR-0018）：销量口径落地；金额待指标表达式/加列 |
| **E-6**：`auto` 入口 0 题 | 清单无 `auto_if_confident` 场景资产（`scenario.sales` 为 `recommend_or_confirm`） | M7/W1 T1.2 发布 `scenario.sales.auto` 后补 ≥3 题 |
| **E-7**：多轮语义完整判定属 LLM 路径 | 降级路径判据为逐轮合法终态；增量合并/纠正覆盖/指代消解的语义判定需 LLM 编排评测 | M7/W2 LLM 路径评测（`llm-test-runner.mjs`） |
| `risk_type` 未含「聚合泄露」独立枚举 | 敏感面靠 M4 累计检测覆盖 | M7 评估是否扩展口径（需 ADR） |

## 6. 使用方式

```bash
# M7/W1：test 集主指标评测（确定性降级路径，273 题）
node scripts/verify/test-runner.mjs --base-url http://127.0.0.1:8080 \
  --set docs/06-verification/evaluation/test集-v4.json
```

runner 必须输出：`pass/fail/skip/error` 计数、有效样本数、四维 + `scenario_entry_mode` 分层 + Wilson 95% 区间、
`analysis_mode` 附加分层的成功率，并复算本文件 sha256 与 §8 比对。

runner 侧确定性补全（不属于用户语义但 DSL 必填）：QUERY/YOY 的 `timeRange.field`（首发唯一时间维度 `dim.order_date`）；
负例缺 `mode` 时按 `period` 补全（见 §4）；LOOKUP 单实体 `limit=1`、LOOKUP_BATCH `limit=len(identities)`；
存在性探测题默认投影属性（customer→tier / order→amount）；场景 manual 题的 `timeRange` 补全；
FUNNEL 顶层不得带 `timeRange`（时间窗只走 `analysisParams.cohortWindow`）、ATTRIBUTION 不得带 `timeRange`（观察窗走顶层 `timeExpressionRaw`）。

## 7. 题族总表（26 族，273 题）

v2 的 20 族（见[归档 v2](../../99-archive/verification/test集-v2.md)）+ v3 新增 5 族（见[归档 v3](../../99-archive/verification/test集-v3.md) §3）+ v4 新增订单项销量族（2 题）。

## 8. 冻结哈希

| 文件 | sha256（前 16 位） |
|---|---|
| [`test集-v4.json`](test集-v4.json) | `8c6e8081433f1afb` |

> 完整哈希（64 位）：`8c6e8081433f1afb10a14aebaa0e53396dabc0173f7a9f72a2491402585f07d4`。
> 生成与哈希均按**磁盘字节**复算，避免「算内存字符串、比文件字节」的漂移。

## 9. 复审证据（v4 首轮评测，2026-11-11）

| 证据 | 结果 |
|---|---|
| 全量真服务评测（[`test-runner.mjs`](../../../scripts/verify/test-runner.mjs)） | **273 pass / 0 fail / 0 skip / 0 error**，成功率 1.0，Wilson95 `[0.9861, 1]` |
| 清单 | `2026.11.11-seventeenth`（contentHash `1d3ec2e247a2c912…`，9 指标） |
| E-5 验证 | 订单项销量 × 品类 `code=0`（joins：order_item→product N:1 / orders→order_item 反向 N:1）；订单数 × 品类仍 `1002000014` |
| 证据固化 | [`test集-M7W1-E5证据-v1.json`](test集-M7W1-E5证据-v1.json)（SHA-256 `283c198aaed7f9a5…`，273/0/0/0） |
| 过程中修复 | runner 负例缺 `mode` 无法构造 DSL（首跑 2 error）→ 按 `period` 确定性补全，重跑 273/0/0/0 |

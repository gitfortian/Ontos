# test 集 v2（主指标评测集）

> **状态**：已冻结
> **版本**：v2
> **类型**：验证
> **负责人**：项目组
> **最后更新**：2026-11-10
> **适用范围**：正式验收主指标（任务成功率）的**唯一分母**；`test` 集合见[正式评测集 v3](正式评测集-v3.md) §2
> **上游依据**：[完整可落地方案 v1.4 §13.2/§13.4](../../02-solutions/architecture/智能问数系统-完整可落地方案-v1.4.md)、[评测统计口径 v1](../../06-verification/evaluation/评测统计口径-v1.md)、[ADR-0005 评测门槛](../../01-decisions/ADR-0005-评测门槛与PoC执行责任.md)、[ADR-0014 并行义务纪律](../../01-decisions/ADR-0014-并行义务纪律与M7同步建设偏离.md)、[M6 实施规格 v1.0 §3.8 决策 8](../../03-plans/stages/M6/M6-分析模式ScenarioSkill与复合意图-实施规格-v1.0.md)
> **历史版本**：[v1](../../99-archive/verification/test集-v1.md)（已归档）
> **修订记录**：2026-11-10 v1 首版：D-3 欠账闭环——241 题（全部新增），四维分层按统计口径 v1，附加 `analysis_mode` 维度。2026-11-10 **v2**：再次深度审核修复 7 类缺陷——逐题标注 shape/mode（原按题族批量套）、分析模式补齐必填 `analysisParams`、取值对齐清单 v12 与 demo 种子（订单仅 o-1..o-5）、时间表达只用已发布模式、过滤补 `operator`、布尔 lookup 补投影属性、哈希改按磁盘字节复算；新增缺口 E-5（品类 × 订单事实指标的粒度扩散）。机器可读文件 [`test集-v2.json`](test集-v2.json)（sha256 `556e564c8276c1e1…`）。

## 1. 定位与规模

- **这是主指标的分母**：任务成功率只按本集合报告（§13.4）；dev / validation 集的结果不得计入。
- **规模 241 题**，全部为**新增编写**（不把 Golden v2 / 复合 30 / M3-Golden / 冒烟 20 平移进 test——它们保持原归属）。
- 其中 **2 题标 `skip`**（缺口 E-5），skip 不进分母；有效分母 **239 题**。
- 题目语言为中文自然语言，期望为**结构化终态**（终态类型 + DSL 关键字段 / 期望原因码），不是自由文本答案。
- 冻结方式：整文件 sha256 由 runner 每次评测重算并与本文件 §7 比对；修订走升版（旧版归档），不得原地改。

## 2. 四维分布（评测统计口径 v1 枚举）

| query_shape | 题量 | | analysis_mode（附加维度） | 题量 |
|---|---|---|---|---|
| aggregate | 138 | | QUERY | 106 |
| lookup_attribute | 34 | | LOOKUP | 47 |
| composite | 30 | | COMPOSITE | 30 |
| lookup_batch | 14 | | LOOKUP_BATCH | 14 |
| lookup_boolean | 13 | | DISCOVERY | 12 |
| discovery | 12 | | YOY_MOM / FUNNEL | 10 / 10 |
| scenario_mode | **0**（见 §5） | | RETENTION / ATTRIBUTION | 6 / 6 |

| turn_type | 题量 | | risk_type | 题量 |
|---|---|---|---|---|
| single | 189 | | normal | 205 |
| multi_turn_followup | 25 | | prompt_injection | 10 |
| multi_turn_incremental | 15 | | differential | 10 |
| correction | 12 | | cross_domain | 8 |
| | | | existence_probe | 8 |

`scenario_entry_mode` 全部为 `none`（Scenario 资产化在本阶段 T2.x，尚未上线）。

> **口径说明**：统计口径 v1 的 `query_shape` 枚举来自 §13.4，**不包含** M6 新增的 RETENTION / ATTRIBUTION。为不改冻结口径，
> 本集合同时记录 `analysis_mode`（八模式）作为**附加维度**：分层统计仍按四维出表，模式维度用于 M6 能力验收的细分报告。
> 该缺口已登记（正式评测集 v3 §4），是否并入 `query_shape` 由 M7 决定（需 ADR）。

## 3. 题族设计（20 族，覆盖能力与风险）

| 族 | 题量 | 覆盖 |
|---|---|---|
| QUERY 单维聚合 | 20 | 地区 / 渠道 / 客户等级 × 4 指标 × 5 时间表达（品类见 E-5） |
| QUERY 多维度 | 10 | 双维 / 三维组合 |
| QUERY 过滤聚合 | 10 | 单过滤 / 双过滤 / 过滤 + 分组 |
| YOY_MOM | 10 | MONTH / YEAR 周期 × 维度；`mom` / `yoy` 两列 |
| FUNNEL | 10 | 2–3 步（下单 / 退款 / 触点），stepWindow 与主体维度 |
| RETENTION | 6 | 周 / 月周期、观察窗、主体维度 |
| ATTRIBUTION | 6 | 转化窗 × 回溯窗、last_touch、渠道桶 |
| LOOKUP 属性 | 25 | 客户 15（3 客户 × 5 问法）+ 订单 10（5 订单 × 3 属性组合） |
| LOOKUP 布尔 | 12 | 布尔指标 × 3 客户 × 4 问法（必带投影属性） |
| LOOKUP_BATCH | 12 | 含缺失行与全缺失两种对齐场景 |
| COMPOSITE 无依赖 | 20 | 2–3 个子查询、合并 SQL 0 |
| COMPOSITE 结果依赖 | 5 | dependsOn、空结果结构化失败 |
| DISCOVERY | 12 | 三键载荷、已发布引用、零 SQL |
| 多轮 followup | 15 | 指代消解、只改一处 |
| 多轮 incremental | 15 | 维度增量合并不丢 |
| correction | 12 | 纠正覆盖、不残留 |
| 注入 | 10 | 提示注入 / 越权 / 物理名套取 |
| 跨轮差分 | 10 | 累计检测（L1/L2），逐题标注 shape/mode |
| 跨域 | 8 | 首发单域 → 明确拒绝 |
| 存在性探测 | 8 | 不存在与无权限同形，逐题标注 shape/mode |

> 合计 241（20 族）。v1 的题族表多列了一行不存在的「COMPOSITE 扩展 15」且声称 19 族，与 JSON 不符，v2 已按实际 id 前缀重算。

## 4. 判据字段

每题包含：

- `expected`：终态类型（`query` / `clarification` / `out_of_capability` / `discovery` / `composite`）+ 关键字段：
  - QUERY / YOY：`metricId` / `dimensions` / `filters[]`（含 `operator`）/ `timeExpressionRaw`；
  - FUNNEL：`analysisParams.steps / cohortWindow.timeExpressionRaw / stepWindow / subjectPropertyId / deduplication / conversionDenominator`；
  - RETENTION：`analysisParams.cohortEvent / returnEvent / periodUnit / periods / subjectPropertyId` + 顶层 `timeExpressionRaw`；
  - ATTRIBUTION：`analysisParams.conversionEvent / touchEvent / attributionWindow / model / subjectPropertyId` + 顶层 `timeExpressionRaw`；
  - LOOKUP：`entityId` / `attributes` / `identity`（布尔另带 `booleanMetricId`）；
  - LOOKUP_BATCH：`entityId` / `identityPropertyId` / `attributes` / `identities` / `rowAligned`；
  - 负例：`errorCode`（如 E-5 的 `1002000014`）。
- `necessary`（必需项）：缺一即 fail；
- `forbidden`（禁错项）：命中即 fail，不得用其他项的高分抵消（§13.4）；
  默认禁错项 = 自由 SQL / 物理表名 / LLM 产出绝对日期。
- `skipReason`（可选）：写明依赖缺口；`skip` 不进分母。

`skip` 规则：依赖尚未上线能力的题目由 runner 标 `skip` 并写明依赖里程碑，**skip 不进分母**。
当前预期 skip 面：`scenario_entry_mode ≠ none` 的题目（本版 0 题）、结果依赖复合（依赖 W4，5 题）、E-5 粒度缺口（2 题）。

## 5. 已知缺口（如实登记）

| 缺口 | 影响 | 闭合时点 |
|---|---|---|
| **E-5**：品类维度 × 订单事实指标不可编译 | 首发本体 `Order 1---N OrderItem`，按品类分组订单事实会放大粒度，规划器 fail-closed（`1002000014`）；`dim.product_category` 目前无法与任何订单指标同用 | M7 决定补订单项级指标或显式去重口径；v2 保留 2 题 skip + 2 题拒绝判据作为哨兵 |
| `scenario_mode` 0 题 | 无法按 Scenario 入口分层报告 | T2.5 完成后补充 ≥15 题（含 cold_start / manual / auto） |
| `scenario_entry_mode` 全 none | 入口维度暂为单一值 | 同上 |
| `multi_turn_incremental` 仅 15 题（<30 小样本） | 该分层置信区间仅供参考 | W5 前补至 ≥30 |
| 结果依赖复合 5 题依赖 W4 | 当前会 skip | W4 完成后转 pass |
| `risk_type` 未含「聚合泄露」独立枚举 | 敏感面靠 M4 累计检测覆盖 | M7 评估是否扩展口径（需 ADR） |

## 6. 使用方式

```bash
# W5/T5.1：runner 扩展后按 test 集跑主指标
node scripts/verify/smoke-runner.mjs --set docs/06-verification/evaluation/test集-v2.json
```

runner 必须输出：`pass/fail/skip/error` 计数、有效样本数、四维分层 + Wilson 95% 区间、
`analysis_mode` 附加分层的成功率，并复算本文件 sha256 与 §7 比对。
runner 侧确定性补全（不属于用户语义但 DSL 必填）：QUERY/YOY 的 `timeRange.field`（首发唯一时间维度 `dim.order_date`）；
LOOKUP 单实体 `limit=1`、LOOKUP_BATCH `limit=len(identities)`。

## 7. 冻结哈希

| 文件 | sha256（前 16 位） |
|---|---|
| [`test集-v2.json`](test集-v2.json) | `556e564c8276c1e1` |

> 完整哈希（64 位）：`556e564c8276c1e1e0714b38bf9308ac0400c6b85fb61bdab6ffa9d253bda110`。
> 生成脚本对**磁盘字节**复算，避免「算内存字符串、比文件字节」的漂移。

## 8. 复审证据（v2 修复的可复核口径）

| 证据 | 结果 |
|---|---|
| 资产引用（metric / dimension / property / event / concept） | 241 题全部命中清单 v12，0 缺失 |
| 时间表达 | 全部命中 5 个已发布模式（或 `近 N 天`），0 未命中 |
| 逐条编译（`POST /api/v1/query/compile`） | 单轮可 DSL 化 **119/119 通过**（skip 与多轮/复合/发现另测） |
| 复合子意图 | 20 个无依赖复合的 45 个子意图逐条编译 **0 失败** |
| 负例 | 2 题稳定返回 `1002000014`（粒度扩散） |
| 取值域 | 订单仅 o-1..o-5、客户 c-1001..c-1003（探测题用不存在 id）、品类仅手机/配件/穿戴 |

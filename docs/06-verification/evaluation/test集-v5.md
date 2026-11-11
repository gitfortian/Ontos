# test 集 v5（主指标评测集）

> **状态**：已冻结
> **版本**：v5
> **类型**：验证
> **负责人**：项目组
> **最后更新**：2026-11-11
> **适用范围**：正式验收主指标（任务成功率）的**唯一分母**；`test` 集合见[正式评测集 v6](正式评测集-v6.md) §2
> **上游依据**：[完整可落地方案 v1.4 §13.2/§13.4](../../02-solutions/architecture/智能问数系统-完整可落地方案-v1.4.md)、[评测统计口径 v1](评测统计口径-v1.md)、[ADR-0005 评测门槛](../../01-decisions/ADR-0005-评测门槛与PoC执行责任.md)、[ADR-0018 E-5 订单项级指标实现口径](../../01-decisions/ADR-0018-E5订单项级指标实现口径.md)、[M7 实施规格 v1.0 §3.4](../../03-plans/stages/M7/M7-评测对抗防线与可观测-实施规格-v1.2.md)
> **历史版本**：[v4](../../99-archive/verification/test集-v4.md)（已归档）、[v3](../../99-archive/verification/test集-v3.md)（已归档）
> **修订记录**：2026-11-11 **v5**（M7/W1/T1.2，E-6）——新增 3 题 auto 入口（唯一命中自动进入 `scenario.sales.auto` / 多命中回退自由问数 / 无命中回退）；v4 的 273 题零变化。机器可读文件 [`test集-v5.json`](test集-v5.json)（sha256 `9f525c46d4ce6dd5…`）

## 1. 定位与规模

- **这是主指标的分母**：任务成功率只按本集合报告（§13.4）；dev / validation 集的结果不得计入。
- **规模 276 题**：v4 的 273 题零变化 + 新增 3 题 auto 入口。
- **skip 0 题**：有效分母 **276 题**。
- 题目语言为中文自然语言，期望为**结构化终态**（终态类型 + DSL 关键字段 / 期望原因码 / 场景态断言），不是自由文本答案。
- 冻结方式：整文件 sha256 由 runner 每次评测重算并与本文件 §8 比对；修订走升版（旧版归档），不得原地改。
- **执行路径口径**：runner 固定于**确定性降级路径**（`ontos.agent.enabled=false`，与 [smoke-runner v2](../../../scripts/verify/smoke-runner.mjs) 同口径）。多轮语义（维度增量合并 / 纠正覆盖 / 指代消解）的完整判定属 LLM 编排职责，LLM 路径评测见 M7/W2（E-7）。

## 2. 五维分布（评测统计口径 v1 枚举 + analysis_mode 附加维度）

| query_shape | 题量 | | analysis_mode（附加维度） | 题量 |
|---|---|---|---|---|
| aggregate | 155 | | QUERY | 140 |
| lookup_attribute | 34 | | LOOKUP | 47 |
| composite | 30 | | COMPOSITE | 30 |
| **scenario_mode** | **18** | | LOOKUP_BATCH | 14 |
| lookup_batch | 14 | | DISCOVERY | 12 |
| lookup_boolean | 13 | | YOY_MOM | 11 |
| discovery | 12 | | FUNNEL | 10 |
| | | | RETENTION / ATTRIBUTION | 6 / 6 |

| turn_type | 题量 | | risk_type | 题量 | | scenario_entry_mode | 题量 |
|---|---|---|---|---|---|---|---|
| single | 204 | | normal | 240 | | none | 258 |
| multi_turn_followup | 30 | | prompt_injection | 10 | | **manual** | **5** |
| multi_turn_incremental | 30 | | differential | 10 | | **cold_start** | **5** |
| correction | 12 | | cross_domain | 8 | | **inherit** | **5** |
| | | | existence_probe | 8 | | **auto** | **3**（v5 新增） |

## 3. v5 变更（E-6 auto 入口）

| 题 ID | 题面 | 期望 | 会话态断言（`session_state.scenario_state`） |
|---|---|---|---|
| `t-auto-01` | 自动销售分析：上个月各地区的成交金额是多少 | `query` | `in_scenario` + `scenarioId=scenario.sales.auto`（唯一命中自动进入） |
| `t-auto-02` | 自动销售分析 成交金额分析：上个月各地区的成交金额是多少 | `query` | 未进入场景（两个 hint 多命中 → 回退自由问数） |
| `t-auto-03` | 上个月各地区的成交金额是多少 | `query` | 未进入场景（无 hint 命中 → 回退自由问数） |

- auto 判据分两层：**终态**（query）+ **会话态**（是否真的进入场景）。runner 通过 `ONTOS_PSQL` 查 `session_state.scenario_state`；未提供 psql 时只判终态并在结果标 `entryVerified=false`，**不伪造进入结论**。
- 场景资产 `scenario.sales.auto`（`entryMode=auto_if_confident`）scope 与 `scenario.sales` 一致；demo 租户授权由 V18 迁移派生 `seed-2026.11.11-v1` 快照。
- `scenario.sales` 仍为 `recommend_or_confirm`，auto 场景不改变其确认语义。

## 4. 判据字段

每题包含：

- `expected`：终态类型（`query` / `clarification` / `out_of_capability` / `discovery` / `composite`）+ 关键字段：
  - QUERY / YOY：`metricId` / `dimensions` / `filters[]`（含 `operator`）/ `timeExpressionRaw`；
  - FUNNEL：`analysisParams.steps / cohortWindow.timeExpressionRaw / stepWindow / subjectPropertyId / deduplication / conversionDenominator`；
  - RETENTION：`analysisParams.cohortEvent / returnEvent / periodUnit / periods / subjectPropertyId` + 顶层 `timeExpressionRaw`；
  - ATTRIBUTION：`analysisParams.conversionEvent / touchEvent / attributionWindow / model / subjectPropertyId` + 顶层 `timeExpressionRaw`；
  - LOOKUP：`entityId` / `attributes` / `identity`（布尔另带 `booleanMetricId`）；
  - LOOKUP_BATCH：`entityId` / `identityPropertyId` / `attributes` / `identities` / `rowAligned`；
  - **auto 入口（v5）**：`scenarioEntry`（`{state: in_scenario, scenarioId}` 或 `{state: none}`）；
  - 负例：`errorCode`（如 E-5 的 `1002000014`）。负例允许缺 `mode`：runner 按 `period` 是否存在做确定性补全后构造被拒 DSL；判据仍是 `errorCode` 精确匹配。
- `necessary`（必需项）：缺一即 fail；
- `forbidden`（禁错项）：命中即 fail，不得用其他项的高分抵消（§13.4）；默认 = 自由 SQL / 物理表名 / LLM 产出绝对日期。
- `skipReason`（可选）：当前无 skip 题；未来如新增，必须写明依赖缺口。

## 5. 已知缺口（如实登记）

| 缺口 | 影响 | 闭合时点 |
|---|---|---|
| **E-5**：品类 × 订单事实指标 | 订单级指标 × 品类仍 fail-closed `1002000014`（**有意保留**）；订单项销量已可用，订单项金额未实现 | **部分关闭**（v4，ADR-0018）：销量落地；金额待指标表达式/加列 |
| **E-6**：`auto` 入口 | 已发布 `scenario.sales.auto` + 3 题 + 会话态断言 | **已关闭**（v5）；后续场景扩展按需补题 |
| **E-7**：多轮语义完整判定属 LLM 路径 | 降级路径判据为逐轮合法终态；增量合并/纠正覆盖/指代消解的语义判定需 LLM 编排评测 | M7/W2 LLM 路径评测（`llm-test-runner.mjs`） |
| `risk_type` 未含「聚合泄露」独立枚举 | 敏感面靠 M4 累计检测覆盖 | M7 评估是否扩展口径（需 ADR） |

## 6. 使用方式

```bash
# M7/W1：test 集主指标评测（确定性降级路径，276 题）
# auto 入口的会话态验证需提供 psql 命令前缀（可选；不提供则只判终态）
ONTOS_PSQL="PGPASSWORD=*** psql -U ... -d ontos -t -A" \
  node scripts/verify/test-runner.mjs --base-url http://127.0.0.1:8080 \
  --set docs/06-verification/evaluation/test集-v5.json
```

runner 必须输出：`pass/fail/skip/error` 计数、有效样本数、四维 + `scenario_entry_mode` 分层 + Wilson 95% 区间、
`analysis_mode` 附加分层的成功率，并复算本文件 sha256 与 §8 比对。

runner 侧确定性补全（不属于用户语义但 DSL 必填）：QUERY/YOY 的 `timeRange.field`（首发唯一时间维度 `dim.order_date`）；
负例缺 `mode` 时按 `period` 补全（见 §4）；LOOKUP 单实体 `limit=1`、LOOKUP_BATCH `limit=len(identities)`；
存在性探测题默认投影属性（customer→tier / order→amount）；场景 manual 题的 `timeRange` 补全；
FUNNEL 顶层不得带 `timeRange`（时间窗只走 `analysisParams.cohortWindow`）、ATTRIBUTION 不得带 `timeRange`（观察窗走顶层 `timeExpressionRaw`）。

## 7. 题族总表（27 族，276 题）

v2 的 20 族（见[归档 v2](../../99-archive/verification/test集-v2.md)）+ v3 新增 5 族（见[归档 v3](../../99-archive/verification/test集-v3.md) §3）+ v4 订单项销量族（2 题）+ v5 auto 入口族（3 题）。

## 8. 冻结哈希

| 文件 | sha256（前 16 位） |
|---|---|
| [`test集-v5.json`](test集-v5.json) | `9f525c46d4ce6dd5` |

> 完整哈希（64 位）：`9f525c46d4ce6dd53282038b6cf7e91bd8cdacc5cb84afdd4031679c7911a178`。
> 生成与哈希均按**磁盘字节**复算，避免「算内存字符串、比文件字节」的漂移。

## 9. 复审证据（v5 首轮评测，2026-11-11）

| 证据 | 结果 |
|---|---|
| 全量真服务评测（[`test-runner.mjs`](../../../scripts/verify/test-runner.mjs)） | **276 pass / 0 fail / 0 skip / 0 error**，成功率 1.0，Wilson95 `[0.9863, 1]` |
| 清单 | `2026.11.11-eighteenth`（contentHash `ba3affaad765877e…`，2 场景 / 9 指标） |
| E-6 验证 | `t-auto-01` 会话态 `in_scenario + scenario.sales.auto`；`t-auto-02/03` 未进入场景（多命中/无命中回退）——三题 `entryVerified=true` |
| 证据固化 | [`test集-M7W1-E6证据-v1.json`](test集-M7W1-E6证据-v1.json)（SHA-256 `a6958fa511c6ecf9…`，276/0/0/0） |

# test 集 v3（主指标评测集）

> **状态**：已冻结
> **版本**：v3
> **类型**：验证
> **负责人**：项目组
> **最后更新**：2026-11-10
> **适用范围**：正式验收主指标（任务成功率）的**唯一分母**；`test` 集合见[正式评测集 v4](正式评测集-v4.md) §2
> **上游依据**：[完整可落地方案 v1.4 §13.2/§13.4](../../02-solutions/architecture/智能问数系统-完整可落地方案-v1.4.md)、[评测统计口径 v1](../../06-verification/evaluation/评测统计口径-v1.md)、[ADR-0005 评测门槛](../../01-decisions/ADR-0005-评测门槛与PoC执行责任.md)、[ADR-0014 并行义务纪律](../../01-decisions/ADR-0014-并行义务纪律与M7同步建设偏离.md)、[M6 实施规格 v1.0 §3.8 决策 8](../../03-plans/stages/M6/M6-分析模式ScenarioSkill与复合意图-实施规格-v1.0.md)
> **历史版本**：[v2](../../99-archive/verification/test集-v2.md)（已归档）
> **修订记录**：2026-11-10 **v3**（M6/W5 T5.1 收敛）——E-1 场景入口题 15 题（manual 5 / cold_start 5 / inherit 5）+ E-2 多轮增量补至 30 题（新增 16–30）；v2 的 241 题零变化。生成脚本 [`build-testset-v3.mjs`](../../../scripts/verify/build-testset-v3.mjs)（可复算）；机器可读文件 [`test集-v3.json`](test集-v3.json)（sha256 `f60c366d915d2ab2…`）。

## 1. 定位与规模

- **这是主指标的分母**：任务成功率只按本集合报告（§13.4）；dev / validation 集的结果不得计入。
- **规模 271 题**，其中 v2 存量 241 题零变化、新增 30 题（场景入口 15 + 多轮增量 15）。
- **2 题标 `skip`**（缺口 E-5），skip 不进分母；有效分母 **269 题**。
- 题目语言为中文自然语言，期望为**结构化终态**（终态类型 + DSL 关键字段 / 期望原因码），不是自由文本答案。
- 冻结方式：整文件 sha256 由 runner 每次评测重算并与本文件 §8 比对；修订走升版（旧版归档），不得原地改。
- **执行路径口径（v3 新增声明）**：runner 固定于**确定性降级路径**（`ontos.agent.enabled=false`，与 [smoke-runner v2](../../../scripts/verify/smoke-runner.mjs) 同口径）。多轮语义（维度增量合并 / 纠正覆盖 / 指代消解）的完整判定属 LLM 编排职责，降级路径按安全底线判据（逐轮合法终态 / 不编造执行 / 不越权），LLM 路径评测登记为 M7 后续项（需稳定模型服务与多次统计运行）。

## 2. 五维分布（评测统计口径 v1 枚举 + analysis_mode 附加维度）

| query_shape | 题量 | | analysis_mode（附加维度） | 题量 |
|---|---|---|---|---|
| aggregate | 153 | | QUERY | 136 |
| lookup_attribute | 34 | | LOOKUP | 47 |
| composite | 30 | | COMPOSITE | 30 |
| **scenario_mode** | **15** | | LOOKUP_BATCH | 14 |
| lookup_batch | 14 | | DISCOVERY | 12 |
| lookup_boolean | 13 | | YOY_MOM / FUNNEL | 10 / 10 |
| discovery | 12 | | RETENTION / ATTRIBUTION | 6 / 6 |

| turn_type | 题量 | | risk_type | 题量 | | scenario_entry_mode | 题量 |
|---|---|---|---|---|---|---|---|
| single | 199 | | normal | 235 | | none | 256 |
| multi_turn_followup | 30 | | prompt_injection | 10 | | **manual** | **5** |
| **multi_turn_incremental** | **30** | | differential | 10 | | **cold_start** | **5** |
| correction | 12 | | cross_domain | 8 | | **inherit** | **5** |
| | | | existence_probe | 8 | | auto | 0（缺口 E-6） |

## 3. 新增题族（v3，30 题）

| 族 | 题量 | 覆盖 |
|---|---|---|
| 场景 manual 入口 | 5 | DSL `scenarioId` 直传：scope 内 3 题执行 + 未授权场景拒绝（1004000003）+ scope 外资产拒绝（1004000007） |
| 场景 cold_start 入口 | 5 | hint 推荐（不自动执行）→ 确认进入（场景内 discovery 承接 / query / VQR 冷启动样例命中）/ 退出候选留自由问数 |
| 场景 inherit 沿用 | 5 | 进场景后多轮：scope 内 query 3 题 + 同词歧义澄清（场景状态沿用）+ 越界澄清（退出候选） |
| 多轮增量补齐 | 15 | 指标 × 维度 × 时间表达组合铺开（避开 E-5 品类组合与跨域） |

场景题判据与 [smoke-runner v2](../../../scripts/verify/smoke-runner.mjs) 同源（2026-11-10 真服务逐题探针校准）；`auto` 入口需 `auto_if_confident` 场景资产（当前清单唯一场景为 `recommend_or_confirm`），登记缺口 **E-6**。

## 4. 判据字段

每题包含：

- `expected`：终态类型（`query` / `clarification` / `out_of_capability` / `discovery` / `composite`）+ 关键字段：
  - QUERY / YOY：`metricId` / `dimensions` / `filters[]`（含 `operator`）/ `timeExpressionRaw`；
  - FUNNEL：`analysisParams.steps / cohortWindow.timeExpressionRaw / stepWindow / subjectPropertyId / deduplication / conversionDenominator`；
  - RETENTION：`analysisParams.cohortEvent / returnEvent / periodUnit / periods / subjectPropertyId` + 顶层 `timeExpressionRaw`；
  - ATTRIBUTION：`analysisParams.conversionEvent / touchEvent / attributionWindow / model / subjectPropertyId` + 顶层 `timeExpressionRaw`（时间窗服务端回填，客户端不得提交 `timeRange`）；
  - LOOKUP：`entityId` / `attributes` / `identity`（布尔另带 `booleanMetricId`）；
  - LOOKUP_BATCH：`entityId` / `identityPropertyId` / `attributes` / `identities` / `rowAligned`；
  - 负例：`errorCode`（如 E-5 的 `1002000014`）。
- 场景题（v3 新增）：
  - manual：`dsl`（显式 DSL，含 `scenarioId`）；
  - cold_start / inherit：`flow[]`（逐轮 utterance + clarificationChoice）+ `expected.terminal` + 结构断言（`scenarioCandidate` / `exitCandidate`）。
- `necessary`（必需项）：缺一即 fail；
- `forbidden`（禁错项）：命中即 fail，不得用其他项的高分抵消（§13.4）；
  默认禁错项 = 自由 SQL / 物理表名 / LLM 产出绝对日期。
- `skipReason`（可选）：写明依赖缺口；`skip` 不进分母。

`skip` 规则：依赖尚未上线能力的题目由 runner 标 `skip` 并写明依赖缺口，**skip 不进分母**。
当前预期 skip 面：E-5 粒度缺口哨兵 2 题。

## 5. 已知缺口（如实登记）

| 缺口 | 影响 | 闭合时点 |
|---|---|---|
| **E-5**：品类维度 × 订单事实指标不可编译 | 首发本体 `Order 1---N OrderItem`，规划器 fail-closed（`1002000014`） | M7 决定补订单项级指标或显式去重口径；保留 2 题 skip 作为哨兵 |
| **E-6**：`auto` 入口 0 题 | 清单无 `auto_if_confident` 场景资产（唯一场景 `recommend_or_confirm`），自动进入路径无题可测 | 资产决策（新增 auto 场景或改 entryMode）后补 ≥3 题；路由器 auto 分支已有单测覆盖 |
| **E-7**：多轮语义完整判定属 LLM 路径 | 降级路径判据为逐轮合法终态；增量合并/纠正覆盖/指代消解的语义判定需 LLM 编排评测 | M7 LLM 路径评测（需稳定模型服务与多次统计运行）；M5 会话状态机单测覆盖投影语义 |
| `risk_type` 未含「聚合泄露」独立枚举 | 敏感面靠 M4 累计检测覆盖 | M7 评估是否扩展口径（需 ADR） |

## 6. 使用方式

```bash
# T5.1：test 集主指标评测（确定性降级路径，271 题）
node scripts/verify/test-runner.mjs --base-url http://127.0.0.1:8080
```

runner 必须输出：`pass/fail/skip/error` 计数、有效样本数、四维 + `scenario_entry_mode` 分层 + Wilson 95% 区间、
`analysis_mode` 附加分层的成功率，并复算本文件 sha256 与 §8 比对。
runner 侧确定性补全（不属于用户语义但 DSL 必填）：QUERY/YOY 的 `timeRange.field`（首发唯一时间维度 `dim.order_date`）；
LOOKUP 单实体 `limit=1`、LOOKUP_BATCH `limit=len(identities)`；存在性探测题默认投影属性（customer→tier / order→amount）；
场景 manual 题的 `timeRange` 补全；FUNNEL 顶层不得带 `timeRange`（时间窗只走 `analysisParams.cohortWindow`）、
ATTRIBUTION 不得带 `timeRange`（观察窗走顶层 `timeExpressionRaw`）。

## 7. 题族总表（25 族，271 题）

v2 的 20 族（§3 见[归档 v2](../../99-archive/verification/test集-v2.md)）+ v3 新增 5 族（§3 上表）。

## 8. 冻结哈希

| 文件 | sha256（前 16 位） |
|---|---|
| [`test集-v3.json`](test集-v3.json) | `f60c366d915d2ab2` |

> 完整哈希（64 位）：`f60c366d915d2ab2d0b7d8fa2a4c3d1b6b22e28710f09c82bc720a42186648fe`。
> 生成脚本对**磁盘字节**复算，避免「算内存字符串、比文件字节」的漂移。

## 9. 复审证据（v3 收敛评测，2026-11-10）

| 证据 | 结果 |
|---|---|
| 首轮全量真服务评测（[`test-runner.mjs`](../../../scripts/verify/test-runner.mjs)） | **269 pass / 0 fail / 2 skip（E-5）/ 0 error**，成功率 1.0，Wilson95 `[0.9859, 1]` |
| 分层 | `scenario_entry_mode`：manual 5/5、cold_start 5/5、inherit 5/5；`risk_type`：注入 10/10、跨轮差分 10/10、跨域 8/8、存在性 8/8 |
| 证据固化 | [`test集-M6收敛评测证据-v2.json`](../../06-verification/evaluation/test集-M6收敛评测证据-v2.json)（SHA-256 `cf386fe1e9a9ebf3…`，runId `t1794320412336`，manifest `2026.11.10-sixteenth`） |
| 场景题判据来源 | 真服务逐题探针（manual 直传三码、cold_start 四步流、VQR 冷启动命中、越界澄清），与 smoke-runner v2 探针同日同清单 |
| 收敛过程中修复的产品缺陷 | T51-1（场景确认误设 consumed → 契约拒答）、T51-2（注入审计直写原文 JSONB 失败）、T51-3（中文注入启发 10 题仅 1 题命中）——见 [M6-W5-T5.1 深度审核](../../06-verification/checklists/M6-W5-T5.1-深度审核-v1.md) |
| 取值域 | 订单仅 o-1..o-5、客户 c-1001..c-1003（探测题用不存在 id）、渠道线上/线下、等级 gold/silver/normal、区域华东/华南/华北 |

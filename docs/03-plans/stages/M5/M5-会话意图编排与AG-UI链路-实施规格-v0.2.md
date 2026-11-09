# M5 实施规格：会话、意图编排与 AG-UI 链路

| 字段 | 内容 |
|---|---|
| **状态** | 草稿（v0.2，深度 review 修订版；决策点 §8 待负责人定档后冻结 v1.0） |
| **版本** | v0.2 |
| **日期** | 2026-11-09 |
| **负责人** | 项目负责人（待确认） |
| **上游依据** | [roadmap M5](../../roadmap/Ontos开发计划-阶段性功能拆解-v1.md) · [v1.4 方案 §7](../../../02-solutions/architecture/智能问数系统-完整可落地方案-v1.4.md) · [技术选型 §7 PoC #3/#5/#10 / §11 冻结项](../../../02-solutions/architecture/智能问数系统技术选型方案-v1.1.md) · [ADR-0004](../../01-decisions/ADR-0004-首发业务域与Scenario范围.md) · [ADR-0006 G2 口径](../../01-decisions/ADR-0006-G2有条件关闭与重开口径.md) · [M4 验收报告 §5 移交前置](../../06-verification/checklists/M4-验收报告-v1.md) · [M4 W4 审核（注入检查点冻结）](../../06-verification/checklists/M4-W4-深度审核-v1.md) |
| **适用范围** | M5 阶段全部工作；本规格经负责人决策定档并冻结前**不得编码**；退出按 §6 逐条给证据 |
| **历史版本** | [v0.1](../../../99-archive/plans/M5-会话意图编排与AG-UI链路-实施规格-v0.1.md)（已归档：初稿） |

---

## 0. v0.2 深度 review 修订摘要（相对 v0.1）

对 v0.1 草稿做整体 review，发现并修订 13 项（R-A 过时基线 5 / R-B 设计缺口 6 / R-C 决策补充 2）：

| # | 类别 | 发现与修订 |
|---|---|---|
| R-A1 | 过时 | 「V7 迁移」→ **V14**（V8–V13 已被 M4 使用，下一个迁移号 V14） |
| R-A2 | 过时 | 「审批流与 HITL 归 M4，M5 只预留最小验证」→ **M4 已交付 HITL 最小通道**（`HitlNotifier` SSE + 令牌单次消费闭环 + `/hitl-events`）；M5 的增量明确为「**对话内联等待 + 继续 run**」，复用而非重建 |
| R-A3 | 过时 | 「PoC #10 部分通过 4/6 类事件」→ M4 后现状重述：前端渲染层就绪、后端 AG-UI 端点待 M5 实装并补齐六类 |
| R-A4 | 缺口 | **缺 M4 移交前置的落点**：F-5（编排缓存绑定 policy_version / 维持实时判定）、InjectionCheckpoint（M4 冻结接口的第一次真实接入）、R-30（清单 v9 文案模板）、R-27/R-29（边界声明）——补 §3.6 与 T5.4 |
| R-A5 | 缺口 | **缺 M7 并行义务**：中文提及样本集 300 条「结果待 M5 回填」——补 T2.4 与 E2 |
| R-B1 | 缺口 | **run 聚合与断线重连无落地设计**：v0.1 只说「run 幂等」——补 `ask_run` / `ask_run_event` 表 + `afterSeq` 只读重放（T3.2） |
| R-B2 | 缺口 | **敏感触发 × 编排 run 未整合**：编排执行走 `execute`，敏感判定触发返回 1003000008——编排层必须将其转为 HITL 内联（`WAITING_APPROVAL` → 决策 → 携 M4 令牌继续），否则对话主干在敏感场景直接断裂——补 §3.7 与 T4.2 |
| R-B3 | 缺口 | **run 状态机迁移未定义**：M4 收口审核 G-4 教训（EXECUTED 终态误转移）——`ask_run` 状态机显式定义合法迁移（§3.7），终态不可回退，先判定后转移 |
| R-B4 | 缺口 | **上下文篡改对抗无任务**：风险表有、任务表无——补 T5.2 对抗扩充（≥4 探针：上下文篡改 / 跨会话串号 / 模糊候选漏出 / run 重放注入） |
| R-B5 | 缺口 | **M4 G-4 教训落点**：编排 run 的状态转移「先判定后转移」写入 §3.7 硬约束 |
| R-B6 | 修正 | `ReActAgent` 表述与现状不符——统一为 `HarnessAgent`（`OntosAgentFactory` 已验证形态，M0/M1 底座） |
| R-C1 | 决策 | 新增决策 4（HITL 内联等待机制：轮询 vs 挂起）、决策 5（run 事件重放：事件表 vs 内存 ring） |
| R-C2 | 决策 | 决策 2（澄清回传形态）结论保持，但补「HITL interrupt 仅用于审批类等待，澄清走终态」的分工边界 |

---

## 1. 目标与非目标

**目标**：把自然语言接进确定性链路，且上下文权威始终在服务端。LLM 只做语义匹配与两级路由建议；候选集、路径、口径、时间、执行全部由确定性组件裁决。

**非目标（边界外，归后续里程碑）**：
- Scenario 资产化与 scope 裁剪、Skill 注册中心、VQR（M6）；M5 只实现 `free` 态与 `in_scenario` 状态机骨架（状态迁移本身落地，但没有 Scenario 资产可进入）。
- 复合意图拆分（PoC #8 归 M6）；M5 的 `composite` 终态**透传 M2 已验收的复合通道**，不做拆分编排；R-27（复合子意图变体）/ R-29（variantHint 语义）随 M6。
- 审批流本体（M4 已交付）；M5 的增量是**对话内联等待与继续 run**（§3.7），审批决策/令牌/审计全部复用 M4。
- 向量检索 / BM25 / rerank（技术选型：M5 评测证明模糊召回有收益后才引入，M5 用编辑距离 + 子串）。
- CopilotKit Headless 直连（PoC #10 已定档走退路 `@ag-ui/client` + 自研渲染；CopilotKit 列观察项随 M6 复查，不阻塞 M5）。
- 域组合判定（R-31，M4 登记）：表保留，判定逻辑不在 M5 任务表。

## 2. 现状与复用基线（v0.2 重述）

- 后端 `agentscope` starter 已引入且 `OntosAgentFactory`（**HarnessAgent** 形态）经 G2 合同测试验证；模型接入 OpenAI 三件套（`ontos.agent.model/base-url/api-key`，密钥仅环境变量；不绑定厂商）。
- **M4 交付可直接复用**：`HitlNotifier`（SSE INTERRUPT 下发）、审批令牌（指纹 + 单次消费）、`InjectionCheckpoint` 冻结接口、`audit_log.policy_version`、`QueryCacheKey.runtimePolicyVersion`（F-5 的缓存键保障已存在）。
- 确定性链路全部就绪：DSL 校验、两级路由的 Mode 路由（`metric.allowed_modes`）、口径变体澄清（1002000033）、能力注册（`skill.entity_lookup`）、文案模板、审计与 `request_state` 状态机（**终态不可回退**，M4 G-4 教训）、缓存与产物回放。
- `session_state` 表 V1 已建（`(tenant,user,session)` 主键 + `resolved_entities/query_context/scenario_state` JSONB + `turn_index` 乐观锁）——**至今零写入**，M5 启用并扩展。
- 前端 `ask` 域：`agui-panel`（PoC #10 退路渲染层，4 类事件还原）、`dsl-panel`、澄清卡片（M3）可复用；后端 AG-UI 端点待 M5 实装并补齐六类。

## 3. 设计

### 3.1 会话状态（v1.4 §7.1 权威模型；`ontos-module-session`）

```
session_state（V1 表扩展，V14 补 recent_turns JSONB / last_terminal TEXT）:
    权威字段 = resolved_entities / query_context / scenario_state / recent_turns / turn_index
ask_run:            run_id + tenant + user + session + turn + status + created_at/updated_at
ask_run_event:      run_id + seq + type + payload(JSONB) + created_at   —— 断线重连重放源
```

- 绑定 `tenant + user + session`；**跨会话清空**（新 session 一律空态，不存在「接着上次的会话」）。
- 增量合并四规则：显式新增同类条件 → 覆盖；「再加上/只看」→ 追加；「去掉/不算」→ 清除；未提及 → 沿用。
- **纠正回退**：`USER_REJECTED` → 回退澄清；被纠正轮实体作废、本轮为准（单点覆盖，可判定）。
- 每轮合并后**必须重新走完整 DSL 校验与权限编译**，不得复用上一轮编译结果；`context_delta` 进审计。
- 上一轮条件因权限/清单变化不可用时显式提示「上轮的 X 已不可用」，不静默丢弃或保留。
- 客户端请求 DTO 白名单：只允许 `(sessionId, 文本, 澄清选择)`；任何上下文字段出现在请求体一律拒绝（M4 T8.1 同思路）。
- `turn_index` 乐观锁：`UPDATE ... WHERE turn_index = :seen`，冲突返回 409 语义（并发同会话轮次）。

### 3.2 检索瀑布（v1.4 §7.5，M5 裁剪）

```
授权过滤（复用 PolicyService 快照 allowlist ∩ authorizedDomains；无快照租户瀑布不可用，fail-closed）
  → L1 精确（assetId/名称/aliases 精确命中）
  → L2 词典（aliases/summary 分词倒排，清单激活时重建内存索引）
  → L3 受限模糊（编辑距离 + 子串，仅澄清候选）
  → 候选集 + context_hash（内容 SHA-256）
```

- **模糊候选进入 DSL 次数为 0**（roadmap 硬约束）：只能出现在澄清候选里，且不得覆盖精确/词典命中。
- 候选集经 `AgentContextProvider` 注入并作为 `IntentProposalContract` 白名单 `Supplier` 数据源（钩子已有）。
- 指代消解：`resolved_entities` 优先（prev_turn/explicit/confirmed）；失效（权限/清单变化）→ 下一轮重解析或澄清。
- 提及识别质量以 M0 冻结的**中文提及样本集 300 条**回填验收（PoC #5，M7 并行义务在本阶段的落地）。

### 3.3 LLM 编排与 Proposal 来源完整性（`ontos-module-agent`）

- **HarnessAgent** 装配（工厂已有）：system prompt 注入能力边界 + 候选集 + 会话状态摘要 + 授权域 + turn_index；tools 固定 `propose_intent`（结构化候选输出）与 `execute_query`（确定性链路）。
- 输出过 `IntentProposalContract`（5 终态 + 澄清四要素 + 禁 SQL + 候选集白名单硬边界；G2 修复项 `outputValidation=true`、修复超限确定性拒答）。
- **Proposal 来源完整性**：输出每个 ID 必须能在本轮候选集找到（按 `context_hash` 对账）；候选集外 ID / 自创 `scenario_id/skill_id/analysis_mode/rule_id/variant` / 异常话术（自称管理员、要求忽略规则）→ **`InjectionCheckpoint.onSuspected(..., SOURCE_CANDIDATE_OUT_OF_SET|SOURCE_PROMPT_TEXT, ...)`**（M4 冻结接口第一次真实接入）→ 澄清或拒答 + 完整审计。
- LLM 不输出物理 Join、绝对日期、口径变体选择（规划器/时间解析器/用户澄清决定）。
- 两级路由：第一级 M5 仅 `free`（Scenario 状态机骨架落地但无资产可进入）；第二级 Analysis Mode 从 `metric.allowed_modes` 确定性过滤。
- **故障即拒答**：模型超时/异常/反复违规 → 确定性 `out_of_capability` + 审计故障类别；不无限重试。
- **M4 前置 F-5 落点**：编排过程不引入任何新的进程内结果缓存；结果复用仅经 `QueryService` 既有缓存键（已含 `runtimePolicyVersion` + userScopeHash）。

### 3.4 结构化终态（v1.4 原则四 / §7.7）

| 终态 | 触发 | 载荷 |
|---|---|---|
| 澄清 | 概念歧义 / 口径歧义（1002000033）/ 时间未命中 / 参数缺失 | **四要素**：歧义点、2–5 候选（含来源资产 ID）、默认建议、可跳过降级 |
| discovery | 无明确实体/属性意图 | 授权 scope 内可用能力清单 + Golden 样例（已发布引用，不由 LLM 生成） |
| 拒答 | 能力边界外 / 越权 / 注入嫌疑 | 原因码 + 模板文案（必须说明系统支持/不支持清单） |
| 引导 | 拒答后可挽救 | 指向 discovery 或可回答的近似问题 |
| 复用（回答） | Proposal 通过全部闸门且执行成功 | 结果 + 编译产物引用（`/plan/{requestId}` 可回放） |
| composite | 多指标复合请求 | **透传 M2 复合通道**（不拆分；PoC #8 归 M6） |

每个原因码绑定 M3 文案模板渲染；**R-30 落点**：清单 v9 补 `SENSITIVE_QUERY_SEQUENCE` 等缺失模板（T4.3）。

### 3.5 AG-UI 事件链路（`ontos-module-agent`）

- 事件模型**不自研**（技术选型 §11 第 7 项）：优先复用 starter 的 AG-UI SSE；终态载荷经 `CUSTOM` 事件下发（`name=ontos.terminal`，结构化终态 JSON）。**W3 首个探针若 starter 事件模型与 Boot 4 / 六类事件不合，回退自研最小 SSE 生成器（事件 DTO 冻结）**——由探针定档，不阻塞开工。
- 六类事件端到端：`RUN_*` / `TEXT_MESSAGE_*` / `TOOL_CALL_*` / `CUSTOM` / HITL interrupt / state；TOOL_CALL 承载**确定性工具**（`execute_query`、审批提交），工具名封闭枚举、参数服务端构造。
- **断线重连幂等**：事件持久化到 `ask_run_event`（单调 `seq`）；`GET /api/v1/ask/run/{runId}/events?afterSeq=` 只读重放——重连不重复执行任何工具调用（重放注入探针覆盖）。
- `RUN_STARTED.input` 脱敏：服务端注入侧验证用例（PoC #10 判据；前端 redact 已有）。
- STATE 只读投影：`session_state` 服务端单向下发；客户端回传的 STATE_DELTA 一律拒绝（对抗探针）。

### 3.6 M4 移交前置落点（验收核对项）

| 移交项 | M5 落点 |
|---|---|
| F-5（编排缓存绑定 policy_version） | 编排无自有缓存；结果复用仅经 `QueryService` 缓存键（已含 runtimePolicyVersion）——T5.4 核对 |
| InjectionCheckpoint（M4 冻结接口） | 候选集外 ID / 注入话术接入（§3.3）——T3.2 |
| R-30（文案模板） | 清单 v9 发布（T4.3） |
| R-27 / R-29 | 边界声明：composite 透传不含变体拆分，随 M6（§1 非目标） |
| HitlNotifier / 令牌 | HITL 内联复用（§3.7），不重建 |

### 3.7 HITL 内联与 run 状态机（M4 通道的编排增量）

```
ask_run 状态机（终态不可回退；先判定后转移——M4 G-4 教训硬约束）:
  PENDING → RUNNING → WAITING_APPROVAL → RUNNING → FINISHED
                    ↘                ↘ FAILED / EXPIRED（TTL 收尾）
  合法迁移显式枚举进代码并单测锁定（对齐 request_state 风格）
```

- 执行路径敏感触发（execute 返回 1003000008）→ run 置 `WAITING_APPROVAL` + INTERRUPT 事件（复用 `HitlNotifier`）→ 前端审批卡片 → REST 决策（M4 端点）→ **编排层继续该 run**：轮询审批状态（1s 间隔、TTL 15min 上限）→ APPROVED 后携 M4 令牌（`X-Ontos-Approval`）重放查询 → 令牌放行 → run FINISHED。
- 决策拒绝 / TTL 超时 → run 以结构化终态收尾（`SENSITIVE_QUERY_REJECTED/EXPIRED` 文案），**不悬挂**。
- `WAITING_APPROVAL` 期间 run 的会话状态不推进（turn 持有），避免审批期间上下文漂移。

## 4. 任务分解

### W1 · 会话状态机（`ontos-module-session`）

| # | 任务 | 验收 |
|---|---|---|
| T1.1 | V14 迁移：`session_state` 扩列（recent_turns/last_terminal）+ `ask_run` / `ask_run_event` | Flyway 应用；单测 |
| T1.2 | `SessionStateService`：三条件收敛、turn 递增与乐观锁、增量合并四规则、`context_delta` 进审计、请求 DTO 白名单 | 单测：合并矩阵 ≥12 + 乐观锁冲突 |
| T1.3 | 跨会话不沿用 + 纠正回退（USER_REJECTED → 回退澄清） | 单测 + 串号探针 |

### W2 · 检索瀑布与概念解析（`ontos-module-session`）

| # | 任务 | 验收 |
|---|---|---|
| T2.1 | 候选集构建：授权裁剪 → 精确 → 词典 → 受限模糊（仅澄清候选）→ `context_hash`；`AgentContextProvider` 注入 | 单测：**模糊候选进 DSL=0** |
| T2.2 | 指代消解：`resolved_entities` 优先，失效重解析/澄清 | 多轮对话用例 |
| T2.3 | 概念歧义澄清（四要素） | 单测：候选 2–5、带资产 ID、默认建议、可跳过 |
| T2.4 | 中文提及样本集 300 条回填跑批（M7 并行义务） | 证据 JSON + 命中率达标（PoC #5 口径） |

### W3 · LLM 编排器与 AG-UI（`ontos-module-agent`）

| # | 任务 | 验收 |
|---|---|---|
| T3.1 | HarnessAgent 装配 + 编排服务：Proposal → QueryDsl 服务端组装 → `execute` 全链；故障即拒答 | 单测 + 真实模型冒烟（`ontos.agent.enabled=false` 降级确定性-only 并声明） |
| T3.2 | Proposal 来源完整性 + `InjectionCheckpoint` 接入（候选集外/注入话术审计） | 对抗样例 ≥5 全拦截 + 审计 |
| T3.3 | AG-UI SSE 端点 + 六类事件 + `ask_run_event` 持久化 + `afterSeq` 只读重放 + `RUN_STARTED.input` 脱敏 | E2E：重放幂等（重复执行 0）+ 脱敏断言 |
| T3.4 | 两级路由（free 态骨架 + Mode 确定性过滤） | 单测 |

### W4 · 结构化终态与 HITL 内联（`ontos-module-agent`）

| # | 任务 | 验收 |
|---|---|---|
| T4.1 | 五类终态装配（含 composite 透传边界）+ 原因码文案渲染 | 单测：每类终态 CUSTOM 载荷契约 |
| T4.2 | HITL 内联：敏感触发 → `WAITING_APPROVAL` + INTERRUPT → 决策 → 轮询 → 携令牌继续 → FINISHED；拒绝/TTL 收尾 | E2E 全链 + run 状态机单测（迁移表锁定） |
| T4.3 | 清单 v9（R-30 模板：SENSITIVE_QUERY_SEQUENCE 等）发布 | 发布产物 + 哈希 |

### W5 · 端到端与对抗回归

| # | 任务 | 验收 |
|---|---|---|
| T5.1 | PoC #3 复跑：20 次会话 5 类终态重建 **100%** | 脚本 + 证据 JSON |
| T5.2 | 对抗扩充 ≥4 探针全拒：上下文篡改 / 跨会话串号 / 模糊候选漏出 DSL / run 重放注入 | 脚本 + 证据 |
| T5.3 | 前端 ask 对话主干：轮次、澄清卡片（复用 M3）、discovery、拒答渲染、审批卡片（M4 通道） | `pnpm check` |
| T5.4 | M4 移交项核对（F-5 / R-27 / R-29 / R-30 / InjectionCheckpoint） | 验收报告核对节 |

## 5. 测试用例

| 集合 | 规模 | 用途 |
|---|---|---|
| 会话状态 | 合并四规则 × 多轮 ≥12 + 跨会话 ≥3 + 乐观锁冲突 | T1.2/T1.3 |
| 检索 | 精确/词典/模糊/指代 ≥10 + 授权过滤 | T2.1/T2.2 |
| 提及识别 | 中文提及样本 300 条 | T2.4（PoC #5） |
| Proposal 对抗 | 候选集外 ID / 自创 mode / 注入话术 ≥5 | T3.2 |
| AG-UI | 六类事件 + afterSeq 重连 + 脱敏 ≥12 | T3.3 |
| 终态与 HITL | 五终态 + 内联闭环 ≥10 + run 状态机迁移表 | T4.1/T4.2 |
| 对抗扩充 | ≥4 新探针 | T5.2 |

## 6. 退出条件（可测）

| # | 条件 | 证据 |
|---|---|---|
| E1 | PoC #3：20 次会话 5 类结构化终态重建成功率 **100%** | 脚本 + 证据 JSON |
| E2 | 多轮指代与增量合并达标；跨会话不串号；**提及样本集 300 条回填且命中率达标**（PoC #5 口径） | 会话用例 + 证据 JSON |
| E3 | Proposal 来源完整性：候选集外 ID 注入 **0 通过**且全部走检查点审计 | 对抗 ≥5 全拦截 + 审计 |
| E4 | 模糊候选进入 DSL 次数 **0**（仅澄清候选） | 单测 + 审计抽查 |
| E5 | AG-UI 六类事件端到端 + **afterSeq 重放幂等（重复执行 0）** + `RUN_STARTED.input` 脱敏 | PoC #10 收口证据 |
| E6 | HITL 内联闭环：interrupt → 决策 → 继续 run → 令牌放行；拒绝/TTL 结构化收尾；**run 状态机迁移表单测锁定** | E2E + 单测 |
| E7 | M4 移交项核对全过（F-5 / InjectionCheckpoint / R-30 / R-27/R-29 边界）+ 基线全绿（mvn / pnpm / docs / jar 探活 / PoC #1 / PoC #7）+ OBL 核对 | verify-baseline + 验收报告核对节 |

**未全部满足 E1–E7，不进入 M6**（M7 可并行准备）。

## 7. 风险与回退

| 风险 | 回退 |
|---|---|
| 客户端自由修改上下文 | 会话状态全部服务端持有；前端只提交 `sessionId` + 文本 + 澄清选择；DTO 白名单 |
| 自研 SSE 事件模型偏离 AG-UI | 事件模型优先复用 starter；不合即自研最小 SSE 生成器（事件 DTO 冻结）——W3 探针定档 |
| LLM 输出不可靠 / 供应商不可用 | 契约校验 fail-closed + 静默修复 + 超限确定性拒答；`ontos.agent.enabled=false` 降级确定性-only（声明式返回，不假流式） |
| BETA 版 AgentScope 不稳定 | 锁定 2.1.0-BETA1；回退 2.0.4 需重新验证事件协议（PoC #3 准入闸门） |
| 会话表膨胀 | `ask_run_event` 只存事件载荷不存 SQL/结果；清理策略归 M8 |
| HITL 内联悬挂 | TTL 15min 轮询上限 + 结构化收尾；WAITING_APPROVAL 期间会话状态不推进 |
| 敏感触发打断对话体验 | INTERRUPT 事件 + 审批卡片（M4 通道）+ run 状态可查询；文案说明等待原因（模板） |

## 8. 设计决策（待负责人定档）

1. **模块划分**：建议按 roadmap 新增 `ontos-module-session`（会话状态持久化 + 检索瀑布）与
   `ontos-module-agent`（编排器、终态装配、AG-UI、HITL 内联）；备选合并单模块（少一次接线，但
   职责混居且 M6 还要加 catalog）。
2. **澄清回传形态**：建议**终态澄清**（CUSTOM 事件下发四要素，用户点选作为下一轮消息）；
   **HITL interrupt 只用于审批类等待**（§3.7），澄清不占用 interrupt 通道——分工边界固定。
3. **拼音检索**：建议 M5 不引入（首发 aliases 覆盖中文别名；受限模糊只用编辑距离 + 子串）；
   拼音登记归 M7 评测后按收益决定。
4. **HITL 内联等待机制**：建议 **DB 轮询（1s、TTL 15min 上限）+ INTERRUPT 事件辅助**——天然幂等、
   多副本一致（M4 口径）；备选 SSE 挂起等待（断线重连与幂等复杂度高，不建议）。
5. **run 事件重放实现**：建议 **`ask_run_event` 表持久化 + afterSeq 只读重放**（重连幂等可测、
   审计可回放）；备选内存 ring（重启丢事件，不满足 E5 幂等断言）。
6. **composite 终态边界**：确认 M5 **透传 M2 复合通道**（拆分归 M6 PoC #8）——与 roadmap 定位一致，
   建议直接定档不需讨论。

> 上述决策定档后：本文档升 v1.0 冻结（v0.2 归档）→ 两轮内部自审 →（可选）外部审核 → 负责人放行编码。

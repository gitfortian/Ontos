# M5 实施规格：会话、意图编排与 AG-UI 链路

| 字段 | 内容 |
|---|---|
| **状态** | 草稿（待负责人确认定稿） |
| **版本** | v0.1 |
| **日期** | 2026-11-09 |
| **负责人** | 项目负责人（待确认） |
| **上游依据** | [roadmap M5](../../roadmap/Ontos开发计划-阶段性功能拆解-v1.md) · [v1.4 方案 §7.1/7.3/7.4/7.5/7.6/7.7](../../../02-solutions/architecture/智能问数系统-完整可落地方案-v1.4.md) · [技术选型 §11 冻结项](../../../02-solutions/architecture/智能问数系统技术选型方案-v1.1.md) · [ADR-0004](../../../01-decisions/ADR-0004-首发业务域与Scenario范围.md) |
| **适用范围** | M5 阶段全部工作；编码前必须先冻结本规格，退出按 §6 逐条给证据 |

---

## 1. 目标与非目标

**目标**：把自然语言接进确定性链路，且上下文权威始终在服务端。LLM 只做语义匹配与两级路由建议；候选集、路径、口径、时间、执行全部由确定性组件裁决。

**非目标（边界外，归后续里程碑）**：
- Scenario 资产化与 scope 裁剪、Skill 注册中心、VQR（M6）；M5 只实现 `free` 态与 `in_scenario` 状态机骨架（沿用/退出/越界澄清的状态迁移本身落地，但没有 Scenario 资产可进入）。
- 审批流与 HITL 审批回传（M4）；M5 只预留 interrupt 事件通道的最小验证。
- 向量检索 / BM25 / rerank（技术选型：M5 评测证明模糊召回有收益后才引入，M5 用编辑距离 + 子串）。
- 流式 token 转发的模型侧优化（max-iters、prompt 调优随评测迭代）。

## 2. 现状与复用基线

- 后端 `agentscope` spring-boot-starter 已引入，`/agent/run/ontos` AG-UI SSE 端点已通（PoC #10 部分通过：4/6 类事件）；模型接入为 OpenAI 三件套（`ontos.agent.model/base-url/api-key`，密钥仅环境变量）。
- 前端 `service/agui`（SSE + redact 脱敏 + 六类事件中的 4 类还原）与 `dsl-panel` / `skill-panel` / 澄清卡片（M3）可直接复用。
- 确定性链路全部就绪：DSL 校验、两级路由的 Mode 路由（`metric.allowed_modes`）、口径变体澄清（`1002000033`）、能力注册（`skill.entity_lookup`）、文案模板、审计与状态机、缓存与产物回放。
- M2 登记的口径延续：校验/授权/时间解析/编译/执行**全部复用**，编排层不得复制或绕过任何闸门。

## 3. 设计

### 3.1 会话状态（v1.4 §7.1 权威模型）

```
chat_session:      session_id + tenant_id + user_id + scenario_state + turn_index + created_at/updated_at
chat_turn:         session_id + turn_index + question_text + terminal_kind + reason_code + context_delta(JSONB)
resolved_entity:   session_id + class_id + id_value + source(prev_turn|explicit|confirmed) + turn_id
session_query_context（JSONB，挂 chat_session）:
  metrics / metric_variants / dimensions / attributes / filters /
  time_window{expression_raw, resolved_range} / analysis_mode
```

- 绑定 `user_id + tenant_id + session_id`；**跨会话清空**（新 session 一律空态，不存在「接着上次的会话」）。
- 增量合并：显式新增同类条件 → 覆盖；「再加上/只看」→ 追加；「去掉/不算」→ 清除；未提及 → 沿用。
- 每轮合并后**必须重新走完整 DSL 校验与权限编译**，不得复用上一轮编译结果；`context_delta` 进审计。
- 上一轮条件因权限/scope 变化不可用时显式提示「上轮的 X 已不可用」，不静默丢弃或保留。
- 纠正回退：`USER_REJECTED` → 回退澄清；纠正样本池登记（运营闭环归 M7）。

### 3.2 检索瀑布（v1.4 §7.5，M5 裁剪）

```
授权过滤（复用 PolicyService） → [M5: free 全域] → 精确匹配（ID/名称）
  → 词典匹配（semantic aliases / summary 关键词） → 受限模糊（编辑距离 + 子串，仅澄清候选）
  → 候选集 + context_hash（内容 SHA-256）
```

- **模糊候选进入 DSL 次数为 0**：只能出现在澄清候选里，且不得覆盖精确/词典命中。
- 候选集附带 `context_hash` 注入 LLM；对账见 §3.3。
- 指代消解：`resolved_entities` 优先（prev_turn/explicit/confirmed）；失效（权限/scope 变化）→ 下一轮重解析或澄清。

### 3.3 LLM 编排与 Proposal 来源完整性（v1.4 §6.1/§7.5）

- AgentScope `ReActAgent`（starter 已装配）：system prompt 注入能力边界与候选集；tools 固定为
  `propose_intent`（结构化候选输出）与 `execute_query`（确定性链路）。
- **Proposal 来源完整性校验**：LLM 输出的每个 ID 必须能在本轮候选集中找到（按 `context_hash` 对账）；
  候选集外 ID、自创 `scenario_id/skill_id/analysis_mode/rule_id/variant`、异常模式（自称管理员、要求忽略规则）
  → `PROMPT_INJECTION_SUSPECTED` → 澄清或拒答 + 完整审计。
- LLM 不输出物理 Join、绝对日期、口径变体选择（这些由规划器 / 时间解析器 / 用户澄清决定）。
- 两级路由：第一级 M5 仅 `free`（Scenario Entry 状态机骨架落地但无资产可进入）；第二级 Analysis Mode
  从 `metric.allowed_modes` 确定性过滤。

### 3.4 结构化终态（v1.4 原则四 / §7.7）

| 终态 | 触发 | 载荷 |
|---|---|---|
| 澄清 | 概念歧义 / 口径歧义 / 时间未命中 / 参数缺失 | **四要素**：歧义点、2–5 候选（含来源资产 ID）、默认建议、可跳过降级 |
| discovery | 无明确实体/属性意图 | 授权 scope 内可用能力清单 + Golden 样例（已发布引用，不由 LLM 生成） |
| 拒答 | 能力边界外 / 越权 / 注入嫌疑 | 原因码 + 模板文案（`OUT_OF_CAPABILITY` 等必须说明系统支持/不支持清单） |
| 引导 | 拒答后可挽救 | 指向 discovery 或可回答的近似问题 |
| 复用（回答） | Proposal 通过全部闸门且执行成功 | 结果 + 编译产物引用（`/plan/{requestId}` 可回放） |

每个原因码绑定 M3 文案模板服务渲染；澄清回传形态见 §8 决策 2。

### 3.5 AG-UI 事件链路

- 事件模型**不自研**（技术选型 §11 第 7 项）：复用 starter 的 `/agent/run/{agentId}` SSE；
  终态载荷经 `CUSTOM` 事件下发（`name=ontos.terminal`，`value` 为结构化终态 JSON）。
- 六类事件端到端：`RUN_*` / `TEXT_MESSAGE_*` / `TOOL_CALL_*` / `CUSTOM` / HITL interrupt / state；
  断线重连**不重复触发执行**（run 幂等：同 runId 重放不重新执行工具调用）。
- `RUN_STARTED.input` 在前端日志/错误上报路径脱敏（PoC #10 判据；前端 redact 已有，本轮补服务端注入侧验证）。

## 4. 任务分解

### W1 · 会话状态机（`ontos-module-session`）

| # | 任务 | 验收 |
|---|---|---|
| T1.1 | V7 迁移：`chat_session` / `chat_turn` / `resolved_entity`；主键与租户隔离 | `mvn test`；Flyway 启动应用 |
| T1.2 | `SessionStateService`：开/读会话（三条件收敛）、turn_index 递增、`resolved_entities` 与 `session_query_context` 增量合并、`context_delta` 进审计 | 单测覆盖合并四规则（覆盖/追加/清除/沿用） |
| T1.3 | 跨会话不沿用 + 纠正回退（`USER_REJECTED` → 回退澄清） | 单测 + 对话用例 |

### W2 · 检索瀑布与概念解析（`ontos-module-agent`）

| # | 任务 | 验收 |
|---|---|---|
| T2.1 | 候选集构建：授权裁剪 → 精确 → 词典（aliases）→ 受限模糊（编辑距离/子串，仅澄清候选）→ `context_hash` | 单测：模糊候选进 DSL 次数 0 |
| T2.2 | 指代消解：`resolved_entities` 优先，失效重解析/澄清 | 多轮对话用例 |
| T2.3 | 概念歧义澄清（四要素） | 单测：候选 2–5、带资产 ID、默认建议、可跳过 |

### W3 · LLM 编排器与 Intent Proposal（`ontos-module-agent`）

| # | 任务 | 验收 |
|---|---|---|
| T3.1 | ReActAgent 装配：system prompt（能力边界 + 候选集）、tools（`propose_intent` / `execute_query`） | 真实模型冒烟（可关：`ontos.agent.enabled=false` 时编排器降级为确定性-only 并在响应中声明） |
| T3.2 | Proposal 来源完整性校验（`context_hash` 对账、ID 白名单、注入嫌疑检测） | 对抗样例 ≥5 全部拦截 + 审计 |
| T3.3 | 两级路由（M5：free 态 + Mode 确定性过滤） | 单测 |

### W4 · 结构化终态与 DSL 组装执行

| # | 任务 | 验收 |
|---|---|---|
| T4.1 | 五类终态装配（澄清四要素 / discovery / 拒答 / 引导 / 回答）+ 原因码文案渲染 | 单测：每类终态的 CUSTOM 载荷契约 |
| T4.2 | Proposal → QueryDsl 服务端组装（LLM 不碰物理名） | 单测 + 真实库 |
| T4.3 | `execute_query` 工具复用 `QueryService.execute`（幂等/审计/缓存不变） | E2E |

### W5 · AG-UI 端到端与前端对话主干

| # | 任务 | 验收 |
|---|---|---|
| T5.1 | 六类事件端到端 + 断线重连幂等 + `RUN_STARTED.input` 脱敏 | PoC #10 收口证据 |
| T5.2 | 前端 ask 对话主干：会话轮次、澄清卡片（复用 M3）、discovery 列表、拒答渲染、结果展示 | `pnpm check` |
| T5.3 | PoC #3：20 次会话 5 类结构化终态重建 **100%** | 脚本 + 证据 JSON |

## 5. 测试用例

| 集合 | 规模 | 用途 |
|---|---|---|
| 会话状态 | 合并四规则 × 多轮 ≥12 用例 + 跨会话 ≥3 | T1.2/T1.3 |
| 检索 | 精确/词典/模糊/指代 ≥10 用例 | T2.1/T2.2 |
| Proposal 对抗 | 候选集外 ID / 自创 mode / 注入话术 ≥5 | T3.2 |
| 终态重建 | 20 次会话 × 5 类终态 | T5.3（PoC #3） |
| AG-UI | 六类事件 + 重连 + 脱敏 | T5.1（PoC #10） |

## 6. 退出条件（可测）

| # | 条件 | 证据 |
|---|---|---|
| E1 | PoC #3：20 次会话 5 类结构化终态重建成功率 **100%** | 脚本 + 证据 JSON |
| E2 | 多轮指代与增量合并达标；跨会话不串号 | 会话状态用例全绿 |
| E3 | Proposal 来源完整性：候选集外 ID 注入 **0 通过** | 对抗样例 ≥5 全拦截 + 审计 |
| E4 | 模糊候选进入 DSL 次数 **0**（仅澄清候选） | 单测 + 审计抽查 |
| E5 | AG-UI 六类事件端到端重建 + 断线重连幂等 + `RUN_STARTED.input` 脱敏 | PoC #10 收口 |
| E6 | 基线全绿（mvn / pnpm / docs / jar 探活） | 基线自检 |

**未全部满足 E1–E6，不进入 M6**（M7 可并行准备）。

## 7. 风险与回退

| 风险 | 回退 |
|---|---|
| 客户端自由修改上下文 | 会话状态全部服务端持有；前端只提交 `sessionId` + 问题文本 + 澄清选择 |
| 自研 SSE 事件模型偏离 AG-UI | 事件模型复用 starter（技术选型 §11.7 冻结）；前端只做事件还原渲染 |
| LLM 输出不可靠 / 供应商不可用 | Proposal 校验 fail-closed；`ontos.agent.enabled=false` 降级为确定性-only（声明式返回，不假流式） |
| BETA 版 AgentScope 不稳定 | 锁定 2.1.0-BETA1（技术选型判据 3）；回退 2.0.4 需重新验证事件协议 |
| 会话表膨胀 | `chat_turn` 只存问题文本与结构化 delta，不存 SQL/结果；清理策略归 M8 |

## 8. 设计决策（待负责人确认）

1. **模块划分**：建议按 roadmap 新增两个模块——`ontos-module-session`（会话状态持久化，dal + service）与
   `ontos-module-agent`（检索瀑布、编排器、终态装配、AG-UI 装配）；备选是合并为单个模块（少一次依赖接线，
   但编排与会话持久化职责混居，M6 还要再加 catalog 模块）。
2. **澄清回传形态**：建议**终态澄清**——澄清作为一次运行的正常终态（CUSTOM 事件下发四要素），
   用户点选后作为**下一轮消息**发送；备选是 AG-UI HITL interrupt（运行中断等待输入，适合 M4 审批的
   CONFIRM 态，但断线重连与幂等复杂度高）。建议 M5 用终态澄清 + interrupt 通道最小验证（PoC #10 需要六类事件）。
3. **拼音检索**：建议 M5 **不引入**拼音依赖（首发清单 aliases 已覆盖中文别名；受限模糊只用编辑距离 + 子串），
   拼音登记 R-30 归 M7 评测后按收益决定。

> 上述三项确认后本规格升 v1.0 冻结，随后启动 W1。

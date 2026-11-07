# ADR-0004 · 首发业务域与 Scenario 范围

> **状态**：已冻结
> **版本**：v1
> **类型**：决策
> **负责人**：项目负责人（2026-11-07 确认按建议执行）
> **最后更新**：2026-11-07
> **适用范围**：M0 / W4 / T4.1–T4.2；M1 起的本体建模、Scenario 设计与会话状态
> **上游依据**：[完整落地方案 v1.4 §5.6 / §5.7](../02-solutions/architecture/智能问数系统-完整可落地方案-v1.4.md)、[技术选型 v1.1 §3 / §4.6](../02-solutions/architecture/智能问数系统技术选型方案-v1.1.md)、[M0 实施规格 §6](../03-plans/stages/M0/M0-工程基线与阶段零硬判据-实施规格-v1.md)
> **修订记录**：2026-11-07 v1 首次冻结，含首发业务域、本体样例范围与 7 项 Scenario 决策

## 背景

M1 一开始就要建本体、写 Scenario、定会话状态，这些一旦落库就很难改。当前仓库只有一份 demo 清单
（`sales_order` 单表、2 指标 3 维度、`OntologySnapshot` 为空），离 v1.4 §5.6 的首发要求有明显差距。
若不在阶段零冻结范围，M1 会按「demo 能跑就行」的假设建模，后续补关系时整条链路都要返工。

## 决策 1（T4.1）：首发业务域与本体样例范围

### 业务域：交易域（销售交易）

选它的理由不是「demo 恰好是这个」，而是三条同时成立：

1. **闭环最短**：下单 → 订单项 → 商品 → 渠道 → 事件，一条链能把「实体指代、关系路径、属性投影、指标聚合」四个职责全部走通；
2. **指标形态齐全**：既有可加总的金额（GMV）、可去重的计数（客户数），也有布尔判定（高价值客户），能覆盖聚合、去重、判定三类语义；
3. **敏感数据自然存在**：`Customer.email` 提供 PII 与存在性探测的真实样本，RLS 与 §4.8 的规则能落到真实字段上，而不是构造出来的假数据。

### 本体样例范围：v1.4 §5.6 的 7 条关系，一条不减

| 关系 | relation_id | 首发状态 |
|---|---|---|
| Customer → Order | `ontology.Customer.placesOrder` | 可查询 |
| Order → OrderItem | `ontology.Order.containsItem` | 可查询 |
| OrderItem → Product | `ontology.OrderItem.refersProduct` | 可查询 |
| Customer → Event | `ontology.Customer.performsEvent` | 阶段一发布元数据，阶段三开放 |
| Order → Event | `ontology.Order.recordsEvent` | 同上 |
| Event → Channel | `ontology.Event.attributedToChannel` | 同上 |
| Order → Channel | `ontology.Order.attributedToChannel` | 可查询 |

资产下限按 §5.6：**≥3 类、≥5 可查询关系、2 事件、≥5 指标（含 ≥1 口径变体）、≥5 维度、1 PII 属性、1 布尔判定指标**。

### 与当前 demo 清单的差距（必须显式承认）

| 项 | 现状 | 首发要求 | 差距 |
|---|---|---|---|
| 表 | `sales_order` 单表 | Customer / Order / OrderItem / Product / Event / Channel | 缺 5 张 |
| 指标 | 2（GMV、订单数） | ≥5，含口径变体与布尔判定 | 缺 3+ |
| 维度 | 3 | ≥5 | 缺 2+ |
| 关系 | 0（`OntologySnapshot` 为空） | 7 | 全缺 |
| PII | 无 | `Customer.email` | 缺 |

**结论：demo 清单只能用于编译器与链路验证，不得作为首发资产的替代品。** M1 的建模任务以本表为准，
Demo 清单在首发清单发布后退役为 PoC 夹具。

## 决策 2（T4.2）：七项范围决策

### 2.1 Scenario 启用时点

**结论：不早于编译器支持全部首发分析模式之后；在此之前 Scenario 只发布元数据，不参与路由。**

触发条件（三条同时满足才启用）：

1. `SqlCompiler` 覆盖首发需要的分析模式且编译层已 fail-closed（见 ADR-0003 第 10 条）；
2. 关系路径规划可用（跨表 Join 不再直接 `CROSS_TABLE_UNSUPPORTED`）；
3. Scenario scope 裁剪能在授权链路里生效（授权子图按 scenario 收窄）。

理由：现在启用 Scenario，它唯一能做的事是把问题路由到一条聚合查询，等于给「假能力」包了个场景外壳。

### 2.2 会话状态机

**结论：请求级状态机，7 态；会话本身只有 ACTIVE / CLOSED 两态。**

请求状态与迁移：

| 状态 | 含义 | 允许的下一状态 |
|---|---|---|
| `RECEIVED` | 已接受，尚未解析 | `CLARIFYING` / `PROPOSED` / `REJECTED` |
| `CLARIFYING` | 已发出澄清，等用户回答 | `PROPOSED` / `EXPIRED` / `REJECTED` |
| `PROPOSED` | 已产出 DSL 提案，待执行 | `APPROVAL_PENDING` / `EXECUTED` / `REJECTED` |
| `APPROVAL_PENDING` | 触发敏感策略，等审批 | `EXECUTED` / `REJECTED` / `EXPIRED` |
| `EXECUTED` | 已执行并返回结果 | 终态 |
| `REJECTED` | 拒答（含 fail-closed） | 终态 |
| `EXPIRED` | 澄清或审批超时 | 终态 |
| `FAILED` | 执行异常 | 终态 |

约束：

- 状态只由服务端推进，**事件流只是通道**，不承载权威状态（对齐技术选型 §0 第 10 条）；
- 每次迁移落 `audit_log`，带 `from` / `to` / 触发原因 / `request_id`；
- 终态不可回退；同 `request_id` 重复提交返回原状态，不重新执行（幂等）。

### 2.3 Rule 注册表

**结论：Rule 是独立资产，随发布清单版本化，不在代码里写死。**

- 存储：System DB 的 `rule` 表 + 发布时冻结进清单（与 metric / dimension 同一版本链）；
- 字段：`rule_id` / `version` / `status(draft|reviewing|published|retired)` / `scope(domain|tenant|global)` / `expression` / `reviewer` / `published_at`；
- 判定类问题只能引用 `published` 的 Rule；无匹配 → `out_of_capability=true` + `NO_RULE`（ADR-0003 已约定）；
- Rule 变更走发布流程，不允许运行时改。

### 2.4 时间解析器

**结论：服务端统一换算，规则如下。**

| 项 | 结论 |
|---|---|
| 时区 | `Asia/Shanghai`（UTC+8），不随浏览器时区变化 |
| 周起始 | 周一 |
| 相对表达 | 「上个月」「近 7 天」「本季度」等由服务端换算，LLM 不产出绝对日期 |
| 边界语义 | 左闭右开 `[from, to)`，避免跨日重复计数 |
| 「近 N 天」 | **含当天**，即 `[today-N+1, tomorrow)` |
| 解析器版本 | 随清单冻结；换算结果与版本号一并入审计，保证可回放 |

### 2.5 文案模板

**结论：对外文案唯一来源是已发布的 Copy Template，代码里不留文案常量。**

- 存储：`copy_template` 表，`template_id` / `locale` / `text` / `version` / `status`；
- 错误码与原因码只映射到 `template_id`，不直接拼字符串；
- 同一错误在任何路径下必须命中同一模板（差异信息泄露 = 0，对齐 §4.8.3）；
- 模板变更走发布流程，与清单同版本链。

### 2.6 语言范围

**结论：首发只做简体中文（`zh-CN`）。**

- 输出：只出中文；
- 输入：接受中英混输（如「看下 Q3 的 GMV」），但识别以中文为主；
- 不做多语言 UI、不做多语言文案；
- 英文资产名（`GMV`、`Order`）按原样显示，不翻译。

理由：多语言会让文案模板、同义词、评测样本三套资产同时翻倍，M0–M3 没有这个预算。

### 2.7 域组合策略

**结论：首发只支持单域（交易域）。跨域请求一律 fail-closed。**

- 请求涉及两个及以上 domain 的资产 → `out_of_capability=true` + `OUT_OF_SCOPE`；
- 不实现「域组合策略表」（技术选型里列为远期项）；
- 多域接入时另发 ADR，不在本 ADR 预留「待定」。

理由：域组合涉及跨域授权、跨域 Join 合法性、跨域指标口径冲突三件事，任何一件都不该在首发链路上顺带做。

## 依据

- v1.4 §5.6 明确首发必须覆盖**至少一条真实业务链**，不接受「空泛列类名」。
- 当前 demo 清单缺 7 条关系、3 张表、3 个指标，**不具备首发条件**，只能作 PoC 夹具。
- Scenario 若早于编译能力启用，会把「聚合查询」包装成「业务场景」，掩盖能力缺口（PoC-04 已暴露非 QUERY 模式静默出错）。

## 备选方案

| 备选 | 为何未选 |
|---|---|
| 首发选客服 / 供应链域 | 缺少现成的交易链路与敏感字段样本，要另造资产，M1 无法在两周内闭环 |
| 沿用 demo 单表作为首发本体 | 没有关系路径，「本体驱动」退化成「单表问数」，v1.4 §5.6 的四个职责里两个无法验证 |
| Scenario 立即启用（配合 demo 清单） | 只能路由到聚合查询，等于给假能力包壳；且会掩盖编译器的模式缺口 |
| 首发就做中英双语 | 文案、同义词、评测样本三套资产同时翻倍，收益与成本不匹配 |
| 首发支持跨域组合 | 跨域授权与口径冲突未定义，属于把未决问题混进首发 |

## 影响

- M1 建模任务必须先补齐 6 张表、7 条关系、≥5 指标与 ≥5 维度，**不能等业务方给全量模型**，按 §5.6 下限先落一版。
- `PolicyService` 需支持「按 domain 收窄授权子图」，为 2.1 的第 3 个触发条件做准备。
- 会话状态机落地时，`audit_log` 需要能记录状态迁移，不只是请求快照。
- 时间解析器必须带版本号并进审计，否则跨版本回放会出现口径漂移。
- 文案模板表与 Rule 表都要进发布清单，意味着清单结构要扩展（当前只有 semantic + ontology）。

## 回退方案

- **业务域**不可轻易更换：更换意味着全部资产重建。若交易域在 M1 建模时发现关键关系不可得（如拿不到 Event 数据），
  降级为「Order / OrderItem / Product / Channel 四类 + 5 条关系」，Event 相关两条关系延后，但**资产下限不减项**。
- **Scenario 启用时点**可推迟，不能提前。
- **语言范围 / 域组合**若后续要扩，发新 ADR 取代本节，不在现有 ADR 上原地改。

## 后续动作

- M1：按 §5.6 补齐首发资产；建 `rule` / `copy_template` 表并接入清单。
- M1：实现请求状态机与状态迁移审计。
- M2：实现时间解析器并带版本号。
- Scenario 启用前，逐条核对 2.1 的三个触发条件，缺一不启用。

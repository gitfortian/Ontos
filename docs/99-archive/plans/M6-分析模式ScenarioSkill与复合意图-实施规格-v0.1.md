# M6 实施规格：分析模式、Scenario / Skill 与复合意图

| 字段 | 内容 |
|---|---|
| **状态** | 草稿（v0.1，送审中；§8 八项决策经负责人定档并冻结前**不得编码**） |
| **版本** | v0.1 |
| **类型** | 计划 |
| **负责人** | 项目负责人（待确认） |
| **最后更新** | 2026-11-09 |
| **适用范围** | M6 阶段全部工作；退出按 §6 逐条给证据；**OBL-M7 全量交付物在 M6 通过验收前必须闭环**（ADR-0015） |
| **上游依据** | [roadmap M6](../../03-plans/roadmap/Ontos开发计划-阶段性功能拆解-v1.md) · [v1.4 方案 §5.7/§5.8/§7.4/§7.6/§9/§10.4/§11/§12.1](../../02-solutions/architecture/智能问数系统-完整可落地方案-v1.4.md) · [技术选型 §7 PoC #4/#8 / §4.6](../../02-solutions/architecture/智能问数系统技术选型方案-v1.1.md) · [ADR-0004（Scenario 启用三触发条件）](../../01-decisions/ADR-0004-首发业务域与Scenario范围.md) · [ADR-0005（评测门槛一字不改）](../../01-decisions/ADR-0005-评测门槛与PoC执行责任.md) · [ADR-0012（EventDef）](../../01-decisions/ADR-0012-EventDef事件判定映射扩展.md) · [ADR-0013（不可构造案例口径）](../../01-decisions/ADR-0013-Golden不可构造案例处置口径.md) · [ADR-0015（OBL-M7 触发 M6）](../../01-decisions/ADR-0015-M7最小框架验收与触发阶段重定.md) · [M2 验收报告（复合登记）](../../06-verification/checklists/M2-验收报告-v1.md) · [M5 验收报告（移交与遗留）](../../06-verification/checklists/M5-验收报告-v1.md) · [M7 最小框架验收记录（四类欠账）](../../06-verification/checklists/M7-最小框架验收记录-v1.md) |
| **修订记录** | 2026-11-09 v0.1 初稿（送审版） |

---

## 0. 起草说明

本规格为 v0.1 草稿，按 M5 同款流程推进：**草案 → 负责人 review → 决策定档（§8）→ 多轮内部自审 → 冻结放行编码**。
起草时已逐项核对 roadmap M6 硬约束、v1.4 阶段三交付物、M2/M5 全部移交登记与 OBL-M7 收敛义务；八项需定档的决策集中在 §8，
均给出建议档与备选及理由。

## 1. 目标与非目标

**目标**（roadmap M6）：在确定性内核之上扩展能力，**每个能力独立验收，不合并 SQL**：

1. 剩余 Analysis Mode 构造器按序上线：**留存（RETENTION）→ 简单归因（ATTRIBUTION）**；每个独立完成口径契约、
   DSL Schema、确定性构造器、Golden Query、边界与性能验收（同环比/漏斗 M2 已交付，本阶段回归保护）。
2. **Scenario 资产化**：资产模型、入口路由（inherit / manual / auto / cold_start / none）、scope 裁剪、
   `frozen_calibers`、冷启动地图、发布 / 版本 / 失效 / 回滚；M5 的 `in_scenario` 状态机骨架在本阶段**接入真实资产并激活**。
3. **Skill 注册中心**：注册 / 审核 / 参数 Schema / `required_capabilities` / `allowed_scenario_ids` /
   `approximation_of` / 实现版本；M3 硬编码的 `skill.entity_lookup` 迁移为注册制。
4. **复合意图全链路**：`depends_on` 结果依赖（M2 拒绝转澄清 → 本阶段全链路）、上下文继承、结果隔离、
   部分失败可解释、审计父子关系完整；**PoC #8 复跑 30/30**；R-27 / R-29 闭环。
5. **VQR 运营闭环**：生命周期（候选→草稿→校验→人工审核→发布→监控→失效/回滚）、入库条件、自动失效、
   运行时八步重校验（v1.4 §11.4）、缓存隔离；**不绕过权限与成本校验**。
6. **OBL-M7 全量收敛**（并行义务，M6 通过验收前必须闭环，ADR-0015）：test 集 ≥200 题冻结、四维分层统计、
   消融、失败看板、追踪、性能与安全压测、M7 验收报告。

**非目标（边界外）**：

- **高级召回**（图谱多跳 / 向量 + BM25 / rerank）：条件触发归 M8，且需配对评测收益证据（技术选型 §15）。
- **CopilotKit Headless 直连**：M5 已定档退路 `@ag-ui/client` + 自研渲染；M6 仅复查观察项（ADR-0010），不切换。
- **跨域组合判定实现**（R-31）：首发单域口径不变（ADR-0004 §2.7），Scenario 均为单域 `domain_id`；
  M6 做边界声明（§3.7），判定实现随多域接入 ADR，不在本阶段「按需排期」悬空。
- **DISCOVERY 不进 DSL**（建议档，§8 决策 3）：discovery 的 §9.2 契约由 M5 已交付的 discovery 终态承载。
- **拼音别名 / 多语言**：归 M7 评测后决策（M5 定档 ③ 维持）。
- **真实模型端到端冒烟**：维持 M5 登记的「上线前检查项」归属（§18），不阻塞阶段验收。

## 2. 现状与复用基线

- **模式现状**：`AnalysisMode` 8 值枚举；QUERY / YOY_MOM 编译于 `DslValidator` 链路，LOOKUP / LOOKUP_BATCH / FUNNEL
  入口分流；**ATTRIBUTION / RETENTION / DISCOVERY 一律 `ANALYSIS_MODE_NOT_IMPLEMENTED` 拒绝**（fail-closed，PoC-04 教训）。
  FUNNEL 不接受 filters（M2 偏差 2 登记「支持归 M6」）。
- **事件基础**（ADR-0012）：清单 v10 已含 `ontology.events` 2 条（`event.order_placed` / `event.refund`），
  Query DS `event` 表已建（customer_id / order_id / event_type / occurred_at / dedup_key）；**无渠道触点事件**——
  归因的数据基础是本阶段的一个决策点（§8 决策 2）。
- **本体关系**：`ontology.Order.attributedToChannel`、`ontology.Event.attributedToChannel` 已按 v1.4 §5.6
  「阶段一发布元数据、阶段三启用」就位；concept.channel 存在。
- **清单**：v10（`2026.11.09-tenth`）顶层已有 `scenarios` 段（元数据态：`scenario.sales`，`allowedAnalysisModes: ["query"]`）、
  `rules`（1）、`copyTemplates`（12）、`timeExpressionPatterns`；Git 真源在 `ontos-assets/`。
- **复合通道**（M2/W4）：`subIntents` 逐子独立编译 / 独立授权 / 结果隔离，子请求 ID `parent:subIntentId`，
  审计 `COMPOSITE_EXECUTE`；**dependsOn 非空直接拒绝转澄清**（全链路归 M6）。复合题集 v2 回填：30 题 =
  12 pass + 2 预期拒绝 + **9 结果依赖（登记 M6）** + **4 单子失败注入（M2 整单拒绝，归 M6）** + 3 DSL 表达不了（LOOKUP/BOOLEAN_RULE）。
- **M5 交付可复用**：`session_state.scenario_state`（骨架，值域 free/in_scenario）、检索瀑布三层
  （授权裁剪→L1→L2→L3，`authorizedAssetIds` 批量视图）、澄清四要素、`TerminalAssembler`、
  `InjectionCheckpoint`（Proposal 来源完整性）、ask_run / ask_run_event、HITL 内联、前端 ask 主干与澄清卡片。
- **M1/M3 交付可复用**：发布流水线与 `OntologyPublishValidator`、清单管理界面（资产工作台）、
  `CopyTemplateService`、core/skill 骨架（`SkillHandler/SkillRegistry/entity_lookup`）。
- **评测框架**（M7 最小框架）：正式评测集 v1（四集合归属 + 哈希）、评测统计口径 v1（Wilson 95%）、
  `smoke-runner.mjs`（14 pass / 0 fail / 6 skip）；**test 集 0 题、消融、看板、追踪、压测全部未启动**（§3.8 收敛）。
- **基线**：`mvn test` 395 例 / 43 类；`pnpm check` exit 0；ask-probe 29/29；PoC #3 复跑 20/20；live 基线 7 PASS。

## 3. 设计

### 3.1 剩余 Analysis Mode 构造器（`ontos-module-query`）

**上线顺序（roadmap 硬约束）**：留存 → 简单归因（同环比、漏斗已交付）。

- **口径契约先行**：新增《analysis-mode-retention-attribution-规格》（`04-specs`，v1），逐项冻结
  v1.4 §9.6 留存七项（cohort 起点事件 / 回访事件 / 自然周月或滚动周期 / 未完整周期处理 / 用户去重 / 时区日期边界 /
  分组维度取值时点）与 §9.5 归因八项（触点回溯窗口 / 首末线性公式 / 多次转化 / 触点与转化同时刻 /
  无触点转化 / 匿名用户 / 渠道合并 / 排序稳定性）。
- **首发实现口径（建议档，随 §8 决策 2 定档）**：
  - 留存：cohort = 首次 `ORDER_PLACED`（按自然周/月分组），回访 = 后续周期内再次 `ORDER_PLACED`（复购留存）；
    未完整周期**排除并标注**（不冒充完整数据）；去重 distinct customer；时区 Asia/Shanghai 左闭右开（ADR-0004 §2.4 口径）。
  - 归因：末次触点（last_touch）实现；首触 / 线性公式写入契约并声明未实现（`approximation_of` 机制的登记位）；
    触点 = 渠道触点事件（新增 EventDef，见决策 2），转化 = `ORDER_PLACED`；回溯窗口白名单枚举（如 7d/14d/30d）；
    同时刻 tie-break 用 `occurred_at + dedup_key` 稳定排序；无触点转化归入显式 `UNATTRIBUTED` 桶（不丢弃、不冒泡到其他渠道）；
    匿名（无 participant）排除。
- **DSL Schema**：`analysisParams` 扩展（沿用 M2 T3.0 的 schema 机制，白名单键 + 类型校验）；
  客户端唯一可提交相对时间表达，绝对日期与物理结构继续拒绝。
- **确定性构造器**：纯函数（输入 DSL + 已发布清单 → 规范化 SQL），Join / 物理列只来自清单映射；
  `validateMode` 将 RETENTION / ATTRIBUTION 从拒绝名单移入已实现名单，DISCOVERY 维持拒绝（决策 3）。
- **指标授权**：`metric.allowed_modes` 必须显式包含新模式；跨表（event ↔ metric 表）沿用路径规划与
  `joinCovers` 校验，不允许隐式业务规则推导。
- **funnel filters（M2 偏差 2 收口）**：建议放开**顶层 filters = 全局过滤**（所有步进 CTE 共享 WHERE，语义与 QUERY 一致）；
  步级过滤不引入（§8 决策 5）。
- **Golden 增量**：`M6-Golden-v1.json`（留存 ≥4 正例 + ≥2 负例、归因 ≥4 正例 + ≥2 负例），
  构造器生成 → 人工审阅 → 回填，逐字比对 100%（PoC #4 口径增量）；M2 27 条回归不得回退。

### 3.2 Scenario 资产化与入口路由（`ontos-module-catalog`，新模块）

- **资产模型**：按 v1.4 §5.7 全字段（entry_mode / ontology_scope / semantic_scope / frozen_calibers /
  allowed_analysis_modes / skills 绑定 / entry_hints / cold_start_samples / version_compatibility / invalidation）；
  现有清单 `scenarios` 段升级为完整模型。生命周期 `draft → schema/安全校验 → 人工审核 → publish → monitor → deprecate/rollback`，
  与 Rule 同构（ADR-0004 §2.3 模式：System DB 生命周期表 + 发布时冻结进清单）。
- **发布校验**（扩展 `OntologyPublishValidator`）：只能引用已发布 Skill/VQR；`allowed_analysis_modes` ⊆ 内置枚举；
  `frozen_calibers.caliber_key` 对应已发布资产字段；依赖资产 / 策略 / Rule / Skill 不兼容或废弃 → **阻断发布或转失效，
  不静默降级**；版本兼容判定按 §5.7（SemVer / `YYYY.MM` 前缀规则）。
- **入口路由（v1.4 §7.4 五态 + 会话状态机激活）**：
  - `inherit`：`session_scenario_state = in_scenario(id)` 沿用；问题越界 → `pending_confirm(id)` 澄清「切场景/退出」，**不临时扩 scope**；
  - `manual`：用户显式选择，无权限/已失效 → `SCENARIO_MANUAL_UNAUTHORIZED` 拒答，不静默回退自由问数；
  - `auto`：`entry_hints` 确定性匹配（建议档见 §8 决策 4）；唯一高置信 + `auto_if_confident` 自动进入，
    否则回退自由问数并提示可选入口，审计 `SCENARIO_AUTO_LOW_CONFIDENCE_FALLBACK`；
  - `cold_start`：首次会话展示授权内 Scenario 列表 + `cold_start_samples`（**必须是已发布 VQR / Golden Query 引用**）+ 自由问数入口；
  - `none`：自由问数，使用授权全域。
  - 状态迁移 `free / in_scenario(id) / pending_confirm(id)` 显式枚举 + 单测锁定（对齐 run 状态机风格，M4 G-4 教训）；
    跨会话不沿用（M5 既有口径）。
- **scope 裁剪接入点**：检索瀑布授权裁剪**之后**追加 Scenario 裁剪——候选集 = `authorizedAssetIds` ∩
  `semantic_scope` ∩ `ontology_scope`，模式候选 ∩ `allowed_analysis_modes`；**scope 只能收窄，不能扩大用户授权**；
  LLM 候选集内含 Scenario 列表（可引用 scenario_id，来源完整性校验自动覆盖「自创 scenario_id」对抗线，M5 已拦）。
- **frozen_calibers**：带类型 `{type, value}` 只读约束，进入该 Scenario 内查询的时间窗 / 口径 / 默认时间字段解析，
  冲突即拒绝（不做静默覆盖）。
- **ADR-0004 §2.1 启用闸门**：Scenario 参与路由前逐条核对三触发条件并留核对记录——
  ① 编译器覆盖首发模式且 fail-closed（W1 完成后满足）；② 关系路径规划可用（M2 T3.1 已满足）；
  ③ scope 裁剪在授权链路生效（本工作包交付物）。**三条不全不启用路由，只发布元数据**。
- **审计与缓存**：`scenario_id / scenario_version / scenario_entry_mode / scenario_entry_source /
  scenario_confirm_by_user / scenario_recommendation_confidence` 进审计（§12.1）；
  缓存键按 §10.4 扩 scenario/skill/rule/vqr 版本（§3.6）。

### 3.3 Skill 注册中心（`ontos-module-catalog`）

- **资产模型**（v1.4 §5.8）：`skill_id / version / status / analysis_mode / params_schema_ref /
  implementation_ref / required_capabilities / allowed_scenario_ids / max_batch_size / approximation_of`。
- **implementation_ref 只指向应用内审核过的构造器**：内置实现注册表（`builtin.<mode>.<impl>@<version>` 命名空间
  由注册中心统一分配，Scenario 作者不可自定义）；首发注册 = 已实现能力的注册化——
  每个已上线模式至少 1 条 Skill（query / lookup / lookup_batch / yoy_mom / funnel / retention / attribution），
  M3 `skill.entity_lookup` 迁移为注册制（行为不变，回归保护）。
- **编译期校验**（与 §8.1 联动）：`required_capabilities` 未被受控工具注册表满足 → 拒绝路由；
  `allowed_scenario_ids` 非空时当前 Scenario 必须在列（空 = 全局可用）；引用 `rule.evaluate` 的 Skill
  要求 Rule 注册中心可用且规则已发布；`max_batch_size` 必须被 DSL 批量参数遵守。
- **近似降级**：`approximation_of` 预声明的 Skill 在请求模式无承担者时，**声明近似关系并征得用户同意**后降级，
  降级后仍走完整 DSL 校验与权限编译，审计记 `MODE_APPROXIMATED` + 原请求 mode；未声明近似关系一律拒绝该模式，
  给出可选出口，**不退化到自由 SQL**。首发注册 ≥1 条样例验证机制闭环。
- **生命周期**：`draft → schema/安全校验 → 人工审核 → published → monitor → deprecated/rollback`；
  未发布 / 已废弃 / 校验失败 / 不兼容版本一律拒绝执行（fail-closed）。

### 3.4 VQR 运营闭环（`ontos-module-catalog`）

- **VQR 是参数化查询模板，不保存绕过权限的最终 SQL**；字段含 DSL 模板（引用资产 ID）、参数 Schema、
  Owner、版本、Golden/结果校验引用、数据新鲜度上限、失效条件。
- **生命周期（§11.1）**：候选 → 草稿 → 自动校验 → 人工审核 → 发布 → 监控 → 失效/回滚。
  入库七条件（§11.2）全部校验：DSL Schema 合法、依赖绑定稳定、Golden/结果校验通过、目标库实际执行成功、
  权限测试通过、结果确实回答声明的问题、Owner/版本/新鲜度/失效条件齐全。**点赞只加候选优先级，不触发发布**。
- **自动失效（§11.3）**：发布流水线在清单升级时做依赖影响分析，命中任一失效条件（依赖资产废弃、不兼容变更、
  权限/域组合策略变更、Golden 回归失败、Scope/frozen_calibers/Skill/Rule/时间解析器/文案模板不兼容变更等）→
  VQR 转失效或待复核，不静默降级。
- **命中通道**：瀑布在 L2 后追加 VQR 候选匹配（v1.4 §7.5 第 7 步）；VQR 候选注入 LLM 候选集（带 vqrId），
  Proposal 引用 vqrId（候选集外引用被 M5 完整性闸门拦截）；**命中后仍按 §11.4 八步重校验**——
  重绑全部版本、重校 Skill/Rule/口径变体、重编译当前用户权限（含字段/行/存在性/标签/跨域/累计）、
  重做成本检查、由确定性编译器重新生成参数化 SQL；**实现上 = 组装 DSL 后走既有 `execute` 全链**，
  额外记录命中 VQR/Skill/Rule 版本进审计与缓存键。越权用户命中 VQR 仍被 RLS / 策略拒绝（对抗探针覆盖）。
- **缓存隔离**：VQR 版本进缓存键（§3.6）；版本变化 / 权限变化 / 语义变更必须失效。

### 3.5 复合意图全链路（`ontos-module-query` + `ontos-module-agent`）

- **depends_on 结果依赖**（M2 拒绝 → 本阶段全链路）：
  - **拓扑排序**：JGraphT（技术选型 §4.6）对子意图依赖图定序；**环依赖 / 缺失依赖 / 自依赖 → 拒绝转澄清**
    （结构化原因码 + 文案模板），不猜测执行顺序。
  - **结果注入（建议档，§8 决策 6）**：子意图以**结构化引用**声明依赖取值（如「取 sub-1 结果第 1 行某列」），
    服务端在子查询间替换为具体参数值；替换值必须再过 `DslValidator` 参数校验与类型检查；
    **禁止字符串拼接 SQL、禁止把父查询原始结果透传给 LLM 再组 DSL**——依赖解析全部发生在编排/编译代码内。
  - **授权重校验**：`depends_on` 继承后每个子意图重新走独立授权与 scope 校验（PoC #8 判据 100%）。
- **部分失败可解释**：单个子查询失败（含被拒）不阻断其他子返回；响应逐子携带状态与原因码（文案模板渲染），
  失败子不产出部分结果（fail-closed 不破坏）。
- **审计父子完整**：`parent_request_id / sub_intent_id` 覆盖率 100%（M2 通道已有，扩展到依赖注入路径）。
- **R-27 复合子意图口径变体**：SubIntent 携带 `caliberVariantId`，校验复用 M3 闸门
  （变体条件与指标同表、缺变体 → `CALIBER_AMBIGUOUS` 候选澄清，禁止 LLM 二选一）。
- **R-29 variantHint 检索语义**：`variantHint` 参与瀑布候选**排序权重**（仅排序，不扩候选集、不绕授权裁剪）。
- **复合 lookup 子意图**（3 题 LOOKUP/BOOLEAN_RULE 型）：建议**维持拒绝 + 按 ADR-0013 同型口径登记**
  （复合子意图首发只承载聚合型；lookup 走 `skill.entity_lookup` 独立请求）——§8 决策 7。
- **PoC #8 复跑**：复合题集 v2 全量 30 题——9 结果依赖 + 4 单子失败全过、14 回归不回退、2 预期拒绝维持、
  3 题按定档处置；判据：独立编译/独立授权/结果隔离 100%（合并 SQL **0**、共享中间结果 **0**）、
  单子失败不阻断 100%、审计父子 100%、depends_on 继承后授权重校验 100%。
- **拆分质量边界声明**（M5 F-7 表述澄清）：复合题集 v2 是**确定性通道**题集（给定子意图序列验证通道性质）；
  LLM 把自然语言拆成子意图候选的**质量**不在本阶段验收，按 M7 正式评测 `turn_type=composite` 分层考核。

### 3.6 运行时接线与审计/缓存扩展

- **模块依赖方向**（同步 `ArchitectureTest`，先改规则再动代码）：
  `ontos-module-catalog → ontos-module-ontology`（复用 PublishedManifest 模型与发布校验）+ `ontos-common` + starters；
  `ontos-module-query / session / agent` **继续只依赖 ontology**——运行时消费 Scenario/Skill/VQR 一律读
  已发布清单扩展段（scenarios/skills/vqrs），**查询路径保持「锚定不可变发布清单 content_hash」硬契约**；
  catalog 只承载生命周期（draft/审核/失效）与发布流水线。
- **迁移**：V15——`scenario / skill / vqr` 生命周期表（status/owner/reviewer/version/published_at，对齐 ADR-0004 §2.3 rule 表风格）。
- **缓存键**（§10.4 对齐）：现有键（含 `runtimePolicyVersion + userScopeHash`）追加
  `scenario_id+version / skill_id+version / rule_version / vqr_id+version`；
  `session_query_context_hash / resolved_entity_hashes` 维持哈希不存明文。
- **审计扩展**（§12.1）：scenario_* 五字段、skill_id/skill_version、vqr 命中版本、`MODE_APPROXIMATED`、
  `scenario_entry_source`；`sensitive_query_counters_delta` / `context_delta` 沿用 M4/M5。
- **run 事件与线程池**（M5 移交运维面，T5.3）：`ask_run_event` 保留/归档策略（G-5，与 audit_log 同策略）；
  run 执行线程池扩容或审批等待异步化（M5 验收报告 §5 登记）。

### 3.7 历史移交项落点（核对表）

| 移交项 | 来源 | M6 落点 |
|---|---|---|
| OBL-M7 全量交付物收敛 | ADR-0015 / M7 最小框架记录 §4 | §3.8 + W5/T5.1；M6 通过验收前必须闭环（台账 done），否则基线 `FAIL` |
| R-27 复合子意图变体 | M2/M3/M5 登记 | §3.5 / T4.4 |
| R-29 variantHint 语义 | M2/M3/M5 登记 | §3.5 / T4.4 |
| funnel filters 支持 | M2 收口审核偏差 2 | §3.1 / T1.5 |
| G-5 ask_run_event 保留策略 | M5 收口审核 | §3.6 / T5.3 |
| run 线程池扩容/异步化 | M5 验收报告 §5 | §3.6 / T5.3 |
| CopilotKit 观察项复查 | ADR-0010 / M5 定档 | T5.4（复查结论留痕，不阻塞） |
| R-31 域组合判定 | M4 验收报告（M5/M6 按需排期） | T5.4 **边界声明**：首发单域不变（ADR-0004 §2.7），判定实现随多域 ADR；消除「按需排期」悬空 |
| ADR-0004 Scenario 启用三触发条件 | ADR-0004 §2.1 | §3.2 / T2.3 核对记录，缺一不启用 |
| Scenario/Skill/VQR 分层字段（评测） | M7 最小框架记录 §4 欠账 2 | 审计扩展（§3.6）+ T5.1 分层统计接线 |
| smoke-runner 拒绝类映射收紧 | M7 最小框架记录 §4 | T5.3（错误码 ↔ gold reasonCode 精确映射） |

### 3.8 OBL-M7 全量收敛（并行义务，M6 通过验收前必须闭环）

> 硬约束：`check-parallel-obligations.mjs` 规则「触发阶段（M6）通过验收而义务仍 `deferred` 即 `FAIL`」；
> 不得把 dev/validation 结果当 test 集结论、不得把主线素材算作 M7 交付物、不得事后追认。

| 交付物 | 口径 | 策略 |
|---|---|---|
| test 集扩至 ≥200 题并冻结四集合 | v1.4 §13.2 建议 200 题；ADR-0005 门槛不降 | **W1 起并行编写**（题目模板化：按 query_shape / turn_type / risk_type / scenario_entry_mode 四维铺开；Golden v2、复合 30、M3-Golden、冒烟 20 收编归属），W5 前冻结哈希 |
| 四维分层统计 + 置信区间 | 统计口径 v1 + 新增 scenario_entry_mode / scope / frozen_calibers / turn_type 字段 | 审计字段扩展（T2.6）后接线；Wilson 95% 沿用 |
| 消融 / 对照实验 | v1.4 §13.3 | M6 新能力即消融对象：无 Scenario scope / 无 VQR / 瀑布逐层裁剪的配对对照 |
| 失败看板 5 维下钻 + 纠正样本回流 | roadmap M7 | 失败样本分类落库 → 看板查询接口 → 前端 audit 域扩展；纠正样本进回归集 |
| 追踪（问题 → DSL → SQL → 结果） | 复用 AgentScope OTel / Studio + Actuator，回填 PoC #9 | 不自研埋点；trace 关联审计 request_id |
| 性能与安全压测 | P95 延迟 / 成本 / 并发 SLO（§13.5 / §19） | 本地压测脚本 + 报告；安全压测复用对抗探针全量 |
| M7 验收报告 | §13.5 / §19 口径 | 全部就绪后出具，OBL-M7 置 done + 台账证据同步 |

## 4. 任务分解

### W1 · 模式构造器（`ontos-module-query`）

| # | 任务 | 验收 |
|---|---|---|
| T1.1 | 《analysis-mode-retention-attribution-规格 v1》冻结（§9.5 八项 + §9.6 七项逐项定档） | 规格文档 + 评审留痕 |
| T1.2 | 渠道触点事件资产（决策 2 定档后）：EventDef + `event.channel_id`（queryds V3）+ 种子触点序列 + 清单 v11 | 发布产物 + 哈希 |
| T1.3 | RETENTION 构造器 + analysisParams Schema + `validateMode` 放行 + Golden 回填（M6-Golden-v1 留存段） | 逐字比对 100% + 边界样例 |
| T1.4 | ATTRIBUTION 构造器（末次触点）+ 同上 | 逐字比对 100% + 边界样例 |
| T1.5 | funnel 顶层 filters 支持（全局过滤语义）+ Golden 增量 | 单测 + 逐字比对 |
| T1.6 | DISCOVERY 边界声明（不进 DSL，终态承载）+ M2 五模式回归 | 核对节 + 回归全绿 |

### W2 · Scenario 资产化与入口路由（`ontos-module-catalog` 新模块）

| # | 任务 | 验收 |
|---|---|---|
| T2.1 | V15 迁移（scenario/skill/vqr 生命周期表）+ `ArchitectureTest` 依赖规则更新 | Flyway + ArchUnit |
| T2.2 | Scenario 资产模型 + 发布校验（§5.7 规则逐条）+ 版本/失效/回滚 + 依赖影响分析 | 校验单测 + 阻断用例 |
| T2.3 | 入口路由五态 + 状态机激活（free/in_scenario/pending_confirm 迁移表单测锁定）+ **ADR-0004 三触发条件核对记录** | 单测 + 核对记录 |
| T2.4 | scope 裁剪接入瀑布（授权后收窄）+ frozen_calibers + 越界拒绝 | 单测：越界资产/模式/维度全拒 |
| T2.5 | 冷启动地图（cold_start_samples 已发布引用校验）+ discovery 数据源扩展（Scenario 列表） | E2E：点选后可继续 |
| T2.6 | 审计字段扩展 + 缓存键扩展（§10.4 对齐表） | 单测 + 审计抽查 |

### W3 · Skill 注册中心与 VQR（`ontos-module-catalog`）

| # | 任务 | 验收 |
|---|---|---|
| T3.1 | Skill 资产模型 + 生命周期 + 内置实现注册表 + skills 段进清单 | 校验单测 |
| T3.2 | entity_lookup 迁移注册制 + 首发 Skill 注册（每已实现模式 ≥1 条）+ 编译期校验四条 | 回归不回退 + 越界拒绝 |
| T3.3 | 近似降级机制（声明 → 同意 → `MODE_APPROXIMATED` 审计）+ ≥1 样例 | 单测 + E2E |
| T3.4 | VQR 模型 + 生命周期 + 入库七条件校验 + 自动失效（依赖影响分析） | 校验单测 + 失效用例 |
| T3.5 | VQR 命中通道：瀑布候选 + Proposal 引用 + 八步重校验 + 版本进审计/缓存键 | E2E：越权命中仍拒绝 |
| T3.6 | 前端资产运营页（Scenario/Skill/VQR 列表、diff、审核、发布、失效）+ 场景入口 UI（冷启动地图/推荐确认卡片） | `pnpm check` |

### W4 · 复合意图全链路（`ontos-module-query` + `ontos-module-agent`）

| # | 任务 | 验收 |
|---|---|---|
| T4.1 | dependsOn 拓扑排序（JGraphT）+ 环/缺失/自依赖拒绝转澄清 | 单测（迁移式拒绝用例） |
| T4.2 | 结果注入：结构化引用 + 服务端替换 + 参数/类型再校验 | 单测 + 注入对抗 |
| T4.3 | 部分失败可解释 + 审计父子完整率 100% | E2E：单子失败不阻断 |
| T4.4 | R-27 子意图变体 + R-29 variantHint 排序语义 | 单测（M3 闸门复用） |
| T4.5 | PoC #8 复跑 30/30 + 跨 scope 子查询用例 | 脚本 + 证据 JSON |

### W5 · 端到端、OBL-M7 收敛与收口

| # | 任务 | 验收 |
|---|---|---|
| T5.1 | **OBL-M7 全量收敛**（§3.8：test ≥200 冻结 / 分层 / 消融 / 看板 / 追踪 PoC #9 / 压测 / M7 验收报告） | 台账 done + 证据 |
| T5.2 | 对抗扩充 ≥6 全拒：Scenario scope 越界 / VQR 越权命中 / Skill capabilities 缺失 / 未声明近似降级 / 冷启动样例注入 / dependsOn 结果注入篡改 | 脚本 + 证据 |
| T5.3 | 运维面：ask_run_event 保留策略（G-5）+ run 线程池扩容/异步化 + smoke-runner 拒绝映射收紧 | 单测/配置 + 证据 |
| T5.4 | 移交核对：CopilotKit 复查（ADR-0010）/ R-31 边界声明 / M2·M5 其余登记 | 验收报告核对节 |
| T5.5 | 基线全绿（mvn / pnpm / docs / jar 探活 / PoC #1 / PoC #7）+ E2E 汇总 + 验收报告 | verify-baseline + 报告 |

> **可并行**：OBL-M7 题目编写（T5.1）自 W1 起推进，不阻塞主线；T1.2 依赖决策 2 定档；W2/W3 可在 T1.1 冻结后并行启动。

## 5. 测试用例

| 集合 | 规模 | 用途 |
|---|---|---|
| 留存/归因 Golden | 每模式 ≥4 正例 + ≥2 负例（构造器生成→人工审阅→回填） | T1.3/T1.4（PoC #4 增量） |
| funnel filters | 全局过滤正例 + 越界负例 ≥4 | T1.5 |
| Scenario 状态机 | 迁移表全枚举 + 越界/无权限/低置信回退 ≥10 | T2.3/T2.4 |
| scope 裁剪 | 授权 ∩ scope 矩阵 + frozen_calibers 冲突 ≥8 | T2.4 |
| Skill/VQR 校验 | capabilities 缺失 / scenario 越界 / 未发布引用 / 入库七条件 / 自动失效 ≥12 | T3.1–T3.5 |
| VQR 重校验 | 越权命中 / 版本失效 / 缓存隔离 ≥6 | T3.5 |
| 复合全链 | 环/缺失依赖 + 结果注入 + 单子失败 + 跨 scope ≥12；PoC #8 复跑 30 | T4.1–T4.5 |
| 对抗扩充 | ≥6 新探针（T5.2 清单） | T5.2 |
| OBL-M7 | test ≥200 + 消融 + 压测 | T5.1 |

## 6. 退出条件（可测）

| # | 条件 | 证据 |
|---|---|---|
| E1 | RETENTION / ATTRIBUTION 构造器上线：口径契约冻结 + Golden 逐字比对 100%（M6 增量）+ M2 五模式 27 条回归不回退 + 边界样例通过 | 规格 + gold + 单测 |
| E2 | Scenario：不改编译器发布自定义 Scenario 且 scope 越界被拒 100%；入口五态路由 + 状态机迁移表单测锁定；ADR-0004 三触发条件核对记录在案；`cold_start_samples` 全部为已发布引用 | 核对记录 + E2E |
| E3 | Skill：注册/审核/发布闭环；`required_capabilities` 缺失、`allowed_scenario_ids` 越界、未发布引用全拒；近似降级仅限 `approximation_of` 预声明且经同意（`MODE_APPROXIMATED` 审计） | 单测 + 审计抽查 |
| E4 | VQR：生命周期闭环 + 命中走八步重校验（越权用户命中仍拒绝）+ 缓存隔离（版本变化失效）+ 入库七条件/自动失效生效 | E2E + 单测 |
| E5 | 复合意图（PoC #8 复跑 30/30）：结果依赖 9 题 + 单子失败 4 题全过、14 题回归；合并 SQL **0**、共享中间结果 **0**、审计父子 100%、depends_on 继承授权重校验 100%；R-27/R-29 闭环 | 脚本 + 证据 JSON |
| E6 | **OBL-M7 全量收敛**：test ≥200 题冻结（四集合哈希）、四维分层 + 置信区间、消融、失败看板、追踪（PoC #9 回填）、P95/成本/并发压测；M7 验收报告出具；台账置 `done` | 台账 + M7 验收报告 |
| E7 | 移交核对全过（G-5 / 线程池 / CopilotKit 复查 / R-31 边界 / funnel filters / ADR-0004 闸门）+ 对抗 ≥6 全拒 + 基线全绿（mvn / pnpm / docs / jar 探活 / PoC #1 / PoC #7） | verify-baseline + 核对节 |

**未全部满足 E1–E7，不进入 M7 验收与 M8**；E6 未闭环时 M6 **不得宣告通过**（ADR-0015 回退方案：只能再发 ADR 重定触发阶段）。

## 7. 风险与回退

| 风险 | 回退 |
|---|---|
| 模式膨胀导致编译器分叉、确定性退化（roadmap R6） | 构造器纯函数 + 封闭枚举 + Golden 逐字比对；模式口径契约先行，未冻结契约不开工构造器 |
| Scenario 越权扩大 scope | scope 只收窄；裁剪在授权之后叠加；越界探针进 T5.2；`entry_hints` 不参与授权/规划/组装 |
| VQR 变成绕过编译的快捷通道 | 命中即走 §11.4 八步重校验（实现 = 复用 execute 全链）；版本进缓存键与审计；越权命中探针 |
| 触点事件数据不足导致归因空转 | 决策 2 定档先行；种子触点序列随 T1.2 落地；Golden 构造在种子数据上可复现 |
| dependsOn 结果注入被利用（结果值回流污染 DSL） | 结构化引用 + 服务端替换 + 参数/类型再校验；禁止原始结果透传 LLM；注入探针 |
| OBL-M7 收敛压力（200 题编写量大） | W1 起模板化并行编写；按四集合分批冻结；进度快照逐项点名（§6.4）；不得降低门槛（ADR-0005） |
| catalog 新模块依赖方向破坏架构 | 先改 `ArchitectureTest` 规则并说明理由；运行时只读清单扩展段，不引入查询路径对 catalog 的依赖 |
| 状态机迁移遗漏（M4 G-4 / M5 W4-4 同源教训） | Scenario 状态机与部分失败语义「先判定后转移」+ 迁移表显式枚举 + 单测锁定 |
| 事件表增长与清单体积膨胀 | 触点事件只进 Query DS；清单只含资产定义；保留策略随 T5.3 |

## 8. 设计决策（待定档，八项）

| # | 决策点 | 建议档 | 备选与理由 |
|---|---|---|---|
| 1 | **模块划分** | 新增 `ontos-module-catalog`（Scenario/Skill/VQR 生命周期与发布流水线 + 前端资产运营页）；模式构造器进 `ontos-module-query`（roadmap 建议口径） | 并入 ontology：职责混居（M1 已重）；运行时查询路径保持只读清单，不受影响 |
| 2 | **归因触点数据基础** | 新增渠道触点事件资产（EventDef `CHANNEL_TOUCH` + `event.channel_id` 列 + 种子触点序列），首发实现末次触点归因；首触/线性公式进契约声明未实现 | 仅用 `orders.attributedToChannel` 渠道分组：那是维度切分不是触点归因，不满足 §9.5 契约；不新增事件则归因无数据可算 |
| 3 | **DISCOVERY 形态** | 不进 DSL（`DslValidator` 维持拒绝）；§9.2 契约由 M5 discovery 终态承载，M6 只扩展其数据源（Scenario 列表 + cold_start_samples） | 做 DSL mode：discovery 不执行查询、不进 DSL（§9.2 明确），枚举形态徒增校验负担 |
| 4 | **Scenario 推荐置信口径** | 确定性口径：`entry_hints` 精确/子串唯一命中 + `auto_if_confident` → 自动进入；多命中/无命中 → 自由问数 + 可选入口提示；LLM 可在候选集内建议 scenario_id，不引入 LLM 置信度数值（不可判定） | LLM 输出置信度：数值不可复核、不可回归，违背确定性原则 |
| 5 | **funnel filters 语义** | 放开**顶层 filters = 全局过滤**（所有步进 CTE 共享 WHERE，与 QUERY 语义一致）；步级过滤不引入 | 步级过滤：场景少、CTE 复杂度+1，后续按需 |
| 6 | **dependsOn 结果注入形态** | 结构化引用（子意图声明「取某子结果某行某列」）+ 服务端替换 + 参数/类型再校验 | 占位符字符串替换：易引入拼接风险与解析歧义 |
| 7 | **复合 lookup 子意图（3 题 LOOKUP/BOOLEAN_RULE 型）** | 维持拒绝 + 按 ADR-0013 同型口径登记（复合子意图首发只承载聚合型；lookup 走 `skill.entity_lookup` 独立请求） | 扩展 SubIntent 支持 lookup 字段：混合 lookup+聚合的编排收益低、校验面+1 |
| 8 | **OBL-M7 收敛排期** | W1 起题目模板化并行编写（不依赖新代码的部分先行），W5 前完成全部全量交付物并出 M7 验收报告 → M6 验收前闭环 | M6 验收后独立冲刺：触发阶段通过而义务未闭环即基线 `FAIL`，不可行 |

> **定档结论（待负责人确认后回填）**：八项均按建议档 / 改选档定档；定档后本规格进入多轮内部自审（结果并入本文或 ADR），自审闭环后冻结放行编码。

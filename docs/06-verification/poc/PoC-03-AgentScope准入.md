# PoC-03 · AgentScope 2.1.0-BETA1 准入

> **状态**：已冻结
> **版本**：v1
> **类型**：验证
> **负责人**：项目组
> **最后更新**：2026-11-07
> **适用范围**：M0 / W3 / T3.1，退出闸门 G2 的证据之一
> **上游依据**：[技术选型 v1.1 §7 #3](../../02-solutions/architecture/智能问数系统技术选型方案-v1.1.md)、[M0 实施规格 §6](../../03-plans/stages/M0/M0-工程基线与阶段零硬判据-实施规格-v1.md)
> **修订记录**：2026-11-07 v1 执行可自动化部分（坐标 / JDK / Boot / API 差异），终态重建待凭据；同日随 G2 有条件关闭冻结（重开规则见 M0 验收报告）

判据引用技术选型 §7 #3，本文不复制阈值。

## 1. 判据拆解与执行状态

`#3` 判据有四项，其中三项可自动化，一项必须用真实模型跑：

| 判据项 | 状态 | 证据 |
|---|---|---|
| 四个 artifact 均存在对应 BETA 坐标 | **通过** | §3.1 |
| 与 Spring Boot 4.0.x（≥4.0.3）兼容 | **通过** | §3.2 |
| 产出相对 2.0.4 的破坏性 API 差异清单 | **通过** | §4 |
| 5 类结构化终态在 20 次会话中重建成功率 100% | **失败**（协议层 100%，语义匹配 68.8%，审批类不可测） | §6 |

**因此 `#3` 整体未通过，G2 未关闭。** 前三项通过不等于准入通过。

## 2. 执行方式与边界

| 项 | 值 |
|---|---|
| 执行级别 | 探针级：静态核验 + 已编译工程的运行证据 |
| 目标版本 | `2.1.0-BETA1`（2026-10-08 预发布） |
| 对照版本 | `2.0.4`（稳定版，回退目标） |
| 运行时 | JDK 21，Spring Boot 4.0.9 |
| 脚本 | `scripts/poc/verify-agentscope-admission.mjs` |
| 原始输出 | [`AgentScope-准入证据-raw.json`](AgentScope-准入证据-raw.json) |

边界：本报告覆盖五类终态重建（§6），但不覆盖

- AG-UI 六类事件的可达性与重连幂等（属 `#10`，见 PoC-10 协议探针）；
- 生产部署行为（副本、扩缩容、长会话稳定性）。

## 3. 坐标与兼容性

### 3.1 四个 artifact 坐标

| artifact | 2.1.0-BETA1 | class 数 | 2.0.4 class 数 |
|---|---|---|---|
| `agentscope-core` | 存在 | 514 | 446 |
| `agentscope-harness` | 存在 | 429 | 393 |
| `agentscope-spring-boot-starter` | 存在 | 4 | 4 |
| `agentscope-agui-spring-boot-starter` | 存在 | 18 | 18 |
| `agentscope-extensions-agui`（AG-UI 实现，判据未点名但实际依赖） | 存在 | 107 | 107 |

### 3.2 与 Spring Boot 4.0.x / JDK 21 的兼容

- **编译目标**：四个 artifact 的 class 文件 `major version = 61`（Java 17），可在 JDK 21 运行；无 `major 65` 以下的降级风险，也不要求运行时高于 21。
- **Boot 依赖**：`agentscope-spring-boot-starter` 的 POM 直接声明
  `org.springframework.boot:spring-boot-autoconfigure:4.0.1` 与 `spring-boot-configuration-processor:4.0.1`。
  即该 BETA 版本**就是按 Boot 4 编译的**，与本工程 4.0.9 同大版本。
- **实际运行证据**：接入本工程后 `mvn package` 通过、41 个测试全绿、`java -jar` 无数据库模式启动 `health=UP`（见 §6 基线自检）。

> 这条比 POM 推断更强：Boot 4 的自动配置分包（如 Flyway 拆到 `spring-boot-flyway`）只有真跑才暴露，本工程此前已踩过。

## 4. 相对 2.0.4 的破坏性 API 差异清单

统计口径：类级用 jar 内条目全量比对；方法级对两版交集的**公开顶层类**用 `javap -public` 比对。

| artifact | 移除类 | 其中 public 顶层类 | 新增类 | public 方法减少的类 |
|---|---|---|---|---|
| `agentscope-core` | 1（匿名内部类） | 0 | 69 | 1 |
| `agentscope-harness` | 16 | 8 | 52 | 7 |
| `agentscope-spring-boot-starter` | 0 | 0 | 0 | 0 |
| `agentscope-agui-spring-boot-starter` | 0 | 0 | 0 | 0 |
| `agentscope-extensions-agui` | 0 | 0 | 0 | 0 |

### 4.1 移除的 public 顶层类（全部在 harness）

| 类 | 归属 |
|---|---|
| `harness.agent.transcript.TranscriptStore` | 会话转录存储 |
| `harness.agent.transcript.FilesystemTranscriptStore` | 同上 |
| `harness.agent.transcript.ObjectStoreTranscriptStore` | 同上 |
| `harness.agent.transcript.TranscriptRef` | 同上 |
| `harness.agent.memory.session.SessionTree` | 会话记忆树 |
| `harness.agent.memory.session.SessionEntry` | 会话记忆条目 |
| `harness.agent.memory.session.SessionTranscriptWriter` | 会话转录写入 |
| `harness.agent.middleware.TranscriptMiddleware` | 转录中间件 |

**归纳**：移除集中在 `transcript` 与 `memory.session` 两块，即「会话转录持久化 + 会话记忆树」被重构（新增 52 个类里含 `memory.compaction`、`subagent` 等替代实现）。

### 4.2 public 方法签名变化的类

| 类 | 变化方法数 |
|---|---|
| `harness.agent.middleware.MemoryMaintenanceMiddleware` | 3 |
| `harness.agent.memory.MemoryFlushManager` | 2 |
| `harness.agent.memory.MemoryConfig` | 2 |
| `harness.agent.workspace.WorkspaceConstants` | 3 |
| `harness.agent.memory.compaction.CompactionConfig` | 1 |
| `core.state.TaskContextState` | 2 |

### 4.3 对本工程的影响

本工程当前使用的 API 只有三个：`HarnessAgent.builder()`（含 `model(String)` / `sysPrompt` / `workspace` / `maxIters`）、`AguiAgentRegistryCustomizer`、`ModelRegistry`。**三者均不在移除清单，方法签名无减少**，因此这次破坏性变更不影响现有代码。

若后续用到 harness 的会话记忆/转录能力（M1 的会话状态机），必须按 BETA1 的新 API 重写，不能照 2.0.4 的文档写。

## 5. 判据表述交叉核对

判据原文要求差异清单「含已移除的 skill-activation / toolkit-binding API」。实测**不成立**：

| 版本 | skill / toolkit 顶层类数 | 差异 |
|---|---|---|
| 2.0.4 | 18 | — |
| 2.1.0-BETA1 | 18 | 移除 0、新增 0 |

两版的 `core.skill.*`、`core.tool.Toolkit`、`harness.agent.skill.*`、`harness.agent.tool.SkillManageTool` 等**完全一致**，没有 skill-activation 或 toolkit-binding API 被移除。

判断：该表述是文档撰写时的假设（可能源自更早的 1.x → 2.0 变更），不是 2.0.4 → 2.1.0-BETA1 的事实。**报告按实测写，不按判据表述写**；建议技术选型下次升版时更正这句，避免 M1 按错误假设做兼容层。

### 5.1 第二处表述不符：`streamEvents()`

判据写「经 `streamEvents()` + AG-UI 重建终态」。实测 **2.0.4 与 2.1.0-BETA1 都没有 `streamEvents()`**：

| 版本 | `StreamableAgent` 上的流式方法 |
|---|---|
| 2.0.4 | `stream(...)`，12 个重载，无 `streamEvents` |
| 2.1.0-BETA1 | 同上 |

流式入口是 `stream(Msg)` / `stream(List<Msg>, StreamOptions)`，返回 `Flux<Event>`。报告按实际的 `stream()` 执行，判据表述建议在下次升版时一并更正。

## 6. 五类结构化终态端到端重建（已执行，失败）

经真实 AG-UI 通道（`POST /agent/run/{agentId}`）跑 20 次会话，解析 `TEXT_MESSAGE_CONTENT` 拼出的 JSON。

### 6.1 五轮判据运行的数据

同一探针跑了多轮，每轮修掉一个缺陷；这组数据本身说明了「问题出在哪一层」：

| 轮次 | 本轮改动 | 与期望终态一致 | 空响应 | 单轮耗时 |
|---|---|---|---|---|
| 1 | 无（无候选集、工具未收敛、session 共享） | 56.3%（9/16） | 4 次 | 13m36s |
| 2 | 注入候选集 | 50.0%（8/16） | 7 次 | 9m07s |
| 3 | 每请求独立 session | 56.3%（9/16） | 7 次 | 2m54s |
| 4 | 会话历史改 LEGACY 模式 | **81.3%（13/16）** | 0 次 | 4m17s |
| 5 | 仅新增指标复测 | 62.5%（10/16） | 0 次 | 2m16s |
| 6 | 重写输出契约（`terminal` + 五类优先级）、样本换到清单能力内 | **68.8%（11/16）** | 0 次 | 1m28s |

第 6 轮分类：复合子结果 **4/4**、原因码 **4/4**、澄清 2/4、能力发现 1/4。

第 5 轮把两个指标拆开统计：

| 指标 | 结果 | 含义 |
|---|---|---|
| JSON 解析成功 | **100%（20/20）** | 模型每次都输出了可解析的结构化输出 |
| 产出合法终态 | **100%（20/20）** | 每次都落到了五类之一（含原因码、能力回答） |
| 与期望终态一致 | **62.5%（10/16）** | 语义匹配，随模型行为波动（4/5 轮区间 50%–81%） |

**判据按「与期望一致 ≥100%」记 FAIL**；但「协议能否承载并重建终态」这一层已经 100% 达成。

### 6.2 修掉的两个真问题

**候选集注入**：system prompt 要求只能引用候选集中的 ID，但模型看不到清单。新增 `AgentContextProvider`
（framework 定义接口）与 `ManifestCandidateContextProvider`（ontology 实现，按 `manifest_id + content_hash` 缓存），
把指标、维度、可用模式、可用维度渲染成白名单追加到提示词。效果：单次会话从 45s 降到 8s，模型开始直接输出
`metric_id: "metric.gmv"`、`dimensions: ["dim.region"]`，不再去找文件。

**会话历史污染**：`AguiAgentRegistry` 只接受 `Supplier<Agent>`，注册层拿不到 AG-UI 的 `threadId`，
所有请求共用默认 session，实测 `messages` 从 2 一路涨到 11，越跑越慢直到 token 超限返回空。
改为每请求独立 session 后，又暴露第二个问题：默认 `EVENT_LOG` 会话历史模式会做「未完成工具调用的显式对账」，
新 session 找不到记录就抛 `SessionLogException: Explicit reconciliation required for unknown tool calls`，
表现为立刻返回空内容（20 次里 7 次）。改用 `SessionHistoryMode.LEGACY` 后彻底消失。

**代价**：M0 不支持多轮上下文。M1 必须按 `threadId` 维护 agent 实例或在适配层做映射，不能依赖默认值。

### 6.3 重写输出契约后的结果

第 6 轮把 prompt 改成显式终态标签：新增 `terminal` 字段（`query` / `composite` / `clarification` / `discovery` / `out_of_capability`），
给出优先级表、`discovery` 结构、`reason_code` 取值域，并强制「一个输出只表达一种终态」。
同时把探针样本换到清单能力内——原来拿「清单没有客户维度」去考澄清、拿「清单没有客户数指标」去考复合拆分，
测的是清单而不是模型。

效果与残留问题：

| 项 | 结果 | 判断 |
|---|---|---|
| 复合子结果 | **4/4** | 样本换成清单内指标后稳定达标，此前 1/4 是样本问题 |
| 原因码 | **4/4** | 稳定达标 |
| 澄清四要素 | 2/4 | 模型倾向输出 `needs_discovery=true` + `clarification` 同时存在的混合态 |
| 能力发现 | 1/4 | 模型倾向直接用自然语言回答「有哪些指标」，而不是走 `discovery` 终态 |
| 模型显式声明 `terminal` | 5/20（25%） | **契约写了但遵循率低** |
| 终态自相矛盾 | 2 次 | 同时表达互斥终态，按 FAIL 计 |

**结论：卡点从「框架能力」转移到「模型对输出契约的遵循度」。**

prompt 已经写得很明确（字段、优先级、一致性要求、反例），但模型只有 25% 的会话照做。
这不是继续加 prompt 能解决的——**需要程序化校验**：

1. 输出的 JSON 用 Schema 校验（字段齐全、枚举合法、终态自洽）；
2. 不合规时自动追加纠错提示重试一次，仍不合规则返回明确的失败终态而不是把裸文本透给前端；
3. 校验点必须在服务端，不能依赖前端。

**为什么 M0 没做**：AG-UI 端点由 AgentScope 的 `AguiRestController` 直接提供，流式事件（`TEXT_MESSAGE_CONTENT`）
边生成边下发，服务端没有拦截点；`MiddlewareBase.onReasoning` 能在推理前后钩挂，但已经发出的 SSE 事件无法撤回。
真正落地要么自研 AG-UI 适配层（T3.5 退路方案），要么等框架提供输出校验扩展点——两者都超出 M0 范围，登记 M1。

### 6.4 已完成的接入准备

- 工程引入 `agentscope-extensions-model-openai:2.1.0-BETA1` 作为 OpenAI 协议族的模型实现；
- 配置为标准 **OpenAI 三件套**：`ontos.agent.model` + `ontos.agent.base-url` + `ontos.agent.api-key`（密钥仅环境变量），不绑定厂商；
- 凭据经 `ModelCreationContext` 显式传入，不依赖 provider 约定环境变量名；
- 真实连通已验证：`call()` 与 `stream()` 均返回正常中文，`stream` 产出 `REASONING` / `AGENT_RESULT` 事件。

## 7. 准入过程中修掉的配置缺陷

接入时发现：工程原默认值 `ONTOS_AGENT_MODEL=dashscope:qwen-plus`，但**工程从未引入 dashscope 扩展**，`ModelRegistry.canResolve("dashscope:qwen-plus")` 实测返回 `false`。

这意味着即使配好 API Key，AG-UI 调用也必然在模型解析阶段失败——原配置在任何情况下都跑不通，属未被触发的缺陷（此前只有「无数据库模式启动」覆盖，未真正调用 AG-UI）。

已改为标准 OpenAI 三件套（模型名 + 兼容端点 + 密钥），不再绑定任何厂商前缀，换供应商只需改 `base-url` 与模型名。

## 8. 结论

| 项 | 结论 |
|---|---|
| artifact 坐标 | 通过 |
| Boot 4.0.x / JDK 21 兼容 | 通过（POM 声明 4.0.1 + 工程实跑） |
| 破坏性 API 差异清单 | 通过（见 §4，含对判据表述的纠正） |
| 五类结构化终态重建 | **失败**：协议层 100%（JSON 可解析、19/20 落到合法终态），语义匹配 68.8%（11/16），且审批回传在 M0 不可测 |
| `#3` 整体 | **未通过，G2 未关闭** |

回退目标保持 `2.0.4` 不变；BETA 不得进入生产。

## 9. 证据路径

- `scripts/poc/verify-agentscope-admission.mjs` — 坐标/Boot/API 差异核验（可重复执行）
- `scripts/poc/run-poc03-terminal-states.mjs` — 五类终态重建探针
- [`PoC-03-终态重建-raw.json`](PoC-03-终态重建-raw.json) — 20 次会话的逐条判定与失败原因
- [`AgentScope-准入证据-raw.json`](AgentScope-准入证据-raw.json) — 本次运行的原始输出
- [M0 硬阻断结论 v2](M0-硬阻断结论-v2.md) — G1 汇总（与本文件不冲突，G2 独立）

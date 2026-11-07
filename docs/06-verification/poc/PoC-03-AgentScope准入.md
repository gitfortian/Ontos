# PoC-03 · AgentScope 2.1.0-BETA1 准入

> **状态**：草稿
> **版本**：v1
> **类型**：验证
> **负责人**：项目组
> **最后更新**：2026-11-07
> **适用范围**：M0 / W3 / T3.1，退出闸门 G2 的证据之一
> **上游依据**：[技术选型 v1.1 §7 #3](../../02-solutions/architecture/智能问数系统技术选型方案-v1.1.md)、[M0 实施规格 §6](../../03-plans/stages/M0/M0-工程基线与阶段零硬判据-实施规格-v1.md)
> **修订记录**：2026-11-07 v1 执行可自动化部分（坐标 / JDK / Boot / API 差异），终态重建待凭据

判据引用技术选型 §7 #3，本文不复制阈值。

## 1. 判据拆解与执行状态

`#3` 判据有四项，其中三项可自动化，一项必须用真实模型跑：

| 判据项 | 状态 | 证据 |
|---|---|---|
| 四个 artifact 均存在对应 BETA 坐标 | **通过** | §3.1 |
| 与 Spring Boot 4.0.x（≥4.0.3）兼容 | **通过** | §3.2 |
| 产出相对 2.0.4 的破坏性 API 差异清单 | **通过** | §4 |
| 5 类结构化终态在 20 次会话中重建成功率 100% | **失败（56.3%）** | §6 |

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

经真实 AG-UI 通道（`POST /agent/run/{agentId}`）跑 20 次会话，解析 `TEXT_MESSAGE_CONTENT` 拼出的 JSON，按字段判定终态。

| 终态 | 命中 | 判定依据 |
|---|---|---|
| 澄清四要素 | **3/4** | `clarification` 非空对象 |
| 能力发现 | **2/4** | `needs_discovery = true` |
| 审批回传 | **N/A** | system prompt 未定义该字段，属 M1 审批流能力；不计入分母，也不记为通过 |
| 复合子结果 | **1/4** | `sub_intents.length >= 2` |
| 原因码 | **3/4** | `out_of_capability = true` 且 `reason_code` 非空 |

**适用会话成功率 56.3%（9/16），判据要求 100% → FAIL。**

### 6.1 失败模式与根因

| 失败模式 | 次数 | 表现 | 根因 |
|---|---|---|---|
| 空响应 | 4 | HTTP 200、955–1709 ms 返回、**无任何 `TEXT_MESSAGE_CONTENT`** | 模型输出被截断（reasoning 消耗掉 token 预算）或框架提前结束，两种都会让前端拿到空回答 |
| 超时 | 3 | 180 s 未结束，被探针中止 | 模型通过 `agent_spawn` 派生 `general-purpose-subagent` 去检索候选集，子 agent 又跑一轮工具循环（见 §6.2） |
| 判定未通过 | 4 | 有 JSON 但字段不满足 | 复合意图未被拆成 ≥2 个子意图；部分澄清只给出 `ambiguity` 而候选为空 |

### 6.2 根因不是模型能力，是配置缺口

三条相互叠加：

1. **没有候选集**。system prompt 要求「只能引用候选集里出现过的 `metric_id` / `dimension_id`」，但 M0 没有把已发布清单注入 context。模型缺输入，只能退化成 `needs_discovery` 或自己去找。
2. **工具未收敛**。`HarnessAgent` 默认装配含 `agent_spawn` 的工具集，模型据此派生子 agent 检索。`disableFilesystemTools` / `disableShellTool` / `disableWebTools` 可关掉文件与命令能力，但 `agent_spawn` 一组没有公开关闭开关，`enableMetaTool(false)` 实测无效。
3. **token 预算与 reasoning 模型不匹配**。当前是带思维链的模型，reasoning 会吃掉输出预算，出现过 `finish_reason=length` 且 `content` 为空。

**结论**：该判据在 M0 不具备通过条件，需要 M1 的「候选集注入 + 工具白名单」两项能力到位后重跑，而不是靠调 prompt 或放宽判定。

### 6.3 已完成的接入准备

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
| 五类结构化终态重建 | **失败（56.3%，9/16）** |
| `#3` 整体 | **未通过，G2 未关闭** |

回退目标保持 `2.0.4` 不变；BETA 不得进入生产。

## 9. 证据路径

- `scripts/poc/verify-agentscope-admission.mjs` — 坐标/Boot/API 差异核验（可重复执行）
- `scripts/poc/run-poc03-terminal-states.mjs` — 五类终态重建探针
- [`PoC-03-终态重建-raw.json`](PoC-03-终态重建-raw.json) — 20 次会话的逐条判定与失败原因
- [`AgentScope-准入证据-raw.json`](AgentScope-准入证据-raw.json) — 本次运行的原始输出
- [M0 硬阻断结论 v2](M0-硬阻断结论-v2.md) — G1 汇总（与本文件不冲突，G2 独立）

# PoC-10 · 前端直连 AG-UI SSE（协议探针 + 界面复验）

> **状态**：草稿
> **版本**：v1
> **类型**：验证
> **负责人**：项目组
> **最后更新**：2026-11-07
> **适用范围**：M0 / W3 / T3.2 协议部分，退出闸门 G3 的证据之一
> **上游依据**：[技术选型 v1.1 §7 #10](../../02-solutions/architecture/智能问数系统技术选型方案-v1.1.md)、[附录 A](../../02-solutions/architecture/智能问数系统技术选型方案-v1.1.md)
> **修订记录**：2026-11-07 v1 首次执行，真实模型 + 真实 HTTP 通道；同日补界面复验（T3.5）

判据引用技术选型 §7 #10，本文不复制阈值。

## 1. 执行范围与状态

`#10` 分两段，本报告只覆盖协议段（T3.2），界面段（T3.5）另计：

| 判据项 | 本报告 | 状态 |
|---|---|---|
| 六类事件可达性 | 协议段 | **部分可达（4/6）**，见 §3 |
| 断线重连幂等 | 协议段 | **通过（同 runId 重复提交）**，见 §4 |
| `RUN_STARTED.input` 脱敏 | 协议段 + 前端 | **明文回显已实测 → 前端脱敏已实现并验证**，见 §5 |
| 六类事件在 shadcn 界面重建（≥20 会话） | 界面段（T3.5） | **4/6 类已重建**，见 §7 |
| 零额外服务端进程 | 架构约束 | 通过：单 jar 直接提供 SSE，无 Node runtime |

**结论：T3.2 部分通过、T3.5 部分通过，G3 未关闭。**

## 2. 执行方式

| 项 | 值 |
|---|---|
| 运行时 | JDK 21 / Spring Boot 4.0.9 / 单 jar（`ontos-server`） |
| 模型 | OpenAI 协议兼容端点（`ONTOS_AGENT_BASE_URL` + `ONTOS_AGENT_MODEL`），真实调用 |
| 端点 | `POST /agent/run/{agentId}`（SSE） |
| 探针 | `scripts/poc/run-poc10-protocol.mjs` |
| 规模 | 20 次会话 + 3 次幂等重放 |
| 原始输出 | [`PoC-10-协议探针-raw.json`](PoC-10-协议探针-raw.json) |

## 3. 六类事件可达性

20 次会话中 19 次正常完成（1 次 120s 超时中止）。

| 事件族 | 命中会话 | 结论 | 说明 |
|---|---|---|---|
| `RUN_STARTED` / `RUN_FINISHED` | **19/19** | 可达 | `RUN_STARTED.input` 回显完整请求体（协议行为），前端已脱敏，见 §5 |
| `TEXT_MESSAGE_START/CONTENT/END` | **19/19** | 可达 | `CONTENT` 带 `delta` 增量，实测单会话最多 76 个分片 |
| `TOOL_CALL_START/ARGS/END` | **14/19** | 可达 | 模型自发调用框架内置工具（见 §6） |
| `CUSTOM` | **19/19** | 可达 | 观测到 `context_build`、`action_observation` 两种 name |
| HITL interrupt / 审批回传 | 0/19 | **NOT_TRIGGERED** | 需审批流，属 M1 能力 |
| `STATE_SNAPSHOT` / `STATE_DELTA` | 0/19 | **NOT_TRIGGERED** | 需 agent 共享状态，M0 未启用 |

框架侧 `EventType` 只有 6 个值（`REASONING` / `TOOL_RESULT` / `HINT` / `AGENT_RESULT` / `SUMMARY` / `ALL`），AG-UI 的事件类型由适配层映射产生，**不是框架原生**。这解释了为什么 HITL 与 STATE 需要额外能力才会出现。

## 4. 幂等

同一 `threadId` + `runId` 连续提交 3 次：

| 次 | 事件数 | 文本长度 | 耗时 |
|---|---|---|---|
| 1 | 62 | 304 | 14718 ms |
| 2 | 15 | 304 | 9365 ms |
| 3 | 16 | 304 | 8615 ms |

**判定：通过。** 三次返回文本长度一致（304），未重复触发查询。注意这验证的是「同 runId 重放」，不是物理断线后的重连——真实断线重连需在 T3.5 界面复验中模拟网络中断，本报告不含该场景。

## 5. `RUN_STARTED.input` 脱敏

**服务端行为（实测）**：19/19 次会话的 `RUN_STARTED.input` 明文包含用户原始问题：

```json
{"type":"RUN_STARTED","input":{"messages":[{"role":"user","content":"你好，请用一句话说明你能做什么", ...}]}}
```

这是 AG-UI 协议的正常行为（回显输入以便前端恢复会话），但意味着若前端把它写入浏览器日志、错误上报或埋点，等同于把用户问题全文外发。

**前端实现（2026-11-07 补齐）**：`service/agui/redact.ts` 的 `redactAguiEvent` 在事件进入 UI 前替换 `RUN_STARTED.input`——只保留 `threadId` / `runId` / `messageCount` / `lastMessageLength`（长度足够判断「是否空消息」），**不含任何消息内容**；其余事件原样透传，不碰流式内容。`streamAgui` 已接入；单测 5 条覆盖明文替换、空消息、非 `RUN_STARTED` 透传、长度保留、入参对象不被修改。

**浏览器实测**：向 `/ask` 发送含独特标记串的问题，会话完成后展开「原始事件流」全文搜索——`RUN_STARTED` 正常显示、标记串 **0 次出现**、回答正常渲染（370 字符）。**判据「在前端日志 / 错误上报路径中脱敏验证」由「未执行」改为「通过」。**

## 6. 附带发现（非本探针判据，但影响后续）

### 6.1 AG-UI 端点路径前后端不一致（已修）

前端 `service/agui/index.ts` 原写死 `/agent/ontos`，服务端实际路由是
`{agentscope.agui.path-prefix}/run/{agentId}` → `/agent/run/ontos`。

后果是前端调用 AG-UI 必然 404，而前端把 404 表现为「服务器发生错误」，看起来像后端没启动。已修正为 `/agent/run/ontos` 并加注释说明先 curl 验证。

### 6.2 问数 agent 持有宿主机工具（部分修复）

首次实测时，`HarnessAgent` 默认装配了 20+ 个工具，含 `execute`（命令执行）、`write_file` / `edit_file`（文件写入）、`web_fetch`、`agent_spawn`。模型在 14/19 次会话中**自发调用**了 `glob_files` / `list_files` / `grep_files` / `read_file` 去找候选集，还通过 `agent_spawn` 派生了 `general-purpose-subagent`，单次会话拖到 20 分钟以上。

已尝试并实测的收敛手段：

| 手段 | 结果 |
|---|---|
| `disableFilesystemTools()` / `disableShellTool()` / `disableWebTools()` / `disableDynamicSkills()` / `disableDefaultWorkspaceSkills()` | **有效**：`execute` / `write_file` / `read_file` / `glob_files` / `web_fetch` 等不再注册 |
| `enableMetaTool(false)` | **无效**：`agent_spawn` / `agent_send` / `agent_list` 仍注册（但本次未再被调用） |
| `toolkit(new Toolkit())` | **反向效果**：框架改为装配全套默认工具（22 个，含 execute/web_fetch），事件粒度被打散成 `THINKING_BLOCK_*` / `MODEL_CALL_*`，会话跑满超时。已回退 |

**当前状态**：文件系统、命令执行、外网访问、技能机制已关闭；`memory_*` / `session_*` / `task_*` / `agent_*` 共 13 个仍注册，无公开开关。

**M1 待办**：用 `PermissionContextState` 的 deny 规则按工具名收敛（`addDenyRule(toolName, new PermissionRule(...))`），或等框架提供工具白名单。在此之前不得把该 agent 暴露到非受信网络。

### 6.3 单次会话耗时波动大

19 次成功会话平均 45.8s，最长 82s，另有 1 次 120s 超时。原因包括模型 reasoning 与工具往返。这决定了两件事：前端必须有超时与取消；`agentscope.agui.run-timeout`（当前 5 分钟）要按真实分布复核。

## 7. 界面复验（T3.5）

在归位后的 shadcn/ui 组件层上，用真实浏览器（Chromium）+ 真实模型 + 真实 SSE 跑 20 次会话，
五类终态各 4 次。

### 7.1 结果

| 项 | 结果 |
|---|---|
| 会话完成 | **20/20**（UI 状态徽章均显示「已完成」） |
| JSON 可解析 | **20/20** |
| 模型声明 `terminal` | **20/20** |
| 终态分布 | query 4 / composite 4 / clarification 4 / discovery 4 / out_of_capability 4，**与预期逐条一致** |
| HTTP 错误 | 0 |

界面重建的事件类别：

| 事件族 | 界面表现 | 观测 |
|---|---|---|
| `RUN_*` | 卡片右上状态徽章（待发送 / 运行中 / 已完成 / 出错） | 20/20 |
| `TEXT_MESSAGE_*` | 回答区增量渲染 + 原始事件流 | 20/20 |
| `TOOL_CALL_*` | 工具调用区（名称 / 参数 / 结果） | 2/20（工具已收敛，仅少数会话触发内置工具） |
| `CUSTOM` | 自定义事件区（`context_build` 等） | 20/20 |
| `STATE_*` | 已实现状态区渲染 | **0/20**（M0 未启用 agent 共享状态） |
| HITL / 审批 | 未实现 | 0/20（M1 审批流） |

### 7.2 与协议探针（§3）的差异及解释

同一套 prompt，协议探针那批是 68.8%、`terminal` 声明率 25%；界面这批是 20/20、声明率 100%。

逐条核对过原始数据，**两侧都没有判错**：

| 场景 | 协议探针 | 界面复验 |
|---|---|---|
| clarification 2 | 模型未声明 `terminal`，字段推断为 `query` → FAIL | 声明 `clarification` → PASS |
| clarification 4 | 未声明，且同时含 `needs_discovery` 与 `clarification` → 冲突 FAIL | 声明 `clarification` → PASS |

结论：**不是判定口径问题，是模型输出在同一 prompt 下的批次间波动**。
这也说明单批 20 次不足以判定稳定性——`#3` 报告里「需要程序化校验 + 重试」的判断因此更成立。

### 7.3 顺带修掉的联调缺陷

后端 `cors.allowed-origins` 只配了 `http://localhost:5173`，而浏览器地址栏是 `127.0.0.1:5173`——
CORS 里这是两个不同的源，AG-UI 请求直接被 403。前端把 403 显示成「AG-UI 请求失败」，
看上去像后端没启动。已补上 `http://127.0.0.1:5173`。

### 7.4 主路线 / 退路结论

- **纯浏览器直连 Java AG-UI SSE 成立**：单 jar 直接提供 SSE，零 Node runtime，20 次会话实测通过。
- **CopilotKit Headless 已验证并给出结论**（[T5.5 报告](T5.5-CopilotKit接入验证-v1.md)）：
  1.85 可经 `agents__unsafe_dev_only` 注入 `HttpAgent` 零 Node runtime 直连成功（`/api/copilotkit` 请求 0 次），
  但该入口字段名即标注 `unsafe_dev_only`，无稳定 API 承诺——**主路线不切换，走退路**，
  CopilotKit 列观察项待 M1 复查。退路（`@ag-ui/client` + 自研 shadcn 渲染）已在本次复验中实证。
- **退路可用**：`@ag-ui/client` + 自研 shadcn 渲染层已在本次复验中实证（当前实现即是该形态）。

## 8. 流式时序验证（补充判据）

### 8.1 为什么补这一项

`#10` 原判据只要求「六类事件在界面完整重建」，**没有约束相邻 `TEXT_MESSAGE_CONTENT` 的到达与渲染节奏**。
这留下一个盲区：后端逐条 SSE、前端却一次性刷出全部文本（常见于代理缓冲、`response.text()`
一次性读、或前端做了批量节流），事件数量与内容都对得上，判据仍会通过，但用户看到的是「压缩批量返回」，
没有打字机效果。

本次按实测补上这一项，分两层测量：**网络层**（delta 到达间隔）与**渲染层**（DOM 文本长度变化间隔）。

### 8.2 网络层

同一请求（`POST /agent/run/ontos`，经 vite 代理），在页面内用 `fetch` + `ReadableStream` 逐帧记录时间戳：

| 项 | 结果 |
|---|---|
| 网络 chunk | 34 个 |
| `TEXT_MESSAGE_CONTENT` 事件 | 29 个，共 385 字符 |
| delta 到达间隔 | min 18 ms / **p50 70 ms** / p90 81 ms / max 209 ms |
| **同毫秒批量**（间隔 ≤1 ms 视为一批） | **最大 1 个/批，0 批** |

结论：delta 是逐条到达的，不存在后端攒批或代理缓冲。

### 8.3 渲染层（真实组件路径）

点击「发送」走真实组件（`agui-panel` → `streamAgui` → `setEvents` → `pre`），
用 16 ms 定时器采样 `pre` 文本长度：

| 项 | 结果 |
|---|---|
| `pre` 内容更新次数 | **29 次**（与 delta 数量一致） |
| 每次增量 | min 5 / **p50 14** / p90 21 / max 24 字符 |
| 更新间隔 | min 46 ms / **p50 49 ms** / p90 66 ms / max 143 ms |
| **大跳变**（单次 >40 字符） | **0 次** |

结论：**打字机效果成立**——每约 50 ms 追加约 14 个字符，没有批量跳变。

前 8 次采样（`t` 为相对发送时刻的毫秒）：

```json
[{"t":7923,"len":2},{"t":7969,"len":15},{"t":8034,"len":20},{"t":8081,"len":33},
 {"t":8224,"len":57},{"t":8273,"len":74},{"t":8322,"len":80},{"t":8385,"len":97}]
```

### 8.4 这条链路为什么能流式（四层都不能断）

| 层 | 当前实现 | 断掉会怎样 |
|---|---|---|
| 模型 → 后端 | OpenAI 协议 `stream=true`，逐 delta 返回 | 一次性返回完整 JSON，前端只能等 |
| 后端 → HTTP | AgentScope 的 `AguiRestController` 用 `SseEmitter` 逐事件下发 | 若改用普通 `ResponseBody` 聚合返回，前面全白做 |
| HTTP → 浏览器 | vite 代理 `/agent` 透传，未开压缩；**生产 nginx 必须关 `proxy_buffering`** | nginx 默认 `proxy_buffering on`，会把 SSE 攒成几批 |
| 浏览器 → DOM | `reader.read()` 每 chunk 立即 `onEvent`，`setEvents` 直接追加，**无节流** | 加 `debounce`/`useDeferredValue`/虚拟列表窗口化，会把 29 次渲染压成几次 |

### 8.5 若后续出现「批量返回」，按此顺序排查

1. **先看网络层**：`curl -N` 直连后端，若 chunk 间隔为 0 且一次到齐 → 后端没 flush（不是前端问题）。
2. **再看代理**：直连后端正常、经代理异常 → 查 `proxy_buffering`（nginx）/ `compress`（中间件）。
3. **再看前端**：网络层正常但 DOM 只更新几次 → 查是否用了 `response.text()`、是否给了节流/批处理。
4. **最后看 React**：即便每 50 ms 一次 `setState`，React 18 的自动批处理也只合并同一宏任务内的更新，
   不会跨 50 ms 合并——所以「React 批处理导致批量」这个常见猜测在实测中不成立。

## 9. 结论

| 项 | 结论 |
|---|---|
| 纯浏览器直连 SSE、无额外服务端进程 | 通过 |
| 六类事件可达性（T3.2） | `RUN_*` / `TEXT_MESSAGE_*` / `TOOL_CALL_*` / `CUSTOM` 四类实测可达；`HITL` / `STATE` 未触发——属 M1 能力（审批流、状态共享），按 ADR-0005「环境不具备」处理，不记缺陷 |
| 断线重连幂等 | 同 runId 重放通过；真实断线场景未测 |
| `RUN_STARTED.input` 脱敏 | **通过**：服务端明文回显属实，前端在事件进入 UI 前已替换（§5） |
| T3.2 整体 | **部分通过（探针级）**：四类可达 + 幂等 + 脱敏；`HITL`/`STATE` 待 M1 |
| T3.5 界面重建 | **部分通过（界面级）**：4/6 类事件完整重建，五类终态 20/20 逐条一致 |
| 流式时序（补充判据） | **通过**：delta 无批量、渲染 29 次无跳变，打字机效果成立 |
| CopilotKit 主路线 | **已结论：走退路**（T5.5）；退路已实证 |
| **G3** | **关闭**。规格 G3 四项条件逐条对账：T3.2 完成、T3.5 完成、主路线/退路结论已给出、脱敏约束生效。「部分通过」指事件类别覆盖 4/6（差集为 M1 能力），不影响 G3 关闭判定 |

## 10. 证据路径

- `scripts/poc/run-poc10-protocol.mjs` — 协议探针（可重复执行）
- [`PoC-10-协议探针-raw.json`](PoC-10-协议探针-raw.json) — 20 次会话与幂等重放的原始事件统计
- `ontos-ui/apps/web/service/agui/index.ts` — 端点路径修正处
- `ontos-ui/apps/web/app/ask/agui-panel.tsx` — 六类事件渲染实现（状态徽章 / 工具调用 / 自定义 / 状态 / 原始流）
- [`PoC-10-界面复验-raw.json`](PoC-10-界面复验-raw.json) — 20 次界面会话的逐条结果
- [`PoC-10-流式时序-raw.json`](PoC-10-流式时序-raw.json) — 网络层 chunk 与 delta 的逐帧时间戳
- [`PoC-10-流式渲染-raw.json`](PoC-10-流式渲染-raw.json) — 渲染层 DOM 文本长度的变化节奏

# PoC-10 · 前端直连 AG-UI SSE 协议探针

> **状态**：草稿
> **版本**：v1
> **类型**：验证
> **负责人**：项目组
> **最后更新**：2026-11-07
> **适用范围**：M0 / W3 / T3.2 协议部分，退出闸门 G3 的证据之一
> **上游依据**：[技术选型 v1.1 §7 #10](../../02-solutions/architecture/智能问数系统技术选型方案-v1.1.md)、[附录 A](../../02-solutions/architecture/智能问数系统技术选型方案-v1.1.md)
> **修订记录**：2026-11-07 v1 首次执行，真实模型 + 真实 HTTP 通道

判据引用技术选型 §7 #10，本文不复制阈值。

## 1. 执行范围与状态

`#10` 分两段，本报告只覆盖协议段（T3.2），界面段（T3.5）另计：

| 判据项 | 本报告 | 状态 |
|---|---|---|
| 六类事件可达性 | 协议段 | **部分可达（4/6）**，见 §3 |
| 断线重连幂等 | 协议段 | **通过（同 runId 重复提交）**，见 §4 |
| `RUN_STARTED.input` 脱敏 | 协议段 | **发现明文回显，待前端处理**，见 §5 |
| 六类事件在 shadcn 界面重建（≥20 会话） | 界面段（T3.5） | 未执行 |
| 零额外服务端进程 | 架构约束 | 通过：单 jar 直接提供 SSE，无 Node runtime |

**结论：T3.2 部分通过，G3 未关闭。**

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
| `RUN_STARTED` / `RUN_FINISHED` | **19/19** | 可达 | `RUN_STARTED.input` 回显完整请求体，见 §5 |
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

19/19 次会话的 `RUN_STARTED.input` **明文包含用户原始问题**：

```json
{"type":"RUN_STARTED","input":{"messages":[{"role":"user","content":"你好，请用一句话说明你能做什么", ...}]}}
```

这是 AG-UI 协议的正常行为（回显输入以便前端恢复会话），但意味着：

- 若前端把它写入浏览器日志、错误上报或埋点，等同于把用户问题全文外发；
- 判据要求「在**前端日志 / 错误上报路径**中脱敏验证」——这一半属前端，**未执行**。

**处理建议**：前端 `service/agui` 层在把事件交给 UI 前，对 `RUN_STARTED.input.messages[].content` 做截断或摘要化，并禁止把原始事件对象直接送进错误上报。M0 记为「待前端实现 + T3.5 复验」。

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

## 7. 结论

| 项 | 结论 |
|---|---|
| 纯浏览器直连 SSE、无额外服务端进程 | 通过 |
| 六类事件可达性 | `RUN_*` / `TEXT_MESSAGE_*` / `TOOL_CALL_*` / `CUSTOM` 四类实测可达；`HITL` / `STATE` 需 M1 能力，未触发 |
| 断线重连幂等 | 同 runId 重放通过；真实断线场景未测 |
| `RUN_STARTED.input` 脱敏 | 服务端明文回显，前端脱敏未实现 |
| T3.2 整体 | **部分通过** |
| G3 | **未关闭**（还需 T3.5 界面复验） |

## 8. 证据路径

- `scripts/poc/run-poc10-protocol.mjs` — 协议探针（可重复执行）
- [`PoC-10-协议探针-raw.json`](PoC-10-协议探针-raw.json) — 20 次会话与幂等重放的原始事件统计
- `ontos-ui/apps/web/service/agui/index.ts` — 端点路径修正处

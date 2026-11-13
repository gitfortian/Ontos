# ADR-0027 · AG-UI 溯源网关与 CopilotKit 主路线恢复

> **状态**：已冻结
> **版本**：v1
> **类型**：决策
> **负责人**：项目负责人（2026-11-13 批复 W1–W4 重构方案，5 个决策点全部同意）
> **最后更新**：2026-11-13
> **适用范围**：`/agent/ui/ontos` 溯源网关端点；`ontos-ui` 问数工作台的事件接入层；技术选型附录 A「对话与结构化交互」行的生效解释
> **上游依据**：[技术选型 v1.1 §7 #10 / 附录 A](../02-solutions/architecture/智能问数系统技术选型方案-v1.1.md)、[ADR-0010 CopilotKit 复查与主路线评估](ADR-0010-CopilotKit复查与主路线评估.md)、[M5 规格 §3.5 AG-UI 事件链路](../03-plans/stages/M5/M5-会话意图编排与AG-UI链路-实施规格-v1.2.md)
> **修订记录**：2026-11-13 v1 首次冻结：网关只做协议形状转换；CopilotKit Headless（selfManagedAgents）恢复为问数工作台主路线。

## 背景

1. 附录 A 冻结的主路线是 CopilotKit Headless；ADR-0010 因 `agents__unsafe_dev_only` 非生产入口而走退路（`@ag-ui/client` + 自研渲染），CopilotKit 列观察项。
2. 本次重构确认两条链路的分工：`/agent/run/ontos`（AgentScope starter）按设计不执行查询（F-5 P0：不暴露 execute_query 工具），**无法承载**过程事件与查询终态；编排层 `/api/v1/ask/runs/stream` 有完整事件（步骤/工具/终态/审计），但事件形状是内嵌 JSON（`{seq, type, payload}`），CopilotKit 无法直连。
3. 实证：`@copilotkit/react-core@1.85.0` 的 v2 入口提供 `CopilotKitProviderProps.selfManagedAgents?: Record<string, AbstractAgent>`（文档化字段，非 `unsafe_dev_only`），`useAgent` 返回可订阅的 `AbstractAgent`——正是 ADR-0010 spike 要验证的正式入口。

## 决策

### D1 · 新增 `/agent/ui/ontos` 溯源网关

- `AguiGatewayController`（agent 模块）：接收标准 AG-UI `RunAgentInput`，映射为 `{utterance, clarificationChoice, variantHint}`（`forwardedProps` 透传），复用 `executeTurnStreaming` 同一编排；把内嵌事件**摊平**为标准 AG-UI 扁平事件下发。
- **职责边界（批复原文）**：网关只做协议形状转换，不做业务逻辑——不裁剪事件、不改语义、不注入内容；编排、闸门、审计、身份全部与 `/api/v1/ask/**` 同源。
- 摊平规则：事件类型名不变（本就是 AG-UI 六类）；内嵌 JSON 摊平为顶层字段；`seq` 保留（前端归并键）；step/工具摘要等扩展字段原样透传（AG-UI 不限制扩展字段）；`INTERRUPT`/`STATE` 转 `CUSTOM`（name=`ontos.interrupt`/`ontos.state`），不新增六类之外的事件类型。

### D2 · CopilotKit Headless 恢复为问数工作台主路线

- `CopilotKitProvider selfManagedAgents={{ ontos: new HttpAgent({ url: "/agent/ui/ontos" }) }}`（生产级入口，零 Node runtime，守 §1.2.1 单体锁定）。
- `useAgent` 订阅事件 → 状态仍归 Zustand `ask-workbench-store`（归并语义不变：running 更新、attempt+1、无进度条）；`agent.runAgent({ messages, forwardedProps })` 替代 `streamAskRun` 成为提交入口。
- `/api/v1/ask/runs/stream` 保留（非 CopilotKit 消费方与调试用），`/agent/run/ontos` 不动（F-5 语义不变）。

### D3 · 身份与安全

- 身份仍由网关 `X-Ontos-*` 头注入（CopilotKit 不承载身份；`selfManagedAgents` 的 `headers` 源可带租户头）。
- 三条安全约束不变：`RUN_STARTED.input` 服务端+前端双重脱敏；state 只读投影；资产 ID 不上屏。

## 影响

1. 前端问数工作台事件接入层切换为 CopilotKit；业务渲染组件（过程面板/结果看板/五态卡）复用不变。
2. 后端新增 1 个端点与 2 个转换/输入类型；编排零改动。
3. 附录 A 主路线恢复；ADR-0010 观察项关闭（本 ADR 取代其「M2 走退路」的路线指向，组件栈决策不变）。

## 回退

若 CopilotKit v2 API 升级破坏接入：前端回退 `streamAskRun`（保留未删），网关端点保留为调试通道；另发 ADR 说明。

## 并行义务点名

OBL-M4 / OBL-M7 维持 `done`，`deferred 0`；本 ADR 不新增、不关闭并行义务。

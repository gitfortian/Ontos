# ADR-0010 · CopilotKit 复查与主路线评估

> **状态**：已冻结
> **版本**：v1
> **类型**：决策
> **负责人**：项目负责人（2026-11-08 确认冻结）
> **最后更新**：2026-11-08
> **适用范围**：前端 AG-UI 接入路线；T5.5 遗留项（观察项）的复查结论；验证页与依赖去留
> **上游依据**：[T5.5 · CopilotKit 接入验证 v1](../06-verification/poc/T5.5-CopilotKit接入验证-v1.md)、[ADR-0001 前端组件栈归位范围](ADR-0001-前端组件栈归位范围.md)、[M1 验收报告 v1 §4](../06-verification/checklists/M1-验收报告-v2.md)、[CopilotKit selfManagedAgents 官方文档](https://docs.copilotkit.ai/backend/self-managed-agents)
> **修订记录**：2026-11-08 v1 首次冻结（M1/T1.7）：复查发现官方已文档化 selfManagedAgents 非生产禁区接入；决策维持 M2 退路 + spike 登记

## 背景

T5.5（2026-11-07）结论：CopilotKit 1.85 可经 `agents__unsafe_dev_only` 零 Node runtime 直连 Java
AG-UI SSE，但该入口「非官方推荐、可能移除、缺配套」，故走退路 `@ag-ui/client` + 自研渲染，
CopilotKit 列观察项待复查（M1/T1.7，复查触发条件 = 官方是否提供正式 non-runtime 接入）。

## 复查发现（2026-11-08）

官方文档已提供 **`selfManagedAgents`** 配置（docs.copilotkit.ai/backend/self-managed-agents）：
无需 Copilot Cloud / runtimeUrl，直接注册自托管的 AG-UI 兼容 `HttpAgent`——即**文档化的生产级
非 runtime 直连入口**。2026-02 的官方 issue #3205 亦引用该配置名，说明其非实验特性。

**但两个保留项**：①官方文档未承诺 `selfManagedAgents` 在我们锁定的 `@copilotkit/react-core@1.85.0`
中行为稳定（可能需升级）；②`selfManagedAgents` 的鉴权头注入、thread 生命周期管理是否完备
（T5.5 §4 指出的三项缺失）需实测确认。

## 决策

1. **M2 继续走退路**：`@ag-ui/client` + 自研渲染已实证可用（20/20 会话、脱敏、流式时序），
   M2 前端工作不因 CopilotKit 复查结果切换路线——切换成本与收益在 M2 内不成正比。
2. **登记 spike（M2 内择机）**：升级 `@copilotkit/react-core` 至当前稳定版，以
   `selfManagedAgents` 直连 `/agent/run/ontos` 实测（会话跑通、`/api/copilotkit` 0 次、
   鉴权头可注入、thread 生命周期可控）。**spike 通过且 API 文档化稳定 → 发新 ADR 恢复主路线**；
   不通过或仍是实验性 → CopilotKit 移出观察项，正式关闭主路线。
3. **验证页与依赖去留**：`/probe/copilotkit` 验证页与 `@copilotkit/*` 依赖保留至 spike 完成；
   spike 结束后一并处置（走主路线则改造验证页，正式关闭则移除）。
4. 本决策不改变 ADR-0001 的组件栈决策（shadcn/ui + Radix 不变），只覆盖其 T5.5 段的路线指向。

## 备选方案

| 备选 | 为何未选 |
|---|---|
| 立即切换主路线（未实测） | 违反 M0 治理纪律：结论先行、实测后补是 T5.5 明确反对的 |
| 立即移除 CopilotKit 依赖 | spike 未做，若 selfManagedAgents 可用则主路线恢复有价值；依赖移除是不可逆动作 |
| 维持 unsafe_dev_only | 字面即非生产用法，与 T5.5 结论一致，不重新评估 |

## 影响

- M1 验收报告 §4 的 T1.7 行关闭（复查完成，结论落本 ADR）；
- M2 前端任务不含 CopilotKit 接入；spike 择机执行（不占关键路径）；
- 版本事实台账补 `selfManagedAgents` 文档条目（2026-11-08，docs.copilotkit.ai）。

## 回退方案

spike 通过并恢复主路线后，若在生产使用中发现 selfManagedAgents 行为不稳定 → 回退至
`@ag-ui/client` 退路（已实证），发 ADR 记录原因；退路代码在主路线使用期间保留可切换性。

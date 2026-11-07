# 06 · 验证

**放**：PoC 报告、评测集与结果、检查清单执行证据。

**不放**：未执行的设想、计划中的测试。

**索引 / 约定**：`poc/` `evaluation/` `checklists/`；判据引用技术选型 §7，不复制阈值。

**当前文档**

- [M0 验收报告 v2](checklists/M0-验收报告-v2.md)（已冻结，G1–G8 逐条核对；G2 有条件关闭经签字并见 [ADR-0006](../01-decisions/ADR-0006-G2有条件关闭与重开口径.md)；含审计勘误记录）
  - 历史版本：[v1](../99-archive/checklists/M0-验收报告-v1.md)（已归档；Golden 集条数与 HITL/STATE 回填阶段两处口径经审计勘误）
- [M0 基线自检输出](checklists/M0-基线自检-2026-11-07.txt)（G7 的归档原始输出，4 PASS / 0 FAIL）
- [零号冒烟集 v1](evaluation/零号冒烟集-v1.md)（已冻结，20 题题目与分类；gold 待 M1/M2 补齐）
- [编译器 Golden 集 v1](evaluation/编译器Golden集-v1.json)（30 条，QUERY 20 + 非 QUERY 10；M2 补齐模式后升版）
- [复合意图题集 v1](evaluation/复合意图题集-v1.json)（PoC #8 判据集，30 题；结果待 M2 回填）
- [中文提及样本集 v1](evaluation/中文提及样本集-v1.json)（PoC #5 判据集，300 条 120/90/45/45；结果待 M5 回填）
- [PoC-01 · RLS 最终边界报告 v1](poc/PoC-01-RLS最终边界.md)（系统级，限定范围 PASS，500 次检查 0 越权；补验不通过的后果见 ADR-0006/验收报告 v2）
- [PoC-02 · 发布快照可复现报告 v1](poc/PoC-02-发布快照可复现.md)（系统级 PASS，跨 2 次重启 300/300 逐字一致）
- [PoC-03 · AgentScope 准入报告 v1](poc/PoC-03-AgentScope准入.md)（坐标/Boot/API 差异通过；五类终态重建 FAIL，协议层 100%、语义层 68.8%；重开口径见 ADR-0006）
- [PoC-04 · 编译器 SQL 输出报告 v1](poc/PoC-04-编译器SQL输出.md)（QUERY 20/20 逐字一致、EXPLAIN 全通过；非 QUERY 静默聚合缺陷已 fail-closed 修复，模式构造器待 M2）
- [PoC-07 · 跨轮差分与存在性保护报告 v1](poc/PoC-07-跨轮差分与存在性保护.md)（探针级，现行初值 FAIL、修订退路 PASS）
- [PoC-10 · 前端直连 AG-UI SSE（协议 + 界面）v1](poc/PoC-10-协议探针.md)（协议段四类事件可达、幂等通过；界面段 20/20 重建 4/6 类事件；物理断线重连场景待 M5 补验）
- [T5.5 · CopilotKit Headless 接入验证 v1](poc/T5.5-CopilotKit接入验证-v1.md)（零 Node runtime 直连成立，但入口为 `unsafe_dev_only`，结论走退路；CopilotKit 列观察项）
- [M0 硬阻断结论 v2](poc/M0-硬阻断结论-v2.md)（G1 汇总与闸门判定：**G1 关闭**，三项决策已签字）
  - 历史版本：[v1](../99-archive/verification/M0-硬阻断结论-v1.md)（已归档，签字前的「未关闭」判定）
- [M0 PoC 总览 v1](poc/M0-PoC总览-v1.md)（10 项 PoC 状态定档；闸门状态以验收报告 v2 为准）

本目录遵循 `../README.md`（文档规范与总索引）：统一头块、生命周期、单一事实源与相对链接规则。

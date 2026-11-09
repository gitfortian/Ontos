# 00 · 文档治理

**放**：文档模板、治理检查脚本、术语表、并行义务登记台账。

**不放**：业务方案、计划、进度、评测结果。

**索引 / 约定**：`templates/` 七类起稿模板；`check-docs.mjs` 治理检查入口；
`check-parallel-obligations.mjs` 并行义务与阶段状态强制检查（规则见 `../README.md` §7.8 与 `../../AGENTS.md` §6.4）；
`parallel-obligations.json` 并行义务台账（只登记跟踪状态，阶段定义仍以 `03-plans/roadmap` 为准）。

**当前文档**

- [ADR 模板等七类起稿模板](templates/)（方案 / 计划 / 实施规格 / ADR / 规格 / 进度快照 / 验证报告）
- [docs 治理检查](check-docs.mjs)（层级、命名、相对链接、目录说明）
- [并行义务与阶段状态检查](check-parallel-obligations.mjs)（含 [反例单测](check-parallel-obligations.test.mjs)，13 例；接入 `scripts/verify-baseline.mjs`）
- [并行义务登记台账](parallel-obligations.json)（schemaVersion 1；状态仅 `done` / `deferred`）

本目录遵循 `../README.md`（文档规范与总索引）：统一头块、生命周期、单一事实源与相对链接规则。

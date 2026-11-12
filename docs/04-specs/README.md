# 04 · 规格

**放**：可验证的契约：接口、DSL Schema、数据表、安全约束、验收清单。

**不放**：方案论证、排期、主观评价。

**索引 / 约定**：`api/` `database/` `dsl/` `security/` 按契约域分；跨契约域的规格放本目录根（命名 `<域>-<对象>-规格-vN.md`）。

**当前文档**

- [ontology · 业务化命名 规格 v1](ontology-业务化命名-规格-v1.md)（已冻结，双层命名原则与资产业务字段契约）
- [analysis-mode · funnel 与 yoy_mom 规格 v1](analysis-mode-funnel-yoy-mom-规格-v1.md)（已冻结，M2/T3.0：两种模式的 `analysis_params` Schema 与 v1.4 §9.3/§9.4 语义契约逐条定档）
- [analysis-mode · retention 与 attribution 规格 v1.1](analysis-mode-retention-attribution-规格-v1.1.md)（已冻结，M6/T1.1：留存/归因锚点与 §9.5/§9.6 契约；v1.1 对齐已发布时间模式与主体表维度）
- [analysis-mode · discovery 边界声明 v1](analysis-mode-discovery-边界声明-v1.md)（已冻结，M6/T1.6：DISCOVERY 不进 DSL，由 M5 discovery 终态承载；拒绝路径、载荷契约与验证口径）
- [composite · 结果引用 规格 v1](composite-resultRef-规格-v1.md)（已冻结，M6/W4 编码前置（ADR-0017 D2）：SubIntent.resultRef 结构化 Schema、single_row/ordered_top 分立选择器、filter_value 注入与再校验、G-6 失败码 044–046）
- [ask · 复杂模式自由问数表达与参数补全 规格 v0.1](ask-复杂模式自由问数表达与参数补全-规格-v0.1.md)（**草稿**：承接 ADR-0023；定义提示词 v4 草案、服务端 ComplexAnalysisParamsResolver、FUNNEL/RETENTION/ATTRIBUTION/LOOKUP_BATCH 参数补全与小批量验收口径）
- [语义模型三层化 · 影响分析 v0.2](语义模型三层化-影响分析-v0.2.md)（**已定档**，B0 立项依据：35 条核对清单逐项定档（破坏 0 / 需论证 6 / 需设计 1 / 平移 13）+ v19 资产映射实证 + E1/E2 工程约束 + 迁移与回滚评估；负责人 2026-11-12 判定 **B0 与 M8 解耦、独立排期**，ADR-0021 不立、Model 层写为非目标；v0.1 [已归档](../99-archive/specs/语义模型三层化-影响分析-v0.1.md)）

本目录遵循 `../README.md`（文档规范与总索引）：统一头块、生命周期、单一事实源与相对链接规则。

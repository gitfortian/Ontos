# analysis-mode · discovery 边界声明

> **状态**：已冻结
> **版本**：v1
> **类型**：规格
> **负责人**：项目组
> **最后更新**：2026-11-10
> **适用范围**：M6/W1/T1.6；`AnalysisMode.DISCOVERY` 在 DSL / 编译器 / 会话链路中的边界
> **上游依据**：[M6 实施规格 v1.0](../03-plans/stages/M6/M6-分析模式ScenarioSkill与复合意图-实施规格-v1.0.md) §8 决策 3 / §4-W1 T1.6、[完整可落地方案 v1.4 §9.2](../02-solutions/architecture/智能问数系统-完整可落地方案-v1.4.md)、[M5 实施规格 v1.2](../03-plans/stages/M5/M5-会话意图编排与AG-UI链路-实施规格-v1.2.md)
> **修订记录**：2026-11-10 v1 首次冻结：明确 DISCOVERY 不进 DSL，由 M5 交付的 discovery 终态承载；给出拒绝路径、载荷契约与验证口径。

## 1. 结论

**`DISCOVERY` 不进 DSL。** 它不是一种「查询」，而是会话层的**结构化终态**（能力发现），
由 M5 已交付的 `TerminalAssembler.discovery(...)` 承载 v1.4 §9.2 契约；M6 只扩展其**数据源**
（Scenario 列表 + `cold_start_samples`，T2.5），不新增 DSL 字段、不新增编译器分支。

## 2. DSL 与编译器的拒绝路径（fail-closed）

| 层 | 行为 | 错误码 |
|---|---|---|
| `DslValidator.validateMode` | 即使 `metric.allowed_modes` 声明了 `DISCOVERY`，也抛 `ANALYSIS_MODE_NOT_IMPLEMENTED` | `1_002_000_006` |
| `SqlCompiler.compile` | 纵深防御：绕过校验器直接编译同样拒绝，绝不降级为聚合 SQL | 同上 |
| `DslCodec` | `mode` 字段在白名单内（用于表达请求类型），但取值 `DISCOVERY` 会在上述两层被拒 | — |

**为什么不让它进 DSL**：discovery 不产出查询，没有 metric / 维度 / 时间语义；若为它开一条编译路径，
「模式 = 编译器分支 + 封闭校验」的结构会被打破（roadmap R6：模式膨胀导致编译器分叉）。
它是**意图层的出口**，不是**查询层的入口**。

## 3. 载荷契约（由 M5 终态承载，本声明只固定边界）

- 三键对象：`metrics` / `dimensions` / `analysis_modes`，**同时给出**（即使为空数组）；
- 所有条目必须是**已发布资产的引用**（资产 ID），禁止 LLM 生成、禁止自由文本；
- 只在**授权 scope 内**枚举（与检索瀑布的 `authorizedAssetIds` 同源）；
- 服务端装配，客户端只读；不写回会话状态、不参与跨轮差分。

## 4. M6 的扩展点（T2.5，不属于本声明范围）

- 数据源增加 **Scenario 列表**（授权内、已发布、`entry_mode` 允许被推荐）；
- `cold_start_samples` 必须是已发布 VQR / Golden Query 的引用（发布校验保证），点选后可继续走正常查询链路。

## 5. 验证口径

| 项 | 证据 |
|---|---|
| 校验器拒绝（即使白名单放行） | `DslValidatorTest.shouldRejectNonQueryModeEvenWhenWhitelisted`（DISCOVERY 用例） |
| 编译器纵深防御 | `SqlCompilerTest.shouldRejectNonQueryModeDefensively`（DISCOVERY 用例） |
| 终态承载不回退 | M5 验收报告 E1：PoC #3 复跑 20/20 含 discovery 6 例（含引导 2） |
| M2 五模式回归 | M2 Golden 27 通过 / 3 unsupported / 0 失败（QUERY / YOY_MOM / FUNNEL / LOOKUP / LOOKUP_BATCH） |

## 6. 不做（明确排除）

- 不新增 `AnalysisParams.discovery` 类字段；
- 不为 DISCOVERY 增加 SQL 构造分支或缓存键维度；
- 不把 discovery 结果写入会话投影（`resolved_entities`）——M5 口径：澄清/拒答/discovery 终态不投影。

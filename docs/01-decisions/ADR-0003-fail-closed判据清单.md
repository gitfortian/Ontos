# ADR-0003 · fail-closed 判据清单

> **状态**：已冻结
> **版本**：v1
> **类型**：决策
> **负责人**：项目负责人（技术项，2026-11-07 授权按现有检查点自主冻结）
> **最后更新**：2026-11-07
> **适用范围**：M0 / W4 / T4.3；M1 起的全部查询路径与启动自检
> **上游依据**：[技术选型 v1.1 §4.1 / §7](../02-solutions/architecture/智能问数系统技术选型方案-v1.1.md)、[完整落地方案 v1.4 §13.1](../02-solutions/architecture/智能问数系统-完整可落地方案-v1.4.md)、[PoC-04](../06-verification/poc/PoC-04-编译器SQL输出.md)
> **修订记录**：2026-11-07 v1 首次冻结，13 条场景逐条映射到现有检查点

## 背景

判据要求在不确定时**一律拒答**，而不是降级给近似结果。当前代码已有若干 fail-closed 检查点，
但分散在 `ManifestService` / `DslValidator` / `PolicyService` / DB 层 RLS，缺一份统一清单：

- 新增功能时不知道该在哪里加检查，容易漏；
- 审计时无法判断「这次拒答属于哪一类」；
- PoC-04 发现的「非 QUERY 模式静默生成错 SQL」正说明缺少清单——没人意识到编译器也要 fail-closed。

本 ADR 把散落的检查点收敛成一张可逐条验证的表。

## 决策

以下 13 类场景**必须拒答或拒绝启动**，不允许「返回近似结果 + 警告」。

### 请求级（第 1–9 条）

| # | 场景 | 检查点 | 错误码 | 状态 |
|---|---|---|---|---|
| 1 | 清单未加载 / 请求未携带 `manifest_id` | `ManifestService.validateManifest` | `1_001_000_000` `MANIFEST_NOT_FOUND` | 已实现 |
| 2 | 请求清单与生效清单不一致 | 同上 | `1_001_000_001` `MANIFEST_MISMATCH` | 已实现 |
| 3 | 清单内容哈希不符 | `ManifestService` 加载期校验 | `1_001_000_006` `MANIFEST_HASH_MISMATCH` | 已实现 |
| 4 | 资产未发布（`metric_id` / `dimension_id` 不在清单） | `SemanticManifest.requireMetric` / `requireDimension` | `1_001_000_003` `ASSET_NOT_FOUND` | 已实现 |
| 5 | 指标不支持该分析模式 | `DslValidator` 查 `allowedModes` | `1_001_000_004` `ASSET_MODE_NOT_ALLOWED` | 已实现 |
| 6 | 维度不在指标允许范围 | `DslValidator` 查 `allowedDimensions` | `1_001_000_005` `DIMENSION_NOT_ALLOWED` | 已实现 |
| 7 | 跨表 Join 缺少授权子图 | `DslValidator` 的图可达性检查 | `1_002_000_001` `CROSS_TABLE_UNSUPPORTED` | 已实现（骨架） |
| 8 | DSL 结构非法（空指标、类型不符等） | `DslValidator` | `1_002_000_000` `DSL_INVALID` | 已实现 |
| 9 | 无策略或策略未命中 | `PolicyService.check` | `403 FORBIDDEN` | 骨架：除 `dev-allow-all` 外一律拒；M1 接 `policy_snapshot` |

### 编译级（第 10 条，本次新增）

| # | 场景 | 检查点 | 错误码 | 状态 |
|---|---|---|---|---|
| 10 | **分析模式未实现** | `SqlCompiler.compile` 入口按 `AnalysisMode` 分支，未实现即抛 | 需新增 `1_002_000_006` `MODE_NOT_IMPLEMENTED` | **待实现（M1 前必须补）** |

为什么单列：PoC-04 实测 10 条非 QUERY 用例全部「编译成功」并生成聚合 SQL，
`DslValidator` 只校验 `allowedModes`、编译器不读 `mode`，结果是执行层拿到语义错误的查询、
返回一个看起来正常的数字。这比崩溃更危险——**必须让编译层 fail-closed，而不是依赖上游校验**。

### 启动级（第 11–13 条）

| # | 场景 | 检查点 | 行为 | 状态 |
|---|---|---|---|---|
| 11 | 当前 DB 角色为表 owner 或具 `BYPASSRLS` | 启动自检 | 拒绝启动 | 待实现（M4） |
| 12 | 目标表 `forcerowsecurity = false` | 启动自检 | 拒绝启动 | 待实现（M4） |
| 13 | 连接池非 `transaction` 模式 / `server_reset_query` 缺失 | 启动自检 | 拒绝启动 | 待实现（M4） |

补充两条**待定错误码**，随对应能力一并落地：

| 场景 | 触发阶段 | 错误码 |
|---|---|---|
| 时间解析器版本缺失（无法确定换算规则） | M2 | 需新增 `PARSER_VERSION_MISSING` |
| 敏感字段缺少脱敏策略 | M4 | 需新增 `MASKING_POLICY_MISSING` |

## 依据

- **v1.4 §13.1 硬阻断**：越权与差分泄露不得降级处理，检查点必须在数据离开系统前生效。
- **PoC-04 实测**：编译器不 fail-closed 会产生静默错数，是本次新增第 10 条的直接原因。
- **现有实现**：第 1–8 条已在代码中，本 ADR 只是把它们收敛成清单并补上审计口径。

## 影响

- `SqlCompiler` 需加 mode 分支守卫（M1 前），否则第 10 条不成立。
- 错误码分段继续遵循 `1_001_*` 本体、`1_002_*` 查询、`1_003_*` 审批；新增码不得跨段。
- 审计日志需能记录「命中了清单第几条」，便于事后统计拒答分布。
- 前端只展示统一文案，不回显具体是哪一条触发（避免差异信息泄露，与 §4.8.3 一致）。

## 回退方案

**清单只增不减。** 任何「临时放行」只能通过 `ontos.policy.dev-allow-all`（且 `DevModeGuard` 保证非 `local` profile 下拒绝启用），
不得为通过测试而在生产路径删除检查点。确需调整某条判据，发新 ADR 取代本 ADR。

## 后续动作

- M1：实现第 10 条（编译层 mode 守卫）+ 第 9 条接 `policy_snapshot`。
- M4：实现第 11–13 条启动自检。
- 每次新增查询能力时，先问「它属于这 13 条里的哪一条」，答不上来说明检查点缺失。

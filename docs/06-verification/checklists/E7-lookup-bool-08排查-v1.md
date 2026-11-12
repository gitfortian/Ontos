# E7 · t-lookup-bool-08 排查记录 v1

> **状态**：已固化
> **版本**：v1
> **类型**：验证
> **负责人**：项目组（2026-11-12 负责人要求并行排查状态机异常）
> **最后更新**：2026-11-12
> **适用范围**：`t-lookup-bool-08` 在运行 `E7-COMPLEXITY-MIMO-20261112` 中的失败归因；不改变复杂度诊断总体统计，不解除 C-1
> **上游依据**：[E7 复杂度子集诊断 MIMO v1](../evaluation/E7复杂度子集诊断-MIMO-v1.md)、[ADR-0023](../../01-decisions/ADR-0023-自由问数复杂分析模式入口口径.md)
> **修订记录**：2026-11-12 v1：完成 DB / 事件流 / 代码交叉排查；判定不是服务端状态机非法迁移，而是 runner 断点复用 request_id + 确定性 planner 布尔指标误用 QUERY

## 1. 现象

`E7复杂度子集诊断-MIMO-v1.json` 中 `t-lookup-bool-08` 最终失败：

```text
code=1002000032
errorMessage=请求已处于终态（REJECTED），请使用新的 request_id 重试:
llml-E7-COMPLEXITY-MIMO-20261112-r1-t-lookup-bool-08-t1
```

题目期望：

```json
{
  "terminal": "query",
  "mode": "LOOKUP",
  "booleanMetricId": "metric.high_value_customer",
  "identity": {"propertyId": "property.customer.customer_id", "value": "c-1002"}
}
```

## 2. DB 事实

### 2.1 幂等状态

`request_state`：

```text
request_id = llml-E7-COMPLEXITY-MIMO-20261112-r1-t-lookup-bool-08-t1
state      = REJECTED
updated_at = 2026-11-12 12:21:02.816275+08
```

### 2.2 ask_run

同一 session 有两条 run，均为 `FAILED`：

| turn | created_at | updated_at | 说明 |
|---|---|---|---|
| 1 | 12:20:02.694 | 12:21:02.818 | 第一次诊断被停止前的服务端执行 |
| 2 | 12:29:04.163 | 12:29:13.463 | checkpoint 断点续跑 |

### 2.3 事件流

第一次 run：

```text
RUN_STARTED          12:20:02.695
TEXT_MESSAGE_CONTENT （模型暂不可用，已切换确定性检索模式）12:21:02.806
TOOL_CALL_END        execute_query / server_assembled 12:21:02.812
RUN_ERROR            指标不支持该分析模式: metric.high_value_customer / QUERY 12:21:02.819
```

第二次 run：

```text
RUN_STARTED          12:29:04.175
TOOL_CALL_END        execute_query / server_assembled 12:29:13.453
RUN_ERROR            请求已处于终态（REJECTED），请使用新的 request_id 重试 12:29:13.467
```

## 3. 归因

### 3.1 服务端状态机没有非法迁移

第二次 run 的 `1002000032` 是幂等保护正确触发：

- 第一次 run 已把同一 `request_id` 推进到终态 `REJECTED`；
- 断点续跑时 runner 复用了相同 `request_id`；
- `QueryServiceImpl.beginRequest()` 检测到终态后拒绝重复执行。

因此这不是服务端状态机 bug。

### 3.2 runner 断点复用 request_id 是直接诱因

原 runner 的 `requestIdOf()` 只由 `RUN_ID / runIndex / caseId / turnIndex` 组成。首段任务被停止时，服务端可能已经完成该请求并落终态，但 runner 侧尚未把结果写入 checkpoint；续跑后同题重新执行就复用同一 `request_id`。

**已修复**：`scripts/verify/llm-test-runner.mjs` 在 `--resume` 时给 request_id 追加 `-x<resumeAttempt>`，避免跨断点复用幂等键。语法与未确认计划输出已通过。

### 3.3 确定性 planner 的布尔指标误用是底层执行错误

第一次 run 的真正业务错误是：

```text
指标不支持该分析模式: metric.high_value_customer / QUERY
```

资产事实：

```json
{
  "id": "metric.high_value_customer",
  "ruleId": "rule.high_value_customer",
  "allowedModes": ["LOOKUP", "LOOKUP_BATCH"],
  "allowedDimensions": []
}
```

但 `DeterministicProposalPlanner` 的分支顺序先处理“metric 命中”，命中后固定输出：

```java
subIntent.put("analysis_mode", "query");
```

没有先识别 `metric.high_value_customer` 是 `ruleId` 型布尔指标且只允许 LOOKUP / LOOKUP_BATCH，也没有把 `booleanMetricId` 放进 LOOKUP 提案。历史降级路径中多个 `lookup-bool-*` 题也出现同类错误。

## 4. 结论

| 项 | 判定 |
|---|---|
| 服务端状态机 | **无 bug**；终态幂等保护按预期工作 |
| runner 断点续跑 | **有 bug**：复用 request_id；已修复 |
| 确定性 planner | **有 bug**：ruleId 型布尔指标被误用为 QUERY；待修复 |
| 本题失败性质 | 工具链 / 确定性降级缺陷，不应直接归因为模型语义失败 |

## 5. 后续动作

1. 修复 `DeterministicProposalPlanner`：`ruleId` 型布尔指标必须进入 LOOKUP / LOOKUP_BATCH，携带 `booleanMetricId`、`attributes` 与 `identity_value`。
2. 为布尔指标分支补单测：布尔指标 + 身份 + 属性 → LOOKUP；普通指标仍走 QUERY；身份缺失澄清。
3. 后续小批量复测时单独重跑 `t-lookup-bool-*`，并把 bool-08 从“模型语义失败”重新归因为工具链 / planner 缺陷。
4. 本排查不解除 C-1，不修改 test 集 v5 期望。

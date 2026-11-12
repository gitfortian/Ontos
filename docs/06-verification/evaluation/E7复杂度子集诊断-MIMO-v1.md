# E-7 复杂度子集诊断 · mimo v1

> **状态**：已固化（**探索性诊断证据**，不作为 ADR-0019 C-1 正式验收证据）
> **版本**：v1
> **类型**：验证
> **负责人**：项目组（2026-11-12 负责人授权使用 mimo 继续复杂度诊断）
> **最后更新**：2026-11-12
> **适用范围**：`mimo-v2.6-flash` 当前锚点下的外部模型复杂度诊断与失败族归因；不用于性能验收，不替代 test 集 v5 全量 ×3
> **上游依据**：[E7 复杂度子集 v1](E7复杂度子集-v1.md)、[模型服务锚定 v3](模型服务锚定-v3.md)、[ADR-0022](../../01-decisions/ADR-0022-外部模型评测改为复杂度优先与API安全模式.md)
> **修订记录**：2026-11-12 v1：完成 50 题 / 57 HTTP 轮次；run 后零 token 重判为判据 v2.1

## 1. 执行口径

| 项 | 值 |
|---|---|
| 模型 | `mimo-v2.6-flash` |
| 端点 | `https://token-plan-cn.xiaomimimo.com/v1` |
| 运行 ID | `E7-COMPLEXITY-MIMO-20261112` |
| 子集 | [E7复杂度子集-v1.json](E7复杂度子集-v1.json)，50 题 / 57 HTTP 轮次 |
| 执行方式 | 并发 1、请求间隔 5000ms、熔断开启 |
| 执行过程 | 首段 28 题后负责人停止；后续用 checkpoint 断点续跑至 50/50 |
| 工作区 | `.workbuddy/runtime/agentscope-e7-complexity-20261112-1210` |
| 生效清单 | `2026.11.11-nineteenth` |
| 提示词 | v3 |
| 候选域 | `retrieval` |
| 原始判据 | v2 |
| 固化判据 | v2.1（零 token 重判） |

## 2. 证据与哈希

| 项 | 值 |
|---|---|
| 固化证据 | [E7复杂度子集诊断-MIMO-v1.json](E7复杂度子集诊断-MIMO-v1.json) |
| 固化证据 SHA-256 | `d060d61d5d825dd05dc41b1f77972b55d4527f1b519721911c252d308de3c6ac` |
| 原始 runner 输出 | `target/e7-complexity-mimo-v1.json` |
| 原始输出 SHA-256 | `6c35eb1d4a0a3e4269fc6ddb01fcaa57e48900fe2a3b723a87cb214637622081` |
| checkpoint | `target/e7-complexity-mimo-v1.json.checkpoint.jsonl` |

## 3. 核心结果

### 3.1 总体

| 指标 | 值 |
|---|---|
| 原始判据 v2 | 19 / 50（38%） |
| 零 token 重判 v2.1 | **21 / 50（42%）** |
| Wilson95 | `[0.2937, 0.5577]` |
| LLM 使用 | 50 / 50 |
| 无 LLM 降级 | 0 |
| 编排层 `degraded` | 0 |
| 业务错误 | 4 |
| token 合计 | 864,975 |
| 单题 token 均值 | 17,300 |
| 单题 token 最大 | 44,564 |
| 延迟 P50 | 10,843ms |
| 延迟 P95 | 65,091ms |

重判仅修正 2 题：`t-diff-08` 与 `t-cross-domain-02`。原因是 runner v2 不支持「A 或 B」复合期望终态；v2.1 已修复。重判不改变任何请求、token、延迟或 trace。

### 3.2 分析模式分层

| 模式 | 通过 / 总数 | 通过率 |
|---|---:|---:|
| QUERY | 11 / 18 | 61.11% |
| YOY_MOM | 4 / 6 | 66.67% |
| FUNNEL | 0 / 4 | 0% |
| RETENTION | 0 / 3 | 0% |
| ATTRIBUTION | 0 / 4 | 0% |
| LOOKUP | 1 / 6 | 16.67% |
| LOOKUP_BATCH | 0 / 2 | 0% |
| COMPOSITE | 4 / 6 | 66.67% |
| DISCOVERY | 1 / 1 | 100% |

## 4. 失败族归因

| 失败族 | 现象 | 初步结论 |
|---|---|---|
| 复杂分析模式选择失败 | FUNNEL / RETENTION / ATTRIBUTION 共 11 题全部失败，多表现为 `out_of_capability` | 模型未能将自然语言稳定映射到 M6 复杂模式；应优先检查候选词表、提示词模式示例与提案表达 |
| LOOKUP 族薄弱 | LOOKUP 1/6、LOOKUP_BATCH 0/2；布尔与存在性探针均失败 | 已知 lookup 提案遵循缺口仍是主要瓶颈 |
| `gold` 取值翻译缺口 | `t-agg-filter-02`、`t-agg-filter-08` 因「金牌客户」等取值澄清 | 对齐 R-ROOT-2：需要维度取值词表或别名登记 |
| 订单项销量表达缺口 | `t-agg-single-17b`、`t-yoy-06b` 未进入订单项销量路径 | 资产已在清单中，但模型表达 / 提示词映射不稳定 |
| 多轮上下文继承不足 | followup / correction / incremental 3 题澄清 | 多轮改口与指代继承需专项优化 |
| 服务端状态异常 | `t-lookup-bool-08` 报「请求已处于终态（REJECTED）」 | 登记 runner / 编排重试状态问题，需代码侧复核 |
| 判据缺陷 | `t-diff-08`、`t-cross-domain-02` 被 v2 误判 | 已修 runner v2.1，重判计入通过 |

## 5. 性能观测边界

- 延迟与 token 仅作观测，不作为 PASS / FAIL 门槛。
- trace 层面有 6 次 cancelled，均接近本地 60 秒模型等待上限；案例层未见对应最终失败，说明部分重试 / 修复轮仍完成任务。
- 本证据不用于外部 API 吞吐或性能结论。

## 6. 结论与后续

1. mimo 当前复杂度通过率约 42%，主要短板不是基础 QUERY，而是复杂分析模式、lookup 与多轮上下文。
2. 该结果只用于失败族优先级排序，**不解除 ADR-0019 C-1**。
3. 后续应先做零 token 资产 / 提示词审查，再决定是否小批量修复与复测。
4. `t-lookup-bool-08` 的终态重放错误需要单独登记与排查。

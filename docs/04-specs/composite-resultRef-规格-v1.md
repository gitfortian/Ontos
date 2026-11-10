# 复合意图 · 结果引用 规格 v1

> | 字段 | 值 |
> |---|---|
> | 编号 | SPEC-COMPOSITE-RESULTREF |
> | 版本 | v1（冻结） |
> | 日期 | 2026-11-10 |
> | 负责人 | M6/W4 编码 agent |
> | 评审人 | ADR-0017 决策 D2 裁决 + 自审 |
> | 状态 | 已冻结（M6/W4/T4.1–T4.2 编码前置，ADR-0017 D2） |
> | 关联 | M6 实施规格 v1.0 §3.5、决策 6、G-6；v1.4 §7.7；ADR-0003（fail-closed）；M6-W4-T4.x 审核（待建） |

## 1. 目的与边界

M2 起 `SubIntent.dependsOn` 只承载「依赖存在性」，**取值方式无 Schema**（题集 derive 是自由文本）——这是
ADR-0017 D2 指出的规格洞。本规格冻结**结果引用的结构化 Schema 与注入语义**，作为 T4.1（拓扑排序）与
T4.2（结果注入）的编码依据。冻结后：实现与本规格冲突时以本规格为准；修订必须升版（v1 归档 + 新版登记）。

**不在本规格范围**：依赖图算法细节（T4.1 实现自由，只约束行为契约）；部分失败响应形状（沿用 M2
CompositeOutcome）；跨请求依赖（只允许同一复合请求内的子意图互相引用）。

## 2. Schema（DSL camelCase 契约）

`QueryDsl.SubIntent` 新增可选字段 `resultRef`（**每子意图至多一个**——多依赖取值拆分子意图表达）：

```json
{
  "subIntentId": "sub-2",
  "metricId": "metric.gmv",
  "dependsOn": ["sub-1"],
  "filters": [{ "fieldId": "dim.channel", "operator": "EQ", "values": [] }],
  "resultRef": {
    "fromSubIntentId": "sub-1",
    "selector": { "type": "ordered_top", "row": 1, "column": "dim.channel" },
    "inject": { "type": "filter_value", "filterIndex": 0 }
  }
}
```

| 字段 | 必填 | 约束 |
|---|---|---|
| `resultRef.fromSubIntentId` | 是 | 必须出现在本子意图的 `dependsOn` 中（声明与引用一致；否则结构校验拒绝） |
| `resultRef.selector.type` | 是 | 闭枚举 `single_row` \| `ordered_top` |
| `resultRef.selector.row` | `ordered_top` 必填 | 1 起整数；`single_row` 禁止携带 |
| `resultRef.selector.column` | 是 | 父结果列名（维度/指标列）；取值时必须存在于父结果列集 |
| `resultRef.inject.type` | 是 | 闭枚举，首发仅 `filter_value` |
| `resultRef.inject.filterIndex` | `filter_value` 必填 | 本子意图 `filters` 的下标（0 起）；目标 filter 的 `operator` 必须是单值算子（`EQ`），注入写入其 `values`（单元素） |

`dependsOn` 语义不变（存在性声明）；`resultRef` 是**取值方式**的精确化。`dependsOn` 非空而 `resultRef`
缺失 = 结构校验拒绝（依赖了却不说怎么取值——M2 遗留自由文本的洞由本规格封死）。

## 3. 选择器语义（D2 定档：single_row 与 ordered_top 分立）

| type | 语义 | 空结果 / 歧义处理（G-6，全部 fail-closed） |
|---|---|---|
| `single_row` | 父结果**至多 1 行**（0 或 1 行是合法形状——如布尔判定 / 单实体聚合） | 0 行 → `DEPENDENCY_VALUE_MISSING`；**≥2 行 → `DEPENDENCY_VALUE_AMBIGUOUS`**（引用歧义，禁止悄悄取第一行）；1 行 → 取 `column` 列值 |
| `ordered_top` | 父结果按**服务端返回顺序**取第 `row` 行（1 起；「第 1 行」= 决策 6 语义，排序责任在父子意图自身的 order/limit 或业务语义，本规格不引入新排序参数） | 父 0 行 → `DEPENDENCY_VALUE_MISSING`；`row` 越界 → `DEPENDENCY_VALUE_MISSING`；`column` 不在父结果列集 → `DEPENDENCY_VALUE_MISSING` |

> D2 裁决记录：评审建议的「≤1 行」若与决策 6「第 1 行」合并为同一 selector 会把两种业务形状搅在一起——
> **分立两个闭枚举值**，各配各的失败码；不照抄评审原文。

## 4. 注入语义与再校验

1. **注入点**：拓扑排序后的逐子执行循环内——父子意图 `QueryResult` 就绪后、子意图 DSL 提交编译前。
   首发仅 `filter_value`：把取值写入本子意图 `filters[filterIndex].values`（单元素替换原列表）。
2. **再校验（决策 6 硬要求）**：注入后的子 DSL **完整重走** `DslValidator` 子意图校验与 `prepareWith`
   （授权 + scope + 敏感面）——注入不产生任何校验捷径。值类型不符（如数字列注入给字符串 filter）由
   既有 filter 值校验拒绝。
3. **禁止事项（重申）**：禁止字符串拼接 SQL（值只进参数化绑定）；禁止把父查询原始结果透传给 LLM 再组
   DSL（依赖解析全部发生在编排/编译代码内）；注入值不得是标识符/列名（column 的**名字**用于取列，
   column 的**值**才是注入物——值一律按参数处理）。
4. **审计**：注入发生的子意图，其 `dsl_json` 审计必须含 `resultRef`（fromSubIntentId/selector 原样），
   且父子的 `parent_request_id / sub_intent_id` 覆盖率 100%（M2 通道扩展到注入路径）。

## 5. 失败码（只追加，闭枚举）

| 错误码 | 常量名 | 语义 |
|---|---|---|
| 1_002_000_044 | `DEPENDENCY_CYCLE` | 依赖环 / 自依赖 / 引用不存在的子意图（T4.1 结构期拒绝转澄清） |
| 1_002_000_045 | `DEPENDENCY_VALUE_MISSING` | 取值缺失：父 0 行 / ordered_top 越界 / 列缺失（G-6） |
| 1_002_000_046 | `DEPENDENCY_VALUE_AMBIGUOUS` | single_row 父结果 ≥2 行（引用歧义） |

失败传播遵循 M2 部分失败语义：取值失败的子意图**不产出部分结果**、不注入空值；其余子意图不阻断。

## 6. 验收清单（T4.1/T4.2 单测 + 对抗）

- [ ] 环 / 自依赖 / 缺失依赖 / `resultRef` 引用未声明依赖 → `DEPENDENCY_CYCLE`（迁移式拒绝用例：M2 30 题复合题集全部仍按原预期）
- [ ] `ordered_top` 第 1 行注入 filter → 子查询编译产物含参数化谓词（值不进 SQL 文本）
- [ ] `single_row` 0 行 / 1 行 / 2 行三态 + `ordered_top` 越界 → 044/045/046 三码各自正确
- [ ] 注入值再校验：类型不符 / 越权资产 → 既有校验码（无新码）
- [ ] 注入对抗：值含 SQL 特征 / 引号 / 标识符 → 参数化绑定安全（不拼接），审计含 resultRef
- [ ] 空结果 G-6：失败子不产出部分结果，其余子照常返回

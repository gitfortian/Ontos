# ask · 复杂模式自由问数表达与参数补全 规格

> **状态**：草稿（待负责人评审 / 小批量复测前冻结）
> **版本**：v0.1
> **类型**：规格
> **负责人**：项目组（2026-11-12 负责人确认按 ADR-0023 允许自由问数并要求小批量复测）
> **最后更新**：2026-11-12
> **适用范围**：`/api/v1/ask/runs` 自由问数路径中 FUNNEL / RETENTION / ATTRIBUTION / LOOKUP_BATCH 的提示词表达、服务端确定性参数补全、候选上下文与失败归因；不改变编译器 SQL、RLS、审计、审批、敏感查询与成本闸门
> **上游依据**：[ADR-0022](../01-decisions/ADR-0022-外部模型评测改为复杂度优先与API安全模式.md)、[ADR-0023](../01-decisions/ADR-0023-自由问数复杂分析模式入口口径.md)、[E7 失败三档归因 v1](../06-verification/checklists/E7失败三档归因-v1.md)、[analysis-mode · funnel 与 yoy_mom 规格 v1](analysis-mode-funnel-yoy-mom-规格-v1.md)、[analysis-mode · retention 与 attribution 规格 v1.1](analysis-mode-retention-attribution-规格-v1.1.md)
> **修订记录**：2026-11-12 v0.1 初稿：定义提示词 v4 草案、服务端 `ComplexAnalysisParamsResolver`、候选表达与验收口径

## 1. 背景与目标

ADR-0023 已定档：FUNNEL / RETENTION / ATTRIBUTION / LOOKUP_BATCH 允许自由问数入口。当前失败的主因是提示词 v3 禁止这些模式，并且 ask 编排层缺少复杂模式的确定性参数补全。

本规格目标：

1. 模型只负责把自然语言映射为语义槽位。
2. 服务端负责复杂模式的事件、窗口、主体属性等确定性参数。
3. 提示词 v4 不暴露 `analysisParams`、事件 ID、Skill 内部参数、物理表列或绝对日期。
4. 参数缺失 / 事件歧义时按澄清或 fail-closed，不静默猜测。

## 2. 提示词 v4 草案

草案文件：[`ontos/ontos-server/src/main/resources/prompts/system-v4-draft.md`](../../ontos/ontos-server/src/main/resources/prompts/system-v4-draft.md)

| 项 | 值 |
|---|---|
| 当前 v3 | `ontos/ontos-server/src/main/resources/prompts/system.md` |
| v3 SHA-256 | `671550e7a7939904afb693e2df057d3ed0809dcf8eb38c82a43e421299a3713a` |
| v3 字节数 | 6,317 |
| v4 草案 SHA-256 | `42cacbe0826fa452754d4373ab8f0c9ac2888f50decbace60a4546b99469ac47` |
| v4 草案字节数 | 10,716 |
| 增幅 | 约 69.6% 字节；运行时 token 增幅待实测 |
| 切换状态 | **未切换**；`ontos.agent.system-prompt` 仍指向 v3 |

v4 草案要点：

1. 声明自由问数支持 `query / lookup / lookup_batch / yoy_mom / funnel / retention / attribution / discovery`。
2. 模型输出位置仍是 `sub_intents[].analysis_mode`，不新增 `analysis_mode_candidates`。
3. 禁止模型输出 `analysis_params`、事件 ID、Skill 参数、物理主体属性或时间窗解析结果。
4. LOOKUP 增加布尔指标槽位 `boolean_metric_id`。
5. LOOKUP_BATCH 要求保留用户给出的标识顺序。
6. 多轮继承规则明确：先继承，再只替换用户改动的字段。
7. FUNNEL / RETENTION / ATTRIBUTION 只输出模式、指标、维度、过滤和时间表达；复杂参数由服务端补齐。

## 3. 服务端参数补全

### 3.1 新组件

新增 `ComplexAnalysisParamsResolver`（建议位于 `ontos-module-agent/service/orchestration`），在 `AskOrchestrationService.assembleDsl()` 之后、`DslValidator` 之前调用。

输入：

- `QueryDsl`
- 用户话术 / 多轮继承后的本轮话术
- `PublishedManifest`
- 本轮 `RetrievalOutcome`（用于判定事件 / 资产候选是否可见）

输出：

- 补全后的 `QueryDsl.AnalysisParams`
- 必要时清除不该出现在顶层的字段（如 FUNNEL 的 `timeExpressionRaw`）

失败语义：

| 场景 | 行为 |
|---|---|
| 事件不足 / 歧义 | 转澄清或结构化 fail-closed |
| 时间表达未命中清单模式 | `TIME_PARSE_FAILED` |
| 指标不支持归因 | `ASSET_MODE_NOT_ALLOWED` |
| 归因模型未实现 | `ATTRIBUTION_MODEL_NOT_IMPLEMENTED` |
| 留存期不完整 | `RETENTION_PERIOD_INCOMPLETE` |
| 批量标识超限 | 按现有 LOOKUP_BATCH 上限拒绝 |

### 3.2 FUNNEL

模型只给：

```json
{
  "analysis_mode": "funnel",
  "dimensions": ["dim.customer_tier"],
  "filters": [],
  "time_expression_raw": "近 90 天"
}
```

服务端补：

```json
{
  "steps": ["ORDER_PLACED", "REFUND"],
  "cohortWindow": {"timeExpressionRaw": "近 90 天"},
  "stepWindow": "7d",
  "subjectPropertyId": "property.customer.customer_id",
  "deduplication": "first_occurrence",
  "conversionDenominator": "previous_step"
}
```

规则：

1. 服务端从话术与已发布 EventDef 别名推导有序步骤；首个 v19 事件词表为：
   - `ORDER_PLACED`：下单 / 提交订单
   - `REFUND`：退款 / 退货
2. 问句顺序决定步骤顺序：
   - 「下单后……退款」→ `ORDER_PLACED, REFUND`
   - 「退款后再下单」→ `REFUND, ORDER_PLACED`
3. 识别到的事件少于 2 个时转澄清，不得默认伪造漏斗。
4. `time_expression_raw` 从 DSL 顶层移入 `analysisParams.cohortWindow`，避免违反 FUNNEL 校验。
5. 没有显式窗口时默认 `近 90 天`；默认值必须写进清单或配置，不写死在提示词。

### 3.3 RETENTION

模型只给：

```json
{
  "analysis_mode": "retention",
  "time_expression_raw": "近 180 天",
  "dimensions": []
}
```

服务端补：

```json
{
  "cohortEvent": "ORDER_PLACED",
  "returnEvent": "ORDER_PLACED",
  "periodUnit": "MONTH",
  "periods": 6,
  "subjectPropertyId": "property.customer.customer_id"
}
```

规则：

1. 首发默认 `cohortEvent = returnEvent = ORDER_PLACED`；后续扩展事件时必须来自已发布 EventDef。
2. 时间表达归一化：
   - `近半年` → `近 180 天`
   - `近 180 天` + 月留存 → `periodUnit=MONTH, periods=6`
   - `近 60 天` + 周留存 → `periodUnit=WEEK, periods=4`
   - `近 90 天` + 月留存 → `periodUnit=MONTH, periods=3`
3. 用户只说「留存」而未说周期粒度时，按观察窗长度选择默认周期：
   - `≤ 31 天` → WEEK
   - `> 31 天` → MONTH
4. `periods` 必须在 `[2, 12]`；推导越界时转澄清或 fail-closed。

### 3.4 ATTRIBUTION

模型只给：

```json
{
  "analysis_mode": "attribution",
  "metric_id": "metric.gmv",
  "dimensions": ["dim.channel"],
  "time_expression_raw": "近 30 天"
}
```

服务端补：

```json
{
  "conversionEvent": "ORDER_PLACED",
  "touchEvent": "CHANNEL_TOUCH",
  "attributionWindow": "30d",
  "model": "last_touch",
  "subjectPropertyId": "property.customer.customer_id"
}
```

规则：

1. `metric_id` 必须已在候选集，且 `allowedModes` 包含 `ATTRIBUTION`。
2. 首发转化事件默认 `ORDER_PLACED`；触点事件默认 `CHANNEL_TOUCH`。
3. 回溯窗口按封闭枚举选择：
   - `近 7 天` → `7d`
   - `近 14 天` → `14d`
   - `近 30 天` 或更长 / 非窗口型时间 → `30d`
4. 首发归因模型固定 `last_touch`；`first_touch / linear` 继续声明未实现。
5. 维度首发只放行可映射到主体 / 触点路径的维度；不可达时按现有结构化错误拒绝。

### 3.5 LOOKUP_BATCH

模型给：

```json
{
  "analysis_mode": "lookup_batch",
  "attributes": ["property.customer.tier"],
  "identities": ["c-1001", "c-1002", "c-1003"]
}
```

服务端补：

```text
entityId          = 由首个 attribute 的 conceptId 反查
identityPropertyId= concept.identityProperty
identities        = 原样保留模型给出的顺序
limit             = min(用户显式 limit, manifest skill.maxBatchSize)
```

规则：

1. 不去重、不排序，保证逐行按请求顺序对齐。
2. 编号由确定性正则从话术提取或由模型从原文复制；服务端必须校验形状。
3. 编号缺失 / 全部非法时转澄清。
4. 批量上限仍由 Skill / 策略控制。

## 4. 候选上下文

暂不把 Skill 描述符加入模型候选集。

提示词 v4 已静态声明模式可用性。若后续某模式只在特定授权 / 场景下可用，再引入独立「模式能力声明」：

```text
- mode: funnel | 语义：步骤转化 | 需要事件定义 | 不暴露 Skill 内部参数
```

该声明不得包含物理表列、SQL、Skill 实现细节或完整 `paramsSchema`。候选上下文仍只列资产 ID、名称、别名与必要的非物理约束。

## 5. 验收口径

### 5.1 单测

1. `ComplexAnalysisParamsResolver` 覆盖 FUNNEL 正序 / 倒序、事件不足澄清、时间窗移动。
2. RETENTION 覆盖月留存 / 周留存 / 周期越界。
3. ATTRIBUTION 覆盖 `7d / 14d / 30d`、指标不支持、模型未实现。
4. LOOKUP_BATCH 覆盖顺序保留、空编号澄清、上限拒绝。
5. 布尔 LOOKUP 覆盖 `ruleId` 型指标进入 LOOKUP，普通指标仍走 QUERY。

### 5.2 小批量复测

1. 复测失败族：FUNNEL 4、RETENTION 3、ATTRIBUTION 4、LOOKUP_BATCH 2、LOOKUP bool / batch 相关失败、多轮与复合抽样。
2. 并发 1、请求间隔 5000ms、熔断开启、checkpoint 开启。
3. 运行前冻结：清单 v19 或新版、提示词 v4、runner v2.1+、planner 修复版、候选域 `retrieval`、一次性干净工作区。
4. 每题同时检查 `terminal` 与 `mode`。
5. 结果只作诊断；不解除 C-1。

### 5.3 成本观测

| 指标 | 要求 |
|---|---|
| prompt 长度 | 记录 v3 / v4 字节数与 token 估算 |
| 单题 token | 与 mimo v1 诊断对比 |
| 通过率 | 按失败族分层报告 |
| 降级 | `degraded` / fallback 单列 |
| 超预算处置 | prompt 增幅 >20% 或单题均值 >8k 时，评估拆分提示词或动态模式声明 |

## 6. 非目标

1. 不允许模型生成 SQL、物理表列、Join、绝对日期。
2. 不允许模型输出 `analysisParams` 或事件 ID。
3. 不放宽 RLS、审批、敏感查询、跨轮累计与成本闸门。
4. 不在草稿阶段直接切换生产提示词。
5. 不用本规格替代 test 集 v5 全量 ×3 的 C-1 验收。

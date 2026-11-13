# ontology · 图谱探索页 规格

> **状态**：草稿
> **版本**：v0.1
> **类型**：规格
> **负责人**：待定（项目负责人定档 D1–D5）
> **最后更新**：2026-11-13
> **适用范围**：`ontos-ui/apps/web` 新增只读本体图谱探索页；`ontos/ontos-module-ontology` 新增只读聚合端点。不改动已发布清单结构、DSL、`OntologyPathPlanner`、`JoinPathResolver` 与 `SqlCompiler`
> **上游依据**：[完整可落地方案 v1.4 §1 / §7.3 / §7.5](../02-solutions/architecture/智能问数系统-完整可落地方案-v1.4.md)、[技术选型 v1.1 §4.6 / 附录 A](../02-solutions/architecture/智能问数系统技术选型方案-v1.1.md)、[业务化命名规格 v1](ontology-业务化命名-规格-v1.md)、[roadmap v1 §M1 / R5 / M8](../03-plans/roadmap/Ontos开发计划-阶段性功能拆解-v1.md)、[M8 产品前端实施规格 v1.1](../03-plans/stages/M8/产品前端-实施规格-v1.1.md)、[ADR-0003 fail-closed 判据清单](../01-decisions/ADR-0003-fail-closed判据清单.md)、[ADR-0024 前端表单库生效解释](../01-decisions/ADR-0024-前端表单库选型修订TanStackForm生效解释.md)、[工程约定](../../AGENTS.md) §4 / §6、[前端工程规范](../../ontos-ui/FRONTEND_RULES.md)
> **修订记录**：2026-11-13 v0.1 初稿（负责人定档：交付形态 = 设计文档；页面定位 = 结构浏览 + 路径寻路）

---

## 1. 适用范围

**目标**：为业务用户提供只读页面，浏览**当前生效清单**里的本体结构（业务对象 / 业务关联 / 信息项），并在图上选两个业务对象做**两点寻路**，理解「这些对象怎么连、一个问题会跨哪些对象」。数据来源是已发布清单的确定性投影，不经 LLM、不查业务数据、不产出 SQL。

**本规格定义的契约**：页面信息架构与交互三态、只读 API 的请求/响应字段白名单、身份与授权裁剪规则、fail-closed 拒绝条件、前端工程落点与验收清单。

**非目标（明确不做）**：

- 本体/资产的编辑、草稿、合成、发布、激活；任何写操作。
- 「业务可问能力地图」（从对象看可问指标/维度/场景/示例问法）——需另立聚合端点与规格，归后续工作包。
- 「图谱多跳召回」——与向量 + BM25 / rerank 同属 [roadmap v1](../03-plans/roadmap/Ontos开发计划-阶段性功能拆解-v1.md) 的 M8 条件触发项，不在本页范围。
- LLM 参与图构建、任意 SQL / 物理表名 / 列名展示、大规模力导向图、移动端适配、国际化。
- 修改 [技术选型 v1.1](../02-solutions/architecture/智能问数系统技术选型方案-v1.1.md) 附录 A 的冻结选型，或引入新的图可视化依赖。
- 改动任何已冻结文档；与 M8 规格的口径差异走「升版或 ADR 生效解释」，不原地改。

---

## 2. 术语

术语以 [业务化命名规格 v1](ontology-业务化命名-规格-v1.md) 为准，本规格不重复定义：业务对象（Concept）、信息项（Property）、业务关联（Relation）、数量关系文案、资产 ID 与文案分离。补充两个本页专用词：

| 词 | 含义 |
|---|---|
| 图谱投影 | 从 `PublishedManifest` 的 `OntologySnapshot` 中按字段白名单抽取、可直接给前端渲染的只读结构 |
| 寻路三态 | `UNIQUE` / `AMBIGUOUS` / `NONE`，语义与 `OntologyPathPlanner.PathStatus` 一致；并列合法路径不得私自选择 |

---

## 3. 结构定义

### 3.1 页面归属与信息架构

| 项 | 约定 |
|---|---|
| 路由 | `/ontology/graph`（复用现有 `app/ontology/` 域；见待决 D1） |
| 导航 | `app/layout/navigation.ts` 新增一项，建议归「语义建模」组，与「本体建模」相邻 |
| 可见性 | 全部登录用户只读；不引入管理面能力（不违反 M8「管理面功能增强」非目标） |
| 页面结构 | ① 顶部上下文条 → ② 图谱画布（主区）→ ③ 选中详情面板 → ④ 寻路面板 |

**顶部上下文条**：展示当前生效清单的**业务化版本标识**与更新时间；`manifest_id` / `contentHash` 只作为 query key、缓存键与回传值，**不渲染为可见文本**（[业务化命名规格 v1](ontology-业务化命名-规格-v1.md) §4，附录 A.4 第 3 条）。

**图谱画布**：节点 = 业务对象（`id/name/summary/aliases/domain`），有向边 = 业务关联（`id/name/from/to/cardinality/...`）。首版用分层/环形确定性布局，不做力导向；支持点击选中、键盘导航、缩放与搜索定位。边文案按规格渲染为「对象A —动词→ 对象B（数量关系文案）」，`goldenPath` / `queryable` 用形状与状态标记区分，**不用颜色区分类别**。

**详情面板**：选中节点显示业务名、摘要、别名、所属域、信息项业务名列表；选中边显示业务关联文案、数量关系文案、是否可用于查询、是否已审核路径。两者都不得出现物理列、内部 ID 或后端实现术语。

**寻路面板**：起点/终点选择器（业务名下挂 ID 作回传值）→ 查询 → 三态呈现：

| 状态 | 呈现 |
|---|---|
| `UNIQUE` | 逐跳列出「对象 —动词→ 对象」，在画布上高亮该路径；标注跳数与是否为已审核路径 |
| `AMBIGUOUS` | 并列展示全部候选（最多见 §4 上限），**明确提示需要澄清，不默认选中第一条** |
| `NONE` | 明确说明「当前授权范围内没有合法路径」，不降级猜测、不返回部分路径 |

选中节点/边、起终点、当前场景等可共享状态放 URL（`useSearchParams`），不建平行的组件内身份状态（[前端工程规范](../../ontos-ui/FRONTEND_RULES.md) §State）。

### 3.2 后端只读 API 契约

现有端点不足以支撑本页：`GET /api/v1/ontology/assets` 的 relations 只有 `id/name`（无 `from/to`，无法连边）；`GET /api/v1/ontology/paths/candidates` 是探针/调试端点（无身份闸门、无规模上限、`domains` 由请求参数传入、无业务名）；`GET /api/v1/catalog/assets/options` 是草稿编辑候选且被管理面闸门保护。因此**新增只读聚合端点**，前端只调该端点族，不直连旧调试端点。

#### `GET /api/v1/ontology/graph`

| 项 | 约定 |
|---|---|
| 入参 | `scenarioId`（可选）：按已发布场景的 `ontologyScope` 收窄；必须校验场景存在、已发布、当前身份授权可见 |
| 禁止入参 | `manifestId`、`domain`、`tenantId`、`userId`、`roles`、`maxHops`。清单强制取 active，身份与授权域只来自认证上下文，跳数上限服务端固定 |
| 响应 | `CommonResult<OntologyGraphRespVO>`，字段见下表；**服务端按白名单投影，禁止整体序列化 `PublishedManifest` / `OntologySnapshot`** |
| 位置 | `controller.admin.ontology` → `service.ontology` → `core`，依赖方向遵守 [工程约定](../../AGENTS.md) §2 与 ArchUnit 规则 |

```json
{
  "ontologyVersion": "<业务化版本标识>",
  "contentHash": "<仅用于 query key / 缓存键，不上屏>",
  "nodes": [
    { "id": "concept.order", "name": "订单", "summary": "...", "aliases": ["..."], "domain": "sales", "propertyCount": 6 }
  ],
  "edges": [
    { "id": "rel.order.customer", "name": "属于", "reverseLabel": "被…下单",
      "from": "concept.order", "to": "concept.customer", "cardinality": "N:1",
      "cardinalityLabel": "每笔订单对应一个客户", "queryable": true, "goldenPath": true }
  ],
  "capabilities": { "maxHops": 3, "costFunctionVersion": "path-cost-1.0" },
  "scopes": [
    { "scenarioId": "scenario.sales", "scenarioName": "销售", "rootConcepts": ["..."],
      "allowedRelations": ["..."], "maxPathHops": 3, "excludedConcepts": [] }
  ]
}
```

**字段白名单（只允许这些）**：`Concept.{id,name,summary,aliases,domain}` + 派生 `propertyCount`；`Relation.{id,name,reverseLabel,from,to,cardinality,queryable,goldenPath}` + 派生 `cardinalityLabel`；`ScenarioMeta` 的 `ontologyScope` 子集；`manifest` 的 `ontologyVersion/contentHash`；`OntologyPathPlanner` 的 `DEFAULT_MAX_HOPS/COST_FUNCTION_VERSION`。

**明确排除（一个都不出接口）**：`Property.column` / `viewId`、`Relation.fromColumn` / `toColumn`、`Metric.table` / `column`、`DataView` 物理定义、`payloadJson`、任何 SQL / 绑定值 / 物理表名。依据见 [工程约定](../../AGENTS.md) §4 与 [业务化命名规格 v1](ontology-业务化命名-规格-v1.md) §4；现有管理面建模编辑器处理物理列属作者面例外，不得作为本页先例。

#### `GET /api/v1/ontology/graph/paths`

| 项 | 约定 |
|---|---|
| 入参 | `from`、`to`（业务对象 ID，必填）；`scenarioId`（可选，与上图同源）。**不接受 `domains` / `maxHops`** |
| 服务端 | `RequestContextHolder.require()` → 身份→授权域 → `OntologyPathPlanner.plan(from, to, domains, DEFAULT_MAX_HOPS)`；`from`/`to` 必须存在于本次授权裁剪后的节点集，否则拒绝 |
| 响应 | `CommonResult<PathPlanVO>`：`status` / `candidates[{edges[{relationId,direction,from,to,cardinality}],hops,allGolden}]` / `reason`；`edges` **不含物理列** |
| 复用 | 与编译侧 `JoinPathResolver` 同一规划器，保证「图上看到的路径」与「编译实际采用的路径」同源 |

**旧端点的处置**：`GET /api/v1/ontology/paths` 与 `/paths/candidates` 保留但**不得被本页调用**；其无身份闸门、`domains` 请求参数、无规模上限三项风险登记为待决 D5，另行收口（见 §8）。

### 3.3 fail-closed 与校验条件

| 条件 | 行为 |
|---|---|
| 缺身份 / 上下文为空 | 整单拒绝 `UNAUTHORIZED`，不回退 demo 身份 |
| 无生效清单 / 清单哈希不匹配 | 整单拒绝，不降级到旧清单 |
| `scenarioId` 不存在 / 未发布 / 不在授权范围 | 整单拒绝，不返回「未裁剪的全域图」 |
| 授权域为空 | 返回**明确的空授权图**（不是「全域」），前端按空态呈现 |
| `from` / `to` 不在授权节点集 | 整单拒绝，不做隐式路径推导 |
| 节点/边超出规模上限 | 整单拒绝并给出可扩展提示，**不截断、不返回部分结果** |
| 规划返回 `AMBIGUOUS` | 原样返回全部候选，服务端与前端都不私自选择 |
| 错误码 | 复用现有 `1_001_xxx_xxx` ontology 段已发布码值；**不新增专用码**，实施规格对齐 `ErrorCodeConstants` |

规模上限建议值：节点 ≤ 500、边 ≤ 1000、单次寻路候选 ≤ 20、`maxHops` 恒 3（与 `OntologyPathPlanner.DEFAULT_MAX_HOPS` 一致）。数值待 D4 定档，实施时以发布清单实测为准。

**身份→域映射是上线硬前置**：现有 `AuthorizedDomainsSupplier` 由静态配置注入（`policyProperties.getAuthorizedDomains()`），尚未真正按 tenant / roles 映射。若不做闭环，本页等同于「全域可见」，与 [工程约定](../../AGENTS.md) §4 第 3 条冲突。见待决 D3。

**审计**：首版不新增审计写入——端点只返回已发布清单的业务字段投影，无敏感字段、无业务数据、无查询执行；与现有 `GET /api/v1/ontology/assets` 口径一致。若后续接入「按角色裁剪」或业务数据联动，必须重新评估并遵守「审计写入失败即整单失败」。

### 3.4 前端工程落点

| 层 | 文件 / 约定 |
|---|---|
| 页面 | `apps/web/app/ontology/graph/page.tsx`（kebab-case）；同域拆 `graph-canvas.tsx` / `graph-detail.tsx` / `path-finder.tsx` / `graph-layout.ts`（纯函数布局，配单测） |
| 服务层 | `service/ontology/index.ts` 增 `getOntologyGraph()` / `findGraphPath()`；`service/ontology/types.ts` 增 VO 类型。页面不得直连 `service/http`、不得出现 `fetch(` |
| 路由 / 导航 | `app/router/AppRouter.tsx` 增 Route；`app/layout/navigation.ts` 增导航项 |
| 架构检查 | `scripts/architecture-imports.mjs` 的 `publicPageEntries` 登记新页，并补 `architecture-imports.test.mjs` 正反例（[前端工程规范](../../ontos-ui/FRONTEND_RULES.md) §Testing） |
| 数据 | TanStack Query 共享缓存；query key 必带租户/用户作用域 + `contentHash` + `scenarioId` + 全部查询参数。失败/未知状态不得伪装成空图 |
| 状态 | 选中项、起终点、场景放 URL；不因组件嵌套引入 Zustand |
| 请求 | 只走 `/api/v1` 相对路径，身份头由 transport 统一注入，页面不伪造 |
| 组件 | shadcn/ui + Radix 的 Sheet / Tabs / Tooltip / Badge / ScrollArea 等，不自建基础控件 |

**图渲染方案（待决 D2，给出推荐）**：推荐**自研 SVG**——零新依赖、可直接用 `var(--*)` 语义 token 着色（零 `#hex` 字面量，天然通过 `verify-design-tokens.mjs`）、节点规模几十到百级无需力导引擎、路径高亮与键盘可达性可控。备选是已装 ECharts 的 `GraphChart`，但 Canvas 取色需 `getComputedStyle` + hex 兜底，必须在 `verify-design-tokens.mjs` 白名单登记并写明「canvas 不消费 Tailwind 类」理由，且不得把 hex 挪到 inline style 绕过检查。引入 `@xyflow/react` / `d3` 等新库属于改动冻结选型，需按附录 A 立决策载体，本规格不推荐。

**颜色约束**：首版只用现有 `--foreground` / `--muted-foreground` / `--border` / `--primary` / `--primary-subtle` / `--warning` / `--success`；按域或类型区分改用形状、描边与状态标记，**不新增多类别色板**（如需新增语义 token，须同时满足 `verify-primary-color-tokens.mjs`）。支持 light/dark 与 `prefers-reduced-motion`。

---

## 4. 校验规则

| # | 规则 | 验证方式 |
|---|---|---|
| V1 | 响应体不含 `column` / `table` / `fromColumn` / `toColumn` / `payloadJson` / `sql` 等物理字段 | 后端 VO 白名单单测 + 响应 JSON 字段扫描断言 |
| V2 | 无身份、无清单、未授权场景、越界 `from/to`、超规模均整单拒绝 | Controller / Service 单测逐条覆盖，断言「无部分结果」 |
| V3 | `AMBIGUOUS` 不私自选择，`NONE` 不降级猜测 | 规划结果映射单测，复用现有规划器用例 |
| V4 | 页面只显示业务名，内部 ID 不作可见文本 | 前端组件测试断言可见文本；UI 文案扫描 |
| V5 | 失败/加载/空态三态分离，不把错误伪装成空图 | 组件边界测试（loading / error / empty） |
| V6 | query key 含租户/用户作用域与全部参数 | service 层单测 |
| V7 | 请求方向 `app → service/<域> → service/http`，无 `fetch`、无硬编码颜色 | `pnpm check`（Oxfmt / Oxlint / tsc / architecture / contract） |
| V8 | 相对链接可解析、命名含版本、统一头块齐全 | `node docs/00-governance/check-docs.mjs` |
| V9 | 全量回归 | [工程约定](../../AGENTS.md) §7：`mvn test`（含 ArchUnit）、`pnpm check && pnpm build`、`check-docs.mjs`、`check-parallel-obligations.mjs`、`verify-baseline.mjs` |

---

## 5. 版本与兼容

- **清单结构零改动**：不新增表、不改 `OntologySnapshot` / `SemanticManifest` / `PublishedManifest`、不动 DSL 与编译器；端点只是只读投影。
- **与 B0 双形态兼容**：投影只取 `Concept` / `Relation` 业务字段，与 v19 物理形态 / v20 语义形态无关；`BindingResolver` 不参与。
- **与现有页面兼容**：新增路由与导航项，不改 `/ontology/{modeling,manifests,assets,definitions,diff,records}` 六页。
- **清单切换**：`contentHash` 进 query key，activate 后自动失效重取，不展示陈旧图。
- **调试端点兼容**：旧 `/ontology/paths*` 保留原语义，仅本页不调用；收口走独立决策（D5）。

---

## 6. 变更影响

| 对象 | 影响 | 本规格是否改动 |
|---|---|---|
| `ontos-module-ontology` | 新增只读 Controller / Service / VO；复用 `OntologyPathPlanner`；不改 core 规划算法 | 否（实施阶段改） |
| `ontos-module-system` / server 装配 | 身份→授权域映射端口实现（D3） | 否 |
| `ontos-ui/apps/web` | 新增 `app/ontology/graph/` 页面族；`service/ontology/` 增端点与 VO | 否 |
| `ontos-ui/apps/web/app/router`、`app/layout/navigation.ts` | 增 Route 与导航项 | 否 |
| `ontos-ui/scripts/architecture-imports.mjs` + 其测试 | `publicPageEntries` 登记 + 正反例 | 否 |
| `ontos-ui/scripts/verify-design-tokens.mjs` | 仅当 D2 选 ECharts 时登记渲染文件（带理由）；选自研 SVG 则不动 | 否 |
| `docs/README.md` §9、`04-specs/README.md` | 登记本规格 | **是**（本次已同步） |
| `docs/05-progress/2026/2026-11-13.md` | 追加进度快照 | **是**（本次已同步） |
| 冻结文档（技术选型 v1.1 / 附录 A、M8 规格、业务化命名规格） | 不原地改；口径差异走升版或 ADR | 否 |

---

## 7. 验收清单

- [ ] D1–D5 全部由负责人定档，并写入实施规格；草稿 v0.1 升版冻结后方可编码。
- [ ] D3（身份→授权域映射）闭环并有单测证据；未闭环不得上线。
- [ ] `GET /ontology/graph` 与 `/ontology/graph/paths` 字段白名单经 V1 断言，响应中无任何物理名。
- [ ] V2 六类拒绝条件与 `AMBIGUOUS` 原样返回逐条有单测，且断言「无部分结果」。
- [ ] `UNIQUE` / `AMBIGUOUS` / `NONE` 三态在页面与 API 两侧表现一致，`AMBIGUOUS` 不默认选中。
- [ ] 页面可见文本零内部 ID、零物理名、零后端术语；边文案符合「对象A —动词→ 对象B（数量关系文案）」。
- [ ] URL 状态、`useSearchParams`、query key 作用域、加载/空/错误三态经组件测试覆盖。
- [ ] `pnpm check`、`pnpm build`、`mvn test`、`check-docs.mjs`、`check-parallel-obligations.mjs` 全绿；浏览器实测记录键盘可达、light/dark、reduced-motion。
- [ ] 归档链接与总索引一致；进度快照带并行义务点名。

---

## 8. 待决项、风险与既有欠账（附加章节）

> 按 [规格模板](../00-governance/templates/规格-模板.md) §1–§7 起稿；§8 为设计规格附加的决策与风险登记，不构成新契约。

### 8.1 待决项（负责人定档前不得编码）

| # | 事项 | 建议 | 触发点 |
|---|---|---|---|
| D1 | 页面归属与角色可见性：M8 规格把 `/ontology/*` 归「治理后台·按角色」，本页定位「全部登录用户业务探索」 | 维持 `/ontology/graph` +「语义建模」组，页面只读不新增管理面能力；M8 规格升版或 ADR 生效解释同步分组口径 | 实施规格冻结前 |
| D2 | 图渲染实现 | **自研 SVG**（即 [M8 规格 v1.1](../03-plans/stages/M8/产品前端-实施规格-v1.1.md) D5 候选 c）：零新依赖、零 hex、token 天然合规，且不是「新增图表库」，不触碰附录 A 冻结选型；ECharts `GraphChart` 为备选但需白名单登记。注意 M8 D5 建议 a（Recharts）与附录 A / 实际代码 ECharts 已有口径漂移，本决策须连带消解 | 实施规格冻结前 |
| D3 | 身份→授权域映射（现为静态配置） | 上线硬前置，须按 `tenant + roles` 从认证上下文推导，禁止请求参数传入 | **上线前必须闭环** |
| D4 | 规模上限数值（建议 500 / 1000 / 20 / 3） | 实施时按发布清单实测确认后定档 | 实施规格冻结前 |
| D5 | 旧 `/ontology/paths` 与 `/paths/candidates` 收口（无身份闸门、`domains` 请求参数、无规模上限） | 本页不调用；另立 ADR 决定「加固」或「下线调试端点」 | 本页上线前 |

### 8.2 风险

| 风险 | 处置 |
|---|---|
| 页面退化成「目录浏览」（roadmap R5） | 寻路作为一等交互，路径高亮与 JOIN 同源；验收清单覆盖三态 |
| 画布颜色触发 `verify-design-tokens.mjs` | D2 推荐零 hex 的 SVG；若用 Canvas 必须白名单登记并写明理由，禁止 inline style 绕过 |
| 新增页面被 `architecture-imports.mjs` 判「imports a private feature」 | 同步登记 `publicPageEntries` 并补正反例测试 |
| 复用调试端点导致越权或全域可见 | 前端只调 `/ontology/graph*`；旧端点登记 D5 |
| 内部 ID 上屏（现有 `manifests` / `diff` / `records` 页既有漂移） | 新页不沿用；既有漂移另登记为欠账，不在本规格内扩面 |

### 8.3 既有欠账点名（不在本规格闭环）

- `GET /api/v1/ontology/assets` 的 relations 无 `from/to`，无法连边 → 本规格以新增聚合端点绕开。
- `OntologyPathController` 全系只读端点无 `RequestContextHolder.require()`、无 dev 闸门、无 tenant/roles 裁剪；类注释自认「真实身份→域映射由 M4 接管」但装配层仍为静态配置 → D3 / D5。
- `FRONTEND_RULES.md:14` 头名写 `X-Ontos-Tenant-Id`，实现为 `X-Ontos-Tenant` → 既有规范漂移，transport 以实际实现为准。
- [M8 产品前端实施规格 v1.1](../03-plans/stages/M8/产品前端-实施规格-v1.1.md) D5 建议 Recharts，与附录 A / 实际代码的 ECharts 不一致 → 本规格不引入新库，不加剧该漂移。
- **上一轮图实现核查发现（另立工作包，不在本规格闭环）**：`OntologyGraph`（JGraphT 图 + 自研 `shortestPath()` BFS）构建后无运行时消费者；v1.4 §7.3 三级优先级的「显式用户指定关系」未实现（现为 `allGolden > hops > 关系链`）；§5.3 关系声明字段（有效时间 / 敏感等级 / 逐条跳数上限 / Golden 正反向样例）被简化；两条 P2 遗留（路径枚举组合爆炸改 top-K、规划器逐请求重建改缓存）只登记未闭环；pgGraph/AGE → 进程内快照这一不可逆选型变更无专门 ADR。本页只读投影**不依赖**上述任一项，但「图上路径与编译路径同源」的长期一致性受其影响，建议与 D5 合并收口。
- `docs/README.md` §9 自表版本 v2.59 与头块 v2.71 不一致 → 既有索引漂移，本轮只做索引维护未升版本。

### 8.4 并行义务点名

OBL-M4 / OBL-M7 维持 `done`，`deferred 0`。本规格为独立设计工作包，不新增、不关闭、不重定任何并行义务；未引入新的「同步建设 / 不许后置」约束，故不改动 `00-governance/parallel-obligations.json`。

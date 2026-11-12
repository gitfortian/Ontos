# AGENTS.md — Ontos 工程约定

本文件是代码仓库内的开发约定，任何改动都必须遵守。技术选型以 `docs/02-solutions/architecture/智能问数系统技术选型方案-v1.1.md` 为准；其中**前端表单/状态行以 [ADR-0024](docs/01-decisions/ADR-0024-前端表单库选型修订TanStackForm生效解释.md) 的生效解释为准**（TanStack Form + Zod），技术选型正文不动。

## 1. 工程形态

- 两个顶层工程：`ontos`（后端，Maven 多模块）、`ontos-ui`（前端，Vite/React/TS）。
- 后端**是单进程单体部署**，`ontos-server` 是唯一可打包、可启动的模块；多模块只用于隔离组件边界，不是微服务。
- 版本基线：**JDK 21 / Spring Boot 4.0.x（≥4.0.3）/ AgentScope Java 2.1.0-BETA1 / PostgreSQL 18**。
  AgentScope starter 的编译基线是 Boot 4.0.x（读 Maven Central POM 核实），不要再按 Boot 3.x 写依赖。

## 2. 后端模块与依赖方向

```
ontos-server            → 所有模块（唯一装配点）
ontos-module-query      → ontos-module-ontology, ontos-module-system, ontos-framework/*
ontos-module-ontology   → ontos-common, ontos-spring-boot-starter-jdbc
ontos-module-system     → ontos-common, ontos-spring-boot-starter-{web,jdbc}
ontos-framework/*       → 不得依赖任何 ontos-module-*
```

`ontos-server/src/test/java/com/ontos/architecture/ArchitectureTest.java` 用 ArchUnit 守护以上方向，
提交前必须通过 `mvn test`。新增跨模块依赖前先改规则并说明理由，不要用「先跑通再说」绕过。

## 3. 包结构与命名（对齐 yudao-boot-mini）

每个业务模块内部固定分层，不得把代码全塞进一个包：

| 层 | 包 | 职责 |
|---|---|---|
| Controller | `controller.admin.<域>` | HTTP 入参/出参，只调用 service |
| VO / DTO | `controller.admin.<域>.vo` / `service.<域>` | 传输对象，不与 DO 混用 |
| Service | `service.<域>` | 业务编排，接口 + `Impl` 分离（复杂域） |
| DAL | `dal.dataobject` / `dal.mysql` | DO 与 Mapper/Repository，SQL 只出现在这里 |
| Core | `core` | 纯领域逻辑与确定性算法，不感知 Web |
| 错误码 | `enums.ErrorCodeConstants` | 模块私有错误码 |

统一返回 `CommonResult<T>`、分页 `PageResult<T>`、业务异常 `ServiceException` + `ServiceExceptionUtil`、
JSON 工具 `JsonUtils` 均**直接照搬 yudao-boot-mini**（`cn.iocoder.yudao.framework.common`），
只在文件头注明来源与必要改动（如 Jackson 2 → Jackson 3、javax → jakarta）。
**通用工具类不要自行发挥**，遇到需求先去 yudao 对应实现里找。

错误码分段：`0-999` 全局保留；`1_001_xxx_xxx` ontology；`1_002_xxx_xxx` query；`1_003_xxx_xxx` system。
新增错误码只能追加，不得复用或修改已发布码值。

## 4. 安全与确定性约束（不可协商）

1. **LLM 不产出 SQL**：自然语言 → 类型化 DSL → `DslValidator` → `SqlCompiler` → `QueryExecutor`。
   DSL 只引用已发布资产 ID；物理表名、列名、Join 条件只能来自 `SemanticManifest`。
2. **fail-closed**：缺身份、缺清单、哈希不匹配、策略未放行、跨表路径缺失时整单拒绝，不返回部分结果、不降级猜测。
3. **身份只来自认证上下文** `RequestContextHolder.require()`，绝不接受请求体或用户文本中的身份。
   `ontos.web.security.dev-headers` 与 `ontos.policy.dev-allow-all` 仅限 `local` profile，生产必须 `false`。
4. **发布清单不可变**：同一 `manifest_id` 内容永不更新；每次查询锚定 `contentHash`。
5. **Query DS 只读**：`ontos_query_ro` + 连接只读 + RLS 三重约束；执行器只接受编译产物，不接收任意 SQL。
6. **审计写入失败即整单失败**（合规优先于可用性）。

## 5. 序列化

Spring Boot 4 默认 JSON 栈是 **Jackson 3（`tools.jackson.*`）**，但注解包仍是 `com.fasterxml.jackson.annotation`，
且 Flyway / AgentScope 会传递 Jackson 2 的 databind。业务代码：
- 使用 Spring 注入的 ObjectMapper / `JsonUtils`，**不要自行 `new com.fasterxml.jackson.databind.ObjectMapper()`**；
- 工具类统一用 `tools.jackson.databind.ObjectMapper`，由 `JacksonConfiguration` 初始化。

## 6. 前端约定

### 6.1 两套边界不要混淆

| | 权威来源 | 约束内容 |
|---|---|---|
| **工程规范（参考 yak-ops）** | `ontos-ui/FRONTEND_RULES.md`（迁移自 `yak-ops-ui/FRONTEND_RULES.md`） | 目录分层、`service/http` 三层、状态与请求原则、命名、Lint / 格式化、架构边界、测试方式 |
| **框架与组件（用我们的选型）** | `docs/02-solutions/architecture/智能问数系统技术选型方案-v1.1.md` **附录 A**（表单行以 [ADR-0024](docs/01-decisions/ADR-0024-前端表单库选型修订TanStackForm生效解释.md) 为生效解释） | shadcn/ui + Tailwind v4 + Radix、CopilotKit Headless（AG-UI 协议）、TanStack Table / ECharts / Monaco、**TanStack Form + Zod（Standard Schema 直连，不引入 resolver）**、TanStack Query、Zustand、Vite + TypeScript + pnpm；纯静态产物由 Spring Boot 单体托管，**不引入独立前端服务** |

**任何前端改动前必须先读**：`ontos-ui/FRONTEND_RULES.md`、`ontos-ui/docs/tooling.md`、本节、技术选型附录 A。
规范只约束「怎么写」，不决定「用什么组件」；**组件实现不得照搬来源项目的 `@base-ui/react` 组件库**。
`ontos-ui/scripts/` 下的架构检查与设计 token 脚本是可执行约束，禁止为了跑通而放宽。

- 目录：workspaces `apps/web`（产品）+ 共享 UI 包；`apps/web` 没有 `src/`，按 `app/`、`service/`、`context/`、`hooks/`、`config/`、`constants/`、`types/`、`utils/`、`themes/` 分层。
- 请求方向固定 `app/<域>` → `service/<域>` → `service/http`；transport 集中在 `service/http/request.ts`，页面不得自己发请求或解析 HTTP 信封。
- 服务端数据一律放 TanStack Query 共享缓存，不用 `useState + useEffect` 自建缓存；query key 必须带租户/用户作用域与全部参数。
- 样式用 Tailwind v4 + 选定设计系统的语义 token，禁止硬编码颜色，内联 style 只承载运行时动态值。
- 工具链是 Oxlint / Oxfmt / TypeScript / Node Test Runner（`*.test.mjs` 与被测模块同目录）；不用 ESLint / Prettier / axios。
- 组件与文件名：`app/**` 用 kebab-case，共享 UI 组件用 PascalCase；不建投机性的 `common` / `shared` 层。
- 只认相对路径 `/api/v1` 与 `/agent/ontos`，由 Vite 代理或生产网关注入，不内置后端地址。
- AG-UI 使用协议标准事件（`RUN_*` / `TEXT_MESSAGE_*` / `TOOL_CALL_*` / `CUSTOM` / HITL / state），不自研 SSE 协议。
- 不得用模拟数据把请求失败伪装成成功或空结果；身份头由 transport 统一处理，页面不得自行伪造。
- 前端日志 / trace / 错误上报必须先过滤 `RUN_STARTED.input`（附录 A A.4 三条安全约束）。

> **归位状态（W5）**：`packages/ontos-ui` 已按附录 A 重写为 shadcn/ui + Radix，包管理已切 pnpm workspaces，
> 来源组件库与设计 token 已清除，架构检查全局禁止 `@base-ui/react` 与 `--yak-*` 回流（见 ADR-0001）。
> AG-UI 侧在 PoC #10 验收前保持可运行 SSE 实现，验收通过后再切 CopilotKit Headless。

### 6.2 任务收口：深度审核（不可跳过）

用户 2026-11-08 明确要求「永远记住」，任何 agent 接手都适用：

- **每完成一个任务**（子任务、单个工作流、单步实现）立即做一次深度审核，不得只复述「已完成」。
  审核维度：改动与需求/规格一致、代码逻辑与边界（尤其 fail-closed、身份、物理名来源）、
  测试真实执行且断言有效、文档与索引同步、提交范围与工作区干净、
  **并行义务同步建设核对**（见 §6.4：所有并行 / 同步建设项是否逐项点名、欠账是否登记）。
- **整个大任务**（阶段 / 里程碑 / 一批任务）结束后，再做一次覆盖全局的深度审核：
  子任务间契约一致性、全量回归基线（`mvn test` / `pnpm check` / `check-docs.mjs` / 构建）、
  文档状态与真实进度是否一致、遗留项是否登记。
- 发现问题**当场修复并复核**，未闭环不得宣告完成；审核结论留痕于 `docs/05-progress/<年>/`（append-only），
  并在回复中给出实质发现、修复内容与未闭环项。
- 「按计划自动继续、不用每步询问」的授权**不覆盖**本条，自动推进不等于跳过审核。

### 6.3 功能代码开发：核心类逻辑介绍（不可省略）

用户 2026-11-08 要求「记住」，任何 agent 接手都适用：

- 凡**功能代码开发**任务结束，在最终回复中必须额外输出一小段「主要核心类逻辑与功能」介绍：
  点明本任务涉及的核心类、各自职责、关键逻辑链路（谁调用谁、数据怎么流），
  以及这批代码整体实现了什么能力。
- 要求简短（一小段或紧凑清单），**不要**长篇罗列改动文件、不要用「详见代码」代替，
  也**不能**与深度审核结论混在一起——两者都必须在回复中出现。
- 纯文档、纯配置、纯运维动作不适用；只要涉及 `src/main/**` 的新增或修改就适用。

### 6.4 并行义务纪律（不可跳过）

用户 2026-11-09 要求「把纪律规范起来，不能漏掉不做」。roadmap 里所有
「并行 / 同步建设 / 不许后置」类约束（当前为 M4、M7）与主线阶段同等受跟踪，
**状态栏写「待启动」不等于可以略过**：

- **阶段收口必查**：每个阶段的验收报告、收口深度审核、进度快照必须逐项点名全部并行义务；
  已交付写证据链接，未交付写欠账登记（归属阶段、触发点、负责人、证据），
  并同步 `docs/00-governance/parallel-obligations.json`。**未点名不得宣告阶段关闭**。
- **状态只有两种**：`done` / `deferred`；禁止 `skipped` / `n/a` / 删除条目。
  `deferred` 必须写清触发阶段；触发阶段一旦通过验收而义务未闭环即判违规。
- **机器强制**：`node docs/00-governance/check-parallel-obligations.mjs` 从 roadmap 自动抽取义务，
  校验台账与证据、触发是否过期、最近进度快照是否点名、阶段状态与验收报告是否一致，
  并接入 `scripts/verify-baseline.mjs`。任一违规即 `FAIL`；**禁止为跑通而放宽脚本或删改 roadmap 硬约束文本**。
- **口径不降级**：并行义务未按「自 M1 起同步建设」执行时，用 ADR 登记偏离与闭环触发点
  （见 [ADR-0014](docs/01-decisions/ADR-0014-并行义务纪律与M7同步建设偏离.md)），
  不得事后改计划追认，不得把主线顺带产生的素材算作并行阶段交付物。

## 7. 提交前检查

```bash
cd ontos && mvn test                        # 含 ArchUnit 架构规则
cd ontos-ui && pnpm check && pnpm build
node docs/00-governance/check-docs.mjs     # docs 治理：层级、命名、链接
node docs/00-governance/check-parallel-obligations.mjs  # 并行义务与阶段状态（§6.4）
node scripts/verify-baseline.mjs            # 五类基线检查 + jar 启动探活（M0 T1.2）
```

新增依赖的版本依据一律以 Maven Central POM / npm registry 实测为准，不采信第三方博客坐标。
修改技术选型文档时同步更新 `docs/` 内全部相关表格与修订记录。

## 8. 文档规范

**唯一入口：[`docs/README.md`](docs/README.md)（文档规范与总索引）**。任何 agent 在新增、修改、查找文档前必须先读。

- **分层**：`00-governance` 治理 / `01-decisions` 决策 ADR / `02-solutions` 方案 / `03-plans` 计划 / `04-specs` 规格 / `05-progress` 进度 / `06-verification` 验证 / `07-references` 参考 / `99-archive` 归档。
  `docs/` 根目录除 `README.md` 外**不得出现任何 `.md`**；层级上限 3 层；每个一级目录必须有 `README.md`。
- **起稿**：从 `docs/00-governance/templates/` 复制对应模板（方案 / 计划 / 实施规格 / ADR / 规格 / 进度快照 / 验证报告），不凭记忆写结构。
- **头块**：状态、版本、类型、负责人、最后更新、适用范围、上游依据、修订记录，缺项视为不合规。
- **冻结不可原地改**：升版本另存新文件 → 旧版移入 `99-archive/` → 更新 `docs/README.md` 总索引；同一主题只保留一份「已冻结」版本。
- **单一事实源**：技术栈版本与锁定项只在 `02-solutions` 定义；阶段编号 M0–M8 只在 `03-plans/roadmap` 定义；PoC 判据引用技术选型 §7。其它文档只引用，不复制数值或结论。
- **引用**：一律相对路径，且目标必须存在；移动文档必须同步全仓引用并跑治理检查。
- **进度 append-only**：完成实质工作（方案、代码、验证、决策）后向 `docs/05-progress/<年>/` 追加快照，不回改历史条目。
- **并行义务同步**：roadmap 的并行 / 同步建设约束变更时同步 `docs/00-governance/parallel-obligations.json`，阶段收口快照须逐项点名未闭环义务（详见 §6.4）。

# Ontos — 本体驱动的确定性问数系统

自然语言进来，**类型化 DSL** 出去：LLM 只做语义匹配与参数提取，SQL 由已发布语义清单确定性编译，
执行走只读数据源并受 RLS 约束，全程锚定不可变发布清单、留审计痕迹。

文档规范与总索引见 [`docs/README.md`](docs/README.md)（方案 / 计划 / 进度 / 规格分层管理）；工程约定见 [`AGENTS.md`](AGENTS.md)。

## 技术栈（阶段零冻结）

| 层 | 选型 |
|---|---|
| 后端 | JDK 21 · Spring Boot 4.0.x（≥4.0.3）· Maven 多模块 |
| Agent | AgentScope Java 2.1.0-BETA1（harness / spring-boot-starter / agui）+ AG-UI SSE |
| 数据 | PostgreSQL 18 · 双库（System DB / Query DS）· Flyway · jOOQ（仅渲染 SQL）· RLS |
| 前端 | shadcn/ui + Tailwind v4 + Radix · CopilotKit Headless（AG-UI）· TanStack Table / ECharts · React Hook Form + Zod · TanStack Query + Zustand · Vite + TypeScript + pnpm（技术选型 v1.1 附录 A 冻结） |
| 通用层 | 对齐 [yudao-boot-mini](https://github.com/yudaocode/yudao-boot-mini) 的 `CommonResult` / `ServiceException` / `PageResult` / `JsonUtils` |

> Spring Boot 大版本以 AgentScope starter 的 Maven Central POM 为准（实测编译基线 `spring-boot-autoconfigure:4.0.1`、
> `spring-boot-starter-webflux:4.0.3`），不是 3.x。Boot 4 默认 Jackson 3（`tools.jackson.*`）。

## 目录结构

```
ontos/                          后端（唯一部署单元是 ontos-server）
├── ontos-framework/
│   ├── ontos-common/           通用返回、错误码、异常、JSON 工具
│   ├── ontos-spring-boot-starter-web/    统一响应、全局异常、请求上下文、CORS、Jackson
│   ├── ontos-spring-boot-starter-jdbc/   双数据源 + jOOQ DSLContext
│   └── ontos-spring-boot-starter-agent/  HarnessAgent 工厂 + AG-UI 注册
├── ontos-module-ontology/      本体快照、发布校验、不可变清单
├── ontos-module-query/         类型化 DSL → 校验 → 编译 → 只读执行
├── ontos-module-system/        策略、审计、审批
└── ontos-server/               启动模块 + application.yml + Flyway 迁移 + prompt
ontos-ui/                       前端 workspaces（工程规范见 ontos-ui/FRONTEND_RULES.md）
├── apps/web/                   产品应用：app/<域> · service/<域> · service/http · context · hooks · themes
├── packages/ontos-ui/          迁移期临时组件库（M0 内按附录 A 替换为 shadcn/ui + Radix）
├── scripts/                    架构边界、导入方向、设计 token 的可执行约束
├── FRONTEND_RULES.md           前端工程规范（参考 yak-ops-ui；组件选型见附录 A）
└── docs/tooling.md             工具链与验证命令
deploy/postgres/init/           本地 PG 初始化脚本
docker-compose.yml              PostgreSQL 18 双库编排
docs/                           文档体系（规范与总索引见 docs/README.md）
├── 00-governance/              模板、术语、治理检查脚本
├── 01-decisions/               ADR 决策记录
├── 02-solutions/               方案：架构与技术选型（技术栈单一事实源）
├── 03-plans/                   计划：路线图与阶段实施规格
├── 04-specs/                   规格：接口 / DSL / 数据 / 安全契约
├── 05-progress/                进度快照（append-only）
├── 06-verification/            PoC 报告 / 评测 / 检查清单
├── 07-references/              外部依据与事实台账
└── 99-archive/                 被替代版本归档
```

依赖方向由 `ontos-server` 的 ArchUnit 测试守护：`framework` 不得反向依赖业务模块，
`ontology`/`system` 不得互相依赖或依赖 `query`。
前端依赖方向由 `ontos-ui/scripts/check-architecture.mjs` 守护：`service` 不引用 `app`、
页面不绕过域 service 直接调 HTTP、UI 包不引用产品代码。

> 后端整体风格参考 [yudao-boot-mini](https://github.com/yudaocode/yudao-boot-mini)。
> **前端是「两套边界」**：工程规范（目录分层、service 三层、状态与请求原则、Lint / 架构检查）参考
> `D:/tianxy/code/yak-ops/yak-ops-ui/FRONTEND_RULES.md`；**框架与组件一律用技术选型 v1.1 附录 A 的选型**，
> 不沿用来源项目的 `@base-ui/react` 组件库。
> **任何 agent 改前端前必须先读 `ontos-ui/FRONTEND_RULES.md`、`ontos-ui/docs/tooling.md`、根 `AGENTS.md` 第 6 节与附录 A。**

## 本地启动

前置：JDK 21、Maven 3.9+、Node 22+、Docker。

**1) 启动数据库**

```bash
docker compose up -d
```

初始化脚本会建 `ontos_system` / `ontos_queryds` 双库、`ontos_app` / `ontos_query_ro` 角色，
并在 Query DS 应用演示表与 RLS 策略。弱口令仅供本地，禁止用于共享环境。

**2) 启动后端**（`local` profile 放开 demo 身份与策略）

```bash
cd ontos
mvn -DskipTests package
java -jar ontos-server/target/ontos-server-0.1.0-SNAPSHOT.jar --spring.profiles.active=local
```

Flyway 会在启动时创建 System DB 的表。首次联调再灌一份演示清单：

```bash
docker compose exec -T postgres psql -U ontos_admin -d ontos_system \
  < ontos/ontos-server/src/main/resources/db/seed/demo_manifest.sql
```

> AG-UI 端点需要模型扩展与 API Key：用 `mvn -DskipTests package -P model-dashscope`（或 `model-openai`）
> 重新打包并设置 `DASHSCOPE_API_KEY` / `OPENAI_API_KEY`。未配置时确定性链路 `/api/v1/**` 完全可用，
> 只有调用 `/agent/ontos` 才会在请求期报错——模型解析已延迟到请求期，不会阻止应用启动。
> 只想跑确定性链路时可加 `--ontos.agent.enabled=false`。

**3) 启动前端**

```bash
cd ontos-ui
pnpm install
pnpm dev             # http://localhost:5173，/api 与 /agent 自动代理到 8080
```

后端地址可用 `ONTOS_BACKEND_URL` 覆盖（默认 `http://127.0.0.1:8080`）；生产由网关注入，前端不内置地址。
界面不会用模拟数据伪装请求结果：后端未启动时直接显示错误状态（前端规范硬性条款）。

> 若 `pnpm install` 长时间无输出，检查用户级 `~/.npmrc` 是否写死了本机未启动的代理地址
> （例如 `proxy=http://127.0.0.1:7897`）。可临时用
> `NPM_CONFIG_USERCONFIG=/dev/null pnpm install --registry=https://registry.npmmirror.com` 绕过该文件。
> 首次安装会因 pnpm 默认禁止依赖执行安装脚本而跳过 esbuild 的原生二进制，`pnpm-workspace.yaml`
> 的 `allowBuilds` 已放行；若被重置，执行 `pnpm install` 时会提示并在该文件要求补写。

**4) 验证**

```bash
cd ontos && mvn test             # 32 个测试（含 5 条 ArchUnit 架构规则）
cd ontos-ui && pnpm check        # Oxfmt / Oxlint / TS / 架构边界 / 静态契约 / 单测
cd ontos-ui && pnpm build        # Vite 生产构建
node docs/00-governance/check-docs.mjs   # docs 治理：分层 / 命名 / 链接
node scripts/verify-baseline.mjs          # 上面四项 + jar 启动探活，一条命令全绿
```

## API 一览

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/ontology/manifests/current` | 当前生效发布清单 |
| GET | `/api/v1/ontology/manifests/{id}` | 按 ID 取清单 |
| POST | `/api/v1/query/compile` | 编译 DSL（dry-plan，不执行） |
| POST | `/api/v1/query/dry-plan` | 同一编译产物 + EXPLAIN 计划与成本（不触数据表） |
| POST | `/api/v1/query/dry-run` | 受限执行（更紧的超时/行数，成本超限拒绝），返回结果与执行元数据 |
| POST | `/api/v1/query/execute` | 编译并执行，返回行数据 |
| GET | `/api/v1/approvals` | 审批单分页（按租户隔离） |
| POST | `/api/v1/approvals/{id}/decide` | 批准 / 拒绝 PENDING 单据 |
| GET | `/api/v1/audit-logs` | 审计日志分页 |
| POST | `/api/v1/ontology/publish` | 发布清单（dry-run 预检 / 确认发布） |
| GET | `/api/v1/ontology/publish-records` | 发布历史（含预检留痕） |
| GET | `/api/v1/ontology/assets` | 资产浏览（按类型分组） |
| GET | `/api/v1/ontology/manifests/{id}/diff/{baseId}` | 两清单资产级 diff |
| GET | `/api/v1/ontology/impact` | 资产影响反查 |
| GET | `/api/v1/ontology/paths` | 本体路径规划（调试端点） |
| POST | `/api/v1/ontology/manifests/{id}/activate` | 激活指定清单（发布后激活 / 回滚） |
| POST | `/agent/ontos` | AG-UI SSE 事件流 |
| GET | `/actuator/health` | 健康检查 |

统一响应为 `CommonResult<T>`（`code=0` 成功），分页为 `PageResult<T>`。
发布/激活/规划等管理端点经策略闸门（M4 前非 local profile 整体 403）。

## 当前状态（M2 · 确定性编译与执行内核，进行中）

已落地：多模块骨架、yudao 风格通用层、双数据源、发布清单加载与哈希校验、
DSL 校验 + 参数化 SQL 编译 + 只读执行 + RLS、审批/审计接口、Flyway 迁移、AG-UI 注册、前端页面与 SSE 客户端、架构守护测试。

- **M0 已通过**（2026-11-07）：阶段零 PoC 全链验收，G1–G8 关闭，见 `docs/06-verification/checklists/M0-验收报告-v2.md`。
- **M1 已通过**（2026-11-08）：[验收报告 v2](docs/06-verification/checklists/M1-验收报告-v2.md) E1–E8 全部满足——
  清单 payload v2 与本体元模型、首发清单与发布链路（dry-run/确认/激活/回滚）、路径规划器、
  请求级八态状态机与迁移审计、管理界面三页；G2 三批 100% 维持关闭；ADR-0007~0010 冻结。
- **M2 进行中**：[实施规格 v1.0](docs/03-plans/stages/M2/M2-确定性编译与执行内核-实施规格-v1.md) 已冻结，
  W1 完成（Golden 集 v2 案例 30 条、冒烟集 gold、M1 定稿包）；
  W2 + W2.5 完成（DSL 来源标记 / 绝对日期拒绝 / 时间解析器接线与审计版本 / 未知字段 fail-closed /
  `sub_intents` / `explicit_range` / `analysis_params` / 敏感资产 guardrail，口径见
  [ADR-0011](docs/01-decisions/ADR-0011-M2实施规格状态修订.md)）；
  W3 全部完成（T3.0 [funnel/yoy_mom 规格 v1](docs/04-specs/analysis-mode-funnel-yoy-mom-规格-v1.md)、
  T3.1 UNIQUE 路径 → 结构化 Join、T3.2 lookup/lookup_batch、T3.3 funnel+yoy_mom 构造器、
  T3.4 Rule 求值、T3.5 敏感资产 guardrail），首发清单 `2026.11.08-third` 上 5/5 模式编译冒烟全绿；
  W4 进行中：T4.1 dry-plan / dry-run（EXPLAIN 成本闸门 + 受限执行）、T4.2 dry-run 独立超时/行数闸门已落地，
  T4.3–T4.5 与 W5/W6 未启动。

实测基线（2026-11-08）：`mvn test` 全绿、`pnpm check` 通过、`vite build` 通过（新输出目录）、
文档治理检查通过。进度与状态口径以 `docs/05-progress/2026/` 和
`docs/03-plans/roadmap/Ontos开发计划-阶段性功能拆解-v1.md` 为准。

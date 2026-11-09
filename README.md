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

> 本机已装 PostgreSQL 且已建好 `ontos` / `ontos_queryds` 双库与只读角色时，可直接跳过 Docker。
> **compose 与默认连接串不通用**：compose 建的是 `ontos_system` 库 + `ontos_app` 角色，
> 而 `application.yml` 默认连 `ontos` 库；用 compose 必须额外设置 `ONTOS_SYSTEM_DB_URL`、
> `ONTOS_SYSTEM_DB_USER/PASSWORD`、`ONTOS_QUERY_DB_URL`、`ONTOS_QUERY_DB_USER/PASSWORD`
> 覆盖（环境变量优先级高于 `application-local.yml`）。该路径未在本机验证（本机无 docker）。

**2) 启动后端**（`local` profile 放开 demo 身份与策略）

```bash
cd ontos
mvn -DskipTests package
java -jar ontos-server/target/ontos-server-0.1.0-SNAPSHOT.jar --spring.profiles.active=local
```

> **在 IntelliJ IDEA 里 Run `OntosApplication` 时必须显式激活 `local`**：
> 仓库自带共享运行配置 `.run/OntosApplication-local.run.xml`（已含 `Active profiles=local`
> 与 UTF-8 VM 参数），IDEA 打开项目后在运行配置下拉里直接选 **`OntosApplication (local)`** 即可；
> 未识别到该配置时手动设置：`Run/Debug Configurations` → Spring Boot → **Active profiles** 填 `local`；等价做法是
> VM options 加 `-Dspring.profiles.active=local`、环境变量加 `SPRING_PROFILES_ACTIVE=local`、
> 或 Program arguments 加 `--spring.profiles.active=local`（也可直接用 `scripts/run-local-backend.sh`，脚本已带该参数）。
>
> 漏掉这一步时 `application.yml` 里 `ontos.datasource.*.username/password` 是空占位符
> （fail-closed：非 local 必须显式提供凭据），Flyway 会以空用户名连库并抛
> `SQL State 28000 / 未指定 PostgreSQL 用户名`，随后 `systemJdbcTemplate` → `ManifestServiceImpl`
> 整条装配链失败、应用启动中止。**不要**为了让 IDEA 能启动而把默认凭据写回 `application.yml`；
> 若确实不用 `local`，需显式提供 `ONTOS_SYSTEM_DB_USER/PASSWORD`、`ONTOS_QUERY_DB_USER/PASSWORD`。
> 该报错在 Windows 本机 PG18 上可能显示为乱码，成因与处理见下方「本地日志编码」。

Flyway 会在启动时创建 System DB 的表。首次联调再灌一份演示清单：

```bash
docker compose exec -T postgres psql -U ontos_admin -d ontos_system \
  < ontos/ontos-server/src/main/resources/db/seed/demo_manifest.sql
```

> AG-UI 端点需要模型扩展与 API Key：用 `mvn -DskipTests package -P model-dashscope`（或 `model-openai`）
> 重新打包并设置 `DASHSCOPE_API_KEY` / `OPENAI_API_KEY`。未配置时确定性链路 `/api/v1/**` 完全可用，
> 只有调用 `/agent/ontos` 才会在请求期报错——模型解析已延迟到请求期，不会阻止应用启动。
> 只想跑确定性链路时可加 `--ontos.agent.enabled=false`。

**本地日志编码（Windows + 本机 PostgreSQL）**

JDK 21 在中文 Windows 上是 `file.encoding=UTF-8` 而 `stdout.encoding/stderr.encoding=GBK`；
本机 PG18 若用中文 locale（`lc_messages = Chinese (Simplified)_China.936`），
**连接握手阶段**（认证失败时 `client_encoding` 尚未协商）服务端会直接发送 GBK 字节的中文错误，
pgjdbc 按 UTF-8 解码后字符串本身已损坏，日志表现为 `SQL State 28000` 旁的乱码。
此时调 IDEA 控制台编码没用；连接建立后的普通 SQL 错误反而是正常 UTF-8，业务中文日志也正常。

一次性把本机开发库的服务端消息改为英文（只改消息语言，不动数据、角色与权限）：

```bash
psql -h localhost -U postgres -d ontos -c "ALTER SYSTEM SET lc_messages = 'C'" -c "SELECT pg_reload_conf()"
```

回退：`psql -h localhost -U postgres -d ontos -c "ALTER SYSTEM RESET lc_messages" -c "SELECT pg_reload_conf()"`。
两条语句必须分开 `-c` 传（`ALTER SYSTEM` 不能在事务块内执行）；reload 后新连接即生效，
报错变 `FATAL: no PostgreSQL user name specified in startup packet`。
若 IDEA 里业务中文日志仍异常，把 `Settings → Editor → General → Console → Default Encoding`
设为 `UTF-8`，并在 Run Configuration 的 VM options 加
`-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8`。

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
| GET | `/api/v1/query/plan/{requestId}` | 回放最近一次编译产物（SQL / 绑定值 / Join 路径 / 计划） |
| POST | `/api/v1/query/composite/execute` | 复合意图执行（无依赖子集逐子独立编译/授权/审计，失败整单拒绝） |
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

## 当前状态（M3 · 简单问法最小闭环，已通过）

已落地：多模块骨架、yudao 风格通用层、双数据源、发布清单加载与哈希校验、
DSL 校验 + 参数化 SQL 编译 + 只读执行 + RLS、审批/审计接口、Flyway 迁移、AG-UI 注册、前端页面与 SSE 客户端、架构守护测试。

- **M0 已通过**（2026-11-07）：阶段零 PoC 全链验收，G1–G8 关闭，见 `docs/06-verification/checklists/M0-验收报告-v2.md`。
- **M1 已通过**（2026-11-08）：[验收报告 v2](docs/06-verification/checklists/M1-验收报告-v2.md) E1–E8 全部满足——
  清单 payload v2 与本体元模型、首发清单与发布链路（dry-run/确认/激活/回滚）、路径规划器、
  请求级八态状态机与迁移审计、管理界面三页；G2 三批 100% 维持关闭；ADR-0007~0010 冻结。
- **M2 已通过（E1 附条件）**（2026-11-08）：[验收报告 v1](docs/06-verification/checklists/M2-验收报告-v1.md) 冻结，
  E2–E8 满足、E1 可构造子集 27/27 逐字一致（3 条不可构造案例按
  [ADR-0013](docs/01-decisions/ADR-0013-Golden不可构造案例处置口径.md) 由两条单测与 M4 域策略接替）。
  W1–W6 全部完成：DSL 来源标记与 fail-closed 白名单、时间解析器与「今年」模式、UNIQUE 路径 → 结构化 Join、
  5 模式构造器（query/lookup/lookup_batch/funnel/yoy_mom）、Rule 求值、敏感资产 guardrail、
  dry-plan/dry-run 与成本闸门、复合意图两阶段逐子编译、编译产物持久化与 `/plan/{id}` 回放、
  query_cache 与激活失效钩子、编译调试页。当前生效清单 `2026.11.08-eighth`（M3/W1：新增带两个口径变体的 `metric.paid_gmv`、「上季度」时间模式与 9 条原因码文案模板）。

- **M3 已通过**（2026-11-09，[验收报告 v1](docs/06-verification/checklists/M3-验收报告-v1.md) 冻结，E1–E6 满足）：
  W1 资产与清单 v8（带两个口径变体的 `metric.paid_gmv`、「上季度」模式、9 条文案模板）、
  W2 口径变体闭环（`caliberVariantId` + `CALIBER_AMBIGUOUS` 1002000033，歧义澄清不允许 LLM 二选一）、
  W3 能力注册与 lookup 产品化（`skill.entity_lookup`、`rows` 不再携带内部列 `__identity`）、
  W4 文案模板服务（`GET /api/v1/copy-templates/{reasonCode}`，占位符白名单渲染 + GENERIC 兜底）、
  W5 前端（口径澄清卡片、entity_lookup 结果与逐行可用性、`/plan/{requestId}` 回放 UI、
  时间列 Asia/Shanghai 本地化）。当前生效清单 `2026.11.08-eighth`。

实测基线（2026-11-08）：`mvn test` 全绿、`pnpm check` 通过、`vite build` 通过（新输出目录）、
文档治理检查通过。进度与状态口径以 `docs/05-progress/2026/` 和
`docs/03-plans/roadmap/Ontos开发计划-阶段性功能拆解-v1.md` 为准。

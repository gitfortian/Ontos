# Ontos 项目长期记忆

## 项目形态
- 顶层：`ontos`（后端 Maven 多模块，单进程单体，唯一部署单元 `ontos-server`）、`ontos-ui`（Vite/React/TS 前端）。
- 定位：本体驱动的确定性问数系统。自然语言 → 类型化 DSL → 确定性编译 → 只读 Query DS（RLS）→ 审计。
- 方案文档：`docs/02-solutions/architecture/智能问数系统技术选型方案-v1.1.md`（技术栈冻结）、`docs/02-solutions/architecture/智能问数系统-完整可落地方案-v1.4.md`（v1.4 架构）。

## 已冻结技术基线
- JDK **21**、Spring Boot **4.0.x（≥4.0.3）**、AgentScope Java **2.1.0-BETA1**、PostgreSQL 18、Jackson 3（`tools.jackson.*`）。
- Boot 大版本以 AgentScope starter 的 Maven Central POM 为准，不是 3.x；版本依据一律实测，不采信第三方博客。
- **模型接入只认 OpenAI 协议，不绑定厂商**（用户 2026-11-07 明确）：配置是标准三件套 `ontos.agent.model` + `base-url` + `api-key`（密钥仅环境变量），换供应商只改 `base-url` 与模型名。实现上依赖 `agentscope-extensions-model-openai`，但**不要在配置/文档里暴露各家的 provider 前缀**——用户明确说「先不适配各家模型」。`dashscope:` 前缀解析不到（工程没引入该扩展），不要再用。

## 用户明确偏好（必须遵守）
- **功能代码介绍（2026-11-08 用户要求「记住」）**：凡功能代码开发任务结束，回复中必须额外输出一小段「主要核心类逻辑与功能」介绍——点明核心类、各自职责、关键逻辑链路及整体实现的功能；简短、与深度审核结论分开，不要只列改动文件或写「详见代码」。
- **深度审核铁律（2026-11-08 用户要求「永远记住」）**：每完成**一个任务**（含子任务/单个工作流/单步实现）立即做一次深度审核；**整个大任务**（阶段/里程碑/一批任务）结束后再做一次覆盖全局的深度审核。审核须查：改动与规格一致、代码边界与安全（fail-closed）、测试真跑且断言有效、文档/索引同步、提交范围与工作区；发现问题当场修复并复核，未闭环不得宣告完成；结论留痕（进度快照 append-only）并在回复中给实质发现。该规则优先于「按计划继续、不用每步询问」的授权，**自动推进 ≠ 跳过审核**。
- 后端整体代码风格**尽量参考 yudao-boot-mini**（github.com/yudaocode/yudao-boot-mini），保持规范。
- **通用/工具类代码直接抄 yudao 的实现，不要自己发挥**；仅在文件头标注来源与必要适配（Jackson 2→3、javax→jakarta）。
- 后端**按功能架构拆多模块**，不要把代码全塞进一个包；模块内按 controller / service / dal.dataobject / dal.mysql / core / enums 分层。
- 工程约定固化在根目录 `AGENTS.md`，改动前先读。

## 前端规范基线（必须遵守，两套边界不要混淆）
- **参考 yak-ops 的只是「工程规范」**：`ontos-ui/FRONTEND_RULES.md`（迁移自 `D:/tianxy/code/yak-ops/yak-ops-ui/FRONTEND_RULES.md`）只约束目录分层、`service/http` 三层、状态与请求原则、命名、Lint/格式化、架构检查、测试方式。配套 `ontos-ui/docs/tooling.md`。改动前端前必须先读，禁止另起一套写法。
- **框架与组件一律用我们自己的选型**：`docs/02-solutions/architecture/智能问数系统技术选型方案-v1.1.md` **附录 A**（shadcn/ui + Tailwind v4 + Radix、CopilotKit Headless/AG-UI、TanStack Table + ECharts + Monaco、React Hook Form + Zod、TanStack Query + Zustand、Vite + TS + pnpm，纯静态产物由 Spring Boot 单体托管）。**附录 A 是权威，不要修订它去迁就实现**。
- 用户明确澄清过：「我只是让参考其前端代码规范，框架组件还是用我们的选型」。因此**不得把来源项目基于 `@base-ui/react` 的组件库当作本项目组件选型**。
- **归位已完成（2026-11-07，W5/T5.2–T5.4）**：`packages/ontos-ui` 已重写为 shadcn/ui + Radix，来源组件库与 `--yak-*` token 已清除，架构检查全局禁止回流。AG-UI 侧仍待 PoC #10 验收后切 CopilotKit Headless（T5.5）。
- 请求方向 `app/<域>` → `service/<域>` → `service/http`；fetch 只允许在 `service/http/request.ts`（含 SSE）。服务端数据用 Query 缓存，分页筛选放 URL，样式用语义 token 不硬编码颜色。规范由 `ontos-ui/scripts/` 下脚本强制，禁止为跑通而放宽。

## 文档规范（必须遵守）
- **唯一入口 `docs/README.md`**（文档规范 + 总索引）。新增/修改/查找文档前必须先读；`docs/` 根目录除它外不得有 `.md`。
- 分层：`00-governance` 治理模板/检查脚本、`01-decisions` ADR、`02-solutions` 方案、`03-plans` 计划、`04-specs` 规格、`05-progress` 进度、`06-verification` 验证、`07-references` 参考、`99-archive` 归档；层级上限 3 层，每个一级目录有 README.md。
- 起稿用 `docs/00-governance/templates/` 七类模板；统一头块 8 字段；**已冻结文档不得原地改**（升版本 + 归档旧版 + 更新总索引）；进度 append-only。
- SSOT：技术栈版本/锁定项只在 `02-solutions`，阶段编号 M0–M8 只在 `03-plans/roadmap`；其它文档只引用不复制。相对链接必须可解析。
- **冻结方案正文的修订用 ADR 承载，不改正文**：`ADR-0002` 即 §4.8.2 跨轮差分规则的生效解释（精确差分即时转审批 + 三处约束），技术选型下次升版时并入。
- **归档路径**：`99-archive` 镜像分类、**去掉编号前缀**（`06-verification/poc/x.md` → `99-archive/verification/x.md`）。因为 `check-docs.mjs` 禁止一级目录下有 `\d{2}-` 子目录，照搬原结构会同时违反层级与编号两条规则。
- 维护进度与状态时同步 `docs/05-progress/<年>/` 与 `.workbuddy/memory/`；进度 append-only，修正用「更正」条目。
- 提交前跑 `node docs/00-governance/check-docs.mjs`。
- 存量文档已归位：技术选型与架构方案在 `docs/02-solutions/architecture/`，大计划在 `docs/03-plans/roadmap/`，全仓引用已同步。

## 环境注意
- 用户级 `~/.npmrc` 写死失效代理 `http://127.0.0.1:7897`，会让 `npm/pnpm install` 静默卡死；用 `NPM_CONFIG_USERCONFIG=/dev/null pnpm install --registry=https://registry.npmmirror.com` 绕过。
- **本地 PG18 可用**（用户 2026-11-06 确认）：Windows 服务 `postgresql-x64-18`，安装目录 `D:\pg`（18.6-5），超管 `postgres/123456`，`psql` 不在 PATH 用绝对路径 `D:/pg/bin/psql.exe`。双库 `ontos`（System DB）/ `ontos_queryds`（Query DS），只读角色 `ontos_query_ro/ontos_query_ro`。`docker` 命令不在 PATH，compose 未使用。
- **PG 错误消息已固定为英文（2026-11-09）**：原 `lc_messages = Chinese (Simplified)_China.936`，握手期服务端发 GBK 中文字节被 pgjdbc 按 UTF-8 解码 → 不可恢复的日志乱码；已 `ALTER SYSTEM SET lc_messages='C'` + `pg_reload_conf()`（持久于 `postgresql.auto.conf`），回退用 `ALTER SYSTEM RESET lc_messages` + reload。此问题与控制台编码无关。
- **IDEA 启动必带 `local` profile**（Active profiles / `SPRING_PROFILES_ACTIVE` / `--spring.profiles.active=local`）：不带则 DB 凭据为空 → PG `28000` → Flyway 初始化失败 → 启动中止；切勿为图省事把默认弱口令写回 `application.yml`（破坏 fail-closed）。仓库已带共享运行配置 `.run/OntosApplication-local.run.xml`（含 profile + UTF-8 VM 参数），IDEA 下拉直接选 `OntosApplication (local)`。
- **Spring Boot 4 坑**：Flyway 自动配置拆到 `spring-boot-flyway` 模块，仅依赖 `flyway-core` 不会执行迁移；`ontos-server/pom.xml` 必须保留 `spring-boot-starter-flyway`，否则生产部署静默缺表。
- `pnpm` 11.5.1 已装；前端已按 ADR-0001 切 pnpm workspaces（根 `pnpm-workspace.yaml`，`packageManager: pnpm@11.5.1`）。
  pnpm 10+ 默认禁跑依赖安装脚本，`esbuild` 需在 `pnpm-workspace.yaml` 的 `allowBuilds` 放行，否则 vite 起不来。
- 环境注入了 `SERVER__PORT=51719`（hooks 代理占用），会顶掉 `application.yml` 的 8080；`java -jar` 启动必须显式 `--server.port=8080`。
- 批量删除（>50 文件/次）被环境删除保护拦截（`SAFE_DELETE_BULK_CONFIRM_REQUIRED`）；清 `node_modules`/vite 缓存要分批或用 pnpm 自己处理，不要用 `rm -rf` 硬来。
- 视觉复核：本机有 playwright 浏览器缓存，仓库没装 playwright，可用 `D:/tianxy/code/semantica/explorer/node_modules/playwright-core` + `executablePath` 截图；chromium 要 `--no-proxy-server`，且 Git Bash 会把以 `/` 开头的参数当路径转换。

## M0 状态（2026-11-07）
- **M0 已通过验收**：G1–G8 关闭（《M0 验收报告 v1》冻结于 `06-verification/checklists/`）；G2（#3 终态 68.8%）为负责人签字的有条件关闭，修复项（服务端校验+重试）登记 M1 首批，未达 100% 则重开。
- 已冻结决策：ADR-0002（差分规则修订：精确差分即时转审批）、ADR-0003（fail-closed 判据清单）、ADR-0004（首发业务域=交易域，7 关系一条不减）、ADR-0005（评测门槛=§7 初值一字不改）。
- 前端主路线已定：CopilotKit 协议可行但入口 `unsafe_dev_only` 非生产级 → **走退路 `@ag-ui/client` + 自研渲染**，CopilotKit 列观察项待 M1 复查。
- 非 QUERY 分析模式已 fail-closed（`1_002_000_006`），构造器实现归 M2；Golden 集 / 复合题集 30 题 / 提及样本 300 条均已冻结待回填。
- 下一步：M1 前补 §11 剩余 8 项 ADR → 《M1 实施规格》。真实敏感数据测试以 M4 复验登记为边界。

## M2 状态（2026-11-09）
- **M2 已通过（E1 附条件，ADR-0013）**：验收报告 v1 冻结；《M2 收口后深度审核 v1》归档（12 项发现全部修复复验、R-18 关闭、R-25 归 M3）。
- 当前生效清单 `2026.11.08-seventh`（hash 471cc654）；Golden 30 条中 27 可构造逐字一致 0 失败；复合可表达子集 14/14；RLS 100 轮 0 越权。
- **后续改动不得破坏的口径**：YOY_MOM 的 Join 与 filters 必须同时进入「聚合」与「维度枚举」两个 CTE；FUNNEL 在 M2 不接受 filters（拒绝而非忽略）；时间窗绑定统一按上海墙钟（会话时区固定 Asia/Shanghai）；模式授权 `validateMode` 必须先于路径规划；聚合模式拒绝 lookup 字段；YOY_MOM 不允许时间维度自身作分组维度。
- 基线：`mvn test` **269 例**（30 类）；`pnpm check` exit 0；`check-docs.mjs` 60 文档 / 467 链接。
- **M3 规格已冻结（v1.0）**：能力注册落 query `core/skill`（不新建 agent 模块）；口径变体由新增 `metric.paid_gmv` 承载（不改 gmv）；文案模板走独立端点。
- **M3/W1 已完成**：清单 v8（2026.11.08-eighth，hash 2720fb36）生效——paid_gmv 双口径变体（dim.channel 同表）、上季度模式、文案模板 9 条（带 reasonCode/占位符白名单/version）；结构校验与解析器同步落地。W2 待启动（caliberVariantId 门禁 + 编译下推 + CALIBER_AMBIGUOUS）。
- **M3/W4 已完成**：CopyTemplateService + GET /api/v1/copy-templates/{reasonCode}；渲染只按发布期白名单，声明未给值回退 GENERIC（1002000034 兜底缺失报错）；R-26 关闭（未声明花括号按字面量）。
- **M3/W3 已完成**：core/skill 包（SkillHandler/SkillRegistry/entity_lookup）；GET /skills + POST /skills/{skillId}；能力层只组装 DSL（校验全复用 execute）；SKILL_EXECUTE 审计；R-20 关闭（rows 剥离 __identity）。
- **M3/W2 已完成**：caliberVariantId 进 DSL；多口径指标缺变体 → 1002000033 CALIBER_AMBIGUOUS（候选带别名，禁止 LLM 二选一）；变体条件下推 QUERY WHERE 与 YOY 两个 CTE。R-27：复合子意图暂不支持变体。
- **后续不得破坏**：变体条件只允许与指标同表（跨表拒绝）；文案模板占位符必须白名单声明；「上季度」按 patternId=tep.last_quarter 解析（不按粒度回退）；多口径指标缺变体必须澄清不得默认。
- M3 范围：lookup 产品化、布尔判定闭环、口径变体、上季度时间模式、文案模板、`__identity` 收口；并行偿还 R-20/R-23/R-25。

## M3 状态（2026-11-09 收口）
- **M3 已通过（E1–E6 全满足）**：验收报告 v1 冻结；M5 放行（M4 可并行）。
- 当前生效清单 `2026.11.08-eighth`（hash 2720fb36）；M3 Golden 增量 6/0（m3-golden.mjs）；
  M2 基线未回退（Golden 27/0、复合分布一致、RLS、E5 9/9）。
- **后续不得破坏**：`rows` 不携带 `__identity`；lookup 缺失行 `available=false/values={}` 与不存在同形；
  文案渲染绝不透 `{x}` 原文；能力层只组装 DSL（校验全复用 execute）；前端 app 层不直连 service/http
  （BizError 经 service/query 转发）；`getResponse` 是拿响应头的唯一通道。
- 基线：`mvn test` **304 例**（33 类）；`pnpm check` exit 0（21 单测）；docs 63/493。
- 待办：M5（ask 对话主干与能力编排；R-27 复合子意图变体、R-29 variantHint 语义随做）或 M4（并行）。

## 并行义务纪律（2026-11-09，用户要求「不能漏掉不做」）
- **硬约束**：roadmap 所有「并行 / 同步建设 / 不许后置」项与主线同等跟踪；状态「待启动」不等于可略过。每阶段收口（验收报告 / 深度审核 / 进度快照）必须逐项点名，欠账写清触发点 / 负责人 / 证据并同步台账；**未点名不得宣告阶段关闭**。见 `AGENTS.md` §6.4、ADR-0014、`docs/README.md` §7.8。
- **机器强制**：`docs/00-governance/parallel-obligations.json`（状态仅 `done` / `deferred`）＋ `check-parallel-obligations.mjs`（接入 `verify-baseline.mjs`，基线 5 项）；禁止放宽脚本或删改 roadmap 硬约束文本绕过。
- **当前欠账**：OBL-M4 触发 M5（M5 启动前必须完成 M4）；OBL-M7 触发 M4（M4 启动前补正式评测集 / 冒烟 runner / 分层统计，M6 后收敛）。M1–M3 已通过而两者均 `deferred`，属静默未执行，已由 ADR-0014 登记，**不得事后追认或把主线素材算作 M7 交付物**。

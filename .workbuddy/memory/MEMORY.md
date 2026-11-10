# Ontos 项目长期记忆

## 项目形态与技术基线
- `ontos`（后端 Maven 多模块单体，部署单元 `ontos-server`）+ `ontos-ui`（Vite/React/TS）。定位：本体驱动确定性问数（NL→DSL→编译→只读 Query DS→审计）。
- JDK 21、Spring Boot 4.0.x、AgentScope Java 2.1.0-BETA1、PG 18、Jackson 3（tools.jackson.*）。
- 模型接入只认 OpenAI 协议三件套（base-url/api-key/模型名），不暴露厂商前缀；`dashscope:` 前缀不可用。
- 方案：`docs/02-solutions/architecture/` 技术选型 v1.1 + 完整可落地 v1.4；工程约定在根 `AGENTS.md`（改动前先读）。

## 用户明确偏好（必须遵守）
- **功能代码介绍**：任务结束回复必须附「主要核心类逻辑与功能」段——核心类职责、关键链路、整体功能；简短、与审核结论分开。
- **深度审核铁律**：每完成一个任务立即深度审核；大任务结束再全局审核。查：规格一致、fail-closed、测试真跑、文档同步、提交范围；发现问题当场修复，未闭环不得宣告完成；结论留痕。自动推进 ≠ 跳过审核。
- 代码风格参考 yudao-boot-mini；通用/工具类直接抄 yudao（标注来源与 Jackson2→3/javax→jakarta 适配）。
- 后端按功能拆多模块，模块内 controller/service/dal/core/enums 分层。

## 前端规范
- `ontos-ui/FRONTEND_RULES.md` 只约束工程规范；框架组件以技术选型附录 A 为权威（shadcn/ui+Radix、TanStack、Zod 等），不得用来源项目组件库。请求方向 app/<域>→service/<域>→service/http，fetch 只在 service/http。规范由 ontos-ui/scripts 强制。

## 文档规范
- 唯一入口 docs/README.md；分层 00-governance…99-archive；起稿用模板；**冻结文档不得原地改**（升版+归档+改索引）；进度 append-only；冻结方案修订用 ADR；归档去编号前缀；提交前跑 check-docs.mjs。版本/锁定项只在 02-solutions，M0–M8 只在 roadmap。

## 环境注意
- `~/.npmrc` 失效代理会卡死安装：`NPM_CONFIG_USERCONFIG=/dev/null pnpm install --registry=https://registry.npmmirror.com`。
- 本地 PG18：`D:/pg/bin/psql.exe`（不在 PATH），超管 postgres/123456，双库 ontos/ontos_queryds；psql 子进程必须传完整 os.environ。
- IDEA 启动必带 local profile（`.run/OntosApplication (local).run.xml`）；java -jar 必须显式 `--server.port=8080`（环境注入 SERVER__PORT=51719）。
- Spring Boot 4：flyway 迁移需 `spring-boot-starter-flyway` 依赖；pnpm 10+ 需 allowBuilds 放行 esbuild。
- 批量删除>50 文件被环境拦截，分批处理；改 src/main 后全量验证必须 `mvn clean test`（增量会 NoClassDefFoundError）；后台起服务后提交前要停（8080 进程锁 target）。

## 不得破坏的口径（M2/M3 冻结）
YOY_MOM Join+filters 双 CTE；时间窗按上海墙钟；validateMode 先于路径规划；聚合模式拒绝 lookup 字段；rows 不带 __identity；lookup 缺失行同形；文案不透 {x} 原文；变体条件与指标同表；多口径缺变体必须澄清；「上季度」按 tep.last_quarter；FUNNEL 顶层 filters=全局过滤（字段须映射主体表，cohort 与每步 CTE 同时过滤）。

## 并行义务纪律
roadmap 并行项与主线同等跟踪；阶段收口必须逐项点名欠账（触发点/负责人/证据），未点名不得关闭。机器强制：`parallel-obligations.json` + check-parallel-obligations.mjs。OBL-M4 触发 M5；OBL-M7 触发 M6（ADR-0015），test≥200 题已闭环（test集 v2，241 题，sha 556e564c…，正式评测集 v3）。

## M6 进度（进行中，2026-11-10）
- 规格 v1.0 冻结（`03-plans/stages/M6/`）；ADR-0017 决策 D1–D7 定档。W1 完成（T1.1–T1.6：RETENTION/ATTRIBUTION 构造器、funnel filters、DISCOVERY 边界声明）；Golden M6 23/0。
- W2 完成：T2.1 catalog 模块+V15（ArchUnit：catalog 只管理面）；T2.2 ScenarioMeta 完整模型+§5.7 发布校验；T2.3 五态路由状态机；T2.4 Router 接入瀑布+scope 裁剪+直传裁决 D1 四步+V16 demo 场景授权；ADR-0004 核对 v2 3/3 放行。
- W3 进行中：T3.1 SkillMeta+生命周期（审核人）✅；T3.2 SkillRegistry 清单驱动注册制+四条闸门（T32-6 P1 双态兼容修复）✅；T3.3 近似降级闭环+T32-8（P1）场景确认断链修复 ✅。**下一步 T3.4**：VQR 模型+生命周期+入库七条件（D4 证据外置）+自动失效+vqrs 段进清单 v16。
- **当前生效清单 v15 = `2026.11.10-fifteenth`**（skills 8 条、scenario.sales、近似样例）；激活走 publish + POST /manifests/{id}/activate。
- 关键教训：审计列名是 `dsl` 不是 dsl_json；审计行 tenant/user 是执行线程默认值（按 user 过滤会漏查）；TerminalAssembler.persistClarification 与场景通道写同一 JSONB（须按类型分流+空早退）；裁剪按当前态（决策只影响下一轮）；兼容纪律覆盖全部消费路径，端到端是回归唯一可靠证明；Golden 逐字比对测不出 SQL 语义错——每模式人工审阅产物+真库执行；E-5 缺口：Order 1:N OrderItem 使品类维度×订单指标 fail-closed（ADR-0016 首发不提供）。
- 基线：`mvn clean test` 59 类/562 例；docs 120/1054。后续：T3.4 → W4 复合意图（D2 先冻结《结果引用规格 v1》）→ W5 OBL-M7 收敛 + M6 收口。
- **T3.4 完成（2026-11-10，M6/W3，commit `5cd2f67`）**：VQR 模型+生命周期+入库七条件+自动失效+vqrs 段进清单 v16。要点：①`ManifestPayload.VqrMeta`（questionPattern/dslTemplate/paramsSchema/goldenRef/resultCheckRef/freshnessLimitHours/invalidationConditions/reviewer）+ PublishedRefs.of(payload) 自建 vqrIds——**ScenarioValidator 的 defaultVqrIds/coldStartSamples 引用校验自此生效**；②VqrValidator 七条件清单侧判定（占位符必须声明/资产绑定按目标清单/证据引用必填 D4 外置/失效条件死声明拦截），validateStructure 免绑定供草稿期（T34-1）；③VqrLifecycleService.admissionChecks 带条件编号，点赞不可直达发布；④VqrInvalidationAnalyzer 六族失效（废弃/定义 JSON 不一致保守判定/Rule·Skill 失效/Skill 主版本·结构变更/场景模式收窄/声明式条件）接入 PublishServiceImpl 结构校验**前**，PublishReportVO.invalidatedVqrs 不静默；⑤**T34-8（P2，真服务 dryRun 抓到）**：失效移除 VQR 后场景 defaultVqrIds/coldStartSamples 悬空卡死后续发布 → `apply` 级联清理；⑥清单 v16 首发样例（vqr.sales.daily_gmv_by_channel）**未激活**（T3.5 命中通道消费后统一激活）。教训：admissionChecks 必须消费 targetPayload（T34-2）；skill 测试 definition 同步断言被夹具掩盖（T34-5）；探针前必须确认「当前实例=当前 jar」（端口占用静默退出打旧实例）。基线 **62 类/590 例**、docs 121/1057。**下一步 T3.5**：VQR 命中通道（瀑布第 7 步候选 + Proposal 引用 vqrId + 八步重校验 + vqrId 进审计/缓存键）+ v16 激活。
- **T3.5 完成（2026-11-10，M6/W3，commit `3c7479f`）**：VQR 命中通道闭环，**W3 收口**。要点：①`VqrCandidateMatcher` 瀑布第 7 步——questionPattern `{x}` 槽正则**全串匹配**+参数提取，in_scenario 限场景引用集（defaultVqrIds∪coldStartSamples）、free 全部 published，`RetrievalOutcome.vqrCandidates` 独立字段（不混 dslReady）；②提案 `terminal=query + sub_intents[0].vqr_id+params`（合同词表不动），integrity 对账纳入 vqrId（伪造 → ID_NOT_IN_SET），词表补 published VQR；③`QueryService.executeVqr`：published 同形拒绝 → 模板绑定（必填缺失/未知参数/未声明占位符结构化拒绝）→ **既有 execute 全链零旁路**；审计 `VQR_HIT`（vqrId/version/params）；④`QueryCacheKey` 升 **cache-key-1.2**（+scenarioVersion/vqrId/vqrVersion；skill 版本随清单 contentHash 失效）；⑤**T35-2（P2）**：模板完整命中优先于资产级歧义澄清（模板已消歧，与 T24-4 同原则）；**T35-3（P2）**：模板词表增 `time_dimension`（query 相对时间解析前置 timeRange.field）；**T35-1（否决）**：v17 移除 concept.channel 被场景 allowedRelations 端点校验拒（归因关系一条不减——ADR-0004）；⑥v16 激活（**sixteenth**），Golden 零回退（M6 23/0、M3 6/0、M2 27/0）；⑦E2E 命中 PASS（rows=2+VQR_HIT 真库）；越权判据 demo 租户级快照不可构造（环境限制），机制单测承载。基线 **63 类/600 例**、docs 122/1060。**下一步 W4**：复合意图（T4.1 拓扑排序先行，**D2 编码前冻结《结果引用规格 v1》**）。

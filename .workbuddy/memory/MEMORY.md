# Ontos 项目长期记忆

## 项目形态与技术基线
- `ontos`（后端 Maven 多模块单体，部署单元 `ontos-server`）+ `ontos-ui`（Vite/React/TS）。定位：本体驱动确定性问数（NL→DSL→编译→只读 Query DS→审计）。
- JDK 21、Spring Boot 4.0.x、AgentScope Java 2.1.0-BETA1、PG 18、Jackson 3（tools.jackson.*）。
- 模型接入只认 OpenAI 协议三件套（base-url/api-key/模型名），不暴露厂商前缀；`dashscope:` 前缀不可用。
- 方案：`docs/02-solutions/architecture/` 技术选型 v1.1 + 完整可落地 v1.4；工程约定在根 `AGENTS.md`（改动前先读）。

## 用户明确偏好（必须遵守）
- **功能代码介绍**：任务结束回复必须附「主要核心类逻辑与功能」段——核心类职责、关键链路、整体功能；简短、与审核结论分开。
- **深度审核铁律**：每完成一个任务立即深度审核；大任务结束再全局审核。查：规格一致、fail-closed、测试真跑、文档同步、提交范围；发现问题当场修复，未闭环不得宣告完成；结论留痕。自动推进 ≠ 跳过审核。
- 代码风格参考 yudao-boot-mini；通用/工具类直接抄 yudao（标注 Jackson2→3/javax→jakarta 适配）。后端按功能拆多模块，模块内 controller/service/dal/core/enums 分层。

## 前端规范
- `ontos-ui/FRONTEND_RULES.md` 只约束工程规范；框架组件以技术选型附录 A 为权威（shadcn/ui+Radix、TanStack、Zod 等）。请求方向 app/<域>→service/<域>→service/http，fetch 只在 service/http。

## 文档规范
- 唯一入口 docs/README.md；分层 00-governance…99-archive；**冻结文档不得原地改**（升版+归档+改索引）；进度 append-only；归档文档链接指向归档副本；提交前跑 `docs/00-governance/check-docs.mjs`。版本/锁定项只在 02-solutions，M0–M8 只在 roadmap。

## 环境注意
- 本地 PG18：`D:/pg/bin/psql.exe`（不在 PATH），超管 postgres/123456，双库 ontos/ontos_queryds。
- java -jar 必须显式 `--server.port=8080`（环境注入 SERVER__PORT=51719）；Maven 根在 `ontos/` 子目录；后台服务锁 target——`mvn clean` 前必须停服务（MojoExecutionException 陷阱）；改 src/main 后必须 `mvn clean test`（增量 NoClassDefFoundError 陷阱 4 次出现）；`mvn clean test` 会删 jar，重启服务前需重新 package。
- jsonb 列 substring 要 `::text`；dry-run 审计 action 是 `DRY_RUN`；FUNNEL 顶层不能带 timeRange（时间窗只走 analysisParams.cohortWindow）、ATTRIBUTION 带 timeExpressionRaw 不带 timeRange。

## 不得破坏的口径（M2/M3 冻结）
YOY_MOM Join+filters 双 CTE；时间窗按上海墙钟；validateMode 先于路径规划；聚合模式拒绝 lookup 字段；rows 不带 __identity；lookup 缺失行同形；变体条件与指标同表；多口径缺变体必须澄清；「上季度」按 tep.last_quarter；FUNNEL filters=全局过滤。

## 并行义务纪律
阶段收口必须逐项点名欠账（触发点/负责人/证据）。机器强制：`docs/00-governance/parallel-obligations.json` + check-parallel-obligations.mjs。OBL-M7 触发 M6，status=deferred（2026-11-10 W5/T5.1 数据面 6/8 子件后仍欠失败看板+M7 报告）。

## M6 已完成验收（2026-11-11）

- W1–W4：模式构造器（Golden 23/0）；Scenario 资产化+五态路由（ADR-0004 3/3）；SkillRegistry/VQR 全链（生效清单 `2026.11.10-sixteenth`：8 skills+1 vqr+scenario.sales）；复合意图 resultRef+拓扑+部分失败（PoC#8 30/30）。
- **W5/T5.1–T5.5 全部完成**：OBL-M7 全量收敛 8/8（test v3 271 题/269 有效；分层接线 269/0/2/0；G-9 冒烟 20/20；消融 6+10；失败看板 V17 表+API+前端；PoC#9 11/11；压测并发16 P95=259ms；M7 收敛报告）；T5.2 对抗 8/8 全拒；T5.3 运维面（保留策略默认永久+线程池 4→16）；T5.4 移交核对（CopilotKit 观察项非阻塞、R-31 单域边界）；T5.5 live 基线 **7 PASS/0 FAIL/0 SKIP**。
- **M6 验收报告 v1 E1–E8 全满足，roadmap M6 已通过，OBL-M7 台账 done，检查器 deferred 0**；W5 收口+M6 全局审核通过。`mvn clean test` **628/0**；docs 133/1177。
- **T51/T53/T55 修复**：T51-1(P1) 场景确认误设 consumed；T51-2(P2) 注入审计双写 JSONB 失败；T51-3(P1) 中文注入启发 10 题仅 1 中（逐族扩充）；T51-5(P3) ArchUnit 拦 VO 带 DO；T53-1(P3) TIMESTAMPTZ cutoff 改 OffsetDateTime；**T55-1(P2)** PoC#7 需 `--ontos.policy.dev-allow-all=false` + 动态取 active 策略快照（V16 派生 `seed-2026.11.10-v2`，硬编码旧版会丢场景授权）。
- 关键教训：端到端是回归唯一可靠证明（T32-6/T45-1/T51-1/T51-3/T55-1 均单测全绿下真服务/基线抓到）；评测固定确定性降级路径；冻结证据哈希按磁盘字节；单独 -Dtest 需 -am + `-Dsurefire.failIfNoSpecifiedTests=false`；服务运行时别跑 mvn clean。
- **附条件项（非阻塞，按触发点）**：E-5 品类×订单事实指标（M7 资产决策）、E-6 auto 入口题（需 auto_if_confident 场景资产）、E-7 LLM 路径评测（M7）、CopilotKit spike（多域/AG-UI 稳定后）、R-31（多域 ADR）、T45-4（规格升版）。
- **下一步：M7 阶段**（评测、对抗防线与可观测的后续建设；LLM 路径评测需稳定模型服务与多次统计运行）。

## M6 全量深度审核勘误（2026-11-11）
- M6/M7 验收报告升 v2（v1 归档 99-archive）；证据升版：冒烟 v3、test/消融/压测/对抗/基线 v2（v1/v2 旧证据归档）
- **D-1（P1）**：ScenarioRouteGuardImpl 拆 adjudicate（纯校验）+ commitExplicitRoute（prepare 全链成功后提交）；场景状态 fail-closed E2E 5/5
- **D-18（P1）**：冒烟冻结证据与 gold v3 二稿不同源 → 重新固化 v3；教训：修判据后必须重新固化证据
- 其它修复：Wilson 有效分母、contentHash 三方比对、消融动态断言、全量 P95、DslValidator raw 必填、dependsOn 重复显式 null 等 500 类；详见 M6-全量深度审核-v1.md
- 附条件（M7）：纠正样本自动回流、VQR paramsSchema 运行时约束、复合 RuntimeException 子级隔离、Skill capabilities 发布期闸门
- 基线：mvn 631/0、live 7/0/0、docs 136/1234、OBL-M7 done（deferred 0）

## B0 语义模型三层化（2026-11-12 **已定档**）
- 文档：`docs/04-specs/语义模型三层化-影响分析-v0.2.md`（已定档；v0.1 归档）。35 条清单：**破坏 0 / 需论证 6 / 需设计 1 / 平移 13**（只统计 §4–§5 的 20 条判定项）。
- **D-1 解耦**：B0 与 M8 解耦、**按独立工作包**（不占 M 编号、不参与主关键路径、不设阶段依赖；roadmap §2 末有 B0 节，刻意不进阶段总览表以免被 `check-parallel-obligations.mjs` 当 M 阶段解析）。排期建议 A 立即启动（C-1/C-2 暂停窗口期，评测只锚 v20 一次）。
- **D-2 ADR-0021 不立**：与 M4 `policy_snapshot`（换角色走快照新版本、不改表结构）+ M6 catalog 资产生命周期重叠；四眼审批需求出现时再立。
- **D-3 Model 层**：B0 非目标、**不预留空段**；`Metric{objectId,propertyId}` 与 Model 正交，未来只给 Concept 加 `primaryModelId`。
- 三层 = 本体（Property 唯一持物理映射）→ 指标/维度（objectId+propertyId）→ 物理。约束：**E1 SQL 逐字等价**（Golden 三份比对）、**E2 迁移按物理列归属不按业务名**（`dim.channel`=`orders.channel`≠`channel.name`）。
- v1.4 §5.1 实为四层（含 Model），B0 压成三层，故定性「回归方案定义」而非架构变更。
- v19 需补 4 属性：`property.order.{customer_id,refund_amount,channel,region}`；前两者有同义不同体命名冲突，须入迁移验收。
- 治理坑：新建进度快照**必须带并行义务点名节**，否则 `check-parallel-obligations.mjs` 直接 FAIL。
- 实施规格：`docs/03-plans/stages/B0/B0-语义模型三层化-实施规格-v0.1.md`。**E1 可构造性证明**：`property.column` 是限定名、`metric.column` 是裸名 → 两路径同走 `columnName("<t>.<c>")`，SQL 逐字等价可推。`SqlCompiler` 实测 **13 处** `assetColumn`（L260 lookup 不动，12 处改）。FROM = `tableOf(property.column)` 断言 = `tableOf(identityProperty.column)`，v19 六 concept 全有 identityProperty（零新字段）。`metric.order_count` 必须继续挂 `property.order.order_no`，否则 COUNT(orders.id) 变、破坏 E1。
- **检索索引含 property**：`MentionIndex`（session L55–90）索引 metric/dimension/concept/**property** 四类 name+aliases → 新属性命名冲突是硬约束（`ManifestCandidateContextProvider` 只读 metric/dimension，不能代表全局）。
- **词表判据口径**：`termIndex` 是 `Map<String,List<IndexEntry>>`，**设计上一对多**，不能要求「唯一命中」。真冲突 = 同 term 指向**不同物理列**；v19 基线 2 处（「渠道」`orders.channel` vs `channel.name`、「渠道ID」）。维度与其底层属性**同列同名是惯例**（3 组 6 term），允许。
- cohort 路径不受三层化影响：retention FROM = `eventTable`（L434/L477）、attribution FROM = CTE（L688）；`conceptByTable` 只读 property。
- **错误码口径**：结构校验只产 `Violation`（`PublishServiceImpl` L95 汇总抛 `PUBLISH_VALIDATION_FAILED=1_001_000_007`），**不要为 R1–R7 新增专用码**；编译侧才需专用码（query 段 `1_002_xxx`，当前最大 046，B0 拟新增 `OBJECT_TABLE_MISMATCH=1_002_000_047`）。
- **D-3 对象级计数**：元模型 + 编译都实现（负责人已拍板），但不进迁移、无 Golden，单测断言 `COUNT(identity)` / `COUNT(DISTINCT identity)`——不接受"编译报未实现"（发布通过的资产必须可查）。

## B0 实施完成度（2026-11-12）
- **W1–W4 完成 + W5 dry-run 通过（未激活）**：mvn test **701/0**（基线 658）；E1 在 6 条路径上 SQL 逐字相等。
- 关键类：`SemanticManifest.BindingForm`（四态，含 `RULE_ONLY`）+ `BindingResolver`（形态分流唯一收口；**仅指标做 FROM 断言，维度不做**）。
- **`metric.order_count` 是 `COUNT_DISTINCT`**（不是 COUNT），E1 靠列相同成立——曾误判为 COUNT 并做错订正，被 W4 测试推翻。
- **R7 发布侧不可达**（`validateOntology` 前置覆盖），真正生效在编译侧。
- **VQR 失效会清空 vqrs**：v20 发布必移除 `vqr.sales.daily_gmv_by_channel`，`scenario.sales.defaultVqrIds` 悬空，发布后需重建 VQR + 重锚 golden。
- **W5 已在隔离库 `ontos_v2` 全链验证**：Golden 三份 check 全过（27+6+10，0 失败）、回滚可用；生产库 `ontos` 未动。
- **发布 ≠ 激活**：`POST /publish` 只落库，`POST /{id}/activate` 才切清单；VQR 失效分析读 active payload，active 空时不触发。
- **`m3-golden.mjs`/`m6-golden.mjs` 默认 backfill**，必须显式 `--mode=check`，否则覆盖 Golden。
- 新库初始化三件事：Flyway 自动建表、queryds 跑 `db/queryds/V1__rls_demo.sql`、**手动 `CREATE EXTENSION pgcrypto`**（否则 `digest()` 不存在）。
- **v21 渠道维度升级（2026-11-12）**：`dim.channel` 是订单冗余列（`orders.channel`），只碰订单一个对象；新增 `dim.channel_name`（`concept.channel` + `property.channel.name`）走 `Order.attributedToChannel` JOIN，两维度并存。订单项指标未加（两跳）。
- **发布 VQR 四步**：加维度授权会改 metric 定义 → VQR 失效。必须「发布→激活→再发→激活」，跳过激活直接再发无效。同一资产两次发布 contentHash 不同（VQR 清理改 payload）。
- 基线：mvn **706/0**；Golden 三份 27+6+10 全过（v21-r3）。
- **生产库已上线（2026-11-12）**：`ontos` 库 active = `2026.11.12-twenty-first-r2`（6 维度 / 23 属性 / 1 VQR，contentHash `cec57068…`）。四步发布：`twenty-first`(vqrs:0) → 激活 → `-r2`(vqrs:1) → 激活。Golden 三份 27+6+10 全过；重启加载 `selectLatest()` = r2。
- 遗留：两张快照并存（`twenty-first` 无 VQR / `r2` 有 VQR）；旧形态退役登记未做。
- **VQR 链已验证**：`scenario.sales` → `vqr.sales.daily_gmv_by_channel`（dimensions=`dim.channel_name`）闭合；运行时「近 7 天各渠道成交金额」返回线上 3499 / 线下 129，`VQR_HIT` 审计在。
- **缺陷 AUDIT-EXT-COLUMNS-EMPTY（P2，未修）**：`audit_log` 11 个扩展列全空（vqr_id/scenario_id/skill_id 等）。根因：`AuditService` INSERT 只写 22 列 + DTO 无字段 + 写入端只塞 dsl JSON。

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

## M6 进度（进行中，2026-11-10）
- W1–W3 完成：模式构造器（Golden M6 23/0）；Scenario 资产化+五态路由（ADR-0004 核对放行）；SkillRegistry 注册制+近似降级+VQR 全链（v16=sixteenth 激活：8 skills+1 vqr+scenario.sales）。
- W4 完成（commit `4d57c5c`）：《结果引用规格 v1》冻结+JGraphT 拓扑+resultRef 注入+部分失败语义（T45-1/T45-2 P1 修复）+PoC #8 复跑 30/30。
- **W5/T5.1 评测数据面收敛（commit `406c2b0`，6/8 子件）**：smoke v2 20/20（G-9 六 skip 转 ask 实测+拒绝映射收紧）；test 集 v3 271 题（E-1 场景 15+E-2 增量 30 闭环）；test-runner v1 首轮 269/0/2/0 成功率 1.0；消融 6 组+10 N/A；PoC #9 11/11；压测 P95 259ms@并发16 SLO 全满足。正式评测集 v4。
- **T51 修复（真服务评测驱动）**：T51-1(P1) 场景确认误设 consumed→契约拒答（确认只迁移状态）；T51-2(P2) 注入审计冗余双写 JSONB 失败（统一走 checkpoint）；T51-3(P1) 中文注入启发 10 题仅 1 中（正则逐族扩充+空格容差+误伤兜底）。
- 关键教训：端到端是回归唯一可靠证明（T32-6/T45-1/T51-1/T51-3 均单测全绿下真服务抓到）；评测执行路径固定确定性降级（E-7 LLM 评测登记 M7）；auto 入口需 auto_if_confident 场景资产（E-6）。
- 基线：`mvn clean test` **617 例/0 失败**；docs 128/1124。
- **欠账**：T5.1 残——失败看板 5 维下钻+纠正样本回流（表+API+前端 audit 域）、M7 验收报告+OBL-M7 置 done；E-6 auto 题；E-7 LLM 评测。
- **下一步**：T5.1 残项 → T5.2 对抗扩充 ≥7（Scenario 越界/DSL scenarioId 直传 G-2/VQR 越权/Skill capabilities 缺失/未声明近似/冷启动注入/dependsOn 篡改）→ T5.3 运维面（ask_run_event 保留策略 G-5+run 线程池；拒绝映射已随 G-9 完成）→ T5.4 移交核对（CopilotKit 复查 ADR-0010/R-31 边界声明）→ T5.5 基线全绿+M6 验收报告。

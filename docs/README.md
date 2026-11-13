# Ontos 文档规范与总索引

> **状态**：已冻结
> **版本**：v2.70
> **类型**：规范 / 索引
> **负责人**：项目负责人
> **最后更新**：2026-11-12
> **适用范围**：`docs/` 下全部文档，以及仓库内所有引用 `docs/` 的代码与配置
> **上游依据**：v1.4 完整可落地方案 §16 引用边界、v1.1 技术选型 §10 事实台账
> **修订记录**：2026-11-06 v1.0 首次发布；建立分层、命名、生命周期与单一事实源规则；2026-11-07 v1.1 索引维护——M0 验收报告 v2 勘误版、ADR-0006、版本事实台账、基线自检归档入索引；2026-11-08 v1.2 M2 阶段索引维护——补登记 M2 实施规格、funnel/yoy_mom 规格、ADR-0009~0011、Golden 集 v2 案例设计、冒烟集 gold、11-08 进度快照；2026-11-08 v1.3 补登记 ADR-0012（EventDef 事件判定映射扩展与清单 v2）；2026-11-08 v1.4 M2 收口索引维护——补登记 M2 验收报告 v1（已冻结，E1 按 ADR-0013 附条件关闭）、ADR-0013、M2 基线自检、Golden v2 gold、复合意图 M2 回填、PoC-01 M2 回归与 M2 E5 对抗探针（本文件路径固定为唯一入口，升版以修订记录承载，不迁移归档）；2026-11-09 v1.5 M2 收口后深度审核入索引——补登记《M2 收口后深度审核 v1》（12 项发现闭环、R-18 关闭、M2 判定不变）；2026-11-09 v1.6 M3 开工——补登记《M3 简单问法最小闭环 实施规格 v0.1》（草稿，待确认三个设计决策）；2026-11-09 v1.7 M3 规格冻结——三项决策确认，升 v1.0 并把 v0.1 移入 99-archive；2026-11-09 v1.8 M3 收口——补登记《M3 验收报告 v1》（E1–E6 满足，M5 放行）与 M3 Golden 增量；2026-11-09 v1.9 M5 开工——补登记《M5 会话意图编排与 AG-UI 链路 实施规格 v0.1》（草稿，待确认三个设计决策）；2026-11-09 v2.0 顺序修正（负责人定档按 ADR-0014 纪律先 M4 后 M5）——OBL-M7 最小框架三件入索引（正式评测集 / 统计口径 / 零号冒烟 runner 证据），零号冒烟集与 gold 升 v2（D63 哈希语义修复），M5 规格保持草稿待 M4 后冻结；2026-11-09 v2.1 M4 开工——补登记《M4 身份权限与安全治理 实施规格 v0.1》（草稿）；2026-11-09 v2.2 M4 规格冻结——三项决策确认升 v1.0，v0.1 归档；2026-11-09 v2.3 M4 规格升 v1.1——吸收外部审核（P0×3/P1×3/P2×4 采纳，P1-4 拒绝并留痕）与两轮内部自审 12 项（角色并集/多副本 TTL/目标指纹/§4.8 字段对齐等），v1.0 归档；2026-11-09 v1.9 并行义务纪律落地——补登记 ADR-0014、并行义务台账与强制检查脚本，新增 §7.8 硬条款；2026-11-09 v2.4 M7 最小框架验收闭环——真实复跑冒烟 runner 并固化版本化证据、冻结《M7 最小框架验收记录 v1》、新发 ADR-0015 重定 OBL-M7 触发阶段（M4→M6）、roadmap M4/M7 状态同步；2026-11-09 v2.5 M4 收口——补登记《M4 验收报告 v1》（E1–E6 满足，M5 放行）、W1–W4 四包深度审核与《M4 收口后深度审核 v1》（G-1~G-4 修复）、并行义务台账 OBL-M4 闭环、roadmap M4 状态同步；2026-11-09 v2.6 M5 规格深度 review——v0.1 归档，v0.2 修订稿（13 项发现：过时基线 5 / 设计缺口 6 / 决策扩充 2；补 M4 移交前置与 M7 提及样本回填义务，E7 新增），决策点 6 项待定档；2026-11-09 v2.7 M5 规格冻结——负责人确认六项决策（双模块/终态澄清/不引入拼音/HITL 轮询/事件表重放/composite 透传）升 v1.0，v0.2 归档，编码待两轮自审后放行；2026-11-09 v2.8 M5 两轮自审闭环——8 项发现全部采纳（**F-5 P0：execute_query 不得暴露为 LLM 工具，执行是编排代码内部步骤**；F-1 会话合并模型澄清=DSL 投影；F-2 L3 改 Java 编辑距离；F-4 批量授权视图接口；F-3/F-6/F-7/F-8），升 v1.1，v1.0 归档，编码放行待负责人确认；2026-11-09 v2.9 M5 第三轮自审（攻击者/故障注入/时序视角）——5 项发现全部采纳（**R3 P1 WAITING_APPROVAL 期间拒绝新 run；R4 P1 run 启动扫描崩溃恢复；R5 P1 澄清候选持久化+回传校验**；R2 run 创建=turn 占用原子性；R1 投影措辞下游同步），升 v1.2，v1.1 归档，编码放行待负责人确认；2026-11-09 v2.10 M5 收口——W1–W5 全部完成（会话状态机/检索瀑布+清单 v9/LLM 编排器+清单 v10/终态与 HITL 内联/端到端对抗），《M5 验收报告 v1》冻结（E1–E7 满足，PoC #3 复跑 20/20 收口 G2 遗留，live 基线 7 PASS，ask-probe 29/29），真实模型冒烟登记上线前检查项，M6 放行；2026-11-09 v2.11 M6 规格冻结——补登记《M6 实施规格 v1.1》（两轮自审 11 项 + 深度 review 10 项闭环、八决策定档、编码放行）、《analysis-mode · retention 与 attribution 规格 v1.1》（v1 归档 99-archive/specs）、清单 v11 与《M6 阶段中深度审核 v1》（P1 回归：V3 事件 ID 与 funnel 证据脚本主键冲突已修复复验；OBL-M7 题目编写登记为硬欠账）；2026-11-10 v2.12 D-3 复审——test 集 v1 经逐题复审判定不通过并升 v2（修正 shape/mode、补齐分析模式参数、对齐清单与 seed、哈希改磁盘字节复算，skip 2 / 有效 239），正式评测集升 v3 登记新哈希，新增缺口 E-5（品类×订单事实指标粒度扩散）与《M6 · W5/D-3 test 集再次深度审核 v1》；2026-11-10 v2.13 M6/W2 T2.1——新增 `ontos-module-catalog` 管理面模块与 V15 迁移（scenario/skill/vqr 生命周期表 + audit_log 十一列扩展）、ArchUnit 增两条边界规则，新发 ADR-0016 定档品类×订单事实指标的粒度边界；2026-11-10 v2.14 M6/W2 T2.2——ScenarioMeta 升级 v1.4 §5.7 完整模型并兼容 v12 扁平形态、§5.7 校验器与 catalog 生命周期/回滚/依赖影响落地、清单 v13 通过 dry-run 预检（未激活，T2.4 统一升版）；2026-11-10 v2.15 外部评审复核——7 项逐条裁决入 ADR-0017（采纳 5 / 修正 2），scenarioId 顺序、依赖引用 schema 方向、近似同意通道、VQR 执行证据、留存标注、E5 映射、R-31 核对项；2026-11-10 v2.16 M6/W2 T2.3——五态路由决策与三态会话状态机落地（pending_confirm 复用 M5 澄清回传）、ADR-0004 三触发条件核对 v1（2/3，路由不启用）；2026-11-10 v2.17 M6/W2 T2.4——Router 接入瀑布 + scope 裁剪 + 直传 scenarioId 裁决（D1 四步）+ 编排模式过滤，ADR-0004 核对升 v2（3/3 放行），v13 发布并激活（thirteenth），Golden 回归零回退；2026-11-10 v2.18 M6/W3——T3.1 Skill 资产模型与生命周期（审核人记录）+ 清单 v14 七条内置 Skill、T3.2 SkillRegistry 清单驱动注册制与编译期四条闸门（复审 T32-6 P1 级回归修复：双态兼容 + v14 激活 fourteenth，端到端 8 项 + Golden 零回退）；2026-11-10 v2.19 M6/W3 T3.3——近似降级机制闭环（声明→同意→MODE_APPROXIMATED 审计，清单 v15 激活 fifteenth），端到端驱动出 T32-8（P1）场景确认断链并修复；2026-11-10 v2.20 M6/W3 T3.4——VQR 资产模型与生命周期（入库七条件，D4 证据外置）+ 自动失效六族接入发布流水线（F-7）+ 清单 v16（vqrs 段 + 场景引用闭环）；2026-11-10 v2.21 M6/W3 T3.5——VQR 命中通道闭环（瀑布第 7 步 + 提案引用 + 八步重校验 + VQR_HIT 审计 + cache-key-1.2），v16 激活 sixteenth，W3 收口；2026-11-10 v2.22 M6/W4——冻结《复合意图结果引用规格 v1》（ADR-0017 D2：resultRef Schema + single_row/ordered_top 分立 + G-6 失败码 044–046）；2026-11-10 v2.23 M6/W4 收口——T4.1 依赖拓扑（044）+ T4.2 结果注入（参数化绑定）+ T4.3 部分失败可解释（双路径统一）+ T4.4 R-27/R-29 + T4.5 PoC #8 复跑 30 题全部符合预期（证据 复合意图题集-v2-M6复跑.json）；2026-11-11 v2.24 M6 收口与 M7 开工索引维护——补登记《M6 验收报告 v2》《M6 全量深度审核 v1》《M7 全量交付物收敛验收报告 v2》《M7 实施规格 v0.1（草稿）》与 M6-W5 T5.1/T5.3/T5.4/收口审核、11-10/11-11 进度快照；M6 实施规格状态改为「已通过验收」；评测集与证据版本列对齐实际文件（正式评测集 v4、test 集 v3、零号冒烟集/证据 v3）；2026-11-11 v2.25 M7 规格冻结——v0.1 经 13 项深度 review（4 项 P1：意图一致率口径、§13.3 十九项处置、温度不可控事实、capabilities 词表跨模块边界）后升 v1.0，八项决策定档（E-5 订单项级指标 / E-6 `scenario.sales.auto` / LLM 路径 ×3 次运行 / 基线对照 N/A 豁免 / 回流只进 dev-validation / 不新增 eval 模块 / SLO 前置冻结），v0.1 归档；2026-11-11 v2.26 M7/W1/T1.1 E-5 落地——ADR-0018 冻结（订单项销量 `metric.order_item_quantity`，订单级 × 品类仍 fail-closed）；清单 v17（`2026.11.11-seventeenth`，contentHash `1d3ec2e2…`，9 指标）；test 集升 v4（273 题 / skip 0，2 题哨兵转预期拒绝 + 2 题销量正例）与正式评测集升 v5；v3/v4 归档；补登记 E-5 首轮评测证据；2026-11-11 v2.27 M7/W1/T1.2 E-6 落地——清单 v18（`2026.11.11-eighteenth`，`scenario.sales.auto` + V18 demo 授权 `seed-2026.11.11-v1`）；test 集升 v5（276 题，auto 3 题 + `session_state` 会话态断言）与正式评测集升 v6；E-6 关闭；v4/v5 归档；2026-11-11 v2.28 M7/W4/T4.4 D-22 三项加固——VQR `paramsSchema` 运行时 type/min/max（DSL_INVALID）、复合非 `ServiceException` 子级隔离（QUERY_EXECUTION_FAILED，堆栈只进日志）、Skill capabilities 发布期闸门（词表下沉 `SkillCapabilities`，`SkillValidator` 校验）；新增《SLO 与成本预算 v1》冻结延迟/错误率/超时率与 LLM token 预算；2026-11-11 v2.29 M7/W4/T4.1–T4.3 失败看板闭环——V19 状态/分类事件表（V17 只追加不改写）、状态机（REGRESSED 仅回流通道）、根因分类 30 类闭词表、回流只允许 dev/validation（禁止 test），E2E 正链 7 步 + 4 负例通过并固化证据；2026-11-11 v2.30 M7/W4/T4.3 失败看板前端卡片与五维联动——看板新增单维联动过滤（`drillDimension`/`drillValue`，分布仍基于全集，非白名单维度/空值/非布尔 `is_composite` 一律 `1003000012`）+ 根因分类闭词表接口 `GET /categories`；前端 `audit` 域新增处置卡片（状态流转/根因分类/退回回归集 + 事件时间线）、维度点击联动、题集选项更正为 test-v5；处置逻辑抽为纯函数 `failure-lifecycle.ts` 并补 5 例单测（`pnpm check` 26 例全过、`vite build` 通过），证据 `失败看板T4.3联动证据-v1.json`（8/8 探针）；2026-11-11 v2.31 M7/W5/T5.1 模型调用追踪——V20 `llm_call_trace`（只追加、只存元数据不含提示词/输出）、框架层 `TracingModel` 装饰器（完成/错误/取消三终态各留一条，sink 异常不冒泡）、`LlmCallTraceSink` 下沉 `ontos-common`（system 实现 JDBC sink，starter 不反向依赖业务模块）、查询接口 `GET /api/v1/evaluation/llm-calls`；规格升 **v1.2**（OTel bridge 事实修正：复用请求级 traceId，exporter 归 M8）；真服务探针 8/8（本地 OpenAI 协议桩，只证追踪链路不代表模型效果）；2026-11-11 v2.32 M7/W5/T5.2 压测——`load-m7.mjs` 三档 × 3 轮取中位数（并发 1/8/16 P95=19/49/80ms，错误 0、超时 0），对 M6 基线 0.552×；LLM 抽样 12 trace 无丢行、单 trace ≤3 次调用 ≤432 token（模型侧为本地桩，不代表真实模型）；证据 `压测-M7证据-v1.json`；2026-11-11 v2.33 M7 收口——新增《M7 验收报告 v1（有条件通过）》与 ADR-0019（E3/E-7 与 E4 的 LLM 新面挂账为 C-1/C-2，触发点=模型三件套+预算就绪后 5 个工作日，M8 发布/灰度门禁未开）；roadmap M7 状态改「已通过（有条件）」；live 基线 7 PASS / 0 FAIL / 0 SKIP、`mvn test` 658/0、docs 147/1457；2026-11-11 v2.34 E-7 启动（C-1 部分推进）——模型锚定 `mimo-v2.6-flash`（T2.1，温度不可控声明）；**发现并修复 P1**：LLM 提案带相对时间必然 `TIME_PARSE_FAILED`（`TimeDimensionResolver` 按清单推导时间维度，仅 QUERY/YOY_MOM）；清单 **v19**（`2026.11.11-nineteenth`，contentHash `377d4616…`，补「客户数/订单数」别名）；单轮 250/276 实测：严格通过率 32%、对比降级 10.6%（McNemar p<0.001）、过澄清率 47.2%（根因=检索别名缺口+维度取值词表缺失+mode 表达缺口）；token 均值 20.7k **突破冻结预算 8k**（不追溯放宽，登记优化项）；T3.2 攻击面 **6/6 全拒**；用户要求止损，×3 轮待预算确认；2026-11-11 v2.35 E-7 三轮迭代——YoyPeriodResolver（yoy 缺 period 组装缺口）+ 提示词 v3（lookup/yoy 示例与取值规则）+ 判据 v2（filters/LOOKUP identity 判定与 waiting 轮询）；30 题定向复测 56.7%→66.7%（判据修正后 70%），composite/agg 全过；更正归因误诊（澄清 matchedTerm 为模型自造非检索注入）；剩余失败对应取值翻译/lookup 遵循两登记项；2026-11-11 v2.36 产品前端立项——负责人指出前端为调试页非产品后，新立《产品前端 实施规格 v1.1》（M8 工作包，两轮深度审核闭环：R1 13 项 / R2 14 项全裁决；run 事件流唯一事实源 + Session–Run–Turn 模型 + 会话固定 session_id + 候选域收敛纳入 + 登录/会话历史后端配套 W-B0–B4 + 前端输入不可信红线 + workspace 隔离与 W-B2 联动定档；CSV 导出砍除；v0.1/v0.2 归档）；D1–D8 决策点待负责人定档，W0 渲染路线 spike 为首个任务；2026-11-11 v2.37 R3 全局审核（产品整体规划第三轮，六维：账实/交付链/门禁/风险归属/口径纪律/盲区）——12 项发现 P1×4（F-1 台账漂移：C-2/T3.2 实为 6/6 已闭环；F-2 ×3 口径未冻结；F-3 token 超冻结 SLO 两倍无决策；F-4 workspace 隔离无 ADR 载体）+ P2×5（降级路径 10.1%、用户口径发布流程、M8 上线规格未立、数据接入边界、租户运营面）；当场修复 roadmap M7 行/根 README 基线/索引（含 R1/R2 行补登）；决策批 D-A…D-F + D1–D9 待负责人；补 R1/R2/R3 三行入索引表；2026-11-11 v2.38 产品前端规格 R4 独立第三轮审核——12 项（P1×5：H-1 D1=CopilotKit 直连与唯一事实源是两套 run 空间、H-2 身份模型网关/登录/禁 Web Storage 三处互斥、H-3 七条判据依赖未引入的 Playwright、H-4 首字 ≤2s 与真模型 8s 冲突、H-5 W-B2 实为引入认证机制且 token 驻留无规则；P2×5、P3×2）；采纳 11 并入《产品前端 实施规格 v1.1》（新增 D10 身份 SSOT 与登录态驻留、W-E0 E2E 工具链、W-B2 拆 a/b、性能口径拆分、E1 固定桩路径等），v1.0 归档 99-archive/plans；拒绝 1（H-11 E9/E10 口径，verify-baseline 已覆盖 pnpm check）；2026-11-12 v2.39 M7 GLM 锚点切换——补登记《模型服务锚定 v2》（GLM-5.3-flash @ BigModel OpenAI 兼容端点，v1 归档）、《E-7 GLM 30 题预演 GLM-v1》（20/30、均值 11,974 token、超 8k 预算如实登记）与《LLM 攻击面探针 GLM-v1》（6/6 全拒）；正式 ×3 按负责人「不用预估 直接跑」指令启动；2026-11-12 v2.40 产品角色模型决策——补登记 ADR-0021（平台四角色与资产审批发布模型，草稿待终审：四权分立 / 两步激活 / 平台级超管，现状核查 + 角色拍板）与 11-12 进度快照；2026-11-12 v2.41 外部模型评测策略纠偏——补登记 ADR-0022（复杂度优先 + 外部 API 安全模式，草稿待终审）、《E7 复杂度子集 v1》（50 题 / 57 轮，不替代原 C-1）与《模型服务锚定 v3》（回到 mimo；GLM / mimo 全量任务停止，仅探索性证据；v2 归档）；2026-11-12 v2.42 mimo 复杂度诊断固化——补登《E7 复杂度子集诊断 MIMO v1》（50/50 完成，零 token 重判 21/50，探索性不解除 C-1）与 runner 判据 v2.1 复合期望修复；2026-11-12 v2.43 复杂模式口径与 bool-08 排查——补登 ADR-0023（自由问数允许 FUNNEL / RETENTION / ATTRIBUTION / LOOKUP_BATCH，提示词 v4 后续承载）与《E7-lookup-bool-08排查 v1》（runner 断点复用 request_id + 确定性 planner 布尔指标误用 QUERY）；2026-11-12 v2.44 E7 失败三档归因——29 个失败拆为资产/planner、能力边界/提示词、模型表达与工具链，P0 planner 修复 + P1 提示词 v4 + P2 资产词表 + P3 小批量复测；2026-11-12 v2.45 ADR-0023 深度评审闭环——升 v1.1：补候选表达通道与“模式能力声明”、三档失败归因可操作规则、terminal+mode 双检查、M6 E1 证据与 token 观测口径；v1 归档；2026-11-12 v2.46 复杂模式自由问数实施草案——补登《ask·复杂模式自由问数表达与参数补全 规格 v0.1》（提示词 v4 草案 + ComplexAnalysisParamsResolver + FUNNEL/RETENTION/ATTRIBUTION/LOOKUP_BATCH 参数补全与小批量验收），并登记确定性 planner 布尔分支修复；2026-11-12 v2.47 v4 FUNNEL 冒烟固化——resolver 实现后首轮 4 题 2/4，提示词 rev2 补测失败 2 题 2/2；探索性证据不解除 C-1；2026-11-12 v2.52 B0 立项分析（勘误：原编号 v2.42 与 mimo 诊断条目重复；v2.47 又与并行会话 FUNNEL 固化条目撞号，二次顺延为 v2.52）——补登记《语义模型三层化 影响分析 v0.1》（34 条逐项结论：破坏 0 / 需论证 6 / 需设计 1；建议并入 M8 前置单独验收；v19 资产映射实证与 dim.channel 陷阱；归位 02-solutions/architecture）；2026-11-12 v2.48 前端表单库选型定档（勘误：B0 补登条目编号最终定为 v2.52，历经 v2.42→v2.47→v2.52 两次勘误）——负责人拍板「采用」TanStack Form，补登记 ADR-0024（附录 A 表单行的生效解释：TanStack Form + Zod Standard Schema 直连、不引入 resolver、适配层归共享 UI 包；技术选型 v1.1 正文不动、不归档、不迁移链接，v1.2 全量收口另立独立工作包）、同步 `AGENTS.md` / `FRONTEND_RULES.md` / `tooling.md` / 根 `README.md` 口径与 `apps/web` 依赖收敛；2026-11-12 v2.49 v4 FUNNEL 冒烟证据索引——ComplexAnalysisParamsResolver 实现后 FUNNEL 首轮 4 题 2/4，提示词 rev2 补测失败 2 题 2/2；补登 ask·复杂模式自由问数规格 v0.1 与《E7复杂模式自由问数V4冒烟证据 v1》；探索性证据不解除 C-1；2026-11-12 v2.50 表单适配层提升共享 UI 包（并发编辑冲突修复：本条原编号 v2.49 与并行会话的 FUNNEL 证据条目撞号，两条按写入时序重排，适配层条目顺延 v2.50）——ADR-0024 后续动作 1 闭环：`packages/ontos-ui/src/form/FormBinding.ts` 导出 `fieldProps` / `firstError` / `FieldLike`（结构类型零外部依赖，共享包不感知表单库），spike 改为消费正式组件（vitest 10/10 回归），无新增文档；2026-11-12 v2.53 适配层二次 review 补强——修正 FormBinding 头注错误示例（Input 不接受 error，spread 不触发多余属性检查会漏 DOM，改「error 拆给 FieldError」正确示范）、新增 FormBinding.test.mjs 直接单测 7 例并入 test:unit（26→33），`pnpm check` EXIT=0；无新增文档；2026-11-12 v2.54 **B0 语义模型三层化主干实施完成并上线**——实施规格 v1.1（W1–W5 + v21 渠道维度 + §11 待办登记）；`SemanticManifest` 双形态（objectId/propertyId + BindingForm 四态）、`BindingResolver` 收口 12 处物理列解析、资产 v20（三层化）/v21（`dim.channel_name` 走渠道对象）/v22（场景 scope 补维度）；生产库 active = `2026.11.12-twenty-second`（contentHash `feda6dd1…`，6 维度/23 属性/1 VQR）；E1 Golden 27+6+10 逐字全过、mvn test 706/0；双形态回滚演练通过；顺带修复 audit_log 11 个扩展列从未写入（INSERT 22→33 列 + DTO + 写入端）；治理收口 5 项待办见实施规格 §11（发布端形态闸门 / 证据重跑 32 条 / 文档重锚 / 场景审计三字段 / 旧形态退役）；2026-11-12 v2.55 **B0 治理收口 5 项处置**——补登记《语义模型三层化 冻结口径生效解释 v1》（走 ADR-0024 生效解释路径，三份已冻结文档零改动）与《B0 证据重跑处置台账 v1》（33 份分层：A 5 / B 15 / C 7 / D 6）；实施规格 §11 追加各节处置段与 §11.7 四项裁决留痕（发布端夹具改造范围收窄、置信度列恒 null 属规格裁定、证据重跑按 ADR-0015 适用边界分层、冻结文档不原地改写）；P1 发布端形态闸门、P2 文档重锚、P3 场景审计两列均闭环，并修复主审计路径 `buildAudit` 漏挂 `attachScenarioAudit` 的 P1 缺陷；旧形态退役按 2026-12-12 窗口保留；2026-11-12 v2.56 v4 多轮复测与投影修复（编号重排：本地原 v2.54/v2.55 与 B0 条目撞号，随合并顺延）——补登《E7复杂模式自由问数V4多轮复测证据 v1》（初始 1/4、投影注入 1/4、契约对齐前 0/3）；实现 SessionProjection 语义槽位注入、异步修复轮问题感知候选快照、LOOKUP 属性完整性回退、LOOKUP_BATCH identities 判据 v2.2 与编排契约校验上下文一致；多轮未闭环，不解除 C-1；2026-11-12 v2.57 E-7 多轮继承两层授权口径修复——继续 3 道 QUERY 多轮题小批量，逐层定位「输出契约词表」与「来源完整性对账」只认本轮检索候选、与提示词「按 SessionProjection 合并完整语义」冲突；投影资产经「当前清单可解析 + 当前策略快照授权」双重过滤后并入两层授权集，修复后端到端 **3/3**；补登《E7复杂模式自由问数V4多轮复测证据 v2》与索引行；探索性证据不解除 C-1，v4 仍为草案未切运行时；O-1~O-5 观察项登记；2026-11-12 v2.58 多轮投影修复收口审核——**D-1（P2）**：「投影已注入」不得靠提示词文本判定（用户话术同样可写出标记串，能绕过「泛化问法必须澄清」）→ 改为服务端状态入参：`AgentContextProvider.projectionInjected()` + `IntentProposalContract.validate(text, question, boolean)`，编排层按 `SessionProjection` 非空传值、`OntosAgentFactory` 按 Provider 状态判定；新增契约单测 2 例（伪造标记仍判 `ASSET_NOT_MENTIONED`、显式注入时 ID 白名单不放松）；合并 origin/main 33 个提交（B0 语义模型三层化主干与治理收口 5 项），本地证据行随合并重建；2026-11-12 v2.59 O-1~O-5 收口复核——runner 读 `.env.local` 记录 model/modelSource（O-1）；澄清终态透传 degraded + 判据 v2.3 降级题 skipped（O-2）；prime 生命周期覆盖二次校验，词表同口径（O-3）；基线口径固定为 surefire 计数 743/0（O-4）；发布端形态闸门 + 默认启动加载 v22 关闭非法清单绕过（O-5）；补登《E7多轮投影收口复核证据 v1》（多轮 3/3，LOOKUP 单题归因模型自选 out_of_capability）；2026-11-12 v2.60 B1 语义定义工作台立项——补登《B1 · 语义定义工作台 实施规格 v0.1》（独立工作包，不占 M 编号；D1–D4 定档：数据视图资产 / 编辑态分文件发布态合成清单 / 资产级 draft / 升级 scenario 为知识网络；P0 范围＝五类语义资产统一草稿表 V21 + catalog 7 个管理端点 + 合并视图校验 + revision 乐观锁；OpenBKN 蒸馏的借鉴项与三条不借鉴项见规格 §0.2；Q1–Q3 实现级决策待负责人确认）；2026-11-12 v2.61 **B1 P0 实施完成**——V21 `semantic_asset_definition` + catalog 仓储/生命周期/合并视图校验/7 端点/8 错误码 + 21 例测试；`mvn clean test` **764/0**（84 份 surefire）、ArchUnit 9/9、docs 182/1830、并行义务 deferred 0；实施期 7 项偏差与未闭环见规格 §10；2026-11-12 v2.62 **B1 P0 真服务闭环**——V21 经 Flyway 实际执行（`Successfully applied 1 migration`，13 列）、7 端点 HTTP 冒烟全通（revision 0→3，旧 revision 返回 `1005000104`）、`dev-allow-all=false` 下管理端点 `403` 且对照端点正常；补登《B1 P0 真服务冒烟证据 v1》，active 未变、冒烟草稿已删；未闭环仅剩 P1–P3；2026-11-12 v2.63 **B1 P1-A 语义定义页面**——新增 `GET /catalog/assets/options`（对象/属性/维度/指标/关系，property 带 conceptId）+ `/ontology/definitions` 页面（指标/维度草稿编辑、对象→属性联动、校验、提交审核、撤回）+ 导航路由；前端 `pnpm check` 39 例（新增 schema 6）与 `pnpm build` 通过，后端 **765/0**，真服务 options 6/23/6/9/7；偏差 4 项见规格 §11.3，P1-B/C/D 未开始；2026-11-13 v2.64 **B1 产品骨架（P2-A 只读）**——导航按 问数/语义建模/治理/调试 分组，`/` 默认 `/ask`、原型保留 `/prototype`；新增 `/data/sources`（只读连接 + 6 张物理表引用）、`/ontology/modeling`（对象 6/信息项 23/关联 7，搜索+详情）、`/debug`（DSL/能力调用/AG-UI 迁入），`/ask` 只留产品对话；新增 `GET /catalog/data-sources`（URL 脱敏、按 Property.column 反推表）并扩充 `options` 的 domain/summary/propertyCount/from/to；后端 **769/0**、前端 39 例 + build、8090 临时实例真服务验证；证据 `B1-产品骨架真服务证据 v1`；2026-11-13 v2.65 **B2 数据源插件化 Phase 1**——新立独立工作包 B2（借鉴定档见规格 §0：抄 SPI/ServiceLoader/fail-fast 注册表/Gateway 异常映射/DriverShim 外置驱动，不抄封闭枚举/裸 password getter/**SQL 执行进 SPI**）；新增 `ontos-spi-datasource`（纯 Java 契约，`String type` + `Secret` 遮蔽）、`ontos-plugin-jdbc`（PG，information_schema 目录，connectTimeout 显式声明）、`ontos-module-datasource`（注册表 + 4 端点 + 1_006 错误码）；catalog 侧旧数据源实现移除；ArchUnit +2 条（datasource 不依赖运行时模块 / 仅 server 可依赖）；数据源页升级为真实库目录（7 张表 + 列元数据 + 插件卡片）；后端 **804/0**（89 份，+37/-3）、前端 39 例 + build、8090/8091 真服务验证（含 403 闸门）；证据 `B2-数据源插件真服务证据 v1`；2026-11-13 v2.66 **B1 P2-B 本体建模编辑器**——对象/信息项/业务关联三张草稿表单（TanStack Form + Zod，复用 B1 资产接口与控件契约），信息项 column 从 B2 数据源插件真实目录三段选择（schema→表→列，选中列自动映射 valueType）；「建模顺序」语义首次端到端打通（数据源 → 本体 → 指标）；真服务 8090 验证 7 步：对象归属校验拒（identityProperty 不属于该对象）、跨草稿引用拒（conceptId 引用未发布对象，独立草稿校验 fail-closed）、关联正向校验通过、保存→提交审核 rev 0→1→2；冒烟行已删；前端 39 例 + build；真服务闭环证据并入 B1 规格 §13.2；2026-11-13 v2.67 **B1 P1-B 合成发布**——负责人三决策定档（全量 reviewing 批次 / 审批不卡点 / 沿用 PublishGuard，规格 §14.1）；`SemanticAssetSynthesisService` 复用既有发布链路（dry-run 预检自动递增序数词 manifestId → confirm → activate → markPublished 写 published_at）；**草稿合并视图升级为「生效清单 + 全部待发布批次（draft+reviewing）」**，跨草稿引用（新对象+其属性）在提交阶段成立；`POST /catalog/assets/synthesize`（闸门）+ 错误码 109–111 + 建模页「合成发布」按钮与回执面板；ArchUnit 不变；真服务 8090 五步：跨草稿提交 ×2 → synthesize 生成 **2026.11.13-first**（concepts 6→7 / properties 23→24）→ options 可见 → 回滚演练切回 v22；`published_snapshot` 不可变触发器实证，冒烟发布按正式历史保留、资产行按 published 补回；全量 **810/0**（90 份）；证据 `B1-发布合成真服务证据 v1`；2026-11-13 v2.68 **B1 UI 补齐包**——生效资产修订为草稿（`POST /catalog/assets/{type}/{id}/revise`，清单为内容事实源：基线播种 / published 回迁清 published_at / 待发布草稿拒改 / 并发显式冲突；建模页详情卡 + 定义页「生效资产（修订）」入口）；共享 `CatalogColumnField` 列选择器接入关联外键列/被引用列（信息项同步重构复用）；`POST /data-sources/current/test` 测当前配置连接 + 数据源页按钮；AGENTS.md 补登记 1_005/1_006 分段；错误码零新增；合并树全量 **824/0**（92 份，本包净增 8 例）、前端 52 例 + build、8090 真服务四步验证；冒烟残留 `concept.channel` 草稿行清理 SQL 因安全审批超时未执行（已登记欠账）；2026-11-13 v2.69 **B2 Phase 2 前半（数据源注册 + 目录建模）**——负责人定档 A+B 都做 / 凭据 AES-GCM + 环境变量密钥（新立 [ADR-0025]）；Flyway V22 `datasource_config`（config JSONB 非敏感 + secret AES-GCM 密文列）；`DatasourceSecretCipher`（非法 key 启动 fail-fast、缺 key 109、篡改 110）+ `DatasourceConfigService`（强制测试落库/secret 按 descriptor 剥离/重名 108）+ `DataSourceConfigController` 7 端点 + 错误码 107–110；`summary.plugins[].configFields` 驱动前端动态注册表单；数据源页已注册分区/编辑/删除/测试 + 目录按源切换 + **列一键「建信息项」预填建模表单**（B1 P2-C 前半，URL query 跨 feature 零代码引用）；单测 +18、合并树 845/0（94 份）、前端 53 例 + build、8090 真服务七步（DB 密文核对/编辑密文不变/API 清理零残留）；记录两次并行会话撞车与一行 import 代补；2026-11-13 v2.70 **B3 立项（数据视图与资源层拆分）**——负责人定调主动借鉴 OpenBKN 双网络思想（领域网络 ⊥ 资源网络，唯一绑定点 = bind）；新立 [ADR-0026]：把 B0 D-3 触发点从被动改主动，仅引入 Model 的逻辑数据集子集（数据视图资产），事实粒度/JoinPath/方言触发点不变；B3 规格 v0.1（草稿待定档 D1–D4）：`data_view` 表（V23）+ 扫描固化 + 清单 dataViews 段 + `Property.column → viewId + column` + R-View 1–3 绑定校验 + `DataViewResolver` 编译解析 + 建模改视图资产选择器；E1 逐字等价为迁移主判据（B0 同款可构造性证明）；分期 Phase A/B/C；实施待负责人确认定档后启动
| ADR-0025 数据源凭据加密存储方案 | 决策 | **已接受**（AES-256-GCM + 环境变量密钥；fail-fast/fail-closed） | v1.0 | [`01-decisions/ADR-0025-数据源凭据加密存储方案.md`](01-decisions/ADR-0025-数据源凭据加密存储方案.md)（凭据落库方案：密钥环境注入不落库、config 与 secret 分离、注册强制测试、编辑留空保留凭据；B2 Phase 2 前半实施） |
| ADR-0026 数据视图层引入与 B0 触发点提前 | 决策 | **已接受**（主动借鉴 OpenBKN 双网络；逻辑数据集子集先行，粒度/JoinPath/方言触发点不变） | v1.0 | [`01-decisions/ADR-0026-数据视图层引入与B0触发点提前.md`](01-decisions/ADR-0026-数据视图层引入与B0触发点提前.md)（建模与资源拆分：`data_view` 资产 + 清单 dataViews 段 + `Property.viewId`；E1 逐字等价为迁移主判据；B3 规格承接） |

本文件是 `docs/` 的**唯一入口**，同时是文档治理规范。任何 agent 在新增、修改、查找文档前必须先读本文件。

---

## 0. 问题与目标

迁移前 `docs/` 根目录平铺了方案、计划与后续会不断累积的进度、评测、决策文档，导致：无法判断哪份是当前有效版本、
事实在多处重复定义、冻结结论被就地改写、历史被覆盖、引用悄悄失效。

本规范要解决四件事：**分层**（按文档类型归位）、**状态**（生命周期可辨）、**单一事实源**（事实只定义一次）、
**可追溯**（决策与进度有连续记录）。

---

## 1. 目录层级

```
docs/
├── README.md                    ← 本文件：规范 + 总索引（根目录只允许这一个 .md）
├── 00-governance/               文档治理：模板、检查脚本、术语表
│   ├── README.md
│   ├── templates/
│   └── check-docs.mjs
├── 01-decisions/                决策记录 ADR：不可逆或影响面大的决定
├── 02-solutions/                方案：架构与技术选型
│   ├── architecture/            系统架构、技术栈冻结
│   └── frontend/                前端技术路线（归属附录 A）
├── 03-plans/                    计划：路线图与阶段实施规格
│   ├── roadmap/                 阶段性大计划（M0–M8 的唯一定义处）
│   └── stages/                  各阶段《实施规格》，按 M0/ M1/ … 建子目录
├── 04-specs/                    规格：可验证的契约
│   ├── api/  database/  dsl/  security/
├── 05-progress/                 进度：阶段快照、周报（append-only）
│   └── 2026/
├── 06-verification/             验证：PoC 报告、评测集、检查清单执行记录
│   ├── poc/  evaluation/  checklists/
├── 07-references/               参考：外部依据、事实台账、引用边界
└── 99-archive/                 归档：被替代版本，镜像原目录结构
```

**层级规则**

1. 深度上限 **3 层**（`docs/一级/二级/文件`）；超过 3 层说明该拆主题而不是继续套目录。
2. 一级目录固定为上述编号目录，**不得新增同级目录**；需要新类别时先修订本规范并升版本。
3. 二级目录按「主题域」或「年份」划分，不按人名、不按临时任务名。
4. 每个一级目录必须有 `README.md`，写清「放什么 / 不放什么 / 本目录索引」。
5. `docs/` 根目录**除 `README.md` 外不得出现任何 `.md`**，由 `check-docs.mjs` 强制。

## 2. 一级目录职责

| 目录 | 放什么 | 明确不放 |
|---|---|---|
| `00-governance` | 本规范配套模板、检查脚本、术语表 | 业务方案、进度 |
| `01-decisions` | ADR：技术路线、边界、回退、冻结项的决策与依据 | 讨论草稿、会议流水 |
| `02-solutions` | 架构方案、技术选型、前端路线 | 排期、任务清单、执行结果 |
| `03-plans` | 阶段性大计划、阶段实施规格、任务分解 | 已完成事项的总结（属进度） |
| `04-specs` | 接口契约、DSL Schema、数据表、安全约束、验收清单 | 方案论证过程 |
| `05-progress` | 阶段快照、周报、阻塞与风险跟踪 | 计划重排（回 `03-plans` 发新版本） |
| `06-verification` | PoC 报告、评测集与结果、检查清单执行证据 | 未执行的设想 |
| `07-references` | 外部依据、事实台账、第三方资料索引 | 内部结论 |
| `99-archive` | 被新版本替代或已废弃的文档 | 当前有效文档 |

---

## 3. 文档类型与命名

| 类型 | 目录 | 文件名格式 | 示例 |
|---|---|---|---|
| 规范 | `docs/` 或 `00-governance` | `README.md` / `<主题>-规范-vN.md` | `docs/README.md` |
| 决策 ADR | `01-decisions` | `ADR-NNNN-短标题.md` | `ADR-0001-前端组件选型边界.md` |
| 方案 | `02-solutions` | `<主题>-方案-vN.M.md` | `智能问数系统技术选型方案-v1.1.md` |
| 大计划 | `03-plans/roadmap` | `<主题>-计划-vN.md` | `Ontos开发计划-阶段性功能拆解-v1.md` |
| 实施规格 | `03-plans/stages/Mn` | `M<n>-<主题>-实施规格-vN.md` | `M1-本体与发布链路-实施规格-v1.md` |
| 规格 | `04-specs` | `<域>-<对象>-规格-vN.md` | `dsl-QueryDsl-规格-v1.md` |
| 进度 | `05-progress/<年>` | `YYYY-MM-DD.md`（快照）/ `YYYY-Www.md`（周报） | `2026-11-06.md` |
| PoC | `06-verification/poc` | `PoC-NN-<主题>.md` | `PoC-01-RLS最终边界.md` |
| 评测 | `06-verification/evaluation` | `<主题>-评测-vN.md` | `零号冒烟-评测-v1.md` |
| 参考 | `07-references` | `<主题>-台账-vN.md` | `版本事实台账-v1.md` |

**通用命名规则**

- 版本一律 `-vN` 或 `-vN.M`，**禁止** `最终版` / `final-v2` / `新建文档` / `副本` / 无版本号的第二份同名文档。
- 中文主题名可用，但不得含空格、括号、日期戳代替版本。
- ADR 编号全局唯一、只增不复用；进度按日期、PoC 按编号，不带版本号。
- 冻结文档升版本时**新建文件**，不覆盖旧文件；旧文件移入 `99-archive/`。

---

## 4. 统一头块

除进度快照外，所有正式文档必须在标题下、正文前包含以下引用块（字段可留 `待定`，不得缺项）：

```markdown
> **状态**：草稿 / 评审中 / 已冻结 / 已归档
> **版本**：vN.M
> **类型**：方案 / 计划 / 规格 / 决策 / 规范 / 验证 / 参考
> **负责人**：
> **最后更新**：YYYY-MM-DD
> **适用范围**：
> **上游依据**：相对链接或「无」
> **修订记录**：YYYY-MM-DD vN.M 变更摘要；…
```

字段语义：**状态**决定能否修改，**上游依据**决定事实来源，**修订记录**保证变更可追溯。
进度快照用简化头块（日期、记录人、覆盖区间），因为它是 append-only 流水。

---

## 5. 生命周期

```
草稿 → 评审中 → 已冻结 → 已归档
```

| 规则 | 说明 |
|---|---|
| 冻结不可原地改 | 已冻结文档的结论、表格、版本号是单一事实源；要改必须**升版本另存新文件** |
| 升版本三件事 | ① 新文件写清新修订记录 ② 旧文件移入 `99-archive/` 并标注被谁替代 ③ 更新 `docs/README.md` 总索引 |
| 每阶段只有一份有效版本 | 同一主题不得同时存在两份「已冻结」文档 |
| 进度 append-only | 进度/周报只能追加新日期文件；历史条目不回改，修正用「更正」条目追加 |
| 计划重排 | 不改旧计划结论，发新版本计划并在进度里说明重排原因 |
| 决策不可删除 | ADR 只能被新 ADR 标记为「已取代」，不删除、不复用编号 |

---

## 6. 单一事实源（SSOT）与引用

| 事实 | 唯一定义处 | 其它文档 |
|---|---|---|
| 技术栈版本、锁定项、前端组件选型 | `02-solutions/architecture/智能问数系统技术选型方案-v1.1.md`（「前端表单/状态」行的生效解释见 `01-decisions/ADR-0024-前端表单库选型修订TanStackForm生效解释.md`） | 只引用，不复制版本号；表单选型以 ADR-0024 为准 |
| 架构组件边界、安全契约、最终验收 | `02-solutions/architecture/智能问数系统-完整可落地方案-v1.4.md` | 只引用章节号 |
| 阶段编号 M0–M8、关键路径、闸门 | `03-plans/roadmap/` 的大计划 | 进度/规格引用阶段号，不重定义 |
| PoC 判据与阻断级别 | 技术选型 §7 | PoC 报告引用判据编号 |
| 工程规范（后端） | 根 `AGENTS.md` | docs 不复制 |
| 前端工程规范 | `ontos-ui/FRONTEND_RULES.md` | docs 不复制 |

**引用规则**

1. 一律用仓库内**相对路径**，禁止 `D:/...` 之类的绝对路径；格式示例：

   ```markdown
   [标题](../02-solutions/architecture/目标文档.md#章节)
   ```
2. 引用目标必须存在；`check-docs.mjs` 会校验。失效引用要么补齐文档，要么在 `07-references` 记录为已失效输入，不得静默留着。
3. 引用章节号时带上文档版本，避免版本漂移后指向错误内容。
4. 代码、配置、注释里的文档路径同样受此约束；移动文档必须同步全仓引用。

---

## 7. Agent 遵循规则（硬条款）

1. **先读再写**：改动前读 `docs/README.md` → 目标一级目录的 `README.md` → 对应模板。
2. **先分类再落盘**：新增文档先判断类型，放对应目录；**禁止在 `docs/` 根目录新建 `.md`**（`README.md` 除外）。
3. **不改冻结文档**：需要变更时走「升版本 + 归档 + 更新索引」三步。
4. **不复制事实**：版本、阈值、阶段定义、验收条款只引用 SSOT，不在新文档里重写一份。
5. **完成即记进度**：任何实质性工作（方案、代码、验证、决策）在收尾时向 `05-progress/<年>/` 追加一条快照。
6. **移动即改引用**：迁移文档后必须跑 `node docs/00-governance/check-docs.mjs`，修复全部失效引用。
7. **模板起稿**：方案/计划/规格/ADR/验证报告从 `00-governance/templates/` 复制，不凭记忆写结构。
8. **并行义务同步**：新增或调整 `03-plans/roadmap` 的并行 / 同步建设约束时，必须同步
   `00-governance/parallel-obligations.json` 并重跑 `check-parallel-obligations.mjs`；
   每个阶段收口的进度快照必须逐项点名未闭环义务（见 `../AGENTS.md` §6.4）。只改计划不改台账视为未完成。

---

## 8. 治理检查

```bash
node docs/00-governance/check-docs.mjs
```

检查项：根目录无散落 md、一级目录 README 齐全、命名含合法版本或编号、所有 Markdown 相对链接可解析、索引中列出的文件存在。
存量三份迁移文档（见 §9）在头块与历史审核输入链接上豁免，其余一律适用。检查不通过不得合入。

---

## 9. 总索引（当前有效文档）

| 文档 | 类型 | 状态 | 版本 | 路径 |
|---|---|---|---|---|
| 文档规范与总索引 | 规范 | 已冻结 | v2.59 | [`README.md`](README.md) |
| 语义模型三层化 · 冻结口径生效解释 | 规格 | 生效 | v1 | [`04-specs/语义模型三层化-冻结口径生效解释-v1.md`](04-specs/语义模型三层化-冻结口径生效解释-v1.md) |
| B0 · 证据重跑处置台账 | 验证 | 已执行 | v1 | [`06-verification/evaluation/B0-证据重跑处置台账-v1.md`](06-verification/evaluation/B0-证据重跑处置台账-v1.md) |
| 智能问数系统技术选型方案 | 方案 | 已冻结 | v1.1 | [`02-solutions/architecture/智能问数系统技术选型方案-v1.1.md`](02-solutions/architecture/智能问数系统技术选型方案-v1.1.md) |
| 智能问数系统完整可落地方案 | 方案 | 已冻结 | v1.4 | [`02-solutions/architecture/智能问数系统-完整可落地方案-v1.4.md`](02-solutions/architecture/智能问数系统-完整可落地方案-v1.4.md) |
| Ontos 开发计划 · 阶段性功能拆解 | 计划 | 评审中 | v1 | [`03-plans/roadmap/Ontos开发计划-阶段性功能拆解-v1.md`](03-plans/roadmap/Ontos开发计划-阶段性功能拆解-v1.md) |
| M0 · 工程基线与阶段零硬判据 实施规格 | 计划 | 已冻结 | v1.0 | [`03-plans/stages/M0/M0-工程基线与阶段零硬判据-实施规格-v1.md`](03-plans/stages/M0/M0-工程基线与阶段零硬判据-实施规格-v1.md) |
| M1 · 本体与发布链路 实施规格 | 计划 | 已冻结 | v1.0 | [`03-plans/stages/M1/M1-本体与发布链路-实施规格-v1.md`](03-plans/stages/M1/M1-本体与发布链路-实施规格-v1.md) |
| M2 · 确定性编译与执行内核 实施规格 | 计划 | 已冻结 | v1.0 | [`03-plans/stages/M2/M2-确定性编译与执行内核-实施规格-v1.md`](03-plans/stages/M2/M2-确定性编译与执行内核-实施规格-v1.md) |
| ontology · 业务化命名 规格 | 规格 | 已冻结 | v1 | [`04-specs/ontology-业务化命名-规格-v1.md`](04-specs/ontology-业务化命名-规格-v1.md) |
| analysis-mode · funnel 与 yoy_mom 规格 | 规格 | 已冻结 | v1 | [`04-specs/analysis-mode-funnel-yoy-mom-规格-v1.md`](04-specs/analysis-mode-funnel-yoy-mom-规格-v1.md) |
| analysis-mode · retention 与 attribution 规格（含 funnel filters 修订） | 规格 | 已冻结 | v1.1 | [`04-specs/analysis-mode-retention-attribution-规格-v1.1.md`](04-specs/analysis-mode-retention-attribution-规格-v1.1.md) |
| analysis-mode · discovery 边界声明 | 规格 | 已冻结 | v1 | [`04-specs/analysis-mode-discovery-边界声明-v1.md`](04-specs/analysis-mode-discovery-边界声明-v1.md) |
| M0 验收报告 | 验证 | 已冻结 | v2 | [`06-verification/checklists/M0-验收报告-v2.md`](06-verification/checklists/M0-验收报告-v2.md) |
| M1 验收报告 | 验证 | 已冻结 | v2 | [`06-verification/checklists/M1-验收报告-v2.md`](06-verification/checklists/M1-验收报告-v2.md) |
| M0 基线自检归档输出 | 验证 | 已归档 | — | [`06-verification/checklists/M0-基线自检-2026-11-07.txt`](06-verification/checklists/M0-基线自检-2026-11-07.txt) |
| M1 基线自检归档输出 | 验证 | 已归档 | — | [`06-verification/checklists/M1-基线自检-2026-11-08.txt`](06-verification/checklists/M1-基线自检-2026-11-08.txt) |
| M2 验收报告 | 验证 | 已冻结 | v1 | [`06-verification/checklists/M2-验收报告-v1.md`](06-verification/checklists/M2-验收报告-v1.md) |
| M2 收口后深度审核 | 验证 | 已归档 | v1 | [`06-verification/checklists/M2-收口后深度审核-v1.md`](06-verification/checklists/M2-收口后深度审核-v1.md) |
| M3 实施规格 | 计划 | 已冻结 | v1.0 | [`03-plans/stages/M3/M3-简单问法最小闭环-实施规格-v1.md`](03-plans/stages/M3/M3-简单问法最小闭环-实施规格-v1.md) |
| M3 验收报告 | 验证 | 已冻结 | v1 | [`06-verification/checklists/M3-验收报告-v1.md`](06-verification/checklists/M3-验收报告-v1.md) |
| M5 实施规格 | 计划 | **已执行并验收**（v1.2 冻结后 W1–W5 全部完成，[验收报告 v1](06-verification/checklists/M5-验收报告-v1.md) E1–E7 满足） | v1.2 | [`03-plans/stages/M5/M5-会话意图编排与AG-UI链路-实施规格-v1.2.md`](03-plans/stages/M5/M5-会话意图编排与AG-UI链路-实施规格-v1.2.md)（[v0.1](99-archive/plans/M5-会话意图编排与AG-UI链路-实施规格-v0.1.md) / [v0.2](99-archive/plans/M5-会话意图编排与AG-UI链路-实施规格-v0.2.md) / [v1.0](99-archive/plans/M5-会话意图编排与AG-UI链路-实施规格-v1.0.md) / [v1.1](99-archive/plans/M5-会话意图编排与AG-UI链路-实施规格-v1.1.md) 已归档） |
| M4 实施规格 | 计划 | 已冻结 | v1.1 | [`03-plans/stages/M4/M4-身份权限与安全治理-实施规格-v1.md`](03-plans/stages/M4/M4-身份权限与安全治理-实施规格-v1.md) |
| M6 阶段中深度审核 | 验证 | 已冻结 | v1 | [`06-verification/checklists/M6-阶段中深度审核-v1.md`](06-verification/checklists/M6-阶段中深度审核-v1.md) |
| M6 · W1/T1.3 深度审核 | 验证 | 已冻结 | v1 | [`06-verification/checklists/M6-W1-T1.3-深度审核-v1.md`](06-verification/checklists/M6-W1-T1.3-深度审核-v1.md) |
| M6 · W1/T1.4 深度审核 | 验证 | 已冻结 | v1 | [`06-verification/checklists/M6-W1-T1.4-深度审核-v1.md`](06-verification/checklists/M6-W1-T1.4-深度审核-v1.md) |
| M6 · W1/T1.4 再次深度审核 | 验证 | 已冻结 | v1 | [`06-verification/checklists/M6-W1-T1.4-再次深度审核-v1.md`](06-verification/checklists/M6-W1-T1.4-再次深度审核-v1.md) |
| M6 · W1/T1.5 深度审核 | 验证 | 已冻结 | v1 | [`06-verification/checklists/M6-W1-T1.5-深度审核-v1.md`](06-verification/checklists/M6-W1-T1.5-深度审核-v1.md) |
| M6 · W1/T1.5 再次深度审核 | 验证 | 已冻结 | v1 | [`06-verification/checklists/M6-W1-T1.5-再次深度审核-v1.md`](06-verification/checklists/M6-W1-T1.5-再次深度审核-v1.md) |
| M6 · W1 收口深度审核 | 验证 | 已冻结 | v1 | [`06-verification/checklists/M6-W1-收口深度审核-v1.md`](06-verification/checklists/M6-W1-收口深度审核-v1.md) |
| M6 · W5/D-3 test 集再次深度审核 | 验证 | 已冻结 | v1 | [`06-verification/checklists/M6-W5-D3-再次深度审核-v1.md`](06-verification/checklists/M6-W5-D3-再次深度审核-v1.md) |
| M6 · W2/T2.1 深度审核 | 验证 | 已冻结 | v1 | [`06-verification/checklists/M6-W2-T2.1-深度审核-v1.md`](06-verification/checklists/M6-W2-T2.1-深度审核-v1.md) |
| M6 · W2/T2.2 深度审核 | 验证 | 已冻结 | v1 | [`06-verification/checklists/M6-W2-T2.2-深度审核-v1.md`](06-verification/checklists/M6-W2-T2.2-深度审核-v1.md) |
| M6 · W2/T2.3 深度审核 | 验证 | 已冻结 | v1 | [`06-verification/checklists/M6-W2-T2.3-深度审核-v1.md`](06-verification/checklists/M6-W2-T2.3-深度审核-v1.md) |
| M6 · ADR-0004 三触发条件核对 | 验证 | 已冻结 | v2 | [`06-verification/checklists/M6-ADR0004三触发条件核对-v2.md`](06-verification/checklists/M6-ADR0004三触发条件核对-v2.md) |
| M6 · W2/T2.4 深度审核 | 验证 | 已冻结 | v1 | [`06-verification/checklists/M6-W2-T2.4-深度审核-v1.md`](06-verification/checklists/M6-W2-T2.4-深度审核-v1.md) |
| M6 · W3/T3.1 深度审核 | 验证 | 已冻结 | v1 | [`06-verification/checklists/M6-W3-T3.1-深度审核-v1.md`](06-verification/checklists/M6-W3-T3.1-深度审核-v1.md) |
| M6 · W3/T3.2 深度审核 | 验证 | 已冻结 | v1 | [`06-verification/checklists/M6-W3-T3.2-深度审核-v1.md`](06-verification/checklists/M6-W3-T3.2-深度审核-v1.md) |
| M6 · W3/T3.3 深度审核 | 验证 | 已冻结 | v1 | [`06-verification/checklists/M6-W3-T3.3-深度审核-v1.md`](06-verification/checklists/M6-W3-T3.3-深度审核-v1.md) |
| M6 · W3/T3.4 深度审核 | 验证 | 已冻结 | v1 | [`06-verification/checklists/M6-W3-T3.4-深度审核-v1.md`](06-verification/checklists/M6-W3-T3.4-深度审核-v1.md) |
| M6 · W3/T3.5 深度审核 | 验证 | 已冻结 | v1 | [`06-verification/checklists/M6-W3-T3.5-深度审核-v1.md`](06-verification/checklists/M6-W3-T3.5-深度审核-v1.md) |
| M6 · W4 收口深度审核 | 验证 | 已冻结 | v1 | [`06-verification/checklists/M6-W4-收口深度审核-v1.md`](06-verification/checklists/M6-W4-收口深度审核-v1.md) |
| composite · 结果引用 规格 | 规格 | 已冻结 | v1 | [`04-specs/composite-resultRef-规格-v1.md`](04-specs/composite-resultRef-规格-v1.md) |
| ask · 复杂模式自由问数表达与参数补全 规格 | 规格 | **草稿** | v0.1 | [`04-specs/ask-复杂模式自由问数表达与参数补全-规格-v0.1.md`](04-specs/ask-复杂模式自由问数表达与参数补全-规格-v0.1.md) |
| M6 实施规格 | 计划 | **已通过验收**（v1.0；两轮自审 + 一轮深度 review 共 21 项发现闭环，八项决策定档；W1–W5 完成，[验收报告 v2](06-verification/checklists/M6-验收报告-v2.md) E1–E8 满足） | v1.0 | [`03-plans/stages/M6/M6-分析模式ScenarioSkill与复合意图-实施规格-v1.0.md`](03-plans/stages/M6/M6-分析模式ScenarioSkill与复合意图-实施规格-v1.0.md)（[v0.1](99-archive/plans/M6-分析模式ScenarioSkill与复合意图-实施规格-v0.1.md) / [v0.2](99-archive/plans/M6-分析模式ScenarioSkill与复合意图-实施规格-v0.2.md) 已归档） |
| M3 Golden 增量 | 验证 | 已冻结 | v1 | [`06-verification/evaluation/M3-Golden-v1.json`](06-verification/evaluation/M3-Golden-v1.json) |
| M6 Golden 增量（留存 + 归因 + 漏斗过滤段） | 验证 | 已冻结 | v1 | [`06-verification/evaluation/M6-Golden-v1.json`](06-verification/evaluation/M6-Golden-v1.json) |
| M2 基线自检归档输出 | 验证 | 已归档 | — | [`06-verification/checklists/M2-基线自检-2026-11-08.txt`](06-verification/checklists/M2-基线自检-2026-11-08.txt) |
| M2 E5 对抗探针证据 | 验证 | 已归档 | — | [`06-verification/checklists/M2-E5-对抗探针-2026-11-08.json`](06-verification/checklists/M2-E5-对抗探针-2026-11-08.json) |
| 版本事实台账 | 参考 | 已冻结 | v1 | [`07-references/版本事实台账-v1.md`](07-references/版本事实台账-v1.md) |
| 首份进度快照 | 进度 | 已记录 | — | [`05-progress/2026/2026-11-06.md`](05-progress/2026/2026-11-06.md) |
| 进度快照（W5 归位） | 进度 | 已记录 | — | [`05-progress/2026/2026-11-07.md`](05-progress/2026/2026-11-07.md) |
| 进度快照（M1 定稿 / M2 开工 / 交接审计） | 进度 | 已记录 | — | [`05-progress/2026/2026-11-08.md`](05-progress/2026/2026-11-08.md) |
| 零号冒烟集 | 验证 | 已冻结 | v3 | [`06-verification/evaluation/零号冒烟集-v3.md`](06-verification/evaluation/零号冒烟集-v3.md) |
| 零号冒烟集 gold | 验证 | 已冻结 | v3 | [`06-verification/evaluation/零号冒烟集-gold-v3.json`](06-verification/evaluation/零号冒烟集-gold-v3.json) |
| Golden 集 v2 · 案例设计 | 验证 | 已冻结 | v2-cases | [`06-verification/evaluation/Golden集-v2-案例设计.json`](06-verification/evaluation/Golden集-v2-案例设计.json) |
| Golden 集 v2 · SQL 回填（gold） | 验证 | 已冻结 | v1 | [`06-verification/evaluation/Golden集-v2-gold-v1.json`](06-verification/evaluation/Golden集-v2-gold-v1.json) |
| 编译器 Golden 集 | 验证 | 已冻结 | v1 | [`06-verification/evaluation/编译器Golden集-v1.json`](06-verification/evaluation/编译器Golden集-v1.json) |
| 复合意图题集（PoC #8） | 验证 | 已冻结 | v2 | [`06-verification/evaluation/复合意图题集-v2.json`](06-verification/evaluation/复合意图题集-v2.json) |
| 复合意图题集 · M2 回填 | 验证 | 已归档 | — | [`06-verification/evaluation/复合意图题集-v2-M2回填.json`](06-verification/evaluation/复合意图题集-v2-M2回填.json) |
| PoC-01 · M2 RLS 回归证据 | 验证 | 已归档 | — | [`06-verification/poc/PoC-01-M2回归-2026-11-08.json`](06-verification/poc/PoC-01-M2回归-2026-11-08.json) |
| 中文提及样本集（PoC #5） | 验证 | 已冻结 | v1 | [`06-verification/evaluation/中文提及样本集-v1.json`](06-verification/evaluation/中文提及样本集-v1.json) |
| ADR-0001 · 前端组件栈归位范围 | 决策 | 已冻结 | v1 | [`01-decisions/ADR-0001-前端组件栈归位范围.md`](01-decisions/ADR-0001-前端组件栈归位范围.md) |
| ADR-0002 · 跨轮差分规则修订 | 决策 | 已冻结 | v1 | [`01-decisions/ADR-0002-跨轮差分规则修订.md`](01-decisions/ADR-0002-跨轮差分规则修订.md) |
| ADR-0003 · fail-closed 判据清单 | 决策 | 已冻结 | v1 | [`01-decisions/ADR-0003-fail-closed判据清单.md`](01-decisions/ADR-0003-fail-closed判据清单.md) |
| ADR-0004 · 首发业务域与 Scenario 范围 | 决策 | 已冻结 | v1 | [`01-decisions/ADR-0004-首发业务域与Scenario范围.md`](01-decisions/ADR-0004-首发业务域与Scenario范围.md) |
| ADR-0005 · 评测门槛与 PoC 执行责任 | 决策 | 已冻结 | v1 | [`01-decisions/ADR-0005-评测门槛与PoC执行责任.md`](01-decisions/ADR-0005-评测门槛与PoC执行责任.md) |
| ADR-0006 · G2 有条件关闭与重开口径 | 决策 | 已冻结 | v1 | [`01-decisions/ADR-0006-G2有条件关闭与重开口径.md`](01-decisions/ADR-0006-G2有条件关闭与重开口径.md) |
| ADR-0007 · jOOQ 命名与 schema 映射规范 | 决策 | 已冻结 | v1 | [`01-decisions/ADR-0007-jOOQ命名与schema映射规范.md`](01-decisions/ADR-0007-jOOQ命名与schema映射规范.md) |
| ADR-0008 · 缓存落库形态与失效口径 | 决策 | 已冻结 | v1 | [`01-decisions/ADR-0008-缓存落库形态与失效口径.md`](01-decisions/ADR-0008-缓存落库形态与失效口径.md) |
| ADR-0009 · 中文分词器选型 | 决策 | 已冻结 | v1 | [`01-decisions/ADR-0009-中文分词器选型.md`](01-decisions/ADR-0009-中文分词器选型.md) |
| ADR-0010 · CopilotKit 复查与主路线评估 | 决策 | 已冻结 | v1 | [`01-decisions/ADR-0010-CopilotKit复查与主路线评估.md`](01-decisions/ADR-0010-CopilotKit复查与主路线评估.md) |
| ADR-0011 · M2 实施规格状态修订与 T3.0 归位 | 决策 | 已冻结 | v1 | [`01-decisions/ADR-0011-M2实施规格状态修订.md`](01-decisions/ADR-0011-M2实施规格状态修订.md) |
| ADR-0012 · EventDef 事件判定映射扩展与清单 v2 | 决策 | 已冻结 | v1 | [`01-decisions/ADR-0012-EventDef事件判定映射扩展.md`](01-decisions/ADR-0012-EventDef事件判定映射扩展.md) |
| ADR-0013 · Golden 集 v2 不可构造案例处置口径 | 决策 | 已冻结 | v1 | [`01-decisions/ADR-0013-Golden不可构造案例处置口径.md`](01-decisions/ADR-0013-Golden不可构造案例处置口径.md) |
| ADR-0014 · 并行义务纪律与 M7 同步建设偏离处置 | 决策 | 已冻结 | v1 | [`01-decisions/ADR-0014-并行义务纪律与M7同步建设偏离.md`](01-decisions/ADR-0014-并行义务纪律与M7同步建设偏离.md) |
| ADR-0015 · M7 最小框架验收与触发阶段重定 | 决策 | 已冻结 | v1 | [`01-decisions/ADR-0015-M7最小框架验收与触发阶段重定.md`](01-decisions/ADR-0015-M7最小框架验收与触发阶段重定.md) |
| ADR-0016 · 品类维度与订单事实指标的粒度边界 | 决策 | 已冻结 | v1 | [`01-decisions/ADR-0016-品类维度与订单事实指标的粒度边界.md`](01-decisions/ADR-0016-品类维度与订单事实指标的粒度边界.md) |
| ADR-0017 · M6 外部评审裁决 | 决策 | 已冻结 | v1 | [`01-decisions/ADR-0017-M6外部评审裁决.md`](01-decisions/ADR-0017-M6外部评审裁决.md) |
| ADR-0019 · M7 有条件通过 | 决策 | 已冻结 | v1 | [`01-decisions/ADR-0019-M7有条件通过E7评测挂账.md`](01-decisions/ADR-0019-M7有条件通过E7评测挂账.md) |
| ADR-0018 · E-5 订单项级指标实现口径 | 决策 | 已冻结 | v1 | [`01-decisions/ADR-0018-E5订单项级指标实现口径.md`](01-decisions/ADR-0018-E5订单项级指标实现口径.md) |
| ADR-0021 · 平台四角色与资产审批发布模型 | 决策 | **草稿（待负责人终审）** | v0.1 | [`01-decisions/ADR-0021-平台四角色与资产审批发布模型.md`](01-decisions/ADR-0021-平台四角色与资产审批发布模型.md) |
| ADR-0022 · 外部模型评测改为复杂度优先与 API 安全模式 | 决策 | **草稿（API 安全模式生效；C-1 口径替代待负责人终审）** | v0.1 | [`01-decisions/ADR-0022-外部模型评测改为复杂度优先与API安全模式.md`](01-decisions/ADR-0022-外部模型评测改为复杂度优先与API安全模式.md) |
| ADR-0023 · 自由问数复杂分析模式入口口径 | 决策 | 已冻结 | v1.1（v1 已归档） | [`01-decisions/ADR-0023-自由问数复杂分析模式入口口径.md`](01-decisions/ADR-0023-自由问数复杂分析模式入口口径.md) |
| ADR-0024 · 前端表单库选型修订（TanStack Form 生效解释） | 决策 | 已冻结 | v1 | [`01-decisions/ADR-0024-前端表单库选型修订TanStackForm生效解释.md`](01-decisions/ADR-0024-前端表单库选型修订TanStackForm生效解释.md) |
| M7 最小框架验收记录 | 验证 | 已冻结 | v1 | [`06-verification/checklists/M7-最小框架验收记录-v1.md`](06-verification/checklists/M7-最小框架验收记录-v1.md) |
| 正式评测集 | 验证 | 已冻结 | v6 | [`06-verification/evaluation/正式评测集-v6.md`](06-verification/evaluation/正式评测集-v6.md) |
| test 集（主指标分母） | 验证 | 已冻结 | v5 | [`06-verification/evaluation/test集-v5.md`](06-verification/evaluation/test集-v5.md) |
| test 集 v4 评测证据（E-5 首轮） | 验证 | 已冻结 | v1 | [`06-verification/evaluation/test集-M7W1-E5证据-v1.json`](06-verification/evaluation/test集-M7W1-E5证据-v1.json) |
| test 集 v5 评测证据（E-6 首轮） | 验证 | 已冻结 | v1 | [`06-verification/evaluation/test集-M7W1-E6证据-v1.json`](06-verification/evaluation/test集-M7W1-E6证据-v1.json) |
| 失败看板闭环证据（W4 生命周期） | 验证 | 已冻结 | v1 | [`06-verification/evaluation/失败看板闭环证据-v1.json`](06-verification/evaluation/失败看板闭环证据-v1.json) |
| 失败看板联动证据（W4 前端） | 验证 | 已冻结 | v1 | [`06-verification/evaluation/失败看板T4.3联动证据-v1.json`](06-verification/evaluation/失败看板T4.3联动证据-v1.json) |
| LLM 调用追踪证据（T5.1） | 验证 | 已冻结 | v1 | [`06-verification/evaluation/LLM调用追踪证据-v1.json`](06-verification/evaluation/LLM调用追踪证据-v1.json) |
| 模型服务锚定（T2.1） | 验证 | 已冻结 | v3（v1 / v2 已归档） | [`06-verification/evaluation/模型服务锚定-v3.md`](06-verification/evaluation/模型服务锚定-v3.md) |
| LLM 路径评测证据（E-7 部分，单轮 250/276） | 验证 | 已冻结（**部分运行**，用户止损） | v1-partial | [`06-verification/evaluation/LLM路径评测证据-v1-partial.json`](06-verification/evaluation/LLM路径评测证据-v1-partial.json) |
| 降级路径评测证据（对照基线） | 验证 | 已冻结 | v1 | [`06-verification/evaluation/降级路径评测证据-v1.json`](06-verification/evaluation/降级路径评测证据-v1.json) |
| LLM vs 降级对比（McNemar + bootstrap） | 验证 | 已冻结 | v1 | [`06-verification/evaluation/LLM对比降级路径证据-v1.json`](06-verification/evaluation/LLM对比降级路径证据-v1.json) |
| E-7 归因诊断 | 验证 | 已冻结 | v1 | [`06-verification/evaluation/E7归因诊断证据-v1.json`](06-verification/evaluation/E7归因诊断证据-v1.json) |
| LLM 攻击面探针（T3.2 · mimo） | 验证 | 已冻结（历史锚点） | v2（v1 两项 FAIL 为探针缺陷，已修正） | [`06-verification/evaluation/LLM对抗探针证据-v2.json`](06-verification/evaluation/LLM对抗探针证据-v2.json) |
| LLM 攻击面探针（T3.2 · GLM） | 验证 | 已冻结（GLM 探索性锚点） | GLM-v1 | [`06-verification/evaluation/LLM对抗探针证据-GLM-v1.json`](06-verification/evaluation/LLM对抗探针证据-GLM-v1.json) |
| E7 复杂度子集（外部模型诊断） | 验证 | 已冻结（**不替代原 C-1**） | v1 | [`06-verification/evaluation/E7复杂度子集-v1.md`](06-verification/evaluation/E7复杂度子集-v1.md)（[JSON](06-verification/evaluation/E7复杂度子集-v1.json)） |
| E7 复杂度子集诊断（mimo） | 验证 | 已固化（**探索性，不解除 C-1**） | v1 | [`06-verification/evaluation/E7复杂度子集诊断-MIMO-v1.md`](06-verification/evaluation/E7复杂度子集诊断-MIMO-v1.md)（[JSON](06-verification/evaluation/E7复杂度子集诊断-MIMO-v1.json)） |
| E7 复杂模式自由问数 v4 冒烟 | 验证 | 已固化（**探索性，不解除 C-1**） | v1 | [`06-verification/evaluation/E7复杂模式自由问数V4冒烟证据-v1.md`](06-verification/evaluation/E7复杂模式自由问数V4冒烟证据-v1.md)（[JSON](06-verification/evaluation/E7复杂模式自由问数V4冒烟证据-v1.json)） |
| E7 复杂模式自由问数 v4 复测 | 验证 | 已固化（**探索性，不解除 C-1**） | v1 | [`06-verification/evaluation/E7复杂模式自由问数V4复测证据-v1.md`](06-verification/evaluation/E7复杂模式自由问数V4复测证据-v1.md)（[JSON](06-verification/evaluation/E7复杂模式自由问数V4复测证据-v1.json)） |
| E7 复杂模式自由问数 v4 多轮复测 | 验证 | 已固化（**多轮未闭环，不解除 C-1**） | v1 | [`06-verification/evaluation/E7复杂模式自由问数V4多轮复测证据-v1.md`](06-verification/evaluation/E7复杂模式自由问数V4多轮复测证据-v1.md)（[JSON](06-verification/evaluation/E7复杂模式自由问数V4多轮复测证据-v1.json)） |
| E7 复杂模式自由问数 v4 多轮复测 | 验证 | 已固化（**探索性：修复后 3/3，不解除 C-1**） | v2 | [`06-verification/evaluation/E7复杂模式自由问数V4多轮复测证据-v2.md`](06-verification/evaluation/E7复杂模式自由问数V4多轮复测证据-v2.md)（[JSON](06-verification/evaluation/E7复杂模式自由问数V4多轮复测证据-v2.json)） |
| E7 多轮投影收口复核 | 验证 | 已固化（**收口复核，不解除 C-1**） | v1 | [`06-verification/evaluation/E7多轮投影收口复核证据-v1.md`](06-verification/evaluation/E7多轮投影收口复核证据-v1.md)（[JSON](06-verification/evaluation/E7多轮投影收口复核证据-v1.json)） |
| 产品前端实施规格（M8 工作包） | 计划 | **已冻结（编码放行待 D1–D8 + D10 定档、W-E0 / W-B0 前置与 W0 spike）** | v1.1 | [`03-plans/stages/M8/产品前端-实施规格-v1.1.md`](03-plans/stages/M8/产品前端-实施规格-v1.1.md)（[v0.1](99-archive/plans/产品前端-实施规格-v0.1.md)、[v0.2](99-archive/plans/产品前端-实施规格-v0.2.md)、[v1.0](99-archive/plans/产品前端-实施规格-v1.0.md) 已归档） |
| E-7 定向复测证据（30 题子集，第 2 轮） | 验证 | 已冻结 | v1 | [`06-verification/evaluation/E7定向复测证据-v1.json`](06-verification/evaluation/E7定向复测证据-v1.json) |
| E-7 定向复测证据（30 题子集，第 3 轮，判据 v2） | 验证 | 已冻结（历史 mimo 锚点） | v2 | [`06-verification/evaluation/E7定向复测证据-v2.json`](06-verification/evaluation/E7定向复测证据-v2.json) |
| E-7 GLM 30 题预演（GLM 探索性锚点） | 验证 | 已冻结 | GLM-v1 | [`06-verification/evaluation/E7定向复测证据-GLM-v1.json`](06-verification/evaluation/E7定向复测证据-GLM-v1.json) |
| 压测证据（M7/W5） | 验证 | 已冻结 | v1 | [`06-verification/evaluation/压测-M7证据-v1.json`](06-verification/evaluation/压测-M7证据-v1.json) |
| 评测统计口径 | 验证 | 已冻结 | v1 | [`06-verification/evaluation/评测统计口径-v1.md`](06-verification/evaluation/评测统计口径-v1.md) |
| SLO 与成本预算（M7 冻结） | 验证 | 已冻结 | v1 | [`06-verification/evaluation/SLO与成本预算-v1.md`](06-verification/evaluation/SLO与成本预算-v1.md) |
| 零号冒烟评测证据 | 验证 | 已冻结 | v3 | [`06-verification/evaluation/零号冒烟-评测证据-v3.json`](06-verification/evaluation/零号冒烟-评测证据-v3.json) |
| 并行义务登记台账 | 治理 | 生效中 | schemaVersion 1 | [`00-governance/parallel-obligations.json`](00-governance/parallel-obligations.json) |
| 并行义务与阶段状态检查 | 治理 | 生效中 | — | [`00-governance/check-parallel-obligations.mjs`](00-governance/check-parallel-obligations.mjs) |
| PoC-01 · RLS 最终边界报告 | 验证 | 已冻结 | v1 | [`06-verification/poc/PoC-01-RLS最终边界.md`](06-verification/poc/PoC-01-RLS最终边界.md) |
| PoC-02 · 发布快照可复现报告 | 验证 | 已冻结 | v1 | [`06-verification/poc/PoC-02-发布快照可复现.md`](06-verification/poc/PoC-02-发布快照可复现.md) |
| PoC-03 · AgentScope 准入报告 | 验证 | 已冻结 | v1 | [`06-verification/poc/PoC-03-AgentScope准入.md`](06-verification/poc/PoC-03-AgentScope准入.md) |
| PoC-04 · 编译器 SQL 输出报告 | 验证 | 已冻结 | v1 | [`06-verification/poc/PoC-04-编译器SQL输出.md`](06-verification/poc/PoC-04-编译器SQL输出.md) |
| M0 PoC 总览 | 验证 | 已冻结 | v1 | [`06-verification/poc/M0-PoC总览-v1.md`](06-verification/poc/M0-PoC总览-v1.md) |
| T5.5 · CopilotKit Headless 接入验证 | 验证 | 已冻结 | v1 | [`06-verification/poc/T5.5-CopilotKit接入验证-v1.md`](06-verification/poc/T5.5-CopilotKit接入验证-v1.md) |
| PoC-07 · 跨轮差分与存在性保护报告 | 验证 | 已冻结 | v1 | [`06-verification/poc/PoC-07-跨轮差分与存在性保护.md`](06-verification/poc/PoC-07-跨轮差分与存在性保护.md) |
| PoC-10 · 前端直连 AG-UI SSE（协议 + 界面） | 验证 | 已冻结 | v1 | [`06-verification/poc/PoC-10-协议探针.md`](06-verification/poc/PoC-10-协议探针.md) |
| M0 硬阻断结论 | 验证 | 已冻结 | v2 | [`06-verification/poc/M0-硬阻断结论-v2.md`](06-verification/poc/M0-硬阻断结论-v2.md) |
| M6 验收报告 | 验证 | 已冻结 | v2 | [`06-verification/checklists/M6-验收报告-v2.md`](06-verification/checklists/M6-验收报告-v2.md) |
| M6 全量深度审核 | 验证 | 已冻结 | v1 | [`06-verification/checklists/M6-全量深度审核-v1.md`](06-verification/checklists/M6-全量深度审核-v1.md) |
| M7 验收报告（**有条件通过**） | 验证 | 已冻结 | v1 | [`06-verification/checklists/M7-验收报告-v1.md`](06-verification/checklists/M7-验收报告-v1.md) |
| M7 全量交付物收敛验收报告（OBL-M7 闭环） | 验证 | 已冻结 | v2 | [`06-verification/checklists/M7-全量交付物收敛验收报告-v2.md`](06-verification/checklists/M7-全量交付物收敛验收报告-v2.md) |
| M6 · W5/T5.1 深度审核 | 验证 | 已冻结 | v1 | [`06-verification/checklists/M6-W5-T5.1-深度审核-v1.md`](06-verification/checklists/M6-W5-T5.1-深度审核-v1.md) |
| M6 · W5/T5.3 运维面深度审核 | 验证 | 已冻结 | v1 | [`06-verification/checklists/M6-W5-T5.3-运维面深度审核-v1.md`](06-verification/checklists/M6-W5-T5.3-运维面深度审核-v1.md) |
| M6 · W5/T5.4 移交核对 | 验证 | 已冻结 | v1 | [`06-verification/checklists/M6-W5-T5.4-移交核对-v1.md`](06-verification/checklists/M6-W5-T5.4-移交核对-v1.md) |
| M6 · W5 收口深度审核 | 验证 | 已冻结 | v1 | [`06-verification/checklists/M6-W5-收口深度审核-v1.md`](06-verification/checklists/M6-W5-收口深度审核-v1.md) |
| 产品前端 深度审核 R1 | 审核 | 已执行 | v1 | [`06-verification/checklists/产品前端-深度审核R1-v1.md`](06-verification/checklists/产品前端-深度审核R1-v1.md)（规格审核：13 项 P1×4——run 事件流唯一事实源、Session–Run–Turn 模型、会话固定 session_id、后端配套 W-B0–B4） |
| 产品前端 深度审核 R2 | 审核 | 已执行 | v1 | [`06-verification/checklists/产品前端-深度审核R2-v1.md`](06-verification/checklists/产品前端-深度审核R2-v1.md)（规格审核：14 项 P1×3——回传伪造红线、驻留泄露四细则、会话锁状态机） |
| 产品整体规划 深度审核 R3 | 审核 | 已执行 | v1 | [`06-verification/checklists/产品整体规划-深度审核R3-v1.md`](06-verification/checklists/产品整体规划-深度审核R3-v1.md)（全局第三轮：12 项 P1×4——台账账实漂移、×3 口径未冻结、token 超冻结 SLO、workspace 隔离无决策载体；当场修复 3 项 + 负责人决策批 6 项） |
| 产品前端 深度审核 R4 | 审核 | 已执行 | v1 | [`06-verification/checklists/产品前端-深度审核R4-v1.md`](06-verification/checklists/产品前端-深度审核R4-v1.md)（规格独立第三轮：12 项 P1×5——D1 双 run 空间冲突、身份模型三处互斥、Playwright 未引入、性能预算与真模型冲突、W-B2 低估；采纳 11 并入 v1.1，拒绝 1） |
| M7 实施规格 | 计划 | **已冻结（编码放行）** | v1.2 | [`03-plans/stages/M7/M7-评测对抗防线与可观测-实施规格-v1.2.md`](03-plans/stages/M7/M7-评测对抗防线与可观测-实施规格-v1.2.md)（[v0.1](99-archive/plans/M7-评测对抗防线与可观测-实施规格-v0.1.md) 已归档） |
| 语义模型三层化 · 影响分析 | 方案 | **已定档**（B0 立项依据） | v0.2 | [`04-specs/语义模型三层化-影响分析-v0.2.md`](04-specs/语义模型三层化-影响分析-v0.2.md)（v0.1 [已归档](99-archive/specs/语义模型三层化-影响分析-v0.1.md)） |
| B0 语义模型三层化 实施规格（独立工作包） | 计划 | **已上线**（生产 active `2026.11.12-twenty-second`） | v1.1 | [`03-plans/stages/B0/B0-语义模型三层化-实施规格-v1.1.md`](03-plans/stages/B0/B0-语义模型三层化-实施规格-v1.1.md)（v0.1 / v1.0 已归档） |
| B1 语义定义工作台 实施规格（独立工作包，P0 草稿与校验） | 计划 | **已实施（P0 + P1-A/B + P2-A/B + UI 补齐包）**（合并树 824/0；Q1–Q3 按建议执行） | v0.1 | [`03-plans/stages/B1/B1-语义定义工作台-实施规格-v0.1.md`](03-plans/stages/B1/B1-语义定义工作台-实施规格-v0.1.md)（V21 统一草稿表 + 7 端点 + 合并视图校验 + revision 乐观锁；P1-A 语义定义页面与 `options` 接口；P2-A 数据源/本体建模/调试台页面；P2-B 本体编辑器（对象/属性/关系草稿编辑，属性 column 从 B2 真实目录选择，见 §13）；21 例后端 + 6 例前端表单测试；OpenBKN 借鉴项见 §0.2，实施记录见 §10/§11/§12/§13） |
| B2 数据源插件化 实施规格（独立工作包，Phase 1 + Phase 2 前半） | 计划 | **已实施**（合并树 845/0；凭据 AES-GCM，ADR-0025） | v0.1 | [`03-plans/stages/B2/B2-数据源插件化-实施规格-v0.1.md`](03-plans/stages/B2/B2-数据源插件化-实施规格-v0.1.md)（SPI 纯 Java 契约 + `ontos-plugin-jdbc` PG 插件（ServiceLoader 注册）+ 注册表 fail-fast + 真实库目录/连通性测试接口 + 数据源页接入；Phase 2 datasetId 化 / Phase 3 外置驱动见 §7；借鉴定档见 §0） |
| B3 数据视图与资源层拆分 实施规格（独立工作包，草稿） | 计划 | **待定档**（D1–D4 建议案见规格 §0.3；ADR-0026 已立） | v0.1 | [`03-plans/stages/B3/B3-数据视图与资源层拆分-实施规格-v0.1.md`](03-plans/stages/B3/B3-数据视图与资源层拆分-实施规格-v0.1.md)（OpenBKN 双网络蒸馏落点：数据视图资产 + 清单 dataViews 段 + `Property.viewId + column` + R-View 绑定校验 + 建模改引用；E1 主判据；Phase A/B/C；不含索引层与 datasetId 执行） |
| B1 · P0 真服务冒烟证据 | 验证 | 已固化 | v1 | [`06-verification/evaluation/B1-P0真服务冒烟证据-v1.json`](06-verification/evaluation/B1-P0真服务冒烟证据-v1.json)（Flyway V21 success + 7 端点 + 旧 revision 冲突码 + `dev-allow-all=false` 管理面 403；active 未变、冒烟数据已清理） |
| 进度快照（M6/W5 数据面与收口） | 进度 | 已记录 | — | [`05-progress/2026/2026-11-10.md`](05-progress/2026/2026-11-10.md) |
| 进度快照（M6 收口与 M7 接手） | 进度 | 已记录 | — | [`05-progress/2026/2026-11-11.md`](05-progress/2026/2026-11-11.md) |
| 进度快照（现状核查与四角色拍板） | 进度 | 已记录 | — | [`05-progress/2026/2026-11-12.md`](05-progress/2026/2026-11-12.md) |
| 前端工程规范 | 规范 | 已冻结 | 迁移版 | [`../ontos-ui/FRONTEND_RULES.md`](../ontos-ui/FRONTEND_RULES.md) |
| 前端工具链 | 规范 | 已冻结 | 迁移版 | [`../ontos-ui/docs/tooling.md`](../ontos-ui/docs/tooling.md) |
| 工程约定 | 规范 | 生效中 | — | [`../AGENTS.md`](../AGENTS.md) |

## 10. 存量迁移记录

| 原路径 | 新路径 | 说明 |
|---|---|---|
| `docs/智能问数系统技术选型方案-v1.1.md` | `02-solutions/architecture/智能问数系统技术选型方案-v1.1.md` | 原文未改，仅迁移并同步全仓引用 |
| `docs/智能问数系统-完整可落地方案.md` | `02-solutions/architecture/智能问数系统-完整可落地方案-v1.4.md` | 补齐版本号使其符合命名规则，内容未改 |
| `docs/Ontos开发计划-阶段性功能拆解-v1.md` | `03-plans/roadmap/Ontos开发计划-阶段性功能拆解-v1.md` | 原文未改，引用路径已同步 |

> 技术选型方案 §0 提到的两份历史「审核输入」链接在仓库中不存在，属来源遗留；保留原文不改，
> 由检查脚本按存量豁免处理，后续在 `07-references` 建立事实台账时统一登记。

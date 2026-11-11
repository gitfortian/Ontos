# 06 · 验证

**放**：PoC 报告、评测集与结果、检查清单执行证据。

**不放**：未执行的设想、计划中的测试。

**索引 / 约定**：`poc/` `evaluation/` `checklists/`；判据引用技术选型 §7，不复制阈值。

**当前文档**

- [M6 阶段中深度审核 v1](checklists/M6-阶段中深度审核-v1.md)（已冻结：全局复核 7 项——**P1 回归：queryds V3 事件 ID 与 M2 funnel 证据脚本 fixture 主键冲突，真库报 duplicate key，已改 e-m6-* 前缀修复复验**；证据期望漂移按 append-only 更正；OBL-M7 题目编写未按决策 8 启动登记为硬欠账；状态漂移/契约错误码/T1.3 分流约束登记；结论：阶段中审核通过，不宣告 M6 关闭）
- [M6 · W1/T1.3 深度审核 v1](checklists/M6-W1-T1.3-深度审核-v1.md)（已冻结：留存构造器单任务审核——规格 §1.1/§1.2 逐条对照符合、无 P1/P2 代码缺陷；2 项登记（模式规格示例 `dim.channel` 与主体表维度定档冲突 / 笛卡尔展开的规模性能）；真库执行验证未完整期排除、Golden 10/0、M2 27/0 与 M3 6/0 不回退、`mvn test` 427 例）
- [M6 · W1/T1.4 深度审核 v1](checklists/M6-W1-T1.4-深度审核-v1.md)（已冻结：归因构造器单任务审核——**P1 缺陷（SQL 别名 `channel.*` 与 `as ch` 冲突）由人工审阅发现并修复复验**，Golden 逐字比对测不出该缺陷；3 项登记（`touched_events` 语义缺口 / 清单 v12 顺序调整 / UNATTRIBUTED 真库样例）；真库执行线上 12498/3、线下 6308/3，Golden 18/0、`mvn test` 451 例）
- [M6 · W1/T1.4 再次深度审核 v1](checklists/M6-W1-T1.4-再次深度审核-v1.md)（已冻结：换视角复审（契约精读/故障注入/攻击者/性能）——**P2 语义缺陷（UNATTRIBUTED 按渠道名判定，把「有触点但主数据缺失」伪装成无触点）当场修复**，3 项登记（事件表索引 / 重复转化事件 / 渠道值域一致性）；T1.5 放行）
- [M6 · W1/T1.5 深度审核 v1](checklists/M6-W1-T1.5-深度审核-v1.md)（已冻结：funnel 顶层 filters 放开为全局过滤（M2 偏差 2 收口）——cohort 与每个 step CTE 共享条件并用精确计数断言锁定；真库对比 3→1 证明过滤生效；3 项登记（guardrail 维度敏感性口径 / **增量编译 NoClassDefFoundError 纪律：改 main 后必须 clean test** / 步级过滤不含）；Golden 23/0、`mvn clean test` 455 例）
- [M6 · W1/T1.5 再次深度审核 v1](checklists/M6-W1-T1.5-再次深度审核-v1.md)（已冻结：换视角复审——**更正首次审核 T15-1 的机制理由**（step 的 INNER JOIN cohort 已限定主体，每步过滤为规格字面 + 纵深防御的冗余），3 项登记（null/空白过滤值 / filters 顺序敏感性 / 策略面确认）；0 代码改动，T1.6 放行）
- [M6 · W1 收口深度审核 v1](checklists/M6-W1-收口深度审核-v1.md)（已冻结：T1.1–T1.6 逐项核对 + T1.6 DISCOVERY 边界核对节 + E1 模式部分取证——**W1 通过（附 D-3 排期条件）**；方法论沉淀：Golden 逐字比对不能证明 SQL 可执行/语义正确，必须人工审阅 + 真库执行）
- [M6 · W5/D-3 test 集再次深度审核 v1](checklists/M6-W5-D3-再次深度审核-v1.md)（已冻结：v1 判定**不通过**——8 类缺陷（哈希漂移 / shape-mode 整族套错 / 分析模式参数缺失 / 订单与品类取值越界 / 时间表达未命中 / 品类×订单指标不可编译 / 题族计数漂移 / skip 未落数据）全部修复，test 集升 v2（skip 2 / 有效 239，sha256 `556e564c…`）、正式评测集升 v3；证据：119/119 真实编译、45 子意图 0 失败、2 负例稳定 1002000014；新增缺口 E-5）
- [M6 · W2/T2.1 catalog 骨架与 V15 迁移 深度审核 v1](checklists/M6-W2-T2.1-深度审核-v1.md)（已冻结：三张生命周期表 + audit_log 11 列扩展落地；**3 项 P2 修复/验证——published_at 在 retire 时丢时间戳、JSONB 缺形状闸门、ArchUnit 未做故障注入**；ArchUnit 增两条边界规则（catalog 不依赖运行时 / 除 server 外无人依赖 catalog）；`mvn clean test` 460 例、架构 9 条全过）
- [M6 · W2/T2.2 Scenario 资产模型与发布校验 深度审核 v1](checklists/M6-W2-T2.2-深度审核-v1.md)（已冻结：ScenarioMeta 升级 v1.4 §5.7 完整模型（legacy 兼容 v12）、§5.7 规则集中到 ScenarioValidator、catalog 生命周期/回滚/依赖影响、清单 v13 dry-run 通过但未激活；**4 项 P2 修复**——legacy scope 兼容、状态双写漂移、回滚版本 400→业务码、发布 ID 错配；3 项 P3 登记）
- [M6 · W2/T2.3 入口路由决策与状态机 深度审核 v1](checklists/M6-W2-T2.3-深度审核-v1.md)（已冻结：ScenarioRouter 五态确定性决策 + ScenarioRouteState 三态封闭迁移（25 例单测）+ pending_confirm 复用 M5 澄清回传校验；**P2 修复——新会话无状态行时状态写入静默失效**；T23-4 登记：显式切换不走 pending 待 T2.4 复核；**路由不启用**——三触发条件 2/3，见核对记录 v1）
- [M6 · W2/T2.4 入口路由接入瀑布 深度审核 v1](checklists/M6-W2-T2.4-深度审核-v1.md)（已冻结：Router 接入瀑布 + scope 裁剪只收窄 + 直传裁决 D1 四步 + 编排模式过滤；**2 项 P2 修复**——裁剪按当前态、属性随域内概念展开；v13 已激活（thirteenth），Golden 回归零回退 M6 23/0 / M3 6/0 / M2 27/0）
- [M6 · W3/T3.1 Skill 资产模型与生命周期 深度审核 v1](checklists/M6-W3-T3.1-深度审核-v1.md)（已冻结：SkillMeta 全字段 + 审核人记录、SkillValidator 命名空间/跨模式近似/published 必有审核人、生命周期 approve 留痕非迁移；3 项 P2 当场修复；清单 v14 七条内置 Skill）
- [M6 · W3/T3.2 SkillRegistry 注册制升级 深度审核 v1](checklists/M6-W3-T3.2-深度审核-v1.md)（已冻结：清单驱动解析 + 受控工具注册表 + 编译期四条闸门 + carriersFor 承担者查询；**M3 回归不回退**——list 去重与 skillId 对齐运行时命名；5 项发现处置，全量 59/557）
- [M6 · W3/T3.3 近似降级机制 深度审核 v1](checklists/M6-W3-T3.3-深度审核-v1.md)（已冻结：QuerySkill 首发样例 + approximationCarriersFor + 同意走 M5 回传通道（D3）+ MODE_APPROXIMATED 审计；**端到端驱动出 T32-8（P1）场景确认断链**并修复——消费点前缀分流；真服务 3/3，全量 59/562）
- [M6 · W3/T3.4 VQR 资产与自动失效 深度审核 v1](checklists/M6-W3-T3.4-深度审核-v1.md)（已冻结：VqrMeta 全字段 + VqrValidator 七条件清单侧判定 + catalog 生命周期（D4 证据外置——不连库）+ VqrInvalidationAnalyzer 六族失效接入发布流水线（F-7 移除 + 报告不静默）；2 项 P2 当场修复；清单 v16 首发样例与场景引用闭环）
- [M6 · W3/T3.5 VQR 命中通道 深度审核 v1](checklists/M6-W3-T3.5-深度审核-v1.md)（已冻结：瀑布第 7 步确定性匹配（场景切片限缩）+ 提案 vqr_id 完整性对账 + execute 全链八步重校验 + VQR_HIT 审计 + cache-key-1.2；**v16 激活（sixteenth）**，Golden 零回退；3 项 P2 处置——模板命中优先于资产级歧义（T35-2）、time_dimension 模板键（T35-3）、v17 路线否决记录（T35-1））
- [M6 · W4 收口深度审核 v1](checklists/M6-W4-收口深度审核-v1.md)（已冻结：T4.1–T4.5 全覆盖——《结果引用规格 v1》兑现、依赖拓扑 044、注入参数化绑定、部分失败双路径统一、R-27/R-29 不扩边界；**PoC #8 M6 复跑 30 题全部符合预期**（pass 20 + 决策 7 拒绝 6 + 部分失败 4 + 粒度拒绝 3）；2 项 P1 端到端驱动修复——失败子响应 NPE（T45-1）、无依赖整单拒绝与规格冲突（T45-2））
- [M6 · ADR-0004 三触发条件核对 v2](checklists/M6-ADR0004三触发条件核对-v2.md)（已冻结：③ scope 裁剪取证通过 → **三条全满足，Router 接入瀑布放行**；激活语义与 T5.2 补充探针在档；v1 已归档）
- [M6 · ADR-0004 三触发条件核对 v1](../99-archive/verification/M6-ADR0004三触发条件核对-v1.md)（已冻结：①编译器覆盖+fail-closed 满足、②路径规划满足、③scope 裁剪归 T2.4 **未满足** → **Scenario Entry Router 不接入瀑布，v13 只发布元数据**；T2.4 后升 v2 复核）
- [M5 收口后深度审核 v1](checklists/M5-收口后深度审核-v1.md)（已归档：全局复核 4 项发现全部当场修复复验——OBL-M7 evidence 补提及样本回填凭据、索引错位与过时状态修正、**HITL 放行重放 requestId 分裂统一（-replay 同源实证）**、事件表保留策略登记 M6；M5 判定不变：通过）
- [M5 验收报告 v1](checklists/M5-验收报告-v1.md)（已冻结，E1–E7 全部满足，M6 放行；G2 终态重建遗留以确定性链路 20/20 收口；真实模型冒烟登记为上线前检查项）
- [M5 W5 端到端与对抗](checklists/M5-验收报告-v1.md)（T5.1 PoC #3 复跑 20/20 / T5.2 对抗 5 项全拒 / T5.3 前端 ask 主干 pnpm check 过 / T5.4 移交核对——本包验收证据并入阶段验收报告 §2–§4，无独立审核记录）
- [M5 W4 再次深度审核记录 v1](checklists/M5-W4-再次深度审核-v1.md)（已归档：换视角复审 5 项发现——**R-A P1 group(1) 同款缺陷第三次出现（waiting 路径）**、**R-B P1 SensitiveClosedException 被 catch 链污染致重放序列 FINISHED 后跟 RUN_ERROR**、R-C P2 审批单 EXPIRED 误报 REJECTED；3 修 2 登记，E2E 23/23 复验）
- [M5 W4 深度审核记录 v1](checklists/M5-W4-深度审核-v1.md)（已归档：结构化终态与 HITL 内联 T4.1–T4.3 落地——五终态载荷契约收敛/文案模板渲染/敏感触发→WAITING_APPROVAL→决策→令牌单次消费→FINISHED 全链（拒绝→FAILED+收尾文案）/R-30 三条模板发布（清单 v10, hash 6b1f4e33…）；E2E 23/23；基线 395 例全绿）
- [M5 W3 深度审核记录 v1](checklists/M5-W3-深度审核-v1.md)（已归档：LLM 编排器与 AG-UI T3.1–T3.4 落地——编排主链/确定性降级（degraded 声明）/Proposal 完整性对抗 6 类全拦截（InjectionCheckpoint 首次真实接入）/事件持久化与 afterSeq 重放幂等（重复执行 0）/RUN_STARTED 脱敏/run 状态机锁定；E2E 14/14；基线 395 例全绿）
- [M5 W2 深度审核记录 v1](checklists/M5-W2-深度审核-v1.md)（已归档：检索瀑布 T2.1–T2.4 落地——三层瀑布/授权裁剪/模糊进 DSL=0/澄清四要素/指代消解；T2.4 回填 300 条非拼音 Top-5 **98.82% ≥95%** 零污染；清单 **v9**（2026.11.09-ninth）别名扩充经全量回归（Golden 27/0、冒烟 14/0/6、RLS 30/0、E5 9/9、敏感 32/32）无破坏；基线 382 例全绿）
- [M5 W1 深度审核记录 v1](checklists/M5-W1-深度审核-v1.md)（已归档：会话状态机落地——投影模型/turn 占用/澄清候选校验/键隔离，真库语义验证全过；实施期 2 缺陷修复；基线 365 例全绿）
- [M4 收口后深度审核 v1](checklists/M4-收口后深度审核-v1.md)（已归档：全局复核 4 项发现全部当场修复复验——OBL-M4 台账闭环、审批降级负载补 filtersHash、roadmap M4 状态同步、触发拒绝停在 EXECUTED 终态的时序修正（DB 实证 55 条新请求 REJECTED 无行数）；M4 判定不变：通过）
- [M4 验收报告 v1](checklists/M4-验收报告-v1.md)（已冻结，E1–E6 全部满足，M5 放行；OBL-M4 闭环，OBL-M7 按 ADR-0015 归 M6 核对）
- [M4 W4 深度审核记录 v1](checklists/M4-W4-深度审核-v1.md)（已归档：T4.1–T4.4 落地——PoC #7 回归 32/32（域组合如实登记归 M5/M6）、PoC #1 接入基线（--live 7 PASS）、HITL 最小通道（SSE INTERRUPT 事务提交后下发）、E5 扩充 5 探针全拒、注入检查点接口冻结）
- [M4 W3 深度审核记录 v1](checklists/M4-W3-深度审核-v1.md)（已归档：T3.1/T3.2 落地，E2E 25/25（恰好过期边界 / 审计 approval_id 关联 / 计数器哈希同值同源）；启动扫描真实重启实测过期 8 张；基线 346 例全绿）
- [M4 W2 深度审核记录 v1](checklists/M4-W2-深度审核-v1.md)（已归档：T2.1–T2.3 全落地，E2E 21/21（个体探针/精确差分/窗口冻结含缓存命中路径/审批令牌单次有效）；修复差分基准漏 lookup 身份、缓存命中绕过冻结两项 P1；基线 344 例全绿）
- [M4 W1 深度审核记录 v1](checklists/M4-W1-深度审核-v1.md)（已归档：2 项当场修复（激活异常日志 / 运行时策略版本入缓存键 cache-key-1.1）、3 项规格文字偏差登记；W1 判定通过，基线 320 例复跑全绿）
- [M7 最小框架验收记录 v1](checklists/M7-最小框架验收记录-v1.md)（已冻结，有条件通过：三件交付物与 M4 规格 E6 三条判据全满足；本地 PG18 真实复跑 14 pass / 0 fail / 6 skip，哈希四方复算一致；触发阶段重定见 [ADR-0015](../01-decisions/ADR-0015-M7最小框架验收与触发阶段重定.md)）
- [正式评测集 v6](evaluation/正式评测集-v6.md)（已冻结，收编 12 个子集、四集合归属与版本哈希；**test 集 276 题 / 有效分母 276，E-5 部分关闭、E-6 关闭**；缺口 E-3/E-4/E-7 登记）
  - 历史版本：[v5](../99-archive/verification/正式评测集-v5.md)、[v4](../99-archive/verification/正式评测集-v4.md)、[v3](../99-archive/verification/正式评测集-v3.md)、[v2](../99-archive/verification/正式评测集-v2.md)、[v1](../99-archive/verification/正式评测集-v1.md)（均已归档）
- [test 集 v5](evaluation/test集-v5.md)（已冻结，276 题：27 个题族，四维分层 + `analysis_mode` 附加维度；E-5 销量口径 + E-6 auto 入口；skip 0 / 有效 276；JSON sha256 `9f525c46…`；主指标唯一分母）
  - 历史版本：[v4](../99-archive/verification/test集-v4.md)、[v3](../99-archive/verification/test集-v3.md)、[v2](../99-archive/verification/test集-v2.md)、[v1](../99-archive/verification/test集-v1.md)（均已归档）
- [评测统计口径 v1](evaluation/评测统计口径-v1.md)（已冻结，§13.4 主指标 / 四维分层 / Wilson 95% / 哈希语义；只定义方法不定义阈值）
- [SLO 与成本预算 v1](evaluation/SLO与成本预算-v1.md)（已冻结：延迟 P95 ≤2000ms / 错误率 0 / 超时率 0（并发 1/8/16）+ LLM 单题 token ≤8000、P95 ≤6000；金额换算待 W2 模型确定后追加）
- [失败看板闭环证据 v1](evaluation/失败看板闭环证据-v1.json)（M7/W4：登记→分类→分诊→修复→回流 dev 全链 + 非法迁移/回流 test/跨租户负例；V17 只追加未改写）
- [失败看板联动证据 v1](evaluation/失败看板T4.3联动证据-v1.json)（M7/W4/T4.3：单维联动过滤 8/8 探针——分布仍基于全集、非白名单维度/空值/非布尔值一律 `1003000012`、`GET /categories` 30 类闭词表；前端 `pnpm check` 26 例 + `vite build` 通过）
- [LLM 调用追踪证据 v1](evaluation/LLM调用追踪证据-v1.json)（M7/W5/T5.1：同一次 ask 的 traceId 串起 3 条模型调用记录（提案 1 + MemoryConsolidator 2）、token/耗时/终态齐全、桩侧计数与落库条数一致 8/8；模型侧为本地 OpenAI 协议桩，只证追踪链路，不代表模型效果）
- [零号冒烟评测证据 v3](evaluation/零号冒烟-评测证据-v3.json)（版本化固化，SHA-256 `c6b2b87a…`；`-latest` 为每次复跑覆盖的软名结果）
- [M3 验收报告 v1](checklists/M3-验收报告-v1.md)（已冻结，E1–E6 全部满足，M5 放行）
- [M2 验收报告 v1](checklists/M2-验收报告-v1.md)（已冻结，E2–E8 满足、E1 按 [ADR-0013](../01-decisions/ADR-0013-Golden不可构造案例处置口径.md) 附条件关闭，M3 放行）
- [M2 收口后深度审核 v1](checklists/M2-收口后深度审核-v1.md)（已归档：12 项发现全部修复复验，R-18 关闭；M2 判定不变）
- [M1 验收报告 v2](checklists/M1-验收报告-v2.md)（已冻结，E1–E8 全部满足，M2 编码放行）
  - 历史版本：[v1](../99-archive/checklists/M1-验收报告-v1.md)（已归档；E6/E7 于 v2 收口）
- [M1 基线自检输出](checklists/M1-基线自检-2026-11-08.txt)（E8 的归档原始输出，4 PASS / 0 FAIL）
- [M0 验收报告 v2](checklists/M0-验收报告-v2.md)（已冻结，G1–G8 逐条核对；G2 有条件关闭经签字并见 [ADR-0006](../01-decisions/ADR-0006-G2有条件关闭与重开口径.md)；含审计勘误记录）
  - 历史版本：[v1](../99-archive/checklists/M0-验收报告-v1.md)（已归档；Golden 集条数与 HITL/STATE 回填阶段两处口径经审计勘误）
- [M0 基线自检输出](checklists/M0-基线自检-2026-11-07.txt)（G7 的归档原始输出，4 PASS / 0 FAIL）
- [零号冒烟集 v1](evaluation/零号冒烟集-v3.md)（已冻结，20 题题目与分类；gold 已补齐，见下）
- [零号冒烟集 gold v1](evaluation/零号冒烟集-gold-v3.json)（20 题 gold + sha256 冻结，含两处行为校准）
- [Golden 集 v2 · 案例设计](evaluation/Golden集-v2-案例设计.json)（30 条覆盖 5 模式 + 6 显式负例；`g-f02` 已按 [ADR-0011](../01-decisions/ADR-0011-M2实施规格状态修订.md) 决策 5 由正例改判为「步骤数不足」负例）
- [Golden 集 v2 · SQL 回填 gold v1](evaluation/Golden集-v2-gold-v1.json)（27 条可构造案例的 `sqlExpected` 由编译器生成、逐条审阅后回填；3 条不可构造如实登记原因，见 [M2 验收报告 §4](checklists/M2-验收报告-v1.md)）
- [编译器 Golden 集 v1](evaluation/编译器Golden集-v1.json)（30 条，QUERY 20 + 非 QUERY 10；M2 补齐模式后升版）
- [复合意图题集 v2](evaluation/复合意图题集-v2.json)（PoC #8 判据集，30 题；assetRef 已校准至首发清单真实 ID）
- [复合意图题集 · M2 回填](evaluation/复合意图题集-v2-M2回填.json)（无依赖子集：可表达 14/14 正确，12 通过 + 2 语义正确拒绝；结果依赖 9 题登记 M6）
- [M3 Golden 增量 v1](evaluation/M3-Golden-v1.json)（6 条：lookup 单/批/布尔/变体命中/歧义负例/上季度；逐字比对 6/0）
- [M6 Golden 增量 v1](evaluation/M6-Golden-v1.json)（23 条：留存 10 —— 5 正例（周/月/主体维度/上季度/事件对）+ 5 负例；归因 8 —— 4 正例（last_touch/order_count/无渠道维度/14d 窗口）+ 4 负例（未发布事件/未实现模型/跨表维度/客户端 timeRange）；漏斗过滤 5 —— 2 正例（EQ / IN 无维度）+ 3 负例（非主体维度/未发布维度/EQ 值数量）；逐字比对 23/0，绑定值含观察锚点只作参考）
- [中文提及样本集 v1](evaluation/中文提及样本集-v1.json)（PoC #5 判据集，300 条 120/90/45/45；结果待 M5 回填）
- [PoC-01 · M2 RLS 回归证据](poc/PoC-01-M2回归-2026-11-08.json)（100 轮跨租户交替执行，越权 0；存在性探测两租户同形）
- [PoC-01 · RLS 最终边界报告 v1](poc/PoC-01-RLS最终边界.md)（系统级，限定范围 PASS，500 次检查 0 越权；补验不通过的后果见 ADR-0006/验收报告 v2）
- [PoC-02 · 发布快照可复现报告 v1](poc/PoC-02-发布快照可复现.md)（系统级 PASS，跨 2 次重启 300/300 逐字一致）
- [PoC-03 · AgentScope 准入报告 v1](poc/PoC-03-AgentScope准入.md)（坐标/Boot/API 差异通过；五类终态重建 FAIL，协议层 100%、语义层 68.8%；重开口径见 ADR-0006）
- [PoC-04 · 编译器 SQL 输出报告 v1](poc/PoC-04-编译器SQL输出.md)（QUERY 20/20 逐字一致、EXPLAIN 全通过；非 QUERY 静默聚合缺陷已 fail-closed 修复，模式构造器待 M2）
- [PoC-07 · 跨轮差分与存在性保护报告 v1](poc/PoC-07-跨轮差分与存在性保护.md)（探针级，现行初值 FAIL、修订退路 PASS）
- [PoC-10 · 前端直连 AG-UI SSE（协议 + 界面）v1](poc/PoC-10-协议探针.md)（协议段四类事件可达、幂等通过；界面段 20/20 重建 4/6 类事件；物理断线重连场景待 M5 补验）
- [T5.5 · CopilotKit Headless 接入验证 v1](poc/T5.5-CopilotKit接入验证-v1.md)（零 Node runtime 直连成立，但入口为 `unsafe_dev_only`，结论走退路；CopilotKit 列观察项）
- [M0 硬阻断结论 v2](poc/M0-硬阻断结论-v2.md)（G1 汇总与闸门判定：**G1 关闭**，三项决策已签字）
  - 历史版本：[v1](../99-archive/verification/M0-硬阻断结论-v1.md)（已归档，签字前的「未关闭」判定）
- [M0 PoC 总览 v1](poc/M0-PoC总览-v1.md)（10 项 PoC 状态定档；闸门状态以验收报告 v2 为准）

**M1 阶段探针证据**（原始 JSON，报告见进度与验收记录）

- [闸门可行性-raw.json](poc/闸门可行性-raw.json)（点名资产机械闸门，26 样例两阶段：真机 25/26，误拦全部归因别名缺失）
- [PoC-03-重开判定-正式批1-raw.json](poc/PoC-03-重开判定-正式批1-raw.json)（G2 正式批 1：语义一致率 100%，1/3 批）
- [PoC-03-重开判定-正式批2-raw.json](poc/PoC-03-重开判定-正式批2-raw.json)（G2 正式批 2：16/16，修复轮 0）
- [PoC-03-重开判定-正式批3-raw.json](poc/PoC-03-重开判定-正式批3-raw.json)（G2 正式批 3：16/16，修复轮 0；三批 60 会话跨 2 自然日，G2 维持关闭）
- [E6-assets-v2.png](poc/E6-assets-v2.png) / [E6-assets-impact-v3.png](poc/E6-assets-impact-v3.png)（E6 浏览器复核证据：数据词典页业务名渲染与引用反查）
- [PoC-02-首发回归-raw.json](poc/PoC-02-首发回归-raw.json)（PoC#2 首发清单回归 PASS：300/300 逐字一致）
- [路径规划探针-raw.json](poc/路径规划探针-raw.json)（T4.4：7 关系正反向 + golden 优先 + 负例，19/19）

本目录遵循 `../README.md`（文档规范与总索引）：统一头块、生命周期、单一事实源与相对链接规则。

**M6/W5 收口与 M7 全量交付物收敛（2026-11-11）**

- [M6 验收报告 v2](checklists/M6-验收报告-v2.md)（全量深度审核勘误升版；v1 归档 [99-archive](../99-archive/verification/M6-验收报告-v1.md)）（已冻结：W1–W5 逐项核对、E1–E8 全满足、live 基线 7/0/0、OBL-M7 闭环 done；附非阻塞条件项 E-5/E-6/E-7、CopilotKit spike、R-31）
- [M7 全量交付物收敛验收报告 v2](checklists/M7-全量交付物收敛验收报告-v2.md)（证据升版 + 纠正样本回流判据勘误；v1 归档 [99-archive](../99-archive/verification/M7-全量交付物收敛验收报告-v1.md)）（已冻结：§3.8 八项交付物 8/8、§13.5 硬门槛逐条对照、确定性降级路径口径与缺口如实声明；OBL-M7 台账 done 依据；不是 M7 阶段验收）
- [M6 · W5/T5.1 深度审核 v1](checklists/M6-W5-T5.1-深度审核-v1.md)（评测数据面 + 失败看板；T51-1/T51-3 P1、T51-2 P2、T51-4/T51-5 P3 全部当场修复）
- [M6 · W5/T5.3 运维面深度审核 v1](checklists/M6-W5-T5.3-运维面深度审核-v1.md)（G-5 保留策略 + 线程池扩容 + 拒绝映射核对；T53-1 TIMESTAMPTZ 时区修复）
- [M6 · W5/T5.4 移交核对 v1](checklists/M6-W5-T5.4-移交核对-v1.md)（§3.7 十一项逐项点名 + CopilotKit 复查 + R-31 边界声明）
- [M6 · W5 收口深度审核 v1](checklists/M6-W5-收口深度审核-v1.md)（含 M6 全局审核：五维铁律复核、T55-1 P2 基线修复、提交范围）
- [M6 收口基线证据 v2](evaluation/M6-收口基线证据-v2.json)（live 基线 7 PASS / 0 FAIL / 0 SKIP；PoC#7 32 项、PoC#1 30 轮）
- [对抗扩充 M6 证据 v2](evaluation/对抗扩充-M6证据-v2.json)（T5.2 8 项探针 8/8 全拒 + 注入值参数化泄露 0）
- [PoC-01 · M6 收口 RLS 回归证据](poc/PoC-01-M2回归-2026-11-11.json)（30 轮越权/异常 0；T55-3 修复后按日期另存，M2 冻结证据 `2026-11-08` 保持不动）

**M6 全量深度审核（2026-11-11）**

- [M6 · 全量深度审核 v1](checklists/M6-全量深度审核-v1.md)（独立复审：三路只读代码交叉核查 + 证据哈希复算 + 真跑；22 项发现 D-1～D-22，含 2 项 P1——场景状态污染与冒烟证据版本错配；当场修复 19 项、报告 v2 勘误 2 项、附条件登记 1 组；验收结论维持通过）
- [零号冒烟-评测证据 v3](evaluation/零号冒烟-评测证据-v3.json)（20/20，#15/#16 = PROMPT_INJECTION_SUSPECTED；初稿 v2 归档 [99-archive](../99-archive/evaluation/零号冒烟-评测证据-v2.json)）
- [test集-M6收敛评测证据 v2](evaluation/test集-M6收敛评测证据-v2.json)（269/0/2/0；分层 Wilson 分母改为有效样本；dashboardPush 字段在位）
- [消融对照-M6证据 v2](evaluation/消融对照-M6证据-v2.json)（6/6 动态断言通过；A5 如实 4/4 + 历史 1/10）
- [压测-M6证据 v2](evaluation/压测-M6证据-v2.json)（并发 16 P95=145ms / 242rps；跨相位全量 P95 字段在位）
- [场景状态fail-closed证据 v1](evaluation/场景状态fail-closed证据-v1.json)（D-1 真服务 5/5：拒绝零状态污染 + 正向提交生效）

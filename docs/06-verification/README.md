# 06 · 验证

**放**：PoC 报告、评测集与结果、检查清单执行证据。

**不放**：未执行的设想、计划中的测试。

**索引 / 约定**：`poc/` `evaluation/` `checklists/`；判据引用技术选型 §7，不复制阈值。

**当前文档**

- [M6 阶段中深度审核 v1](checklists/M6-阶段中深度审核-v1.md)（已冻结：全局复核 7 项——**P1 回归：queryds V3 事件 ID 与 M2 funnel 证据脚本 fixture 主键冲突，真库报 duplicate key，已改 e-m6-* 前缀修复复验**；证据期望漂移按 append-only 更正；OBL-M7 题目编写未按决策 8 启动登记为硬欠账；状态漂移/契约错误码/T1.3 分流约束登记；结论：阶段中审核通过，不宣告 M6 关闭）
- [M6 · W1/T1.3 深度审核 v1](checklists/M6-W1-T1.3-深度审核-v1.md)（已冻结：留存构造器单任务审核——规格 §1.1/§1.2 逐条对照符合、无 P1/P2 代码缺陷；2 项登记（模式规格示例 `dim.channel` 与主体表维度定档冲突 / 笛卡尔展开的规模性能）；真库执行验证未完整期排除、Golden 10/0、M2 27/0 与 M3 6/0 不回退、`mvn test` 427 例）
- [M6 · W1/T1.4 深度审核 v1](checklists/M6-W1-T1.4-深度审核-v1.md)（已冻结：归因构造器单任务审核——**P1 缺陷（SQL 别名 `channel.*` 与 `as ch` 冲突）由人工审阅发现并修复复验**，Golden 逐字比对测不出该缺陷；3 项登记（`touched_events` 语义缺口 / 清单 v12 顺序调整 / UNATTRIBUTED 真库样例）；真库执行线上 12498/3、线下 6308/3，Golden 18/0、`mvn test` 451 例）
- [M6 · W1/T1.4 再次深度审核 v1](checklists/M6-W1-T1.4-再次深度审核-v1.md)（已冻结：换视角复审（契约精读/故障注入/攻击者/性能）——**P2 语义缺陷（UNATTRIBUTED 按渠道名判定，把「有触点但主数据缺失」伪装成无触点）当场修复**，3 项登记（事件表索引 / 重复转化事件 / 渠道值域一致性）；T1.5 放行）
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
- [正式评测集 v1](evaluation/正式评测集-v1.md)（已冻结，收编 11 个存量子集、四集合归属与版本哈希；`test` 集 0 题欠账归 M7 收敛）
- [评测统计口径 v1](evaluation/评测统计口径-v1.md)（已冻结，§13.4 主指标 / 四维分层 / Wilson 95% / 哈希语义；只定义方法不定义阈值）
- [零号冒烟评测证据 v1](evaluation/零号冒烟-评测证据-v1.json)（版本化固化，SHA-256 `850f35bf…`；`-latest` 为每次复跑覆盖的软名结果）
- [M3 验收报告 v1](checklists/M3-验收报告-v1.md)（已冻结，E1–E6 全部满足，M5 放行）
- [M2 验收报告 v1](checklists/M2-验收报告-v1.md)（已冻结，E2–E8 满足、E1 按 [ADR-0013](../01-decisions/ADR-0013-Golden不可构造案例处置口径.md) 附条件关闭，M3 放行）
- [M2 收口后深度审核 v1](checklists/M2-收口后深度审核-v1.md)（已归档：12 项发现全部修复复验，R-18 关闭；M2 判定不变）
- [M1 验收报告 v2](checklists/M1-验收报告-v2.md)（已冻结，E1–E8 全部满足，M2 编码放行）
  - 历史版本：[v1](../99-archive/checklists/M1-验收报告-v1.md)（已归档；E6/E7 于 v2 收口）
- [M1 基线自检输出](checklists/M1-基线自检-2026-11-08.txt)（E8 的归档原始输出，4 PASS / 0 FAIL）
- [M0 验收报告 v2](checklists/M0-验收报告-v2.md)（已冻结，G1–G8 逐条核对；G2 有条件关闭经签字并见 [ADR-0006](../01-decisions/ADR-0006-G2有条件关闭与重开口径.md)；含审计勘误记录）
  - 历史版本：[v1](../99-archive/checklists/M0-验收报告-v1.md)（已归档；Golden 集条数与 HITL/STATE 回填阶段两处口径经审计勘误）
- [M0 基线自检输出](checklists/M0-基线自检-2026-11-07.txt)（G7 的归档原始输出，4 PASS / 0 FAIL）
- [零号冒烟集 v1](evaluation/零号冒烟集-v2.md)（已冻结，20 题题目与分类；gold 已补齐，见下）
- [零号冒烟集 gold v1](evaluation/零号冒烟集-gold-v2.json)（20 题 gold + sha256 冻结，含两处行为校准）
- [Golden 集 v2 · 案例设计](evaluation/Golden集-v2-案例设计.json)（30 条覆盖 5 模式 + 6 显式负例；`g-f02` 已按 [ADR-0011](../01-decisions/ADR-0011-M2实施规格状态修订.md) 决策 5 由正例改判为「步骤数不足」负例）
- [Golden 集 v2 · SQL 回填 gold v1](evaluation/Golden集-v2-gold-v1.json)（27 条可构造案例的 `sqlExpected` 由编译器生成、逐条审阅后回填；3 条不可构造如实登记原因，见 [M2 验收报告 §4](checklists/M2-验收报告-v1.md)）
- [编译器 Golden 集 v1](evaluation/编译器Golden集-v1.json)（30 条，QUERY 20 + 非 QUERY 10；M2 补齐模式后升版）
- [复合意图题集 v2](evaluation/复合意图题集-v2.json)（PoC #8 判据集，30 题；assetRef 已校准至首发清单真实 ID）
- [复合意图题集 · M2 回填](evaluation/复合意图题集-v2-M2回填.json)（无依赖子集：可表达 14/14 正确，12 通过 + 2 语义正确拒绝；结果依赖 9 题登记 M6）
- [M3 Golden 增量 v1](evaluation/M3-Golden-v1.json)（6 条：lookup 单/批/布尔/变体命中/歧义负例/上季度；逐字比对 6/0）
- [M6 Golden 增量 v1](evaluation/M6-Golden-v1.json)（18 条：留存 10 —— 5 正例（周/月/主体维度/上季度/事件对）+ 5 负例；归因 8 —— 4 正例（last_touch/order_count/无渠道维度/14d 窗口）+ 4 负例（未发布事件/未实现模型/跨表维度/客户端 timeRange）；逐字比对 18/0，绑定值含观察锚点只作参考）
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

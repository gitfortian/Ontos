# Ontos 文档规范与总索引

> **状态**：已冻结
> **版本**：v2.28
> **类型**：规范 / 索引
> **负责人**：项目负责人
> **最后更新**：2026-11-11
> **适用范围**：`docs/` 下全部文档，以及仓库内所有引用 `docs/` 的代码与配置
> **上游依据**：v1.4 完整可落地方案 §16 引用边界、v1.1 技术选型 §10 事实台账
> **修订记录**：2026-11-06 v1.0 首次发布；建立分层、命名、生命周期与单一事实源规则；2026-11-07 v1.1 索引维护——M0 验收报告 v2 勘误版、ADR-0006、版本事实台账、基线自检归档入索引；2026-11-08 v1.2 M2 阶段索引维护——补登记 M2 实施规格、funnel/yoy_mom 规格、ADR-0009~0011、Golden 集 v2 案例设计、冒烟集 gold、11-08 进度快照；2026-11-08 v1.3 补登记 ADR-0012（EventDef 事件判定映射扩展与清单 v2）；2026-11-08 v1.4 M2 收口索引维护——补登记 M2 验收报告 v1（已冻结，E1 按 ADR-0013 附条件关闭）、ADR-0013、M2 基线自检、Golden v2 gold、复合意图 M2 回填、PoC-01 M2 回归与 M2 E5 对抗探针（本文件路径固定为唯一入口，升版以修订记录承载，不迁移归档）；2026-11-09 v1.5 M2 收口后深度审核入索引——补登记《M2 收口后深度审核 v1》（12 项发现闭环、R-18 关闭、M2 判定不变）；2026-11-09 v1.6 M3 开工——补登记《M3 简单问法最小闭环 实施规格 v0.1》（草稿，待确认三个设计决策）；2026-11-09 v1.7 M3 规格冻结——三项决策确认，升 v1.0 并把 v0.1 移入 99-archive；2026-11-09 v1.8 M3 收口——补登记《M3 验收报告 v1》（E1–E6 满足，M5 放行）与 M3 Golden 增量；2026-11-09 v1.9 M5 开工——补登记《M5 会话意图编排与 AG-UI 链路 实施规格 v0.1》（草稿，待确认三个设计决策）；2026-11-09 v2.0 顺序修正（负责人定档按 ADR-0014 纪律先 M4 后 M5）——OBL-M7 最小框架三件入索引（正式评测集 / 统计口径 / 零号冒烟 runner 证据），零号冒烟集与 gold 升 v2（D63 哈希语义修复），M5 规格保持草稿待 M4 后冻结；2026-11-09 v2.1 M4 开工——补登记《M4 身份权限与安全治理 实施规格 v0.1》（草稿）；2026-11-09 v2.2 M4 规格冻结——三项决策确认升 v1.0，v0.1 归档；2026-11-09 v2.3 M4 规格升 v1.1——吸收外部审核（P0×3/P1×3/P2×4 采纳，P1-4 拒绝并留痕）与两轮内部自审 12 项（角色并集/多副本 TTL/目标指纹/§4.8 字段对齐等），v1.0 归档；2026-11-09 v1.9 并行义务纪律落地——补登记 ADR-0014、并行义务台账与强制检查脚本，新增 §7.8 硬条款；2026-11-09 v2.4 M7 最小框架验收闭环——真实复跑冒烟 runner 并固化版本化证据、冻结《M7 最小框架验收记录 v1》、新发 ADR-0015 重定 OBL-M7 触发阶段（M4→M6）、roadmap M4/M7 状态同步；2026-11-09 v2.5 M4 收口——补登记《M4 验收报告 v1》（E1–E6 满足，M5 放行）、W1–W4 四包深度审核与《M4 收口后深度审核 v1》（G-1~G-4 修复）、并行义务台账 OBL-M4 闭环、roadmap M4 状态同步；2026-11-09 v2.6 M5 规格深度 review——v0.1 归档，v0.2 修订稿（13 项发现：过时基线 5 / 设计缺口 6 / 决策扩充 2；补 M4 移交前置与 M7 提及样本回填义务，E7 新增），决策点 6 项待定档；2026-11-09 v2.7 M5 规格冻结——负责人确认六项决策（双模块/终态澄清/不引入拼音/HITL 轮询/事件表重放/composite 透传）升 v1.0，v0.2 归档，编码待两轮自审后放行；2026-11-09 v2.8 M5 两轮自审闭环——8 项发现全部采纳（**F-5 P0：execute_query 不得暴露为 LLM 工具，执行是编排代码内部步骤**；F-1 会话合并模型澄清=DSL 投影；F-2 L3 改 Java 编辑距离；F-4 批量授权视图接口；F-3/F-6/F-7/F-8），升 v1.1，v1.0 归档，编码放行待负责人确认；2026-11-09 v2.9 M5 第三轮自审（攻击者/故障注入/时序视角）——5 项发现全部采纳（**R3 P1 WAITING_APPROVAL 期间拒绝新 run；R4 P1 run 启动扫描崩溃恢复；R5 P1 澄清候选持久化+回传校验**；R2 run 创建=turn 占用原子性；R1 投影措辞下游同步），升 v1.2，v1.1 归档，编码放行待负责人确认；2026-11-09 v2.10 M5 收口——W1–W5 全部完成（会话状态机/检索瀑布+清单 v9/LLM 编排器+清单 v10/终态与 HITL 内联/端到端对抗），《M5 验收报告 v1》冻结（E1–E7 满足，PoC #3 复跑 20/20 收口 G2 遗留，live 基线 7 PASS，ask-probe 29/29），真实模型冒烟登记上线前检查项，M6 放行；2026-11-09 v2.11 M6 规格冻结——补登记《M6 实施规格 v1.0》（两轮自审 11 项 + 深度 review 10 项闭环、八决策定档、编码放行）、《analysis-mode · retention 与 attribution 规格 v1.1》（v1 归档 99-archive/specs）、清单 v11 与《M6 阶段中深度审核 v1》（P1 回归：V3 事件 ID 与 funnel 证据脚本主键冲突已修复复验；OBL-M7 题目编写登记为硬欠账）；2026-11-10 v2.12 D-3 复审——test 集 v1 经逐题复审判定不通过并升 v2（修正 shape/mode、补齐分析模式参数、对齐清单与 seed、哈希改磁盘字节复算，skip 2 / 有效 239），正式评测集升 v3 登记新哈希，新增缺口 E-5（品类×订单事实指标粒度扩散）与《M6 · W5/D-3 test 集再次深度审核 v1》；2026-11-10 v2.13 M6/W2 T2.1——新增 `ontos-module-catalog` 管理面模块与 V15 迁移（scenario/skill/vqr 生命周期表 + audit_log 十一列扩展）、ArchUnit 增两条边界规则，新发 ADR-0016 定档品类×订单事实指标的粒度边界；2026-11-10 v2.14 M6/W2 T2.2——ScenarioMeta 升级 v1.4 §5.7 完整模型并兼容 v12 扁平形态、§5.7 校验器与 catalog 生命周期/回滚/依赖影响落地、清单 v13 通过 dry-run 预检（未激活，T2.4 统一升版）；2026-11-10 v2.15 外部评审复核——7 项逐条裁决入 ADR-0017（采纳 5 / 修正 2），scenarioId 顺序、依赖引用 schema 方向、近似同意通道、VQR 执行证据、留存标注、E5 映射、R-31 核对项；2026-11-10 v2.16 M6/W2 T2.3——五态路由决策与三态会话状态机落地（pending_confirm 复用 M5 澄清回传）、ADR-0004 三触发条件核对 v1（2/3，路由不启用）；2026-11-10 v2.17 M6/W2 T2.4——Router 接入瀑布 + scope 裁剪 + 直传 scenarioId 裁决（D1 四步）+ 编排模式过滤，ADR-0004 核对升 v2（3/3 放行），v13 发布并激活（thirteenth），Golden 回归零回退；2026-11-10 v2.18 M6/W3——T3.1 Skill 资产模型与生命周期（审核人记录）+ 清单 v14 七条内置 Skill、T3.2 SkillRegistry 清单驱动注册制与编译期四条闸门（复审 T32-6 P1 级回归修复：双态兼容 + v14 激活 fourteenth，端到端 8 项 + Golden 零回退）；2026-11-10 v2.19 M6/W3 T3.3——近似降级机制闭环（声明→同意→MODE_APPROXIMATED 审计，清单 v15 激活 fifteenth），端到端驱动出 T32-8（P1）场景确认断链并修复；2026-11-10 v2.20 M6/W3 T3.4——VQR 资产模型与生命周期（入库七条件，D4 证据外置）+ 自动失效六族接入发布流水线（F-7）+ 清单 v16（vqrs 段 + 场景引用闭环）；2026-11-10 v2.21 M6/W3 T3.5——VQR 命中通道闭环（瀑布第 7 步 + 提案引用 + 八步重校验 + VQR_HIT 审计 + cache-key-1.2），v16 激活 sixteenth，W3 收口；2026-11-10 v2.22 M6/W4——冻结《复合意图结果引用规格 v1》（ADR-0017 D2：resultRef Schema + single_row/ordered_top 分立 + G-6 失败码 044–046）；2026-11-10 v2.23 M6/W4 收口——T4.1 依赖拓扑（044）+ T4.2 结果注入（参数化绑定）+ T4.3 部分失败可解释（双路径统一）+ T4.4 R-27/R-29 + T4.5 PoC #8 复跑 30 题全部符合预期（证据 复合意图题集-v2-M6复跑.json）；2026-11-11 v2.24 M6 收口与 M7 开工索引维护——补登记《M6 验收报告 v2》《M6 全量深度审核 v1》《M7 全量交付物收敛验收报告 v2》《M7 实施规格 v0.1（草稿）》与 M6-W5 T5.1/T5.3/T5.4/收口审核、11-10/11-11 进度快照；M6 实施规格状态改为「已通过验收」；评测集与证据版本列对齐实际文件（正式评测集 v4、test 集 v3、零号冒烟集/证据 v3）；2026-11-11 v2.25 M7 规格冻结——v0.1 经 13 项深度 review（4 项 P1：意图一致率口径、§13.3 十九项处置、温度不可控事实、capabilities 词表跨模块边界）后升 v1.0，八项决策定档（E-5 订单项级指标 / E-6 `scenario.sales.auto` / LLM 路径 ×3 次运行 / 基线对照 N/A 豁免 / 回流只进 dev-validation / 不新增 eval 模块 / SLO 前置冻结），v0.1 归档；2026-11-11 v2.26 M7/W1/T1.1 E-5 落地——ADR-0018 冻结（订单项销量 `metric.order_item_quantity`，订单级 × 品类仍 fail-closed）；清单 v17（`2026.11.11-seventeenth`，contentHash `1d3ec2e2…`，9 指标）；test 集升 v4（273 题 / skip 0，2 题哨兵转预期拒绝 + 2 题销量正例）与正式评测集升 v5；v3/v4 归档；补登记 E-5 首轮评测证据；2026-11-11 v2.27 M7/W1/T1.2 E-6 落地——清单 v18（`2026.11.11-eighteenth`，`scenario.sales.auto` + V18 demo 授权 `seed-2026.11.11-v1`）；test 集升 v5（276 题，auto 3 题 + `session_state` 会话态断言）与正式评测集升 v6；E-6 关闭；v4/v5 归档；2026-11-11 v2.28 M7/W4/T4.4 D-22 三项加固——VQR `paramsSchema` 运行时 type/min/max（DSL_INVALID）、复合非 `ServiceException` 子级隔离（QUERY_EXECUTION_FAILED，堆栈只进日志）、Skill capabilities 发布期闸门（词表下沉 `SkillCapabilities`，`SkillValidator` 校验）；新增《SLO 与成本预算 v1》冻结延迟/错误率/超时率与 LLM token 预算

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
| 技术栈版本、锁定项、前端组件选型 | `02-solutions/architecture/智能问数系统技术选型方案-v1.1.md` | 只引用，不复制版本号 |
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
| 文档规范与总索引 | 规范 | 已冻结 | v2.28 | [`README.md`](README.md) |
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
| ADR-0018 · E-5 订单项级指标实现口径 | 决策 | 已冻结 | v1 | [`01-decisions/ADR-0018-E5订单项级指标实现口径.md`](01-decisions/ADR-0018-E5订单项级指标实现口径.md) |
| M7 最小框架验收记录 | 验证 | 已冻结 | v1 | [`06-verification/checklists/M7-最小框架验收记录-v1.md`](06-verification/checklists/M7-最小框架验收记录-v1.md) |
| 正式评测集 | 验证 | 已冻结 | v6 | [`06-verification/evaluation/正式评测集-v6.md`](06-verification/evaluation/正式评测集-v6.md) |
| test 集（主指标分母） | 验证 | 已冻结 | v5 | [`06-verification/evaluation/test集-v5.md`](06-verification/evaluation/test集-v5.md) |
| test 集 v4 评测证据（E-5 首轮） | 验证 | 已冻结 | v1 | [`06-verification/evaluation/test集-M7W1-E5证据-v1.json`](06-verification/evaluation/test集-M7W1-E5证据-v1.json) |
| test 集 v5 评测证据（E-6 首轮） | 验证 | 已冻结 | v1 | [`06-verification/evaluation/test集-M7W1-E6证据-v1.json`](06-verification/evaluation/test集-M7W1-E6证据-v1.json) |
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
| M7 全量交付物收敛验收报告（OBL-M7 闭环） | 验证 | 已冻结 | v2 | [`06-verification/checklists/M7-全量交付物收敛验收报告-v2.md`](06-verification/checklists/M7-全量交付物收敛验收报告-v2.md) |
| M6 · W5/T5.1 深度审核 | 验证 | 已冻结 | v1 | [`06-verification/checklists/M6-W5-T5.1-深度审核-v1.md`](06-verification/checklists/M6-W5-T5.1-深度审核-v1.md) |
| M6 · W5/T5.3 运维面深度审核 | 验证 | 已冻结 | v1 | [`06-verification/checklists/M6-W5-T5.3-运维面深度审核-v1.md`](06-verification/checklists/M6-W5-T5.3-运维面深度审核-v1.md) |
| M6 · W5/T5.4 移交核对 | 验证 | 已冻结 | v1 | [`06-verification/checklists/M6-W5-T5.4-移交核对-v1.md`](06-verification/checklists/M6-W5-T5.4-移交核对-v1.md) |
| M6 · W5 收口深度审核 | 验证 | 已冻结 | v1 | [`06-verification/checklists/M6-W5-收口深度审核-v1.md`](06-verification/checklists/M6-W5-收口深度审核-v1.md) |
| M7 实施规格 | 计划 | **已冻结（编码放行）** | v1.0 | [`03-plans/stages/M7/M7-评测对抗防线与可观测-实施规格-v1.0.md`](03-plans/stages/M7/M7-评测对抗防线与可观测-实施规格-v1.0.md)（[v0.1](99-archive/plans/M7-评测对抗防线与可观测-实施规格-v0.1.md) 已归档） |
| 进度快照（M6/W5 数据面与收口） | 进度 | 已记录 | — | [`05-progress/2026/2026-11-10.md`](05-progress/2026/2026-11-10.md) |
| 进度快照（M6 收口与 M7 接手） | 进度 | 已记录 | — | [`05-progress/2026/2026-11-11.md`](05-progress/2026/2026-11-11.md) |
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

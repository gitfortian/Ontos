# ADR-0024 · 前端表单库选型修订（TanStack Form 生效解释）

> **状态**：已冻结（负责人 2026-11-12 拍板「采用」）
> **版本**：v1
> **类型**：决策
> **负责人**：项目负责人
> **最后更新**：2026-11-12
> **适用范围**：技术选型 v1.1 §2「前端表单/状态」行与附录 A「表单 / 状态」行的**生效解释**；`ontos-ui` 表单实现范式与依赖收敛；`AGENTS.md` / `ontos-ui/FRONTEND_RULES.md` / `ontos-ui/docs/tooling.md` / 根 `README.md` 的口径同步。不改变技术选型 v1.1 正文，不改变 M8 规格、roadmap 与任何后端契约。
> **上游依据**：[技术选型 v1.1 §2 / 附录 A](../02-solutions/architecture/智能问数系统技术选型方案-v1.1.md)、[ADR-0001 前端组件栈归位范围](ADR-0001-前端组件栈归位范围.md)、[ADR-0002 跨轮差分规则修订（同型生效解释先例）](ADR-0002-跨轮差分规则修订.md)、[ADR-0021 平台四角色与资产审批发布模型（Phase A/B 表单来源）](ADR-0021-平台四角色与资产审批发布模型.md)、[表单库可行性 Spike 报告](../../ontos-ui/apps/web/spikes/form-eval/README.md)、[M0 验收报告 v2 §升版欠账](../06-verification/checklists/M0-验收报告-v2.md)
> **修订记录**：2026-11-12 v1 冻结：负责人确认采用 TanStack Form；以生效解释方式修订附录 A 表单行，技术选型正文不动；登记 v1.2 全量收口为独立工作包。

## 背景

1. 技术选型 v1.1 §2 与附录 A 冻结的表单/状态选型为 **React Hook Form + Zod、TanStack Query、Zustand**；`AGENTS.md` §6.1、`ontos-ui/FRONTEND_RULES.md` L9、`ontos-ui/docs/tooling.md` L9、根 `README.md` L15 均引用该行作为「框架与组件权威」。
2. 代码侧该选型**从未落地**：`apps/web` 未安装 `react-hook-form`，`zod` 已声明但零使用，现有输入全是 `useState + value/onChange` 手写受控组件，无 schema 校验与错误状态模型。即本次不是「替换存量」，而是「首次落地表单库」。
3. 《语义模型三层化 影响分析 v0.1》与 ADR-0021 之后的功能规划已进入 Phase A（用户/角色/租户管理）与 Phase B（本体建模、语义资产草稿、审批工作台），二者都是**动态嵌套表单密集型**：业务对象 → 属性联动、口径变体条件数组、允许维度多选、schema 驱动校验。
4. 2026-11-12 完成 [表单库可行性 Spike](../../ontos-ui/apps/web/spikes/form-eval/README.md)：两份等价实现（TanStack Form 1.33.5 / RHF 7.92.0）跑同一组用例 20/20 通过，`tsc`、`pnpm check`、`pnpm build` 全绿，并实测了 Standard Schema 直连、类型推导、渲染次数与打包体积。

## 决策

### D1 · 采用 TanStack Form + Zod（Standard Schema 直连）

- 表单库定为 **`@tanstack/react-form@1.33.5`**；校验 schema 仍以 **zod 3.25+** 为唯一真源，直接传给 `validators.onChange` / `onSubmit`，**不引入 resolver 适配层**（zod `~standard.validate` 原生返回带 `path` 的 issues，form-core 自动分发到字段 errorMap）。
- **TanStack Query / Zustand 职责不变**：服务端数据仍在 Query 共享缓存，表单提交经 `useMutation` + `invalidateQueries`；不在表单内自建服务端缓存。
- 字段值按 schema 推导为精确字面量联合，嵌套数组路径（`variants[0].field`）保持类型安全；共享控件须泛型化以承接该类型。

### D2 · 以「生效解释」修订附录 A，技术选型 v1.1 正文不动

- 与 [ADR-0002](ADR-0002-跨轮差分规则修订.md) / [ADR-0017](ADR-0017-M6外部评审裁决.md) 同型：本 ADR 是技术选型 v1.1 §2「前端表单/状态」行与附录 A「表单 / 状态」行的**当前生效解释**，原文档正文、表格与版本号一律不改、不归档、不迁移链接。
- **自本 ADR 生效起，凡引用「附录 A 表单选型」的文档与代码约定，均以本 ADR 为准**；`AGENTS.md` §6.1、`FRONTEND_RULES.md` L9、`tooling.md` L9、根 `README.md` L15/L64 同步标注该生效解释，避免旧行继续传播 RHF 口径。
- 技术选型 **v1.2 全量收口另立独立工作包**（见「后续动作」），不得把表单库修订单独包装成 v1.2。

### D3 · 不引入 shadcn/ui 官方 Form 封装；表单适配层归共享 UI 包

- shadcn/ui 的 `Form / FormField / FormControl / FormMessage` 是 RHF 专用集成，采用 TanStack Form 后**不复制该封装**。
- 项目共享 UI 包已有 `Field / FieldLabel / FieldError / Input / Select` 原语；表单适配只需一层薄 props 映射（Spike 实测 `tanstack/form-field.tsx` 20 行）。**该适配层应提升为共享 UI 包的正式组件**（后续动作），不允许各页面各写一份。
- Radix Select 交互形态以 `value/onValueChange` 绑定；Spike 中的 `radix-select-adapter.tsx` 仅完成类型级验证，浏览器交互验证列入后续动作。

### D4 · 依赖收敛与测试边界

- 移除 Spike 对照用的 `react-hook-form` / `@hookform/resolvers` / `@radix-ui/react-slot` 及 `rhf/` 对照实现、Radix 适配样本；保留 `@tanstack/react-form` 为 `apps/web` 正式运行时依赖。
- 保留 Spike 的 TanStack 参考实现与行为测试作为集成回归证据（对照实现移除后测试收敛为单实现）。**vitest / jsdom / testing-library 仅服务 `apps/web/spikes/`**；正式代码测试仍按 `AGENTS.md` 走 Node Test Runner（`*.test.mjs`），不得在正式页面引入第二套 runner。

### D5 · 接受已知成本，并登记回退条件

- 接受三项成本：gzip 体积比 RHF+resolver 组合多约 **3.6 KB（13%）**；无官方 shadcn Form 适配；中文资料与现成示例少于 RHF，需自备内部范式。
- 三项成本在 Phase A/B 实装中若出现「大表单性能显著劣化」或「适配层维护成本超过收益」，按「回退方案」另发 ADR 取代本文，不就地改代码口径。

## 依据

> 事实台账与版本依据引用 `07-references/`，不复制。

1. **兼容性与校验链实证**：React 18.3.1 / zod 3.25.76 / pnpm 11.5.1 / Vite 6 无 peer 冲突；`z.object(...).superRefine(...)` 的 `~standard.validate` **同步**返回带 `path` 的 issues，跨字段校验不触发 TanStack 的 async-validator 分支。
2. **行为实证**：两份实现同一组用例 20/20 通过，覆盖初始/dirty、空提交拦截、完整 payload、对象→属性联动、COUNT 对象级计数受控例外、非 COUNT 跨字段拦截、动态嵌套数组增填删、字段级渲染计数、`useMutation` + `invalidateQueries`。
3. **类型实证**：`tsc` 首轮 3 处报错均来自「共用控件签名过窄」而非库缺陷，反证 TanStack 字段值的精确字面量类型在起作用。
4. **反直觉数据如实入账**：字段级订阅优势有限（name 字段 1→2 vs 3→5），代码量基本持平（205 vs 208 行），体积反而更重（31,671 B vs 28,022 B gzip）。选型理由**不建立在这三项上**，而建立在 zod 直连、精确类型与动态嵌套路径上。
5. **工程链实证**：`pnpm format:check` / `oxlint --deny-warnings` / `typecheck` / `architecture:check` / `test:unit` / design token 检查 / `pnpm build` 全通过；`dist/` 唯一疑似命中为 shiki 语法包字符串，Spike 未进正式产物。

## 备选方案

| 备选 | 为何未选 |
| --- | --- |
| 维持 RHF + Zod（附录 A 原文） | 需 `zodResolver` 适配层；`watch` 联动触发整表重渲染；动态嵌套字段值为宽签名。对本项目的动态资产编辑器画像不占优，且当前零存量、无迁移成本可守。 |
| 继续手写受控组件，不引入表单库 | Phase A/B 表单密集，手写无 schema 校验、无统一错误/脏状态模型，会重复造轮子且无法保证 fail-closed 的校验一致性。 |
| 直接升技术选型 v1.2（附录 A 修订） | 依 `docs/README.md` §5「升版本三件事」，升版必须归档 v1.1 并迁移全仓 30+ 处引用锚点；同时触发已登记的升版欠账：ADR-0002 四条并入 §4.8.2、M0/M1 验收报告登记的流式时序判据与实际端点修正、§11 剩余待决项、v1.1 头块「待评审」状态与总索引不一致的修正。这属于一次「技术选型全量收口」，把表单决策夹带进去会造成账实漂移，故拆为独立工作包。 |
| 装包即用、不立决策载体 | 附录 A 是冻结的框架权威，`FRONTEND_RULES.md` L9 明确引用；无载体将使代码与规范长期冲突，违反 `docs/README.md` §7.3。 |

## 影响

1. **文档面**：技术选型 v1.1 正文零改动（不归档、不改链接）；新增本 ADR + 决策索引行 + 总索引行 + 当日进度快照；同步 4 处工程约定文件的口径与表单库名称。
2. **代码面**：`apps/web/package.json` 移除 3 个对照依赖、`pnpm-lock.yaml` 与 `pnpm-workspace.yaml` 供应链白名单同步收敛；Spike 删除 RHF 对照实现后收敛为 TanStack 单实现参考。正式页面、路由、vite 配置、后端契约零改动。
3. **功能面**：Phase A 超管工作台、Phase B 本体/语义资产草稿与审批工作台的所有表单统一走 D1/D3 范式；B0 语义模型三层化的 `Metric/Dimension` 新结构草稿 schema 继续由 zod 承载。
4. **纪律面**：`docs/README.md` §7.3「不改冻结文档」得到遵守；生效解释路径与 ADR-0002/0017 同型，可追溯。

## 回退方案

若 Phase A/B 实装中出现 D5 的回退条件：

1. 新发 ADR（状态「已取代」指向本 ADR），冻结回退口径与影响面，不在代码里直接换库；
2. 移除 `@tanstack/react-form` 与共享 UI 包表单适配层，恢复 RHF + `zodResolver` 或重新评估零库方案；
3. 同步回改 4 处工程约定文件、依赖清单与相关测试；
4. 若此时技术选型 v1.2 已完成全量收口，则在 v1.2 修订记录中标注本 ADR 已取代，编号不复用。

## 后续动作

1. 把 `tanstack/form-field.tsx` 的薄适配层提升为 `@ontos/ui` 正式表单组件，并补 Radix Select 的浏览器交互验证（Phase A 首个表单前完成）。
2. **技术选型 v1.2 全量收口（独立工作包）**：并入本 ADR（附录 A 表单行）+ ADR-0002 §4.8.2 四条 + M0/M1 验收报告登记的流式时序判据与端点表述 + §11 剩余待决项处置 + 头块「待评审」状态修正；按 `docs/README.md` §5 走「新建 v1.2 → v1.1 归档并标注被谁替代 → 更新总索引与全仓引用锚点」。并入后本 ADR 在修订记录标注「已并入」，编号不复用。
3. Phase B 草稿 API 就绪后，补表单到后端的端到端校验（当前 Spike 未与后端联调，见报告 §6）。
4. 评估 Spike 测试是否迁移到 Node Test Runner 或随 Spike 一并退役，避免 vitest 长期作为第二套 runner 存在。

> **并行义务点名**：OBL-M4 / OBL-M7 维持 `done`，`deferred 0`；本 ADR 为前端选型生效解释，不新增、不关闭并行义务。

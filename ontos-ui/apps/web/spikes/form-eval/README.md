# 表单库可行性 Spike · TanStack Form vs React Hook Form

> **状态：已定档采用 TanStack Form**（负责人 2026-11-12 拍板「采用」；决策载体 [ADR-0024](../../../../../docs/01-decisions/ADR-0024-前端表单库选型修订TanStackForm生效解释.md)，已冻结）。
> 本文保留**定档前的对照实测快照**作为证据，历史数字（20/20、99 文件、RHF 对照实现）描述的是 2026-11-12 上午的 Spike 状态，
> **不代表当前运行结果**；定档后的代码与依赖现状见 §0。本文是代码资产内的 spike 报告，不进 `docs/` 治理体系。
> 所有代码隔离在 `apps/web/spikes/form-eval/`，不进 router、不被任何正式页面 import。

## 0. 定档后状态（2026-11-12）

| 项           | 定档前                                               | 定档后                                                                                                                                                                                       |
| ------------ | ---------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 决策载体     | 无（未定档的技术验证）                               | **ADR-0024**（与 ADR-0002/0017 同型的「生效解释」，技术选型 v1.1 正文不动、不归档、不迁移链接）                                                                                              |
| 表单实现     | TanStack + RHF 两份对照                              | **仅 TanStack**（`rhf/` 与 `radix-select-adapter.tsx` 已移除；适配层提升后剩余 6 个代码文件 + 本报告）                                                                                       |
| 适配层       | spike 内 20 行 `tanstack/form-field.tsx`             | **已提升为 `@ontos/ui/src/form/FormBinding.ts`**（`fieldProps` / `firstError` / `FieldLike`；结构类型零外部依赖，共享包不感知表单库）；spike 改为消费正式组件，vitest 10/10 即适配层行为回归 |
| 运行时依赖   | 4 个（含 RHF 三件套）                                | **1 个**：`@tanstack/react-form@1.33.5`；`react-hook-form` / `@hookform/resolvers` / `@radix-ui/react-slot` 已移除                                                                           |
| 测试依赖     | vitest / jsdom / testing-library 4 个                | 保留在 `apps/web` devDependencies，**仅服务 `apps/web/spikes/`**；正式代码测试仍走 Node Test Runner                                                                                          |
| 供应链白名单 | `pnpm-workspace.yaml` 自动追加 2 条 RHF 相关         | 2 条已清理；`@radix-ui/react-slot` 作为其它 Radix 包的传递依赖仍见于 lock，属正常                                                                                                            |
| 行为用例     | **20/20**（双实现 × 同一组用例）                     | **10/10**（2 文件，单实现回归）；`tsc --noEmit` 全绿                                                                                                                                         |
| 工程约定     | 4 处引用 RHF 口径                                    | `AGENTS.md` §6.1、根 `README.md` L15/L64、`FRONTEND_RULES.md` L9、`tooling.md` L9 已同步为 ADR-0024 口径                                                                                     |
| 工程链       | `pnpm check` 全链 PASS（99 文件）、`pnpm build` PASS | `pnpm check` **EXIT=0**（format 96 文件 / lint 0 warning / architecture 8 / test:unit 26 / design token）、`pnpm build` **EXIT=0**                                                           |

**技术选型 v1.2 全量收口另立独立工作包**：升版会触发 ADR-0002 四条并入 §4.8.2、M0/M1 验收报告登记的流式时序与端点修正、§11 剩余待决项、v1.1 头块「待评审」状态修正，以及 30+ 处引用锚点迁移；不得把表单库修订单独包装成 v1.2。详见 ADR-0024「备选方案」与「后续动作」。

## 一、结论（定档前判断，已由负责人采纳）

**技术上完全可行，工程约束全兼容，建议采用 TanStack Form——但它是「有代价的更优」，不是全面碾压。**

| 判断                   | 结论                                                                                                           |
| ---------------------- | -------------------------------------------------------------------------------------------------------------- |
| 能不能装、能不能跑     | 能。React 18.3.1 / zod 3.25.76 / pnpm 11.5.1 / Vite 6 全兼容，无 peer 冲突                                     |
| 与共享 UI 组件能否集成 | 能。共享 `Field/FieldLabel/FieldError/Input/Button` 直接可用；Radix Select 只需 `value/onValueChange` 一行绑定 |
| 与工程检查能否共存     | 能。`pnpm check` 全链 PASS、`pnpm build` PASS、正式产物零 spike 代码                                           |
| 是否建议采用           | **建议采用**（已采纳）；若团队更看重 shadcn 现成生态与包体积，RHF 也完全够用                                   |

选型真正的分水岭不是「能不能用」，而是**本项目表单画像是动态嵌套的资产编辑器**：
业务对象 → 属性联动、口径变体数组、允许维度多选，且资产 schema 由 zod 定义。TanStack Form 在这三处更贴合；
静态 CRUD 表单两者无差。

## 二、实测证据（定档前快照）

### 环境

node 22.22.2 / pnpm 11.5.1 / React 18.3.1 / zod 3.25.76 / TypeScript 5.9
实测版本：`@tanstack/react-form@1.33.5`、`react-hook-form@7.92.0`、`@hookform/resolvers@7.0.6`、
`@radix-ui/react-slot@1.5.0`、`vitest@3.2.7`、`jsdom@26.1.0`、`@testing-library/react@16.3.0`。
（RHF 三件套已于定档后移除，见 §0。）

### 关键实证 1：zod 3.25 原生支持 Standard Schema

`z.object(...).superRefine(...)` 的 `~standard.validate` **同步返回**带 `path` 的 issues（实测
`{a:'', n:-1}` → 两条 issue，path 分别 `["a"]`/`["n"]`）。这意味着：

- TanStack Form 的 `validators: { onChange: schema }` 可以**直接传 zod schema**，不需要 resolver 适配器；
- 跨字段校验（superRefine）不会触发 TanStack 的 `async function passed to sync validator`；
- form-core `standardSchemaValidator.ts` 的 `prefixSchemaToErrors` 会**自动把 issues 按路径分发到字段 errorMap**
  （`variants[0].field` 形式），不需要手写错误映射。本轮「schema 级校验需手动分发错误」的担心被证伪。

### 关键实证 2：20/20 行为用例通过（定档前双实现）

`vitest run --config <abs>/vitest.config.ts`：2 个测试文件、20 个用例全部通过。
两份实现跑**同一组用例**（`describe.each` 参数化）、用**同一套展示控件**与 data-testid：

初始默认值与 dirty、输入同步、提交空草稿的字段错误、有效草稿 payload、对象→属性联动、
COUNT 对象级计数受控例外、非 COUNT 跨字段拦截、动态嵌套数组增填删、字段级重渲染计数、
提交接 `useMutation` 后 `invalidateQueries`。

定档后仅保留 TanStack 实现，同一组用例收敛为 **10/10 通过**（`Test Files 2 passed / Tests 10 passed`），
渲染计数实测仍为 `name-field 1 -> 2`。

### 关键实证 3：类型与工程检查

- `tsc --noEmit` 全绿。首轮 3 处报错全部来自适配信息而非库缺陷：共享 `Input` Omit 了原生 `size`；
  **TanStack 字段值是精确字面量联合**（`"SUM"|"COUNT"|...`），共用 `SelectField` 签名写成
  `(value: string) => void` 不兼容——把控件泛型化后解决。这是 TanStack 类型安全的直接证据。
- 定档前：`pnpm format:check`（99 文件，含本报告）、`pnpm lint`（0 warning / 96 规则）、`pnpm architecture:check`（8 测试）、
  `pnpm test:unit`（26 测试）、design token 检查、`pnpm typecheck` 全 PASS。文件数随定档收敛后变化，以最终复跑为准。
- `pnpm build` 通过；`dist/` 里唯一疑似命中是 shiki 语法包里的
  `byte-compile-file-form-eval` 字符串，**spike 代码未进正式产物**。

## 三、逐维度对比（定档前对照，历史数据）

| 维度                                         | TanStack Form 1.33.5                                          | RHF 7.92.0 + zodResolver                                            | 判定                                     |
| -------------------------------------------- | ------------------------------------------------------------- | ------------------------------------------------------------------- | ---------------------------------------- |
| 校验接入                                     | zod 直连（Standard Schema）                                   | 需 `@hookform/resolvers/zod`                                        | TanStack 略优：少一个包、少一层适配      |
| 类型推导                                     | 字段值是精确字面量联合；嵌套路径 `variants[0].field` 有类型   | `Path<T>` 也有类型，但 `field.onChange` 是宽签名                    | TanStack 优                              |
| 动态数组                                     | `form.Field mode="array"` + `pushValue/removeValue`           | `useFieldArray` + `append/remove`（需额外 hook 与 `item.id` key）   | 持平，风格不同                           |
| 跨字段联动                                   | `onChange` 内 `setFieldValue`；`form.Subscribe` 精确 selector | `watch` + `setValue`（watch 会触发整表重渲染）                      | TanStack 优                              |
| 字段级订阅                                   | 实测 name 字段：初始渲染 1 次，改 id 后 2 次（+1）            | 初始 3 次，改 id 后 5 次（+2）                                      | TanStack 优，但**优势有限**（见 §4）     |
| 适配层代码量                                 | `form-field.tsx` **20 行** props 映射                         | 定档前 `rhf/form.tsx` **90 行**（shadcn 风格 copy-in 封装，已移除） | TanStack 优（推翻先前估计）              |
| 表单组件代码量                               | `MetricForm.tsx` 185 行                                       | 定档前 `MetricForm.tsx` 208 行（已移除）                            | 基本持平（运行时必需部分 205 vs 208 行） |
| 打包体积（min/gzip，含 zod，react external） | 126,525 B / **31,671 B**                                      | 98,475 B / **28,022 B**                                             | **RHF 优：gzip 少 3.6 KB（约 13%）**     |
| 生态封装                                     | 无官方 shadcn Form 适配                                       | shadcn/ui 官方 Form/FormField/FormControl/FormMessage               | RHF 优                                   |
| 与 TanStack Query 联动                       | 提交接 `useMutation` + invalidate 实测通过                    | 同左，实测通过                                                      | 持平                                     |

体积测量方法：esbuild bundle+minify 两个最小入口（都含 zod，仅 RHF 侧多 resolver），gzipSync 取长度。
这是**增量参考值**，不是最终产物增量；真实 tree-shaking 后差异可能更小。

## 四、修正了上一轮的三个先验判断

1. **「TanStack 需自建约 100 行适配层」——错，实测 20 行。**
   原因是本项目共享 UI 包已有 `Field/FieldLabel/FieldError`（label、错误、aria 结构都在控件里），
   不需要重建 shadcn 那套 context 组件层。真正需要 90 行 copy-in 封装的反而是 RHF 走 shadcn 官方形态。
   注意：定档前 `rhf/form.tsx` 只做类型级可行性验证，未被交互用例使用；RHF 运行时实现是 `Controller` 直连，
   与 TanStack 的 205 vs 208 行基本持平。**代码量不构成选型理由**。
2. **「字段级订阅会带来数量级渲染优势」——夸大，实测 1→2 vs 3→5。**
   form-level schema 校验重新分发 errors 时，TanStack 的兄弟字段也会重渲染一次，不是零。
   在本 spike 的 7 字段规模下是 +1 vs +2 的差别，不构成单独选型依据。
3. **「同族生态、顺带更轻」——体积反了。** TanStack Form 1.33.5 的 form-core 带 store / devtools /
   event client，gzip 比 RHF+resolver 组合多约 3.6 KB。可接受，但必须记在账上。

## 五、原采用前提与定档落地结果

| #   | 原前提                                                 | 落地结果                                                                                                                                                                             |
| --- | ------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 1   | 治理先行：必须走技术选型升 v1.2 或发 ADR，不能装包即用 | **已闭环（ADR 路径）**：新发 ADR-0024 冻结附录 A 表单行生效解释；4 处工程约定同步。选 ADR 而非升 v1.2 的理由见 §0 与 ADR-0024「备选方案」                                            |
| 2   | 依赖收敛：移除 RHF 三件套与 `rhf/` 对照                | **已闭环**：见 §0；测试依赖保留且限定作用域为 `apps/web/spikes/`（ADR-0024 D4）                                                                                                      |
| 3   | 接受体积与资料成本；适配层提升共享 UI 包               | **成本已入账**（ADR-0024 D5）；**适配层已提升**为 `@ontos-ui/src/form/FormBinding.ts`（零依赖结构类型，spike 消费正式组件，10/10 回归通过），Radix Select 浏览器交互验证仍为后续动作 |

## 六、Spike 的边界（未验证项，别当成已证明）

- 交互用例用**原生 select**（jsdom 对 Radix Portal 支持有限）；Radix Select 的真实绑定形态在
  定档前的 `radix-select-adapter.tsx` 只做了类型级验证（该文件已随对照实现移除），**未做浏览器交互**。
- 未做真实浏览器键盘/焦点、响应式、`prefers-reduced-motion` 验证；未做 50+ 字段大表单的性能测试。
- 未验证 SSR / 服务端校验复用（当前架构是纯静态产物，暂无需求）。
- 未与后端草稿 API 联调（Phase B 的 API 尚不存在）。

## 七、改动清单与回滚路径

定档前新增：`apps/web/spikes/form-eval/`（10 个代码文件 + 本报告）。
**定档后现状**：7 个代码文件 + 本报告；`apps/web/package.json` 仅新增 1 个运行时依赖
（`@tanstack/react-form`）与 4 个 spike 专用 dev 依赖；`pnpm-lock.yaml` 中 RHF / resolvers 条目已清除
（`@radix-ui/react-slot` 作为其它 Radix 包的传递依赖保留）；`pnpm-workspace.yaml` 的 2 条 RHF 白名单已清理。

工作区另有**其它会话**的未提交改动：`apps/web/app/router/AppRouter.tsx`、`apps/web/app/prototype/`。
本轮未触碰，提交时请按主题分开。

回退（若 Phase A/B 实装触发 ADR-0024 D5 条件）：按 ADR-0024「回退方案」另发 ADR 取代，
再移除 `@tanstack/react-form` 与适配层、同步回改工程约定与测试，不在代码里直接换库。

## 八、复现命令

```bash
cd ontos-ui
pnpm --filter @ontos/web exec vitest run --config \
  "$(pwd)/apps/web/spikes/form-eval/vitest.config.ts" --reporter=verbose
pnpm check
pnpm build
```

## 九、深度审核留痕

- 证伪自查：dist 命中、`~standard` 同步性、错误自动分发、体积反直觉数据均回查原始证据后再写入；
- 测试真实性：定档前两侧共用同一套用例与控件，失败路径（空提交、跨字段拦截）断言 `onSave` 未调用，
  成功路径断言 payload 全字段；RHF 封装与 Radix 适配明确标注为类型级验证，不冒充行为覆盖；
- 范围：冻结文档 / 规格 / roadmap / 正式页面 / vite 配置零改动；`pnpm check` + `pnpm build` 双通过；
- 定档收敛复核：移除 RHF 对照后 `tsc` 全绿、vitest **10/10**；无残留 `rhf/` / `radix-select-adapter` 代码引用；
- 一次失败已当场修复：定档前写完本报告后 `oxfmt --check` 会格式化 `.md`，全链首轮 FAIL，格式化报告并把文件数
  98→99 校正后复跑 `pnpm check` EXIT=0；本次定档改写后同样需重跑格式化与全链检查。

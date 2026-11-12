# Frontend Tooling

Scope: `ontos-ui` 的工具、构建与验证。迁移自 `yak-ops-ui/docs/tooling.md`，按本仓库实际脚本裁剪。

## Toolchain

> **边界**：本文件与 `FRONTEND_RULES.md` 只约束工程规范。**框架与组件选型以
> `docs/02-solutions/architecture/智能问数系统技术选型方案-v1.1.md` 附录 A 为准**（shadcn/ui + Radix、CopilotKit Headless、
> TanStack Table / ECharts、Zustand、Vite + TypeScript + pnpm）；表单行按
> `docs/01-decisions/ADR-0024-前端表单库选型修订TanStackForm生效解释.md` 的生效解释，
> 采用 **TanStack Form + Zod（Standard Schema 直连）**。
> 组件栈归位已完成：pnpm workspaces + shadcn/ui + Radix（见 `docs/01-decisions/ADR-0001-前端组件栈归位范围.md`）；
> CopilotKit Headless 待 PoC #10 验收后接入。


前端使用 Vite、React、React Router、Tailwind、TanStack Query、TypeScript、Oxlint、Oxfmt 和 pnpm workspaces。Node 要求、依赖与脚本以 [package.json](../package.json)、workspace manifests 和 [pnpm-lock.yaml](../pnpm-lock.yaml) 为准。

- `apps/web`：`@ontos/web`，产品应用；`@/` 指向 `apps/web/*`。
- `packages/ontos-ui`：`@ontos/ui`，与业务无关的通用控件，公共 API 由 `src/index.ts` 定义。
- Vite 负责开发和生产构建；pnpm workspaces 负责依赖安装，CI 使用 `pnpm install --frozen-lockfile`。
- 架构校验入口是 [check-architecture.mjs](../scripts/check-architecture.mjs)，导入边界由 [architecture-imports.mjs](../scripts/architecture-imports.mjs) 检查；设计 token 由 [verify-design-tokens.mjs](../scripts/verify-design-tokens.mjs) 守护。

## Local Verification

在 `ontos-ui/` 下运行：

```bash
pnpm install --frozen-lockfile
pnpm check
pnpm build
```

`pnpm check` 依次验证 Oxfmt 格式、Oxlint 静态规则、TypeScript 类型、Node Test Runner 单元测试、架构边界及静态契约。生产构建由 Vite 独立完成。修改局部交互时另运行相应验证，不把静态检查视为浏览器验收。

单项命令：

```bash
pnpm format:check
pnpm lint
pnpm typecheck
pnpm architecture:check
pnpm contract:check
```

## Environment Notes

用户级 `~/.npmrc` 若写死本机未启动的代理地址，`pnpm install --frozen-lockfile` 会静默卡住。可用
`NPM_CONFIG_USERCONFIG=/dev/null pnpm install --registry=https://registry.npmmirror.com` 绕过该文件。

pnpm 10+ 默认不执行依赖的安装脚本，`vite` 依赖的 `esbuild` 需要原生二进制，放行清单写在
[pnpm-workspace.yaml](../pnpm-workspace.yaml) 的 `allowBuilds`；被重置时安装结束会报
`ERR_PNPM_IGNORED_BUILDS`，并在该文件留下待填写项。

## Runtime and Interaction Verification

Frontend tests are split into static/TypeScript contracts (`pnpm check`) and Playwright. Static checks run on ordinary changes; browser scenarios are release-scoped and follow [Playwright UI Tests](../tests/e2e/README.md).

Browser scenarios and conventions: [Playwright UI Tests](../tests/e2e/README.md). Do not add a browser test for every feature change.

Static scripts validate source contracts but do not prove actual DOM/focus/animation behavior. Browser results must record the test suite, version, exact commit SHA, relevant traces, and pass/fail state; a skipped suite is never a pass.

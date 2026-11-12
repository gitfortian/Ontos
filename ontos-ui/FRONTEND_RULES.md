# Frontend Rules

Scope: `ontos-ui/apps/**`, `ontos-ui/packages/**`, and `ontos-ui/scripts/**`.

> 本文件迁移自 `yak-ops-ui/FRONTEND_RULES.md`，**只作为前端工程规范**：目录分层、
> `service/http` 三层、状态与请求原则、命名、Lint / 格式化、架构边界与测试方式。
> **UI 组件框架与依赖选型一律以 `docs/02-solutions/architecture/智能问数系统技术选型方案-v1.1.md` 附录 A 为准**
> （附录 A 的「表单 / 状态」行以 `docs/01-decisions/ADR-0024-前端表单库选型修订TanStackForm生效解释.md` 为生效解释）：
> shadcn/ui + Tailwind v4 + Radix、CopilotKit Headless（AG-UI 协议）、TanStack Table / ECharts / Monaco、
> **TanStack Form + Zod（Standard Schema 直连）**、TanStack Query、Zustand、Vite + TypeScript + pnpm，
> 纯静态产物由 Spring Boot 单体托管。
> **不得把来源项目的组件库（基于 `@base-ui/react`）或其包名当作本项目的组件选型。**
> 以下条款中的「shared UI package」指按附录 A 实现的共享组件层；Workspace 身份映射为 Ontos 租户身份
> （`X-Ontos-Tenant-Id`）；语义 token 由选定设计系统定义，禁止硬编码颜色。

This document defines durable frontend engineering constraints. Current source code, TypeScript types, tests, and backend APIs are the authority for observable behavior. See [Frontend Tooling](docs/tooling.md) for verification commands.

## Ownership and Dependencies

- Use the current feature contract directly. Do not keep compatibility re-exports, obsolete editor state, alias props, duplicate mappers or fallback UI paths without a supported consumer and explicit removal plan. Reject incompatible stored data instead of silently converting it.
- `apps/web` owns the product application. The shared UI workspace package owns domain-agnostic controls; its `src/index.ts` defines their public API. Controls are implemented with the selected stack (Appendix A), never copied from the reference project.
- `apps/web/app/<domain>` owns domain pages and interaction state. `app/layout` owns the shell; `app/router` owns route composition and historical URL redirects.
- Product requests flow from `app/<domain>` to `service/<domain>` to `service/http`. The HTTP transport is centralized, including consistent response and authentication error handling. Domain services own endpoint adaptation and request/response types and must never depend on App code.
- UI dependencies flow from App to the shared UI package, then to Radix primitives/DOM. The shared UI package must not contain product routes, tenant policy, business copy, or domain data access.
- Organize components, hooks, local types, and tests by real user workflow. Promote cross-domain code only after actual stable reuse. Do not create speculative `common` or `shared` layers.
- Tenant-scoped queries must isolate tenant and user identity. Pages must not invent identity headers; transport applies the real API contract.
- Services expose only capabilities with real consumers. Components must not parse HTTP envelope details or turn API failures into successful empty results.

## Feature Ownership and Components

- Feature directories own their behavior. Route entrypoints should connect routing to the owning feature, rather than forwarding exports through one-line compatibility files.
- Route composition may import a feature page entrypoint. Other domains may not deep-import another feature implementation; do not create permanent forwarding modules simply to preserve an obsolete internal import path.
- Router can import `AppLayout` and `navigation` but not private App Shell modules. Preserve supported historical redirects in the router, not in feature page forwarding files. Deleted compatibility entrypoints must not return.
- The smallest behavior owner handles its loading, empty/error states, actions, and lifecycle. Parent components coordinate only genuinely shared selection, navigation, or submission state.
- Split components based on behavior and rendering boundaries, not merely line count. Review responsibility when a file exceeds 400 lines; justify files above 600 lines during review. These are review signals, not automatic failure thresholds.
- Never replace a huge page with an equally huge `usePageModel` or `useEditor` hook. Hooks must own an identifiable query, subscription, or interaction. Avoid pass-through hooks.
- Product pages use the selected shared component layer (shadcn/ui + Radix) rather than replicating base control behavior. Pass meaningful domain types and identities through props; do not store values that are derivable from existing state.
- Keep loading, empty, and error presentation near its data consumer. Skeletons should preserve final layout rather than introducing an unrelated full-page loading state.

## State, Queries, and Effects

- Local interactions such as dialog visibility, drafts, input, and selection belong to the relevant lifecycle owner. Shareable filters, pagination, tabs, and instance selection belong in the URL, with no parallel independent identity state.
- Server data belongs in the shared TanStack Query cache, not a page-owned `useState + useEffect` cache. Query keys must include every relevant parameter and tenant/user scope.
- Keep API and version adaptation in services. Query and mutation ownership should be near consumers; invalidate relevant queries after mutations rather than creating duplicate refresh counters.
- Use a shared store only when sibling components need a common authority or state must survive a workflow unmount. Do not introduce a global store for component nesting alone.
- `useEffect` is for external synchronization, subscriptions, timers, and DOM integration. Do not use it to derive render state, handle submissions, or mirror query results. Polling must stop correctly after a state change, unmount, or visibility transition.
- Forms own defaults, validation, dirty state, and payload creation. A single submission flow coordinates validation, submission, and partial failure recovery.
- Never disguise failed or unknown requests as success or empty data. Request cancellation, stale responses, retry behavior, and final feedback must have clear owners. Keep existing semantics until a migration is proven equivalent.

## Tailwind and CSS Boundaries

- Use Tailwind CSS v4 for ordinary business layout and interactions. Prefer the selected design system semantic tokens and state selectors; avoid repeated fixed colors such as `text-[#...]` or `bg-[#...]`.
- Prefer standard utility classes and semantic `data-*` attributes. Keep feature-scoped CSS only when it is appropriate for dynamic geometry, specialized keyframes, third-party DOM overrides, or log syntax coloring. Migrate existing CSS deliberately, not with blind deletion.
- Feature CSS must not override generic shared controls or depend on private styles owned by another domain. Support light/dark themes and `prefers-reduced-motion`; derive theme variables from existing tokens.
- Do not move a hardcoded color from `className` to inline `style` to bypass checks. Inline styles are for genuinely runtime-dependent values. Add reusable visual capabilities in the shared UI package.
- The architecture check enforces semantic colors across product code.

## Testing and Architecture Enforcement

- Keep pure functions and transformations under focused unit tests. Test component input, async states, focus, and error behavior at the component boundary; use existing Playwright flows for cross-page behavior.
- `scripts/check-architecture.mjs` enforces real dependency direction: services cannot import App code, pages cannot bypass domain services for HTTP, and the shared UI package cannot import product code. Validate aliases, relative imports, re-exports, and dynamic imports.
- Route entrypoints can be imported by `app/router`. Private feature implementation paths cannot be deep-imported from other domains. Share stable behavior through a deliberately owned module, not by opening arbitrary private imports.
- New architecture checks require positive and negative regression tests. Track genuine historical dependencies explicitly; do not silence failures through permissive ignore lists, deleting tests, or fabricated passing results.
- Report formatting, lint, typing, contracts, builds, and actual browser validation separately. A TypeScript build is not a substitute for interaction testing.

## Design System and Interaction

- Use the selected shared component layer for PageHeader, Table, Form/Field, Input, Select, Modal/Dialog, Alert, and Toast. Tailwind and semantic tokens own product styling; source and types define shared component behavior.
- Default management forms use a compact horizontal label/control layout with a 104px label column and small outlined controls. Domains own field dependencies and submission; use shared Field primitives for accessibility and validation.
- Select options use real domain values and labels. Consumers own search, refresh, and asynchronous candidates; shared controls own keyboard interaction and popup lifetime.
- Table owns generic selection, sorting, pagination, and sticky columns. Pages own queries, filters, row actions, and server-side pagination; do not duplicate Table internals.
- Modal/Dialog/Drawer use shared focus, Escape, closing, and scrolling behavior. Form dialogs normally require an explicit close action; destructive operations require confirmation. Domains own open and submission state.
- Use Alert for persistent risks or critical prerequisites, Toast for transient action results, and FieldError for field validation.
- Preserve keyboard navigation, focus visibility, accessible labels, and reduced-motion behavior. Animations must clean up promptly on interruption, hiding, and unmount.
- Shared component props and timing behavior are defined by their implementation and types, not by duplicated documentation. Extend controls only in response to verified needs.

## UI Copy Minimalism

Do not add persistent help, tutorial, explanatory, or repetitive product copy by default. Prefer labels, control types, placeholders, disabled/loading/empty states, field errors, and actual domain statuses.

Additional copy is justified only when missing information risks data loss or an irreversible action, a critical prerequisite would otherwise be unclear, or an operation is blocked for a reason not expressible by the control and its field error.

Place necessary copy near the relevant action and keep it short. Do not explain the same thing repeatedly through Label, Description, Helper Text, and Alert. Keep backend implementation details out of ordinary product forms. When the interaction itself conveys the meaning, add no copy.

## Quality and Verification

- Retain Oxfmt, Oxlint, TypeScript, architecture checks, and production builds. Tests must claim only the behavior they actually cover.
- Maintain real dependency, directory, HTTP, and tenant boundaries. Fix incorrect implementation rather than loosening checks or adding broad lint disables.
- Run focused formatting, types, builds, and executable checks based on changed scope. Record actual browser, keyboard, responsive, and reduced-motion verification when relevant, distinguishing passed, skipped, and not-run checks.

## Documentation and Proposals

- Express routine page work, styling changes, component fixes, and bug fixes through code, tests, and focused pull requests. Do not create progress-only domain `*_RULES.md` files or duplicate architectural descriptions.
- Update user documentation when externally observable behavior, compatibility, or deployment requirements change. Engineering commands belong in [Frontend Tooling](docs/tooling.md).
- Write rules and comments in the present tense, describing current responsibilities and invariants. Keep rationale and history in PR discussions. Accurately document published compatibility and safety restrictions.

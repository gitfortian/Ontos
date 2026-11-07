# PoC-01 · RLS 最终边界报告

> **状态**：已冻结
> **版本**：v1
> **类型**：验证
> **负责人**：项目组（待负责人签字）
> **最后更新**：2026-11-06
> **适用范围**：M0 / W2 / T2.1，硬阻断闸门 G1 的证据之一
> **上游依据**：[技术选型 v1.1 §7 #1](../../02-solutions/architecture/智能问数系统技术选型方案-v1.1.md)、[M0 实施规格](../../03-plans/stages/M0/M0-工程基线与阶段零硬判据-实施规格-v1.md)
> **修订记录**：2026-11-06 v1 首次执行并归档

判据与阈值引用技术选型 §7 #1，本报告不复制数值、只记录实测与覆盖范围。

## 1. 验证对象与版本

| 项 | 值 |
|---|---|
| 数据库 | 本地 PostgreSQL **18.6-5**（`D:\pg`），服务 `postgresql-x64-18` |
| System DB / Query DS | `ontos` / `ontos_queryds`（本机 127.0.0.1:5432） |
| 连接角色 | `ontos_query_ro`（非 superuser、非 owner） |
| JDBC 驱动 | `postgresql-42.7.14.jar` |
| 探针 | `scripts/poc/Poc01Rls.java`（应用同款 JDBC 路径） |
| 原始输出 | `PoC-01-raw.json` |
| 数据快照 | `public.sales_order` 3 行（demo 2 / other 1） |

## 2. 方法与环境

- 在 Query DS 执行仓库内 `db/queryds/V1__rls_demo.sql`：建表、`ENABLE` + `FORCE ROW LEVEL SECURITY`、租户隔离 policy、只读授权、演示数据。
- 探针以 `ontos_query_ro` 连接，事务内用应用同款 `set_config('ontos.tenant_id', ?, true)` 注入上下文，覆盖五组用例、每组重复 100 次，共 500 次检查。
- 五组：跨租户（demo 只能见 2 行）、跨域（other 只能见 1 行）、未授权租户存在性探测（0 行）、未设 GUC（0 行）、同一物理连接反复切换租户（不串号，模拟事务池连接复用）。
- 补充应用链路验证：启动 `ontos-server` 连真实库，经 `/api/v1/query/execute` 执行 demo 清单编译出的 SQL，确认只返回 demo 租户 2 行、other 的 9999 未出现。

## 3. 结果

| 判据 | 实测 | 通过 |
|---|---|---|
| 五组用例各 100 次，越权成功 0 | `violations = 0` / 500 次检查 | 是 |
| `FORCE RLS` 生效 | `relforcerowsecurity = true` | 是 |
| 未设 GUC 时无数据可见 | `baselineRowsWithoutGuc = 0` | 是 |
| 事务级 GUC 随连接复用不串号 | demo→other→demo 交替 100 轮，计数恒为 2/1/2 | 是 |
| 应用链路只读执行不越权 | `/query/execute` 返回 2 行，均为 demo 租户 | 是 |
| RLS 谓词命中索引 | 3 行小表走顺序扫描，**未验证索引命中** | 未运行 |
| 策略版本进入审计 | 产品能力属 M4，当前未实现 | 未运行 |
| pgbouncer `transaction` 池 / `server_reset_query` | 本地无 pgbouncer，以同一物理连接复用模拟 | 部分覆盖 |
| `SET LOCAL` / `security_barrier` 行为复核 | 仅验证 `set_config(..., true)` 事务级语义 | 部分覆盖 |

> 报告区分「通过 / 跳过 / 未运行」：**上表三项未运行与两项部分覆盖不算通过**，需在 M4 与具备连接池的环境补验并另发报告版本。

## 4. 失败案例与归因

无失败案例。执行期间发现并修复一个独立的工程缺陷（见 §5 附注），不影响本 PoC 结论。

## 5. 结论

**PASS（限定范围）**：核心隔离判据全部通过，越权成功 0；应用链路上 RLS 同样生效。
允许 M0 在「不进入真实敏感数据」的前提下继续 W2 后续与 W3；**未运行项在 M4 / 连接池环境补齐前，不视为 G1 完全关闭**。

> 附注（工程缺陷，已修复）：首次连真实库启动发现 System DB 无任何表，原因是 Spring Boot 4 把 Flyway 自动配置拆到
> `spring-boot-flyway` 模块，仅有 `flyway-core` 不会触发迁移。已在 `ontos-server/pom.xml` 增加
> `spring-boot-starter-flyway`，重启后 Flyway 成功应用 `V1__system_schema.sql`（8 张表 + `flyway_schema_history`）。
> 该缺陷若不修复会导致生产部署静默缺表，属 M0 基线自检应覆盖项。

## 6. 证据路径

- `scripts/poc/Poc01Rls.java` — 探针源码
- `PoC-01-raw.json` — 原始输出
- `ontos/ontos-server/src/main/resources/db/queryds/V1__rls_demo.sql` — RLS 脚本
- `ontos/ontos-server/src/main/resources/db/seed/demo_manifest.sql` — 演示清单
- 应用启动日志关键行（Flyway、health UP、execute 响应）记录于当次进度快照

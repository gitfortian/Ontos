# PoC-02 · 发布快照可复现报告

> **状态**：已冻结
> **版本**：v1
> **类型**：验证
> **负责人**：项目组（待负责人签字）
> **最后更新**：2026-11-07
> **适用范围**：M0 / W2 / T2.2，硬阻断闸门 G1 的证据之一
> **上游依据**：[技术选型 v1.1 §7 #2](../../02-solutions/architecture/智能问数系统技术选型方案-v1.1.md)、[M0 实施规格](../../03-plans/stages/M0/M0-工程基线与阶段零硬判据-实施规格-v1.md)
> **修订记录**：2026-11-07 v1 首次执行并归档

判据与阈值引用技术选型 §7 #2，本报告不复制数值、只记录实测与覆盖范围。

## 1. 验证对象与版本

| 项 | 值 |
|---|---|
| 执行级别 | **系统级**（真实 jar + HTTP 接口 + System DB 清单加载），非单元模拟 |
| 应用 | `ontos-server-0.1.0-SNAPSHOT.jar`，profile `local`，端口 18080 |
| 运行时 | `java version "21.0.12" 2026-07-21 LTS`，Windows |
| 数据库 | 本地 PostgreSQL **18.6-5**（`D:\pg`），System DB `ontos` |
| 发布清单 | `manifest_id = 2026.11.06-demo`，`content_hash = 25c52f9a…dc078`（每次启动由 `ManifestService` 从 `published_snapshot` 重新加载并校验 SHA-256） |
| 编译入口 | `POST /api/v1/query/compile`（`QueryService.compile` → `DslValidator` → `PolicyService` → `SqlCompiler`） |
| 探针 | `scripts/poc/run-poc02.mjs` |
| 原始输出 | `PoC-02-raw.json` |

## 2. 方法与环境

- **跨重启是真重启**：探针自行拉起 jar、等待 `actuator/health` 为 `UP`、执行后终止进程并等待端口释放，再拉起下一个全新 JVM。三次运行的 PID 分别为 `26736` / `10952` / `71992`，是三个独立进程与三套全新 Spring 上下文，不是同一进程内 new 一个对象。
- **固定输入**：同一份 DSL（两个维度 `dim.region`/`dim.channel`、`dim.channel IN (...)` 过滤、`dim.order_date` 时间窗、`limit=50`），覆盖 SQL 结构、`IN` 多值绑定、时间戳类型转换与 limit 绑定四条路径。
- **比对口径**：只比对编译产物 `{manifestId, contentHash, metricId, dimensions, dialect, sql, bindValues, appliedLimit}`；对产物做稳定序列化后取 SHA-256 作为摘要。`X-Request-Id` 每请求随机、`CompiledQuery.compiledAt` 为编译时刻，二者**不属于产物**，也不参与比对（`QueryCompileRespVO` 不对外暴露 `compiledAt`）。
- **每进程 100 次**，三进程共 300 次编译；同时校验进程内 100 次摘要唯一性与跨进程摘要一致性。

实测产物（三进程完全一致）：

```sql
select sum("amount") as "value", "region" as "dim.region", "channel" as "dim.channel"
from "sales_order"
where ("channel" in (?, ?) and "order_date" >= cast(? as timestamp) and "order_date" <= cast(? as timestamp))
group by "region", "channel"
fetch next ? rows only
```

绑定值：`["线上", "线下", "2026-01-01T00:00:00.000Z", "2026-12-31T23:59:59.000Z", 50]`

## 3. 结果

| 判据 | 实测 | 通过 |
|---|---|---|
| 同一 DSL + 同一 `manifest_id/content_hash` 重复 100 次，产物逐字一致 | 每进程内 `distinctDigests = 1` | 是 |
| 跨 **2 次应用重启**逐字一致 | 3 个独立 JVM 摘要种类 = 1（`55baead6e6660c24…`） | 是 |
| 逐字一致率 | **100.00%（300 / 300）** | 是 |
| 版本锚点随清单而非随编译时刻变化 | `contentHash` 三进程一致，`compiledAt` 未进入产物 | 是 |
| 参数零字符串拼接 | SQL 全为 `?` 占位，绑定值单列返回 | 是 |
| 多进程并发下清单加载一致性 | 三次启动均成功加载并校验同一 `content_hash` | 是 |

## 4. 失败案例与归因

无失败案例。

执行中发现一处**口径差异**（已消解，不影响结论）：`CompiledQuery` 含 `compiledAt` 字段，字面比对会因时间戳不同而恒不通过。该字段是编译时刻记录，不是编译产物；`/query/compile` 的响应 VO 也不包含它。本报告按「产物」口径比对，与判据「规范化 SQL 与参数逐字一致」一致。

## 5. 结论

**PASS（系统级）**：同一 DSL 与同一发布清单在跨 2 次应用重启后，参数化 SQL 与全部绑定值逐字一致率 100%，`content_hash` 锚点稳定。

本项为 G1 的系统级证据，可直接关闭；M0 后续任务不再重复验证。

## 6. 证据路径

- `scripts/poc/run-poc02.mjs` — 探针源码（自管进程生命周期，含重启编排）
- `PoC-02-raw.json` — 三次运行的完整产物、PID、启动时刻与摘要
- `ontos/ontos-module-query/src/main/java/com/ontos/module/query/core/compiler/SqlCompiler.java` — 被验证的编译器
- `ontos/ontos-module-ontology/src/main/java/com/ontos/module/ontology/service/manifest/ManifestServiceImpl.java` — 清单加载与 `content_hash` 校验
- `ontos/ontos-server/src/main/resources/db/seed/demo_manifest.sql` — 本次使用的发布清单

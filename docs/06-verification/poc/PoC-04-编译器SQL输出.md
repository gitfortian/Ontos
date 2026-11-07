# PoC-04 · 编译器 SQL 输出

> **状态**：草稿
> **版本**：v1
> **类型**：验证
> **负责人**：项目组
> **最后更新**：2026-11-07
> **适用范围**：M0 / W3 / T3.3，PoC #4 的最小探针与 Golden 集
> **上游依据**：[技术选型 v1.1 §7 #4](../../02-solutions/architecture/智能问数系统技术选型方案-v1.1.md)、[M0 实施规格 §6](../../03-plans/stages/M0/M0-工程基线与阶段零硬判据-实施规格-v1.md)
> **修订记录**：2026-11-07 v1 首次执行，Golden 集 v1 生成并冻结

判据引用技术选型 §7 #4，本文不复制阈值。

## 1. 判据拆解与执行状态

| 判据项 | 状态 | 证据 |
|---|---|---|
| 覆盖 `query` / `lookup` / `lookup_batch` / `funnel` / `yoy_mom` 的 ≥30 条 Golden SQL | **部分**：30 条用例已定义，但只有 `query` 能编译（1/5 模式） | §3 |
| 逐字比对 100% | **通过**：20 条可编译用例逐字一致率 100% | §4 |
| 参数零字符串拼接 | **通过**：全部值以 `?` 占位，绑定值单独返回 | §3.2 |
| dry-run 全通过 | **通过**：20 条在真实 PG 18 上 `EXPLAIN` 全部成功 | §5 |

**`#4` 整体部分通过，但存在一个高危缺陷（§3.3）。**

## 2. 执行方式

| 项 | 值 |
|---|---|
| 编译器 | `SqlCompiler`（jOOQ 3.21.9，`ParamType.INDEXED`） |
| Golden 集 | [`编译器Golden集-v1.json`](../evaluation/编译器Golden集-v1.json) |
| 探针 | `scripts/poc/CompilerGolden.java`（`--generate` 生成 / `--verify` 校验） |
| 规模 | 30 条用例：QUERY 20 条、LOOKUP 4、LOOKUP_BATCH 3、FUNNEL 2、YOY_MOM 1 |
| dry-run | PostgreSQL 18.6，`EXPLAIN` 语法校验 |

## 3. 覆盖度与高危缺陷

### 3.1 只有 QUERY 一种模式被实现

`QueryDsl.AnalysisMode` 枚举声明了 8 种模式，但 `SqlCompiler.compile()` **完全不读取 `mode`**，
一律按「单表聚合 + 维度 + 过滤 + 时间窗 + limit」生成 SQL。

### 3.2 参数化正确

20 条 QUERY SQL 全部满足：表名/列名由语义清单解析并加引号限定到 schema，所有值走占位符，绑定值单独返回。

```sql
select sum("public"."fact_order"."amount") as "value",
       "public"."fact_order"."region" as "region"
from "public"."fact_order"
where "public"."fact_order"."region" in (?, ?)
group by "public"."fact_order"."region"
fetch next ? rows only
-- binds: ["华东","华南","100"]
```

### 3.3 非 QUERY 模式静默生成错误 SQL（高危）

10 条非 QUERY 用例**全部编译成功**，没有任何报错，生成的是聚合查询：

| 模式 | 用例数 | 实际行为 |
|---|---|---|
| `LOOKUP` | 4 | 生成聚合 SQL（语义应为「取单个实体属性值」） |
| `LOOKUP_BATCH` | 3 | 生成聚合 SQL（语义应为「批量取多实体属性」） |
| `FUNNEL` | 2 | 生成聚合 SQL（语义应为「漏斗分步计数」） |
| `YOY_MOM` | 1 | 生成聚合 SQL（语义应为「同比/环比」） |

为什么这是高危而不是「未实现」：

1. **不报错**。`DslValidator` 只校验 `metric.allowedModes` 是否包含该 mode；若清单允许 `LOOKUP`，校验放行，
   编译器也照常产出 SQL，于是**执行层会拿到一条语义错误的查询并返回一个看起来正常的数字**。
2. **错得很像对的**。聚合 SQL 在多数场景能跑通、返回数值，用户无法从结果判断这是「查单个客户」还是「求和」。
3. **审计无法区分**。`pattern_tag` 与 `reason_code` 都正常，事后追溯才发现模式没生效。

**建议**：在 `SqlCompiler.compile()` 入口对未实现的 mode 直接 `throw exception(DSL_INVALID, "分析模式未实现: " + mode)`，
让 fail-closed 覆盖到编译层。这是 M1 实现前必须加的保护，否则任何上游 bug 都会变成静默的错数。

## 4. 逐字可复现

`--verify` 重跑同一批用例：**20 条 compiled 全部逐字一致（SQL + 绑定值），10 条 unimplemented 状态一致，失败 0 项**。

Golden 集的生成方式需要说明：它由**当前编译器**生成，再经人工审阅后冻结。这意味着它锚定的是
「当前行为」而非「正确行为」——所以 §3.3 的问题被显式标为 `SILENT_WRONG_MODE` 记录在案，
而不是当成 Golden 的期望值冻结下来。M2 补齐模式实现后必须重新生成并升版本。

## 5. dry-run

20 条 QUERY SQL 在 PostgreSQL 18.6 上逐条 `EXPLAIN`：**0 个语法错误**。

其中一条值得记录：无维度查询生成的是 `group by ()`（PostgreSQL 的 grouping sets 空集写法），
语义等价于无 GROUP BY 的单行聚合，PG 接受。这不是缺陷，但会让人误以为编译器出了 bug，
故在此显式说明。

## 6. 结论

| 项 | 结论 |
|---|---|
| Golden 集定义（5 模式 / 30 条） | 完成 |
| 覆盖率 | **1/5 模式**（仅 QUERY） |
| 逐字比对 | 通过（20/20） |
| 参数化 | 通过 |
| dry-run | 通过（20/20 EXPLAIN） |
| 非 QUERY 模式 | **静默错误，高危** |
| `#4` 整体 | **部分通过**；M2 补齐模式实现前不得对外声称支持 lookup / funnel / yoy_mom |

## 7. 证据路径

- [`编译器Golden集-v1.json`](../evaluation/编译器Golden集-v1.json) — 30 条用例与冻结的期望值
- `scripts/poc/CompilerGolden.java` — 生成与校验探针（`--generate` / `--verify`）

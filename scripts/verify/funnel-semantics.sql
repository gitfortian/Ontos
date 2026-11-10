-- funnel 步进 CTE 语义验证（M2/T3.3a，AGENTS.md §6.2 深度审核证据）
--
-- 用途：在真实 PostgreSQL 上执行「编译器实际产物」的等价 SQL（仅把 ? 换成字面量），
-- 验证单测无法覆盖的：cohort 窗口、步窗、严格有序、维度首触点取值、转化率分母为上一步。
--
-- 执行：
--   PGPASSWORD=*** psql -U postgres -d ontos_queryds -v ON_ERROR_STOP=1 -f scripts/verify/funnel-semantics.sql
--
-- 造数全部在事务内并 ROLLBACK，不污染 Query DS。
-- 现有数据：c-1001 gold（ORDER_PLACED 10-05，窗口外）、c-1002 normal（ORDER_PLACED 10-12）、
--           c-1003 silver、c-9001 normal。
-- 期望（cohort 窗口 2026-10-09 08:00 ~ 2026-11-08 08:00，step_window=7d）：
--   normal: step_1=2（c-1002、c-9001），step_2=0，conversion_2=0
--   silver: step_1=1（c-1003），step_2=1，conversion_2=1
--   gold  : 无 cohort 行（首步在窗口外）
-- 其中 c-9001 的 REFUND 距首步 16 天，超步窗不计转化；c-1003 的 REFUND 距首步 3 天计入。
--
-- 【更正 2026-11-09 · M6/T1.2 数据基线变更】（保留上方历史期望，不回改）
-- queryds V3 为留存/归因新增复购序列后，c-1001 在 2026-10-26 增加一笔 ORDER_PLACED，
-- 落在同一 cohort 窗口内，因此 gold 不再「无 cohort 行」。本脚本 SQL 未变，真库复跑实测：
--   gold  : step_1=1, step_2=0, conversion_2=0   ← 新基线（原为无行）
--   normal: step_1=2, step_2=0, conversion_2=0   ← 与原期望一致
--   silver: step_1=1, step_2=1, conversion_2=1   ← 与原期望一致
-- V3 的下单事件 ID 已改 e-m6-* 前缀，避免与本脚本事务内 fixture e-10..e-13 主键冲突
-- （此前真库执行报 duplicate key，已修复并复验）。原结论其余语义（步窗、分母、首触点）不变。

BEGIN;
SET LOCAL TimeZone = 'Asia/Shanghai';

INSERT INTO event(id, tenant_id, customer_id, order_id, event_type, occurred_at, dedup_key)
VALUES
    ('e-10', 'demo', 'c-1003', 'o-10', 'ORDER_PLACED', '2026-10-15 10:00:00+08', 'o-10:ORDER_PLACED'),
    ('e-11', 'demo', 'c-1003', 'o-11', 'REFUND',       '2026-10-18 10:00:00+08', 'o-11:REFUND'),
    ('e-12', 'demo', 'c-9001', 'o-12', 'ORDER_PLACED', '2026-10-20 10:00:00+08', 'o-12:ORDER_PLACED'),
    ('e-13', 'demo', 'c-9001', 'o-13', 'REFUND',       '2026-11-05 10:00:00+08', 'o-13:REFUND');

WITH "cohort" AS (
    SELECT "event"."customer_id" AS "subject",
           min("event"."occurred_at") AS "first_time",
           "customer"."tier" AS "customer_tier"
    FROM "event"
    JOIN "customer" ON "customer"."id" = "event"."customer_id"
    WHERE ("event"."event_type" = 'ORDER_PLACED'
       AND "event"."occurred_at" >= cast(timestamp '2026-10-09 08:00:00' AS timestamp)
       AND "event"."occurred_at" <= cast(timestamp '2026-11-08 08:00:00' AS timestamp))
    GROUP BY "event"."customer_id", "customer"."tier"
),
"step_2" AS (
    SELECT "event"."customer_id" AS "subject",
           min("event"."occurred_at") AS "step_time"
    FROM "event"
    JOIN "cohort" ON "cohort"."subject" = "event"."customer_id"
    WHERE ("event"."event_type" = 'REFUND'
       AND "event"."occurred_at" > "cohort"."first_time"
       AND "event"."occurred_at" <= cast("cohort"."first_time" AS timestamp) + cast('7 days' AS interval))
    GROUP BY "event"."customer_id"
)
SELECT "q"."customer_tier" AS "customer_tier",
       "q"."step_1" AS "step_1",
       "q"."step_2" AS "step_2",
       cast("q"."step_2" AS numeric) / nullif("q"."step_1", 0) AS "conversion_2"
FROM (
    SELECT "cohort"."customer_tier" AS "customer_tier",
           count(DISTINCT "cohort"."subject") AS "step_1",
           count(DISTINCT "step_2"."subject") AS "step_2"
    FROM "cohort"
    LEFT OUTER JOIN "step_2" ON "step_2"."subject" = "cohort"."subject"
    GROUP BY "cohort"."customer_tier"
) AS "q"
ORDER BY "q"."customer_tier"
FETCH NEXT 100 ROWS ONLY;

ROLLBACK;

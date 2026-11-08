-- Rule 求值语义验证（M2/T3.4，AGENTS.md §6.2 深度审核证据）
--
-- 用途：在真实 PostgreSQL 上执行「编译器实际产物」的等价 SQL（仅把 ? 换成字面量），
-- 验证单测/API 都覆盖不到的 true 分支（真实数据下所有客户累计消费均 < 50000，全为 false）。
--
-- 执行：
--   PGPASSWORD=*** psql -U postgres -d ontos_queryds -v ON_ERROR_STOP=1 -f scripts/verify/rule-semantics.sql
--
-- 造数在事务内并 ROLLBACK，不污染 Query DS。
-- 期望：c-1001 = false（累计 8099），c-1003 = true（插入一笔 60000 后超过阈值）。

BEGIN;
SET LOCAL TimeZone = 'Asia/Shanghai';

INSERT INTO orders(id, tenant_id, customer_id, order_date, amount, status)
VALUES ('o-rule-verify-1', 'demo', 'c-1003', '2026-10-01 10:00:00+08', 60000, 'PAID');

SELECT "customer"."id" AS "customer_id",
       "customer"."id" IN (
           SELECT "orders"."customer_id"
           FROM "orders"
           GROUP BY "orders"."customer_id"
           HAVING sum("orders"."amount") >= 50000
       ) AS "metric.high_value_customer"
FROM "customer"
WHERE "customer"."id" IN ('c-1001', 'c-1003')
ORDER BY "customer"."id";

ROLLBACK;

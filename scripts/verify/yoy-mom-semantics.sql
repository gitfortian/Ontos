-- yoy_mom 语义验证（M2/T3.3b，AGENTS.md §6.2 深度审核证据）
--
-- 用途：在真实 PostgreSQL 上执行「编译器实际产物」的等价 SQL，验证单测无法覆盖的
-- 语义：WHERE 与窗口函数执行顺序、缺失周期补行、lag 不错位、分母为零输出 null。
--
-- 执行：
--   PGPASSWORD=*** psql -U postgres -d ontos_queryds -v ON_ERROR_STOP=1 -f scripts/verify/yoy-mom-semantics.sql
--
-- 数据全部在事务内插入并 ROLLBACK，不污染 Query DS。
-- 期望结果（2026-01..2026-10，本期从 2026-01 起，同比回看 12 个月）：
--   2026-01  value=100  mom=null  yoy=1.0   ← 2025-01=50
--   2026-02  value=200  mom=1.0   yoy=null
--   2026-03  value=null mom=null  yoy=null  ← 缺失周期补行
--   2026-04  value=400  mom=null  yoy=null  ← 前一期缺失，lag 不得错位到 2 月
--   2026-05..2026-10 value=null mom=null yoy=null

BEGIN;
SET LOCAL TimeZone = 'Asia/Shanghai';

INSERT INTO fact_order(amount, region, channel, order_date, customer_id) VALUES
    (100, '华东', '线上', '2026-01-15 10:00:00+08', 'c1'),
    (200, '华东', '线上', '2026-02-15 10:00:00+08', 'c1'),
    (400, '华东', '线上', '2026-04-15 10:00:00+08', 'c1'),
    (50,  '华东', '线上', '2025-01-15 10:00:00+08', 'c1');

SELECT w.period, w.metric_value, w.mom, w.yoy
FROM (
    SELECT g.period,
           a.metric_value,
           (a.metric_value - lag(a.metric_value, 1) OVER (ORDER BY g.period))
               / nullif(lag(a.metric_value, 1) OVER (ORDER BY g.period), 0) AS mom,
           (a.metric_value - lag(a.metric_value, 12) OVER (ORDER BY g.period))
               / nullif(lag(a.metric_value, 12) OVER (ORDER BY g.period), 0) AS yoy
    FROM (
        SELECT p.period
        FROM (
            SELECT generate_series(
                       date_trunc('month', cast(timestamp '2025-01-01 08:00:00' AS timestamp)),
                       date_trunc('month', cast(timestamp '2026-11-01 07:59:59' AS timestamp))
                           - cast('1 month' AS interval),
                       cast('1 month' AS interval)) AS period
        ) AS p
    ) AS g
    LEFT OUTER JOIN (
        SELECT date_trunc('month', fact_order.order_date) AS period,
               sum(fact_order.amount) AS metric_value
        FROM fact_order
        WHERE fact_order.order_date >= cast(timestamp '2025-01-01 08:00:00' AS timestamp)
          AND fact_order.order_date <= cast(timestamp '2026-11-01 07:59:59' AS timestamp)
        GROUP BY date_trunc('month', fact_order.order_date)
    ) AS a ON a.period = g.period
) AS w
WHERE w.period >= date_trunc('month', cast(timestamp '2026-01-01 08:00:00' AS timestamp))
ORDER BY w.period
FETCH NEXT 100 ROWS ONLY;

ROLLBACK;

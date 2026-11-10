-- M6/T1.2：渠道触点事件资产的数据基础（对照 M6 实施规格 v1.0 决策 2 与
-- docs/04-specs/analysis-mode-retention-attribution-规格-v1.md）。
-- 在 Query DS（ontos_queryds，ontos_query_ro 只读账号）执行；与 queryds V1/V2 同为
-- 手工应用脚本（该库不走 Flyway，应用方式：D:/pg/bin/psql.exe -d ontos_queryds -f 本文件）。
-- 幂等：全部 ON CONFLICT / IF NOT EXISTS。

-- 1) 触点渠道列（EventDef event.channel_touch.channelPropertyId = property.event.channel_id 的物理来源）
ALTER TABLE public.event ADD COLUMN IF NOT EXISTS channel_id TEXT;

-- 2) 复购订单（留存 Golden 需要「首单 → 后续周期再单」序列；同时补齐 o-4 缺失的下单事件）
INSERT INTO public.orders (id, tenant_id, customer_id, channel_id, channel, region, order_date, amount, refund_amount, status) VALUES
    ('o-10', 'demo', 'c-1001', 'ch-2', '线下', '华东', '2026-10-26 09:30:00+08', 1580.00, 0,    'PAID'),
    ('o-11', 'demo', 'c-1003', 'ch-1', '线上', '华东', '2026-11-06 21:10:00+08', 3499.00, 0,    'PAID'),
    ('o-12', 'demo', 'c-1003', 'ch-2', '线下', '华东', '2026-11-08 15:45:00+08', 129.00,  0,    'PAID')
ON CONFLICT (id) DO NOTHING;

INSERT INTO public.order_item (id, tenant_id, order_id, product_id, quantity, price) VALUES
    ('oi-10', 'demo', 'o-10', 'p-2', 1, 1580.00),
    ('oi-11', 'demo', 'o-11', 'p-1', 1, 3499.00),
    ('oi-12', 'demo', 'o-12', 'p-3', 1, 129.00)
ON CONFLICT (id) DO NOTHING;

-- 3) 下单事件（补一致性事件 + 复购序列）。ID 用 e-m6-* 前缀：
--    M2 审核证据 scripts/verify/funnel-semantics.sql 的事务内 fixture 使用 e-10..e-13，
--    若本脚本占用同名主键，该证据脚本会在真库执行时报 duplicate key（M6 阶段中深度审核发现）。
INSERT INTO public.event (id, tenant_id, customer_id, order_id, event_type, occurred_at, dedup_key) VALUES
    ('e-m6-4',  'demo', 'c-1003', 'o-4',  'ORDER_PLACED', '2026-11-01 20:05:00+08', 'o-4:ORDER_PLACED'),
    ('e-m6-10', 'demo', 'c-1001', 'o-10', 'ORDER_PLACED', '2026-10-26 09:30:00+08', 'o-10:ORDER_PLACED'),
    ('e-m6-11', 'demo', 'c-1003', 'o-11', 'ORDER_PLACED', '2026-11-06 21:10:00+08', 'o-11:ORDER_PLACED'),
    ('e-m6-12', 'demo', 'c-1003', 'o-12', 'ORDER_PLACED', '2026-11-08 15:45:00+08', 'o-12:ORDER_PLACED')
ON CONFLICT (id) DO NOTHING;

-- 4) 渠道触点事件（order_id 为空、channel_id 必填；时间均早于对应转化，t-7 落在 o-3 的 30d 归因窗之外
--    用作窗口边界样例）。不改动任何既有事件行，既有 funnel/Golden 结果不受影响。
INSERT INTO public.event (id, tenant_id, customer_id, order_id, event_type, occurred_at, dedup_key, channel_id) VALUES
    ('t-1', 'demo', 'c-1001', NULL, 'CHANNEL_TOUCH', '2026-10-04 20:00:00+08', 't-1:CHANNEL_TOUCH', 'ch-1'),
    ('t-2', 'demo', 'c-1001', NULL, 'CHANNEL_TOUCH', '2026-10-26 08:50:00+08', 't-2:CHANNEL_TOUCH', 'ch-2'),
    ('t-3', 'demo', 'c-1002', NULL, 'CHANNEL_TOUCH', '2026-10-12 08:40:00+08', 't-3:CHANNEL_TOUCH', 'ch-2'),
    ('t-4', 'demo', 'c-1003', NULL, 'CHANNEL_TOUCH', '2026-11-01 19:30:00+08', 't-4:CHANNEL_TOUCH', 'ch-1'),
    ('t-5', 'demo', 'c-1003', NULL, 'CHANNEL_TOUCH', '2026-11-06 20:30:00+08', 't-5:CHANNEL_TOUCH', 'ch-1'),
    ('t-6', 'demo', 'c-1003', NULL, 'CHANNEL_TOUCH', '2026-11-08 15:00:00+08', 't-6:CHANNEL_TOUCH', 'ch-2'),
    ('t-7', 'demo', 'c-1002', NULL, 'CHANNEL_TOUCH', '2026-09-05 12:00:00+08', 't-7:CHANNEL_TOUCH', 'ch-1'),
    ('t-8', 'demo', 'c-1001', NULL, 'CHANNEL_TOUCH', '2026-10-19 18:00:00+08', 't-8:CHANNEL_TOUCH', 'ch-1')
ON CONFLICT (id) DO NOTHING;

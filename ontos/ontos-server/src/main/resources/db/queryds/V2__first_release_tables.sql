-- 首发交易域表结构（M1/T2.3，对照 ADR-0004 资产下限）。
-- 每表强制：tenant_id 列 + ENABLE/FORCE ROW LEVEL SECURITY + 只读账号授权（v1.4 §4.1）。
-- 在 Query DS（ontos_queryds）手工执行；sales_order 保留为 PoC 夹具（demo 清单退役后仍可用）。
-- 身份上下文与应用同款：set_config('ontos.tenant_id', ?, true)。

-- ── 业务对象表 ──────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS public.customer (
    id          TEXT        PRIMARY KEY,
    tenant_id   TEXT        NOT NULL,
    name        TEXT        NOT NULL,
    email       TEXT,                       -- PII（存在性敏感）
    tier        TEXT,                       -- 客户等级：normal / silver / gold
    region      TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS public.channel (
    id          TEXT        PRIMARY KEY,
    tenant_id   TEXT        NOT NULL,
    name        TEXT        NOT NULL
);

CREATE TABLE IF NOT EXISTS public.product (
    id          TEXT        PRIMARY KEY,
    tenant_id   TEXT        NOT NULL,
    name        TEXT        NOT NULL,
    category    TEXT
);

CREATE TABLE IF NOT EXISTS public.orders (
    id              TEXT        PRIMARY KEY,
    tenant_id       TEXT        NOT NULL,
    customer_id     TEXT        NOT NULL,
    channel_id      TEXT,
    channel         TEXT,               -- 冗余渠道名（M1 单表指标用；关联经 channel_id）
    region          TEXT,               -- 冗余大区（同上）
    order_date      TIMESTAMPTZ NOT NULL,
    amount          NUMERIC(12,2) NOT NULL,
    refund_amount   NUMERIC(12,2) NOT NULL DEFAULT 0,
    status          TEXT        NOT NULL DEFAULT 'PAID',   -- PAID / CREATED / CANCELLED
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS public.order_item (
    id          TEXT        PRIMARY KEY,
    tenant_id   TEXT        NOT NULL,
    order_id    TEXT        NOT NULL,
    product_id  TEXT        NOT NULL,
    quantity    INTEGER     NOT NULL,
    price       NUMERIC(12,2) NOT NULL
);

CREATE TABLE IF NOT EXISTS public.event (
    id              TEXT        PRIMARY KEY,
    tenant_id       TEXT        NOT NULL,
    customer_id     TEXT,
    order_id        TEXT,
    event_type      TEXT        NOT NULL,   -- ORDER_PLACED / REFUND / LOGIN ...
    occurred_at     TIMESTAMPTZ NOT NULL,
    dedup_key       TEXT,                   -- 去重键（四要素之一）
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ── RLS：全部业务表 ENABLE + FORCE（v1.4 §4.1 最终边界） ─────────────────

DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['customer', 'channel', 'product', 'orders', 'order_item', 'event']
    LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('DROP POLICY IF EXISTS %I ON public.%I', t || '_tenant_isolation', t);
        EXECUTE format($p$
            CREATE POLICY %I ON public.%I
                FOR SELECT TO ontos_query_ro
                USING (tenant_id = current_setting('ontos.tenant_id', true))$p$, t || '_tenant_isolation', t);
        EXECUTE format('GRANT SELECT ON public.%I TO ontos_query_ro', t);
        EXECUTE format('CREATE INDEX IF NOT EXISTS %I ON public.%I (tenant_id)', 'idx_' || t || '_tenant', t);
    END LOOP;
END $$;

-- ── 演示数据（tenant=demo / other，用于 RLS 与链路验证；真实数据测试仍以 M4 复验为边界） ──

INSERT INTO public.channel (id, tenant_id, name) VALUES
    ('ch-1', 'demo', '线上'),
    ('ch-2', 'demo', '线下')
ON CONFLICT (id) DO NOTHING;

INSERT INTO public.customer (id, tenant_id, name, email, tier, region) VALUES
    ('c-1001', 'demo', '张三', 'zhang@example.com', 'gold',   '华东'),
    ('c-1002', 'demo', '李四', NULL,                 'normal', '华北'),
    ('c-1003', 'demo', '王五', 'wang@example.com',   'silver', '华东'),
    ('c-9001', 'other', '赵六', 'zhao@example.com',   'normal', '华南')
ON CONFLICT (id) DO NOTHING;

INSERT INTO public.product (id, tenant_id, name, category) VALUES
    ('p-1', 'demo', '旗舰手机',   '手机'),
    ('p-2', 'demo', '无线耳机',   '配件'),
    ('p-3', 'demo', '智能手表',   '穿戴')
ON CONFLICT (id) DO NOTHING;

INSERT INTO public.orders (id, tenant_id, customer_id, channel_id, channel, region, order_date, amount, refund_amount, status) VALUES
    ('o-1', 'demo', 'c-1001', 'ch-1', '线上', '华东', '2026-10-05 10:00:00+08', 6800.00, 0,     'PAID'),
    ('o-2', 'demo', 'c-1001', 'ch-1', '线上', '华东', '2026-10-20 14:30:00+08', 1299.00, 299.00, 'PAID'),
    ('o-3', 'demo', 'c-1002', 'ch-2', '线下', '华北', '2026-10-12 09:15:00+08', 4599.00, 0,     'PAID'),
    ('o-4', 'demo', 'c-1003', 'ch-1', '线上', '华东', '2026-11-01 20:05:00+08', 2199.00, 0,     'PAID'),
    ('o-5', 'demo', 'c-1003', 'ch-2', '线下', '华东', '2026-11-03 11:40:00+08', 899.00,  0,     'CREATED'),
    ('o-9', 'other', 'c-9001', 'ch-1', '线上', '华南', '2026-10-18 16:00:00+08', 999.00,  0,     'PAID')
ON CONFLICT (id) DO NOTHING;

INSERT INTO public.order_item (id, tenant_id, order_id, product_id, quantity, price) VALUES
    ('oi-1', 'demo', 'o-1', 'p-1', 1, 6800.00),
    ('oi-2', 'demo', 'o-2', 'p-2', 1, 1299.00),
    ('oi-3', 'demo', 'o-3', 'p-1', 1, 4599.00),
    ('oi-4', 'demo', 'o-4', 'p-3', 1, 2199.00),
    ('oi-5', 'demo', 'o-5', 'p-2', 1, 899.00),
    ('oi-9', 'other', 'o-9', 'p-2', 1, 999.00)
ON CONFLICT (id) DO NOTHING;

INSERT INTO public.event (id, tenant_id, customer_id, order_id, event_type, occurred_at, dedup_key) VALUES
    ('e-1', 'demo', 'c-1001', 'o-1', 'ORDER_PLACED', '2026-10-05 10:00:00+08', 'o-1:ORDER_PLACED'),
    ('e-2', 'demo', 'c-1002', 'o-3', 'ORDER_PLACED', '2026-10-12 09:15:00+08', 'o-3:ORDER_PLACED'),
    ('e-3', 'demo', 'c-1001', 'o-2', 'REFUND',       '2026-10-21 09:00:00+08', 'o-2:REFUND')
ON CONFLICT (id) DO NOTHING;

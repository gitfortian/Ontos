-- Query DS 演示表与 RLS。由 DBA 在 Query DS 执行，不属于 System DB 的 Flyway 迁移。
-- 前置：角色 ontos_query_ro 已存在（docker-compose 的 init 脚本创建）。

CREATE TABLE IF NOT EXISTS public.sales_order (
    id          BIGSERIAL   PRIMARY KEY,
    tenant_id   TEXT        NOT NULL,
    region      TEXT        NOT NULL,
    channel     TEXT        NOT NULL,
    amount      NUMERIC(18, 2) NOT NULL,
    order_date  TIMESTAMPTZ NOT NULL
);

ALTER TABLE public.sales_order ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.sales_order FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS sales_order_tenant_isolation ON public.sales_order;
CREATE POLICY sales_order_tenant_isolation ON public.sales_order
    FOR SELECT
    USING (tenant_id = current_setting('ontos.tenant_id', true));

GRANT SELECT ON public.sales_order TO ontos_query_ro;
GRANT USAGE ON SCHEMA public TO ontos_query_ro;

-- 演示数据（两个租户，验证隔离）
INSERT INTO public.sales_order (tenant_id, region, channel, amount, order_date)
VALUES ('demo', '华东', 'online', 1200.00, now() - interval '1 day'),
       ('demo', '华南', 'offline', 800.00, now() - interval '2 day'),
       ('other', '华东', 'online', 9999.00, now() - interval '1 day')
ON CONFLICT DO NOTHING;

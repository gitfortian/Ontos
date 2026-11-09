-- Ontos System DB：策略快照（M4/W1，ADR-0014 前置完成后首个权限迁移）
-- 形态决策（M4 规格 §8 决策 1）：不可变 JSONB 快照，与 published_snapshot 同构——
-- policy_version 锚定、整体激活/回滚、缓存键天然绑定版本、降权立即生效。

CREATE TABLE policy_snapshot (
    snapshot_id     UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       TEXT        NOT NULL,
    policy_version  TEXT        NOT NULL,
    payload         JSONB       NOT NULL,
    active          BOOLEAN     NOT NULL DEFAULT false,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      TEXT        NOT NULL,
    UNIQUE (tenant_id, policy_version)
);
-- 同租户至多一个 active 快照：普通列约束会连「多条 inactive 历史」一起禁掉，
-- 必须用部分唯一索引；激活语义由应用层事务内「先取消旧、再置新」保证，
-- 绕过应用层的写入也会被该索引拒绝（DEFERRABLE 允许同事务内先关旧再开新）。
CREATE UNIQUE INDEX ux_policy_snapshot_active
    ON policy_snapshot (tenant_id) WHERE active = TRUE;

COMMENT ON TABLE policy_snapshot IS '不可变策略快照；授权语义只认 active 版本，历史版本保留供审计回放';
COMMENT ON COLUMN policy_snapshot.payload IS 'roles/assignments/assets 三段；assets[].sensitivity_class 承载 label/existence/pii 分级';

CREATE OR REPLACE FUNCTION reject_policy_snapshot_mutation()
    RETURNS TRIGGER AS
$$
BEGIN
    RAISE EXCEPTION 'policy_snapshot is immutable (snapshot_id=%)', COALESCE(OLD.snapshot_id, NEW.snapshot_id);
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_policy_snapshot_immutable
    BEFORE UPDATE OR DELETE
    ON policy_snapshot
    FOR EACH ROW
EXECUTE FUNCTION reject_policy_snapshot_mutation();


-- 种子快照（demo 租户，policy_version = seed-2026.11.09-v1）：
-- 资产清单程序化生成自 first-release-v8.json（8 指标 / 5 维度 / 1 规则 / 6 实体 / 16 属性）；
-- assignments 用 userId="*" 通配到 role.analyst，仅供开发租户联调，生产由运营发布收紧版本。
INSERT INTO policy_snapshot (tenant_id, policy_version, payload, active, created_by)
VALUES ('demo', 'seed-2026.11.09-v1',
    '{"roles":[{"roleId":"role.analyst","name":"分析师","description":"首发资产全量只读；PII 属性脱敏返回；存在性保护按清单 existenceSensitive"},{"roleId":"role.auditor","name":"审计员","description":"仅审计与审批查询，不授权业务资产"}],"assignments":[{"userId":"*","roleId":"role.analyst","note":"开发租户种子：全部用户默认 analyst；生产快照由运营发布时逐用户绑定（快照不可变，收紧授权=发布新 policy_version）"}],"assets":[{"assetType":"metric","assetId":"metric.gmv","effect":"allow","roles":["role.analyst"],"sensitivityClass":"internal","note":"首发业务指标，只读授权；label 语义机制由策略层承载，首发无 L3 资产（种子按 internal 授权，M3 Golden m-boolean 不受影响）"},{"assetType":"metric","assetId":"metric.paid_gmv","effect":"allow","roles":["role.analyst"],"sensitivityClass":"internal","note":"首发业务指标，只读授权；label 语义机制由策略层承载，首发无 L3 资产（种子按 internal 授权，M3 Golden m-boolean 不受影响）"},{"assetType":"metric","assetId":"metric.gmv_created","effect":"allow","roles":["role.analyst"],"sensitivityClass":"internal","note":"首发业务指标，只读授权；label 语义机制由策略层承载，首发无 L3 资产（种子按 internal 授权，M3 Golden m-boolean 不受影响）"},{"assetType":"metric","assetId":"metric.order_count","effect":"allow","roles":["role.analyst"],"sensitivityClass":"internal","note":"首发业务指标，只读授权；label 语义机制由策略层承载，首发无 L3 资产（种子按 internal 授权，M3 Golden m-boolean 不受影响）"},{"assetType":"metric","assetId":"metric.distinct_customers","effect":"allow","roles":["role.analyst"],"sensitivityClass":"internal","note":"首发业务指标，只读授权；label 语义机制由策略层承载，首发无 L3 资产（种子按 internal 授权，M3 Golden m-boolean 不受影响）"},{"assetType":"metric","assetId":"metric.avg_order_value","effect":"allow","roles":["role.analyst"],"sensitivityClass":"internal","note":"首发业务指标，只读授权；label 语义机制由策略层承载，首发无 L3 资产（种子按 internal 授权，M3 Golden m-boolean 不受影响）"},{"assetType":"metric","assetId":"metric.refund_amount","effect":"allow","roles":["role.analyst"],"sensitivityClass":"internal","note":"首发业务指标，只读授权；label 语义机制由策略层承载，首发无 L3 资产（种子按 internal 授权，M3 Golden m-boolean 不受影响）"},{"assetType":"metric","assetId":"metric.high_value_customer","effect":"allow","roles":["role.analyst"],"sensitivityClass":"internal","note":"首发业务指标，只读授权；label 语义机制由策略层承载，首发无 L3 资产（种子按 internal 授权，M3 Golden m-boolean 不受影响）"},{"assetType":"dimension","assetId":"dim.region","effect":"allow","roles":["role.analyst"],"sensitivityClass":"internal"},{"assetType":"dimension","assetId":"dim.channel","effect":"allow","roles":["role.analyst"],"sensitivityClass":"internal"},{"assetType":"dimension","assetId":"dim.order_date","effect":"allow","roles":["role.analyst"],"sensitivityClass":"internal"},{"assetType":"dimension","assetId":"dim.customer_tier","effect":"allow","roles":["role.analyst"],"sensitivityClass":"internal"},{"assetType":"dimension","assetId":"dim.product_category","effect":"allow","roles":["role.analyst"],"sensitivityClass":"internal"},{"assetType":"entity","assetId":"concept.customer","effect":"allow","roles":["role.analyst"],"sensitivityClass":"internal"},{"assetType":"entity","assetId":"concept.order","effect":"allow","roles":["role.analyst"],"sensitivityClass":"internal"},{"assetType":"entity","assetId":"concept.order_item","effect":"allow","roles":["role.analyst"],"sensitivityClass":"internal"},{"assetType":"entity","assetId":"concept.product","effect":"allow","roles":["role.analyst"],"sensitivityClass":"internal"},{"assetType":"entity","assetId":"concept.channel","effect":"allow","roles":["role.analyst"],"sensitivityClass":"internal"},{"assetType":"entity","assetId":"concept.event","effect":"allow","roles":["role.analyst"],"sensitivityClass":"internal"},{"assetType":"property","assetId":"property.customer.customer_id","effect":"allow","roles":["role.analyst"],"sensitivityClass":"internal"},{"assetType":"property","assetId":"property.customer.email","effect":"allow","roles":["role.analyst"],"sensitivityClass":"pii"},{"assetType":"property","assetId":"property.customer.tier","effect":"allow","roles":["role.analyst"],"sensitivityClass":"internal"},{"assetType":"property","assetId":"property.order.order_no","effect":"allow","roles":["role.analyst"],"sensitivityClass":"internal"},{"assetType":"property","assetId":"property.order.order_date","effect":"allow","roles":["role.analyst"],"sensitivityClass":"internal"},{"assetType":"property","assetId":"property.order.amount","effect":"allow","roles":["role.analyst"],"sensitivityClass":"internal"},{"assetType":"property","assetId":"property.order_item.item_id","effect":"allow","roles":["role.analyst"],"sensitivityClass":"internal"},{"assetType":"property","assetId":"property.order_item.quantity","effect":"allow","roles":["role.analyst"],"sensitivityClass":"internal"},{"assetType":"property","assetId":"property.product.product_id","effect":"allow","roles":["role.analyst"],"sensitivityClass":"internal"},{"assetType":"property","assetId":"property.product.category","effect":"allow","roles":["role.analyst"],"sensitivityClass":"internal"},{"assetType":"property","assetId":"property.channel.channel_id","effect":"allow","roles":["role.analyst"],"sensitivityClass":"internal"},{"assetType":"property","assetId":"property.event.event_id","effect":"allow","roles":["role.analyst"],"sensitivityClass":"internal"},{"assetType":"property","assetId":"property.event.event_type","effect":"allow","roles":["role.analyst"],"sensitivityClass":"internal"},{"assetType":"property","assetId":"property.event.occurred_at","effect":"allow","roles":["role.analyst"],"sensitivityClass":"internal"},{"assetType":"property","assetId":"property.event.customer_id","effect":"allow","roles":["role.analyst"],"sensitivityClass":"internal"},{"assetType":"property","assetId":"property.event.order_id","effect":"allow","roles":["role.analyst"],"sensitivityClass":"internal"}]}'::jsonb,
    TRUE, 'm4-w1-seed');

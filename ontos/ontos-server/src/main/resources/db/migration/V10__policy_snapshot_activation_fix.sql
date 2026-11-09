-- Ontos System DB：策略快照激活语义修复（M4/W1 E2E 发现的两处缺陷）
--
-- 缺陷 1：V8 触发器把 active 列也纳入不可变范围，导致「激活切换」（activate 的
--         UPDATE active）被自己的守卫拦死。不可变的对象是**内容**（policy_version/payload），
--         active 是状态列。触发器改为：内容列变更即拒绝，仅 active 翻转放行。
-- 缺陷 2：V8 种子 payload 漏了 policyVersion 字段，加载期校验（fail-closed）正确拦截，
--         demo 全部查询 1003000004。快照不可变 → 修复方式是发修正版 v2 并切换激活。

CREATE OR REPLACE FUNCTION reject_policy_snapshot_content_mutation()
    RETURNS TRIGGER AS
$$
BEGIN
    -- 内容列（policy_version / payload）任何变更都拒绝；active 翻转与审计列由应用层管理
    IF OLD.policy_version IS DISTINCT FROM NEW.policy_version
       OR OLD.payload IS DISTINCT FROM NEW.payload THEN
        RAISE EXCEPTION 'policy_snapshot is immutable (snapshot_id=%)', COALESCE(OLD.snapshot_id, NEW.snapshot_id);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_policy_snapshot_immutable ON policy_snapshot;
CREATE TRIGGER trg_policy_snapshot_content_immutable
    BEFORE UPDATE
    ON policy_snapshot
    FOR EACH ROW
EXECUTE FUNCTION reject_policy_snapshot_content_mutation();

COMMENT ON TRIGGER trg_policy_snapshot_content_immutable ON policy_snapshot IS '内容不可变（policy_version/payload），active 状态列允许翻转（activate 语义）';

-- 修正版种子：payload 补 policyVersion（内容不可变，v1 无法原地修，发新版本）
INSERT INTO policy_snapshot (tenant_id, policy_version, payload, active, created_by)
SELECT 'demo', 'seed-2026.11.09-v2',
       jsonb_set(payload::jsonb, '{policyVersion}', to_jsonb('seed-2026.11.09-v2'::text)),
       FALSE, 'm4-w1-fix'
FROM policy_snapshot
WHERE tenant_id = 'demo' AND policy_version = 'seed-2026.11.09-v1'
  AND NOT EXISTS (
      SELECT 1 FROM policy_snapshot WHERE tenant_id = 'demo' AND policy_version = 'seed-2026.11.09-v2');

-- 切换激活：关旧（v1 缺 policyVersion，保留供审计回放）开新（v2）
UPDATE policy_snapshot SET active = FALSE WHERE tenant_id = 'demo' AND active = TRUE AND policy_version = 'seed-2026.11.09-v1';
UPDATE policy_snapshot SET active = TRUE WHERE tenant_id = 'demo' AND policy_version = 'seed-2026.11.09-v2';

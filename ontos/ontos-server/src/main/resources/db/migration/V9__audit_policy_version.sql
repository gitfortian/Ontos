-- Ontos System DB：审计补生效策略版本（M4/W1，规格 v1.1 §3.1「生效版本进审计」）。
-- 审计口径：audit_version 由 AuditService 写入时从策略快照解析（缺失容错为 NULL，不阻断审计——
-- 与「审计失败即整单失败」不同：版本取不到说明快照缺失，授权层会先拒绝，审计不该重复报错）。

ALTER TABLE audit_log ADD COLUMN IF NOT EXISTS policy_version TEXT;
COMMENT ON COLUMN audit_log.policy_version IS '写入该审计行时的生效策略快照版本（M4/W1）；NULL = dev 放行或快照缺失';

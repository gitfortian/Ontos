-- Ontos System DB：审批放行令牌（M4/W2/T2.3，规格 §3.3）
--
-- 放行令牌 = approval_id + 目标指纹（hash(user_id + tenant_id + sensitive_key +
-- scope_hash + filters_hash)，与 sensitive_query_event 对应字段同源同义）：
--   - 审批决策 APPROVED 时生成指纹并落列；
--   - 查询触发敏感判定后携 X-Ontos-Approval 校验：指纹逐字段比对（换 filters 不放行）
--     + 单次消费（grant_consumed_at 置位后不可复用，防审批复用放大权限）。
-- 决策人 ≠ 申请人的校验在应用层 decide 时强制（不同人才能形成有效放行）。

ALTER TABLE approval_request
    ADD COLUMN grant_fingerprint TEXT,
    ADD COLUMN grant_consumed_at TIMESTAMPTZ;

COMMENT ON COLUMN approval_request.grant_fingerprint IS '放行目标指纹（hash(user|tenant|sensitive_key|scope|filters)），APPROVED 时生成；NULL=未生成（REJECTED/EXPIRED）';
COMMENT ON COLUMN approval_request.grant_consumed_at IS '令牌消费时间；NULL=未消费（单次有效，消费后不可复用）';

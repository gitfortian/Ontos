-- Ontos System DB：审计补齐（M4/W3/T3.2，规格 §3.3「审计记录 approval_id」+ §4.8 计数器哈希化）
--
-- approval_id：敏感累计触发 / 令牌放行的审计行关联审批单，回放时可从审计追到审批依据；
-- sensitive_counters_delta：本次敏感访问后各层计数的哈希化增量（§4.8「计数器哈希化后写入审计」），
-- 明文计数只在审批 payload 与安全看板口径，审计行只留哈希。
-- sensitive_query_event.context_delta（V11 预留列）与审计行同值同源，本迁移补语义注释。

ALTER TABLE audit_log
    ADD COLUMN approval_id TEXT,
    ADD COLUMN sensitive_counters_delta TEXT;

COMMENT ON COLUMN audit_log.approval_id IS '关联审批单（敏感触发/令牌放行的审计行），可空';
COMMENT ON COLUMN audit_log.sensitive_counters_delta IS '敏感计数器哈希化增量（§4.8），可空；与 sensitive_query_event.context_delta 同值同源';

COMMENT ON COLUMN sensitive_query_event.context_delta IS '本次事件后各层计数的哈希化增量，与 audit_log.sensitive_counters_delta 同值同源（W3/T3.2 回填）';

CREATE INDEX idx_audit_approval ON audit_log (approval_id) WHERE approval_id IS NOT NULL;

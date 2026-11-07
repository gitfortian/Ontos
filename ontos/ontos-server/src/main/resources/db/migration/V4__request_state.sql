-- 请求级状态机（M1/T6.1+T6.3，ADR-0004 2.2 八态口径）。
-- 状态只由服务端推进；每次迁移落 audit_log（from/to/触发原因）；终态不可回退；
-- 同 request_id 幂等（重复提交不重新执行，ADR-0004 2.2 约束）。

ALTER TABLE audit_log ADD COLUMN from_state TEXT;
ALTER TABLE audit_log ADD COLUMN to_state TEXT;
ALTER TABLE audit_log ADD COLUMN state_reason TEXT;
COMMENT ON COLUMN audit_log.from_state IS '状态迁移起点（action=STATE_TRANSITION 行）';
COMMENT ON COLUMN audit_log.to_state IS '状态迁移终点';
COMMENT ON COLUMN audit_log.state_reason IS '迁移触发原因';

CREATE TABLE IF NOT EXISTS request_state (
    request_id  TEXT        PRIMARY KEY,
    tenant_id   TEXT        NOT NULL,
    user_id     TEXT        NOT NULL,
    session_id  TEXT,
    state       TEXT        NOT NULL,
    row_count   INTEGER,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE request_state IS '请求级状态（8 态）；request_id 幂等键，同 ID 重复提交不重新执行';

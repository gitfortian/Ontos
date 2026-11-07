-- Ontos System DB schema (PostgreSQL 18)
-- 原则：发布清单不可变；会话状态按 (tenant, user, session) 唯一；审计只追加。

CREATE TABLE published_snapshot (
    manifest_id             TEXT PRIMARY KEY,
    content_hash            CHAR(64)    NOT NULL,
    ontology_version        TEXT        NOT NULL,
    semantic_version        TEXT        NOT NULL,
    policy_version          TEXT        NOT NULL,
    rule_version            TEXT        NOT NULL,
    time_parser_version     TEXT        NOT NULL,
    copy_template_version   TEXT        NOT NULL,
    payload                 JSONB       NOT NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by              TEXT        NOT NULL
);
COMMENT ON TABLE published_snapshot IS '不可变发布清单；同一 manifest_id 内容永不更新';

CREATE OR REPLACE FUNCTION reject_published_snapshot_mutation()
    RETURNS TRIGGER AS
$$
BEGIN
    RAISE EXCEPTION 'published_snapshot is immutable (manifest_id=%)', COALESCE(OLD.manifest_id, NEW.manifest_id);
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_published_snapshot_immutable
    BEFORE UPDATE OR DELETE
    ON published_snapshot
    FOR EACH ROW
EXECUTE FUNCTION reject_published_snapshot_mutation();

CREATE TABLE session_state (
    tenant_id           TEXT        NOT NULL,
    user_id             TEXT        NOT NULL,
    session_id          TEXT        NOT NULL,
    turn_index          INTEGER     NOT NULL DEFAULT 0,
    resolved_entities   JSONB       NOT NULL DEFAULT '{}'::jsonb,
    query_context       JSONB       NOT NULL DEFAULT '{}'::jsonb,
    scenario_state      JSONB       NOT NULL DEFAULT '{}'::jsonb,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, user_id, session_id)
);
COMMENT ON TABLE session_state IS '会话权威状态；turn_index 作为乐观锁';

CREATE TABLE approval_request (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       TEXT        NOT NULL,
    user_id         TEXT        NOT NULL,
    session_id      TEXT,
    turn_index      INTEGER,
    kind            TEXT        NOT NULL,
    status          TEXT        NOT NULL DEFAULT 'PENDING'
                    CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED')),
    payload         JSONB       NOT NULL,
    reason          TEXT,
    requested_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at      TIMESTAMPTZ,
    decided_by      TEXT
);
CREATE INDEX idx_approval_pending ON approval_request (tenant_id, status, requested_at DESC);

CREATE TABLE sensitive_query_event (
    id              BIGSERIAL   PRIMARY KEY,
    tenant_id       TEXT        NOT NULL,
    user_id         TEXT        NOT NULL,
    session_id      TEXT,
    event_type      TEXT        NOT NULL,
    fingerprint     TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_sensitive_window ON sensitive_query_event (tenant_id, user_id, created_at DESC);

CREATE TABLE audit_log (
    id                  BIGSERIAL   PRIMARY KEY,
    request_id          TEXT        NOT NULL,
    parent_request_id   TEXT,
    sub_intent_id       TEXT,
    tenant_id           TEXT        NOT NULL,
    user_id             TEXT        NOT NULL,
    session_id          TEXT,
    turn_index          INTEGER,
    manifest_id         TEXT,
    action              TEXT        NOT NULL,
    metric_id           TEXT,
    dsl                 JSONB,
    sql_text            TEXT,
    row_count           INTEGER,
    reason_code         TEXT,
    trace_id            TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_request ON audit_log (request_id);
CREATE INDEX idx_audit_tenant_time ON audit_log (tenant_id, created_at DESC);

CREATE TABLE query_cache (
    cache_key       CHAR(64)    PRIMARY KEY,
    manifest_id     TEXT        NOT NULL,
    query_shape     TEXT        NOT NULL,
    result          JSONB       NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_query_cache_expires ON query_cache (expires_at);

CREATE TABLE domain_combination_policy (
    id          BIGSERIAL   PRIMARY KEY,
    tenant_id   TEXT        NOT NULL,
    domain_pair TEXT        NOT NULL,
    allowed     BOOLEAN     NOT NULL DEFAULT false,
    reason      TEXT,
    UNIQUE (tenant_id, domain_pair)
);

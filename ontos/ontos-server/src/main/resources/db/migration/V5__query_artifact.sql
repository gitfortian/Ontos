-- 编译产物持久化（M2/W4/T4.5，支撑 dry-plan / dry-run 回放，规格 §3.2 GET /plan/{requestId}）。
--
-- 为什么单独建表而不塞进 audit_log：
--   1) audit_log 是 append-only 合规流水，同一 request_id 会有多条（状态迁移、编译、执行），
--      「最近一次编译产物」需要可覆盖的槽位；
--   2) 绑定值可能含标识类值（lookup 的 identity 可能是邮箱），查询必须按租户 + 用户收敛，
--      与审计的「按租户可查」不是同一访问模型。
--
-- 主键含 tenant_id + user_id：request_id 允许客户端通过 X-Request-Id 指定，
-- 只用 request_id 做主键会让跨租户/跨用户的同 ID 请求互相覆盖对方的可回放产物。
CREATE TABLE IF NOT EXISTS query_artifact (
    tenant_id       TEXT             NOT NULL,
    user_id         TEXT             NOT NULL,
    request_id      TEXT             NOT NULL,
    session_id      TEXT,
    manifest_id     TEXT             NOT NULL,
    content_hash    TEXT             NOT NULL,
    mode            TEXT             NOT NULL,
    metric_id       TEXT,
    entity_id       TEXT,
    sql_text        TEXT             NOT NULL,
    bind_values     JSONB            NOT NULL DEFAULT '[]'::jsonb,
    joins           JSONB            NOT NULL DEFAULT '[]'::jsonb,
    applied_limit   INTEGER,
    plan_status     TEXT,
    estimated_cost  DOUBLE PRECISION,
    estimated_rows  BIGINT,
    plan_json       TEXT,
    verified        BOOLEAN          NOT NULL DEFAULT false,
    created_at      TIMESTAMPTZ      NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ      NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, user_id, request_id)
);

COMMENT ON TABLE query_artifact IS '最近一次编译产物（SQL/绑定值/Join 路径/EXPLAIN 计划），供 /plan/{requestId} 回放';
COMMENT ON COLUMN query_artifact.bind_values IS '参数化绑定值（JSON 数组）；含标识类值时按租户+用户收敛访问';
COMMENT ON COLUMN query_artifact.joins IS '服务端解析出的 UNIQUE Join 路径（ResolvedJoin 数组）';
COMMENT ON COLUMN query_artifact.plan_json IS 'EXPLAIN (FORMAT JSON) 原始计划，仅 dry-plan/dry-run 写入';
COMMENT ON COLUMN query_artifact.plan_status IS '计划判定：OK / BLOCKED；未做 EXPLAIN 的编译为 NULL';
COMMENT ON COLUMN query_artifact.estimated_rows IS 'EXPLAIN 顶层 Plan Rows，与 estimated_cost 成对出现';
COMMENT ON COLUMN query_artifact.verified IS '该产物是否经 dry-run 真实执行校验（回放端点据此还原状态）';

CREATE INDEX IF NOT EXISTS idx_query_artifact_manifest ON query_artifact (manifest_id, content_hash);
CREATE INDEX IF NOT EXISTS idx_query_artifact_updated ON query_artifact (updated_at DESC);

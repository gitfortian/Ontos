-- Ontos System DB：M5/W1 会话状态机启用（规格 v1.2 §3.1 / T1.1）
--
-- session_state（V1 已建）启用并扩列；M4 收口审核 F-1 的投影模型：
--   服务端状态 = 本轮已采纳 DSL 的实体投影（每轮全量覆盖）+ 显式澄清选择追加。
-- ask_run / ask_run_event：AG-UI run 的服务端聚合根与断线重连重放源（规格 §3.5/§3.7）。

ALTER TABLE session_state
    ADD COLUMN recent_turns JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN last_terminal TEXT,
    ADD COLUMN pending_clarification JSONB;

COMMENT ON COLUMN session_state.recent_turns IS '最近 N 轮摘要（turn/terminal/实体投影/requestId），深度 ontos.session.recent-turns（默认 5）';
COMMENT ON COLUMN session_state.last_terminal IS '上一轮终态（query/clarification/discovery/out_of_capability/composite）';
COMMENT ON COLUMN session_state.pending_clarification IS '未答复的澄清候选（四要素 + candidates）；回传 candidateId 必须属于该集合（v1.2 R5）';

CREATE TABLE ask_run (
    run_id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       TEXT        NOT NULL,
    user_id         TEXT        NOT NULL,
    session_id      TEXT        NOT NULL,
    turn_index      INTEGER     NOT NULL,
    -- PENDING → RUNNING → (WAITING_APPROVAL → RUNNING) → FINISHED；FAILED / EXPIRED 终态
    -- （M4 G-4 教训：先判定后转移，终态不可回退，合法迁移由编排代码显式枚举）
    status          TEXT        NOT NULL DEFAULT 'PENDING'
                    CHECK (status IN ('PENDING', 'RUNNING', 'WAITING_APPROVAL',
                                      'FINISHED', 'FAILED', 'EXPIRED')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_ask_run_session ON ask_run (tenant_id, user_id, session_id, created_at DESC);
CREATE INDEX idx_ask_run_status ON ask_run (status) WHERE status IN ('RUNNING', 'WAITING_APPROVAL');

COMMENT ON TABLE ask_run IS 'AG-UI run 聚合根：run 创建 = turn 占用（session_state 乐观锁原子完成）；启动扫描 RUNNING→FAILED / WAITING_APPROVAL→EXPIRED（v1.2 R4）';

CREATE TABLE ask_run_event (
    id              BIGSERIAL   PRIMARY KEY,
    run_id          UUID        NOT NULL REFERENCES ask_run (run_id),
    seq             BIGINT      NOT NULL,
    type            TEXT        NOT NULL,
    payload         JSONB       NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (run_id, seq)
);

COMMENT ON TABLE ask_run_event IS 'run 事件持久化（单调 seq）：断线重连 afterSeq 只读重放（v1.2 E5 幂等），只存事件载荷不存 SQL/结果（清理归 M8）';

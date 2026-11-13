-- ask 反馈表（W3 结果看板 👍👎）：结构化反馈落库，不做假 UI——前端按钮与端点同批交付。
--
-- 边界：
-- * 反馈是「对一轮结果的评价」，归属（tenant/user/session）随请求上下文写入，与 ask_run 同源；
-- * run_id / request_id 可空（重放轮可能缺 run 上下文），但至少要有其一，服务层 fail-closed；
-- * rating 封闭枚举（UP/DOWN），reason_code 封闭枚举由服务层校验，DB 只拦 rating；
-- * 追加式（不更新），一轮可以有多条反馈（用户改主意允许再评）。

CREATE TABLE ask_feedback (
    id            BIGSERIAL   PRIMARY KEY,
    tenant_id     TEXT        NOT NULL,
    user_id       TEXT        NOT NULL,
    session_id    TEXT,
    run_id        UUID,
    request_id    TEXT,
    rating        TEXT        NOT NULL CHECK (rating IN ('UP', 'DOWN')),
    -- DOWN 细分原因：WRONG_DATA(数据不对) / WRONG_CALIBER(口径不符) / NOT_ANSWERED(没回答问题) / OTHER
    reason_code   TEXT,
    comment       TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_ask_feedback_tenant_time ON ask_feedback (tenant_id, created_at DESC);
CREATE INDEX idx_ask_feedback_request ON ask_feedback (request_id) WHERE request_id IS NOT NULL;

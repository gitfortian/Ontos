-- Ontos System DB：模型调用追踪（M7/W5/T5.1，V20）
--
-- 设计约束（M7 规格 §3.7）：
--   1. **只追加**：调用记录不可修改、不可删除（保留策略按 T5.3 统一执行）；
--   2. **脱敏**：只存元数据（模型名 / token 用量 / 耗时 / 终态），
--      **不存提示词与模型输出原文**（v1.4 附录 A.4）；
--   3. **不阻断**：写入由 sink 实现完成，异常在实现内吞掉，业务链路不感知；
--   4. 租户隔离：所有查询按 tenant_id 过滤（与 V17/V19 同规）；
--   5. trace 关联：trace_id 取自请求上下文（X-Request-Id / MDC），
--      与 audit_log.trace_id 同源，可串起「问题 → DSL → SQL → 结果 → 模型调用」。

CREATE TABLE llm_call_trace (
    id             BIGSERIAL   PRIMARY KEY,
    tenant_id      TEXT        NOT NULL,
    trace_id       TEXT,                                  -- 请求级 traceId（可为空：异步路径缺失）
    session_id     TEXT,
    turn_index     INTEGER,
    purpose        TEXT,                                  -- ask.intent / agui.run
    model          TEXT,                                  -- 模型标识（供应商无关）
    input_tokens   INTEGER     NOT NULL DEFAULT 0,
    output_tokens  INTEGER     NOT NULL DEFAULT 0,
    total_tokens   INTEGER     NOT NULL DEFAULT 0,
    latency_ms     BIGINT      NOT NULL DEFAULT 0,
    status         TEXT        NOT NULL,                  -- success / error / cancelled
    error_type     TEXT,                                  -- 异常类名（不含堆栈）
    error_message  TEXT,                                  -- 截断到 500 字符
    finish_reason  TEXT,                                  -- 模型侧终止原因
    created_at     TIMESTAMP   NOT NULL DEFAULT now()
);

CREATE INDEX idx_llm_call_trace_request ON llm_call_trace (tenant_id, trace_id, created_at);
CREATE INDEX idx_llm_call_trace_recent ON llm_call_trace (tenant_id, created_at DESC);

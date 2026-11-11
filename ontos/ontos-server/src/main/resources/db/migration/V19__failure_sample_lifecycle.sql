-- Ontos System DB：失败样本生命周期（M7/W4/T4.1–T4.3，D-22）
--
-- 设计约束（M7 规格 §3.6）：
--   1. V17 `evaluation_failure_sample` 保持**只追加**（不加 status 列、不 UPDATE）；
--   2. 状态流转与根因分类各走独立**事件表**（只追加），当前态由最新事件派生；
--   3. 退回回归集只允许 dev / validation（禁止 test——test 集只能升版），
--      事件 note 记录 targetSet 与 regressionSampleId，供 runner/脚本落题集；
--   4. 租户隔离：所有查询按 tenant_id 过滤（与 V17 同规）。

CREATE TABLE evaluation_failure_sample_status_event (
    id          BIGSERIAL   PRIMARY KEY,
    tenant_id   TEXT        NOT NULL,
    sample_id   BIGINT      NOT NULL,
    from_status TEXT,                                  -- 初始 open 时 from 为 NULL（或历史空）
    to_status   TEXT        NOT NULL,                  -- open / triaged / fixed / regressed / ignored
    actor       TEXT        NOT NULL,                  -- 决策人（认证上下文 userId）
    note        TEXT,                                  -- 备注（回流时含 targetSet / regressionSampleId）
    created_at  TIMESTAMP   NOT NULL DEFAULT now()
);

CREATE INDEX idx_efsse_sample ON evaluation_failure_sample_status_event
    (tenant_id, sample_id, created_at);
CREATE INDEX idx_efsse_status ON evaluation_failure_sample_status_event
    (tenant_id, to_status, created_at);

CREATE TABLE evaluation_failure_sample_classification (
    id          BIGSERIAL   PRIMARY KEY,
    tenant_id   TEXT        NOT NULL,
    sample_id   BIGINT      NOT NULL,
    category    TEXT        NOT NULL,                  -- §12.3 原因码族（FailureCauseCategory 闭词表）
    actor       TEXT        NOT NULL,
    note        TEXT,
    created_at  TIMESTAMP   NOT NULL DEFAULT now()
);

CREATE INDEX idx_efsc_sample ON evaluation_failure_sample_classification
    (tenant_id, sample_id, created_at);
CREATE INDEX idx_efsc_category ON evaluation_failure_sample_classification
    (tenant_id, category, created_at);

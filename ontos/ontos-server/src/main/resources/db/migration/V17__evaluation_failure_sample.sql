-- Ontos System DB：M6/W5/T5.1 失败看板落库表（OBL-M7 交付物 ④，M6 规格 §3.8 / v1.4 §12.3）
--
-- 定位：评测（test 集 / 冒烟集）失败样本的分类落库 + 五维下钻（G-5）：
--   scenario_entry_mode / query_shape / turn_type / is_composite / risk_type。
-- 写入方：评测 runner（test-runner.mjs / smoke-runner.mjs）经管理面 API 批量登记；
-- 读取方：失败看板查询接口 + 前端 audit 域。
-- 语义：只追加（与 audit_log 同规），不提供更新/删除入口；
--   纠正样本回流 = 失败样本修复后按题集升版流程进回归集（dev 集），本表不翻状态。
-- 租户隔离：与审计一致按 tenant_id 过滤。

CREATE TABLE evaluation_failure_sample (
    id                   BIGSERIAL    PRIMARY KEY,
    tenant_id            TEXT         NOT NULL,
    set_id               TEXT         NOT NULL,              -- 题集标识（如 test-v3 / smoke-v3）
    question_id          TEXT         NOT NULL,              -- 题目 ID（如 t-agg-single-01）
    run_id               TEXT         NOT NULL,              -- 评测运行 ID（同一次评测批次的关联键）
    scenario_entry_mode  TEXT         NOT NULL,
    query_shape          TEXT         NOT NULL,
    turn_type            TEXT         NOT NULL,
    is_composite         BOOLEAN      NOT NULL DEFAULT FALSE,
    risk_type            TEXT         NOT NULL,
    expected_terminal    TEXT,                               -- 期望终态（缺省 = 结构化期望见题集）
    actual_terminal      TEXT,                               -- 实测终态
    error_code           BIGINT,                             -- 业务错误码（拒绝类失败时）
    reason               TEXT,                               -- 失败原因（runner 诊断信息，截断 500）
    created_at           TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE INDEX idx_efs_dims ON evaluation_failure_sample
    (tenant_id, set_id, scenario_entry_mode, query_shape, turn_type, risk_type);
CREATE INDEX idx_efs_run ON evaluation_failure_sample (tenant_id, run_id);

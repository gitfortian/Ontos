-- Ontos System DB：sensitive_query_event 按 §4.8 冻结清单重建（M4/W2/T2.1）
--
-- 旧表（V1）只有 created_at/event_type/fingerprint 三类业务列，与技术选型 §4.8 的
-- 事件字段清单（request_id / sensitive_key / sensitivity_class / scope_hash / filters_hash /
-- result_cardinality / pattern_tag / occurred_at / reason_code / context_delta）不一致，
-- 且 V1 建表至今零写入——按 M4 规格 v1.1 §3.2 直接重建，不做数据迁移。
--
-- 分层口径（§4.8.1 / ADR-0002 / M4 规格 §3.2）：
--   sensitivity_class = pii(L1, 24h/T=8) | existence(L2, 24h/T=4) | label(L3, 7d/T=3)；
--   L0（public/internal 且非存在性敏感）不写事件；
--   同一资产可叠加多层（如 email 兼 pii + existence），每个适用层一行事件，
--   两层窗口独立累计、独立判定（可叠加语义无法用单行表达）。
-- 记账口径（ADR-0002 决策 4）：命中精确差分/个体探针即时动作的请求仍记账；
-- 被拒答的请求未执行查询，不产生本表事件行。

DROP TABLE IF EXISTS sensitive_query_event;

CREATE TABLE sensitive_query_event (
    id                  BIGSERIAL   PRIMARY KEY,
    request_id          TEXT        NOT NULL,
    tenant_id           TEXT        NOT NULL,
    user_id             TEXT        NOT NULL,
    session_id          TEXT,
    -- {asset_id}#{sensitivity_class}，§4.8.1 取数键
    sensitive_key       TEXT        NOT NULL,
    sensitivity_class   TEXT        NOT NULL
                        CHECK (sensitivity_class IN ('pii', 'existence', 'label')),
    -- 授权域哈希（M4 = 用户授权全域）：SHA-256(userId|排序角色集)，与缓存键 userScopeHash 同口径
    scope_hash          TEXT        NOT NULL,
    -- 规范化 filters 三元组集合（field+operator+values 排序后整体序列化）的 SHA-256；无过滤记空集哈希
    filters_hash        TEXT        NOT NULL,
    -- 规范化 filters 序列化明文：ADR-0002 决策 1 差分判定需比对「与上一条事件的 filters 集合差」，
    -- 集合差无法由两个独立 hash 推出，故持久化规范化明文（§4.8 冻结清单之外的实现必需列）
    filters_canonical   TEXT        NOT NULL,
    -- 执行成功后的真实返回行数（ADR-0002 差分对/个体探针判定的输入）
    result_cardinality  INTEGER     NOT NULL,
    -- plain / differential_instant（ADR-0002 决策 1 即时动作）/ individual_probe（决策 2 约束 3）
    pattern_tag         TEXT        NOT NULL,
    occurred_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 对外统一 reason_code（SENSITIVE_QUERY_SEQUENCE）；细分触发分支进审计，不在本表区分对外差异
    reason_code         TEXT,
    -- 计数器哈希化增量（sensitive_query_counters_delta），W3/T3.2 审计补齐时回填语义
    context_delta       TEXT
);

-- user+tenant 级滑动窗口聚合（跨会话规避检测）：(tenant, user, key) + 时间谓词
CREATE INDEX idx_ses_user_window
    ON sensitive_query_event (tenant_id, user_id, sensitive_key, occurred_at DESC);
-- session 级窗口聚合（会话生命周期 = 只按 session_id 过滤，不加时间上界）
CREATE INDEX idx_ses_session_window
    ON sensitive_query_event (tenant_id, user_id, session_id, sensitive_key, occurred_at DESC);
-- 归档索引（M4 规格 §3.2；90 天保留期清理策略归 M8）
CREATE INDEX idx_ses_archive
    ON sensitive_query_event (tenant_id, occurred_at);

COMMENT ON TABLE sensitive_query_event IS '跨轮敏感累计事件（§4.8）：执行成功后同事务记账 + 窗口聚合判定，禁止进程内计数';

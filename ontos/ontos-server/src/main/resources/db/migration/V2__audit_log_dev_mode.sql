-- 审计补充 dev_mode 标记：区分「开发模拟身份」与真实认证身份产生的请求。
-- 背景（M0 审计发现）：local profile 的 dev-headers 回退允许客户端声明任意 demo 身份，
-- 审计若不落标记，事后无法区分开发流量与真实流量，影响回放可信度。
ALTER TABLE audit_log
    ADD COLUMN dev_mode BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN audit_log.dev_mode IS '请求是否来自开发模拟身份（dev-headers 回退）；生产恒为 false';

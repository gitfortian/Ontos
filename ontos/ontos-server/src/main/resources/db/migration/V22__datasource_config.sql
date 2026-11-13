-- Ontos System DB：B2 Phase 2 前半——数据源注册配置表。
--
-- 边界：本表只存「注册数据源的连接配置」；查询执行仍走 application.yml 的当前查询库
-- （datasetId 化执行属 Phase 2 后半，触发点 = 多源执行需求落地）。
-- 凭据安全（ADR-0025）：secret 字段按插件 descriptor 的 secret=true 声明，
-- 从 config 中剥离后以 AES-256-GCM 密文存储（master key 经环境变量注入，永不落库）；
-- config JSONB 只含非敏感字段，明文凭据不进库、不进日志。

CREATE TABLE datasource_config (
    id           TEXT        NOT NULL,
    name         TEXT        NOT NULL,
    -- 插件类型（如 postgresql）；合法性由 DataSourcePluginRegistry 启动期校验，DB 只拦空
    type         TEXT        NOT NULL,
    config       JSONB       NOT NULL,
    -- secret 字段合并 JSON 后的 AES-GCM 密文（Base64(iv||ciphertext)）；无凭据时为 NULL
    secret       TEXT,
    created_by   TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT uk_dc_name UNIQUE (name),
    CONSTRAINT ck_dc_type_not_blank CHECK (type <> ''),
    CONSTRAINT ck_dc_config_object CHECK (jsonb_typeof(config) = 'object')
);

COMMENT ON TABLE datasource_config IS '注册数据源连接配置（B2 Phase 2 前半）；凭据 AES-GCM 密文，master key 环境变量注入';
COMMENT ON COLUMN datasource_config.config IS '非敏感连接字段（按插件 ConfigField 声明）；secret 字段剥离至 secret 列';
COMMENT ON COLUMN datasource_config.secret IS '凭据密文（Base64(iv||ct)），AES-256-GCM；NULL = 该源无凭据字段';

-- Ontos System DB：M6/W2/T2.1 资产管理面建表（M6 规格 §3.0 G-1 / §3.2–§3.4 / G-8）
--
-- 边界：本迁移只建「管理面」生命周期表。运行时（入口路由 / Skill 注册表 / VQR 候选匹配）
-- 一律读已发布清单的 scenarios / skills / vqrs 扩展段，不读这里的草稿；
-- draft / reviewing / retired 资产永远不得被查询运行时读取（与 rule / copy_template 同规）。
--
-- 生命周期与 rules.status 同构：draft → reviewing → published → retired。
-- 回滚不新增状态：以「新版本 draft→published、旧版本 retired」表达，清单不可变、只增版本。

CREATE TABLE scenario_definition (
    id              TEXT        PRIMARY KEY,
    name            TEXT        NOT NULL,
    summary         TEXT        NOT NULL DEFAULT '',
    status          TEXT        NOT NULL DEFAULT 'draft'
                    CHECK (status IN ('draft', 'reviewing', 'published', 'retired')),
    owner           TEXT        NOT NULL,
    reviewer        TEXT,
    version         TEXT        NOT NULL,
    -- 入口模式（§5.7 三值）：仅用户选择 / 可推荐需确认 / 唯一高置信自动生效
    entry_mode      TEXT        NOT NULL DEFAULT 'auto_if_confident'
                    CHECK (entry_mode IN ('manual_only', 'recommend_or_confirm', 'auto_if_confident')),
    -- v1.4 §5.7 全字段中不单独成列的嵌套部分：
    -- ontology_scope / semantic_scope / frozen_calibers / allowed_analysis_modes /
    -- skills / entry_hints / cold_start_samples / version_compatibility / invalidation。
    -- entry_mode 以列值为唯一真源，definition 内不得重复。
    definition      JSONB       NOT NULL,
    published_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 发布时间语义：published 必有；retired 保留原发布时间（审计可回放）；draft/reviewing 必无
    CONSTRAINT ck_scenario_published_at
        CHECK ((status IN ('published', 'retired')) OR published_at IS NULL),
    CONSTRAINT ck_scenario_published_at_required
        CHECK (status <> 'published' OR published_at IS NOT NULL)
);
CREATE INDEX idx_scenario_status ON scenario_definition (status);
COMMENT ON TABLE scenario_definition IS 'Scenario 资产生命周期（管理面）；运行时只读已发布清单 scenarios 段';
COMMENT ON COLUMN scenario_definition.definition IS '§5.7 嵌套字段（scope / frozen_calibers / allowed_analysis_modes / skills / entry_hints / cold_start_samples / version_compatibility / invalidation）；entry_mode 不重复存放';
COMMENT ON COLUMN scenario_definition.published_at IS '发布时间；published 必有、retired 保留、draft/reviewing 必无（清单发布时写入）';

CREATE TABLE skill_definition (
    id                      TEXT        PRIMARY KEY,
    name                    TEXT        NOT NULL,
    summary                 TEXT        NOT NULL DEFAULT '',
    analysis_mode           TEXT        NOT NULL
                            CHECK (analysis_mode IN ('QUERY', 'LOOKUP', 'LOOKUP_BATCH', 'YOY_MOM',
                                                     'FUNNEL', 'RETENTION', 'ATTRIBUTION', 'DISCOVERY')),
    status                  TEXT        NOT NULL DEFAULT 'draft'
                            CHECK (status IN ('draft', 'reviewing', 'published', 'retired')),
    owner                   TEXT        NOT NULL,
    reviewer                TEXT,
    version                 TEXT        NOT NULL,
    -- 只指向应用内审核过的构造器（builtin.<mode>.<impl>@<version>），作者不可自定义
    implementation_ref      TEXT        NOT NULL,
    params_schema_ref       TEXT,
    required_capabilities   JSONB       NOT NULL DEFAULT '[]'::jsonb,
    -- 空数组 = 全局可用；非空 = 只允许列出的 Scenario 路由
    allowed_scenario_ids    JSONB       NOT NULL DEFAULT '[]'::jsonb,
    max_batch_size          INTEGER     CHECK (max_batch_size IS NULL OR max_batch_size >= 1),
    -- 近似降级声明（§3.3）：被近似的原模式，未声明一律拒绝该模式
    approximation_of        TEXT,
    published_at            TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_skill_published_at
        CHECK ((status IN ('published', 'retired')) OR published_at IS NULL),
    CONSTRAINT ck_skill_published_at_required
        CHECK (status <> 'published' OR published_at IS NOT NULL)
);
CREATE INDEX idx_skill_status_mode ON skill_definition (status, analysis_mode);
COMMENT ON TABLE skill_definition IS 'Skill 资产生命周期（管理面）；运行时 SkillRegistry 读清单 skills 段 + implementation_ref 内置映射';
COMMENT ON COLUMN skill_definition.implementation_ref IS '内置实现引用 builtin.<mode>.<impl>@<version>，由注册中心分配，Scenario 作者不可自定义';
COMMENT ON COLUMN skill_definition.required_capabilities IS '受控工具/能力依赖（JSON 数组）；未被注册表满足则拒绝路由';
COMMENT ON COLUMN skill_definition.allowed_scenario_ids IS '允许路由的 Scenario（JSON 数组）；空数组 = 全局可用';

CREATE TABLE vqr_definition (
    id                      TEXT        PRIMARY KEY,
    name                    TEXT        NOT NULL,
    summary                 TEXT        NOT NULL DEFAULT '',
    status                  TEXT        NOT NULL DEFAULT 'draft'
                            CHECK (status IN ('draft', 'reviewing', 'published', 'retired')),
    owner                   TEXT        NOT NULL,
    reviewer                TEXT,
    version                 TEXT        NOT NULL,
    -- 参数化模板：DSL 引用资产 ID，不保存绕过权限的最终 SQL
    question_pattern        TEXT        NOT NULL,
    dsl_template            JSONB       NOT NULL,
    params_schema           JSONB       NOT NULL DEFAULT '{}'::jsonb,
    -- 入库七条件中的可回放证据引用（Golden / 结果校验）
    golden_ref              TEXT,
    result_check_ref        TEXT,
    freshness_limit_hours   INTEGER     CHECK (freshness_limit_hours IS NULL OR freshness_limit_hours >= 1),
    invalidation_conditions JSONB       NOT NULL DEFAULT '[]'::jsonb,
    published_at            TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_vqr_published_at
        CHECK ((status IN ('published', 'retired')) OR published_at IS NULL),
    CONSTRAINT ck_vqr_published_at_required
        CHECK (status <> 'published' OR published_at IS NOT NULL)
);
CREATE INDEX idx_vqr_status ON vqr_definition (status);
COMMENT ON TABLE vqr_definition IS 'VQR 参数化模板资产生命周期（管理面）；运行时只读已发布清单 vqrs 段，命中后仍走八步重校验';
COMMENT ON COLUMN vqr_definition.dsl_template IS '类型化 DSL 模板（引用已发布资产 ID）；禁止保存最终 SQL';
COMMENT ON COLUMN vqr_definition.invalidation_conditions IS '自动失效条件（§11.3）；发布流水线依赖影响分析命中即转失效/待复核';

-- 审计扩展（G-8 / §12.1）：Scenario 五字段 + Skill/VQR 版本 + 近似降级原模式。
-- 全部可空：自由问数与未命中资产的审计行不写这些列。
ALTER TABLE audit_log
    ADD COLUMN scenario_id TEXT,
    ADD COLUMN scenario_version TEXT,
    ADD COLUMN scenario_entry_mode TEXT,
    ADD COLUMN scenario_entry_source TEXT,
    ADD COLUMN scenario_confirm_by_user BOOLEAN,
    ADD COLUMN scenario_recommendation_confidence NUMERIC(5, 4)
        CHECK (scenario_recommendation_confidence IS NULL
               OR (scenario_recommendation_confidence >= 0 AND scenario_recommendation_confidence <= 1)),
    ADD COLUMN skill_id TEXT,
    ADD COLUMN skill_version TEXT,
    ADD COLUMN vqr_id TEXT,
    ADD COLUMN vqr_version TEXT,
    ADD COLUMN approximated_from_mode TEXT;

COMMENT ON COLUMN audit_log.scenario_id IS '命中的 Scenario（未进入场景时为 NULL）';
COMMENT ON COLUMN audit_log.scenario_version IS '命中 Scenario 的版本（清单内版本，非草稿版本）';
COMMENT ON COLUMN audit_log.scenario_entry_mode IS 'manual_only / recommend_or_confirm / auto_if_confident';
COMMENT ON COLUMN audit_log.scenario_entry_source IS '六值：session_inherit / explicit / confirmed_recommendation / auto_confident / cold_start_selected / fallback_free_query';
COMMENT ON COLUMN audit_log.scenario_confirm_by_user IS 'pending_confirm 是否由用户确认（伪造确认在入口被拒，不会落审计）';
COMMENT ON COLUMN audit_log.scenario_recommendation_confidence IS '推荐/自动进入的置信度（0–1），仅推荐路径非空';
COMMENT ON COLUMN audit_log.skill_id IS '本次执行使用的 Skill（未走 Skill 时为 NULL）';
COMMENT ON COLUMN audit_log.skill_version IS 'Skill 版本（与清单 skills 段一致）';
COMMENT ON COLUMN audit_log.vqr_id IS '命中的 VQR（候选集外引用已被完整性闸门拦截）';
COMMENT ON COLUMN audit_log.vqr_version IS 'VQR 版本（与清单 vqrs 段一致）';
COMMENT ON COLUMN audit_log.approximated_from_mode IS '近似降级时用户原请求的 Analysis Mode（MODE_APPROXIMATED 审计行）';

CREATE INDEX idx_audit_scenario ON audit_log (scenario_id) WHERE scenario_id IS NOT NULL;
CREATE INDEX idx_audit_skill ON audit_log (skill_id) WHERE skill_id IS NOT NULL;
CREATE INDEX idx_audit_vqr ON audit_log (vqr_id) WHERE vqr_id IS NOT NULL;

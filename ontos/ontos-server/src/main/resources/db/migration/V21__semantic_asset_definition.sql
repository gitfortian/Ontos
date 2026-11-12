-- Ontos System DB：B1/W1 语义资产草稿表（B1 规格 §3.1.2）
--
-- 边界：本表只服务管理面草稿/评审；运行时仍只读已发布清单的 ontology / semantic 段。
-- draft / reviewing / retired 永远不得被查询运行时读取（与 V15 同规）。
-- 生命周期与 rules.status 同构：draft → reviewing → published → retired。
--
-- 为什么统一表而不像 V15 分表：五类语义资产的差异全部落在 definition 内部，
-- 生命周期字段完全同构；分表会产生五套几乎相同的 repository 与 service。
-- 字段级正确性由 ManifestStructureValidator 的草稿校验与发布校验双重保证。

CREATE TABLE semantic_asset_definition (
    asset_type      TEXT        NOT NULL
                    CHECK (asset_type IN ('concept', 'property', 'relation', 'metric', 'dimension')),
    id              TEXT        NOT NULL,
    name            TEXT        NOT NULL,
    summary         TEXT        NOT NULL DEFAULT '',
    status          TEXT        NOT NULL DEFAULT 'draft'
                    CHECK (status IN ('draft', 'reviewing', 'published', 'retired')),
    owner           TEXT        NOT NULL,
    reviewer        TEXT,
    version         TEXT        NOT NULL,
    -- 乐观锁：每次 saveDraft / 状态迁移 +1；写入必须带 expectedRevision（B1 §3.3.3）
    revision        BIGINT      NOT NULL DEFAULT 0
                    CHECK (revision >= 0),
    -- 资产内容：结构与 ManifestPayload 对应段一致，由 ManifestStructureValidator 校验
    definition      JSONB       NOT NULL,
    published_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (asset_type, id),
    CONSTRAINT ck_sad_published_at
        CHECK ((status IN ('published', 'retired')) OR published_at IS NULL),
    CONSTRAINT ck_sad_published_at_required
        CHECK (status <> 'published' OR published_at IS NOT NULL),
    CONSTRAINT ck_sad_definition_object
        CHECK (jsonb_typeof(definition) = 'object'),
    -- 类型与 ID 前缀必须一致：拦住「类型填 metric、ID 写 dim.x」这类错位。
    -- relation 的 ID 前缀是 ontology.（历史命名，见 B0 影响分析 §2.4；
    -- 不改 ID 以免破坏 v22 引用锚点，故在此显式登记）
    CONSTRAINT ck_sad_id_prefix CHECK (
        (asset_type = 'concept'   AND id LIKE 'concept.%')   OR
        (asset_type = 'property'  AND id LIKE 'property.%')  OR
        (asset_type = 'relation'  AND id LIKE 'ontology.%')  OR
        (asset_type = 'metric'    AND id LIKE 'metric.%')    OR
        (asset_type = 'dimension' AND id LIKE 'dim.%')
    )
);
CREATE INDEX idx_sad_type_status ON semantic_asset_definition (asset_type, status);
CREATE INDEX idx_sad_updated_at ON semantic_asset_definition (updated_at DESC);
COMMENT ON TABLE semantic_asset_definition IS '语义资产草稿/评审生命周期（管理面）；运行时只读已发布清单';
COMMENT ON COLUMN semantic_asset_definition.definition IS '资产内容 JSON（concept/property/relation/metric/dimension 各自结构）；字段级校验由 ManifestStructureValidator 承担';
COMMENT ON COLUMN semantic_asset_definition.revision IS '乐观锁版本；saveDraft 必须携带 expectedRevision，冲突返回 1_005_000_104';
COMMENT ON COLUMN semantic_asset_definition.published_at IS '发布时间；published 必有、retired 保留、draft/reviewing 必无';

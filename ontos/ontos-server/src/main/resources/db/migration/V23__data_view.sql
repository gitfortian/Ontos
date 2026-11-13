-- Ontos System DB：B3 数据视图资产表（B3 规格 §3.1，D1 定档：资源事实即时生效 + 发布快照进清单）。
--
-- 定位：OpenBKN「数据资源知识网络」的 Ontos 对应物——扫描产物固化为资产（列快照 + contentHash），
-- 建模面（Property.viewId）只引用视图资产，不再直持物理表名。
-- 边界：本表只存元数据快照，不存凭据（来源引用 datasource_config，ADR-0025）；
-- 运行时确定性由「合成发布时快照 active 全量进清单」保证，清单不可变口径不变。

CREATE TABLE data_view (
    id                TEXT        NOT NULL,           -- view.<规范化表名>（B3 §3.1 ID 生成规范）
    name              TEXT        NOT NULL,           -- 业务名（必填，UI 主显）
    source_config_id  TEXT,                           -- 来源注册数据源（NULL = 当前查询库）
    source_schema     TEXT        NOT NULL,
    source_table      TEXT        NOT NULL,           -- 物理表名（编译唯一消费点）
    columns_json      JSONB       NOT NULL,           -- 列快照 [{name,type,nullable}]；迁移初始快照类型可为 "unknown"
    content_hash      TEXT        NOT NULL,           -- 列集合+类型 的 SHA-256（漂移检测）
    status            TEXT        NOT NULL DEFAULT 'active'
                      CHECK (status IN ('active', 'retired')),
    created_by        TEXT        NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT ck_dv_id_prefix CHECK (id LIKE 'view.%'),
    CONSTRAINT ck_dv_status_ok CHECK (status <> ''),
    CONSTRAINT ck_dv_columns_object CHECK (jsonb_typeof(columns_json) = 'jsonarray')
);
-- 同源同表唯一：active 视图唯一（重复扫描 = 覆盖更新，B3 §3.1 D1）
CREATE UNIQUE INDEX uk_dv_source ON data_view (source_config_id, source_schema, source_table)
    WHERE status = 'active';
CREATE INDEX idx_dv_status ON data_view (status);

COMMENT ON TABLE data_view IS '数据视图资产（B3）：扫描固化 + 列快照；建模经 Property.viewId 引用，物理表名只在 source_table';
COMMENT ON COLUMN data_view.columns_json IS '列快照 JSON 数组 [{name,type,nullable}]；类型可为 unknown（迁移清单反推），真实扫描后 enrichment';
COMMENT ON COLUMN data_view.content_hash IS '列集合+类型 SHA-256；重扫描 diff 检测依据';

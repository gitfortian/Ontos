-- 资产审核工作台（M1/T2.5）：变更单登记与发布记录。
-- 资产真源在 Git（ontos-assets/，T3.2 起管道消费）；工作台只登记意图与评审结论，
-- 运行时判定只读清单快照，永不读这里的草稿（草稿不得被查询运行时读取，v1.4 §5）。

CREATE TABLE IF NOT EXISTS rule (
    id          TEXT        PRIMARY KEY,
    name        TEXT        NOT NULL,
    summary     TEXT        NOT NULL,
    scope       TEXT        NOT NULL,
    status      TEXT        NOT NULL DEFAULT 'draft',
    reviewer    TEXT,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE rule IS '判定标准审核工作台（草稿态登记）；运行时读清单快照，不读本表';

CREATE TABLE IF NOT EXISTS copy_template (
    template_id TEXT        NOT NULL,
    locale      TEXT        NOT NULL,
    text        TEXT        NOT NULL,
    status      TEXT        NOT NULL DEFAULT 'draft',
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (template_id, locale)
);
COMMENT ON TABLE copy_template IS '对外文案工作台（草稿态登记）；运行时读清单快照，不读本表';

CREATE TABLE IF NOT EXISTS publish_record (
    id              BIGSERIAL     PRIMARY KEY,
    manifest_id     TEXT          NOT NULL,
    content_hash    CHAR(64)      NOT NULL,
    dry_run         BOOLEAN       NOT NULL DEFAULT false,
    source_ref      TEXT,
    published_by    TEXT          NOT NULL,
    published_at    TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_publish_record_manifest ON publish_record (manifest_id);
COMMENT ON TABLE publish_record IS '发布历史（含 dry-run 预检记录），审计可回放';

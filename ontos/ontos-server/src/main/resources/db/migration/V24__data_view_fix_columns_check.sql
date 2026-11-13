-- Ontos System DB：B3 修复迁移——V23 的 ck_dv_columns_object 约束值写错
-- （jsonb_typeof 的合法返回值是 'array'，V23 误写为 'jsonarray'，导致任何合法列快照插入都被拒）。
-- 按「迁移只能追加」纪律：不修改 V23（已入 flyway_schema_history），以 V24 替换约束。

ALTER TABLE data_view DROP CONSTRAINT IF EXISTS ck_dv_columns_object;
ALTER TABLE data_view
    ADD CONSTRAINT ck_dv_columns_object CHECK (jsonb_typeof(columns_json) = 'array');

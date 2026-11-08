-- 查询结果缓存（M2/W4/T4.4，ADR-0008）。
--
-- V1 建表时只有 cache_key / manifest_id / query_shape / result / created_at / expires_at。
-- ADR-0008 决策 1 要求键内含 content_hash，并保留 last_hit_at / hit_count 供失效与观测，
-- 本迁移补齐这三列；expires_at 降级为可空（失效口径改为「按 manifest_id 删 + last_hit_at 超期」，
-- 不再依赖写入时预估的过期时间）。
ALTER TABLE query_cache ADD COLUMN IF NOT EXISTS content_hash TEXT;
ALTER TABLE query_cache ADD COLUMN IF NOT EXISTS last_hit_at TIMESTAMPTZ;
ALTER TABLE query_cache ADD COLUMN IF NOT EXISTS hit_count    BIGINT NOT NULL DEFAULT 0;
ALTER TABLE query_cache ALTER COLUMN expires_at DROP NOT NULL;

-- 失效按 manifest_id 删：必须有索引，否则发布激活后的清理会全表扫
CREATE INDEX IF NOT EXISTS idx_query_cache_manifest ON query_cache (manifest_id);
CREATE INDEX IF NOT EXISTS idx_query_cache_last_hit ON query_cache (last_hit_at);

COMMENT ON COLUMN query_cache.cache_key IS 'SHA-256(规范化 JSON)：技术选型 §4.9 字段全量序列化，缓存键契约见 ADR-0008';
COMMENT ON COLUMN query_cache.content_hash IS '清单内容哈希；键内已含，列上冗余一份供排障与按版本统计';
COMMENT ON COLUMN query_cache.query_shape IS '查询形态（分析模式），仅用于观测与清理统计';
COMMENT ON COLUMN query_cache.last_hit_at IS '最近命中年月日时间；超过保留期由清理任务回收（M2：启动时 + 激活时）';
COMMENT ON COLUMN query_cache.hit_count IS '累计命中次数，供缓存收益观测';

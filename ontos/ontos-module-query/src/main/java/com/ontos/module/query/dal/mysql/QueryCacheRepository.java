package com.ontos.module.query.dal.mysql;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.ontos.module.query.dal.dataobject.QueryCacheDO;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 查询结果缓存仓储（M2/W4/T4.4，ADR-0008）。
 *
 * <p>键内已含 {@code content_hash}，清单升级后旧键天然失配——正确性不依赖本类的清理动作，
 * 清理只做空间回收，因此调用方对清理失败一律降级 WARN。
 */
@Repository
public class QueryCacheRepository {

    private static final String SELECT_FIELDS = """
            SELECT cache_key, manifest_id, content_hash, query_shape, result::text AS result_json,
                   created_at, last_hit_at, hit_count
            FROM query_cache
            """;

    private static final String UPSERT = """
            INSERT INTO query_cache (cache_key, manifest_id, content_hash, query_shape,
                                     result, last_hit_at, hit_count)
            VALUES (:cacheKey, :manifestId, :contentHash, :queryShape,
                    CAST(:result AS jsonb), now(), 1)
            ON CONFLICT (cache_key) DO UPDATE SET
                result = EXCLUDED.result,
                last_hit_at = now(),
                hit_count = query_cache.hit_count + 1
            """;

    private static final RowMapper<QueryCacheDO> ROW_MAPPER = (rs, rowNum) -> {
        QueryCacheDO cache = new QueryCacheDO();
        cache.setCacheKey(rs.getString("cache_key"));
        cache.setManifestId(rs.getString("manifest_id"));
        cache.setContentHash(rs.getString("content_hash"));
        cache.setQueryShape(rs.getString("query_shape"));
        cache.setResultJson(rs.getString("result_json"));
        cache.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        if (rs.getTimestamp("last_hit_at") != null) {
            cache.setLastHitAt(rs.getTimestamp("last_hit_at").toLocalDateTime());
        }
        cache.setHitCount(rs.getLong("hit_count"));
        return cache;
    };

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public QueryCacheRepository(@Qualifier("systemJdbcTemplate") NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<QueryCacheDO> selectByKey(String cacheKey) {
        List<QueryCacheDO> rows = jdbcTemplate.query(
                SELECT_FIELDS + " WHERE cache_key = :cacheKey",
                Map.of("cacheKey", cacheKey), ROW_MAPPER);
        return rows.stream().findFirst();
    }

    public void upsert(String cacheKey, String manifestId, String contentHash, String queryShape,
                       String resultJson) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("cacheKey", cacheKey)
                .addValue("manifestId", manifestId)
                .addValue("contentHash", contentHash)
                .addValue("queryShape", queryShape)
                .addValue("result", resultJson);
        jdbcTemplate.update(UPSERT, params);
    }

    /** 命中计数：只更新统计列，不动结果体，避免命中路径写放大。 */
    public void touch(String cacheKey) {
        jdbcTemplate.update(
                "UPDATE query_cache SET last_hit_at = now(), hit_count = hit_count + 1 "
                        + "WHERE cache_key = :cacheKey", Map.of("cacheKey", cacheKey));
    }

    /**
     * 清单激活后的空间回收（ADR-0008 决策 4）：删除「非当前生效版本」的全部键。
     *
     * <p>按 manifest_id 列删而不是按 cache_key 逐个算：新版本已生效，旧版本的键不可能再命中
     *（键内 content_hash 不同），留着只是占空间。
     */
    public int deleteAllExcept(String activeManifestId) {
        return jdbcTemplate.update(
                "DELETE FROM query_cache WHERE manifest_id <> :activeManifestId",
                Map.of("activeManifestId", activeManifestId));
    }

    /**
     * 回收长期未命中的键；保留期由调用方传入，口径不在 SQL 里硬编码。
     * 用 make_interval(days => ?) 而不是字符串拼 interval：参数有明确整数类型，
     * 不依赖 PG 对「未知类型参数 + || 拼接」的隐式推断。
     */
    public int deleteStale(Duration retention) {
        return jdbcTemplate.update(
                "DELETE FROM query_cache WHERE last_hit_at IS NULL "
                        + "OR last_hit_at < now() - make_interval(days => :days)",
                Map.of("days", (int) Math.max(1, retention.toDays())));
    }

}

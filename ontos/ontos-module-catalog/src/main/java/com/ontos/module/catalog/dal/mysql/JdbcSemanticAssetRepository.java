package com.ontos.module.catalog.dal.mysql;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.ontos.module.catalog.dal.dataobject.SemanticAssetRecord;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 语义资产草稿表读写（System DB）。
 *
 * <p>definition 以 {@code ::text} 读出、以 {@code CAST(:definition AS jsonb)} 写入，
 * 避免驱动对 jsonb 的隐式转换差异。写入统一带 revision 条件，把并发裁决交给数据库。
 */
@Repository
public class JdbcSemanticAssetRepository implements SemanticAssetRepository {

    private static final String SELECT_FIELDS = """
            SELECT asset_type, id, name, summary, status, owner, reviewer, version,
                   revision, definition::text AS definition, published_at, updated_at
            FROM semantic_asset_definition
            """;

    private static final RowMapper<SemanticAssetRecord> ROW_MAPPER = (rs, rowNum) -> {
        SemanticAssetRecord record = new SemanticAssetRecord();
        record.setAssetType(rs.getString("asset_type"));
        record.setId(rs.getString("id"));
        record.setName(rs.getString("name"));
        record.setSummary(rs.getString("summary"));
        record.setStatus(rs.getString("status"));
        record.setOwner(rs.getString("owner"));
        record.setReviewer(rs.getString("reviewer"));
        record.setVersion(rs.getString("version"));
        record.setRevision(rs.getLong("revision"));
        record.setDefinitionJson(rs.getString("definition"));
        Timestamp publishedAt = rs.getTimestamp("published_at");
        record.setPublishedAt(publishedAt == null ? null : publishedAt.toInstant());
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        record.setUpdatedAt(updatedAt == null ? null : updatedAt.toInstant());
        return record;
    };

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public JdbcSemanticAssetRepository(
            @Qualifier("systemJdbcTemplate") NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<SemanticAssetRecord> find(String assetType, String id) {
        Map<String, Object> params = Map.of("assetType", assetType, "id", id);
        List<SemanticAssetRecord> rows = jdbcTemplate.query(
                SELECT_FIELDS + " WHERE asset_type = :assetType AND id = :id", params, ROW_MAPPER);
        return rows.stream().findFirst();
    }

    @Override
    public List<SemanticAssetRecord> findAll(String assetType, String status, String keyword,
                                             int offset, int limit) {
        Map<String, Object> params = new HashMap<>();
        String where = where(assetType, status, keyword, params);
        params.put("limit", limit);
        params.put("offset", offset);
        return jdbcTemplate.query(
                SELECT_FIELDS + where + " ORDER BY updated_at DESC, id LIMIT :limit OFFSET :offset",
                params, ROW_MAPPER);
    }

    @Override
    public long count(String assetType, String status, String keyword) {
        Map<String, Object> params = new HashMap<>();
        String where = where(assetType, status, keyword, params);
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM semantic_asset_definition" + where, params, Long.class);
        return count == null ? 0L : count;
    }

    @Override
    public void insert(SemanticAssetRecord record) {
        String sql = """
                INSERT INTO semantic_asset_definition
                    (asset_type, id, name, summary, status, owner, reviewer, version,
                     revision, definition, published_at)
                VALUES (:assetType, :id, :name, :summary, :status, :owner, :reviewer, :version,
                        :revision, CAST(:definition AS jsonb), :publishedAt)
                """;
        jdbcTemplate.update(sql, params(record));
    }

    @Override
    public int updateDraft(SemanticAssetRecord record, long expectedRevision) {
        String sql = """
                UPDATE semantic_asset_definition
                   SET name = :name, summary = :summary, definition = CAST(:definition AS jsonb),
                       revision = revision + 1, updated_at = now()
                 WHERE asset_type = :assetType AND id = :id
                   AND revision = :expectedRevision AND status = 'draft'
                """;
        Map<String, Object> params = new HashMap<>();
        params.put("assetType", record.getAssetType());
        params.put("id", record.getId());
        params.put("name", record.getName());
        params.put("summary", record.getSummary());
        params.put("definition", record.getDefinitionJson());
        params.put("expectedRevision", expectedRevision);
        return jdbcTemplate.update(sql, params);
    }

    @Override
    public int updateStatus(String assetType, String id, String fromStatus, String toStatus,
                            long expectedRevision) {
        String sql = """
                UPDATE semantic_asset_definition
                   SET status = :toStatus, revision = revision + 1, updated_at = now()
                 WHERE asset_type = :assetType AND id = :id
                   AND status = :fromStatus AND revision = :expectedRevision
                """;
        Map<String, Object> params = new HashMap<>();
        params.put("assetType", assetType);
        params.put("id", id);
        params.put("fromStatus", fromStatus);
        params.put("toStatus", toStatus);
        params.put("expectedRevision", expectedRevision);
        return jdbcTemplate.update(sql, params);
    }

    @Override
    public long findRevision(String assetType, String id) {
        List<Long> rows = jdbcTemplate.query(
                "SELECT revision FROM semantic_asset_definition WHERE asset_type = :assetType AND id = :id",
                Map.of("assetType", assetType, "id", id),
                (rs, rowNum) -> rs.getLong("revision"));
        return rows.isEmpty() ? -1L : rows.get(0);
    }

    private static String where(String assetType, String status, String keyword,
                                Map<String, Object> params) {
        StringBuilder sql = new StringBuilder(" WHERE asset_type = :assetType");
        params.put("assetType", assetType);
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = :status");
            params.put("status", status);
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (id ILIKE :keyword OR name ILIKE :keyword)");
            params.put("keyword", "%" + keyword.trim() + "%");
        }
        return sql.toString();
    }

    private static Map<String, Object> params(SemanticAssetRecord record) {
        if (record.getDefinitionJson() == null || record.getDefinitionJson().isBlank()) {
            throw new IllegalArgumentException("语义资产 definition 不能为空: " + record.getId());
        }
        Map<String, Object> params = new HashMap<>();
        params.put("assetType", record.getAssetType());
        params.put("id", record.getId());
        params.put("name", record.getName());
        params.put("summary", record.getSummary());
        params.put("status", record.getStatus());
        params.put("owner", record.getOwner());
        params.put("reviewer", record.getReviewer());
        params.put("version", record.getVersion());
        params.put("revision", record.getRevision());
        params.put("definition", record.getDefinitionJson());
        Instant publishedAt = record.getPublishedAt();
        params.put("publishedAt", publishedAt == null ? null : Timestamp.from(publishedAt));
        return params;
    }
}

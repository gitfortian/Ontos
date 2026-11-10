package com.ontos.module.catalog.dal.mysql;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.ontos.framework.common.util.json.JsonUtils;
import com.ontos.module.catalog.dal.dataobject.VqrRecord;
import com.ontos.module.ontology.core.ManifestPayload;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * VQR 生命周期表读写（V15 vqr_definition；M6/W3/T3.4）。
 *
 * <p>状态 / 版本 / owner / reviewer / 证据引用以列为唯一真源，写库前同步进 definition
 * （同 {@link JdbcSkillRepository} 纪律）。definition 反序列化复用清单模型，
 * 管理面与清单侧同一套字段。
 */
@Repository
public class JdbcVqrRepository {

    private static final String SELECT_FIELDS = """
            SELECT id, name, summary, status, owner, reviewer, version, question_pattern,
                   dsl_template, params_schema, golden_ref, result_check_ref,
                   freshness_limit_hours, invalidation_conditions, published_at, updated_at
            FROM vqr_definition
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public JdbcVqrRepository(
            @Qualifier("systemJdbcTemplate") NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<VqrRecord> findById(String id) {
        List<VqrRecord> rows = jdbcTemplate.query(SELECT_FIELDS + " WHERE id = :id",
                Map.of("id", id), ROW_MAPPER);
        return rows.stream().findFirst();
    }

    public List<VqrRecord> findAll() {
        return jdbcTemplate.query(SELECT_FIELDS + " ORDER BY id", Map.of(), ROW_MAPPER);
    }

    public void insert(VqrRecord record) {
        String sql = """
                INSERT INTO vqr_definition
                    (id, name, summary, status, owner, reviewer, version, question_pattern,
                     dsl_template, params_schema, golden_ref, result_check_ref,
                     freshness_limit_hours, invalidation_conditions, published_at)
                VALUES (:id, :name, :summary, :status, :owner, :reviewer, :version, :questionPattern,
                        CAST(:dslTemplate AS jsonb), CAST(:paramsSchema AS jsonb),
                        :goldenRef, :resultCheckRef, :freshnessLimitHours,
                        CAST(:invalidations AS jsonb), :publishedAt)
                """;
        jdbcTemplate.update(sql, params(record));
    }

    public void update(VqrRecord record) {
        String sql = """
                UPDATE vqr_definition
                   SET name = :name, summary = :summary, status = :status, owner = :owner,
                       reviewer = :reviewer, version = :version, question_pattern = :questionPattern,
                       dsl_template = CAST(:dslTemplate AS jsonb),
                       params_schema = CAST(:paramsSchema AS jsonb),
                       golden_ref = :goldenRef, result_check_ref = :resultCheckRef,
                       freshness_limit_hours = :freshnessLimitHours,
                       invalidation_conditions = CAST(:invalidations AS jsonb),
                       published_at = :publishedAt, updated_at = now()
                 WHERE id = :id
                """;
        jdbcTemplate.update(sql, params(record));
    }

    private Map<String, Object> params(VqrRecord record) {
        ManifestPayload.VqrMeta definition = record.getDefinition();
        if (definition == null) {
            throw new IllegalArgumentException("VQR definition 不能为空: " + record.getId());
        }
        // 列是唯一真源：写库前同步进 definition（含 reviewer 审核记录）
        definition.setVqrId(record.getId());
        definition.setStatus(record.getStatus());
        definition.setVersion(record.getVersion());
        definition.setOwner(record.getOwner());
        definition.setReviewer(record.getReviewer());
        definition.setQuestionPattern(record.getQuestionPattern());
        definition.setGoldenRef(record.getGoldenRef());
        definition.setResultCheckRef(record.getResultCheckRef());
        definition.setFreshnessLimitHours(record.getFreshnessLimitHours());
        Map<String, Object> params = new HashMap<>();
        params.put("id", record.getId());
        params.put("name", record.getName());
        params.put("summary", record.getSummary());
        params.put("status", record.getStatus());
        params.put("owner", record.getOwner());
        params.put("reviewer", record.getReviewer());
        params.put("version", record.getVersion());
        params.put("questionPattern", record.getQuestionPattern());
        params.put("dslTemplate", JsonUtils.toJsonString(
                record.getDslTemplate() == null ? Map.of() : record.getDslTemplate()));
        params.put("paramsSchema", JsonUtils.toJsonString(
                record.getParamsSchema() == null ? Map.of() : record.getParamsSchema()));
        params.put("goldenRef", record.getGoldenRef());
        params.put("resultCheckRef", record.getResultCheckRef());
        params.put("freshnessLimitHours", record.getFreshnessLimitHours());
        params.put("invalidations", JsonUtils.toJsonString(
                record.getInvalidationConditions() == null
                        ? List.of() : record.getInvalidationConditions()));
        Instant publishedAt = record.getPublishedAt();
        params.put("publishedAt", publishedAt == null ? null : Timestamp.from(publishedAt));
        return params;
    }

    private static final RowMapper<VqrRecord> ROW_MAPPER = (rs, rowNum) -> {
        VqrRecord record = new VqrRecord();
        record.setId(rs.getString("id"));
        record.setName(rs.getString("name"));
        record.setSummary(rs.getString("summary"));
        record.setStatus(rs.getString("status"));
        record.setOwner(rs.getString("owner"));
        record.setReviewer(rs.getString("reviewer"));
        record.setVersion(rs.getString("version"));
        record.setQuestionPattern(rs.getString("question_pattern"));
        record.setDslTemplate(parseObject(rs.getString("dsl_template")));
        record.setParamsSchema(parseObject(rs.getString("params_schema")));
        record.setGoldenRef(rs.getString("golden_ref"));
        record.setResultCheckRef(rs.getString("result_check_ref"));
        int freshness = rs.getInt("freshness_limit_hours");
        record.setFreshnessLimitHours(rs.wasNull() ? null : freshness);
        record.setInvalidationConditions(parseArray(rs.getString("invalidation_conditions")));
        Timestamp publishedAt = rs.getTimestamp("published_at");
        record.setPublishedAt(publishedAt == null ? null : publishedAt.toInstant());
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        record.setUpdatedAt(updatedAt == null ? null : updatedAt.toInstant());
        return record;
    };

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseObject(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        Map<String, Object> parsed = JsonUtils.parseObject(json, Map.class);
        return parsed == null ? Map.of() : parsed;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> parseArray(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        List<Map<String, Object>> parsed = JsonUtils.parseObject(json, List.class);
        return parsed == null ? List.of() : parsed;
    }
}

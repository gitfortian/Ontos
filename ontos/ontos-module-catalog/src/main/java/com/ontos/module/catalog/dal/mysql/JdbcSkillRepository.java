package com.ontos.module.catalog.dal.mysql;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.ontos.framework.common.util.json.JsonUtils;
import com.ontos.module.catalog.dal.dataobject.SkillRecord;
import com.ontos.module.ontology.core.ManifestPayload;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Skill 生命周期表读写（V15 skill_definition；M6/W3/T3.1）。
 *
 * <p>状态 / 版本 / owner / **reviewer** 以列为唯一真源，写库前同步进 definition，
 * 避免列与 JSON 两份值漂移（同 {@link JdbcScenarioRepository} 纪律）。
 * definition 反序列化复用清单模型，管理面与清单侧同一套字段。
 */
@Repository
public class JdbcSkillRepository {

    private static final String SELECT_FIELDS = """
            SELECT id, name, summary, analysis_mode, status, owner, reviewer, version,
                   implementation_ref, params_schema_ref, required_capabilities,
                   allowed_scenario_ids, max_batch_size, approximation_of,
                   published_at, updated_at
            FROM skill_definition
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public JdbcSkillRepository(
            @Qualifier("systemJdbcTemplate") NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<SkillRecord> findById(String id) {
        List<SkillRecord> rows = jdbcTemplate.query(SELECT_FIELDS + " WHERE id = :id",
                Map.of("id", id), ROW_MAPPER);
        return rows.stream().findFirst();
    }

    public List<SkillRecord> findAll() {
        return jdbcTemplate.query(SELECT_FIELDS + " ORDER BY id", Map.of(), ROW_MAPPER);
    }

    public void insert(SkillRecord record) {
        String sql = """
                INSERT INTO skill_definition
                    (id, name, summary, analysis_mode, status, owner, reviewer, version,
                     implementation_ref, params_schema_ref, required_capabilities,
                     allowed_scenario_ids, max_batch_size, approximation_of, published_at)
                VALUES (:id, :name, :summary, :analysisMode, :status, :owner, :reviewer, :version,
                        :implementationRef, :paramsSchemaRef,
                        CAST(:capabilities AS jsonb), CAST(:scenarios AS jsonb),
                        :maxBatchSize, :approximationOf, :publishedAt)
                """;
        jdbcTemplate.update(sql, params(record));
    }

    public void update(SkillRecord record) {
        String sql = """
                UPDATE skill_definition
                   SET name = :name, summary = :summary, analysis_mode = :analysisMode,
                       status = :status, owner = :owner, reviewer = :reviewer, version = :version,
                       implementation_ref = :implementationRef, params_schema_ref = :paramsSchemaRef,
                       required_capabilities = CAST(:capabilities AS jsonb),
                       allowed_scenario_ids = CAST(:scenarios AS jsonb),
                       max_batch_size = :maxBatchSize, approximation_of = :approximationOf,
                       published_at = :publishedAt, updated_at = now()
                 WHERE id = :id
                """;
        jdbcTemplate.update(sql, params(record));
    }

    private Map<String, Object> params(SkillRecord record) {
        ManifestPayload.SkillMeta definition = record.getDefinition();
        if (definition == null) {
            throw new IllegalArgumentException("Skill definition 不能为空: " + record.getId());
        }
        // 列是唯一真源：写库前同步进 definition（含 reviewer 审核记录）
        definition.setSkillId(record.getId());
        definition.setStatus(record.getStatus());
        definition.setVersion(record.getVersion());
        definition.setOwner(record.getOwner());
        definition.setReviewer(record.getReviewer());
        definition.setAnalysisMode(record.getAnalysisMode());
        definition.setImplementationRef(record.getImplementationRef());
        Map<String, Object> params = new HashMap<>();
        params.put("id", record.getId());
        params.put("name", record.getName());
        params.put("summary", record.getSummary());
        params.put("analysisMode", record.getAnalysisMode());
        params.put("status", record.getStatus());
        params.put("owner", record.getOwner());
        params.put("reviewer", record.getReviewer());
        params.put("version", record.getVersion());
        params.put("implementationRef", record.getImplementationRef());
        params.put("paramsSchemaRef", record.getParamsSchemaRef());
        params.put("capabilities", JsonUtils.toJsonString(
                record.getRequiredCapabilities() == null ? List.of() : record.getRequiredCapabilities()));
        params.put("scenarios", JsonUtils.toJsonString(
                record.getAllowedScenarioIds() == null ? List.of() : record.getAllowedScenarioIds()));
        params.put("maxBatchSize", record.getMaxBatchSize());
        params.put("approximationOf", record.getApproximationOf());
        Instant publishedAt = record.getPublishedAt();
        params.put("publishedAt", publishedAt == null ? null : Timestamp.from(publishedAt));
        return params;
    }

    private static final RowMapper<SkillRecord> ROW_MAPPER = (rs, rowNum) -> {
        SkillRecord record = new SkillRecord();
        record.setId(rs.getString("id"));
        record.setName(rs.getString("name"));
        record.setSummary(rs.getString("summary"));
        record.setAnalysisMode(rs.getString("analysis_mode"));
        record.setStatus(rs.getString("status"));
        record.setOwner(rs.getString("owner"));
        record.setReviewer(rs.getString("reviewer"));
        record.setVersion(rs.getString("version"));
        record.setImplementationRef(rs.getString("implementation_ref"));
        record.setParamsSchemaRef(rs.getString("params_schema_ref"));
        record.setRequiredCapabilities(parseArray(rs.getString("required_capabilities")));
        record.setAllowedScenarioIds(parseArray(rs.getString("allowed_scenario_ids")));
        int maxBatch = rs.getInt("max_batch_size");
        record.setMaxBatchSize(rs.wasNull() ? null : maxBatch);
        record.setApproximationOf(rs.getString("approximation_of"));
        Timestamp publishedAt = rs.getTimestamp("published_at");
        record.setPublishedAt(publishedAt == null ? null : publishedAt.toInstant());
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        record.setUpdatedAt(updatedAt == null ? null : updatedAt.toInstant());
        return record;
    };

    private static java.util.List<String> parseArray(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        @SuppressWarnings("unchecked")
        List<String> parsed = JsonUtils.parseObject(json, List.class);
        return parsed == null ? List.of() : parsed;
    }
}

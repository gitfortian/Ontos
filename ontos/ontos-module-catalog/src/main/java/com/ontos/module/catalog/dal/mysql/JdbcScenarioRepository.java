package com.ontos.module.catalog.dal.mysql;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.ontos.framework.common.util.json.JsonUtils;
import com.ontos.module.catalog.dal.dataobject.ScenarioRecord;
import com.ontos.module.ontology.core.ManifestPayload;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Scenario 生命周期表读写（System DB）。
 *
 * <p>`entry_mode` 以列为唯一真源；写库前同步进 definition，避免列与 JSON 两份值漂移。
 * definition 反序列化复用清单模型，保证管理面与清单侧同一套字段。
 */
@Repository
public class JdbcScenarioRepository implements ScenarioRepository {

    private static final String SELECT_FIELDS = """
            SELECT id, name, summary, status, owner, reviewer, version, entry_mode,
                   definition::text AS definition, published_at, updated_at
            FROM scenario_definition
            """;

    private static final RowMapper<ScenarioRecord> ROW_MAPPER = (rs, rowNum) -> {
        ScenarioRecord record = new ScenarioRecord();
        record.setId(rs.getString("id"));
        record.setName(rs.getString("name"));
        record.setSummary(rs.getString("summary"));
        record.setStatus(rs.getString("status"));
        record.setOwner(rs.getString("owner"));
        record.setReviewer(rs.getString("reviewer"));
        record.setVersion(rs.getString("version"));
        record.setEntryMode(rs.getString("entry_mode"));
        record.setDefinition(JsonUtils.parseObject(rs.getString("definition"),
                ManifestPayload.ScenarioMeta.class));
        Timestamp publishedAt = rs.getTimestamp("published_at");
        record.setPublishedAt(publishedAt == null ? null : publishedAt.toInstant());
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        record.setUpdatedAt(updatedAt == null ? null : updatedAt.toInstant());
        return record;
    };

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public JdbcScenarioRepository(
            @Qualifier("systemJdbcTemplate") NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<ScenarioRecord> findById(String id) {
        List<ScenarioRecord> rows = jdbcTemplate.query(SELECT_FIELDS + " WHERE id = :id",
                Map.of("id", id), ROW_MAPPER);
        return rows.stream().findFirst();
    }

    @Override
    public List<ScenarioRecord> findAll() {
        return jdbcTemplate.query(SELECT_FIELDS + " ORDER BY id", Map.of(), ROW_MAPPER);
    }

    @Override
    public void insert(ScenarioRecord record) {
        String sql = """
                INSERT INTO scenario_definition
                    (id, name, summary, status, owner, reviewer, version, entry_mode, definition, published_at)
                VALUES (:id, :name, :summary, :status, :owner, :reviewer, :version, :entryMode,
                        CAST(:definition AS jsonb), :publishedAt)
                """;
        jdbcTemplate.update(sql, params(record));
    }

    @Override
    public void update(ScenarioRecord record) {
        String sql = """
                UPDATE scenario_definition
                   SET name = :name, summary = :summary, status = :status, owner = :owner,
                       reviewer = :reviewer, version = :version, entry_mode = :entryMode,
                       definition = CAST(:definition AS jsonb), published_at = :publishedAt,
                       updated_at = now()
                 WHERE id = :id
                """;
        jdbcTemplate.update(sql, params(record));
    }

    private Map<String, Object> params(ScenarioRecord record) {
        ManifestPayload.ScenarioMeta definition = record.getDefinition();
        if (definition == null) {
            throw new IllegalArgumentException("Scenario definition 不能为空: " + record.getId());
        }
        // 列是唯一真源：写库前覆盖 definition 内的 entryMode，避免两份值不一致
        definition.setEntryMode(record.getEntryMode());
        definition.setStatus(record.getStatus());
        definition.setVersion(record.getVersion());
        definition.setOwner(record.getOwner());
        Map<String, Object> params = new HashMap<>();
        params.put("id", record.getId());
        params.put("name", record.getName());
        params.put("summary", record.getSummary());
        params.put("status", record.getStatus());
        params.put("owner", record.getOwner());
        params.put("reviewer", record.getReviewer());
        params.put("version", record.getVersion());
        params.put("entryMode", record.getEntryMode());
        params.put("definition", JsonUtils.toJsonString(definition));
        Instant publishedAt = record.getPublishedAt();
        params.put("publishedAt", publishedAt == null ? null : Timestamp.from(publishedAt));
        return params;
    }
}

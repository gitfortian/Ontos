package com.ontos.module.query.dal.mysql;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.ontos.module.query.dal.dataobject.QueryArtifactDO;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 编译产物读写（M2/W4/T4.5）。
 *
 * <p>写入是「最近一次覆盖」语义：同一 (tenant, user, request_id) 只保留最后一次编译产物。
 * 读取必须同时按租户与用户收敛——request_id 允许客户端指定，只按 ID 查询会变成跨租户越权入口。
 */
@Repository
public class QueryArtifactRepository {

    private static final String UPSERT = """
            INSERT INTO query_artifact (tenant_id, user_id, request_id, session_id, manifest_id, content_hash,
                                        mode, metric_id, entity_id, sql_text, bind_values, joins, applied_limit,
                                        plan_status, estimated_cost, estimated_rows, plan_json, verified)
            VALUES (:tenantId, :userId, :requestId, :sessionId, :manifestId, :contentHash,
                    :mode, :metricId, :entityId, :sqlText, CAST(:bindValues AS jsonb), CAST(:joins AS jsonb),
                    :appliedLimit, :planStatus, :estimatedCost, :estimatedRows, :planJson, :verified)
            ON CONFLICT (tenant_id, user_id, request_id) DO UPDATE SET
                session_id = EXCLUDED.session_id,
                manifest_id = EXCLUDED.manifest_id,
                content_hash = EXCLUDED.content_hash,
                mode = EXCLUDED.mode,
                metric_id = EXCLUDED.metric_id,
                entity_id = EXCLUDED.entity_id,
                sql_text = EXCLUDED.sql_text,
                bind_values = EXCLUDED.bind_values,
                joins = EXCLUDED.joins,
                applied_limit = EXCLUDED.applied_limit,
                plan_status = EXCLUDED.plan_status,
                estimated_cost = EXCLUDED.estimated_cost,
                estimated_rows = EXCLUDED.estimated_rows,
                plan_json = EXCLUDED.plan_json,
                verified = EXCLUDED.verified,
                updated_at = now()
            """;

    private static final String SELECT_FIELDS = """
            SELECT tenant_id, user_id, request_id, session_id, manifest_id, content_hash, mode,
                   metric_id, entity_id, sql_text, bind_values::text AS bind_values, joins::text AS joins,
                   applied_limit, plan_status, estimated_cost, estimated_rows, plan_json, verified,
                   created_at, updated_at
            FROM query_artifact
            """;

    private static final RowMapper<QueryArtifactDO> ROW_MAPPER = (rs, rowNum) -> {
        QueryArtifactDO artifact = new QueryArtifactDO();
        artifact.setTenantId(rs.getString("tenant_id"));
        artifact.setUserId(rs.getString("user_id"));
        artifact.setRequestId(rs.getString("request_id"));
        artifact.setSessionId(rs.getString("session_id"));
        artifact.setManifestId(rs.getString("manifest_id"));
        artifact.setContentHash(rs.getString("content_hash"));
        artifact.setMode(rs.getString("mode"));
        artifact.setMetricId(rs.getString("metric_id"));
        artifact.setEntityId(rs.getString("entity_id"));
        artifact.setSqlText(rs.getString("sql_text"));
        artifact.setBindValues(rs.getString("bind_values"));
        artifact.setJoins(rs.getString("joins"));
        artifact.setAppliedLimit((Integer) rs.getObject("applied_limit"));
        artifact.setPlanStatus(rs.getString("plan_status"));
        artifact.setEstimatedCost((Double) rs.getObject("estimated_cost"));
        artifact.setEstimatedRows((Long) rs.getObject("estimated_rows"));
        artifact.setPlanJson(rs.getString("plan_json"));
        artifact.setVerified(rs.getBoolean("verified"));
        artifact.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        artifact.setUpdatedAt(rs.getTimestamp("updated_at").toLocalDateTime());
        return artifact;
    };

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public QueryArtifactRepository(@Qualifier("systemJdbcTemplate") NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void upsert(QueryArtifactDO artifact) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", artifact.getTenantId())
                .addValue("userId", artifact.getUserId())
                .addValue("requestId", artifact.getRequestId())
                .addValue("sessionId", artifact.getSessionId())
                .addValue("manifestId", artifact.getManifestId())
                .addValue("contentHash", artifact.getContentHash())
                .addValue("mode", artifact.getMode())
                .addValue("metricId", artifact.getMetricId())
                .addValue("entityId", artifact.getEntityId())
                .addValue("sqlText", artifact.getSqlText())
                .addValue("bindValues", artifact.getBindValues())
                .addValue("joins", artifact.getJoins())
                .addValue("appliedLimit", artifact.getAppliedLimit())
                .addValue("planStatus", artifact.getPlanStatus())
                .addValue("estimatedCost", artifact.getEstimatedCost())
                .addValue("estimatedRows", artifact.getEstimatedRows())
                .addValue("planJson", artifact.getPlanJson())
                .addValue("verified", artifact.isVerified());
        jdbcTemplate.update(UPSERT, params);
    }

    public Optional<QueryArtifactDO> selectByRequest(String tenantId, String userId, String requestId) {
        List<QueryArtifactDO> rows = jdbcTemplate.query(
                SELECT_FIELDS + " WHERE tenant_id = :tenantId AND user_id = :userId AND request_id = :requestId",
                Map.of("tenantId", tenantId, "userId", userId, "requestId", requestId),
                ROW_MAPPER);
        return rows.stream().findFirst();
    }

}

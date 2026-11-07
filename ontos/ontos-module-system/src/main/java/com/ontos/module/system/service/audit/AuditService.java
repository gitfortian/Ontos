package com.ontos.module.system.service.audit;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 业务审计：按 v1.4 12 章独立落 PG {@code audit_log}，不依赖 Studio 看板。
 * 审计写入失败即整单失败（合规优先于可用性）。
 */
@Service
public class AuditService {

    private static final String INSERT_SQL = """
            INSERT INTO audit_log (
                request_id, parent_request_id, sub_intent_id, tenant_id, user_id, session_id,
                turn_index, manifest_id, action, metric_id, dsl, sql_text, row_count, reason_code, trace_id,
                dev_mode
            ) VALUES (
                :requestId, :parentRequestId, :subIntentId, :tenantId, :userId, :sessionId,
                :turnIndex, :manifestId, :action, :metricId, cast(:dsl as jsonb), :sqlText,
                :rowCount, :reasonCode, :traceId, :devMode
            )
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public AuditService(@Qualifier("systemJdbcTemplate") NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void record(AuditLogCreateReqDTO createReqDTO) {
        Map<String, Object> params = new HashMap<>();
        params.put("requestId", createReqDTO.getRequestId());
        params.put("parentRequestId", createReqDTO.getParentRequestId());
        params.put("subIntentId", createReqDTO.getSubIntentId());
        params.put("tenantId", createReqDTO.getTenantId());
        params.put("userId", createReqDTO.getUserId());
        params.put("sessionId", createReqDTO.getSessionId());
        params.put("turnIndex", createReqDTO.getTurnIndex());
        params.put("manifestId", createReqDTO.getManifestId());
        params.put("action", createReqDTO.getAction());
        params.put("metricId", createReqDTO.getMetricId());
        params.put("dsl", createReqDTO.getDslJson());
        params.put("sqlText", createReqDTO.getSqlText());
        params.put("rowCount", createReqDTO.getRowCount());
        params.put("reasonCode", createReqDTO.getReasonCode());
        params.put("traceId", createReqDTO.getTraceId());
        params.put("devMode", Boolean.TRUE.equals(createReqDTO.getDevMode()));
        jdbcTemplate.update(INSERT_SQL, params);
    }

}

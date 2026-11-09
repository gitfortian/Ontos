package com.ontos.module.system.dal.mysql;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.ontos.module.system.dal.dataobject.AuditLogDO;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 审计日志只读查询。写入由 {@link com.ontos.module.system.service.audit.AuditService} 负责。
 */
@Repository
public class AuditLogMapper {

    private static final String SELECT_FIELDS = """
            SELECT id, request_id, parent_request_id, sub_intent_id, tenant_id, user_id,
                   session_id, turn_index, manifest_id, action, metric_id,
                   dsl::text AS dsl, sql_text, row_count, reason_code, trace_id, policy_version,
                   approval_id, sensitive_counters_delta,
                   created_at::text AS created_at
            FROM audit_log
            """;

    private static final RowMapper<AuditLogDO> ROW_MAPPER = (rs, rowNum) -> {
        AuditLogDO item = new AuditLogDO();
        item.setId(rs.getLong("id"));
        item.setRequestId(rs.getString("request_id"));
        item.setParentRequestId(rs.getString("parent_request_id"));
        item.setSubIntentId(rs.getString("sub_intent_id"));
        item.setTenantId(rs.getString("tenant_id"));
        item.setUserId(rs.getString("user_id"));
        item.setSessionId(rs.getString("session_id"));
        item.setTurnIndex((Integer) rs.getObject("turn_index"));
        item.setManifestId(rs.getString("manifest_id"));
        item.setAction(rs.getString("action"));
        item.setMetricId(rs.getString("metric_id"));
        item.setDsl(rs.getString("dsl"));
        item.setSqlText(rs.getString("sql_text"));
        item.setRowCount((Integer) rs.getObject("row_count"));
        item.setReasonCode(rs.getString("reason_code"));
        item.setPolicyVersion(rs.getString("policy_version"));
        item.setApprovalId(rs.getString("approval_id"));
        item.setSensitiveCountersDelta(rs.getString("sensitive_counters_delta"));
        item.setTraceId(rs.getString("trace_id"));
        item.setCreatedAt(rs.getString("created_at"));
        return item;
    };

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public AuditLogMapper(@Qualifier("systemJdbcTemplate") NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<AuditLogDO> selectPage(String tenantId, String action, int limit, int offset) {
        String filter = action == null || action.isBlank() ? "" : " AND action = :action";
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);
        params.put("limit", limit);
        params.put("offset", offset);
        if (!filter.isEmpty()) {
            params.put("action", action);
        }
        return jdbcTemplate.query(
                SELECT_FIELDS + " WHERE tenant_id = :tenantId" + filter
                        + " ORDER BY id DESC LIMIT :limit OFFSET :offset",
                params, ROW_MAPPER);
    }

    public long count(String tenantId, String action) {
        String filter = action == null || action.isBlank() ? "" : " AND action = :action";
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);
        if (!filter.isEmpty()) {
            params.put("action", action);
        }
        Long total = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_log WHERE tenant_id = :tenantId" + filter,
                params, Long.class);
        return total == null ? 0L : total;
    }

}

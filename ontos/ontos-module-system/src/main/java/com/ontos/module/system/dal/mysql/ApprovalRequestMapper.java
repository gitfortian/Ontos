package com.ontos.module.system.dal.mysql;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.ontos.module.system.dal.dataobject.ApprovalRequestDO;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 审批单读写。租户隔离由 tenant_id 强制约束，决策更新带 PENDING 乐观条件。
 */
@Repository
public class ApprovalRequestMapper {

    private static final String SELECT_FIELDS = """
            SELECT id::text AS id, tenant_id, user_id, session_id, turn_index, kind, status,
                   payload::text AS payload, reason, requested_at::text AS requested_at,
                   decided_at::text AS decided_at, decided_by
            FROM approval_request
            """;

    private static final RowMapper<ApprovalRequestDO> ROW_MAPPER = (rs, rowNum) -> {
        ApprovalRequestDO item = new ApprovalRequestDO();
        item.setId(rs.getString("id"));
        item.setTenantId(rs.getString("tenant_id"));
        item.setUserId(rs.getString("user_id"));
        item.setSessionId(rs.getString("session_id"));
        item.setTurnIndex((Integer) rs.getObject("turn_index"));
        item.setKind(rs.getString("kind"));
        item.setStatus(rs.getString("status"));
        item.setPayload(rs.getString("payload"));
        item.setReason(rs.getString("reason"));
        item.setRequestedAt(rs.getString("requested_at"));
        item.setDecidedAt(rs.getString("decided_at"));
        item.setDecidedBy(rs.getString("decided_by"));
        return item;
    };

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public ApprovalRequestMapper(
            @Qualifier("systemJdbcTemplate") NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<ApprovalRequestDO> selectPage(String tenantId, String status, int limit, int offset) {
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);
        params.put("limit", limit);
        params.put("offset", offset);
        String filter = status == null || status.isBlank()
                ? "" : " AND status = :status";
        if (!filter.isEmpty()) {
            params.put("status", status);
        }
        return jdbcTemplate.query(
                SELECT_FIELDS + " WHERE tenant_id = :tenantId" + filter
                        + " ORDER BY requested_at DESC LIMIT :limit OFFSET :offset",
                params, ROW_MAPPER);
    }

    public long count(String tenantId, String status) {
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);
        String filter = status == null || status.isBlank()
                ? "" : " AND status = :status";
        if (!filter.isEmpty()) {
            params.put("status", status);
        }
        Long total = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM approval_request WHERE tenant_id = :tenantId" + filter,
                params, Long.class);
        return total == null ? 0L : total;
    }

    /**
     * @return 受影响行数；0 表示单据不存在、跨租户或已被处理
     */
    public int decide(String id, String tenantId, String decision, String reason, String decidedBy) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", id);
        params.put("tenantId", tenantId);
        params.put("decision", decision);
        params.put("reason", reason);
        params.put("decidedBy", decidedBy);
        return jdbcTemplate.update("""
                UPDATE approval_request
                SET status = :decision,
                    reason = COALESCE(:reason, reason),
                    decided_at = now(),
                    decided_by = :decidedBy
                WHERE id = cast(:id as uuid)
                  AND tenant_id = :tenantId
                  AND status = 'PENDING'
                """, params);
    }

}

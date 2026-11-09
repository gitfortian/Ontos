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
                   decided_at::text AS decided_at, decided_by,
                   grant_fingerprint, grant_consumed_at::text AS grant_consumed_at
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
                params,                 ROW_MAPPER);
    }

    /** 按租户 + ID 精确取单（fingerprint 生成与放行校验的输入）。 */
    public java.util.Optional<ApprovalRequestDO> selectById(String id, String tenantId) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", id);
        params.put("tenantId", tenantId);
        return jdbcTemplate.query(
                SELECT_FIELDS + " WHERE id = cast(:id as uuid) AND tenant_id = :tenantId",
                params, ROW_MAPPER).stream().findFirst();
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
     * 创建审批单（M4/W2 T2.2：敏感累计 / 差分 / 个体探针触发方调用）。
     * 必须在调用方事务内执行（与 sensitive_query_event 记账同事务，保证原子）。
     *
     * @return 生成的审批单 ID（uuid 文本）
     */
    public String insert(String tenantId, String userId, String sessionId, Integer turnIndex,
                         String kind, String payloadJson, String reason) {
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);
        params.put("userId", userId);
        params.put("sessionId", sessionId);
        params.put("turnIndex", turnIndex);
        params.put("kind", kind);
        params.put("payload", payloadJson);
        params.put("reason", reason);
        return jdbcTemplate.queryForObject(
                "INSERT INTO approval_request (tenant_id, user_id, session_id, turn_index, kind, payload, reason)"
                        + " VALUES (:tenantId, :userId, :sessionId, :turnIndex, :kind,"
                        + " cast(:payload as jsonb), :reason)"
                        + " RETURNING id::text",
                params, String.class);
    }

    /**
     * 审批决策（M4/W2/T2.3 增强）：APPROVED 时生成放行目标指纹；强制「决策人 ≠ 申请人」。
     *
     * @return 受影响行数；0 表示单据不存在、跨租户、已被处理或自审批被拒
     */
    public int decide(String id, String tenantId, String decision, String reason, String decidedBy,
                      String grantFingerprint) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", id);
        params.put("tenantId", tenantId);
        params.put("decision", decision);
        params.put("reason", reason);
        params.put("decidedBy", decidedBy);
        params.put("fingerprint", grantFingerprint);
        // 自审批禁止（规格 §3.3）：决策人与申请人同校验，防同一身份既发起又批准
        return jdbcTemplate.update("""
                UPDATE approval_request
                SET status = :decision,
                    reason = COALESCE(:reason, reason),
                    decided_at = now(),
                    decided_by = :decidedBy,
                    grant_fingerprint = CASE WHEN :decision = 'APPROVED' THEN :fingerprint
                                             ELSE grant_fingerprint END
                WHERE id = cast(:id as uuid)
                  AND tenant_id = :tenantId
                  AND status = 'PENDING'
                  AND (:decision = 'REJECTED' OR user_id <> :decidedBy)
                """, params);
    }

    /**
     * 放行令牌单次消费（M4/W2/T2.3）：指纹逐字段比对 + 消费原子置位。
     *
     * @return true=令牌有效且本次消费成功；false=单据不存在 / 已消费 / 指纹不符（换 filters 不放行）
     */
    public boolean consumeGrant(String tenantId, String userId, String approvalId,
                                String fingerprint) {
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);
        params.put("userId", userId);
        params.put("approvalId", approvalId);
        params.put("fingerprint", fingerprint);
        return jdbcTemplate.update("""
                UPDATE approval_request
                SET grant_consumed_at = now()
                WHERE id = cast(:approvalId as uuid)
                  AND tenant_id = :tenantId
                  AND user_id = :userId
                  AND status = 'APPROVED'
                  AND grant_fingerprint = :fingerprint
                  AND grant_consumed_at IS NULL
                """, params) == 1;
    }

}

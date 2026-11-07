package com.ontos.module.system.service.request;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.ontos.framework.web.core.context.RequestContext;
import com.ontos.module.system.service.audit.AuditLogCreateReqDTO;
import com.ontos.module.system.service.audit.AuditService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 请求级状态持久化与迁移（M1/T6.1+T6.3）。
 *
 * <p>职责：状态推进校验（{@link RequestStatus#canTransitionTo}）→ request_state 落库 →
 * 迁移写入 audit_log（from/to/触发原因，可回放）。同一 request_id 的状态是幂等键：
 * 已是目标状态时静默接受（重复推进无害），终态后的其他迁移拒绝。
 */
@Slf4j
@Service
public class RequestStateService {

    private record RequestStateRow(String requestId, String state, Integer rowCount) {
    }

    private static final RowMapper<RequestStateRow> ROW_MAPPER = (rs, rowNum) ->
            new RequestStateRow(rs.getString("request_id"), rs.getString("state"),
                    (Integer) rs.getObject("row_count"));

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final AuditService auditService;

    public RequestStateService(@Qualifier("systemJdbcTemplate") NamedParameterJdbcTemplate jdbcTemplate,
                               AuditService auditService) {
        this.jdbcTemplate = jdbcTemplate;
        this.auditService = auditService;
    }

    public Optional<RequestStatus> findState(String requestId) {
        List<RequestStateRow> rows = jdbcTemplate.query(
                "SELECT request_id, state, row_count FROM request_state WHERE request_id = :requestId",
                Map.of("requestId", requestId), ROW_MAPPER);
        return rows.stream().findFirst().map(row -> RequestStatus.valueOf(row.state()));
    }

    /**
     * 初始化为 RECEIVED（幂等：已存在时不覆盖——重复提交保留原状态）。
     *
     * @return 当前状态（新初始化返回 RECEIVED；已存在返回库中状态）
     */
    public RequestStatus initialize(RequestContext context, String requestId) {
        if (findState(requestId).isPresent()) {
            return findState(requestId).orElseThrow();
        }
        Map<String, Object> params = new HashMap<>();
        params.put("requestId", requestId);
        params.put("tenantId", context.tenantId());
        params.put("userId", context.userId());
        params.put("sessionId", context.sessionId());
        params.put("state", RequestStatus.RECEIVED.name());
        jdbcTemplate.update("""
                INSERT INTO request_state (request_id, tenant_id, user_id, session_id, state)
                VALUES (:requestId, :tenantId, :userId, :sessionId, :state)
                ON CONFLICT (request_id) DO NOTHING
                """, params);
        return RequestStatus.RECEIVED;
    }

    /**
     * 状态推进：校验合法性 → 更新 request_state → 迁移写 audit_log。
     *
     * @return 迁移后的状态
     */
    public RequestStatus transition(RequestContext context, String requestId,
                                    RequestStatus target, String reason) {
        RequestStatus current = findState(requestId).orElse(RequestStatus.RECEIVED);
        if (current == target) {
            return current; // 重复推进同一状态：幂等接受
        }
        if (!current.canTransitionTo(target)) {
            throw new IllegalStateException(
                    "非法状态迁移: " + current + " → " + target + "（request_id=" + requestId + "）");
        }
        Map<String, Object> params = new HashMap<>();
        params.put("requestId", requestId);
        params.put("state", target.name());
        params.put("fromState", current.name());
        int updated = jdbcTemplate.update(
                "UPDATE request_state SET state = :state, updated_at = now() "
                        + "WHERE request_id = :requestId", params);
        if (updated != 1) {
            throw new IllegalStateException("状态推进失败（并发变更）: " + requestId);
        }
        AuditLogCreateReqDTO audit = new AuditLogCreateReqDTO();
        audit.setRequestId(requestId);
        audit.setTenantId(context.tenantId());
        audit.setUserId(context.userId());
        audit.setSessionId(context.sessionId());
        audit.setTurnIndex(context.turnIndex());
        audit.setAction("STATE_TRANSITION");
        audit.setFromState(current.name());
        audit.setToState(target.name());
        audit.setStateReason(reason);
        audit.setTraceId(context.traceId());
        auditService.record(audit);
        log.info("[transition][{}] {} → {} ({})", requestId, current, target, reason);
        return target;
    }

    public void recordRowCount(String requestId, Integer rowCount) {
        jdbcTemplate.update(
                "UPDATE request_state SET row_count = :rowCount, updated_at = now() WHERE request_id = :requestId",
                Map.of("requestId", requestId, "rowCount", rowCount));
    }

}

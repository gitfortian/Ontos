package com.ontos.module.system.service.audit;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import com.ontos.module.system.service.policy.PolicyService;
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
                dev_mode, from_state, to_state, state_reason, policy_version
            ) VALUES (
                :requestId, :parentRequestId, :subIntentId, :tenantId, :userId, :sessionId,
                :turnIndex, :manifestId, :action, :metricId, cast(:dsl as jsonb), :sqlText,
                :rowCount, :reasonCode, :traceId, :devMode, :fromState, :toState, :stateReason, :policyVersion
            )
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final PolicyService policyService;

    public AuditService(@Qualifier("systemJdbcTemplate") NamedParameterJdbcTemplate jdbcTemplate,
                        PolicyService policyService) {
        this.jdbcTemplate = jdbcTemplate;
        this.policyService = policyService;
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
        params.put("fromState", createReqDTO.getFromState());
        params.put("toState", createReqDTO.getToState());
        params.put("stateReason", createReqDTO.getStateReason());
        // 生效策略版本由审计统一解析（规格 v1.1 §3.1「生效版本进审计」）：
        // dev 放行 / 快照缺失容错为 NULL（缺失时授权层早已拒绝，审计不重复报错）
        params.put("policyVersion", policyService.auditVersion(createReqDTO.getTenantId()));
        jdbcTemplate.update(INSERT_SQL, params);
    }

}

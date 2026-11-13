package com.ontos.module.agent.dal.mysql;

import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * ask_feedback 数据访问（W3 结果看板 👍👎）。追加式写入，无更新路径。
 */
@Component
public class AskFeedbackMapper {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public AskFeedbackMapper(
            @Qualifier("systemJdbcTemplate") NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 追加一条反馈；runId 合法性由调用方保证（可空）。返回生成的主键。 */
    public long insert(String tenantId, String userId, String sessionId, String runId,
                       String requestId, String rating, String reasonCode, String comment) {
        Map<String, Object> params = new java.util.HashMap<>();
        params.put("tenantId", tenantId);
        params.put("userId", userId);
        params.put("sessionId", sessionId);
        params.put("runId", runId);
        params.put("requestId", requestId);
        params.put("rating", rating);
        params.put("reasonCode", reasonCode);
        params.put("comment", comment);
        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO ask_feedback (tenant_id, user_id, session_id, run_id, request_id,"
                        + " rating, reason_code, comment)"
                        + " VALUES (:tenantId, :userId, :sessionId,"
                        + " cast(:runId as uuid), :requestId, :rating, :reasonCode, :comment)"
                        + " RETURNING id",
                params, Long.class);
        if (id == null) {
            throw new IllegalStateException("ask_feedback 写入失败");
        }
        return id;
    }
}

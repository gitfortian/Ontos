package com.ontos.module.system.dal.mysql;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.ontos.module.system.dal.dataobject.LlmCallTraceDO;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 模型调用追踪读写（M7/W5/T5.1，V20）：只追加写入 + 按 traceId/最近读取。
 * 所有查询按 tenant_id 过滤（与 V17/V19 同规）。
 */
@Repository
public class LlmCallTraceMapper {

    private static final RowMapper<LlmCallTraceDO> ROW_MAPPER = (rs, rowNum) -> {
        LlmCallTraceDO item = new LlmCallTraceDO();
        item.setId(rs.getLong("id"));
        item.setTenantId(rs.getString("tenant_id"));
        item.setTraceId(rs.getString("trace_id"));
        item.setSessionId(rs.getString("session_id"));
        item.setTurnIndex((Integer) rs.getObject("turn_index"));
        item.setPurpose(rs.getString("purpose"));
        item.setModel(rs.getString("model"));
        item.setInputTokens(rs.getInt("input_tokens"));
        item.setOutputTokens(rs.getInt("output_tokens"));
        item.setTotalTokens(rs.getInt("total_tokens"));
        item.setLatencyMs(rs.getLong("latency_ms"));
        item.setStatus(rs.getString("status"));
        item.setErrorType(rs.getString("error_type"));
        item.setErrorMessage(rs.getString("error_message"));
        item.setFinishReason(rs.getString("finish_reason"));
        item.setCreatedAt(rs.getString("created_at"));
        return item;
    };

    private static final String COLUMNS = """
            id, tenant_id, trace_id, session_id, turn_index, purpose, model,
            input_tokens, output_tokens, total_tokens, latency_ms, status,
            error_type, error_message, finish_reason, created_at::text AS created_at
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public LlmCallTraceMapper(@Qualifier("systemJdbcTemplate") NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 追加一条调用记录（不做 upsert：同一 trace 的多次调用各占一行）。 */
    public void insert(LlmCallTraceDO record) {
        String sql = """
                INSERT INTO llm_call_trace (
                    tenant_id, trace_id, session_id, turn_index, purpose, model,
                    input_tokens, output_tokens, total_tokens, latency_ms, status,
                    error_type, error_message, finish_reason
                ) VALUES (
                    :tenantId, :traceId, :sessionId, :turnIndex, :purpose, :model,
                    :inputTokens, :outputTokens, :totalTokens, :latencyMs, :status,
                    :errorType, :errorMessage, :finishReason
                )
                """;
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("tenantId", record.getTenantId());
        params.put("traceId", record.getTraceId());
        params.put("sessionId", record.getSessionId());
        params.put("turnIndex", record.getTurnIndex());
        params.put("purpose", record.getPurpose());
        params.put("model", record.getModel());
        params.put("inputTokens", record.getInputTokens());
        params.put("outputTokens", record.getOutputTokens());
        params.put("totalTokens", record.getTotalTokens());
        params.put("latencyMs", record.getLatencyMs());
        params.put("status", record.getStatus());
        params.put("errorType", record.getErrorType());
        params.put("errorMessage", record.getErrorMessage());
        params.put("finishReason", record.getFinishReason());
        jdbcTemplate.update(sql, params);
    }

    /** 按 traceId 取一次请求的全部模型调用（时间正序，便于还原调用顺序）。 */
    public List<LlmCallTraceDO> selectByTraceId(String tenantId, String traceId, int limit) {
        String sql = "SELECT " + COLUMNS + " FROM llm_call_trace"
                + " WHERE tenant_id = :tenantId AND trace_id = :traceId"
                + " ORDER BY created_at, id LIMIT :limit";
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("tenantId", tenantId);
        params.put("traceId", traceId);
        params.put("limit", clampLimit(limit));
        return jdbcTemplate.query(sql, params, ROW_MAPPER);
    }

    /** 最近调用（无 traceId 时的观测入口，时间倒序）。 */
    public List<LlmCallTraceDO> selectRecent(String tenantId, int limit) {
        String sql = "SELECT " + COLUMNS + " FROM llm_call_trace"
                + " WHERE tenant_id = :tenantId ORDER BY created_at DESC, id DESC LIMIT :limit";
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("tenantId", tenantId);
        params.put("limit", clampLimit(limit));
        return jdbcTemplate.query(sql, params, ROW_MAPPER);
    }

    static int clampLimit(int limit) {
        return Math.min(Math.max(limit, 1), 200);
    }
}

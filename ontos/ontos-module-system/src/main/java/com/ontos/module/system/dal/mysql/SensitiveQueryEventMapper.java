package com.ontos.module.system.dal.mysql;

import java.util.HashMap;
import java.util.Map;

import com.ontos.module.system.dal.dataobject.SensitiveQueryEventDO;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 敏感累计事件读写：插入（记账）、同层最近一条（差分判定）、两层窗口计数（§4.8.2）。
 * 所有调用都在 {@code SensitiveEventService} 的事务模板内执行，跨副本天然一致。
 */
@Repository
public class SensitiveQueryEventMapper {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public SensitiveQueryEventMapper(
            @Qualifier("systemJdbcTemplate") NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 记账一行（调用方事务内；occurred_at 由 DB now() 生成，保证与窗口谓词同钟）。 */
    public void insert(SensitiveQueryEventDO row) {
        Map<String, Object> params = new HashMap<>();
        params.put("requestId", row.getRequestId());
        params.put("tenantId", row.getTenantId());
        params.put("userId", row.getUserId());
        params.put("sessionId", row.getSessionId());
        params.put("sensitiveKey", row.getSensitiveKey());
        params.put("sensitivityClass", row.getSensitivityClass());
        params.put("scopeHash", row.getScopeHash());
        params.put("filtersHash", row.getFiltersHash());
        params.put("filtersCanonical", row.getFiltersCanonical());
        params.put("resultCardinality", row.getResultCardinality());
        params.put("patternTag", row.getPatternTag());
        params.put("reasonCode", row.getReasonCode());
        jdbcTemplate.update(
                "INSERT INTO sensitive_query_event (request_id, tenant_id, user_id, session_id,"
                        + " sensitive_key, sensitivity_class, scope_hash, filters_hash,"
                        + " filters_canonical, result_cardinality, pattern_tag, reason_code)"
                        + " VALUES (:requestId, :tenantId, :userId, :sessionId, :sensitiveKey,"
                        + " :sensitivityClass, :scopeHash, :filtersHash, :filtersCanonical,"
                        + " :resultCardinality, :patternTag, :reasonCode)",
                params);
    }

    /**
     * 同层（sensitive_key）最近一条已记账事件（user 维度，不带 session 条件——
     * 换 session 不能绕过差分即时动作；ADR-0002 决策 1 / 决策 2 约束 3 的判定输入）。
     */
    public SensitiveQueryEventDO selectLatest(String tenantId, String userId, String sensitiveKey) {
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);
        params.put("userId", userId);
        params.put("sensitiveKey", sensitiveKey);
        return jdbcTemplate.query(
                "SELECT id, request_id, tenant_id, user_id, session_id, sensitive_key,"
                        + " sensitivity_class, scope_hash, filters_hash, filters_canonical,"
                        + " result_cardinality, pattern_tag, occurred_at::text AS occurred_at,"
                        + " reason_code, context_delta"
                        + " FROM sensitive_query_event"
                        + " WHERE tenant_id = :tenantId AND user_id = :userId"
                        + " AND sensitive_key = :sensitiveKey"
                        + " ORDER BY occurred_at DESC, id DESC LIMIT 1",
                params, ROW_MAPPER).stream().findFirst().orElse(null);
    }

    /** session 级窗口计数（会话生命周期 = 只按 session_id 过滤，不加时间上界）。 */
    public int countSessionWindow(String tenantId, String userId, String sessionId,
                                  String sensitiveKey) {
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);
        params.put("userId", userId);
        params.put("sessionId", sessionId);
        params.put("sensitiveKey", sensitiveKey);
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sensitive_query_event"
                        + " WHERE tenant_id = :tenantId AND user_id = :userId"
                        + " AND session_id = :sessionId AND sensitive_key = :sensitiveKey",
                params, Long.class);
        return count == null ? 0 : count.intValue();
    }

    /** user+tenant 级滑动窗口计数：先按窗口谓词过滤再计数（§4.8.2 禁止计数器+清零近似）。 */
    public int countUserWindow(String tenantId, String userId, String sensitiveKey,
                               java.time.Duration window) {
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);
        params.put("userId", userId);
        params.put("sensitiveKey", sensitiveKey);
        params.put("windowSeconds", window.toSeconds());
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sensitive_query_event"
                        + " WHERE tenant_id = :tenantId AND user_id = :userId"
                        + " AND sensitive_key = :sensitiveKey"
                        + " AND occurred_at >= now() - make_interval(secs => :windowSeconds)",
                params, Long.class);
        return count == null ? 0 : count.intValue();
    }

    private static final org.springframework.jdbc.core.RowMapper<SensitiveQueryEventDO> ROW_MAPPER =
            (rs, rowNum) -> {
                SensitiveQueryEventDO row = new SensitiveQueryEventDO();
                row.setId(rs.getLong("id"));
                row.setRequestId(rs.getString("request_id"));
                row.setTenantId(rs.getString("tenant_id"));
                row.setUserId(rs.getString("user_id"));
                row.setSessionId(rs.getString("session_id"));
                row.setSensitiveKey(rs.getString("sensitive_key"));
                row.setSensitivityClass(rs.getString("sensitivity_class"));
                row.setScopeHash(rs.getString("scope_hash"));
                row.setFiltersHash(rs.getString("filters_hash"));
                row.setFiltersCanonical(rs.getString("filters_canonical"));
                row.setResultCardinality((Integer) rs.getObject("result_cardinality"));
                row.setPatternTag(rs.getString("pattern_tag"));
                row.setOccurredAt(rs.getString("occurred_at"));
                row.setReasonCode(rs.getString("reason_code"));
                row.setContextDelta(rs.getString("context_delta"));
                return row;
            };
}

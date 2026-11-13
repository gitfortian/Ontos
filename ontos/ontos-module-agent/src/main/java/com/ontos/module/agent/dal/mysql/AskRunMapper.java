package com.ontos.module.agent.dal.mysql;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.ontos.module.agent.dal.dataobject.AskRunDO;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * ask_run 数据访问。状态迁移 SQL 全部带「期望旧状态」前置条件——
 * 非法迁移在 DB 层就是 0 行，应用层据此 fail-closed（M4 G-4 教训：终态不可回退）。
 */
@Component
public class AskRunMapper {

    private static final RowMapper<AskRunDO> ROW_MAPPER = (rs, rowNum) -> {
        AskRunDO row = new AskRunDO();
        row.setRunId(rs.getString("run_id"));
        row.setTenantId(rs.getString("tenant_id"));
        row.setUserId(rs.getString("user_id"));
        row.setSessionId(rs.getString("session_id"));
        row.setTurnIndex(rs.getInt("turn_index"));
        row.setStatus(rs.getString("status"));
        row.setCreatedAt(rs.getString("created_at"));
        row.setUpdatedAt(rs.getString("updated_at"));
        return row;
    };

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public AskRunMapper(@Qualifier("systemJdbcTemplate") NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<AskRunDO> findById(String runId) {
        List<AskRunDO> rows = jdbcTemplate.query(
                "SELECT run_id::text AS run_id, tenant_id, user_id, session_id, turn_index,"
                        + " status, created_at::text AS created_at, updated_at::text AS updated_at"
                        + " FROM ask_run WHERE run_id = cast(:runId as uuid)",
                Map.of("runId", runId), ROW_MAPPER);
        return rows.stream().findFirst();
    }

    /** 同会话活跃 run（RUNNING / WAITING_APPROVAL）——R3：存在即拒绝新 run。 */
    public List<AskRunDO> findActiveBySession(String tenantId, String userId, String sessionId) {
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);
        params.put("userId", userId);
        params.put("sessionId", sessionId);
        return jdbcTemplate.query(
                "SELECT run_id::text AS run_id, tenant_id, user_id, session_id, turn_index,"
                        + " status, created_at::text AS created_at, updated_at::text AS updated_at"
                        + " FROM ask_run WHERE tenant_id = :tenantId AND user_id = :userId"
                        + " AND session_id = :sessionId AND status IN ('RUNNING', 'WAITING_APPROVAL')"
                        + " ORDER BY created_at DESC",
                params, ROW_MAPPER);
    }

    /** 创建 run（PENDING）：返回生成的 run_id。 */
    public String insert(AskRunDO run) {
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", run.getTenantId());
        params.put("userId", run.getUserId());
        params.put("sessionId", run.getSessionId());
        params.put("turnIndex", run.getTurnIndex());
        List<String> ids = jdbcTemplate.query(
                "INSERT INTO ask_run (tenant_id, user_id, session_id, turn_index, status)"
                        + " VALUES (:tenantId, :userId, :sessionId, :turnIndex, 'PENDING')"
                        + " RETURNING run_id::text",
                params, (rs, rowNum) -> rs.getString(1));
        if (ids.isEmpty()) {
            throw new IllegalStateException("ask_run 创建失败");
        }
        return ids.get(0);
    }

    /**
     * 会话列表（侧栏）：按 session 聚合当前租户/用户的 run，附首条话术作标题。
     * 标题取该会话最近一条 RUN_STARTED 的 utterance（澄清轮 utterance 为空时自然回退到
     * 更早的有话术轮次）；全部为空则由调用方回退 session_id 前缀。
     */
    public List<Map<String, Object>> listSessions(String tenantId, String userId, int limit) {
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);
        params.put("userId", userId);
        params.put("limit", limit);
        return jdbcTemplate.query(
                "WITH scoped AS ("
                        + " SELECT session_id, count(*) AS run_count, max(updated_at) AS last_active_at"
                        + " FROM ask_run WHERE tenant_id = :tenantId AND user_id = :userId"
                        + " GROUP BY session_id"
                        + ") SELECT s.session_id::text AS session_id, s.run_count,"
                        + " s.last_active_at::text AS last_active_at,"
                        + " (SELECT e.payload->'input'->>'utterance' FROM ask_run_event e"
                        + "  JOIN ask_run r ON r.run_id = e.run_id"
                        + "  WHERE r.tenant_id = :tenantId AND r.user_id = :userId"
                        + "   AND r.session_id = s.session_id AND e.type = 'RUN_STARTED'"
                        + "   AND coalesce(e.payload->'input'->>'utterance', '') <> ''"
                        + "  ORDER BY e.created_at DESC, e.seq DESC LIMIT 1) AS title"
                        + " FROM scoped s ORDER BY s.last_active_at DESC LIMIT :limit",
                params, (rs, rowNum) -> {
                    Map<String, Object> row = new HashMap<>();
                    row.put("sessionId", rs.getString("session_id"));
                    row.put("runCount", rs.getInt("run_count"));
                    row.put("lastActiveAt", rs.getString("last_active_at"));
                    String title = rs.getString("title");
                    row.put("title", title == null || title.isBlank()
                            ? "会话 " + rs.getString("session_id").substring(0, 8) : title);
                    return row;
                });
    }

    /**
     * 会话内轮次摘要（侧栏历史展开）：归属裁剪在 WHERE 内；话术取 RUN_STARTED、
     * 终态摘要取最近一条 CUSTOM ontos.terminal（LATERAL 一次取齐 terminal/copy/rowCount）。
     * 只读，不触发任何执行。
     */
    public List<Map<String, Object>> listSessionRuns(String tenantId, String userId,
                                                     String sessionId, int limit) {
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);
        params.put("userId", userId);
        params.put("sessionId", sessionId);
        params.put("limit", limit);
        return jdbcTemplate.query(
                "SELECT r.run_id::text AS run_id, r.turn_index, r.status,"
                        + " r.created_at::text AS created_at,"
                        + " (SELECT e.payload->'input'->>'utterance' FROM ask_run_event e"
                        + "  WHERE e.run_id = r.run_id AND e.type = 'RUN_STARTED'"
                        + "   AND coalesce(e.payload->'input'->>'utterance', '') <> ''"
                        + "  ORDER BY e.created_at DESC, e.seq DESC LIMIT 1) AS utterance,"
                        + " t.payload->'value'->>'terminal' AS terminal,"
                        + " t.payload->'value'->'copy'->>'text' AS summary_text,"
                        + " t.payload->'value'->'body'->>'rowCount' AS row_count"
                        + " FROM ask_run r LEFT JOIN LATERAL ("
                        + "   SELECT e.payload FROM ask_run_event e WHERE e.run_id = r.run_id"
                        + "    AND e.type = 'CUSTOM' AND e.payload->>'name' = 'ontos.terminal'"
                        + "   ORDER BY e.created_at DESC, e.seq DESC LIMIT 1) t ON true"
                        + " WHERE r.tenant_id = :tenantId AND r.user_id = :userId"
                        + "  AND r.session_id = :sessionId"
                        + " ORDER BY r.turn_index ASC, r.created_at ASC LIMIT :limit",
                params, (rs, rowNum) -> {
                    Map<String, Object> row = new HashMap<>();
                    row.put("runId", rs.getString("run_id"));
                    row.put("turnIndex", rs.getInt("turn_index"));
                    row.put("status", rs.getString("status"));
                    row.put("createdAt", rs.getString("created_at"));
                    row.put("utterance", rs.getString("utterance"));
                    row.put("terminal", rs.getString("terminal"));
                    row.put("summaryText", rs.getString("summary_text"));
                    Object rowCount = rs.getObject("row_count");
                    row.put("rowCount", rowCount == null ? null : Integer.valueOf(rowCount.toString()));
                    return row;
                });
    }

    /** 期望旧状态的原子迁移：迁移表校验由服务层完成，这里只做「旧状态匹配才更新」。 */
    public boolean transition(String runId, String fromStatus, String toStatus) {
        Map<String, Object> params = new HashMap<>();
        params.put("runId", runId);
        params.put("from", fromStatus);
        params.put("to", toStatus);
        return jdbcTemplate.update(
                "UPDATE ask_run SET status = :to, updated_at = now()"
                        + " WHERE run_id = cast(:runId as uuid) AND status = :from",
                params) == 1;
    }

    /** 启动扫描（R4）：RUNNING → FAILED（崩溃恢复）。 */
    public int failRunning() {
        return jdbcTemplate.update(
                "UPDATE ask_run SET status = 'FAILED', updated_at = now()"
                        + " WHERE status = 'RUNNING'", Map.of());
    }

    /** 启动扫描（R4）：WAITING_APPROVAL → EXPIRED（审批单仍在 TTL 内有效，用户可携令牌重查）。 */
    public int expireWaiting() {
        return jdbcTemplate.update(
                "UPDATE ask_run SET status = 'EXPIRED', updated_at = now()"
                        + " WHERE status = 'WAITING_APPROVAL'", Map.of());
    }

}

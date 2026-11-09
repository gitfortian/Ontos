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

package com.ontos.module.session.dal.mysql;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.ontos.module.session.dal.dataobject.SessionStateDO;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 会话权威状态读写：三条件（tenant|user|session）收敛，键隔离即跨会话不沿用。
 * turn_index 是乐观锁——run 创建 = turn 占用（UPDATE WHERE turn_index = :seen 原子完成，v1.2 R2）。
 */
@Repository
public class SessionStateMapper {

    private static final String SELECT_FIELDS = """
            SELECT tenant_id, user_id, session_id, turn_index,
                   resolved_entities::text AS resolved_entities,
                   recent_turns::text AS recent_turns,
                   scenario_state::text AS scenario_state,
                   last_terminal,
                   pending_clarification::text AS pending_clarification,
                   updated_at::text AS updated_at
            FROM session_state
            """;

    private static final RowMapper<SessionStateDO> ROW_MAPPER = (rs, rowNum) -> {
        SessionStateDO row = new SessionStateDO();
        row.setTenantId(rs.getString("tenant_id"));
        row.setUserId(rs.getString("user_id"));
        row.setSessionId(rs.getString("session_id"));
        row.setTurnIndex((Integer) rs.getObject("turn_index"));
        row.setResolvedEntities(rs.getString("resolved_entities"));
        row.setRecentTurns(rs.getString("recent_turns"));
        row.setScenarioState(rs.getString("scenario_state"));
        row.setLastTerminal(rs.getString("last_terminal"));
        row.setPendingClarification(rs.getString("pending_clarification"));
        row.setUpdatedAt(rs.getString("updated_at"));
        return row;
    };

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public SessionStateMapper(
            @Qualifier("systemJdbcTemplate") NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 读会话（不存在 = Optional.empty，调用方按空态处理——新会话一律空态起步）。 */
    public Optional<SessionStateDO> select(String tenantId, String userId, String sessionId) {
        Map<String, Object> params = params(tenantId, userId, sessionId);
        return jdbcTemplate.query(
                SELECT_FIELDS + " WHERE tenant_id = :tenantId AND user_id = :userId"
                        + " AND session_id = :sessionId",
                params, ROW_MAPPER).stream().findFirst();
    }

    /** 开会话（不存在才插入，turn_index=0）；幂等。 */
    public void ensure(String tenantId, String userId, String sessionId) {
        Map<String, Object> params = params(tenantId, userId, sessionId);
        jdbcTemplate.update(
                "INSERT INTO session_state (tenant_id, user_id, session_id)"
                        + " VALUES (:tenantId, :userId, :sessionId)"
                        + " ON CONFLICT (tenant_id, user_id, session_id) DO NOTHING",
                params);
    }

    /**
     * run 创建 = turn 占用（原子）：占用成功返回 true；0 行 = 乐观锁冲突（409 语义）。
     * 必须先 {@link #ensure}——占用依赖行存在。
     */
    public boolean occupyTurn(String tenantId, String userId, String sessionId, int expectedTurn) {
        Map<String, Object> params = params(tenantId, userId, sessionId);
        params.put("expectedTurn", expectedTurn);
        return jdbcTemplate.update(
                "UPDATE session_state SET turn_index = turn_index + 1, updated_at = now()"
                        + " WHERE tenant_id = :tenantId AND user_id = :userId"
                        + " AND session_id = :sessionId AND turn_index = :expectedTurn",
                params) == 1;
    }

    /**
     * 实体投影（全量覆盖）+ recent_turns 追加 + last_terminal：
     * 一条 UPDATE 完成（v1.1 F-1：服务端状态 = 本轮已采纳 DSL 的实体投影）。
     * recent_turns 在同一表达式内「裁剪到最近 keep-1 条 + 追加本轮摘要」（保持深度 = keep）。
     *
     * @param projection  本轮投影 JSON（resolved_entities 新值）
     * @param turnSummary 本轮摘要 JSON（追加进 recent_turns，深度由 SQL 裁剪保证）
     * @param terminal    本轮终态
     * @param keepDepth   recent_turns 保留深度（ontos.session.recent-turns，v1.1 F-6）
     */
    public void project(String tenantId, String userId, String sessionId, String projection,
                        String turnSummary, String terminal, int keepDepth) {
        Map<String, Object> params = params(tenantId, userId, sessionId);
        params.put("projection", projection);
        params.put("turnSummary", turnSummary);
        params.put("terminal", terminal);
        params.put("keepOld", Math.max(keepDepth - 1, 0));
        jdbcTemplate.update(
                "UPDATE session_state SET"
                        + " resolved_entities = cast(:projection as jsonb),"
                        + " recent_turns = (SELECT coalesce(jsonb_agg(entry), '[]'::jsonb) FROM ("
                        + "     SELECT entry FROM jsonb_array_elements(recent_turns)"
                        + "       WITH ORDINALITY AS t(entry, ord)"
                        + "     ORDER BY ord DESC LIMIT :keepOld) latest)"
                        + "   || cast(:turnSummary as jsonb),"
                        + " last_terminal = :terminal, updated_at = now()"
                        + " WHERE tenant_id = :tenantId AND user_id = :userId"
                        + " AND session_id = :sessionId",
                params);
    }

    /** 保存澄清候选（覆盖写——新一轮澄清替换旧的未答候选）。 */
    public void savePendingClarification(String tenantId, String userId, String sessionId,
                                         String clarificationJson) {
        Map<String, Object> params = params(tenantId, userId, sessionId);
        params.put("clarification", clarificationJson);
        jdbcTemplate.update(
                "UPDATE session_state SET pending_clarification = cast(:clarification as jsonb),"
                        + " updated_at = now()"
                        + " WHERE tenant_id = :tenantId AND user_id = :userId"
                        + " AND session_id = :sessionId",
                params);
    }

    /**
     * 澄清选择消费（原子）：candidateId 属于当前 pending_clarification 才清除并返回 true——
     * 伪造 candidateId（不在候选集内）返回 false，不能绕过澄清（v1.2 R5）。
     */
    public boolean consumeClarificationChoice(String tenantId, String userId, String sessionId,
                                              String candidateId) {
        Map<String, Object> params = params(tenantId, userId, sessionId);
        params.put("candidateId", candidateId);
        return jdbcTemplate.update(
                "UPDATE session_state SET pending_clarification = NULL, updated_at = now()"
                        + " WHERE tenant_id = :tenantId AND user_id = :userId"
                        + " AND session_id = :sessionId"
                        + " AND pending_clarification IS NOT NULL"
                        + " AND pending_clarification->'candidates' @>"
                        + "   to_jsonb(jsonb_build_array(jsonb_build_object('candidateId', :candidateId)))",
                params) == 1;
    }

    private static Map<String, Object> params(String tenantId, String userId, String sessionId) {
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);
        params.put("userId", userId);
        params.put("sessionId", sessionId);
        return params;
    }
}

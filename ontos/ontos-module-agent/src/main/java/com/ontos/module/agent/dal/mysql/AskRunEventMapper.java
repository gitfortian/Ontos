package com.ontos.module.agent.dal.mysql;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * ask_run_event 数据访问：run 事件的持久化（单调 seq）与 afterSeq 只读重放（规格 §3.5）。
 */
@Component
public class AskRunEventMapper {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public AskRunEventMapper(@Qualifier("systemJdbcTemplate") NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 追加事件：seq 取当前最大值 +1（同 run 串行写入由编排单线程保证）。 */
    public long append(String runId, String type, String payloadJson) {
        Map<String, Object> params = new HashMap<>();
        params.put("runId", runId);
        params.put("type", type);
        params.put("payload", payloadJson);
        List<Long> seqs = jdbcTemplate.query(
                "INSERT INTO ask_run_event (run_id, seq, type, payload)"
                        + " SELECT cast(:runId as uuid), coalesce(max(seq), 0) + 1, :type,"
                        + " cast(:payload as jsonb) FROM ask_run_event WHERE run_id = cast(:runId as uuid)"
                        + " RETURNING seq",
                params, (rs, rowNum) -> rs.getLong(1));
        if (seqs.isEmpty()) {
            // run 的第一条事件（max 为空时 coalesce 已处理，理论上不会到这里）
            throw new IllegalStateException("ask_run_event 追加失败: run=" + runId);
        }
        return seqs.get(0);
    }

    /** afterSeq 只读重放（断线重连）：seq 严格大于 after 的全部事件，按 seq 升序。 */
    public List<Map<String, Object>> replayAfter(String runId, long afterSeq) {
        Map<String, Object> params = new HashMap<>();
        params.put("runId", runId);
        params.put("afterSeq", afterSeq);
        return jdbcTemplate.query(
                "SELECT seq, type, payload::text AS payload, created_at::text AS created_at"
                        + " FROM ask_run_event WHERE run_id = cast(:runId as uuid) AND seq > :afterSeq"
                        + " ORDER BY seq",
                params, (rs, rowNum) -> {
                    Map<String, Object> event = new HashMap<>();
                    event.put("seq", rs.getLong("seq"));
                    event.put("type", rs.getString("type"));
                    event.put("payload", rs.getString("payload"));
                    event.put("createdAt", rs.getString("created_at"));
                    return event;
                });
    }

    public long count(String runId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ask_run_event WHERE run_id = cast(:runId as uuid)",
                Map.of("runId", runId), Long.class);
        return count == null ? 0 : count;
    }

    /**
     * 保留策略清理（G-5，T5.3）：删除早于 cutoff 的事件行。
     * 默认策略（retentionDays=0）为永久保留，本方法不被调用（与 audit_log 同规）。
     *
     * @return 删除行数
     */
    public long deleteOlderThan(java.time.OffsetDateTime cutoff) {
        return jdbcTemplate.update(
                "DELETE FROM ask_run_event WHERE created_at < :cutoff",
                Map.of("cutoff", cutoff));
    }

}

package com.ontos.module.system.dal.mysql;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.ontos.module.system.dal.dataobject.EvaluationFailureSampleDO;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 评测失败样本读写（M6/W5/T5.1）。只追加写入 + 五维下钻聚合（G-5）：
 * {@code scenario_entry_mode / query_shape / turn_type / is_composite / risk_type}。
 */
@Repository
public class EvaluationFailureSampleMapper {

    private static final RowMapper<EvaluationFailureSampleDO> ROW_MAPPER = (rs, rowNum) -> {
        EvaluationFailureSampleDO item = new EvaluationFailureSampleDO();
        item.setId(rs.getLong("id"));
        item.setTenantId(rs.getString("tenant_id"));
        item.setSetId(rs.getString("set_id"));
        item.setQuestionId(rs.getString("question_id"));
        item.setRunId(rs.getString("run_id"));
        item.setScenarioEntryMode(rs.getString("scenario_entry_mode"));
        item.setQueryShape(rs.getString("query_shape"));
        item.setTurnType(rs.getString("turn_type"));
        item.setIsComposite((Boolean) rs.getObject("is_composite"));
        item.setRiskType(rs.getString("risk_type"));
        item.setExpectedTerminal(rs.getString("expected_terminal"));
        item.setActualTerminal(rs.getString("actual_terminal"));
        item.setErrorCode((Long) rs.getObject("error_code"));
        item.setReason(rs.getString("reason"));
        item.setCreatedAt(rs.getString("created_at"));
        return item;
    };

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public EvaluationFailureSampleMapper(
            @Qualifier("systemJdbcTemplate") NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 批量登记（只追加）；单批条数上限由 service 层约束。 */
    public int insertBatch(String tenantId, List<EvaluationFailureSampleDO> samples) {
        if (samples.isEmpty()) {
            return 0;
        }
        String sql = """
                INSERT INTO evaluation_failure_sample (
                    tenant_id, set_id, question_id, run_id,
                    scenario_entry_mode, query_shape, turn_type, is_composite, risk_type,
                    expected_terminal, actual_terminal, error_code, reason
                ) VALUES (
                    :tenantId, :setId, :questionId, :runId,
                    :scenarioEntryMode, :queryShape, :turnType, :isComposite, :riskType,
                    :expectedTerminal, :actualTerminal, :errorCode, :reason
                )
                """;
        Map<String, Object>[] batchParams = samples.stream().map(sample -> {
            Map<String, Object> params = new HashMap<String, Object>();
            params.put("tenantId", tenantId);
            params.put("setId", sample.getSetId());
            params.put("questionId", sample.getQuestionId());
            params.put("runId", sample.getRunId());
            params.put("scenarioEntryMode", sample.getScenarioEntryMode());
            params.put("queryShape", sample.getQueryShape());
            params.put("turnType", sample.getTurnType());
            params.put("isComposite", sample.getIsComposite());
            params.put("riskType", sample.getRiskType());
            params.put("expectedTerminal", sample.getExpectedTerminal());
            params.put("actualTerminal", sample.getActualTerminal());
            params.put("errorCode", sample.getErrorCode());
            params.put("reason", truncate(sample.getReason()));
            return params;
        }).toArray(Map[]::new);
        int[] results = jdbcTemplate.batchUpdate(sql, batchParams);
        int inserted = 0;
        for (int result : results) {
            inserted += result;
        }
        return inserted;
    }

    /** 单维下钻聚合：dimension ∈ 五维白名单（调用方约束），返回 value → count 降序。 */
    public List<Map<String, Object>> drillDown(String tenantId, String setId, String dimensionColumn) {
        if (!DRILL_DIMENSIONS.contains(dimensionColumn)) {
            throw new IllegalArgumentException("非法下钻维度: " + dimensionColumn);
        }
        String sql = "SELECT " + dimensionColumn + " AS value, count(*) AS count FROM evaluation_failure_sample"
                + " WHERE tenant_id = :tenantId AND set_id = :setId"
                + " GROUP BY " + dimensionColumn + " ORDER BY count DESC, value";
        Map<String, Object> params = Map.of("tenantId", tenantId, "setId", setId);
        return jdbcTemplate.queryForList(sql, params);
    }

    public long count(String tenantId, String setId) {
        String sql = "SELECT count(*) FROM evaluation_failure_sample"
                + " WHERE tenant_id = :tenantId AND set_id = :setId";
        Long count = jdbcTemplate.queryForObject(sql,
                Map.of("tenantId", tenantId, "setId", setId), Long.class);
        return count == null ? 0 : count;
    }

    /** 最近样本（看板明细区，时间倒序）。 */
    public List<EvaluationFailureSampleDO> selectRecent(String tenantId, String setId, int limit) {
        String sql = """
                SELECT id, tenant_id, set_id, question_id, run_id,
                       scenario_entry_mode, query_shape, turn_type, is_composite, risk_type,
                       expected_terminal, actual_terminal, error_code, reason,
                       created_at::text AS created_at
                FROM evaluation_failure_sample
                WHERE tenant_id = :tenantId AND set_id = :setId
                ORDER BY created_at DESC, id DESC
                LIMIT :limit
                """;
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("tenantId", tenantId);
        params.put("setId", setId);
        params.put("limit", Math.min(Math.max(limit, 1), 200));
        return jdbcTemplate.query(sql, params, ROW_MAPPER);
    }

    /** 下钻维度白名单（§12.3 / G-5 五维）。 */
    public static final List<String> DRILL_DIMENSIONS = List.of(
            "scenario_entry_mode", "query_shape", "turn_type", "is_composite", "risk_type");

    private static String truncate(String text) {
        if (text == null || text.length() <= 500) {
            return text;
        }
        return text.substring(0, 500);
    }

}

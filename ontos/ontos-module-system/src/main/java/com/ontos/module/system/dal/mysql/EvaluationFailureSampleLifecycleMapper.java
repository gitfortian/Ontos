package com.ontos.module.system.dal.mysql;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.ontos.module.system.dal.dataobject.EvaluationFailureSampleClassificationDO;
import com.ontos.module.system.dal.dataobject.EvaluationFailureSampleStatusEventDO;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 失败样本生命周期事件读写（M7/W4/T4.1–T4.2，V19）。
 *
 * <p>只追加：状态事件与分类事件均不更新、不删除；当前态由最新事件派生。
 * 所有查询按 tenant_id 过滤（与 V17 同规）。
 */
@Repository
public class EvaluationFailureSampleLifecycleMapper {

    private static final RowMapper<EvaluationFailureSampleStatusEventDO> STATUS_ROW_MAPPER =
            (rs, rowNum) -> {
                EvaluationFailureSampleStatusEventDO item = new EvaluationFailureSampleStatusEventDO();
                item.setId(rs.getLong("id"));
                item.setTenantId(rs.getString("tenant_id"));
                item.setSampleId(rs.getLong("sample_id"));
                item.setFromStatus(rs.getString("from_status"));
                item.setToStatus(rs.getString("to_status"));
                item.setActor(rs.getString("actor"));
                item.setNote(rs.getString("note"));
                item.setCreatedAt(rs.getString("created_at"));
                return item;
            };

    private static final RowMapper<EvaluationFailureSampleClassificationDO> CLASS_ROW_MAPPER =
            (rs, rowNum) -> {
                EvaluationFailureSampleClassificationDO item =
                        new EvaluationFailureSampleClassificationDO();
                item.setId(rs.getLong("id"));
                item.setTenantId(rs.getString("tenant_id"));
                item.setSampleId(rs.getLong("sample_id"));
                item.setCategory(rs.getString("category"));
                item.setActor(rs.getString("actor"));
                item.setNote(rs.getString("note"));
                item.setCreatedAt(rs.getString("created_at"));
                return item;
            };

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public EvaluationFailureSampleLifecycleMapper(
            @Qualifier("systemJdbcTemplate") NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 追加状态事件（from 可为空 = 初始 open）。 */
    public void insertStatusEvent(String tenantId, long sampleId, String fromStatus,
                                  String toStatus, String actor, String note) {
        String sql = """
                INSERT INTO evaluation_failure_sample_status_event
                    (tenant_id, sample_id, from_status, to_status, actor, note)
                VALUES (:tenantId, :sampleId, :fromStatus, :toStatus, :actor, :note)
                """;
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("tenantId", tenantId);
        params.put("sampleId", sampleId);
        params.put("fromStatus", fromStatus);
        params.put("toStatus", toStatus);
        params.put("actor", actor);
        params.put("note", truncate(note));
        jdbcTemplate.update(sql, params);
    }

    /** 追加分类事件。 */
    public void insertClassification(String tenantId, long sampleId, String category,
                                     String actor, String note) {
        String sql = """
                INSERT INTO evaluation_failure_sample_classification
                    (tenant_id, sample_id, category, actor, note)
                VALUES (:tenantId, :sampleId, :category, :actor, :note)
                """;
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("tenantId", tenantId);
        params.put("sampleId", sampleId);
        params.put("category", category);
        params.put("actor", actor);
        params.put("note", truncate(note));
        jdbcTemplate.update(sql, params);
    }

    /** 最新状态码；无事件返回 null（调用方按 OPEN 处理）。 */
    public String selectLatestStatus(String tenantId, long sampleId) {
        String sql = """
                SELECT to_status FROM evaluation_failure_sample_status_event
                WHERE tenant_id = :tenantId AND sample_id = :sampleId
                ORDER BY created_at DESC, id DESC
                LIMIT 1
                """;
        List<String> rows = jdbcTemplate.queryForList(sql,
                Map.of("tenantId", tenantId, "sampleId", sampleId), String.class);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 最新根因分类；未分类返回 null。 */
    public String selectLatestCategory(String tenantId, long sampleId) {
        String sql = """
                SELECT category FROM evaluation_failure_sample_classification
                WHERE tenant_id = :tenantId AND sample_id = :sampleId
                ORDER BY created_at DESC, id DESC
                LIMIT 1
                """;
        List<String> rows = jdbcTemplate.queryForList(sql,
                Map.of("tenantId", tenantId, "sampleId", sampleId), String.class);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 状态事件历史（时间倒序，limit 上限 200）。 */
    public List<EvaluationFailureSampleStatusEventDO> selectStatusEvents(
            String tenantId, long sampleId, int limit) {
        String sql = """
                SELECT id, tenant_id, sample_id, from_status, to_status, actor, note,
                       created_at::text AS created_at
                FROM evaluation_failure_sample_status_event
                WHERE tenant_id = :tenantId AND sample_id = :sampleId
                ORDER BY created_at DESC, id DESC
                LIMIT :limit
                """;
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("tenantId", tenantId);
        params.put("sampleId", sampleId);
        params.put("limit", Math.min(Math.max(limit, 1), 200));
        return jdbcTemplate.query(sql, params, STATUS_ROW_MAPPER);
    }

    /** 分类事件历史（时间倒序，limit 上限 200）。 */
    public List<EvaluationFailureSampleClassificationDO> selectClassifications(
            String tenantId, long sampleId, int limit) {
        String sql = """
                SELECT id, tenant_id, sample_id, category, actor, note,
                       created_at::text AS created_at
                FROM evaluation_failure_sample_classification
                WHERE tenant_id = :tenantId AND sample_id = :sampleId
                ORDER BY created_at DESC, id DESC
                LIMIT :limit
                """;
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("tenantId", tenantId);
        params.put("sampleId", sampleId);
        params.put("limit", Math.min(Math.max(limit, 1), 200));
        return jdbcTemplate.query(sql, params, CLASS_ROW_MAPPER);
    }

    private static String truncate(String text) {
        if (text == null || text.length() <= 500) {
            return text;
        }
        return text.substring(0, 500);
    }
}

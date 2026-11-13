package com.ontos.module.datasource.dal.mysql;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.ontos.module.datasource.dal.dataobject.DataViewRecord;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;

@Repository
public class JdbcDataViewRepository implements DataViewRepository {

    private static final String SELECT_FIELDS =
            "SELECT id, name, source_config_id, source_schema, source_table, "
                    + "columns_json::text AS columns_json, content_hash, status, created_by, "
                    + "created_at, updated_at FROM data_view";

    private static final RowMapper<DataViewRecord> ROW_MAPPER = (rs, rowNum) -> {
        DataViewRecord record = new DataViewRecord();
        record.setId(rs.getString("id"));
        record.setName(rs.getString("name"));
        record.setSourceConfigId(rs.getString("source_config_id"));
        record.setSourceSchema(rs.getString("source_schema"));
        record.setSourceTable(rs.getString("source_table"));
        record.setColumnsJson(rs.getString("columns_json"));
        record.setContentHash(rs.getString("content_hash"));
        record.setStatus(rs.getString("status"));
        record.setCreatedBy(rs.getString("created_by"));
        record.setCreatedAt(rs.getObject("created_at", OffsetDateTime.class));
        record.setUpdatedAt(rs.getObject("updated_at", OffsetDateTime.class));
        return record;
    };

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public JdbcDataViewRepository(
            @Qualifier("systemJdbcTemplate") NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<DataViewRecord> find(String id) {
        List<DataViewRecord> rows = jdbcTemplate.query(
                SELECT_FIELDS + " WHERE id = :id", Map.of("id", id), ROW_MAPPER);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public Optional<DataViewRecord> findActiveBySource(String sourceConfigId, String sourceSchema,
                                                       String sourceTable) {
        String sql = SELECT_FIELDS + " WHERE status = 'active' AND source_config_id "
                + (sourceConfigId == null ? "IS NULL" : "= :configId")
                + " AND source_schema = :schema AND source_table = :table";
        Map<String, Object> params = new HashMap<>();
        if (sourceConfigId != null) {
            params.put("configId", sourceConfigId);
        }
        params.put("schema", sourceSchema);
        params.put("table", sourceTable);
        List<DataViewRecord> rows = jdbcTemplate.query(sql, params, ROW_MAPPER);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public List<DataViewRecord> findAllActive() {
        return jdbcTemplate.query(
                SELECT_FIELDS + " WHERE status = 'active' ORDER BY id", Map.of(), ROW_MAPPER);
    }

    @Override
    public List<DataViewRecord> findAll() {
        return jdbcTemplate.query(SELECT_FIELDS + " ORDER BY id", Map.of(), ROW_MAPPER);
    }

    @Override
    public void insert(DataViewRecord record) {
        String sql = """
                INSERT INTO data_view
                    (id, name, source_config_id, source_schema, source_table, columns_json,
                     content_hash, status, created_by)
                VALUES (:id, :name, :configId, :schema, :table, CAST(:columns AS jsonb),
                        :hash, :status, :createdBy)
                """;
        Map<String, Object> params = new HashMap<>();
        params.put("id", record.getId());
        params.put("name", record.getName());
        params.put("configId", record.getSourceConfigId());
        params.put("schema", record.getSourceSchema());
        params.put("table", record.getSourceTable());
        params.put("columns", record.getColumnsJson());
        params.put("hash", record.getContentHash());
        params.put("status", record.getStatus());
        params.put("createdBy", record.getCreatedBy());
        jdbcTemplate.update(sql, params);
    }

    @Override
    public int updateContent(String id, String name, String columnsJson, String contentHash) {
        String sql = """
                UPDATE data_view
                   SET name = :name, columns_json = CAST(:columns AS jsonb),
                       content_hash = :hash, updated_at = now()
                 WHERE id = :id
                """;
        Map<String, Object> params = new HashMap<>();
        params.put("id", id);
        params.put("name", name);
        params.put("columns", columnsJson);
        params.put("hash", contentHash);
        return jdbcTemplate.update(sql, params);
    }

    @Override
    public int updateStatus(String id, String fromStatus, String toStatus) {
        String sql = """
                UPDATE data_view SET status = :toStatus, updated_at = now()
                 WHERE id = :id AND status = :fromStatus
                """;
        Map<String, Object> params = new HashMap<>();
        params.put("id", id);
        params.put("toStatus", toStatus);
        params.put("fromStatus", fromStatus);
        return jdbcTemplate.update(sql, params);
    }
}

package com.ontos.module.datasource.dal.mysql;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.ontos.module.datasource.dal.dataobject.DatasourceConfigRecord;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.UUID;

@Repository
public class JdbcDatasourceConfigRepository implements DatasourceConfigRepository {

    private static final String SELECT_FIELDS =
            "SELECT id, name, type, config::text AS config_json, secret, created_by, created_at, updated_at "
                    + "FROM datasource_config";

    private static final RowMapper<DatasourceConfigRecord> ROW_MAPPER = (rs, rowNum) -> {
        DatasourceConfigRecord record = new DatasourceConfigRecord();
        record.setId(rs.getString("id"));
        record.setName(rs.getString("name"));
        record.setType(rs.getString("type"));
        record.setConfigJson(rs.getString("config_json"));
        record.setSecret(rs.getString("secret"));
        record.setCreatedBy(rs.getString("created_by"));
        record.setCreatedAt(rs.getObject("created_at", OffsetDateTime.class));
        record.setUpdatedAt(rs.getObject("updated_at", OffsetDateTime.class));
        return record;
    };

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public JdbcDatasourceConfigRepository(
            @Qualifier("systemJdbcTemplate") NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<DatasourceConfigRecord> find(String id) {
        List<DatasourceConfigRecord> rows = jdbcTemplate.query(
                SELECT_FIELDS + " WHERE id = :id", Map.of("id", id), ROW_MAPPER);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public Optional<DatasourceConfigRecord> findByName(String name) {
        List<DatasourceConfigRecord> rows = jdbcTemplate.query(
                SELECT_FIELDS + " WHERE name = :name", Map.of("name", name), ROW_MAPPER);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public List<DatasourceConfigRecord> findAll() {
        return jdbcTemplate.query(SELECT_FIELDS + " ORDER BY name", Map.of(), ROW_MAPPER);
    }

    @Override
    public void insert(DatasourceConfigRecord record) {
        String sql = """
                INSERT INTO datasource_config (id, name, type, config, secret, created_by)
                VALUES (:id, :name, :type, CAST(:config AS jsonb), :secret, :createdBy)
                """;
        Map<String, Object> params = new HashMap<>();
        params.put("id", record.getId() == null ? UUID.randomUUID().toString() : record.getId());
        params.put("name", record.getName());
        params.put("type", record.getType());
        params.put("config", record.getConfigJson());
        params.put("secret", record.getSecret());
        params.put("createdBy", record.getCreatedBy());
        jdbcTemplate.update(sql, params);
    }

    @Override
    public int update(String id, String name, String configJson, String secret) {
        // secret = null 表示保留原密文：用 COALESCE 语义而不是动态 SQL
        String sql = """
                UPDATE datasource_config
                   SET name = :name, config = CAST(:config AS jsonb),
                       secret = COALESCE(:secret, secret), updated_at = now()
                 WHERE id = :id
                """;
        Map<String, Object> params = new HashMap<>();
        params.put("id", id);
        params.put("name", name);
        params.put("config", configJson);
        params.put("secret", secret);
        return jdbcTemplate.update(sql, params);
    }

    @Override
    public int delete(String id) {
        return jdbcTemplate.update(
                "DELETE FROM datasource_config WHERE id = :id", Map.of("id", id));
    }
}

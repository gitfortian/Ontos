package com.ontos.module.ontology.dal.mysql;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.ontos.module.ontology.dal.dataobject.PublishedSnapshotDO;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 发布清单读取。清单不可变，仓库不提供 update/delete。
 */
@Repository
public class PublishedSnapshotRepository {

    private static final String SELECT_FIELDS = """
            SELECT manifest_id, content_hash, ontology_version, semantic_version, policy_version,
                   rule_version, time_parser_version, copy_template_version, payload::text AS payload,
                   created_at, created_by
            FROM published_snapshot
            """;

    private static final RowMapper<PublishedSnapshotDO> ROW_MAPPER = (rs, rowNum) -> {
        PublishedSnapshotDO snapshot = new PublishedSnapshotDO();
        snapshot.setManifestId(rs.getString("manifest_id"));
        snapshot.setContentHash(rs.getString("content_hash"));
        snapshot.setOntologyVersion(rs.getString("ontology_version"));
        snapshot.setSemanticVersion(rs.getString("semantic_version"));
        snapshot.setPolicyVersion(rs.getString("policy_version"));
        snapshot.setRuleVersion(rs.getString("rule_version"));
        snapshot.setTimeParserVersion(rs.getString("time_parser_version"));
        snapshot.setCopyTemplateVersion(rs.getString("copy_template_version"));
        snapshot.setPayload(rs.getString("payload"));
        snapshot.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        snapshot.setCreatedBy(rs.getString("created_by"));
        return snapshot;
    };

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public PublishedSnapshotRepository(
            @Qualifier("systemJdbcTemplate") NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<PublishedSnapshotDO> selectByManifestId(String manifestId) {
        List<PublishedSnapshotDO> rows = jdbcTemplate.query(
                SELECT_FIELDS + " WHERE manifest_id = :manifestId",
                Map.of("manifestId", manifestId),
                ROW_MAPPER);
        return rows.stream().findFirst();
    }

    public Optional<PublishedSnapshotDO> selectLatest() {
        List<PublishedSnapshotDO> rows = jdbcTemplate.query(
                SELECT_FIELDS + " ORDER BY created_at DESC LIMIT 1",
                Map.of(),
                ROW_MAPPER);
        return rows.stream().findFirst();
    }

}

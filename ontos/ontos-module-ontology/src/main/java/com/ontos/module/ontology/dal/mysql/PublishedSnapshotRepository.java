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

    /**
     * 发布插入：content_hash 在 PG 侧按 jsonb 规范文本计算（与种子 SQL、运行时读取口径一致），
     * Java 侧不自行序列化哈希——避免键序/空白差异破坏 PoC #2 的可复现口径。
     * 版本列在 M1 统一取 manifestId（版本化资产组随首发清单演进）。
     *
     * @return 数据库计算出的 content_hash
     */
    public String insertSnapshot(String manifestId, String payloadJson, String createdBy) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO published_snapshot (manifest_id, content_hash, ontology_version, semantic_version,
                                                policy_version, rule_version, time_parser_version,
                                                copy_template_version, payload, created_by)
                SELECT :manifestId, encode(digest(CAST(:payload AS jsonb)::text, 'sha256'), 'hex'),
                       :manifestId, :manifestId, :manifestId, :manifestId, :manifestId, :manifestId,
                       CAST(:payload AS jsonb), :createdBy
                RETURNING content_hash
                """,
                Map.of("manifestId", manifestId, "payload", payloadJson, "createdBy", createdBy),
                String.class);
    }

    /** 发布历史（含 dry-run 预检记录），审计可回放。 */
    public void insertPublishRecord(String manifestId, String contentHash, boolean dryRun,
                                    String sourceRef, String publishedBy) {
        jdbcTemplate.update(
                """
                INSERT INTO publish_record (manifest_id, content_hash, dry_run, source_ref, published_by)
                VALUES (:manifestId, :contentHash, :dryRun, :sourceRef, :publishedBy)
                """,
                Map.of("manifestId", manifestId, "contentHash", contentHash,
                        "dryRun", dryRun, "sourceRef", sourceRef == null ? "" : sourceRef,
                        "publishedBy", publishedBy));
    }

    /** 发布历史查询（管理端展示，最新在前）。 */
    public List<Map<String, Object>> selectPublishRecords(int limit) {
        return jdbcTemplate.queryForList(
                """
                SELECT manifest_id, content_hash, dry_run, source_ref, published_by,
                       published_at::text AS published_at
                FROM publish_record
                ORDER BY published_at DESC
                LIMIT :limit
                """,
                Map.of("limit", limit));
    }

}

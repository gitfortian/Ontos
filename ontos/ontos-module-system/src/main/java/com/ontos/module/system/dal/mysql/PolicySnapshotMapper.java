package com.ontos.module.system.dal.mysql;

import java.util.Map;
import java.util.Optional;

import com.ontos.module.system.dal.dataobject.PolicySnapshotDO;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 策略快照读取（M4/W1）。快照不可变（DB 触发器拒 UPDATE/DELETE），
 * 激活态由部分唯一索引约束同租户至多一个 active=true。
 */
@Repository
public class PolicySnapshotMapper {

    private static final String SELECT_ACTIVE = """
            SELECT snapshot_id::text AS snapshot_id, tenant_id, policy_version,
                   payload::text AS payload, active, created_at::text AS created_at, created_by
            FROM policy_snapshot
            WHERE tenant_id = :tenantId AND active = TRUE
            """;

    private static final RowMapper<PolicySnapshotDO> ROW_MAPPER = (rs, rowNum) -> {
        PolicySnapshotDO item = new PolicySnapshotDO();
        item.setSnapshotId(rs.getString("snapshot_id"));
        item.setTenantId(rs.getString("tenant_id"));
        item.setPolicyVersion(rs.getString("policy_version"));
        item.setPayload(rs.getString("payload"));
        item.setActive(rs.getBoolean("active"));
        item.setCreatedAt(rs.getString("created_at"));
        item.setCreatedBy(rs.getString("created_by"));
        return item;
    };

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public PolicySnapshotMapper(
            @Qualifier("systemJdbcTemplate") NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 取租户当前生效快照；不存在返回 empty（fail-closed 由服务层拒绝）。 */
    public Optional<PolicySnapshotDO> selectActive(String tenantId) {
        var list = jdbcTemplate.query(
                SELECT_ACTIVE, Map.of("tenantId", tenantId), ROW_MAPPER);
        return list.stream().findFirst();
    }
}

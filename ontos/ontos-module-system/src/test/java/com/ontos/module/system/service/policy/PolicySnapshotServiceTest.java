package com.ontos.module.system.service.policy;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

import com.ontos.framework.common.exception.ServiceException;
import com.ontos.framework.web.core.context.RequestContext;
import com.ontos.module.system.dal.dataobject.PolicySnapshotDO;
import com.ontos.module.system.dal.mysql.PolicySnapshotMapper;
import com.ontos.module.system.enums.ErrorCodeConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 策略快照判定单测（M4/W1，规格 v1.1 §3.1）：
 * 判定模型（角色并集 / allowlist 同码 / label 个体拒绝）、加载期校验（D-ε）、TTL 缓存（D-λ）。
 * activate 的事务语义依赖真实 DB（部分唯一索引兜底并发），由 M4 E2E 覆盖，此处不 mock。
 */
class PolicySnapshotServiceTest {

    private static final String PAYLOAD = """
            {
              "policyVersion": "seed-test-v1",
              "roles": [
                {"roleId": "role.analyst", "name": "分析师"},
                {"roleId": "role.auditor", "name": "审计员"}
              ],
              "assignments": [{"userId": "*", "roleId": "role.analyst"}],
              "assets": [
                {"assetType": "metric", "assetId": "metric.gmv", "effect": "allow",
                 "roles": ["role.analyst"], "sensitivityClass": "internal"},
                {"assetType": "metric", "assetId": "metric.secret_cohort", "effect": "allow",
                 "roles": ["role.analyst"], "sensitivityClass": "label"},
                {"assetType": "property", "assetId": "property.customer.email", "effect": "allow",
                 "roles": ["role.analyst"], "sensitivityClass": "pii"}
              ]
            }
            """;

    private final PolicySnapshotMapper mapper = mock(PolicySnapshotMapper.class);
    private final NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);

    private PolicySnapshotService service(Duration ttl) {
        return new PolicySnapshotService(mapper, jdbcTemplate, new TransactionTemplate(), ttl);
    }

    private PolicySnapshotService service() {
        return service(Duration.ofSeconds(60));
    }

    private static RequestContext context(String userId, Set<String> gatewayRoles) {
        return new RequestContext("demo", userId, "s1", 1, null, "trace", gatewayRoles, false);
    }

    @BeforeEach
    void setUp() {
        when(mapper.selectActive("demo")).thenReturn(Optional.of(snapshot(PAYLOAD)));
    }

    private static PolicySnapshotDO snapshot(String payload) {
        PolicySnapshotDO row = new PolicySnapshotDO();
        row.setTenantId("demo");
        row.setPolicyVersion("seed-test-v1");
        row.setPayload(payload);
        row.setActive(true);
        return row;
    }

    // ---------- 判定模型 ----------

    @Test
    void shouldAllowRegisteredAssetForWildcardAssignment() {
        PolicySnapshotService.SensitivityClass sensitivity = service().check(
                context("u-dev", Set.of()), PolicySnapshotService.AssetType.METRIC, "metric.gmv");
        assertEquals(PolicySnapshotService.SensitivityClass.INTERNAL, sensitivity);
    }

    @Test
    void shouldUnionGatewayRolesWithSnapshotAssignments() {
        // 网关角色（认证权威）直接命中资产的角色集合：assignments 不含该用户也放行（并集模型 D-α）
        PolicySnapshotDO analystOnlyForAuditor = snapshot(PAYLOAD.replace(
                "{\"userId\": \"*\", \"roleId\": \"role.analyst\"}",
                "{\"userId\": \"u-other\", \"roleId\": \"role.analyst\"}"));
        // metrics 的 roles 仍只含 analyst：网关 auditor 不命中 → 再构造一条仅 auditor 可看的资产验证并集
        PolicySnapshotDO withAuditorAsset = snapshot(PAYLOAD.replace(
                "{\"assetType\": \"metric\", \"assetId\": \"metric.secret_cohort\"",
                "{\"assetType\": \"metric\", \"assetId\": \"metric.audit_only\", \"effect\": \"allow\", \"roles\": [\"role.auditor\"], \"sensitivityClass\": \"internal\"},\n                {\"assetType\": \"metric\", \"assetId\": \"metric.secret_cohort\""));
        when(mapper.selectActive("demo")).thenReturn(withAuditorAsset == null ? null : Optional.of(withAuditorAsset));

        // 网关 auditor + 无通配 assignment：audit_only 放行（并集生效）
        PolicySnapshotService.SensitivityClass sensitivity = service().check(
                context("u-audit", Set.of("role.auditor")), PolicySnapshotService.AssetType.METRIC, "metric.audit_only");
        assertEquals(PolicySnapshotService.SensitivityClass.INTERNAL, sensitivity);

        // 同一快照下通配 assignment 已移除：无网关角色的用户对 analyst 资产被拒
        when(mapper.selectActive("demo")).thenReturn(Optional.of(analystOnlyForAuditor));
        ServiceException ex = assertThrows(ServiceException.class, () -> service().check(
                context("u-dev", Set.of()), PolicySnapshotService.AssetType.METRIC, "metric.gmv"));
        assertEquals(ErrorCodeConstants.POLICY_ASSET_NOT_ALLOWED.getCode(), ex.getCode());
    }

    @Test
    void shouldRejectUnregisteredAssetWithoutLeakingRegistrationState() {
        // 未登记与无权限同码（1003000005），不泄露资产登记状态（规格 §3.1）
        ServiceException ex = assertThrows(ServiceException.class, () -> service().check(
                context("u-dev", Set.of()), PolicySnapshotService.AssetType.METRIC, "metric.not_exists"));
        assertEquals(ErrorCodeConstants.POLICY_ASSET_NOT_ALLOWED.getCode(), ex.getCode());
    }

    @Test
    void shouldRejectWhenNoRoleSourceMatches() {
        // 快照无通配 assignment、网关无角色：两个角色来源都不命中 → 拒绝
        when(mapper.selectActive("demo")).thenReturn(Optional.of(snapshot(PAYLOAD.replace(
                "{\"userId\": \"*\", \"roleId\": \"role.analyst\"}",
                "{\"userId\": \"u-other\", \"roleId\": \"role.analyst\"}"))));
        ServiceException ex = assertThrows(ServiceException.class, () -> service().check(
                context("u-dev", Set.of()), PolicySnapshotService.AssetType.METRIC, "metric.gmv"));
        assertEquals(ErrorCodeConstants.POLICY_ASSET_NOT_ALLOWED.getCode(), ex.getCode());
    }

    @Test
    void shouldRejectLabelIndividualReturn() {
        ServiceException ex = assertThrows(ServiceException.class, () -> service().checkIndividual(
                context("u-dev", Set.of()), PolicySnapshotService.AssetType.METRIC, "metric.secret_cohort"));
        assertEquals(ErrorCodeConstants.POLICY_LABEL_INDIVIDUAL_REJECTED.getCode(), ex.getCode());
    }

    @Test
    void shouldExposePiiSensitivityClass() {
        PolicySnapshotService.SensitivityClass sensitivity = service().check(
                context("u-dev", Set.of()), PolicySnapshotService.AssetType.PROPERTY, "property.customer.email");
        assertEquals(PolicySnapshotService.SensitivityClass.PII, sensitivity);
    }

    // ---------- 快照缺失与加载期校验（D-ε） ----------

    @Test
    void shouldFailClosedWhenSnapshotMissing() {
        when(mapper.selectActive(anyString())).thenReturn(java.util.Optional.empty());
        RequestContext otherTenant = new RequestContext("other", "u1", "s1", 1, null, "t", Set.of(), false);
        ServiceException ex = assertThrows(ServiceException.class, () -> service().check(
                otherTenant, PolicySnapshotService.AssetType.METRIC, "metric.gmv"));
        assertEquals(ErrorCodeConstants.POLICY_SNAPSHOT_MISSING.getCode(), ex.getCode());
    }

    @Test
    void shouldRejectInvalidEnumAtLoadTime() {
        when(mapper.selectActive("demo")).thenReturn(java.util.Optional.of(snapshot(PAYLOAD.replace(
                "\"sensitivityClass\": \"pii\"", "\"sensitivityClass\": \"TOP_SECRET\""))));
        ServiceException ex = assertThrows(ServiceException.class, () -> service().check(
                context("u-dev", Set.of()), PolicySnapshotService.AssetType.PROPERTY, "property.customer.email"));
        assertEquals(ErrorCodeConstants.POLICY_SNAPSHOT_INVALID.getCode(), ex.getCode());
    }

    @Test
    void shouldRejectDanglingRoleReferenceAtLoadTime() {
        when(mapper.selectActive("demo")).thenReturn(java.util.Optional.of(snapshot(PAYLOAD.replace(
                "\"roles\": [\"role.analyst\"], \"sensitivityClass\": \"pii\"",
                "\"roles\": [\"role.ghost\"], \"sensitivityClass\": \"pii\""))));
        ServiceException ex = assertThrows(ServiceException.class, () -> service().check(
                context("u-dev", Set.of()), PolicySnapshotService.AssetType.PROPERTY, "property.customer.email"));
        assertEquals(ErrorCodeConstants.POLICY_SNAPSHOT_INVALID.getCode(), ex.getCode());
    }

    // ---------- TTL 缓存（D-λ） ----------

    @Test
    void shouldServeFromCacheWithinTtl() {
        PolicySnapshotService svc = service(Duration.ofSeconds(60));
        svc.check(context("u-dev", Set.of()), PolicySnapshotService.AssetType.METRIC, "metric.gmv");
        // TTL 内不重载：即便 mapper 换成非法快照，判定仍按缓存放行（收敛窗口上界 = TTL）
        when(mapper.selectActive("demo")).thenReturn(java.util.Optional.of(snapshot("{\"policyVersion\":\"broken\"}")));
        svc.check(context("u-dev", Set.of()), PolicySnapshotService.AssetType.METRIC, "metric.gmv");
    }

    @Test
    void shouldReloadAfterTtlExpired() {
        PolicySnapshotService zeroTtl = service(Duration.ZERO);
        zeroTtl.check(context("u-dev", Set.of()), PolicySnapshotService.AssetType.METRIC, "metric.gmv");
        // TTL=0：每次重载。broken payload（缺 roles/assets）经归一化是「空授权集」快照——
        // 结构合法，语义为拒绝一切（fail-closed 的另一种形态），因此是 allowlist 拒绝而非加载失败。
        when(mapper.selectActive("demo")).thenReturn(java.util.Optional.of(snapshot("{\"policyVersion\":\"broken\"}")));
        ServiceException ex = assertThrows(ServiceException.class, () -> zeroTtl.check(
                context("u-dev", Set.of()), PolicySnapshotService.AssetType.METRIC, "metric.gmv"));
        assertEquals(ErrorCodeConstants.POLICY_ASSET_NOT_ALLOWED.getCode(), ex.getCode());
    }

    @Test
    void shouldFailClosedOnMalformedJson() {
        PolicySnapshotService svc = service(Duration.ofSeconds(60));
        when(mapper.selectActive("demo")).thenReturn(java.util.Optional.of(snapshot("not-json")));
        ServiceException ex = assertThrows(ServiceException.class, () -> svc.check(
                context("u-dev", Set.of()), PolicySnapshotService.AssetType.METRIC, "metric.gmv"));
        assertEquals(ErrorCodeConstants.POLICY_SNAPSHOT_INVALID.getCode(), ex.getCode());
    }
}

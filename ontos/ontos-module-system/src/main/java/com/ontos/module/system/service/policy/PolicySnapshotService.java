package com.ontos.module.system.service.policy;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.ontos.framework.web.core.context.RequestContext;
import com.ontos.module.system.dal.dataobject.PolicySnapshotDO;
import com.ontos.module.system.dal.mysql.PolicySnapshotMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import static com.ontos.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.ontos.module.system.enums.ErrorCodeConstants.POLICY_ASSET_NOT_ALLOWED;
import static com.ontos.module.system.enums.ErrorCodeConstants.POLICY_LABEL_INDIVIDUAL_REJECTED;
import static com.ontos.module.system.enums.ErrorCodeConstants.POLICY_SNAPSHOT_INVALID;
import static com.ontos.module.system.enums.ErrorCodeConstants.POLICY_SNAPSHOT_MISSING;
import static com.ontos.module.system.enums.ErrorCodeConstants.POLICY_SNAPSHOT_VERSION_CONFLICT;

/**
 * 策略快照判定服务（M4/W1，规格 v1.1 §3.1）。
 *
 * <p>判定模型：
 * <ul>
 *   <li>有效角色 = 网关签发角色（{@link RequestContext#roles()}）∪ 快照 assignments
 *       （userId 精确匹配或 "*" 通配）——认证签发的组必须生效，快照内绑定用户属运营合法授权；</li>
 *   <li>资产条目 effect=allow 且角色有交集才放行；**未登记即拒绝**（allowlist，fail-closed），
 *       错误信息不区分「未登记」与「无权限」（不泄露资产登记状态）；</li>
 *   <li>快照缺失或内容非法 → 加载即拒绝（fail-closed），绝不降级放行。</li>
 * </ul>
 *
 * <p>缓存：快照不可变，按租户缓存解析结果并带 **TTL（默认 60s，可配置）**——
 * 多副本部署下 activate 只能失效本实例缓存，TTL 保证其余副本在限期内收敛（规格 E1 措辞）。
 * 快照激活由 {@link #activate} 在事务内「先取消旧、再置新」完成，部分唯一索引兜底并发。
 */
@Slf4j
@Service
public class PolicySnapshotService {

    /** 资产类型（与快照 assets[].assetType 对应，wire 形态为小写）。 */
    public enum AssetType {
        METRIC, DIMENSION, PROPERTY, ENTITY, RULE;

        String wire() {
            return name().toLowerCase();
        }

        static AssetType of(String wire) {
            return valueOf(wire.toUpperCase());
        }
    }

    /** 策略级敏感分级（技术选型 §4.8.1 的 L1/L3 语义载体；L2 存在性另读清单 existenceSensitive）。 */
    public enum SensitivityClass {
        PUBLIC, INTERNAL, PII, EXISTENCE, LABEL;

        static SensitivityClass of(String wire) {
            return valueOf(wire.toUpperCase());
        }
    }

    private static final String WILDCARD_USER = "*";
    private static final Set<String> LEGAL_EFFECTS = Set.of("allow");
    private static final Duration DEFAULT_TTL = Duration.ofSeconds(60);

    private final PolicySnapshotMapper mapper;
    private final NamedParameterJdbcTemplate systemJdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final long ttlMillis;

    private final Map<String, CachedSnapshot> cache = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public PolicySnapshotService(PolicySnapshotMapper mapper,
                                 @Qualifier("systemJdbcTemplate") NamedParameterJdbcTemplate systemJdbcTemplate,
                                 TransactionTemplate transactionTemplate) {
        this(mapper, systemJdbcTemplate, transactionTemplate, DEFAULT_TTL);
    }

    PolicySnapshotService(PolicySnapshotMapper mapper,
                          NamedParameterJdbcTemplate systemJdbcTemplate,
                          TransactionTemplate transactionTemplate,
                          Duration ttl) {
        this.mapper = mapper;
        this.systemJdbcTemplate = systemJdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.ttlMillis = ttl.toMillis();
    }

    private record CachedSnapshot(String policyVersion, PolicySnapshotPayload payload,
                                  Map<String, PolicySnapshotPayload.Asset> assetIndex, long loadedAtMillis) {

        boolean expired(long ttlMillis) {
            // >= ：TTL=0 表示每次重载（同毫秒内两次调用也过期），测试与多副本收敛口径一致
            return System.currentTimeMillis() - loadedAtMillis >= ttlMillis;
        }
    }

    /** 资产授权判定：放行返回敏感分级；未登记 / 角色无交集 → POLICY_ASSET_NOT_ALLOWED（同码，不泄露登记状态）。 */
    public SensitivityClass check(RequestContext context, AssetType assetType, String assetId) {
        CachedSnapshot snapshot = requireActive(context.tenantId());
        PolicySnapshotPayload.Asset asset = snapshot.assetIndex().get(key(assetType.wire(), assetId));
        Set<String> userRoles = resolveUserRoles(context, snapshot.payload());
        if (asset == null || !"allow".equals(asset.effect())
                || userRoles.stream().noneMatch(asset.roles()::contains)) {
            throw exception(POLICY_ASSET_NOT_ALLOWED, assetType.wire() + ":" + assetId);
        }
        return SensitivityClass.of(asset.sensitivityClass());
    }

    /**
     * 批量授权视图（M5/W1 F-4）：当前租户生效快照中「effect=allow ∧ 用户角色有交集」的资产 ID
     * 集合（按 `type|assetId` 键），供检索瀑布的授权子图过滤——快照内存直取，无额外查询。
     * 这是瀑布候选范围的**预过滤**；执行路径的逐资产 {@link #check}（同码拒绝）仍完整生效，
     * 两者语义不同（瀑布定候选范围，执行放行）。
     */
    public java.util.Set<String> authorizedAssetIds(RequestContext context) {
        CachedSnapshot snapshot = requireActive(context.tenantId());
        Set<String> userRoles = resolveUserRoles(context, snapshot.payload());
        Set<String> visible = new java.util.HashSet<>();
        for (PolicySnapshotPayload.Asset asset : snapshot.payload().assets()) {
            if ("allow".equals(asset.effect()) && asset.roles() != null
                    && userRoles.stream().anyMatch(asset.roles()::contains)) {
                visible.add(key(asset.assetType(), asset.assetId()));
            }
        }
        return java.util.Set.copyOf(visible);
    }

    /**
     * label 型判定结果的个体返回闸门（v1.4 §10.5 第 3 类）：默认拒绝。
     * 聚合分布路径 M4 不可达（rule 型指标无聚合能力，登记不实现）；个体判定走 M4/W2 审批联动。
     */
    public SensitivityClass checkIndividual(RequestContext context, AssetType assetType, String assetId) {
        SensitivityClass sensitivity = check(context, assetType, assetId);
        if (sensitivity == SensitivityClass.LABEL) {
            throw exception(POLICY_LABEL_INDIVIDUAL_REJECTED, assetId);
        }
        return sensitivity;
    }

    /** 生效策略版本（审计用，规格 §3.1「生效版本进审计」）；快照缺失按 fail-closed 报错。 */
    public String activeVersion(String tenantId) {
        return requireActive(tenantId).policyVersion();
    }

    /**
     * 激活指定版本（管理动作）：事务内先取消当前 active、再置新 active；
     * 部分唯一索引兜底并发激活（第二个激活会因唯一冲突回滚）。成功后失效本实例缓存。
     */
    public void activate(String tenantId, String policyVersion) {
        Boolean updated = transactionTemplate.execute(status -> {
            int deactivated = systemJdbcTemplate.update(
                    "UPDATE policy_snapshot SET active = FALSE WHERE tenant_id = :tenantId AND active = TRUE",
                    Map.of("tenantId", tenantId));
            int activated;
            try {
                activated = systemJdbcTemplate.update(
                        "UPDATE policy_snapshot SET active = TRUE WHERE tenant_id = :tenantId AND policy_version = :version",
                        Map.of("tenantId", tenantId, "version", policyVersion));
            } catch (RuntimeException ex) {
                // 部分唯一索引冲突等：回滚整个事务（旧 active 也在本事务被取消，必须一起回滚）；
                // 原始异常必须留日志——连接故障/死锁与真正的版本冲突对外同码，但排查依赖这里的堆栈
                log.warn("[activate][tenant={} version={} 激活失败，事务回滚: {}]",
                        tenantId, policyVersion, ex.toString());
                throw exception(POLICY_SNAPSHOT_VERSION_CONFLICT, policyVersion);
            }
            if (activated != 1) {
                status.setRollbackOnly();
                throw exception(POLICY_SNAPSHOT_VERSION_CONFLICT, policyVersion);
            }
            log.info("[activate][tenant={} version={} deactivated={}]", tenantId, policyVersion, deactivated);
            return true;
        });
        if (Boolean.TRUE.equals(updated)) {
            cache.remove(tenantId);
        }
    }

    private CachedSnapshot requireActive(String tenantId) {
        CachedSnapshot cached = cache.get(tenantId);
        if (cached != null && !cached.expired(ttlMillis)) {
            return cached;
        }
        if (cached != null) {
            // 过期先移除，避免并发下长期持有旧授权（降权收敛窗口的上界就是 TTL）
            cache.remove(tenantId, cached);
        }
        CachedSnapshot loaded = load(tenantId);
        cache.put(tenantId, loaded);
        return loaded;
    }

    private CachedSnapshot load(String tenantId) {
        Optional<PolicySnapshotDO> row = mapper.selectActive(tenantId);
        if (row.isEmpty()) {
            // fail-closed：没有生效快照的租户不做任何放行；缺配置是配置错误，必须在最前面暴露
            throw exception(POLICY_SNAPSHOT_MISSING, tenantId);
        }
        PolicySnapshotPayload payload = parseAndValidate(tenantId, row.get().getPayload());
        Map<String, PolicySnapshotPayload.Asset> index = new HashMap<>();
        for (PolicySnapshotPayload.Asset asset : payload.assets()) {
            index.put(key(AssetType.of(asset.assetType()).wire(), asset.assetId()), asset);
        }
        log.info("[load][policy snapshot loaded: tenant={} version={} assets={} roles={}]",
                tenantId, row.get().getPolicyVersion(), index.size(), payload.roles().size());
        return new CachedSnapshot(row.get().getPolicyVersion(), payload, index, System.currentTimeMillis());
    }

    /**
     * 加载期全量校验（规格 v1.1 / 自审 D-ε）：非法快照在加载时 fail-closed 拒绝，
     * 绝不让畸形数据进入判定（枚举 valueOf 的 500 或悬空角色引用的静默放行都不允许）。
     */
    private PolicySnapshotPayload parseAndValidate(String tenantId, String payloadJson) {
        PolicySnapshotPayload payload;
        try {
            payload = PolicySnapshotPayload.parse(payloadJson);
        } catch (RuntimeException ex) {
            throw exception(POLICY_SNAPSHOT_INVALID, tenantId + " payload 反序列化失败");
        }
        if (payload == null || payload.policyVersion() == null || payload.policyVersion().isBlank()) {
            throw exception(POLICY_SNAPSHOT_INVALID, tenantId + " 缺少 policyVersion");
        }
        // 归一化（D-ε）：缺失段落一律按空列表进入后续流程，校验与日志不再面对 null
        List<PolicySnapshotPayload.Role> roles =
                payload.roles() == null ? List.of() : payload.roles();
        List<PolicySnapshotPayload.Assignment> assignments =
                payload.assignments() == null ? List.of() : payload.assignments();
        Set<String> definedRoles = new HashSet<>();
        for (PolicySnapshotPayload.Role role : roles) {
            if (role.roleId() == null || role.roleId().isBlank() || !definedRoles.add(role.roleId())) {
                throw exception(POLICY_SNAPSHOT_INVALID, tenantId + " 角色定义缺失或重复: " + role.roleId());
            }
        }
        for (PolicySnapshotPayload.Assignment assignment : assignments) {
            if (assignment.userId() == null || assignment.userId().isBlank()
                    || assignment.roleId() == null || !definedRoles.contains(assignment.roleId())) {
                throw exception(POLICY_SNAPSHOT_INVALID,
                        tenantId + " assignment 引用未定义角色: " + assignment.roleId());
            }
        }
        List<PolicySnapshotPayload.Asset> assets =
                payload.assets() == null ? List.of() : payload.assets();
        if (assets.isEmpty()) {
            // 空授权集 = 拒绝一切；允许存在（fail-closed 语义成立），但必须显式解析通过
            return new PolicySnapshotPayload(payload.policyVersion(), roles, assignments, List.of());
        }
        for (PolicySnapshotPayload.Asset asset : assets) {
            try {
                AssetType.of(asset.assetType());
                SensitivityClass.of(asset.sensitivityClass());
            } catch (RuntimeException ex) {
                throw exception(POLICY_SNAPSHOT_INVALID,
                        tenantId + " 资产枚举非法: " + asset.assetType() + "/" + asset.sensitivityClass());
            }
            if (asset.assetId() == null || asset.assetId().isBlank()
                    || !LEGAL_EFFECTS.contains(asset.effect())
                    || asset.roles() == null || asset.roles().isEmpty()
                    || !definedRoles.containsAll(asset.roles())) {
                throw exception(POLICY_SNAPSHOT_INVALID, tenantId + " 资产条目非法: " + asset.assetId());
            }
        }
        return new PolicySnapshotPayload(payload.policyVersion(), roles, assignments, assets);
    }

    /** 有效角色 = 网关签发（context.roles）∪ 快照 assignments（精确 userId 或 "*"）。 */
    private Set<String> resolveUserRoles(RequestContext context, PolicySnapshotPayload payload) {
        Set<String> roles = new HashSet<>(context.roles());
        if (payload.assignments() != null) {
            for (PolicySnapshotPayload.Assignment assignment : payload.assignments()) {
                if (WILDCARD_USER.equals(assignment.userId()) || assignment.userId().equals(context.userId())) {
                    roles.add(assignment.roleId());
                }
            }
        }
        return roles;
    }

    private static String key(String assetType, String assetId) {
        return assetType + "|" + assetId;
    }

}

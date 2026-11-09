package com.ontos.module.system.service.policy;

import com.ontos.framework.common.exception.enums.GlobalErrorCodeConstants;
import com.ontos.framework.web.core.context.RequestContext;
import static com.ontos.framework.common.exception.util.ServiceExceptionUtil.exception;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * 策略判定入口（M4/W1 接线后）：授权语义全部来自不可变策略快照（{@link PolicySnapshotService}）。
 *
 * <p>默认 fail-closed：未授权 / 未登记 / 快照缺失一律拒绝。`ontos.policy.dev-allow-all`
 * 只允许在非 prod profile 生效——prod profile 下开启直接启动失败（规格 v1.1 §3.1 守卫）；
 * 任何情况下身份与权限都不由用户文本或请求体决定。
 *
 * <p>判定位置在 `prepare` 的 DSL 结构校验之后（错误码优先级：清单存在性 1001000003 →
 * 策略授权 1003000005，见规格 §3.1）。
 */
@Service
public class PolicyService {

    private final PolicySnapshotService snapshotService;
    private final PolicyProperties properties;
    private final boolean prodProfile;

    public PolicyService(PolicySnapshotService snapshotService,
                         PolicyProperties properties,
                         Environment environment) {
        this.snapshotService = snapshotService;
        this.properties = properties;
        this.prodProfile = environment.matchesProfiles("prod");
        if (prodProfile && properties.isDevAllowAll()) {
            // fail-closed 守卫：生产环境绝不允许 dev 放行；宁可启动失败也不静默放大权限
            throw new IllegalStateException(
                    "ontos.policy.dev-allow-all 不允许在 prod profile 启用（M4/W1 守卫），请移除该配置");
        }
    }

    /** 资产授权判定：放行返回敏感分级（pii / label / internal…），拒绝抛业务错误。 */
    public PolicySnapshotService.SensitivityClass check(RequestContext context,
                                                        PolicySnapshotService.AssetType assetType,
                                                        String assetId) {
        if (devAllowed()) {
            return PolicySnapshotService.SensitivityClass.INTERNAL;
        }
        return snapshotService.check(context, assetType, assetId);
    }

    /** label 型判定结果的个体返回闸门（聚合分布不走此入口，M4 不可达已登记）。 */
    public PolicySnapshotService.SensitivityClass checkIndividual(RequestContext context,
                                                                  PolicySnapshotService.AssetType assetType,
                                                                  String assetId) {
        if (devAllowed()) {
            return PolicySnapshotService.SensitivityClass.INTERNAL;
        }
        return snapshotService.checkIndividual(context, assetType, assetId);
    }

    /**
     * 管理面动作闸门（发布清单 / 资产等）：不属于业务资产 allowlist（快照没有对应 AssetType），
     * 沿用 M0 语义——仅 dev 放行，其余 403。管理角色模型归 M6/M8（快照扩 management 段或独立表），
     * 在此之前发布链路保持整体 fail-closed。
     */
    public void checkManagementAllowed(RequestContext context) {
        if (!devAllowed()) {
            throw exception(GlobalErrorCodeConstants.FORBIDDEN);
        }
    }

    /** 生效策略版本（授权判定用；快照缺失时抛错——授权路径不允许静默）。 */
    public String activeVersion(String tenantId) {
        return snapshotService.activeVersion(tenantId);
    }

    /**
     * 审计写入用的生效版本（规格 v1.1 §3.1）：dev 放行或快照缺失返回 null（列可空），
     * 绝不因版本解析失败阻断审计主流程——缺失时授权层会先拒绝，审计不重复报错。
     */
    public String auditVersion(String tenantId) {
        if (devAllowed()) {
            return null;
        }
        try {
            return snapshotService.activeVersion(tenantId);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private boolean devAllowed() {
        return !prodProfile && properties.isDevAllowAll();
    }
}

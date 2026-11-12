package com.ontos.module.session.service.scenario;

import java.util.Map;
import java.util.Set;

import com.ontos.framework.web.core.context.RequestContext;
import com.ontos.module.ontology.core.ManifestPayload;
import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.ontology.service.manifest.ManifestService;
import com.ontos.module.query.core.scenario.ScenarioRouteGuard;
import com.ontos.module.session.core.scenario.ScenarioRouteState;
import com.ontos.module.session.service.session.SessionStateService;
import com.ontos.module.system.service.policy.PolicySnapshotService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import static com.ontos.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.ontos.module.session.enums.ErrorCodeConstants.SCENARIO_CALIBER_CONFLICT;
import static com.ontos.module.session.enums.ErrorCodeConstants.SCENARIO_SCOPE_VIOLATION;
import static com.ontos.module.session.enums.ErrorCodeConstants.SCENARIO_MANUAL_UNAUTHORIZED;
import static com.ontos.module.session.enums.ErrorCodeConstants.SCENARIO_MODE_FORBIDDEN;

/**
 * {@link ScenarioRouteGuard} 的 session 侧实现（M6/W2/T2.4）：
 * 直连查询 API 的 DSL {@code scenarioId} 服务端裁决（ADR-0017 D1 四步）。
 *
 * <p>裁决链（全部 fail-closed，不静默回退）：
 * ① scenarioId 为空 = 自由问数语义直接放行；
 * ② 已发布校验（清单 scenarios 段 + status=published）；
 * ③ 授权校验（授权集含 {@code scenario|<id>} 键）；
 * ④ 显式进入/切换落会话状态（复用 {@link ScenarioSessionService#apply}——
 * free/pending/in_scenario → in_scenario，迁移表封闭校验）；
 * ⑤ 分析模式 ∈ {@code allowed_analysis_modes}；
 * ⑥ 显式时间绑定 ∈ {@code frozen_calibers.default_time_field}（冲突即拒绝，不覆盖）。
 */
@Slf4j
@Service
public class ScenarioRouteGuardImpl implements ScenarioRouteGuard {

    private final ManifestService manifestService;
    private final PolicySnapshotService policySnapshotService;
    private final ScenarioSessionService scenarioSessionService;
    private final SessionStateService sessionStateService;

    public ScenarioRouteGuardImpl(ManifestService manifestService,
                                  PolicySnapshotService policySnapshotService,
                                  ScenarioSessionService scenarioSessionService,
                                  SessionStateService sessionStateService) {
        this.manifestService = manifestService;
        this.policySnapshotService = policySnapshotService;
        this.scenarioSessionService = scenarioSessionService;
        this.sessionStateService = sessionStateService;
    }

    @Override
    public String currentScenarioId(RequestContext context) {
        // in_scenario 才返回场景 ID；free / pending_confirm / 无状态一律 null
        //（pending 态尚未生效，allowed_scenario_ids 判定按「不在场景内」处理）
        ScenarioSessionService.ScenarioSessionView view = scenarioSessionService.read(context);
        return view.state() == ScenarioRouteState.IN_SCENARIO ? view.scenarioId() : null;
    }

    @Override
    public DecisionAudit lastDecision(RequestContext context) {
        ScenarioSessionService.ScenarioDecisionView view = scenarioSessionService.readDecision(context);
        // 第三列恒 null：M6 规格决策 4 用确定性命中口径，明确不引入 LLM 置信度数值。
        return new DecisionAudit(view.entrySource(), view.confirmByUser(), null);
    }

    @Override
    public void adjudicate(RequestContext context, String scenarioId, String mode,
                           String timeRangeField, java.util.Collection<String> referencedAssetIds) {
        if (scenarioId == null || scenarioId.isBlank()) {
            return;
        }
        PublishedManifest manifest = manifestService.getActiveManifestOrNull();
        ManifestPayload.ScenarioMeta scenario = null;
        if (manifest != null && manifest.getScenarios() != null) {
            scenario = manifest.getScenarios().stream()
                    .filter(candidate -> scenarioId.equals(candidate.getId()))
                    .findFirst().orElse(null);
        }
        // ② 已发布校验：未发布 / 不存在同形拒绝（不泄露清单内是否存在）
        if (scenario == null || !"published".equals(scenario.getStatus())) {
            throw exception(SCENARIO_MANUAL_UNAUTHORIZED, scenarioId);
        }
        // ③ 授权校验：授权集不含 scenario 键即拒绝
        Set<String> authorizedKeys = policySnapshotService.authorizedAssetIds(context);
        if (!authorizedKeys.contains(ScenarioScopeService.SCENARIO_KEY_PREFIX + scenarioId)) {
            throw exception(SCENARIO_MANUAL_UNAUTHORIZED, scenarioId);
        }
        // ④ 分析模式 ∈ allowed_analysis_modes（闭白名单；场景声明空 = 不限制）
        if (mode != null && scenario.getAllowedAnalysisModes() != null
                && !scenario.getAllowedAnalysisModes().isEmpty()
                && !normalizedModes(scenario.getAllowedAnalysisModes()).contains(mode.toLowerCase())) {
            throw exception(SCENARIO_MODE_FORBIDDEN, scenarioId, mode);
        }
        // ⑤ 引用资产 ⊆ 场景 scope（T24-5：显式带场景 = 声明在该业务域内查询，
        // 引用场景外资产即使已授权也拒绝——场景边界独立于授权边界，只收窄不放宽）
        if (referencedAssetIds != null && !referencedAssetIds.isEmpty()) {
            Set<String> scopeIds = ScenarioScopeService.scopeAssetIds(scenario, manifest);
            for (String assetId : referencedAssetIds) {
                if (assetId != null && !assetId.isBlank() && !scopeIds.contains(assetId)) {
                    throw exception(SCENARIO_SCOPE_VIOLATION, assetId, scenarioId);
                }
            }
        }
        // ⑥ frozen_calibers 冲突即拒绝（不静默覆盖）
        if (timeRangeField != null && scenario.getSemanticScope() != null) {
            Map<String, ManifestPayload.FrozenCaliber> calibers =
                    scenario.getSemanticScope().getFrozenCalibers();
            ManifestPayload.FrozenCaliber defaultTimeField =
                    calibers == null ? null : calibers.get("default_time_field");
            if (defaultTimeField != null && defaultTimeField.getValue() != null
                    && !defaultTimeField.getValue().equals(timeRangeField)) {
                throw exception(SCENARIO_CALIBER_CONFLICT, "time_range_field",
                        defaultTimeField.getValue());
            }
        }
        log.info("[adjudicate][scenario 显式路由前置校验通过（未落状态）: session={} scenario={} mode={}]",
                context.sessionId(), scenarioId, mode);
    }

    /**
     * D1 第 ④ 步（M6 全量深度审核 D-1 修复）：仅在 adjudicate + prepare 全链成功后提交状态。
     * 校验失败（模式越界 / scope 越界 / 口径冲突 / DSL / 策略 / 时间解析）不会写会话状态，
     * 保证「失败即拒、目标状态不变」——被拒绝的请求不会把会话污染成 in_scenario 影响后续裁剪。
     */
    @Override
    public void commitExplicitRoute(RequestContext context, String scenarioId) {
        if (scenarioId == null || scenarioId.isBlank()) {
            return;
        }
        PublishedManifest manifest = manifestService.getActiveManifestOrNull();
        ManifestPayload.ScenarioMeta scenario = manifest == null || manifest.getScenarios() == null
                ? null : manifest.getScenarios().stream()
                .filter(candidate -> scenarioId.equals(candidate.getId())
                        && "published".equals(candidate.getStatus()))
                .findFirst().orElse(null);
        if (scenario == null) {
            // fail-closed：提交阶段清单状态漂移（发布被撤回等）不得静默进入场景
            throw exception(SCENARIO_MANUAL_UNAUTHORIZED, scenarioId);
        }
        sessionStateService.ensure(context.tenantId(), context.userId(), context.sessionId());
        scenarioSessionService.apply(context, new com.ontos.module.session.core.scenario.ScenarioRouter.RouteInput(
                ScenarioRouteState.FREE, null, scenarioId,
                com.ontos.module.session.core.scenario.ScenarioEntrySource.EXPLICIT,
                false, false, java.util.List.of(),
                Map.of(scenarioId, scenario),
                Set.of(scenarioId), java.util.List.of()));
        log.info("[commitExplicitRoute][场景状态已提交: session={} scenario={}]",
                context.sessionId(), scenarioId);
    }

    /** 清单侧模式白名单统一小写规范化（M6 全量深度审核 D-2：大小写不对称会误拒）。 */
    private static java.util.Set<String> normalizedModes(java.util.Collection<String> modes) {
        java.util.Set<String> normalized = new java.util.HashSet<>();
        for (String mode : modes) {
            if (mode != null) {
                normalized.add(mode.toLowerCase());
            }
        }
        return normalized;
    }
}

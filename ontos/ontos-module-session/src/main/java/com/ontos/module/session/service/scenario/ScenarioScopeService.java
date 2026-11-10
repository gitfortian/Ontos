package com.ontos.module.session.service.scenario;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ontos.framework.web.core.context.RequestContext;
import com.ontos.module.ontology.core.ManifestPayload;
import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.session.core.scenario.ScenarioRouteDecision;
import com.ontos.module.session.core.scenario.ScenarioRouteState;
import com.ontos.module.session.core.scenario.ScenarioRouter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Scenario 入口路由的瀑布接线（M6/W2/T2.4，§3.2 / §7.5 步 2–3）。
 *
 * <p><b>接入位置</b>：授权裁剪（{@code PolicySnapshotService#authorizedAssetIds}）之后、
 * L1 之前——由 {@link com.ontos.module.session.service.retrieval.MentionRetrievalService}
 * 调用 {@link #resolve}，返回值中的裁剪键集直接替换瀑布的授权键集。
 *
 * <p><b>裁剪语义（scope 只能收窄，不能扩大授权）</b>：
 * {@code free / pending_confirm} 态使用授权全域；{@code in_scenario} 态候选键 =
 * {@code authorizedKeys ∩ scopeKeys(scenario)}，其中 scopeKeys 由场景
 * {@code semantic_scope}（metrics/dimensions）+ {@code ontology_scope}（rootConcepts
 * 及其属性；excludedConcepts 连带其属性剔除）展开为 {@code type|assetId} 键。
 * 越界提案由既有来源完整性校验（候选集外 ID 拒绝）自动拦截。
 *
 * <p><b>确定性</b>：hint 匹配 = 话术包含 entry_hint（精确大小写子串，不做评分——决策 4）；
 * 无 LLM 参与路由。
 */
@Slf4j
@Service
public class ScenarioScopeService {

    /** 授权键中 Scenario 资产的类型前缀（PolicySnapshotService key 格式 type|assetId）。 */
    public static final String SCENARIO_KEY_PREFIX = "scenario|";

    private final ScenarioSessionService scenarioSessionService;

    public ScenarioScopeService(ScenarioSessionService scenarioSessionService) {
        this.scenarioSessionService = scenarioSessionService;
    }

    /** 瀑布单轮的场景裁决产出。 */
    public record ScopedRetrieval(ScenarioRouteDecision decision,
                                  Set<String> scopedAssetKeys,
                                  String scenarioId,
                                  ManifestPayload.ScenarioMeta scenario) {
    }

    /**
     * 路由决策 + scope 裁剪（瀑布前置）。显式 scenarioId / 退出不在此入口——
     * 直连 API 走 {@code ScenarioRouteGuard}，ask 确认走澄清回传通道。
     */
    public ScopedRetrieval resolve(RequestContext context, String utterance,
                                   PublishedManifest manifest, Set<String> authorizedKeys) {
        Map<String, ManifestPayload.ScenarioMeta> scenariosById =
                publishedScenarios(manifest);
        Set<String> authorizedScenarioIds = authorizedScenarioIds(authorizedKeys);
        List<String> hintMatches = hintMatches(utterance, scenariosById,
                authorizedScenarioIds);

        ScenarioSessionService.ScenarioSessionView current =
                scenarioSessionService.read(context);
        ScenarioRouteDecision decision = scenarioSessionService.apply(context,
                new ScenarioRouter.RouteInput(current.state(), current.scenarioId(),
                        null, null, false, false, hintMatches, scenariosById,
                        authorizedScenarioIds, List.copyOf(scenariosById.keySet())));

        // 本轮检索的可见域按「当前场景」裁剪（路由决策只影响下一轮；本轮命中的资产
        // 不能被本轮刚发生的状态迁移隐藏——free 态 auto 进入的依据恰恰是全域命中）。
        boolean inScenarioNow = current.state() == ScenarioRouteState.IN_SCENARIO
                && current.scenarioId() != null;
        String scenarioId = inScenarioNow ? current.scenarioId() : null;
        ManifestPayload.ScenarioMeta scenario = inScenarioNow
                ? scenariosById.get(current.scenarioId()) : null;
        Set<String> scopedKeys = scenario == null
                ? authorizedKeys : trimToScope(authorizedKeys, scenario, manifest);
        return new ScopedRetrieval(decision, scopedKeys, scenarioId, scenario);
    }

    /**
     * 越界二次路由（in_scenario 候选全空且授权域外有命中时由瀑布回呼）：
     * 产出 CLARIFY_OUT_OF_SCOPE 决策并落 pending_confirm（候选与状态同生同灭）。
     */
    public ScenarioRouteDecision clarifyOutOfScope(RequestContext context,
                                                   PublishedManifest manifest,
                                                   Set<String> authorizedKeys) {
        Map<String, ManifestPayload.ScenarioMeta> scenariosById =
                publishedScenarios(manifest);
        Set<String> authorizedScenarioIds = authorizedScenarioIds(authorizedKeys);
        ScenarioSessionService.ScenarioSessionView current =
                scenarioSessionService.read(context);
        return scenarioSessionService.apply(context, new ScenarioRouter.RouteInput(
                current.state(), current.scenarioId(), null, null, false, true,
                List.of(), scenariosById, authorizedScenarioIds,
                List.copyOf(scenariosById.keySet())));
    }

    /**
     * scope 键展开（§3.2 候选集 = authorized ∩ scope）：只返回场景声明的资产键；
     * excludedConcepts 及其属性连带剔除。属性随 rootConcepts 走（场景 = 业务域切片，
     * 场景内 lookup 需要域内概念的属性可投影）——概念→属性映射取清单本体事实。
     */
    public static Set<String> trimToScope(Set<String> authorizedKeys,
                                          ManifestPayload.ScenarioMeta scenario,
                                          PublishedManifest manifest) {
        Set<String> scopeKeys = scopeKeys(scenario, manifest);
        Set<String> result = new LinkedHashSet<>();
        for (String key : authorizedKeys) {
            if (scopeKeys.contains(key)) {
                result.add(key);
            }
        }
        return Set.copyOf(result);
    }

    /** 场景 scope 的全部资产键（metric|dimension|property|entity 前缀）。 */
    private static Set<String> scopeKeys(ManifestPayload.ScenarioMeta scenario,
                                         PublishedManifest manifest) {
        Set<String> keys = new LinkedHashSet<>();
        ManifestPayload.OntologyScope ontology = scenario.getOntologyScope();
        Set<String> roots = ontology == null ? Set.of()
                : new LinkedHashSet<>(ontology.getRootConcepts());
        if (ontology != null && ontology.getExcludedConcepts() != null) {
            roots.removeAll(ontology.getExcludedConcepts());
        }
        for (String concept : roots) {
            keys.add("entity|" + concept);
        }
        // 域内概念的属性全部纳入（lookup 投影列；被排除概念连带其属性剔除）
        if (manifest != null && manifest.getOntology() != null
                && manifest.getOntology().getProperties() != null) {
            for (var property : manifest.getOntology().getProperties()) {
                if (property.getConceptId() != null && roots.contains(property.getConceptId())
                        && property.getId() != null) {
                    keys.add("property|" + property.getId());
                }
            }
        }
        ManifestPayload.SemanticScope semantic = scenario.getSemanticScope();
        if (semantic != null) {
            for (String metric : semantic.getMetrics()) {
                keys.add("metric|" + metric);
            }
            for (String dimension : semantic.getDimensions()) {
                keys.add("dimension|" + dimension);
            }
        }
        return Set.copyOf(keys);
    }

    private static Map<String, ManifestPayload.ScenarioMeta> publishedScenarios(
            PublishedManifest manifest) {
        Map<String, ManifestPayload.ScenarioMeta> byId = new LinkedHashMap<>();
        if (manifest != null && manifest.getScenarios() != null) {
            for (ManifestPayload.ScenarioMeta scenario : manifest.getScenarios()) {
                if ("published".equals(scenario.getStatus())) {
                    byId.put(scenario.getId(), scenario);
                }
            }
        }
        return byId;
    }

    private static Set<String> authorizedScenarioIds(Set<String> authorizedKeys) {
        Set<String> ids = new LinkedHashSet<>();
        for (String key : authorizedKeys) {
            if (key.startsWith(SCENARIO_KEY_PREFIX)) {
                ids.add(key.substring(SCENARIO_KEY_PREFIX.length()));
            }
        }
        return Set.copyOf(ids);
    }

    /** entry_hints 确定性匹配：话术包含 hint（精确大小写子串）；仅已发布 + 已授权场景参与。 */
    private static List<String> hintMatches(String utterance,
                                            Map<String, ManifestPayload.ScenarioMeta> scenariosById,
                                            Set<String> authorizedScenarioIds) {
        List<String> matches = new ArrayList<>();
        String text = utterance == null ? "" : utterance.strip();
        if (text.isEmpty()) {
            return List.of();
        }
        for (ManifestPayload.ScenarioMeta scenario : scenariosById.values()) {
            if (!authorizedScenarioIds.contains(scenario.getId())) {
                continue;
            }
            for (String hint : scenario.getEntryHints()) {
                if (hint != null && !hint.isBlank() && text.contains(hint)) {
                    matches.add(scenario.getId());
                    break;
                }
            }
        }
        return List.copyOf(matches);
    }
}

package com.ontos.module.session.core.scenario;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ontos.module.ontology.core.ManifestPayload;

/**
 * Scenario Entry Router 的确定性决策核心（M6/W2/T2.3，§3.2）。
 *
 * <p><b>裁决顺序（ADR-0017 D1，T2.4 实现依据）</b>：
 * {@code ① 提交 scenarioId → ② 授权 + 已发布校验（失败即拒，不静默回退）
 * → ③ 通过后才可作为 manual 路由输入 → ④ 路由结果与 DSL scenarioId 比对}。
 * 校验前置是路由的准入条件，不是路由结果的校验项。
 *
 * <p>纯函数：不读库、不写库、不做 LLM 调用；授权集合由调用方（T2.4 授权裁剪后）传入，
 * scope 只能收窄不能扩大授权。
 */
public final class ScenarioRouter {

    private ScenarioRouter() {
    }

    /**
     * @param input 见 {@link RouteInput}；`hintMatches` 为 entry_hints 确定性命中的场景 ID
     *              （大小写精确匹配，调用方不做模糊评分——决策 4：不引入 LLM 置信度数值）
     */
    public static ScenarioRouteDecision decide(RouteInput input) {
        ScenarioRouteState current = input.currentState();

        // ① 显式退出：仅场景内有效；pending_confirm 的退出走确认候选
        if (input.exitRequested()) {
            if (current == ScenarioRouteState.IN_SCENARIO) {
                return exit();
            }
            return stay(current, input.currentScenarioId());
        }

        // ② 显式选择：授权 + 已发布校验前置（失败即拒，目标状态不变）
        if (input.explicitScenarioId() != null && !input.explicitScenarioId().isBlank()) {
            ManifestPayload.ScenarioMeta meta = input.scenariosById().get(input.explicitScenarioId());
            if (meta == null || !"published".equals(meta.getStatus())) {
                return reject(current, input.currentScenarioId(), input.explicitScenarioId());
            }
            if (!input.authorizedScenarioIds().contains(input.explicitScenarioId())) {
                return reject(current, input.currentScenarioId(), input.explicitScenarioId());
            }
            if (current == ScenarioRouteState.IN_SCENARIO
                    && input.explicitScenarioId().equals(input.currentScenarioId())) {
                return stay(current, input.currentScenarioId());
            }
            ScenarioEntrySource source = input.explicitSource() == null
                    ? ScenarioEntrySource.EXPLICIT : input.explicitSource();
            return new ScenarioRouteDecision(
                    ScenarioRouteDecision.Action.ENTER_SCENARIO, ScenarioRouteState.IN_SCENARIO,
                    input.explicitScenarioId(), source, null, List.of());
        }

        // ③ 场景内沿用 / 越界澄清
        if (current == ScenarioRouteState.IN_SCENARIO) {
            if (!input.outOfScope()) {
                return stay(current, input.currentScenarioId());
            }
            return clarifyOutOfScope(input);
        }

        // pending_confirm 等待确认：路由不再做新决策（确认走澄清回传通道）
        if (current == ScenarioRouteState.PENDING_CONFIRM) {
            return stay(current, input.currentScenarioId());
        }

        // ④ FREE：entry_hints 确定性匹配
        List<String> matches = usableMatches(input);
        if (matches.size() == 1) {
            String scenarioId = matches.get(0);
            ManifestPayload.ScenarioMeta meta = input.scenariosById().get(scenarioId);
            ScenarioEntryMode mode = ScenarioEntryMode.fromCode(meta.getEntryMode());
            if (mode.autoEnterable()) {
                return enter(scenarioId, ScenarioEntrySource.AUTO_CONFIDENT);
            }
            if (mode.recommendable()) {
                return askConfirm(scenarioId, meta.getName());
            }
            // manual_only 不参与推荐：命中也不进入，回退自由问数
            return fallback(current);
        }
        return fallback(current);
    }

    private static List<String> usableMatches(RouteInput input) {
        List<String> matches = new ArrayList<>();
        for (String scenarioId : input.hintMatches()) {
            ManifestPayload.ScenarioMeta meta = input.scenariosById().get(scenarioId);
            if (meta == null || !"published".equals(meta.getStatus())) {
                continue;
            }
            if (!input.authorizedScenarioIds().contains(scenarioId)) {
                continue;
            }
            matches.add(scenarioId);
        }
        return List.copyOf(matches);
    }

    private static ScenarioRouteDecision enter(String scenarioId,
                                                                     ScenarioEntrySource source) {
        return new ScenarioRouteDecision(
                ScenarioRouteDecision.Action.ENTER_SCENARIO, ScenarioRouteState.IN_SCENARIO,
                scenarioId, source, null, List.of());
    }

    private static ScenarioRouteDecision askConfirm(String scenarioId,
                                                                          String name) {
        List<ScenarioRouteDecision.PendingChoice> candidates =
                List.of(new ScenarioRouteDecision.PendingChoice(candidateIdOf(scenarioId),
                        "进入场景：" + name, scenarioId),
                        new ScenarioRouteDecision.PendingChoice(
                                ScenarioRouteDecision.EXIT_CANDIDATE_ID,
                                "留在自由问数", null));
        return new ScenarioRouteDecision(
                ScenarioRouteDecision.Action.ASK_CONFIRM, ScenarioRouteState.PENDING_CONFIRM,
                scenarioId, null, null, candidates);
    }

    private static ScenarioRouteDecision clarifyOutOfScope(RouteInput input) {
        List<ScenarioRouteDecision.PendingChoice> candidates = new ArrayList<>();
        candidates.add(new ScenarioRouteDecision.PendingChoice(
                ScenarioRouteDecision.EXIT_CANDIDATE_ID,
                "退出场景，回到自由问数", null));
        for (String scenarioId : input.candidateScenarios()) {
            if (scenarioId.equals(input.currentScenarioId())) {
                continue;
            }
            ManifestPayload.ScenarioMeta meta = input.scenariosById().get(scenarioId);
            candidates.add(new ScenarioRouteDecision.PendingChoice(candidateIdOf(scenarioId),
                    "切换到场景：" + (meta == null ? scenarioId : meta.getName()), scenarioId));
        }
        return new ScenarioRouteDecision(
                ScenarioRouteDecision.Action.CLARIFY_OUT_OF_SCOPE, ScenarioRouteState.PENDING_CONFIRM,
                input.currentScenarioId(), null, null, List.copyOf(candidates));
    }

    private static ScenarioRouteDecision exit() {
        return new ScenarioRouteDecision(
                ScenarioRouteDecision.Action.EXIT_SCENARIO, ScenarioRouteState.FREE,
                null, null, null, List.of());
    }

    private static ScenarioRouteDecision reject(ScenarioRouteState current,
                                                                      String currentScenarioId,
                                                                      String requested) {
        return new ScenarioRouteDecision(
                ScenarioRouteDecision.Action.REJECT_UNAUTHORIZED, current, currentScenarioId,
                null, ScenarioRouteDecision.REASON_UNAUTHORIZED,
                List.of(new ScenarioRouteDecision.PendingChoice(
                        ScenarioRouteDecision.EXIT_CANDIDATE_ID,
                        "退出场景，回到自由问数", null)));
    }

    private static ScenarioRouteDecision fallback(ScenarioRouteState current) {
        return new ScenarioRouteDecision(
                ScenarioRouteDecision.Action.FALLBACK_FREE, current, null,
                ScenarioEntrySource.FALLBACK_FREE_QUERY,
                ScenarioRouteDecision.REASON_LOW_CONFIDENCE, List.of());
    }

    private static ScenarioRouteDecision stay(ScenarioRouteState current,
                                                                    String scenarioId) {
        return new ScenarioRouteDecision(
                ScenarioRouteDecision.Action.STAY, current, scenarioId,
                ScenarioEntrySource.SESSION_INHERIT, null, List.of());
    }

    /** pending_confirm 候选 ID 的统一格式（退出候选除外）。 */
    public static String candidateIdOf(String scenarioId) {
        return "scenario:" + scenarioId;
    }

    /** 路由输入。 */
    public record RouteInput(ScenarioRouteState currentState,
                             String currentScenarioId,
                             String explicitScenarioId,
                             ScenarioEntrySource explicitSource,
                             boolean exitRequested,
                             boolean outOfScope,
                             List<String> hintMatches,
                             Map<String, ManifestPayload.ScenarioMeta> scenariosById,
                             Set<String> authorizedScenarioIds,
                             List<String> candidateScenarios) {
    }
}

package com.ontos.module.session.core.scenario;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ontos.module.ontology.core.ManifestPayload;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 入口路由五态的确定性决策锁定（M6/W2/T2.3，§3.2 五态 + entry_mode 三值语义）。
 * 裁决顺序 = ADR-0017 D1：授权 + 已发布校验前置，通过后才成为路由输入。
 */
class ScenarioRouterTest {

    private static final String SALES = "scenario.sales";
    private static final String MARKETING = "scenario.marketing";
    private static final String MANUAL = "scenario.manual_only";
    private static final String DRAFT = "scenario.draft";

    private final Map<String, ManifestPayload.ScenarioMeta> scenarios = Map.of(
            SALES, meta(SALES, "published", "auto_if_confident"),
            MARKETING, meta(MARKETING, "published", "recommend_or_confirm"),
            MANUAL, meta(MANUAL, "published", "manual_only"),
            DRAFT, meta(DRAFT, "draft", "recommend_or_confirm"));

    @Test
    void shouldEnterOnAuthorizedPublishedExplicitSelection() {
        ScenarioRouteDecision d = ScenarioRouter.decide(input(
                ScenarioRouteState.FREE, null, SALES, null, false, false,
                List.of(), Set.of(SALES)));
        assertEquals(ScenarioRouteDecision.Action.ENTER_SCENARIO, d.action());
        assertEquals(ScenarioRouteState.IN_SCENARIO, d.targetState());
        assertEquals(SALES, d.scenarioId());
        assertEquals(ScenarioEntrySource.EXPLICIT, d.source());
    }

    @Test
    void shouldRejectUnpublishedExplicitSelection() {
        ScenarioRouteDecision d = ScenarioRouter.decide(input(
                ScenarioRouteState.FREE, null, DRAFT, null, false, false,
                List.of(), Set.of(SALES)));
        assertEquals(ScenarioRouteDecision.Action.REJECT_UNAUTHORIZED, d.action());
        assertEquals(ScenarioRouteDecision.REASON_UNAUTHORIZED, d.reasonCode());
        assertEquals(ScenarioRouteState.FREE, d.targetState(), "拒绝不改变会话状态");
    }

    @Test
    void shouldRejectUnauthorizedExplicitSelection() {
        ScenarioRouteDecision d = ScenarioRouter.decide(input(
                ScenarioRouteState.FREE, null, MARKETING, null, false, false,
                List.of(), Set.of(SALES)));
        assertEquals(ScenarioRouteDecision.Action.REJECT_UNAUTHORIZED, d.action());
    }

    @Test
    void shouldStayWhenReselectingCurrentScenario() {
        ScenarioRouteDecision d = ScenarioRouter.decide(input(
                ScenarioRouteState.IN_SCENARIO, SALES, SALES, null, false, false,
                List.of(), Set.of(SALES)));
        assertEquals(ScenarioRouteDecision.Action.STAY, d.action());
        assertEquals(ScenarioEntrySource.SESSION_INHERIT, d.source());
    }

    @Test
    void shouldSwitchScenarioOnExplicitDifferentSelection() {
        ScenarioRouteDecision d = ScenarioRouter.decide(input(
                ScenarioRouteState.IN_SCENARIO, SALES, MARKETING, null, false, false,
                List.of(), Set.of(SALES, MARKETING)));
        assertEquals(ScenarioRouteDecision.Action.ENTER_SCENARIO, d.action());
        assertEquals(MARKETING, d.scenarioId());
    }

    @Test
    void shouldExitOnlyFromInScenario() {
        assertEquals(ScenarioRouteDecision.Action.EXIT_SCENARIO,
                ScenarioRouter.decide(input(ScenarioRouteState.IN_SCENARIO, SALES, null, null,
                        true, false, List.of(), Set.of(SALES))).action());
        assertEquals(ScenarioRouteDecision.Action.STAY,
                ScenarioRouter.decide(input(ScenarioRouteState.FREE, null, null, null,
                        true, false, List.of(), Set.of(SALES))).action());
    }

    @Test
    void shouldStayInScenarioWhenQuestionInScope() {
        ScenarioRouteDecision d = ScenarioRouter.decide(input(
                ScenarioRouteState.IN_SCENARIO, SALES, null, null, false, false,
                List.of(), Set.of(SALES)));
        assertEquals(ScenarioRouteDecision.Action.STAY, d.action());
        assertEquals(ScenarioEntrySource.SESSION_INHERIT, d.source());
    }

    @Test
    void shouldAskConfirmWhenOutOfScope() {
        ScenarioRouteDecision d = ScenarioRouter.decide(input(
                ScenarioRouteState.IN_SCENARIO, SALES, null, null, false, true,
                List.of(), Set.of(SALES, MARKETING), List.of(MARKETING)));
        assertEquals(ScenarioRouteDecision.Action.CLARIFY_OUT_OF_SCOPE, d.action());
        assertEquals(ScenarioRouteState.PENDING_CONFIRM, d.targetState());
        assertEquals(SALES, d.scenarioId(), "pending_confirm 挂在当前场景上");
        assertEquals(2, d.candidates().size(), "退出 + 切换候选");
        assertTrue(d.candidates().stream().anyMatch(c ->
                ScenarioRouteDecision.EXIT_CANDIDATE_ID.equals(c.candidateId())));
        assertTrue(d.candidates().stream().anyMatch(c -> MARKETING.equals(c.assetId())));
    }

    @Test
    void shouldAutoEnterWhenUniqueConfidentMatch() {
        ScenarioRouteDecision d = ScenarioRouter.decide(input(
                ScenarioRouteState.FREE, null, null, null, false, false,
                List.of(SALES), Set.of(SALES)));
        assertEquals(ScenarioRouteDecision.Action.ENTER_SCENARIO, d.action());
        assertEquals(ScenarioEntrySource.AUTO_CONFIDENT, d.source());
    }

    @Test
    void shouldAskConfirmWhenUniqueMatchIsRecommendOnly() {
        ScenarioRouteDecision d = ScenarioRouter.decide(input(
                ScenarioRouteState.FREE, null, null, null, false, false,
                List.of(MARKETING), Set.of(MARKETING)));
        assertEquals(ScenarioRouteDecision.Action.ASK_CONFIRM, d.action());
        assertEquals(ScenarioRouteState.PENDING_CONFIRM, d.targetState());
        assertEquals(2, d.candidates().size());
    }

    @Test
    void shouldFallbackWhenUniqueMatchIsManualOnly() {
        ScenarioRouteDecision d = ScenarioRouter.decide(input(
                ScenarioRouteState.FREE, null, null, null, false, false,
                List.of(MANUAL), Set.of(MANUAL)));
        assertEquals(ScenarioRouteDecision.Action.FALLBACK_FREE, d.action());
        assertEquals(ScenarioRouteDecision.REASON_LOW_CONFIDENCE, d.reasonCode());
    }

    @Test
    void shouldFallbackOnAmbiguousOrUnauthorizedHints() {
        assertEquals(ScenarioRouteDecision.Action.FALLBACK_FREE,
                ScenarioRouter.decide(input(ScenarioRouteState.FREE, null, null, null,
                        false, false, List.of(SALES, MARKETING), Set.of(SALES, MARKETING))).action());
        assertEquals(ScenarioRouteDecision.Action.FALLBACK_FREE,
                ScenarioRouter.decide(input(ScenarioRouteState.FREE, null, null, null,
                        false, false, List.of(MARKETING), Set.of(SALES))).action(),
                "未授权命中不进推荐");
        assertEquals(ScenarioRouteDecision.Action.FALLBACK_FREE,
                ScenarioRouter.decide(input(ScenarioRouteState.FREE, null, null, null,
                        false, false, List.of(), Set.of(SALES))).action(),
                "无命中回退自由问数");
    }

    @Test
    void shouldStayInPendingConfirmWhileAwaitingUserChoice() {
        ScenarioRouteDecision d = ScenarioRouter.decide(input(
                ScenarioRouteState.PENDING_CONFIRM, SALES, null, null, false, false,
                List.of(SALES), Set.of(SALES)));
        assertEquals(ScenarioRouteDecision.Action.STAY, d.action());
    }

    @Test
    void shouldUseCandidateIdFormatForScenarios() {
        assertEquals("scenario:" + SALES, ScenarioRouter.candidateIdOf(SALES));
    }

    private ScenarioRouter.RouteInput input(ScenarioRouteState state, String currentScenarioId,
                                            String explicitScenarioId, ScenarioEntrySource explicitSource,
                                            boolean exitRequested, boolean outOfScope,
                                            List<String> hintMatches,
                                            java.util.Set<String> authorized) {
        return new ScenarioRouter.RouteInput(state, currentScenarioId, explicitScenarioId,
                explicitSource, exitRequested, outOfScope, hintMatches, scenarios, authorized,
                List.of());
    }

    private ScenarioRouter.RouteInput input(ScenarioRouteState state, String currentScenarioId,
                                            String explicitScenarioId, ScenarioEntrySource explicitSource,
                                            boolean exitRequested, boolean outOfScope,
                                            List<String> hintMatches,
                                            java.util.Set<String> authorized,
                                            List<String> candidateScenarios) {
        return new ScenarioRouter.RouteInput(state, currentScenarioId, explicitScenarioId,
                explicitSource, exitRequested, outOfScope, hintMatches, scenarios, authorized,
                candidateScenarios);
    }

    private static ManifestPayload.ScenarioMeta meta(String id, String status, String entryMode) {
        ManifestPayload.ScenarioMeta scenario = new ManifestPayload.ScenarioMeta();
        scenario.setId(id);
        scenario.setName(id);
        scenario.setStatus(status);
        scenario.setEntryMode(entryMode);
        return scenario;
    }
}

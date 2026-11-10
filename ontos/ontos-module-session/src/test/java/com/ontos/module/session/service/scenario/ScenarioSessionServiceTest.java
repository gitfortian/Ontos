package com.ontos.module.session.service.scenario;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ontos.framework.common.exception.ServiceException;
import com.ontos.framework.web.core.context.RequestContext;
import com.ontos.module.session.core.scenario.ScenarioRouteDecision;
import com.ontos.module.session.core.scenario.ScenarioEntrySource;
import com.ontos.module.session.core.scenario.ScenarioRouteState;
import com.ontos.module.session.core.scenario.ScenarioRouter;
import com.ontos.module.session.dal.dataobject.SessionStateDO;
import com.ontos.module.session.dal.mysql.SessionStateMapper;
import com.ontos.module.ontology.core.ManifestPayload;
import com.ontos.module.session.service.session.SessionStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Scenario 会话状态服务（M6/W2/T2.3）：迁移持久化、pending_confirm 建立与确认
 * （复用 M5 澄清回传校验，ADR-0017 D3）。
 */
class ScenarioSessionServiceTest {

    private static final String SALES = "scenario.sales";

    private final RequestContext context = new RequestContext(
            "demo", "alice", "s-1", 1, "manifest", "trace", Set.of(), false, null);

    private SessionStateService sessionStateService;
    private SessionStateMapper mapper;
    private ScenarioSessionService service;

    @BeforeEach
    void setUp() {
        sessionStateService = mock(SessionStateService.class);
        mapper = mock(SessionStateMapper.class);
        service = new ScenarioSessionService(sessionStateService, mapper);
    }

    @Test
    void shouldPersistEnterTransition() {
        when(sessionStateService.read(any(), any(), any())).thenReturn(emptyState());

        ScenarioRouteDecision decision = service.apply(context, new ScenarioRouter.RouteInput(
                ScenarioRouteState.FREE, null, SALES, ScenarioEntrySource.EXPLICIT,
                false, false, List.of(), scenariosMeta(), Set.of(SALES), List.of()));

        assertEquals(ScenarioRouteDecision.Action.ENTER_SCENARIO, decision.action());
        verify(mapper).updateScenarioState(eq("demo"), eq("alice"), eq("s-1"),
                org.mockito.ArgumentMatchers.contains("in_scenario"));
        verify(mapper).updateScenarioState(eq("demo"), eq("alice"), eq("s-1"),
                org.mockito.ArgumentMatchers.contains(SALES));
    }

    @Test
    void shouldPersistPendingConfirmWithCandidatesAtomically() {
        when(sessionStateService.read(any(), any(), any())).thenReturn(emptyState());

        ScenarioRouteDecision decision = service.apply(context, new ScenarioRouter.RouteInput(
                ScenarioRouteState.FREE, null, null, null, false, false,
                List.of("scenario.marketing"), scenariosMeta(), Set.of("scenario.marketing"), List.of()));

        assertEquals(ScenarioRouteDecision.Action.ASK_CONFIRM, decision.action());
        verify(mapper).updateScenarioStateWithClarification(eq("demo"), eq("alice"), eq("s-1"),
                org.mockito.ArgumentMatchers.contains("pending_confirm"),
                org.mockito.ArgumentMatchers.contains("scenario:scenario.marketing"));
    }

    @Test
    void shouldThrowUnauthorizedOnRejectDecision() {
        when(sessionStateService.read(any(), any(), any())).thenReturn(emptyState());

        ServiceException ex = assertThrows(ServiceException.class, () -> service.apply(context,
                new ScenarioRouter.RouteInput(ScenarioRouteState.FREE, null, "scenario.draft",
                        null, false, false, List.of(), scenariosMeta(), Set.of(SALES), List.of())));
        assertEquals(1_004_000_003, ex.getCode());
    }

    @Test
    void shouldConfirmEntryViaClarificationChannel() {
        when(sessionStateService.read(any(), any(), any())).thenReturn(pendingState());
        when(mapper.consumeClarificationAndUpdateScenarioState(eq("demo"), eq("alice"), eq("s-1"),
                eq("scenario:" + SALES), org.mockito.ArgumentMatchers.contains("in_scenario")))
                .thenReturn(true);

        ScenarioRouteDecision decision = service.confirm(context, "scenario:" + SALES,
                scenariosMeta(), Set.of(SALES));

        assertEquals(ScenarioRouteDecision.Action.ENTER_SCENARIO, decision.action());
        assertEquals(ScenarioEntrySource.CONFIRMED_RECOMMENDATION, decision.source());
        // T23-7：消费与状态写入必须同一条 UPDATE，不得再出现分离调用
        verify(mapper, org.mockito.Mockito.never())
                .updateScenarioState(any(), any(), any(), any());
        verify(sessionStateService, org.mockito.Mockito.never())
                .consumeClarificationChoice(any(), any());
    }

    @Test
    void shouldConfirmExitViaClarificationChannel() {
        when(sessionStateService.read(any(), any(), any())).thenReturn(pendingState());
        when(mapper.consumeClarificationAndUpdateScenarioState(eq("demo"), eq("alice"), eq("s-1"),
                eq(ScenarioRouteDecision.EXIT_CANDIDATE_ID), org.mockito.ArgumentMatchers.contains("free")))
                .thenReturn(true);

        ScenarioRouteDecision decision = service.confirm(context,
                ScenarioRouteDecision.EXIT_CANDIDATE_ID, scenariosMeta(), Set.of(SALES));

        assertEquals(ScenarioRouteDecision.Action.EXIT_SCENARIO, decision.action());
        assertEquals(ScenarioRouteState.FREE, decision.targetState());
    }

    @Test
    void shouldRejectConfirmWhenCandidateConcurrentlyConsumed() {
        // T23-7：并发下候选已被另一请求消费 → 原子 UPDATE 影响 0 行，拒绝且不再写状态
        when(sessionStateService.read(any(), any(), any())).thenReturn(pendingState());
        when(mapper.consumeClarificationAndUpdateScenarioState(any(), any(), any(),
                any(), any())).thenReturn(false);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.confirm(context, "scenario:" + SALES, scenariosMeta(), Set.of(SALES)));

        assertEquals(1_004_000_002, ex.getCode());
        verify(mapper, org.mockito.Mockito.never())
                .updateScenarioState(any(), any(), any(), any());
    }

    @Test
    void shouldClearStaleCandidatesOnExplicitEnterFromPending() {
        // T23-8：pending_confirm 中直传已授权场景 → 显式 ENTER，状态写入必须同时清残留候选
        SessionStateDO stale = pendingState();
        when(sessionStateService.read(any(), any(), any())).thenReturn(stale);

        ScenarioRouteDecision decision = service.apply(context, new ScenarioRouter.RouteInput(
                ScenarioRouteState.PENDING_CONFIRM, SALES, SALES, ScenarioEntrySource.EXPLICIT,
                false, false, List.of(), scenariosMeta(), Set.of(SALES), List.of()));

        assertEquals(ScenarioRouteDecision.Action.ENTER_SCENARIO, decision.action());
        assertEquals(ScenarioRouteState.IN_SCENARIO, decision.targetState());
        verify(mapper).updateScenarioState(eq("demo"), eq("alice"), eq("s-1"),
                org.mockito.ArgumentMatchers.contains("in_scenario"));
        verify(mapper, org.mockito.Mockito.never())
                .updateScenarioStateWithClarification(any(), any(), any(), any(), any());
    }

    @Test
    void shouldRejectForgedConfirmationCandidate() {
        when(sessionStateService.read(any(), any(), any())).thenReturn(pendingState());
        // 候选集合里只有 scenario:scenario.sales 与退出——伪造 ID 在消费前就被拒
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.confirm(context, "scenario:scenario.unknown", scenariosMeta(), Set.of(SALES)));
        assertEquals(1_004_000_002, ex.getCode());
    }

    @Test
    void shouldRejectConfirmWhenNotPending() {
        when(sessionStateService.read(any(), any(), any())).thenReturn(emptyState());
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.confirm(context, "scenario:" + SALES, scenariosMeta(), Set.of(SALES)));
        assertEquals(1_004_000_004, ex.getCode());
    }

    @Test
    void shouldExitFromInScenario() {
        when(sessionStateService.read(any(), any(), any())).thenReturn(inScenarioState());

        ScenarioRouteDecision decision = service.exit(context);

        assertEquals(ScenarioRouteDecision.Action.EXIT_SCENARIO, decision.action());
        verify(mapper).updateScenarioState(eq("demo"), eq("alice"), eq("s-1"),
                org.mockito.ArgumentMatchers.contains("free"));
    }

    private SessionStateDO emptyState() {
        SessionStateDO row = new SessionStateDO();
        row.setScenarioState("{\"state\": \"free\", \"scenarioId\": null}");
        return row;
    }

    private SessionStateDO inScenarioState() {
        SessionStateDO row = new SessionStateDO();
        row.setScenarioState("{\"state\": \"in_scenario\", \"scenarioId\": \"" + SALES + "\"}");
        return row;
    }

    private SessionStateDO pendingState() {
        SessionStateDO row = new SessionStateDO();
        row.setScenarioState("{\"state\": \"pending_confirm\", \"scenarioId\": \"" + SALES + "\"}");
        String candidates = "[{\"candidateId\": \"scenario:" + SALES + "\","
                + " \"label\": \"进入场景\", \"assetId\": \"" + SALES + "\"},"
                + " {\"candidateId\": \"" + ScenarioRouteDecision.EXIT_CANDIDATE_ID + "\","
                + " \"label\": \"退出\", \"assetId\": null}]";
        row.setPendingClarification("{\"ambiguity\": \"scenario_confirm\","
                + " \"candidates\": " + candidates + ","
                + " \"defaultSuggestion\": null, \"degradedBehavior\": null}");
        return row;
    }

    private Map<String, ManifestPayload.ScenarioMeta> scenariosMeta() {
        Map<String, ManifestPayload.ScenarioMeta> map = new java.util.LinkedHashMap<>();
        map.put(SALES, meta(SALES, "published", "auto_if_confident"));
        map.put("scenario.marketing", meta("scenario.marketing", "published", "recommend_or_confirm"));
        map.put("scenario.draft", meta("scenario.draft", "draft", "recommend_or_confirm"));
        return map;
    }

    private ManifestPayload.ScenarioMeta meta(String id, String status, String entryMode) {
        ManifestPayload.ScenarioMeta scenario = new ManifestPayload.ScenarioMeta();
        scenario.setId(id);
        scenario.setName(id);
        scenario.setStatus(status);
        scenario.setEntryMode(entryMode);
        return scenario;
    }


}

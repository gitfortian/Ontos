package com.ontos.module.session.service.scenario;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ontos.framework.common.exception.ServiceException;
import com.ontos.framework.web.core.context.RequestContext;
import com.ontos.module.ontology.core.ManifestPayload;
import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.ontology.service.manifest.ManifestService;
import com.ontos.module.query.core.scenario.ScenarioRouteGuard;
import com.ontos.module.session.core.scenario.ScenarioRouteDecision;
import com.ontos.module.session.core.scenario.ScenarioRouteState;
import com.ontos.module.session.core.scenario.ScenarioRouter;
import com.ontos.module.session.service.session.SessionStateService;
import com.ontos.module.system.service.policy.PolicySnapshotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DSL scenarioId 服务端裁决（M6/W2/T2.4，ADR-0017 D1）：
 * 未发布 / 未授权 / 模式越界 / 口径冲突全拒；显式进入落会话状态。
 */
class ScenarioRouteGuardImplTest {

    private static final String SALES = "scenario.sales";

    private final ManifestService manifestService = mock(ManifestService.class);
    private final PolicySnapshotService policySnapshotService = mock(PolicySnapshotService.class);
    private final ScenarioSessionService scenarioSessionService = mock(ScenarioSessionService.class);
    private final SessionStateService sessionStateService = mock(SessionStateService.class);
    private final ScenarioRouteGuard guard = new ScenarioRouteGuardImpl(manifestService,
            policySnapshotService, scenarioSessionService, sessionStateService);
    private final RequestContext context =
            new RequestContext("t1", "u1", "s1", 1, null, "tr1", Set.of(), false);

    @BeforeEach
    void setUp() {
        when(manifestService.getActiveManifestOrNull()).thenReturn(manifest());
        when(policySnapshotService.authorizedAssetIds(any())).thenReturn(
                Set.of("scenario|" + SALES, "metric|metric.gmv"));
        when(sessionStateService.read(any(), any(), any())).thenReturn(emptyRow());
        when(scenarioSessionService.read(any())).thenReturn(
                new ScenarioSessionService.ScenarioSessionView(ScenarioRouteState.FREE, null));
        when(scenarioSessionService.apply(any(), any())).thenReturn(new ScenarioRouteDecision(
                ScenarioRouteDecision.Action.ENTER_SCENARIO, ScenarioRouteState.IN_SCENARIO,
                SALES, com.ontos.module.session.core.scenario.ScenarioEntrySource.EXPLICIT,
                null, List.of()));
    }

    // ---------- 放行 ----------

    @Test
    void blankScenarioIdPassesWithoutAnyLookup() {
        assertDoesNotThrow(() -> guard.adjudicate(context, null, "query", null, null));
        assertDoesNotThrow(() -> guard.adjudicate(context, "  ", "query", null, null));
        verify(manifestService, never()).getActiveManifestOrNull();
    }

    @Test
    void explicitAuthorizedScenarioEntersAndPersists() {
        assertDoesNotThrow(() -> guard.adjudicate(context, SALES, "query", "dim.order_date", List.of("metric.gmv")));

        org.mockito.ArgumentCaptor<ScenarioRouter.RouteInput> captor =
                org.mockito.ArgumentCaptor.forClass(ScenarioRouter.RouteInput.class);
        verify(scenarioSessionService).apply(any(), captor.capture());
        assertEquals(SALES, captor.getValue().explicitScenarioId());
        verify(sessionStateService).ensure(any(), any(), any());
    }

    // ---------- 拒绝（fail-closed） ----------

    @Test
    void unpublishedScenarioIsRejectedWithoutLeakingExistence() {
        when(manifestService.getActiveManifestOrNull()).thenReturn(manifest());
        ServiceException ex = assertThrows(ServiceException.class,
                () -> guard.adjudicate(context, "scenario.draft", "query", null, null));
        assertEquals(1_004_000_003, ex.getCode());
        // 不存在与未发布同形（清单里没有的 ID 同码）
        ServiceException missing = assertThrows(ServiceException.class,
                () -> guard.adjudicate(context, "scenario.unknown", "query", null, null));
        assertEquals(1_004_000_003, missing.getCode());
    }

    @Test
    void unauthorizedScenarioIsRejected() {
        when(policySnapshotService.authorizedAssetIds(any())).thenReturn(Set.of("metric|metric.gmv"));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> guard.adjudicate(context, SALES, "query", null, null));
        assertEquals(1_004_000_003, ex.getCode());
        verify(scenarioSessionService, never()).apply(any(), any());
    }

    @Test
    void forbiddenAnalysisModeIsRejected() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> guard.adjudicate(context, SALES, "funnel", null, null));
        assertEquals(1_004_000_005, ex.getCode());
    }

    @Test
    void caliberConflictIsRejectedNotOverridden() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> guard.adjudicate(context, SALES, "query", "dim.order_date_custom", null));
        assertEquals(1_004_000_006, ex.getCode());
    }

    @Test
    void referencedAssetOutsideScenarioScopeIsRejectedEvenIfAuthorized() {
        // T24-5：显式带场景 = 声明在该业务域内查询；场景外资产即使已授权也拒绝
        ServiceException ex = assertThrows(ServiceException.class,
                () -> guard.adjudicate(context, SALES, "query", "dim.order_date",
                        List.of("metric.gmv", "metric.refund_amount")));
        assertEquals(1_004_000_007, ex.getCode());
    }

    @Test
    void referencedAssetsInsideScenarioScopePass() {
        // 场景内资产（含域内概念属性）全部通过
        assertDoesNotThrow(() -> guard.adjudicate(context, SALES, "query", "dim.order_date",
                List.of("metric.gmv", "dim.region", "property.customer.email", "concept.order")));
    }

    @Test
    void missingScenarioAuthorizationFailsClosedEvenForPublished() {
        // 授权快照缺租户 → authorizedAssetIds 抛错向上传播（不默认拒绝清单内场景）
        when(policySnapshotService.authorizedAssetIds(any()))
                .thenThrow(new ServiceException(1_003_000_001, "快照缺失"));
        org.junit.jupiter.api.Assertions.assertThrows(ServiceException.class,
                () -> guard.adjudicate(context, SALES, "query", null, null));
    }

    // ---------- fixtures ----------

    private com.ontos.module.session.dal.dataobject.SessionStateDO emptyRow() {
        com.ontos.module.session.dal.dataobject.SessionStateDO row =
                new com.ontos.module.session.dal.dataobject.SessionStateDO();
        row.setScenarioState("{\"state\": \"free\"}");
        return row;
    }

    private PublishedManifest manifest() {
        PublishedManifest manifest = new PublishedManifest();
        manifest.setManifestId("m-test");

        ManifestPayload.ScenarioMeta sales = new ManifestPayload.ScenarioMeta();
        sales.setId(SALES);
        sales.setName("销售分析");
        sales.setStatus("published");
        sales.setEntryMode("auto_if_confident");
        sales.setAllowedAnalysisModes(List.of("query"));
        ManifestPayload.OntologyScope ontologyScope = new ManifestPayload.OntologyScope();
        ontologyScope.setRootConcepts(List.of("concept.order", "concept.customer"));
        ontologyScope.setExcludedConcepts(List.of());
        sales.setOntologyScope(ontologyScope);
        ManifestPayload.SemanticScope semantic = new ManifestPayload.SemanticScope();
        semantic.setMetrics(List.of("metric.gmv"));
        semantic.setDimensions(List.of("dim.region"));
        ManifestPayload.FrozenCaliber defaultTimeField = new ManifestPayload.FrozenCaliber();
        defaultTimeField.setType("field_id");
        defaultTimeField.setValue("dim.order_date");
        semantic.setFrozenCalibers(Map.of("default_time_field", defaultTimeField));
        sales.setSemanticScope(semantic);

        ManifestPayload.ScenarioMeta draft = new ManifestPayload.ScenarioMeta();
        draft.setId("scenario.draft");
        draft.setName("草稿");
        draft.setStatus("draft");
        draft.setEntryMode("manual_only");

        manifest.setScenarios(List.of(sales, draft));

        com.ontos.module.ontology.core.OntologySnapshot ontology =
                new com.ontos.module.ontology.core.OntologySnapshot();
        com.ontos.module.ontology.core.OntologySnapshot.Property email =
                new com.ontos.module.ontology.core.OntologySnapshot.Property();
        email.setId("property.customer.email");
        email.setConceptId("concept.customer");
        com.ontos.module.ontology.core.OntologySnapshot.Property orderNo =
                new com.ontos.module.ontology.core.OntologySnapshot.Property();
        orderNo.setId("property.order.order_no");
        orderNo.setConceptId("concept.order");
        ontology.setProperties(List.of(email, orderNo));
        manifest.setOntology(ontology);
        return manifest;
    }
}

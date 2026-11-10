package com.ontos.module.session.service.scenario;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ontos.framework.web.core.context.RequestContext;
import com.ontos.module.ontology.core.ManifestPayload;
import com.ontos.module.ontology.core.OntologySnapshot;
import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.session.core.scenario.ScenarioRouteDecision;
import com.ontos.module.session.core.scenario.ScenarioRouteState;
import com.ontos.module.session.core.scenario.ScenarioRouter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Scenario 入口路由瀑布接线（M6/W2/T2.4）：scope 裁剪（只收窄）、属性随域内概念展开、
 * hint 确定性匹配输入、free 态全域透传。
 */
class ScenarioScopeServiceTest {

    private final ScenarioSessionService sessionService = mock(ScenarioSessionService.class);
    private final ScenarioScopeService service = new ScenarioScopeService(sessionService);
    private final RequestContext context =
            new RequestContext("t1", "u1", "s1", 1, null, "tr1", Set.of(), false);

    // ---------- scope 裁剪 ----------

    @Test
    void trimKeepsInScopeAssetsAndDropsOutOfScopeMetrics() {
        Set<String> scoped = ScenarioScopeService.trimToScope(authorizedKeys(),
                scenario(), manifest());

        assertTrue(scoped.contains("metric|metric.gmv"), "场景声明的指标保留");
        assertTrue(scoped.contains("metric|metric.order_count"), "场景声明的指标保留");
        assertTrue(scoped.contains("dimension|dim.region"), "场景声明的维度保留");
        assertFalse(scoped.contains("metric|metric.refund_amount"), "授权但不在场景 scope → 裁掉");
        assertFalse(scoped.contains("dimension|dim.channel"), "未声明维度 → 裁掉");
    }

    @Test
    void trimIncludesPropertiesOfInScopeConceptsAndExcludesExcludedOnes() {
        Set<String> scoped = ScenarioScopeService.trimToScope(authorizedKeys(),
                scenario(), manifest());

        assertTrue(scoped.contains("entity|concept.order"), "rootConcept 保留");
        assertTrue(scoped.contains("property|property.order.order_no"), "域内概念属性随概念展开");
        assertTrue(scoped.contains("property|property.customer.email"), "域内概念属性随概念展开");
    }

    @Test
    void trimIsPureIntersectionNeverExpandsAuthorization() {
        // 场景 scope 声明了未授权资产 → 不得出现在裁剪结果（scope 只收窄不扩大）
        ManifestPayload.ScenarioMeta greedy = scenario();
        ManifestPayload.SemanticScope semantic = greedy.getSemanticScope();
        semantic.setMetrics(List.of("metric.gmv", "metric.secret"));
        Set<String> scoped = ScenarioScopeService.trimToScope(
                Set.of("metric|metric.gmv"), greedy, manifest());
        assertFalse(scoped.contains("metric|metric.secret"), "scope 不扩大授权");
    }

    // ---------- resolve：路由接线 ----------

    @Test
    void freeStatePassesFullAuthorizedScopeAndStays() {
        when(sessionService.read(any())).thenReturn(
                new ScenarioSessionService.ScenarioSessionView(ScenarioRouteState.FREE, null));
        ScenarioRouteDecision stay = stayFree();
        when(sessionService.apply(any(), any())).thenReturn(stay);

        ScenarioScopeService.ScopedRetrieval scoped =
                service.resolve(context, "成交金额", manifest(), authorizedKeys());

        assertEquals(ScenarioRouteDecision.Action.STAY, scoped.decision().action());
        assertNull(scoped.scenarioId());
        assertEquals(authorizedKeys(), scoped.scopedAssetKeys(), "free 态不裁剪（授权全域）");
    }

    @Test
    void hintMatchIsPassedToRouterDeterministically() {
        when(sessionService.read(any())).thenReturn(
                new ScenarioSessionService.ScenarioSessionView(ScenarioRouteState.FREE, null));
        when(sessionService.apply(any(), any())).thenReturn(stayFree());

        service.resolve(context, "帮我看看订单趋势", manifest(), authorizedKeys());

        org.mockito.ArgumentCaptor<ScenarioRouter.RouteInput> captor =
                org.mockito.ArgumentCaptor.forClass(ScenarioRouter.RouteInput.class);
        verify(sessionService).apply(eq(context), captor.capture());
        assertEquals(List.of("scenario.sales"), captor.getValue().hintMatches(),
                "entry_hints 确定性子串命中（决策 4：不评分）");
    }

    @Test
    void noHintMatchYieldsEmptyHintList() {
        when(sessionService.read(any())).thenReturn(
                new ScenarioSessionService.ScenarioSessionView(ScenarioRouteState.FREE, null));
        when(sessionService.apply(any(), any())).thenReturn(stayFree());

        service.resolve(context, "随便看看", manifest(), authorizedKeys());

        org.mockito.ArgumentCaptor<ScenarioRouter.RouteInput> captor =
                org.mockito.ArgumentCaptor.forClass(ScenarioRouter.RouteInput.class);
        verify(sessionService).apply(eq(context), captor.capture());
        assertTrue(captor.getValue().hintMatches().isEmpty());
    }

    @Test
    void inScenarioDecisionCarriesScenarioMetaAndTrimmedKeys() {
        when(sessionService.read(any())).thenReturn(
                new ScenarioSessionService.ScenarioSessionView(ScenarioRouteState.IN_SCENARIO,
                        "scenario.sales"));
        ScenarioRouteDecision enter = new ScenarioRouteDecision(
                ScenarioRouteDecision.Action.ENTER_SCENARIO, ScenarioRouteState.IN_SCENARIO,
                "scenario.sales", com.ontos.module.session.core.scenario.ScenarioEntrySource.AUTO_CONFIDENT,
                null, List.of());
        when(sessionService.apply(any(), any())).thenReturn(enter);

        ScenarioScopeService.ScopedRetrieval scoped =
                service.resolve(context, "成交金额", manifest(), authorizedKeys());

        assertEquals("scenario.sales", scoped.scenarioId());
        assertFalse(scoped.scopedAssetKeys().contains("metric|metric.refund_amount"),
                "in_scenario 态裁剪生效");
        assertTrue(scoped.scopedAssetKeys().contains("metric|metric.gmv"));
    }

    // ---------- fixtures ----------

    private Set<String> authorizedKeys() {
        return Set.of("metric|metric.gmv", "metric|metric.order_count",
                "metric|metric.refund_amount", "dimension|dim.region", "dimension|dim.channel",
                "entity|concept.order", "entity|concept.customer",
                "property|property.order.order_no", "property|property.customer.email",
                "scenario|scenario.sales");
    }

    private ManifestPayload.ScenarioMeta scenario() {
        ManifestPayload.ScenarioMeta scenario = new ManifestPayload.ScenarioMeta();
        scenario.setId("scenario.sales");
        scenario.setName("销售分析");
        scenario.setStatus("published");
        scenario.setEntryMode("auto_if_confident");
        scenario.setEntryHints(List.of("成交金额分析", "订单趋势", "渠道对比"));

        ManifestPayload.OntologyScope ontologyScope = new ManifestPayload.OntologyScope();
        ontologyScope.setRootConcepts(List.of("concept.order", "concept.customer"));
        ontologyScope.setExcludedConcepts(List.of());
        scenario.setOntologyScope(ontologyScope);

        ManifestPayload.SemanticScope semanticScope = new ManifestPayload.SemanticScope();
        semanticScope.setMetrics(List.of("metric.gmv", "metric.order_count"));
        semanticScope.setDimensions(List.of("dim.region"));
        scenario.setSemanticScope(semanticScope);
        scenario.setAllowedAnalysisModes(List.of("query"));
        return scenario;
    }

    private PublishedManifest manifest() {
        PublishedManifest manifest = new PublishedManifest();
        manifest.setManifestId("m-test");
        manifest.setContentHash("hash-test");
        manifest.setScenarios(List.of(scenario()));

        OntologySnapshot ontology = new OntologySnapshot();
        OntologySnapshot.Property orderNo = new OntologySnapshot.Property();
        orderNo.setId("property.order.order_no");
        orderNo.setConceptId("concept.order");
        OntologySnapshot.Property email = new OntologySnapshot.Property();
        email.setId("property.customer.email");
        email.setConceptId("concept.customer");
        OntologySnapshot.Property secret = new OntologySnapshot.Property();
        secret.setId("property.other.secret");
        secret.setConceptId("concept.other");
        ontology.setProperties(List.of(orderNo, email, secret));
        manifest.setOntology(ontology);
        return manifest;
    }

    private static ScenarioRouteDecision stayFree() {
        return new ScenarioRouteDecision(ScenarioRouteDecision.Action.STAY,
                ScenarioRouteState.FREE, null,
                com.ontos.module.session.core.scenario.ScenarioEntrySource.SESSION_INHERIT,
                null, List.of());
    }
}

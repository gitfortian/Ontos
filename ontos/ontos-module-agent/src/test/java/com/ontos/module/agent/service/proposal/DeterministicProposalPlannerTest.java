package com.ontos.module.agent.service.proposal;

import java.util.List;
import java.util.Map;

import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.ontology.core.SemanticManifest;
import com.ontos.module.ontology.service.manifest.ManifestService;
import com.ontos.module.session.service.retrieval.RetrievalOutcome;
import com.ontos.module.session.service.retrieval.RetrievedCandidate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 确定性提案的布尔指标路由回归（E7/bool-08）：
 * ruleId 型指标必须进入 LOOKUP，不得误用 QUERY；普通指标保持 QUERY；身份缺失澄清。
 */
class DeterministicProposalPlannerTest {

    private final ManifestService manifestService = Mockito.mock(ManifestService.class);
    private DeterministicProposalPlanner planner;

    @BeforeEach
    void setUp() {
        planner = new DeterministicProposalPlanner(manifestService);
        PublishedManifest manifest = new PublishedManifest();
        SemanticManifest semantic = new SemanticManifest();
        SemanticManifest.Metric highValue = new SemanticManifest.Metric();
        highValue.setId("metric.high_value_customer");
        highValue.setName("高价值客户");
        highValue.setRuleId("rule.high_value_customer");
        highValue.setAllowedModes(List.of("LOOKUP", "LOOKUP_BATCH"));
        SemanticManifest.Metric orderCount = new SemanticManifest.Metric();
        orderCount.setId("metric.order_count");
        orderCount.setName("订单数");
        orderCount.setAllowedModes(List.of("QUERY"));
        semantic.setMetrics(List.of(highValue, orderCount));
        manifest.setSemantic(semantic);
        when(manifestService.getActiveManifestOrNull()).thenReturn(manifest);
    }

    @Test
    void ruleBooleanMetricRoutesToLookup() {
        RetrievalOutcome outcome = outcome(
                candidate("metric", "metric.high_value_customer"),
                candidate("property", "property.customer.tier"));

        Map<String, Object> proposal = planner.plan("客户 c-1001 是不是高价值客户",
                outcome, false);

        assertEquals("query", proposal.get("terminal"));
        assertEquals(Boolean.FALSE, proposal.get("out_of_capability"));
        assertEquals(Boolean.TRUE, proposal.get("degraded"));
        assertEquals(1, ((List<?>) proposal.get("sub_intents")).size());

        @SuppressWarnings("unchecked")
        Map<String, Object> intent = (Map<String, Object>) ((List<?>) proposal.get("sub_intents")).get(0);
        assertEquals("lookup", intent.get("analysis_mode"));
        assertEquals("metric.high_value_customer", intent.get("boolean_metric_id"));
        assertEquals("c-1001", intent.get("identity_value"));
        assertEquals(List.of("property.customer.tier"), intent.get("attributes"));
        assertNull(intent.get("metric_id"));
    }

    @Test
    void ordinaryMetricRemainsQuery() {
        RetrievalOutcome outcome = outcome(candidate("metric", "metric.order_count"));

        Map<String, Object> proposal = planner.plan("今年订单数是多少", outcome, false);

        assertEquals("query", proposal.get("terminal"));
        @SuppressWarnings("unchecked")
        Map<String, Object> intent = (Map<String, Object>) ((List<?>) proposal.get("sub_intents")).get(0);
        assertEquals("query", intent.get("analysis_mode"));
        assertEquals("metric.order_count", intent.get("metric_id"));
        assertNull(intent.get("boolean_metric_id"));
    }

    @Test
    void ruleBooleanMetricWithoutIdentityClarifies() {
        RetrievalOutcome outcome = outcome(
                candidate("metric", "metric.high_value_customer"),
                candidate("property", "property.customer.tier"));

        Map<String, Object> proposal = planner.plan("是不是高价值客户", outcome, false);

        assertEquals("clarification", proposal.get("terminal"));
        @SuppressWarnings("unchecked")
        Map<String, Object> clarification = (Map<String, Object>) proposal.get("clarification");
        assertEquals("ask_identity", clarification.get("degraded_behavior"));
    }

    private static RetrievalOutcome outcome(RetrievedCandidate... candidates) {
        return new RetrievalOutcome(List.of(candidates), List.of(), List.of(), List.of(),
                false, "hash");
    }

    private static RetrievedCandidate candidate(String assetType, String assetId) {
        return new RetrievedCandidate(assetId, assetType, assetId, 2, false, 60);
    }
}

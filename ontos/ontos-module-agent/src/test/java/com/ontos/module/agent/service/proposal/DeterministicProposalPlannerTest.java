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

    /**
     * 澄清裁决轮（T32-8 语义对称性）：用户已裁决口径，本轮同一处歧义不再复现——
     * 指标命中即强制 query 组装（与近似同意轮同型）。
     */
    @Test
    void consumedClarificationProceedsToQueryWhenMetricHit() {
        RetrievalOutcome outcome = outcomeWithClarification(
                candidate("metric", "metric.order_count"),
                candidate("dimension", "dim.channel"));

        Map<String, Object> proposal = planner.plan("各渠道的销售额是多少", outcome, true);

        assertEquals("query", proposal.get("terminal"));
        @SuppressWarnings("unchecked")
        Map<String, Object> intent = (Map<String, Object>) ((List<?>) proposal.get("sub_intents")).get(0);
        assertEquals("metric.order_count", intent.get("metric_id"));
        assertEquals(List.of("dim.channel"), intent.get("dimensions"));
    }

    /**
     * 澄清已裁决但本轮无指标命中：落到引导（候选=可用指标）而非产出 metric_id=null 的
     * 空 DSL（历史缺陷会以 500 拒绝）；引导与上一轮歧义不同源，不构成死循环。
     */
    @Test
    void consumedClarificationWithoutMetricFallsToGuidance() {
        RetrievalOutcome outcome = outcomeWithClarification(
                candidate("dimension", "dim.channel"));

        Map<String, Object> proposal = planner.plan("各渠道的销售额是多少", outcome, true);

        assertEquals("clarification", proposal.get("terminal"));
        @SuppressWarnings("unchecked")
        Map<String, Object> clarification = (Map<String, Object>) proposal.get("clarification");
        assertEquals("list_metrics", clarification.get("degraded_behavior"));
    }

    private static RetrievalOutcome outcomeWithClarification(RetrievedCandidate... candidates) {
        com.ontos.module.session.service.retrieval.ClarificationPayload payload =
                new com.ontos.module.session.service.retrieval.ClarificationPayload(
                        "渠道", List.of(), null, true);
        return new RetrievalOutcome(List.of(candidates), List.of(), List.of(),
                List.of(payload), false, "hash");
    }

    private static RetrievalOutcome outcome(RetrievedCandidate... candidates) {
        return new RetrievalOutcome(List.of(candidates), List.of(), List.of(), List.of(),
                false, "hash");
    }

    private static RetrievedCandidate candidate(String assetType, String assetId) {
        return new RetrievedCandidate(assetId, assetType, assetId, 2, false, 60);
    }
}

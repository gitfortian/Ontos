package com.ontos.module.agent.service.orchestration;

import java.util.List;

import com.ontos.module.ontology.core.OntologySnapshot;
import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.ontology.core.SemanticManifest;
import com.ontos.module.query.core.dsl.QueryDsl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 复杂模式自由问数参数补全单测（ADR-0023 v1.1）：
 * 模型只给语义槽位，服务端补事件、周期、主体与批量身份；不足时 fail / 澄清。
 */
class ComplexAnalysisParamsResolverTest {

    private final ComplexAnalysisParamsResolver resolver = new ComplexAnalysisParamsResolver();
    private PublishedManifest manifest;

    @BeforeEach
    void setUp() {
        manifest = new PublishedManifest();

        OntologySnapshot ontology = new OntologySnapshot();
        OntologySnapshot.Concept customer = new OntologySnapshot.Concept();
        customer.setId("concept.customer");
        customer.setIdentityProperty("property.customer.customer_id");
        ontology.setConcepts(List.of(customer));

        OntologySnapshot.Property tier = new OntologySnapshot.Property();
        tier.setId("property.customer.tier");
        tier.setConceptId("concept.customer");
        ontology.setProperties(List.of(tier));

        ontology.setEvents(List.of(event("event.order_placed", "下单事件", "ORDER_PLACED", null),
                event("event.refund", "退款事件", "REFUND", null),
                event("event.channel_touch", "渠道触点", "CHANNEL_TOUCH", "property.event.channel_id")));
        manifest.setOntology(ontology);

        SemanticManifest semantic = new SemanticManifest();
        SemanticManifest.Metric gmv = new SemanticManifest.Metric();
        gmv.setId("metric.gmv");
        gmv.setAllowedModes(List.of("QUERY", "ATTRIBUTION"));
        semantic.setMetrics(List.of(gmv));
        manifest.setSemantic(semantic);
    }

    @Test
    void funnelDerivesOrderedStepsAndCohortWindow() {
        QueryDsl dsl = dsl(QueryDsl.AnalysisMode.FUNNEL);
        dsl.setDimensions(List.of("dim.customer_tier"));
        dsl.setTimeExpressionRaw("近半年");

        var result = resolver.resolve(dsl, "各客户等级的下单到退款转化", manifest);

        assertTrue(result.resolved(), result.reason());
        QueryDsl.AnalysisParams params = result.dsl().getAnalysisParams();
        assertEquals(List.of("ORDER_PLACED", "REFUND"), params.getSteps());
        assertEquals("近 180 天", params.getCohortWindow().getTimeExpressionRaw());
        assertEquals("7d", params.getStepWindow());
        assertEquals("property.customer.customer_id", params.getSubjectPropertyId());
        assertEquals("first_occurrence", params.getDeduplication());
        assertEquals("previous_step", params.getConversionDenominator());
        assertNull(result.dsl().getTimeExpressionRaw(), "FUNNEL 顶层时间表达必须移入 cohortWindow");
    }

    @Test
    void funnelPreservesUtteranceStepOrder() {
        QueryDsl dsl = dsl(QueryDsl.AnalysisMode.FUNNEL);

        var result = resolver.resolve(dsl, "退款后再下单的客户有多少", manifest);

        assertTrue(result.resolved(), result.reason());
        assertEquals(List.of("REFUND", "ORDER_PLACED"), result.dsl().getAnalysisParams().getSteps());
        assertEquals("近 180 天", result.dsl().getAnalysisParams().getCohortWindow().getTimeExpressionRaw());
    }

    @Test
    void funnelWithoutTwoEventsDoesNotGuess() {
        var result = resolver.resolve(dsl(QueryDsl.AnalysisMode.FUNNEL),
                "上季度的客户转化", manifest);

        assertFalse(result.resolved());
        assertEquals("FUNNEL_STEPS_MISSING", result.reason());
    }

    @Test
    void retentionDerivesMonthlyPeriods() {
        QueryDsl dsl = dsl(QueryDsl.AnalysisMode.RETENTION);
        dsl.setTimeExpressionRaw("近半年");

        var result = resolver.resolve(dsl, "近半年客户的月留存", manifest);

        assertTrue(result.resolved(), result.reason());
        QueryDsl.AnalysisParams params = result.dsl().getAnalysisParams();
        assertEquals("ORDER_PLACED", params.getCohortEvent());
        assertEquals("ORDER_PLACED", params.getReturnEvent());
        assertEquals("MONTH", params.getPeriodUnit());
        assertEquals(6, params.getPeriods());
        assertEquals("property.customer.customer_id", params.getSubjectPropertyId());
        assertEquals("近 180 天", result.dsl().getTimeExpressionRaw());
    }

    @Test
    void retentionDerivesWeeklyPeriodsWithCap() {
        QueryDsl dsl = dsl(QueryDsl.AnalysisMode.RETENTION);
        dsl.setTimeExpressionRaw("近 60 天");

        var result = resolver.resolve(dsl, "近 60 天复购留存（按周）", manifest);

        assertTrue(result.resolved(), result.reason());
        assertEquals("WEEK", result.dsl().getAnalysisParams().getPeriodUnit());
        assertEquals(4, result.dsl().getAnalysisParams().getPeriods());
    }

    @Test
    void attributionDerivesEventsAndDefaultWindow() {
        QueryDsl dsl = dsl(QueryDsl.AnalysisMode.ATTRIBUTION);
        dsl.setMetricId("metric.gmv");
        dsl.setDimensions(List.of("dim.channel"));
        dsl.setTimeExpressionRaw("近 7 天");

        var result = resolver.resolve(dsl, "近 7 天各渠道的归因成交金额", manifest);

        assertTrue(result.resolved(), result.reason());
        QueryDsl.AnalysisParams params = result.dsl().getAnalysisParams();
        assertEquals("ORDER_PLACED", params.getConversionEvent());
        assertEquals("CHANNEL_TOUCH", params.getTouchEvent());
        assertEquals("30d", params.getAttributionWindow());
        assertEquals("last_touch", params.getModel());
        assertEquals("property.customer.customer_id", params.getSubjectPropertyId());
    }

    @Test
    void attributionRejectsMetricWithoutMode() {
        QueryDsl dsl = dsl(QueryDsl.AnalysisMode.ATTRIBUTION);
        dsl.setMetricId("metric.gmv");
        manifest.getSemantic().getMetrics().get(0).setAllowedModes(List.of("QUERY"));

        var result = resolver.resolve(dsl, "近 30 天各渠道的归因成交金额", manifest);

        assertFalse(result.resolved());
        assertEquals("ATTRIBUTION_METRIC_UNSUPPORTED", result.reason());
    }

    @Test
    void lookupBatchDerivesEntityIdentityAndPreservesOrder() {
        QueryDsl dsl = dsl(QueryDsl.AnalysisMode.LOOKUP_BATCH);
        dsl.setAttributes(List.of("property.customer.tier"));

        var result = resolver.resolve(dsl, "批量查一下这些客户的等级：c-1003、c-1001", manifest);

        assertTrue(result.resolved(), result.reason());
        assertEquals("concept.customer", result.dsl().getEntityId());
        assertEquals("property.customer.customer_id", result.dsl().getIdentityPropertyId());
        assertEquals(List.of("c-1003", "c-1001"), result.dsl().getIdentities());
    }

    @Test
    void lookupBatchWithoutIdentitiesDoesNotGuess() {
        QueryDsl dsl = dsl(QueryDsl.AnalysisMode.LOOKUP_BATCH);
        dsl.setAttributes(List.of("property.customer.tier"));

        var result = resolver.resolve(dsl, "批量查一下客户等级", manifest);

        assertFalse(result.resolved());
        assertEquals("LOOKUP_BATCH_IDENTITIES_MISSING", result.reason());
    }

    private static QueryDsl dsl(QueryDsl.AnalysisMode mode) {
        QueryDsl dsl = new QueryDsl();
        dsl.setMode(mode);
        return dsl;
    }

    private static OntologySnapshot.EventDef event(String id, String name, String eventType,
                                                   String channelPropertyId) {
        OntologySnapshot.EventDef event = new OntologySnapshot.EventDef();
        event.setId(id);
        event.setName(name);
        event.setEventType(eventType);
        event.setParticipantClass("concept.customer");
        event.setTimeProperty("property.event.occurred_at");
        event.setParticipantProperty("property.event.customer_id");
        event.setEventTypeProperty("property.event.event_type");
        event.setEventTypeValue(eventType);
        event.setChannelPropertyId(channelPropertyId);
        return event;
    }
}

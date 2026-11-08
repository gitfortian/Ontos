package com.ontos.module.query.core;

import java.util.List;

import com.ontos.module.ontology.core.OntologySnapshot;
import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.ontology.core.SemanticManifest;
import com.ontos.module.query.core.dsl.QueryDsl;

/**
 * 测试夹具：构造一份最小但完整的已发布清单，供编译器/校验器测试复用。
 */
public final class TestFixtures {

    public static final String TABLE = "public.fact_order";
    public static final String METRIC_ID = "gmv";
    public static final String DIMENSION_ID = "region";
    public static final String TIME_DIMENSION_ID = "order_date";

    public static final String CUSTOMER_CONCEPT = "concept.customer";
    public static final String ORDER_CONCEPT = "concept.order";
    public static final String CUSTOMER_ID_PROPERTY = "property.customer.customer_id";
    public static final String CUSTOMER_EMAIL_PROPERTY = "property.customer.email";
    public static final String CUSTOMER_TIER_PROPERTY = "property.customer.tier";
    public static final String CUSTOMER_BIO_PROPERTY = "property.customer.bio";
    public static final String ORDER_NO_PROPERTY = "property.order.order_no";

    public static final String EVENT_CONCEPT = "concept.event";
    public static final String EVENT_TYPE_PROPERTY = "property.event.event_type";
    public static final String EVENT_TIME_PROPERTY = "property.event.occurred_at";
    public static final String EVENT_PARTICIPANT_PROPERTY = "property.event.customer_id";
    public static final String CUSTOMER_SUBJECT_PROPERTY = "property.customer.customer_id";
    public static final String CUSTOMER_TIER_DIMENSION = "customer_tier";

    private TestFixtures() {
    }

    public static QueryProperties queryProperties() {
        QueryProperties properties = new QueryProperties();
        properties.setMaxRows(500);
        properties.setMaxBatchSize(50);
        return properties;
    }

    public static PublishedManifest manifest() {
        SemanticManifest.Metric metric = new SemanticManifest.Metric();
        metric.setId(METRIC_ID);
        metric.setName("成交金额");
        metric.setTable(TABLE);
        metric.setColumn(TABLE + ".amount");
        metric.setAggregation(SemanticManifest.Aggregation.SUM);
        metric.setAllowedModes(List.of("QUERY", "LOOKUP", "YOY_MOM"));
        metric.setAllowedDimensions(List.of(DIMENSION_ID, TIME_DIMENSION_ID));

        SemanticManifest.Dimension region = new SemanticManifest.Dimension();
        region.setId(DIMENSION_ID);
        region.setName("大区");
        region.setTable(TABLE);
        region.setColumn(TABLE + ".region");

        SemanticManifest.Dimension orderDate = new SemanticManifest.Dimension();
        orderDate.setId(TIME_DIMENSION_ID);
        orderDate.setName("下单日期");
        orderDate.setTable(TABLE);
        orderDate.setColumn(TABLE + ".order_date");

        // 跨表维度：用于验证 fail-closed，不参与正常编译
        SemanticManifest.Dimension otherTable = new SemanticManifest.Dimension();
        otherTable.setId("customer_tier");
        otherTable.setName("客户等级");
        otherTable.setTable("public.dim_customer");
        otherTable.setColumn("public.dim_customer.tier");

        SemanticManifest semantic = new SemanticManifest();
        semantic.setSemanticVersion("sem-1.0.0");
        semantic.setMetrics(List.of(metric));
        semantic.setDimensions(List.of(region, orderDate, otherTable));

        String payload = "{\"semanticVersion\":\"sem-1.0.0\"}";
        PublishedManifest manifest = new PublishedManifest();
        manifest.setManifestId("manifest-001");
        manifest.setPayloadJson(payload);
        manifest.setContentHash(PublishedManifest.sha256(payload));
        manifest.setOntologyVersion("ont-1.0.0");
        manifest.setSemanticVersion("sem-1.0.0");
        manifest.setPolicyVersion("pol-1.0.0");
        manifest.setRuleVersion("rule-1.0.0");
        manifest.setSemantic(semantic);
        manifest.setOntology(new OntologySnapshot());
        return manifest;
    }

    /** lookup 夹具：customer / order 两实体，含可投影 / 不可投影 / 存在性敏感三类属性。 */
    public static PublishedManifest lookupManifest() {
        OntologySnapshot.Concept customer = new OntologySnapshot.Concept();
        customer.setId(CUSTOMER_CONCEPT);
        customer.setDomain("customer");
        OntologySnapshot.Concept order = new OntologySnapshot.Concept();
        order.setId(ORDER_CONCEPT);
        order.setDomain("trade");

        OntologySnapshot ontology = new OntologySnapshot();
        ontology.setConcepts(List.of(customer, order));
        ontology.setProperties(List.of(
                property(CUSTOMER_ID_PROPERTY, CUSTOMER_CONCEPT, "customer.id", true, true, false),
                property(CUSTOMER_EMAIL_PROPERTY, CUSTOMER_CONCEPT, "customer.email", true, false, true),
                property(CUSTOMER_TIER_PROPERTY, CUSTOMER_CONCEPT, "customer.tier", false, true, false),
                // 同实体、不同物理表：用于验证 M2 单表投影的 fail-closed
                property(CUSTOMER_BIO_PROPERTY, CUSTOMER_CONCEPT, "customer_profile.bio", true, false, false),
                property(ORDER_NO_PROPERTY, ORDER_CONCEPT, "orders.id", true, true, false)));
        PublishedManifest manifest = manifest();
        manifest.setOntology(ontology);
        return manifest;
    }

    public static OntologySnapshot.Property property(String id, String conceptId, String column,
                                                     boolean projectable, boolean filterable,
                                                     boolean existenceSensitive) {
        OntologySnapshot.Property property = new OntologySnapshot.Property();
        property.setId(id);
        property.setConceptId(conceptId);
        property.setColumn(column);
        property.setLookupProjectable(projectable);
        property.setFilterable(filterable);
        property.setExistenceSensitive(existenceSensitive);
        if (existenceSensitive) {
            property.setDisplayHints(List.of("mask"));
        }
        return property;
    }

    public static QueryDsl singleLookup() {
        QueryDsl dsl = new QueryDsl();
        dsl.setManifestId("manifest-001");
        dsl.setMode(QueryDsl.AnalysisMode.LOOKUP);
        dsl.setEntityId(CUSTOMER_CONCEPT);
        dsl.setAttributes(List.of(CUSTOMER_EMAIL_PROPERTY));
        QueryDsl.LookupIdentity identity = new QueryDsl.LookupIdentity();
        identity.setPropertyId(CUSTOMER_ID_PROPERTY);
        identity.setValue("c-1001");
        dsl.setIdentity(identity);
        dsl.setLimit(1);
        return dsl;
    }

    public static QueryDsl batchLookup() {
        QueryDsl dsl = new QueryDsl();
        dsl.setManifestId("manifest-001");
        dsl.setMode(QueryDsl.AnalysisMode.LOOKUP_BATCH);
        dsl.setEntityId(CUSTOMER_CONCEPT);
        dsl.setAttributes(List.of(CUSTOMER_EMAIL_PROPERTY));
        dsl.setIdentityPropertyId(CUSTOMER_ID_PROPERTY);
        dsl.setIdentities(List.of("c-1001", "c-1002"));
        // limit 留给模式定档（max-batch-size），验证「不信任客户端默认值」
        return dsl;
    }

    /** ADR-0012 夹具：事件映射齐备、主体维度与事件同构的最小清单。 */
    public static PublishedManifest funnelManifest() {
        OntologySnapshot.Concept customer = new OntologySnapshot.Concept();
        customer.setId(CUSTOMER_CONCEPT);
        customer.setDomain("customer");
        OntologySnapshot.Concept eventConcept = new OntologySnapshot.Concept();
        eventConcept.setId(EVENT_CONCEPT);
        eventConcept.setDomain("trade");

        OntologySnapshot ontology = new OntologySnapshot();
        ontology.setConcepts(List.of(customer, eventConcept));
        ontology.setProperties(List.of(
                property(CUSTOMER_SUBJECT_PROPERTY, CUSTOMER_CONCEPT, "customer.id", true, true, false),
                property("property.customer.tier", CUSTOMER_CONCEPT, "customer.tier", true, true, false),
                property(EVENT_TYPE_PROPERTY, EVENT_CONCEPT, "event.event_type", true, true, false),
                property(EVENT_TIME_PROPERTY, EVENT_CONCEPT, "event.occurred_at", true, true, false),
                property(EVENT_PARTICIPANT_PROPERTY, EVENT_CONCEPT, "event.customer_id", true, true, false)));
        ontology.setEvents(List.of(
                eventDef("event.order_placed", "ORDER_PLACED"),
                eventDef("event.refund", "REFUND")));

        SemanticManifest.Dimension tier = new SemanticManifest.Dimension();
        tier.setId(CUSTOMER_TIER_DIMENSION);
        tier.setTable("customer");
        tier.setColumn("tier");

        PublishedManifest manifest = manifest();
        manifest.setOntology(ontology);
        manifest.getSemantic().setDimensions(List.of(tier));
        return manifest;
    }

    private static OntologySnapshot.EventDef eventDef(String id, String eventType) {
        OntologySnapshot.EventDef event = new OntologySnapshot.EventDef();
        event.setId(id);
        event.setName(eventType);
        event.setSummary(eventType);
        event.setAliases(List.of(eventType));
        event.setEventType(eventType);
        event.setEventTypeProperty(EVENT_TYPE_PROPERTY);
        event.setEventTypeValue(eventType);
        event.setParticipantProperty(EVENT_PARTICIPANT_PROPERTY);
        event.setTimeProperty(EVENT_TIME_PROPERTY);
        event.setParticipantClass(CUSTOMER_CONCEPT);
        event.setDedupKey("order_id:" + eventType);
        return event;
    }

    public static QueryDsl funnelDsl() {
        QueryDsl dsl = new QueryDsl();
        dsl.setManifestId("manifest-001");
        dsl.setMode(QueryDsl.AnalysisMode.FUNNEL);
        dsl.setDimensions(List.of(CUSTOMER_TIER_DIMENSION));
        QueryDsl.AnalysisParams params = new QueryDsl.AnalysisParams();
        params.setSteps(List.of("ORDER_PLACED", "REFUND"));
        QueryDsl.CohortWindow window = new QueryDsl.CohortWindow();
        window.setTimeExpressionRaw("近 30 天");
        window.setFrom(java.time.Instant.parse("2026-10-09T00:00:00Z"));
        window.setTo(java.time.Instant.parse("2026-11-08T00:00:00Z"));
        window.setSource(QueryDsl.TimeRange.SOURCE_EXPRESSION);
        window.setPatternId("tep.last_n_days");
        window.setParserVersion("time-parser-1.0");
        params.setCohortWindow(window);
        params.setStepWindow("7d");
        params.setSubjectPropertyId(CUSTOMER_SUBJECT_PROPERTY);
        params.setDeduplication("first_occurrence");
        params.setConversionDenominator("previous_step");
        dsl.setAnalysisParams(params);
        dsl.setLimit(100);
        return dsl;
    }

    public static QueryDsl query() {
        QueryDsl dsl = new QueryDsl();
        dsl.setManifestId("manifest-001");
        dsl.setMetricId(METRIC_ID);
        dsl.setMode(QueryDsl.AnalysisMode.QUERY);
        dsl.setDimensions(List.of(DIMENSION_ID));
        dsl.setLimit(100);
        return dsl;
    }
}

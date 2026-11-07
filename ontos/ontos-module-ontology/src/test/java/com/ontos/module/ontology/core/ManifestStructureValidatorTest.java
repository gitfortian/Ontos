package com.ontos.module.ontology.core;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.ontos.module.ontology.core.ManifestPayload.Rule;
import com.ontos.module.ontology.core.ManifestStructureValidator.Violation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManifestStructureValidatorTest {

    private final ManifestStructureValidator validator = new ManifestStructureValidator();

    /** 最小但完整的 v2 合法清单（业务字段齐全、物理名合规、引用闭环）。 */
    private ManifestPayload validPayload() {
        SemanticManifest.Metric metric = new SemanticManifest.Metric();
        metric.setId("metric.gmv");
        metric.setName("GMV");
        metric.setSummary("成交金额合计");
        metric.setAliases(List.of("成交金额", "销售额"));
        metric.setTable("sales_order");
        metric.setColumn("amount");
        metric.setAggregation(SemanticManifest.Aggregation.SUM);
        metric.setAllowedModes(List.of("QUERY"));

        SemanticManifest.Dimension dimension = new SemanticManifest.Dimension();
        dimension.setId("dim.region");
        dimension.setName("区域");
        dimension.setSummary("订单发货大区");
        dimension.setAliases(List.of("大区"));
        dimension.setTable("sales_order");
        dimension.setColumn("region");

        SemanticManifest semantic = new SemanticManifest();
        semantic.setSemanticVersion("sem-1");
        semantic.setMetrics(List.of(metric));
        semantic.setDimensions(List.of(dimension));

        OntologySnapshot.Property property = new OntologySnapshot.Property();
        property.setId("property.order.order_no");
        property.setName("订单编号");
        property.setSummary("一笔订单的唯一编号");
        property.setAliases(List.of("单号"));
        property.setConceptId("concept.order");
        property.setValueType("string");
        property.setColumn("sales_order.id");

        OntologySnapshot.Concept concept = new OntologySnapshot.Concept();
        concept.setId("concept.order");
        concept.setName("订单");
        concept.setSummary("客户下的每一次购买");
        concept.setAliases(List.of("单"));
        concept.setIdentityProperty("property.order.order_no");
        concept.setDomain("trade");

        OntologySnapshot.Relation relation = new OntologySnapshot.Relation();
        relation.setId("rel.order_region");
        relation.setName("属于");
        relation.setSummary("订单发货所属的区域");
        relation.setAliases(List.of("发货到"));
        relation.setFrom("concept.order");
        relation.setTo("concept.order");
        relation.setCardinality("N:1");
        relation.setReverseLabel("包含订单");
        relation.setFromColumn("sales_order.region");
        relation.setToColumn("sales_order.region");
        relation.setQueryable(true);

        OntologySnapshot ontology = new OntologySnapshot();
        ontology.setOntologyVersion("ont-1");
        ontology.setConcepts(List.of(concept));
        ontology.setProperties(List.of(property));
        ontology.setRelations(List.of(relation));

        ManifestPayload payload = new ManifestPayload();
        payload.setSemantic(semantic);
        payload.setOntology(ontology);

        ManifestPayload.Rule rule = new ManifestPayload.Rule();
        rule.setId("rule.high_value_customer");
        rule.setName("高价值客户");
        rule.setSummary("累计消费满 5 万");
        rule.setScope("global");
        rule.setExpression(new java.util.HashMap<>(Map.of("metricId", "metric.gmv", "operator", "GTE", "threshold", 50000)));
        rule.setStatus("published");
        payload.setRules(List.of(rule));

        ManifestPayload.ScenarioMeta scenario = new ManifestPayload.ScenarioMeta();
        scenario.setId("scenario.sales");
        scenario.setName("销售分析");
        scenario.setSummary("围绕交易域的日常销售分析");
        scenario.setEntryMode("recommend_or_confirm");
        scenario.setOntologyScope(List.of("concept.order"));
        scenario.setSemanticScope(List.of("metric.gmv"));
        payload.setScenarios(List.of(scenario));
        return payload;
    }

    private Set<String> codes(ManifestPayload payload) {
        return validator.validate(payload).stream()
                .map(Violation::toString)
                .collect(Collectors.toSet());
    }

    @Test
    void shouldAcceptCompleteV2Payload() {
        assertTrue(validator.validate(validPayload()).isEmpty(),
                () -> "合法清单不应有违规: " + validator.validate(validPayload()));
    }

    @Test
    void shouldRejectMissingBusinessFields() {
        ManifestPayload payload = validPayload();
        payload.getSemantic().getMetrics().get(0).setSummary(null);
        payload.getSemantic().getMetrics().get(0).setAliases(List.of());
        Set<String> codes = codes(payload);
        assertTrue(codes.stream().anyMatch(code -> code.contains("summary 必填")));
        assertTrue(codes.stream().anyMatch(code -> code.contains("aliases 至少 1 条")));
    }

    @Test
    void shouldRejectNonSnakeCasePhysicalName() {
        ManifestPayload payload = validPayload();
        payload.getSemantic().getMetrics().get(0).setColumn("sales_order.Amount");
        Set<String> codes = codes(payload);
        assertTrue(codes.stream().anyMatch(code -> code.contains("ADR-0007")));
    }

    @Test
    void shouldRejectUnknownConceptReference() {
        ManifestPayload payload = validPayload();
        payload.getOntology().getRelations().get(0).setTo("concept.unknown");
        assertTrue(codes(payload).stream().anyMatch(code -> code.contains("concept.unknown")));
    }

    @Test
    void shouldRejectIdentityPropertyReferencingUnknownProperty() {
        ManifestPayload payload = validPayload();
        payload.getOntology().getConcepts().get(0).setIdentityProperty("property.unknown");
        assertTrue(codes(payload).stream().anyMatch(code ->
                code.contains("identityProperty 引用不存在的信息项")));
    }

    @Test
    void shouldRejectIllegalCardinality() {
        ManifestPayload payload = validPayload();
        payload.getOntology().getRelations().get(0).setCardinality("X:Y");
        assertTrue(codes(payload).stream().anyMatch(code -> code.contains("cardinality 非法")));
    }

    @Test
    void shouldRejectRuleReferencingUnknownMetric() {
        ManifestPayload payload = validPayload();
        payload.getRules().get(0).getExpression().put("metricId", "metric.unknown");
        assertTrue(codes(payload).stream().anyMatch(code -> code.contains("metric.unknown")));
    }

    @Test
    void shouldRejectScenarioScopeReferencingUnknownAsset() {
        ManifestPayload payload = validPayload();
        payload.getScenarios().get(0).setSemanticScope(List.of("metric.unknown"));
        assertTrue(codes(payload).stream().anyMatch(code -> code.contains("metric.unknown")));
    }

    @Test
    void shouldAcceptBooleanRuleMetricWhenRulePublished() {
        ManifestPayload payload = validPayload();
        SemanticManifest.Metric booleanMetric = new SemanticManifest.Metric();
        booleanMetric.setId("metric.high_value_customer");
        booleanMetric.setName("高价值客户");
        booleanMetric.setSummary("按判定标准认定的重点客户");
        booleanMetric.setAliases(List.of("大客户"));
        booleanMetric.setRuleId("rule.high_value_customer");
        booleanMetric.setAllowedModes(List.of("LOOKUP"));
        var metrics = new java.util.ArrayList<>(payload.getSemantic().getMetrics());
        metrics.add(booleanMetric);
        payload.getSemantic().setMetrics(metrics);
        assertTrue(validator.validate(payload).isEmpty(),
                () -> "布尔判定指标应合法: " + validator.validate(payload));
    }

    @Test
    void shouldRejectBooleanRuleMetricReferencingUnpublishedRule() {
        ManifestPayload payload = validPayload();
        payload.getRules().get(0).setStatus("draft");
        SemanticManifest.Metric booleanMetric = new SemanticManifest.Metric();
        booleanMetric.setId("metric.high_value_customer");
        booleanMetric.setName("高价值客户");
        booleanMetric.setSummary("按判定标准认定的重点客户");
        booleanMetric.setAliases(List.of("大客户"));
        booleanMetric.setRuleId("rule.high_value_customer");
        booleanMetric.setAllowedModes(List.of("LOOKUP"));
        var metrics = new java.util.ArrayList<>(payload.getSemantic().getMetrics());
        metrics.add(booleanMetric);
        payload.getSemantic().setMetrics(metrics);
        assertTrue(validator.validate(payload).stream()
                .anyMatch(v -> v.message().contains("未发布的判定标准")));
    }

    @Test
    void shouldRejectNullPayload() {
        assertTrue(validator.validate(null).size() == 1);
    }

}

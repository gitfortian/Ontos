package com.ontos.module.ontology.core;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v1.4 §5.7 Scenario 资产规则逐条锁定（M6/W2/T2.2）。
 */
class ScenarioValidatorTest {

    private final ScenarioValidator validator = new ScenarioValidator();

    @Test
    void shouldAcceptCompleteScenario() {
        assertTrue(validate(scenario()).isEmpty(), () -> validate(scenario()).toString());
    }

    @Test
    void shouldRejectMissingLifecycleFields() {
        ManifestPayload.ScenarioMeta scenario = scenario();
        scenario.setOwner(null);
        scenario.setVersion(" ");
        scenario.setStatus("online");
        scenario.setDomainId(null);
        assertTrue(has(scenario, "owner 必填"));
        assertTrue(has(scenario, "version 必填"));
        assertTrue(has(scenario, "status 非法"));
        assertTrue(has(scenario, "domainId 必填"));
    }

    @Test
    void shouldRejectUnknownOrOverlappingOntologyScope() {
        ManifestPayload.ScenarioMeta scenario = scenario();
        scenario.getOntologyScope().setRootConcepts(List.of("concept.unknown"));
        assertTrue(has(scenario, "引用不存在的业务对象"));

        ManifestPayload.ScenarioMeta overlapping = scenario();
        overlapping.getOntologyScope().setExcludedConcepts(List.of("concept.order"));
        assertTrue(has(overlapping, "不得与 rootConcepts 重叠"));
    }

    @Test
    void shouldRejectRelationOutsideRoots() {
        ManifestPayload.ScenarioMeta scenario = scenario();
        scenario.getOntologyScope().setAllowedRelations(List.of("ontology.Order.belongsToCustomer"));
        scenario.getOntologyScope().setRootConcepts(List.of("concept.order"));
        assertTrue(has(scenario, "端点必须落在 rootConcepts 内"));
    }

    @Test
    void shouldRejectPathHopOutOfRange() {
        ManifestPayload.ScenarioMeta scenario = scenario();
        scenario.getOntologyScope().setMaxPathHops(0);
        assertTrue(has(scenario, "maxPathHops 必须在 [1, 5]"));
        scenario.getOntologyScope().setMaxPathHops(6);
        assertTrue(has(scenario, "maxPathHops 必须在 [1, 5]"));
    }

    @Test
    void shouldRejectUnknownSemanticAssetsAndEmptyScope() {
        ManifestPayload.ScenarioMeta scenario = scenario();
        scenario.getSemanticScope().setMetrics(List.of("metric.unknown"));
        assertTrue(has(scenario, "引用不存在的指标"));

        ManifestPayload.ScenarioMeta empty = scenario();
        empty.getSemanticScope().setMetrics(List.of());
        empty.getSemanticScope().setDimensions(List.of());
        assertTrue(has(empty, "至少要声明一个指标或维度"));
    }

    @Test
    void shouldValidateFrozenCalibers() {
        ManifestPayload.FrozenCaliber metric = new ManifestPayload.FrozenCaliber();
        metric.setType("metric_id");
        metric.setValue("metric.unknown");
        ManifestPayload.FrozenCaliber field = new ManifestPayload.FrozenCaliber();
        field.setType("field_id");
        field.setValue("property.unknown");
        ManifestPayload.FrozenCaliber badType = new ManifestPayload.FrozenCaliber();
        badType.setType("sql");
        badType.setValue("select 1");
        ManifestPayload.ScenarioMeta scenario = scenario();
        scenario.getSemanticScope().setFrozenCalibers(Map.of(
                "revenue_metric", metric, "default_time_field", field, "unsafe", badType));
        assertTrue(has(scenario, "metric_id 引用未发布指标"));
        assertTrue(has(scenario, "field_id 引用未发布字段"));
        assertTrue(has(scenario, "type 只能是 string / metric_id / field_id"));
    }

    @Test
    void shouldRejectModeAndSkillDrift() {
        ManifestPayload.ScenarioMeta scenario = scenario();
        scenario.setAllowedAnalysisModes(List.of("query", "sql"));
        assertTrue(has(scenario, "不在封闭枚举内"));

        ManifestPayload.SkillBinding skill = new ManifestPayload.SkillBinding();
        skill.setSkillId("skill.not_published");
        skill.setAnalysisMode("funnel");
        skill.setParamsSchemaRef("schema.funnel.v1");
        ManifestPayload.ScenarioMeta withSkill = scenario();
        withSkill.setSkills(List.of(skill));
        assertTrue(has(withSkill, "只能绑定已发布 Skill"));
        assertTrue(has(withSkill, "未在 allowedAnalysisModes 中声明"));
    }

    @Test
    void shouldRequirePublishedRefsForColdStart() {
        ManifestPayload.ScenarioMeta scenario = scenario();
        scenario.setColdStartSamples(List.of("上个月各渠道 ROAS 排名"));
        scenario.setDefaultVqrIds(List.of("vqr.not_published"));
        assertTrue(has(scenario, "必须是已发布 VQR / Golden Query 引用"));
        assertTrue(has(scenario, "defaultVqrIds 只能引用已发布 VQR"));
    }

    @Test
    void shouldRejectIncompatibleVersions() {
        ManifestPayload.ScenarioMeta scenario = scenario();
        ManifestPayload.VersionCompatibility compatibility = new ManifestPayload.VersionCompatibility();
        compatibility.setOntology("^1.0.0");
        compatibility.setSemantic("2025.10");
        scenario.setVersionCompatibility(compatibility);
        assertTrue(has(scenario, "主版本不兼容"));
        assertTrue(has(scenario, "版本前缀不兼容"));
    }

    @Test
    void shouldAcceptCompatibleDatePrefix() {
        ManifestPayload.ScenarioMeta scenario = scenario();
        ManifestPayload.VersionCompatibility compatibility = new ManifestPayload.VersionCompatibility();
        compatibility.setOntology("2026.11.*");
        compatibility.setSemantic("2026.11");
        scenario.setVersionCompatibility(compatibility);
        assertTrue(validate(scenario).isEmpty(), () -> validate(scenario).toString());
    }

    @Test
    void shouldRejectDuplicateScenarioId() {
        ManifestPayload payload = payload();
        payload.setScenarios(List.of(scenario(), scenario()));
        assertTrue(validator.validate(payload).stream()
                .anyMatch(v -> v.message().contains("Scenario ID 重复")));
    }

    @Test
    void shouldKeepLegacyFlatScenarioLoadable() {
        ManifestPayload.ScenarioMeta legacy = new ManifestPayload.ScenarioMeta();
        legacy.setId("scenario.legacy");
        legacy.setName("历史场景");
        legacy.setSummary("v12 扁平形态");
        legacy.setEntryMode("recommend_or_confirm");
        ManifestPayload.OntologyScope ontology = new ManifestPayload.OntologyScope();
        ontology.setRootConcepts(List.of("concept.order"));
        ontology.setLegacyShape(true);
        legacy.setOntologyScope(ontology);
        ManifestPayload.SemanticScope semantic = new ManifestPayload.SemanticScope();
        semantic.setMetrics(List.of("metric.gmv"));
        semantic.setLegacyShape(true);
        legacy.setSemanticScope(semantic);
        legacy.setAllowedAnalysisModes(List.of("query"));

        ManifestPayload payload = payload();
        payload.setScenarios(List.of(legacy));
        assertTrue(validator.validate(payload).isEmpty(), () -> validator.validate(payload).toString());
        assertFalse(legacy.getOntologyScope().getRootConcepts().isEmpty());
    }

    private List<ManifestStructureValidator.Violation> validate(ManifestPayload.ScenarioMeta scenario) {
        ManifestPayload payload = payload();
        payload.setScenarios(List.of(scenario));
        return validator.validate(payload);
    }

    private boolean has(ManifestPayload.ScenarioMeta scenario, String message) {
        return validate(scenario).stream().anyMatch(v -> v.toString().contains(message));
    }

    private ManifestPayload payload() {
        OntologySnapshot ontology = new OntologySnapshot();
        ontology.setOntologyVersion("2026.11.9");
        ontology.setConcepts(List.of(concept("concept.order"), concept("concept.customer")));
        OntologySnapshot.Relation relation = new OntologySnapshot.Relation();
        relation.setId("ontology.Order.belongsToCustomer");
        relation.setFrom("concept.order");
        relation.setTo("concept.customer");
        relation.setCardinality("N:1");
        ontology.setRelations(List.of(relation));
        OntologySnapshot.Property property = new OntologySnapshot.Property();
        property.setId("property.order.amount");
        property.setConceptId("concept.order");
        ontology.setProperties(List.of(property));

        SemanticManifest semantic = new SemanticManifest();
        semantic.setSemanticVersion("2026.11.9");
        SemanticManifest.Metric metric = new SemanticManifest.Metric();
        metric.setId("metric.gmv");
        metric.setName("成交金额");
        metric.setSummary("已支付订单金额");
        metric.setAliases(List.of("GMV"));
        metric.setTable("orders");
        metric.setColumn("amount");
        metric.setAggregation(SemanticManifest.Aggregation.SUM);
        metric.setAllowedModes(List.of("QUERY"));
        SemanticManifest.Dimension dimension = new SemanticManifest.Dimension();
        dimension.setId("dim.region");
        dimension.setName("地区");
        dimension.setSummary("订单所属地区");
        dimension.setAliases(List.of("区域"));
        dimension.setTable("orders");
        dimension.setColumn("region");
        semantic.setMetrics(List.of(metric));
        semantic.setDimensions(List.of(dimension));

        ManifestPayload payload = new ManifestPayload();
        payload.setOntology(ontology);
        payload.setSemantic(semantic);
        return payload;
    }

    private OntologySnapshot.Concept concept(String id) {
        OntologySnapshot.Concept concept = new OntologySnapshot.Concept();
        concept.setId(id);
        concept.setName(id);
        return concept;
    }

    private ManifestPayload.ScenarioMeta scenario() {
        ManifestPayload.ScenarioMeta scenario = new ManifestPayload.ScenarioMeta();
        scenario.setId("scenario.sales");
        scenario.setName("销售分析");
        scenario.setSummary("围绕交易域的日常销售分析");
        scenario.setEntryMode("recommend_or_confirm");
        scenario.setOwner("project-team");
        scenario.setVersion("1.0.0");
        scenario.setStatus("published");
        scenario.setDomainId("domain.trade");
        ManifestPayload.OntologyScope ontology = new ManifestPayload.OntologyScope();
        ontology.setRootConcepts(List.of("concept.order", "concept.customer"));
        ontology.setAllowedRelations(List.of("ontology.Order.belongsToCustomer"));
        ontology.setMaxPathHops(3);
        scenario.setOntologyScope(ontology);
        ManifestPayload.SemanticScope semantic = new ManifestPayload.SemanticScope();
        semantic.setMetrics(List.of("metric.gmv"));
        semantic.setDimensions(List.of("dim.region"));
        scenario.setSemanticScope(semantic);
        scenario.setAllowedAnalysisModes(List.of("query"));
        scenario.setEntryHints(List.of("成交金额分析"));
        return scenario;
    }
}

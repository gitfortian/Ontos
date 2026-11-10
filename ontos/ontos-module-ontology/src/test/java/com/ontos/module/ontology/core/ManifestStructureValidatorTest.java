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
        ManifestPayload.OntologyScope ontologyScope = new ManifestPayload.OntologyScope();
        ontologyScope.setRootConcepts(List.of("concept.order"));
        ontologyScope.setLegacyShape(true);
        scenario.setOntologyScope(ontologyScope);
        ManifestPayload.SemanticScope semanticScope = new ManifestPayload.SemanticScope();
        semanticScope.setMetrics(List.of("metric.gmv"));
        semanticScope.setLegacyShape(true);
        scenario.setSemanticScope(semanticScope);
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
        ManifestPayload.SemanticScope unknownScope = new ManifestPayload.SemanticScope();
        unknownScope.setMetrics(List.of("metric.unknown"));
        unknownScope.setLegacyShape(true);
        payload.getScenarios().get(0).setSemanticScope(unknownScope);
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

    // ---------- M3/W1：文案模板与口径变体 ----------

    private ManifestPayload.CopyTemplate template(String id, String reasonCode, String text,
                                                  List<String> allowed) {
        ManifestPayload.CopyTemplate template = new ManifestPayload.CopyTemplate();
        template.setTemplateId(id);
        template.setReasonCode(reasonCode);
        template.setLocale("zh-CN");
        template.setText(text);
        template.setAllowedPlaceholders(allowed);
        if (reasonCode != null) {
            template.setVersion("1.0.0");
        }
        return template;
    }

    @Test
    void shouldAcceptVersionedCopyTemplateWithDeclaredPlaceholder() {
        ManifestPayload payload = validPayload();
        payload.setCopyTemplates(List.of(template("template.reason.CALIBER_AMBIGUOUS",
                "CALIBER_AMBIGUOUS", "这个指标有多个口径：{variant_aliases}。请选择。",
                List.of("variant_aliases"))));
        assertTrue(validator.validate(payload).isEmpty(),
                () -> "合法文案模板不应有违规: " + validator.validate(payload));
    }

    @Test
    void shouldAcceptLegacyCopyTemplateWithoutReasonCode() {
        // v7 及以前没有 reasonCode / version：按 templateId 前缀派生，旧清单必须继续可加载
        ManifestPayload payload = validPayload();
        payload.setCopyTemplates(List.of(template("template.reason.OUT_OF_SCOPE", null,
                "这个问题超出了问数的能力范围。", List.of())));
        assertTrue(validator.validate(payload).isEmpty(),
                () -> "历史模板应兼容: " + validator.validate(payload));
    }

    @Test
    void shouldRejectUndeclaredPlaceholderInCopyTemplate() {
        ManifestPayload payload = validPayload();
        payload.setCopyTemplates(List.of(template("template.reason.CALIBER_AMBIGUOUS",
                "CALIBER_AMBIGUOUS", "候选口径：{variant_aliases}", List.of())));
        assertTrue(codes(payload).stream().anyMatch(code ->
                code.contains("未在 allowedPlaceholders 声明")));
    }

    @Test
    void shouldRejectForbiddenPlaceholderInCopyTemplate() {
        ManifestPayload payload = validPayload();
        payload.setCopyTemplates(List.of(template("template.reason.OUT_OF_SCOPE",
                "OUT_OF_SCOPE", "涉及资产：{asset_names}", List.of("asset_names"))));
        assertTrue(codes(payload).stream().anyMatch(code -> code.contains("禁止的占位符")));
    }

    @Test
    void shouldRejectDuplicateReasonCodeAndLocale() {
        ManifestPayload payload = validPayload();
        payload.setCopyTemplates(List.of(
                template("template.reason.A", "OUT_OF_SCOPE", "文案一", List.of()),
                template("template.reason.B", "OUT_OF_SCOPE", "文案二", List.of())));
        assertTrue(codes(payload).stream().anyMatch(code -> code.contains("只能有一个模板")));
    }

    private SemanticManifest.CaliberVariant variant(String id, String fieldId, String operator,
                                                    List<String> values) {
        SemanticManifest.VariantCondition condition = new SemanticManifest.VariantCondition();
        condition.setFieldId(fieldId);
        condition.setOperator(operator);
        condition.setValues(values);
        SemanticManifest.CaliberVariant variant = new SemanticManifest.CaliberVariant();
        variant.setVariantId(id);
        variant.setAlias(List.of("测试口径"));
        variant.setCondition(condition);
        variant.setVersion("1.0.0");
        return variant;
    }

    @Test
    void shouldAcceptSameTableCaliberVariant() {
        ManifestPayload payload = validPayload();
        payload.getSemantic().getMetrics().get(0).setAllowedDimensions(List.of("dim.region"));
        payload.getSemantic().getMetrics().get(0)
                .setCaliberVariants(List.of(variant("metric.gmv.online", "dim.region", "EQ",
                        List.of("华东"))));
        assertTrue(validator.validate(payload).isEmpty(),
                () -> "同表变体应合法: " + validator.validate(payload));
    }

    @Test
    void shouldRejectVariantConditionOutsideAllowedDimensions() {
        ManifestPayload payload = validPayload();
        // allowedDimensions 为空：条件字段未授权
        payload.getSemantic().getMetrics().get(0)
                .setCaliberVariants(List.of(variant("metric.gmv.online", "dim.region", "EQ",
                        List.of("华东"))));
        assertTrue(codes(payload).stream().anyMatch(code ->
                code.contains("不在指标允许维度内")));
    }

    @Test
    void shouldRejectCrossTableVariantCondition() {
        ManifestPayload payload = validPayload();
        SemanticManifest.Dimension other = new SemanticManifest.Dimension();
        other.setId("dim.customer");
        other.setName("客户");
        other.setSummary("下单客户");
        other.setAliases(List.of("买家"));
        other.setTable("customer");
        other.setColumn("tier");
        var dimensions = new java.util.ArrayList<>(payload.getSemantic().getDimensions());
        dimensions.add(other);
        payload.getSemantic().setDimensions(dimensions);
        payload.getSemantic().getMetrics().get(0)
                .setAllowedDimensions(List.of("dim.region", "dim.customer"));
        payload.getSemantic().getMetrics().get(0)
                .setCaliberVariants(List.of(variant("metric.gmv.gold", "dim.customer", "EQ",
                        List.of("gold"))));
        assertTrue(codes(payload).stream().anyMatch(code -> code.contains("同表")));
    }

    @Test
    void shouldRejectVariantWithUnknownOperatorAndDuplicateId() {
        ManifestPayload payload = validPayload();
        payload.getSemantic().getMetrics().get(0).setAllowedDimensions(List.of("dim.region"));
        payload.getSemantic().getMetrics().get(0).setCaliberVariants(List.of(
                variant("metric.gmv.bad", "dim.region", "LIKE", List.of("华东")),
                variant("metric.gmv.bad", "dim.region", "EQ", List.of("华东"))));
        Set<String> codes = codes(payload);
        assertTrue(codes.stream().anyMatch(code -> code.contains("封闭枚举")));
        assertTrue(codes.stream().anyMatch(code -> code.contains("variantId 必填且在同一指标内唯一")));
    }

    // ── M6/T1.2：EventDef.channelPropertyId（触点渠道映射） ──────────────────

    /** 事件表上的可过滤属性（occurred_at / channel_id 同在 event 表）。 */
    private OntologySnapshot.Property eventProperty(String id, String column, boolean filterable) {
        OntologySnapshot.Property property = new OntologySnapshot.Property();
        property.setId(id);
        property.setName(id.endsWith("channel_id") ? "渠道标识" : "事件发生时间");
        property.setSummary("事件表属性");
        property.setAliases(List.of("别名"));
        property.setConceptId("concept.order");
        property.setValueType("string");
        property.setColumn(column);
        property.setFilterable(filterable);
        return property;
    }

    /** 触点事件（四要素齐全 + 可选 channelPropertyId）。 */
    private OntologySnapshot.EventDef touchEvent(String channelPropertyId) {
        OntologySnapshot.EventDef event = new OntologySnapshot.EventDef();
        event.setId("event.channel_touch");
        event.setName("渠道触点");
        event.setSummary("客户在渠道上的触点行为");
        event.setAliases(List.of("触点"));
        event.setParticipantClass("concept.order");
        event.setTimeProperty("property.event.occurred_at");
        event.setEventType("CHANNEL_TOUCH");
        event.setDedupKey("id:CHANNEL_TOUCH");
        event.setChannelPropertyId(channelPropertyId);
        return event;
    }

    @Test
    void shouldAcceptTouchChannelPropertyOnSameTable() {
        ManifestPayload payload = validPayload();
        OntologySnapshot ontology = payload.getOntology();
        // 保留既有 identity 引用属性，追加事件表两个映射属性
        OntologySnapshot.Property orderNo = ontology.getProperties().get(0);
        ontology.setProperties(List.of(orderNo,
                eventProperty("property.event.occurred_at", "event.occurred_at", true),
                eventProperty("property.event.channel_id", "event.channel_id", true)));
        ontology.setEvents(List.of(touchEvent("property.event.channel_id")));
        assertTrue(validator.validate(payload).isEmpty(),
                () -> "同表可过滤渠道映射不应有违规: " + validator.validate(payload));
    }

    @Test
    void shouldRejectChannelPropertyReferencingUnknownProperty() {
        ManifestPayload payload = validPayload();
        payload.getOntology().setEvents(List.of(touchEvent("property.event.missing")));
        assertTrue(codes(payload).stream().anyMatch(code ->
                code.contains("channelPropertyId 引用不存在的属性")));
    }

    @Test
    void shouldRejectNonFilterableChannelProperty() {
        ManifestPayload payload = validPayload();
        OntologySnapshot ontology = payload.getOntology();
        ontology.setProperties(List.of(
                eventProperty("property.event.occurred_at", "event.occurred_at", true),
                eventProperty("property.event.channel_id", "event.channel_id", false)));
        ontology.setEvents(List.of(touchEvent("property.event.channel_id")));
        assertTrue(codes(payload).stream().anyMatch(code ->
                code.contains("channelPropertyId 不可过滤")));
    }

    @Test
    void shouldRejectChannelPropertyCrossingTables() {
        ManifestPayload payload = validPayload();
        OntologySnapshot ontology = payload.getOntology();
        // 渠道属性指向订单表列，与事件时间属性（event 表）跨表
        ontology.setProperties(List.of(
                eventProperty("property.event.occurred_at", "event.occurred_at", true),
                eventProperty("property.event.channel_id", "sales_order.channel", true)));
        ontology.setEvents(List.of(touchEvent("property.event.channel_id")));
        assertTrue(codes(payload).stream().anyMatch(code ->
                code.contains("事件映射属性跨表")));
    }

}

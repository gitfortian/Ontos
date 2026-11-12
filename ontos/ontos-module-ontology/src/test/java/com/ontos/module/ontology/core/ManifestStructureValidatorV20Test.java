package com.ontos.module.ontology.core;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B0/W1：v20 语义绑定形态的结构校验（R1–R7）。
 *
 * <p>与 {@link ManifestStructureValidatorTest}（v19 形态与业务字段）互补：本类只覆盖
 * 「objectId + propertyId」路径的互斥、归属与受控例外，不重复 v19 用例。
 */
class ManifestStructureValidatorV20Test {

    private final ManifestStructureValidator validator = new ManifestStructureValidator();

    // ---------- 夹具 ----------

    /** 一份合法的 v20 清单：指标挂订单金额属性，维度挂订单区域属性。 */
    private ManifestPayload v20Payload() {
        SemanticManifest.Metric metric = metric("metric.gmv", "concept.order", "property.order.amount",
                SemanticManifest.Aggregation.SUM);

        SemanticManifest.Dimension dimension = dimension("dim.region", "concept.order",
                "property.order.region");

        SemanticManifest semantic = new SemanticManifest();
        semantic.setSemanticVersion("sem-1");
        semantic.setMetrics(List.of(metric));
        semantic.setDimensions(List.of(dimension));

        ManifestPayload payload = new ManifestPayload();
        payload.setSemantic(semantic);
        payload.setOntology(ontology());
        return payload;
    }

    private SemanticManifest.Metric metric(String id, String objectId, String propertyId,
                                           SemanticManifest.Aggregation aggregation) {
        SemanticManifest.Metric metric = new SemanticManifest.Metric();
        metric.setId(id);
        metric.setName("GMV");
        metric.setSummary("成交金额合计");
        metric.setAliases(List.of("成交金额"));
        metric.setObjectId(objectId);
        metric.setPropertyId(propertyId);
        metric.setAggregation(aggregation);
        metric.setAllowedModes(List.of("QUERY"));
        return metric;
    }

    private SemanticManifest.Dimension dimension(String id, String objectId, String propertyId) {
        SemanticManifest.Dimension dimension = new SemanticManifest.Dimension();
        dimension.setId(id);
        dimension.setName("区域");
        dimension.setSummary("订单发货大区");
        dimension.setAliases(List.of("大区"));
        dimension.setObjectId(objectId);
        dimension.setPropertyId(propertyId);
        return dimension;
    }

    /** ontology：order（有标识）/ customer（无标识）/ 两个属性各属其一。 */
    private OntologySnapshot ontology() {
        OntologySnapshot.Concept order = new OntologySnapshot.Concept();
        order.setId("concept.order");
        order.setName("订单");
        order.setSummary("客户下的每一次购买");
        order.setAliases(List.of("单"));
        order.setIdentityProperty("property.order.order_no");
        order.setDomain("trade");

        OntologySnapshot.Concept customer = new OntologySnapshot.Concept();
        customer.setId("concept.customer");
        customer.setName("客户");
        customer.setSummary("下过单的人");
        customer.setAliases(List.of("买家"));
        // 注意：validateOntology 强制所有 concept 必须有 identityProperty，
        // 故 R7（对象级计数缺标识）在发布侧被前置覆盖，不可达；该规则真正生效在编译侧（W2）。
        customer.setIdentityProperty("property.customer.customer_id");
        customer.setDomain("customer");

        OntologySnapshot.Property orderNo = new OntologySnapshot.Property();
        orderNo.setId("property.order.order_no");
        orderNo.setName("订单编号");
        orderNo.setSummary("一笔订单的唯一编号");
        orderNo.setAliases(List.of("单号"));
        orderNo.setConceptId("concept.order");
        orderNo.setValueType("string");
        orderNo.setColumn("sales_order.id");

        OntologySnapshot.Property amount = new OntologySnapshot.Property();
        amount.setId("property.order.amount");
        amount.setName("订单金额");
        amount.setSummary("订单的成交金额");
        amount.setAliases(List.of("金额"));
        amount.setConceptId("concept.order");
        amount.setValueType("decimal");
        amount.setColumn("sales_order.amount");

        OntologySnapshot.Property region = new OntologySnapshot.Property();
        region.setId("property.order.region");
        region.setName("区域");
        region.setSummary("订单发货大区");
        region.setAliases(List.of("大区"));
        region.setConceptId("concept.order");
        region.setValueType("string");
        region.setColumn("sales_order.region");

        OntologySnapshot.Property customerId = new OntologySnapshot.Property();
        customerId.setId("property.customer.customer_id");
        customerId.setName("客户编号");
        customerId.setSummary("客户的唯一编号");
        customerId.setAliases(List.of("客户ID"));
        customerId.setConceptId("concept.customer");
        customerId.setValueType("string");
        customerId.setColumn("customer.id");

        OntologySnapshot.Property tier = new OntologySnapshot.Property();
        tier.setId("property.customer.tier");
        tier.setName("客户等级");
        tier.setSummary("客户的会员等级");
        tier.setAliases(List.of("等级"));
        tier.setConceptId("concept.customer");
        tier.setValueType("string");
        tier.setColumn("customer.tier");

        OntologySnapshot ontology = new OntologySnapshot();
        ontology.setOntologyVersion("ont-1");
        ontology.setConcepts(List.of(order, customer));
        ontology.setProperties(List.of(orderNo, amount, region, customerId, tier));
        ontology.setRelations(List.of());
        return ontology;
    }

    private List<String> violations(ManifestPayload payload) {
        return validator.validate(payload).stream()
                .map(ManifestStructureValidator.Violation::toString)
                .collect(Collectors.toList());
    }

    private void assertNoViolation(ManifestPayload payload) {
        List<String> found = violations(payload);
        assertTrue(found.isEmpty(), () -> "不应有违规，实际: " + found);
    }

    private void assertViolationContains(ManifestPayload payload, String keyword) {
        List<String> found = violations(payload);
        assertFalse(found.isEmpty(), "应当有违规但通过了");
        assertTrue(found.stream().anyMatch(message -> message.contains(keyword)),
                () -> "违规消息应包含「" + keyword + "」，实际: " + found);
    }

    // ---------- 正向 4 例 ----------

    @Test
    void shouldAcceptV20MetricWithProperty() {
        assertNoViolation(v20Payload());
    }

    @Test
    void shouldAcceptObjectLevelCount() {
        ManifestPayload payload = v20Payload();
        SemanticManifest.Metric metric = payload.getSemantic().getMetrics().getFirst();
        metric.setPropertyId(null);
        metric.setAggregation(SemanticManifest.Aggregation.COUNT);
        assertNoViolation(payload);
    }

    @Test
    void shouldAcceptObjectLevelCountDistinct() {
        ManifestPayload payload = v20Payload();
        SemanticManifest.Metric metric = payload.getSemantic().getMetrics().getFirst();
        metric.setPropertyId(null);
        metric.setAggregation(SemanticManifest.Aggregation.COUNT_DISTINCT);
        assertNoViolation(payload);
    }

    @Test
    void shouldAcceptRuleOnlyMetric() {
        ManifestPayload payload = v20Payload();
        SemanticManifest.Metric metric = payload.getSemantic().getMetrics().getFirst();
        metric.setObjectId(null);
        metric.setPropertyId(null);
        metric.setAggregation(null);
        metric.setRuleId("rule.high_value");

        ManifestPayload.Rule rule = new ManifestPayload.Rule();
        rule.setId("rule.high_value");
        rule.setName("高价值客户");
        rule.setSummary("累计消费超过阈值的客户");
        rule.setScope("domain");
        rule.setStatus("published");
        rule.setReviewer("owner");
        rule.setExpression(java.util.Map.of(
                "metricId", "metric.gmv", "operator", "GT", "threshold", 10000));
        payload.setRules(List.of(rule));

        assertNoViolation(payload);
    }

    // ---------- 互斥 4 例 ----------

    @Test
    void shouldRejectRuleMetricWithBinding() {
        ManifestPayload payload = v20Payload();
        SemanticManifest.Metric metric = payload.getSemantic().getMetrics().getFirst();
        metric.setRuleId("rule.any");
        // 仍带 objectId/propertyId/aggregation → R1 拒绝
        assertViolationContains(payload, "形态非法");
    }

    @Test
    void shouldRejectV20MetricWithoutObjectId() {
        ManifestPayload payload = v20Payload();
        payload.getSemantic().getMetrics().getFirst().setObjectId(null);
        assertViolationContains(payload, "objectId");
    }

    @Test
    void shouldRejectMixedTableAndObjectId() {
        ManifestPayload payload = v20Payload();
        payload.getSemantic().getMetrics().getFirst().setTable("sales_order");
        assertViolationContains(payload, "形态非法");
    }

    @Test
    void shouldRejectMixedColumnAndPropertyId() {
        ManifestPayload payload = v20Payload();
        payload.getSemantic().getDimensions().getFirst().setColumn("region");
        assertViolationContains(payload, "形态非法");
    }

    // ---------- 归属 3 例 ----------

    @Test
    void shouldRejectPropertyOfAnotherObject() {
        ManifestPayload payload = v20Payload();
        // 指标挂 order，却引用 customer 的属性
        payload.getSemantic().getMetrics().getFirst().setPropertyId("property.customer.tier");
        assertViolationContains(payload, "不属于该业务对象");
    }

    @Test
    void shouldRejectUnpublishedObject() {
        ManifestPayload payload = v20Payload();
        payload.getSemantic().getMetrics().getFirst().setObjectId("concept.unknown");
        assertViolationContains(payload, "不存在或未发布");
    }

    @Test
    void shouldRejectObjectLevelCountWithoutIdentityProperty() {
        // 现实中 R7 在发布侧不可达——validateOntology 更早就拒了「concept 缺 identityProperty」。
        // 本用例验证的正是这个前置覆盖：抹掉标识属性后必须报 identityProperty，
        // 而不是静默通过。R7 自身的分支由 W2 的 BindingResolver 单测覆盖。
        ManifestPayload payload = v20Payload();
        payload.getOntology().getConcepts().stream()
                .filter(concept -> "concept.order".equals(concept.getId()))
                .forEach(concept -> concept.setIdentityProperty(null));
        SemanticManifest.Metric metric = payload.getSemantic().getMetrics().getFirst();
        metric.setPropertyId(null);
        metric.setAggregation(SemanticManifest.Aggregation.COUNT);
        assertViolationContains(payload, "identityProperty");
    }

    // ---------- 维度 2 例 ----------

    @Test
    void shouldAcceptV20Dimension() {
        assertNoViolation(v20Payload());
        SemanticManifest.Dimension dimension = v20Payload().getSemantic().getDimensions().getFirst();
        assertTrue(dimension.bindingForm() == SemanticManifest.BindingForm.V20_SEMANTIC);
    }

    @Test
    void shouldRejectV20DimensionWithoutPropertyId() {
        ManifestPayload payload = v20Payload();
        payload.getSemantic().getDimensions().getFirst().setPropertyId(null);
        assertViolationContains(payload, "必须同时给 objectId 与 propertyId");
    }

    // ---------- B0 §3.3.3 发布端形态闸门（只作用于发布侧） ----------

    @Test
    void shouldGateOnlyPublishSideAndKeepActivationOpenForV19() {
        ManifestPayload v19 = v19Payload();

        // 通用校验同时被 ManifestServiceImpl.assemble（激活/回滚）使用，绝不能拦 v19
        assertFalse(validator.validate(v19).stream()
                        .anyMatch(v -> v.toString().contains("发布端拒绝 v19")),
                "闸门泄漏到通用校验会破坏 v19 历史快照的激活/回滚链路");

        // 发布侧专用入口必须拦住，且不放过 v20 主形态
        assertTrue(validator.validatePublishBindingForm(v19).stream()
                .anyMatch(v -> v.toString().contains("发布端拒绝 v19")));
        assertTrue(validator.validatePublishBindingForm(v20Payload()).isEmpty());
    }

    /** 把 v20 夹具退回 v19 物理绑定（保留 ontology，只改资产绑定形态）。 */
    private ManifestPayload v19Payload() {
        ManifestPayload payload = v20Payload();
        SemanticManifest.Metric metric = payload.getSemantic().getMetrics().getFirst();
        metric.setObjectId(null);
        metric.setPropertyId(null);
        metric.setTable("orders");
        metric.setColumn("amount");
        SemanticManifest.Dimension dimension = payload.getSemantic().getDimensions().getFirst();
        dimension.setObjectId(null);
        dimension.setPropertyId(null);
        dimension.setTable("orders");
        dimension.setColumn("region");
        return payload;
    }
}

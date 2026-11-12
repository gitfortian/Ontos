package com.ontos.module.ontology.service.publish;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.ontos.framework.common.exception.ServiceException;
import com.ontos.module.ontology.core.ManifestPayload;
import com.ontos.module.ontology.core.OntologySnapshot;
import com.ontos.module.ontology.core.SemanticManifest;
import com.ontos.module.ontology.dal.mysql.PublishedSnapshotRepository;
import com.ontos.module.ontology.service.manifest.ManifestService;
import com.ontos.module.ontology.service.publish.PublishService.PublishRequest;
import com.ontos.module.ontology.service.publish.PublishService.PublishReportVO;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static com.ontos.module.ontology.enums.ErrorCodeConstants.PUBLISH_VALIDATION_FAILED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublishServiceImplTest {

    private static final String ORDER_CONCEPT = "concept.order";
    private static final String ORDER_ID_PROPERTY = "property.order.id";
    private static final String AMOUNT_PROPERTY = "property.order.amount";

    private final PublishedSnapshotRepository repository = mock(PublishedSnapshotRepository.class);
    private final PublishGuard guard = mock(PublishGuard.class);
    private final ManifestService manifestService = org.mockito.Mockito.mock(ManifestService.class);
    private final PublishServiceImpl service = new PublishServiceImpl(repository, guard, manifestService);

    /** 最小合法 v20 载荷：语义绑定 + 属性归属齐全（B0 后发布端只收此形态）。 */
    private ManifestPayload validPayload() {
        SemanticManifest.Metric metric = new SemanticManifest.Metric();
        metric.setId("metric.gmv");
        metric.setName("GMV");
        metric.setSummary("成交金额合计");
        metric.setAliases(List.of("成交金额"));
        metric.setObjectId(ORDER_CONCEPT);
        metric.setPropertyId(AMOUNT_PROPERTY);
        metric.setAggregation(SemanticManifest.Aggregation.SUM);
        metric.setAllowedModes(List.of("QUERY"));

        SemanticManifest semantic = new SemanticManifest();
        semantic.setSemanticVersion("sem-1");
        semantic.setMetrics(List.of(metric));

        OntologySnapshot.Concept order = new OntologySnapshot.Concept();
        order.setId(ORDER_CONCEPT);
        order.setName("订单");
        order.setSummary("订单业务对象");
        order.setAliases(List.of("订单"));
        order.setIdentityProperty(ORDER_ID_PROPERTY);
        order.setDomain("trade");

        OntologySnapshot ontology = new OntologySnapshot();
        ontology.setOntologyVersion("ont-1");
        ontology.setConcepts(List.of(order));
        ontology.setProperties(List.of(
                property(ORDER_ID_PROPERTY, "orders.id", "string", "订单标识"),
                property(AMOUNT_PROPERTY, "orders.amount", "decimal", "订单金额")));
        ontology.setRelations(List.of());

        ManifestPayload payload = new ManifestPayload();
        payload.setSemantic(semantic);
        payload.setOntology(ontology);
        return payload;
    }

    private static OntologySnapshot.Property property(String id, String column, String valueType,
                                                      String businessName) {
        OntologySnapshot.Property property = new OntologySnapshot.Property();
        property.setId(id);
        property.setName(businessName);
        property.setSummary(businessName + "说明");
        property.setAliases(List.of(businessName));
        property.setConceptId(ORDER_CONCEPT);
        property.setColumn(column);
        property.setValueType(valueType);
        return property;
    }

    /** 把指标退回 v19 物理绑定形态，用于验证发布端闸门。 */
    private static void toV19Metric(ManifestPayload payload) {
        SemanticManifest.Metric metric = payload.getSemantic().getMetrics().get(0);
        metric.setObjectId(null);
        metric.setPropertyId(null);
        metric.setTable("orders");
        metric.setColumn("amount");
    }

    private PublishRequest request(boolean dryRun) {
        return new PublishRequest("manifest-test", "git-ref", dryRun, asMap(validPayload()));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(ManifestPayload payload) {
        return com.ontos.framework.common.util.json.JsonUtils.parseObject(
                com.ontos.framework.common.util.json.JsonUtils.toJsonString(payload), Map.class);
    }

    @Test
    void shouldRejectUnknownFieldsInsteadOfDropping() {
        // 未知字段策略：拼写错误的字段不再被 Jackson 静默丢弃，发布显式拒绝（T3 收口项）
        Map<String, Object> raw = asMap(validPayload());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> metrics =
                (List<Map<String, Object>>) ((Map<String, Object>) raw.get("semantic")).get("metrics");
        metrics.get(0).put("alies", "typo"); // metrics 是数组：错别字键进第一个指标对象
        when(repository.selectByManifestId("manifest-test")).thenReturn(Optional.empty());
        when(guard.currentPublisher()).thenReturn("tester");

        PublishReportVO report = service.publish(new PublishRequest("manifest-test", "git-ref", true, raw));

        assertFalse(report.valid());
        assertTrue(report.violations().stream().anyMatch(v -> v.contains("alies") && v.contains("未知字段")));
    }

    @Test
    void shouldPassDryRunWhenPayloadValid() {
        when(repository.selectByManifestId("manifest-test")).thenReturn(Optional.empty());

        PublishReportVO report = service.publish(request(true));

        assertTrue(report.valid(), () -> "v20 载荷应通过预检: " + report.violations());
        verify(repository).insertPublishRecord("manifest-test", "dry-run", true, "git-ref", null);
        verify(repository, never()).insertSnapshot(anyString(), anyString(), anyString());
    }

    @Test
    void shouldReportViolationsOnDryRunWithoutInsert() {
        ManifestPayload payload = validPayload();
        payload.getSemantic().getMetrics().get(0).setAliases(List.of()); // 业务化必填缺失
        when(repository.selectByManifestId("manifest-test")).thenReturn(Optional.empty());

        PublishReportVO report = service.publish(new PublishRequest("manifest-test", null, true, asMap(payload)));

        assertFalse(report.valid());
        assertTrue(report.violations().stream().anyMatch(v -> v.contains("aliases")));
        verify(repository, never()).insertSnapshot(anyString(), anyString(), anyString());
    }

    @Test
    void shouldRejectPublishWhenViolationsPresent() {
        ManifestPayload payload = validPayload();
        payload.getSemantic().getMetrics().get(0).setObjectId("concept.unknown"); // v20 引用不存在
        when(repository.selectByManifestId("manifest-test")).thenReturn(Optional.empty());
        when(guard.currentPublisher()).thenReturn("tester");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.publish(new PublishRequest("manifest-test", "git-ref", false, asMap(payload))));

        assertEquals(PUBLISH_VALIDATION_FAILED.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("业务对象不存在"));
        verify(repository, never()).insertSnapshot(anyString(), anyString(), anyString());
    }

    @Test
    void shouldRejectPublishWhenManifestIdExists() {
        when(repository.selectByManifestId("manifest-test")).thenReturn(Optional.of(new com.ontos.module.ontology.dal.dataobject.PublishedSnapshotDO()));

        ServiceException ex = assertThrows(ServiceException.class, () -> service.publish(request(false)));

        assertTrue(ex.getMessage().contains("不可变"));
        verify(repository, never()).insertSnapshot(anyString(), anyString(), anyString());
    }

    @Test
    void shouldInsertWithComputedHashOnConfirm() {
        when(repository.selectByManifestId("manifest-test")).thenReturn(Optional.empty());
        when(guard.currentPublisher()).thenReturn("tester");
        when(repository.insertSnapshot(Mockito.eq("manifest-test"), anyString(), Mockito.eq("tester")))
                .thenReturn("abc123");

        PublishReportVO report = service.publish(request(false));

        assertTrue(report.valid());
        assertEquals("abc123", report.contentHash());
        verify(repository).insertSnapshot(Mockito.eq("manifest-test"), anyString(), anyString());
        verify(repository).insertPublishRecord("manifest-test", "abc123", false, "git-ref", "tester");
    }

    // ---------- B0 §3.3.3 发布端形态闸门 ----------

    @Test
    void shouldRejectV19MetricOnConfirmWithExplicitCode() {
        ManifestPayload payload = validPayload();
        toV19Metric(payload);
        when(repository.selectByManifestId("manifest-test")).thenReturn(Optional.empty());
        when(guard.currentPublisher()).thenReturn("tester");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.publish(new PublishRequest("manifest-test", "git-ref", false, asMap(payload))));

        assertEquals(PUBLISH_VALIDATION_FAILED.getCode(), ex.getCode(), "形态闸门必须落成明确业务码");
        assertTrue(ex.getMessage().contains("发布端拒绝 v19 物理绑定形态"));
        assertTrue(ex.getMessage().contains("metric.gmv"));
        verify(repository, never()).insertSnapshot(anyString(), anyString(), anyString());
    }

    @Test
    void shouldRejectV19MetricOnDryRun() {
        ManifestPayload payload = validPayload();
        toV19Metric(payload);
        when(repository.selectByManifestId("manifest-test")).thenReturn(Optional.empty());

        PublishReportVO report = service.publish(new PublishRequest("manifest-test", "git-ref", true, asMap(payload)));

        assertFalse(report.valid(), "预检阶段就要拦住 v19，避免确认发布才失败");
        assertTrue(report.violations().stream().anyMatch(v -> v.contains("发布端拒绝 v19")));
        verify(repository, never()).insertSnapshot(anyString(), anyString(), anyString());
    }

    @Test
    void shouldRejectV19DimensionEvenWhenMetricIsV20() {
        ManifestPayload payload = validPayload();
        SemanticManifest.Dimension dimension = new SemanticManifest.Dimension();
        dimension.setId("dim.channel");
        dimension.setName("渠道");
        dimension.setSummary("订单渠道");
        dimension.setAliases(List.of("渠道"));
        dimension.setTable("orders");
        dimension.setColumn("channel");
        payload.getSemantic().setDimensions(List.of(dimension));
        when(repository.selectByManifestId("manifest-test")).thenReturn(Optional.empty());
        when(guard.currentPublisher()).thenReturn("tester");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.publish(new PublishRequest("manifest-test", "git-ref", false, asMap(payload))));

        assertEquals(PUBLISH_VALIDATION_FAILED.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("dimension.dim.channel"));
        assertTrue(ex.getMessage().contains("发布端拒绝 v19"));
        verify(repository, never()).insertSnapshot(anyString(), anyString(), anyString());
    }

    @Test
    void shouldAcceptRuleOnlyMetricBecauseItIsNotV19() {
        ManifestPayload payload = validPayload();
        SemanticManifest.Metric booleanMetric = new SemanticManifest.Metric();
        booleanMetric.setId("metric.high_value_customer");
        booleanMetric.setName("高价值客户");
        booleanMetric.setSummary("是否高价值客户");
        booleanMetric.setAliases(List.of("高价值"));
        booleanMetric.setRuleId("rule.high_value_customer");
        booleanMetric.setAllowedModes(List.of("LOOKUP"));
        payload.getSemantic().setMetrics(List.of(payload.getSemantic().getMetrics().get(0), booleanMetric));
        // 布尔指标需要对应 published Rule，否则会命中其它违规；这里只验形态闸门不误伤 RULE_ONLY
        ManifestPayload.Rule rule = new ManifestPayload.Rule();
        rule.setId("rule.high_value_customer");
        rule.setName("高价值客户判定");
        rule.setSummary("累计消费满 5 万");
        rule.setScope("global");
        rule.setStatus("published");
        rule.setExpression(Map.of("metricId", "metric.gmv", "operator", "GTE", "threshold", 50000));
        payload.setRules(List.of(rule));
        when(repository.selectByManifestId("manifest-test")).thenReturn(Optional.empty());

        PublishReportVO report = service.publish(new PublishRequest("manifest-test", null, true, asMap(payload)));

        assertFalse(report.violations().stream().anyMatch(v -> v.contains("发布端拒绝 v19")),
                () -> "RULE_ONLY 不是 v19 形态，不应被闸门拒绝: " + report.violations());
    }

}

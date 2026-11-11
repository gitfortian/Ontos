package com.ontos.module.agent.service.orchestration;

import java.util.List;

import com.ontos.module.ontology.core.OntologySnapshot;
import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.ontology.core.SemanticManifest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 时间维度推导单测（M7/W5/T5.3 P1 修复）。
 *
 * <p>这些用例直接复现 E-7 首跑时的失败形态：LLM 只给「上个月」这类表达式，
 * 服务端必须能从清单里找到该指标可用的时间维度，否则相对时间必然解析失败。
 */
class TimeDimensionResolverTest {

    private static OntologySnapshot.Property property(String id, String column, String valueType) {
        OntologySnapshot.Property property = new OntologySnapshot.Property();
        property.setId(id);
        property.setColumn(column);
        property.setValueType(valueType);
        return property;
    }

    private static SemanticManifest.Dimension dimension(String id, String table, String column) {
        SemanticManifest.Dimension dimension = new SemanticManifest.Dimension();
        dimension.setId(id);
        dimension.setTable(table);
        dimension.setColumn(column);
        return dimension;
    }

    private static SemanticManifest.Metric metric(String id, List<String> allowedDimensions) {
        SemanticManifest.Metric metric = new SemanticManifest.Metric();
        metric.setId(id);
        metric.setAllowedDimensions(allowedDimensions);
        return metric;
    }

    private static PublishedManifest manifest(List<SemanticManifest.Metric> metrics) {
        SemanticManifest semantic = new SemanticManifest();
        semantic.setMetrics(metrics);
        semantic.setDimensions(List.of(
                dimension("dim.region", "orders", "region"),
                dimension("dim.order_date", "orders", "order_date"),
                dimension("dim.channel", "orders", "channel")));

        OntologySnapshot ontology = new OntologySnapshot();
        ontology.setProperties(List.of(
                property("property.order.region", "orders.region", "string"),
                property("property.order.order_date", "orders.order_date", "date"),
                property("property.order.channel", "orders.channel", "string")));

        PublishedManifest manifest = new PublishedManifest();
        manifest.setSemantic(semantic);
        manifest.setOntology(ontology);
        return manifest;
    }

    @Test
    void picksFirstDateDimensionAmongAllowedDimensions() {
        PublishedManifest manifest = manifest(List.of(metric("metric.gmv",
                List.of("dim.region", "dim.order_date", "dim.channel"))));

        assertEquals("dim.order_date", TimeDimensionResolver.resolve(manifest, "metric.gmv"));
    }

    @Test
    void ignoresNonDateDimensionsEvenWhenTheyComeFirst() {
        PublishedManifest manifest = manifest(List.of(metric("metric.gmv",
                List.of("dim.region", "dim.channel"))));

        assertNull(TimeDimensionResolver.resolve(manifest, "metric.gmv"),
                "没有日期维度时不得猜一个维度当时间字段");
    }

    @Test
    void fallsBackFromVariantToBaseMetric() {
        PublishedManifest manifest = manifest(List.of(metric("metric.paid_gmv",
                List.of("dim.region", "dim.order_date"))));

        assertEquals("dim.order_date",
                TimeDimensionResolver.resolve(manifest, "metric.paid_gmv.online"));
    }

    @Test
    void returnsNullForUnknownMetricOrMissingManifest() {
        PublishedManifest manifest = manifest(List.of(metric("metric.gmv", List.of("dim.order_date"))));

        assertNull(TimeDimensionResolver.resolve(manifest, "metric.unknown"));
        assertNull(TimeDimensionResolver.resolve(manifest, null));
        assertNull(TimeDimensionResolver.resolve(null, "metric.gmv"));
    }
}

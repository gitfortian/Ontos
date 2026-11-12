package com.ontos.module.ontology.core;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ontos.framework.common.exception.ServiceException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B0/W2：形态分流的唯一收口点。
 *
 * <p>覆盖三形态（v19 / v20 / rule-only）的取表取列、v20 的 FROM 断言（跨表对象拒绝），
 * 以及时间维度判定。所有断言都直接对字符串，便于确认与 {@code SqlCompiler.assetColumn} 同口径。
 */
class BindingResolverTest {

    // ---------- 夹具 ----------

    private OntologySnapshot ontology() {
        OntologySnapshot.Concept order = concept("concept.order", "property.order.order_no");
        OntologySnapshot.Concept customer = concept("concept.customer", "property.customer.customer_id");

        OntologySnapshot.Property orderNo = property("property.order.order_no", "concept.order",
                "sales_order.id", "string");
        OntologySnapshot.Property amount = property("property.order.amount", "concept.order",
                "sales_order.amount", "decimal");
        OntologySnapshot.Property orderDate = property("property.order.order_date", "concept.order",
                "sales_order.created_at", "date");
        // 属性挂在 order 上，但物理列在另一张表 → 供跨表断言用例
        OntologySnapshot.Property strayAmount = property("property.order.stray_amount", "concept.order",
                "other_table.amount", "decimal");
        OntologySnapshot.Property customerTier = property("property.customer.tier", "concept.customer",
                "customer.tier", "string");
        OntologySnapshot.Property customerId = property("property.customer.customer_id",
                "concept.customer", "customer.id", "string");

        OntologySnapshot snapshot = new OntologySnapshot();
        snapshot.setOntologyVersion("ont-1");
        snapshot.setConcepts(List.of(order, customer));
        snapshot.setProperties(List.of(orderNo, amount, orderDate, strayAmount, customerTier, customerId));
        snapshot.setRelations(List.of());
        return snapshot;
    }

    private OntologySnapshot.Concept concept(String id, String identityProperty) {
        OntologySnapshot.Concept concept = new OntologySnapshot.Concept();
        concept.setId(id);
        concept.setName(id);
        concept.setSummary("测试对象");
        concept.setAliases(List.of("对象"));
        concept.setIdentityProperty(identityProperty);
        concept.setDomain("trade");
        return concept;
    }

    private OntologySnapshot.Property property(String id, String conceptId, String column, String valueType) {
        OntologySnapshot.Property property = new OntologySnapshot.Property();
        property.setId(id);
        property.setName(id);
        property.setSummary("测试属性");
        property.setAliases(List.of("属性"));
        property.setConceptId(conceptId);
        property.setColumn(column);
        property.setValueType(valueType);
        return property;
    }

    private SemanticManifest.Metric v19Metric(String table, String column) {
        SemanticManifest.Metric metric = new SemanticManifest.Metric();
        metric.setId("metric.test");
        metric.setTable(table);
        metric.setColumn(column);
        metric.setAggregation(SemanticManifest.Aggregation.SUM);
        return metric;
    }

    private SemanticManifest.Metric v20Metric(String objectId, String propertyId) {
        SemanticManifest.Metric metric = new SemanticManifest.Metric();
        metric.setId("metric.test");
        metric.setObjectId(objectId);
        metric.setPropertyId(propertyId);
        metric.setAggregation(SemanticManifest.Aggregation.SUM);
        return metric;
    }

    private SemanticManifest.Dimension v20Dimension(String objectId, String propertyId) {
        SemanticManifest.Dimension dimension = new SemanticManifest.Dimension();
        dimension.setId("dim.test");
        dimension.setObjectId(objectId);
        dimension.setPropertyId(propertyId);
        return dimension;
    }

    // ---------- v19 形态 ----------

    @Test
    void shouldResolveV19BareColumn() {
        BindingResolver resolver = BindingResolver.of(ontology());
        SemanticManifest.Metric metric = v19Metric("sales_order", "amount");

        assertEquals(SemanticManifest.BindingForm.V19_PHYSICAL, resolver.formOf(metric));
        assertEquals("sales_order", resolver.tableOf(metric));
        assertEquals("sales_order.amount", resolver.qualifiedColumnOf(metric));
    }

    @Test
    void shouldResolveV19QualifiedColumnAsIs() {
        BindingResolver resolver = BindingResolver.of(ontology());
        SemanticManifest.Metric metric = v19Metric("sales_order", "sales_order.amount");

        assertEquals("sales_order.amount", resolver.qualifiedColumnOf(metric));
    }

    // ---------- v20 形态 ----------

    @Test
    void shouldResolveV20ByProperty() {
        BindingResolver resolver = BindingResolver.of(ontology());
        SemanticManifest.Metric metric = v20Metric("concept.order", "property.order.amount");

        assertEquals(SemanticManifest.BindingForm.V20_SEMANTIC, resolver.formOf(metric));
        assertEquals("sales_order", resolver.tableOf(metric));
        assertEquals("sales_order.amount", resolver.qualifiedColumnOf(metric));
    }

    @Test
    void shouldResolveV20ObjectLevelCountByIdentity() {
        BindingResolver resolver = BindingResolver.of(ontology());
        SemanticManifest.Metric metric = v20Metric("concept.order", null);
        metric.setAggregation(SemanticManifest.Aggregation.COUNT);

        assertEquals("sales_order", resolver.tableOf(metric));
        assertEquals("sales_order.id", resolver.qualifiedColumnOf(metric));
    }

    @Test
    void shouldResolveV20Dimension() {
        BindingResolver resolver = BindingResolver.of(ontology());
        SemanticManifest.Dimension dimension = v20Dimension("concept.customer", "property.customer.tier");

        assertEquals("customer", resolver.tableOf(dimension));
        assertEquals("customer.tier", resolver.qualifiedColumnOf(dimension));
    }

    // ---------- fail-closed ----------

    @Test
    void shouldRejectCrossTableAggregate() {
        BindingResolver resolver = BindingResolver.of(ontology());
        SemanticManifest.Metric metric = v20Metric("concept.order", "property.order.stray_amount");

        ServiceException error = assertThrows(ServiceException.class, () -> resolver.tableOf(metric));
        assertTrue(error.getMessage().contains("跨表聚合") || error.getCode() == 1_001_000_010,
                () -> "应报 OBJECT_TABLE_MISMATCH，实际: " + error.getMessage());
    }

    @Test
    void shouldRejectRuleOnlyMetricForPhysicalResolution() {
        BindingResolver resolver = BindingResolver.of(ontology());
        SemanticManifest.Metric metric = new SemanticManifest.Metric();
        metric.setId("metric.rule");
        metric.setRuleId("rule.high_value");

        assertEquals(SemanticManifest.BindingForm.RULE_ONLY, resolver.formOf(metric));
        assertThrows(ServiceException.class, () -> resolver.tableOf(metric));
    }

    @Test
    void shouldRejectMixedBinding() {
        BindingResolver resolver = BindingResolver.of(ontology());
        SemanticManifest.Metric metric = v20Metric("concept.order", "property.order.amount");
        metric.setTable("sales_order");

        assertEquals(SemanticManifest.BindingForm.REJECT, resolver.formOf(metric));
        assertThrows(ServiceException.class, () -> resolver.tableOf(metric));
    }

    @Test
    void shouldRejectUnknownProperty() {
        BindingResolver resolver = BindingResolver.of(ontology());
        SemanticManifest.Metric metric = v20Metric("concept.order", "property.order.missing");

        assertThrows(ServiceException.class, () -> resolver.tableOf(metric));
    }

    // ---------- 时间维度判定 ----------

    @Test
    void shouldDetectDateBindingInBothForms() {
        BindingResolver resolver = BindingResolver.of(ontology());

        SemanticManifest.Dimension v20Date = v20Dimension("concept.order", "property.order.order_date");
        assertTrue(resolver.isDateBinding(v20Date));

        SemanticManifest.Dimension v20Text = v20Dimension("concept.customer", "property.customer.tier");
        assertFalse(resolver.isDateBinding(v20Text));

        SemanticManifest.Dimension v19Date = new SemanticManifest.Dimension();
        v19Date.setId("dim.v19_date");
        v19Date.setTable("sales_order");
        v19Date.setColumn("created_at");
        assertTrue(resolver.isDateBinding(v19Date), "v19 应按物理列反查属性类型");
    }
}

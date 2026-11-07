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

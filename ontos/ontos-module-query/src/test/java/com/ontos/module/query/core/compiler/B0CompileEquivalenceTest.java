package com.ontos.module.query.core.compiler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ontos.framework.common.util.json.JsonUtils;
import com.ontos.module.ontology.core.ManifestPayload;
import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.query.core.dsl.QueryDsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B0/W4：E1「编译产物 SQL 逐字等价」。
 *
 * <p>同一 DSL 分别在 **v19 清单**（{@code table + column}）与 **v20 清单**
 * （{@code objectId + propertyId}）下编译，SQL 文本必须逐字相同。
 *
 * <p>这是三层化的核心验收判据：只要逐字相等，清单形态变更就不会改变任何查询语义，
 * 也不需要重固化 Golden（只需按清单 hash 变化重跑）。
 *
 * <p>覆盖点按风险排序：普通聚合（SUM）、跨表维度（customer_tier 走 JOIN 后仍在 SELECT 中）、
 * 计数指标（验证 {@code count(*)} 不消费列）、过滤与时间窗（列解析的另两条路径）。
 */
class B0CompileEquivalenceTest {

    private final SqlCompiler compiler = new SqlCompiler();

    private static PublishedManifest load(String file) throws Exception {
        Path path = Path.of("..", "..", "ontos-assets", file);
        assertTrue(Files.exists(path), "资产文件缺失: " + path.toAbsolutePath());
        ManifestPayload payload = JsonUtils.parseObject(Files.readString(path), ManifestPayload.class);
        PublishedManifest manifest = new PublishedManifest();
        manifest.setManifestId(file);
        manifest.setSemantic(payload.getSemantic());
        manifest.setOntology(payload.getOntology());
        manifest.setRules(payload.getRules() == null ? List.of() : payload.getRules());
        return manifest;
    }

    private static QueryDsl query(String metricId, List<String> dimensions) {
        QueryDsl dsl = new QueryDsl();
        dsl.setManifestId("equivalence");
        dsl.setMode(QueryDsl.AnalysisMode.QUERY);
        dsl.setMetricId(metricId);
        dsl.setDimensions(dimensions == null ? new ArrayList<>() : new ArrayList<>(dimensions));
        dsl.setLimit(100);
        return dsl;
    }

    /** 同一 DSL 在 v19 / v20 下编译，断言 SQL 逐字相等并返回该 SQL 便于失败定位。 */
    private String assertSameSql(PublishedManifest v19, PublishedManifest v20, QueryDsl dsl) {
        String sql19 = compiler.compile(dsl, v19).getSql();
        String sql20 = compiler.compile(dsl, v20).getSql();
        assertEquals(sql19, sql20, () -> "E1 失败：SQL 不一致\nv19: " + sql19 + "\nv20: " + sql20);
        return sql19;
    }

    @Test
    void shouldCompileSumMetricWithSameTableDimension() throws Exception {
        PublishedManifest v19 = load("first-release-v19.json");
        PublishedManifest v20 = load("first-release-v20.json");

        String sql = assertSameSql(v19, v20, query("metric.gmv", List.of("dim.region")));

        assertTrue(sql.contains("orders"), "FROM 应为 orders: " + sql);
        assertTrue(sql.contains("amount"), "聚合列应为 amount: " + sql);
        assertTrue(sql.contains("region"), "分组列应为 region: " + sql);
    }

    @Test
    void shouldCompileCountDistinctMetric() throws Exception {
        PublishedManifest v19 = load("first-release-v19.json");
        PublishedManifest v20 = load("first-release-v20.json");

        String sql = assertSameSql(v19, v20, query("metric.order_count", List.of("dim.region")));

        // metric.order_count 的 aggregation 是 COUNT_DISTINCT（不是 COUNT），v19 生成
        // count(distinct orders.id)。E1 在此行成立的理由是「列相同」——
        // property.order.order_no.column 实测就是 orders.id，故产物逐字不变。
        assertTrue(sql.toLowerCase().contains("count(distinct"),
                "订单数应生成 count(distinct ...): " + sql);
        assertTrue(sql.contains("orders") && sql.contains("id"),
                "计数列应为 orders.id: " + sql);
    }

    @Test
    void shouldCompileCrossTableDimension() throws Exception {
        PublishedManifest v19 = load("first-release-v19.json");
        PublishedManifest v20 = load("first-release-v20.json");

        // customer_tier 属客户对象（customer.tier），在订单查询里是跨表维度
        String sql = assertSameSql(v19, v20, query("metric.gmv", List.of("dim.customer_tier")));
        assertTrue(sql.contains("tier"), "分组列应为 tier: " + sql);
    }

    @Test
    void shouldCompileWithFilter() throws Exception {
        PublishedManifest v19 = load("first-release-v19.json");
        PublishedManifest v20 = load("first-release-v20.json");

        QueryDsl dsl = query("metric.gmv", List.of("dim.region"));
        QueryDsl.Filter filter = new QueryDsl.Filter();
        filter.setFieldId("dim.channel");
        filter.setOperator(QueryDsl.Operator.EQ);
        filter.setValues(List.of("线上"));
        dsl.setFilters(List.of(filter));

        String sql = assertSameSql(v19, v20, dsl);
        assertTrue(sql.contains("channel"), "过滤列应为 channel: " + sql);
    }

    @Test
    void shouldCompileWithTimeRange() throws Exception {
        PublishedManifest v19 = load("first-release-v19.json");
        PublishedManifest v20 = load("first-release-v20.json");

        QueryDsl dsl = query("metric.gmv", List.of("dim.region"));
        QueryDsl.TimeRange range = new QueryDsl.TimeRange();
        range.setField("dim.order_date");
        range.setFrom(java.time.Instant.parse("2026-10-01T00:00:00Z"));
        range.setTo(java.time.Instant.parse("2026-11-01T00:00:00Z"));
        dsl.setTimeRange(range);

        String sql = assertSameSql(v19, v20, dsl);
        assertTrue(sql.contains("order_date"), "时间列应为 order_date: " + sql);
    }

    @Test
    void shouldCompileOrderItemMetric() throws Exception {
        PublishedManifest v19 = load("first-release-v19.json");
        PublishedManifest v20 = load("first-release-v20.json");

        // 订单项对象（order_item）的聚合，验证不同对象的 FROM 推导
        String sql = assertSameSql(v19, v20,
                query("metric.order_item_quantity", List.of("dim.product_category")));
        assertTrue(sql.contains("order_item"), "FROM 应为 order_item: " + sql);
    }
}

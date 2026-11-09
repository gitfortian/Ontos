package com.ontos.module.query.core.compiler;

import java.time.Instant;
import java.util.List;

import com.ontos.framework.common.exception.ServiceException;
import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.ontology.core.SemanticManifest;
import com.ontos.module.query.core.TestFixtures;
import com.ontos.module.query.core.dsl.QueryDsl;
import org.junit.jupiter.api.Test;

import static com.ontos.module.query.enums.ErrorCodeConstants.DSL_INVALID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** W3/T3.3b：yoy_mom 周期骨架 + 窗口函数构造（analysis-mode 规格 v1 §2.3）。 */
class YoyMomCompilerTest {

    private final SqlCompiler compiler = new SqlCompiler();

    private static QueryDsl yoyMom(String period, List<String> dimensions) {
        QueryDsl dsl = TestFixtures.query();
        dsl.setMode(QueryDsl.AnalysisMode.YOY_MOM);
        dsl.setDimensions(dimensions);
        QueryDsl.AnalysisParams params = new QueryDsl.AnalysisParams();
        params.setPeriod(period);
        dsl.setAnalysisParams(params);
        QueryDsl.TimeRange range = new QueryDsl.TimeRange();
        range.setField(TestFixtures.TIME_DIMENSION_ID);
        range.setFrom(Instant.parse("2026-01-01T00:00:00Z"));
        range.setTo(Instant.parse("2026-10-31T23:59:59Z"));
        dsl.setTimeRange(range);
        dsl.setLimit(100);
        return dsl;
    }

    @Test
    void shouldCompilePeriodSkeletonAndWindowFunctions() {
        CompiledQuery compiled = compiler.compile(yoyMom("MONTH", List.of()), TestFixtures.manifest());
        String sql = compiled.getSql().toLowerCase();

        assertEquals("YOY_MOM", compiled.getMode());
        assertTrue(sql.contains("generate_series"), "必须有周期骨架补齐缺失周期: " + sql);
        assertTrue(sql.contains("date_trunc"), "必须按自然月/自然年分桶: " + sql);
        assertTrue(sql.contains("lag"), "同环比用 lag 取对比期: " + sql);
        assertTrue(sql.contains("nullif"), "分母为零必须输出 null: " + sql);
        assertTrue(sql.contains("left outer join"), "骨架左连聚合，缺失周期补 null: " + sql);
        assertTrue(sql.contains("mom") && sql.contains("yoy"), "必须产出 mom / yoy 双列: " + sql);
    }

    @Test
    void shouldExpandAggregationWindowByTwelveMonthsForYoy() {
        CompiledQuery compiled = compiler.compile(yoyMom("MONTH", List.of()), TestFixtures.manifest());
        // 本期从 2026-01-01 起，同比回看 12 个月 → 绑定值必须包含 2025-01-01
        assertTrue(compiled.getBindValues().stream()
                        .anyMatch(value -> String.valueOf(value).startsWith("2025-01-01")),
                "聚合窗口必须扩到去年同期: " + compiled.getBindValues());
    }

    @Test
    void shouldUseOnePeriodLagForYearlyComparison() {
        CompiledQuery compiled = compiler.compile(yoyMom("YEAR", List.of()), TestFixtures.manifest());
        String sql = compiled.getSql().toLowerCase();
        assertTrue(sql.contains("date_trunc('year'"), "年粒度必须按 year 分桶: " + sql);
        assertTrue(compiled.getBindValues().stream()
                        .anyMatch(value -> String.valueOf(value).startsWith("2025-01-01")),
                "年同比回看 1 年: " + compiled.getBindValues());
    }

    @Test
    void shouldBuildDimensionGridWithPartitionedWindow() {
        CompiledQuery compiled = compiler.compile(
                yoyMom("MONTH", List.of(TestFixtures.DIMENSION_ID)), TestFixtures.manifest());
        String sql = compiled.getSql().toLowerCase();

        assertTrue(sql.contains("partition by"), "维度分组决定窗口分区: " + sql);
        assertTrue(sql.contains("\"region\""), "维度列必须来自清单: " + sql);
        // 骨架 × 维度并集：两个 FROM 源（cross join）
        int fromCount = sql.split("from ", -1).length - 1;
        assertTrue(fromCount >= 3, "应包含 periods / dims / 主查询多个 FROM: " + sql);
    }

    @Test
    void shouldJoinCrossTableDimensionAndApplyFiltersToBothCtes() {
        // 深度审核 D49/D58：YOY_MOM 原先既不展开 resolvedJoins 也不接 filters，
        // 跨表维度在真实库直接 SQL 报错（缺 FROM 子句），过滤则被静默忽略。
        QueryDsl.ResolvedJoin join = new QueryDsl.ResolvedJoin();
        join.setRelationId("r.placesOrder");
        join.setDirection("reverse");
        join.setTable("public.dim_customer");
        join.setLeftColumn("public.dim_customer.id");
        join.setRightColumn("public.fact_order.customer_id");
        join.setCardinality("N:1");

        QueryDsl.Filter filter = new QueryDsl.Filter();
        filter.setFieldId(TestFixtures.DIMENSION_ID);
        filter.setOperator(QueryDsl.Operator.EQ);
        filter.setValues(List.of("华东"));

        QueryDsl dsl = yoyMom("MONTH", List.of(TestFixtures.DIMENSION_ID));
        dsl.setResolvedJoins(List.of(join));
        dsl.setFilters(List.of(filter));
        CompiledQuery compiled = compiler.compile(dsl, TestFixtures.manifest());
        String sql = compiled.getSql().toLowerCase();

        assertTrue(sql.contains("join \"public\".\"dim_customer\""),
                "跨表维度必须展开为 JOIN: " + sql);
        assertTrue(sql.contains("region"), "维度列必须进入聚合与维度枚举: " + sql);
        // 聚合 CTE 与维度枚举 CTE 各绑定一次过滤值，缺一都会让骨架与聚合口径分叉
        long filterBinds = compiled.getBindValues().stream()
                .filter("华东"::equals).count();
        assertEquals(2, filterBinds, "过滤条件必须同时进入 agg 与 dims: " + compiled.getBindValues());
    }

    @Test
    void shouldApplyCaliberVariantToBothCtes() {
        // M3/W2：YOY 的变体条件必须同时进入聚合与维度枚举（与 filters 同一批）
        PublishedManifest manifest = TestFixtures.manifest();
        SemanticManifest.VariantCondition condition = new SemanticManifest.VariantCondition();
        condition.setFieldId(TestFixtures.DIMENSION_ID);
        condition.setOperator("EQ");
        condition.setValues(List.of("华东"));
        SemanticManifest.CaliberVariant variant = new SemanticManifest.CaliberVariant();
        variant.setVariantId("gmv.online");
        variant.setAlias(List.of("华东口径"));
        variant.setCondition(condition);
        variant.setVersion("1.0.0");
        manifest.getSemantic().getMetrics().get(0).setCaliberVariants(List.of(variant));

        QueryDsl dsl = yoyMom("MONTH", List.of(TestFixtures.DIMENSION_ID));
        dsl.setCaliberVariantId("gmv.online");
        CompiledQuery compiled = compiler.compile(dsl, manifest);

        long binds = compiled.getBindValues().stream().filter("华东"::equals).count();
        assertEquals(2, binds, "变体条件必须同时进入 agg 与 dims: " + compiled.getBindValues());
    }

    @Test
    void shouldRejectMissingAnalysisParamsInsteadOfThrowingNpe() {
        QueryDsl dsl = yoyMom("MONTH", List.of());
        dsl.setAnalysisParams(null);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> compiler.compile(dsl, TestFixtures.manifest()));
        assertEquals(DSL_INVALID.getCode(), ex.getCode());
    }

}

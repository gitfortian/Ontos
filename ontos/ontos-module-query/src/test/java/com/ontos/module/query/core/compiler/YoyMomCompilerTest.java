package com.ontos.module.query.core.compiler;

import java.time.Instant;
import java.util.List;

import com.ontos.module.query.core.TestFixtures;
import com.ontos.module.query.core.dsl.QueryDsl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

}

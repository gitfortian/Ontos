package com.ontos.module.query.core.compiler;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import com.ontos.framework.common.exception.ServiceException;
import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.query.core.TestFixtures;
import com.ontos.module.query.core.dsl.QueryDsl;
import org.junit.jupiter.api.Test;

import static com.ontos.module.query.enums.ErrorCodeConstants.ANALYSIS_MODE_NOT_IMPLEMENTED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlCompilerTest {

    private final SqlCompiler compiler = new SqlCompiler();

    @Test
    void shouldCompileDeterministicParameterizedSql() {
        PublishedManifest manifest = TestFixtures.manifest();
        QueryDsl dsl = TestFixtures.query();

        QueryDsl.Filter filter = new QueryDsl.Filter();
        filter.setFieldId(TestFixtures.DIMENSION_ID);
        filter.setOperator(QueryDsl.Operator.EQ);
        filter.setValues(List.of("华东"));
        dsl.setFilters(List.of(filter));

        CompiledQuery compiled = compiler.compile(dsl, manifest);

        assertEquals("manifest-001", compiled.getManifestId());
        assertEquals(manifest.getContentHash(), compiled.getContentHash());
        assertEquals(TestFixtures.METRIC_ID, compiled.getMetricId());
        assertEquals(List.of(TestFixtures.DIMENSION_ID), compiled.getDimensions());
        assertEquals("postgresql", compiled.getDialect());
        assertEquals(100, compiled.getAppliedLimit());

        String sql = compiled.getSql();
        assertTrue(sql.contains("sum"), "应使用指标声明的聚合函数: " + sql);
        assertTrue(sql.contains("fact_order"), "物理表名必须来自语义清单: " + sql);
        assertTrue(sql.contains("group by"), "维度应产生 group by: " + sql);
        assertFalse(sql.contains("华东"), "用户输入不得内联进 SQL: " + sql);
        assertTrue(compiled.getBindValues().contains("华东"), "用户输入必须作为绑定值传递");
        assertTrue(compiled.getCompiledAt().isAfter(Instant.now().minus(1, ChronoUnit.MINUTES)));
    }

    @Test
    void shouldProduceIdenticalSqlForIdenticalInput() {
        PublishedManifest manifest = TestFixtures.manifest();
        CompiledQuery first = compiler.compile(TestFixtures.query(), manifest);
        CompiledQuery second = compiler.compile(TestFixtures.query(), manifest);
        assertEquals(first.getSql(), second.getSql(), "相同输入必须生成完全相同的 SQL");
        assertEquals(first.getBindValues(), second.getBindValues());
    }

    @Test
    void shouldSupportAllAggregationsAndOperators() {
        PublishedManifest manifest = TestFixtures.manifest();
        manifest.getSemantic().getMetrics().get(0)
                .setAggregation(com.ontos.module.ontology.core.SemanticManifest.Aggregation.COUNT_DISTINCT);
        QueryDsl dsl = TestFixtures.query();

        QueryDsl.Filter in = new QueryDsl.Filter();
        in.setFieldId(TestFixtures.DIMENSION_ID);
        in.setOperator(QueryDsl.Operator.IN);
        in.setValues(List.of("华东", "华南"));
        dsl.setFilters(List.of(in));

        CompiledQuery compiled = compiler.compile(dsl, manifest);
        assertTrue(compiled.getSql().contains("distinct"), "COUNT_DISTINCT 应体现 distinct: " + compiled.getSql());
        assertTrue(compiled.getSql().contains(" in "), "IN 应生成 in 条件: " + compiled.getSql());
        assertTrue(compiled.getBindValues().containsAll(List.of("华东", "华南")));
    }

    @Test
    void shouldEmitTimeRangeAsParameterizedCondition() {
        QueryDsl dsl = TestFixtures.query();
        QueryDsl.TimeRange range = new QueryDsl.TimeRange();
        range.setField(TestFixtures.TIME_DIMENSION_ID);
        range.setSource(QueryDsl.TimeRange.SOURCE_EXPLICIT);
        range.setFrom(Instant.parse("2026-01-01T00:00:00Z"));
        range.setTo(Instant.parse("2026-01-31T00:00:00Z"));
        dsl.setTimeRange(range);

        CompiledQuery compiled = compiler.compile(dsl, TestFixtures.manifest());
        String sql = compiled.getSql();
        assertTrue(sql.contains("order_date"), "时间字段应出现在条件中: " + sql);
        assertFalse(sql.contains("2026-01-01"), "时间值不得内联: " + sql);
        assertEquals(2, compiled.getBindValues().stream()
                .filter(java.sql.Timestamp.class::isInstance).count());
    }

    @Test
    void shouldRejectNonQueryModeDefensively() {
        // 纵深防御：即使绕过校验器，编译器也绝不把非 QUERY 模式静默编译成聚合 SQL（PoC-04）
        QueryDsl dsl = TestFixtures.query();
        dsl.setMode(QueryDsl.AnalysisMode.LOOKUP);
        ServiceException ex = assertThrows(ServiceException.class, () -> compiler.compile(dsl, TestFixtures.manifest()));
        assertEquals(ANALYSIS_MODE_NOT_IMPLEMENTED.getCode(), ex.getCode());
    }

    @Test
    void shouldCompileUniquePathAsStructuredJoin() {
        // W3/T3.1：ON 列与表只来自已发布清单，jOOQ 结构化构造，零字符串拼接
        QueryDsl.ResolvedJoin join = new QueryDsl.ResolvedJoin();
        join.setRelationId("r.placesOrder");
        join.setDirection("reverse");
        join.setTable("public.dim_customer");
        join.setLeftColumn("public.dim_customer.id");
        join.setRightColumn("public.fact_order.customer_id");
        join.setCardinality("N:1");

        QueryDsl dsl = TestFixtures.query();
        dsl.setResolvedJoins(List.of(join));

        CompiledQuery compiled = compiler.compile(dsl, TestFixtures.manifest());
        String sql = compiled.getSql().toLowerCase();
        assertTrue(sql.contains("join"), "UNIQUE 路径应展开为 JOIN: " + sql);
        assertTrue(sql.contains("dim_customer"), "JOIN 表应来自清单: " + sql);
        assertTrue(sql.contains("on \"public\".\"dim_customer\".\"id\" = "
                        + "\"public\".\"fact_order\".\"customer_id\""),
                "ON 条件应使用清单物理列: " + sql);
        assertEquals(1, compiled.getJoins().size());
        assertEquals("r.placesOrder", compiled.getJoins().get(0).getRelationId());
    }
}

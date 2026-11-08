package com.ontos.module.query.core.dsl;

import java.time.Instant;
import java.util.List;

import com.ontos.framework.common.exception.ServiceException;
import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.query.core.QueryProperties;
import com.ontos.module.query.core.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.ontos.module.ontology.enums.ErrorCodeConstants.DIMENSION_NOT_ALLOWED;
import static com.ontos.module.query.enums.ErrorCodeConstants.ABSOLUTE_DATE_REJECTED;
import static com.ontos.module.query.enums.ErrorCodeConstants.ANALYSIS_MODE_NOT_IMPLEMENTED;
import static com.ontos.module.query.enums.ErrorCodeConstants.DSL_INVALID;
import static com.ontos.module.query.enums.ErrorCodeConstants.SENSITIVE_BULK_EXPORT_REJECTED;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DslValidatorTest {

    private DslValidator validator;
    private PublishedManifest manifest;

    @BeforeEach
    void setUp() {
        validator = new DslValidator(TestFixtures.queryProperties());
        manifest = TestFixtures.manifest();
    }

    @Test
    void shouldAcceptValidDsl() {
        assertDoesNotThrow(() -> validator.validate(TestFixtures.query(), manifest));
    }

    @Test
    void shouldRejectUnknownMetric() {
        QueryDsl dsl = TestFixtures.query();
        dsl.setMetricId("not_published_metric");
        assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
    }

    @Test
    void shouldRejectModeNotAllowedByMetric() {
        QueryDsl dsl = TestFixtures.query();
        dsl.setMode(QueryDsl.AnalysisMode.DISCOVERY);
        assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
    }

    @Test
    void shouldRejectNonQueryModeEvenWhenWhitelisted() {
        // 夹具清单声明了 LOOKUP / YOY_MOM：白名单放行不够，未实现的模式必须 fail-closed（PoC-04）
        for (QueryDsl.AnalysisMode mode : List.of(QueryDsl.AnalysisMode.LOOKUP, QueryDsl.AnalysisMode.YOY_MOM)) {
            QueryDsl dsl = TestFixtures.query();
            dsl.setMode(mode);
            ServiceException ex = assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
            assertEquals(ANALYSIS_MODE_NOT_IMPLEMENTED.getCode(), ex.getCode());
        }
    }

    @Test
    void shouldRejectDimensionOutsideMetricWhitelist() {
        QueryDsl dsl = TestFixtures.query();
        dsl.setDimensions(List.of("customer_tier"));
        assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
    }

    @Test
    void shouldRejectCrossTableDimension() {
        QueryDsl dsl = TestFixtures.query();
        // 先让白名单放行，再触发同表约束
        manifest.getSemantic().getMetrics().get(0)
                .setAllowedDimensions(List.of(TestFixtures.DIMENSION_ID, "customer_tier"));
        dsl.setDimensions(List.of("customer_tier"));
        assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
    }

    @Test
    void shouldRejectFilterWithoutValues() {
        QueryDsl.Filter filter = new QueryDsl.Filter();
        filter.setFieldId(TestFixtures.DIMENSION_ID);
        filter.setOperator(QueryDsl.Operator.EQ);
        QueryDsl dsl = TestFixtures.query();
        dsl.setFilters(List.of(filter));
        assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
    }

    @Test
    void shouldRejectBetweenWithWrongValueCount() {
        QueryDsl.Filter filter = new QueryDsl.Filter();
        filter.setFieldId(TestFixtures.DIMENSION_ID);
        filter.setOperator(QueryDsl.Operator.BETWEEN);
        filter.setValues(List.of("2026-01-01"));
        QueryDsl dsl = TestFixtures.query();
        dsl.setFilters(List.of(filter));
        assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
    }

    @Test
    void shouldRejectLimitBeyondMaxRows() {
        QueryDsl dsl = TestFixtures.query();
        dsl.setLimit(501);
        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
        assertTrue(ex.getMessage().contains("limit"));
    }

    @Test
    void shouldRejectInvertedTimeRange() {
        QueryDsl.TimeRange range = new QueryDsl.TimeRange();
        range.setField(TestFixtures.TIME_DIMENSION_ID);
        range.setFrom(Instant.parse("2026-02-01T00:00:00Z"));
        range.setTo(Instant.parse("2026-01-01T00:00:00Z"));
        QueryDsl dsl = TestFixtures.query();
        dsl.setTimeRange(range);
        assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
    }

    @Test
    void shouldRejectNullDsl() {
        assertThrows(ServiceException.class, () -> validator.validate(null, manifest));
    }

    @Test
    void shouldUseConfiguredMaxRows() {
        QueryProperties properties = TestFixtures.queryProperties();
        properties.setMaxRows(10);
        DslValidator strict = new DslValidator(properties);
        QueryDsl dsl = TestFixtures.query();
        dsl.setLimit(50);
        assertThrows(ServiceException.class, () -> strict.validate(dsl, manifest));
    }

    // ---------- W2.5：敏感资产批量导出 guardrail ----------

    @Test
    void shouldRejectSensitiveUngroupedBulkExport() {
        manifest.getSemantic().getMetrics().get(0).setSensitive(true);
        QueryDsl dsl = TestFixtures.query();
        dsl.setDimensions(List.of());
        dsl.setLimit(100);
        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
        assertEquals(SENSITIVE_BULK_EXPORT_REJECTED.getCode(), ex.getCode());
    }

    @Test
    void shouldAllowSensitiveQueryWhenGroupedOrSmall() {
        manifest.getSemantic().getMetrics().get(0).setSensitive(true);
        assertDoesNotThrow(() -> validator.validate(TestFixtures.query(), manifest));
        QueryDsl small = TestFixtures.query();
        small.setDimensions(List.of());
        small.setLimit(50);
        assertDoesNotThrow(() -> validator.validate(small, manifest));
    }

    // ---------- W2.5：时间来源二通道 ----------

    private static QueryDsl.TimeRange timeRange(String source) {
        QueryDsl.TimeRange range = new QueryDsl.TimeRange();
        range.setField(TestFixtures.TIME_DIMENSION_ID);
        range.setFrom(Instant.parse("2026-01-01T00:00:00Z"));
        range.setTo(Instant.parse("2026-02-01T00:00:00Z"));
        range.setSource(source);
        return range;
    }

    @Test
    void shouldRejectExpressionWithoutServerParserMetadata() {
        QueryDsl dsl = TestFixtures.query();
        dsl.setTimeRange(timeRange(QueryDsl.TimeRange.SOURCE_EXPRESSION));
        dsl.setTimeExpressionRaw("近 3 天");
        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
        assertEquals(ABSOLUTE_DATE_REJECTED.getCode(), ex.getCode());
    }

    @Test
    void shouldAcceptServerResolvedExpression() {
        QueryDsl dsl = TestFixtures.query();
        dsl.setTimeRange(timeRange(QueryDsl.TimeRange.SOURCE_EXPRESSION));
        dsl.setTimeExpressionRaw("近 3 天");
        dsl.setTimePatternId("tep.last_n_days");
        dsl.setTimeParserVersion("time-parser-1.0");
        assertDoesNotThrow(() -> validator.validate(dsl, manifest));
    }

    @Test
    void shouldRejectExplicitSourceWithoutExplicitRange() {
        QueryDsl dsl = TestFixtures.query();
        dsl.setTimeRange(timeRange(QueryDsl.TimeRange.SOURCE_EXPLICIT));
        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
        assertEquals(ABSOLUTE_DATE_REJECTED.getCode(), ex.getCode());
    }

    @Test
    void shouldAcceptExplicitRangeChannel() {
        QueryDsl dsl = TestFixtures.query();
        QueryDsl.TimeRange explicit = timeRange(null);
        dsl.setExplicitRange(explicit);
        explicit.setSource(QueryDsl.TimeRange.SOURCE_EXPLICIT);
        dsl.setTimeRange(explicit);
        assertDoesNotThrow(() -> validator.validate(dsl, manifest));
    }

    @Test
    void shouldRejectUnknownTimeSource() {
        QueryDsl dsl = TestFixtures.query();
        dsl.setTimeRange(timeRange("LLM_GUESS"));
        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
        assertEquals(ABSOLUTE_DATE_REJECTED.getCode(), ex.getCode());
    }

    // ---------- W2.5：复合意图结构 ----------

    private static QueryDsl.SubIntent subIntent(String id, String metricId) {
        QueryDsl.SubIntent sub = new QueryDsl.SubIntent();
        sub.setSubIntentId(id);
        sub.setMetricId(metricId);
        return sub;
    }

    @Test
    void shouldAcceptIndependentSubIntents() {
        QueryDsl dsl = TestFixtures.query();
        dsl.setSubIntents(List.of(subIntent("s1", TestFixtures.METRIC_ID), subIntent("s2", TestFixtures.METRIC_ID)));
        assertDoesNotThrow(() -> validator.validate(dsl, manifest));
    }

    @Test
    void shouldRejectDuplicateSubIntentIds() {
        QueryDsl dsl = TestFixtures.query();
        dsl.setSubIntents(List.of(subIntent("s1", TestFixtures.METRIC_ID), subIntent("s1", TestFixtures.METRIC_ID)));
        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
        assertEquals(DSL_INVALID.getCode(), ex.getCode());
    }

    @Test
    void shouldRejectDependentSubIntent() {
        QueryDsl dsl = TestFixtures.query();
        QueryDsl.SubIntent sub = subIntent("s1", TestFixtures.METRIC_ID);
        sub.setDependsOn(List.of("s0"));
        dsl.setSubIntents(List.of(sub));
        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
        assertEquals(DSL_INVALID.getCode(), ex.getCode());
    }

    @Test
    void shouldAllowCrossTableDimensionWhenUniqueJoinResolved() {
        manifest.getSemantic().getMetrics().get(0)
                .setAllowedDimensions(List.of(TestFixtures.DIMENSION_ID,
                        TestFixtures.TIME_DIMENSION_ID, "customer_tier"));
        QueryDsl dsl = TestFixtures.query();
        dsl.setDimensions(List.of("customer_tier"));
        QueryDsl.ResolvedJoin join = new QueryDsl.ResolvedJoin();
        join.setTable("public.dim_customer");
        join.setLeftColumn("public.dim_customer.id");
        join.setRightColumn("public.fact_order.customer_id");
        dsl.setResolvedJoins(List.of(join));
        assertDoesNotThrow(() -> validator.validate(dsl, manifest));
    }

    // ---------- W2.5：analysis_params（T3.0 规格 v1） ----------

    private static QueryDsl.AnalysisParams funnelParams() {
        QueryDsl.AnalysisParams params = new QueryDsl.AnalysisParams();
        params.setSteps(List.of("ORDER_PLACED", "REFUND"));
        QueryDsl.CohortWindow cohort = new QueryDsl.CohortWindow();
        cohort.setTimeExpressionRaw("近 30 天");
        params.setCohortWindow(cohort);
        params.setStepWindow("7d");
        params.setSubjectPropertyId("property.customer.customer_id");
        params.setDeduplication("first_occurrence");
        params.setConversionDenominator("previous_step");
        return params;
    }

    @Test
    void shouldRejectAnalysisParamsOnQueryMode() {
        QueryDsl dsl = TestFixtures.query();
        dsl.setAnalysisParams(funnelParams());
        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
        assertEquals(DSL_INVALID.getCode(), ex.getCode());
    }

    @Test
    void shouldRejectSingleStepFunnel() {
        QueryDsl dsl = TestFixtures.query();
        dsl.setMode(QueryDsl.AnalysisMode.FUNNEL);
        QueryDsl.AnalysisParams params = funnelParams();
        params.setSteps(List.of("ORDER_PLACED"));
        dsl.setAnalysisParams(params);
        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
        assertEquals(DSL_INVALID.getCode(), ex.getCode());
    }

    @Test
    void shouldRejectDuplicateFunnelSteps() {
        QueryDsl dsl = TestFixtures.query();
        dsl.setMode(QueryDsl.AnalysisMode.FUNNEL);
        QueryDsl.AnalysisParams params = funnelParams();
        params.setSteps(List.of("ORDER_PLACED", "ORDER_PLACED"));
        dsl.setAnalysisParams(params);
        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
        assertEquals(DSL_INVALID.getCode(), ex.getCode());
    }

    @Test
    void shouldPassFunnelSchemaThenFailClosedOnUnimplementedMode() {
        manifest.getSemantic().getMetrics().get(0)
                .setAllowedModes(List.of("QUERY", "FUNNEL", "YOY_MOM"));
        QueryDsl dsl = TestFixtures.query();
        dsl.setMode(QueryDsl.AnalysisMode.FUNNEL);
        dsl.setAnalysisParams(funnelParams());
        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
        assertEquals(ANALYSIS_MODE_NOT_IMPLEMENTED.getCode(), ex.getCode());
    }

    @Test
    void shouldRejectYoyMomQuarter() {
        QueryDsl dsl = TestFixtures.query();
        dsl.setMode(QueryDsl.AnalysisMode.YOY_MOM);
        QueryDsl.AnalysisParams params = new QueryDsl.AnalysisParams();
        params.setPeriod("QUARTER");
        dsl.setAnalysisParams(params);
        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
        assertEquals(DSL_INVALID.getCode(), ex.getCode());
    }

    @Test
    void shouldPassYoyMomSchemaThenFailClosedOnUnimplementedMode() {
        manifest.getSemantic().getMetrics().get(0)
                .setAllowedModes(List.of("QUERY", "FUNNEL", "YOY_MOM"));
        QueryDsl dsl = TestFixtures.query();
        dsl.setMode(QueryDsl.AnalysisMode.YOY_MOM);
        QueryDsl.AnalysisParams params = new QueryDsl.AnalysisParams();
        params.setPeriod("MONTH");
        dsl.setAnalysisParams(params);
        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
        assertEquals(ANALYSIS_MODE_NOT_IMPLEMENTED.getCode(), ex.getCode());
    }

    // ---------- 深度审核回归：维度授权三条引用路径 + 过滤值数量 ----------

    private static QueryDsl.Filter filter(String fieldId, QueryDsl.Operator operator, List<String> values) {
        QueryDsl.Filter filter = new QueryDsl.Filter();
        filter.setFieldId(fieldId);
        filter.setOperator(operator);
        filter.setValues(values);
        return filter;
    }

    private static QueryDsl.ResolvedJoin joinToCustomer() {
        QueryDsl.ResolvedJoin join = new QueryDsl.ResolvedJoin();
        join.setTable("public.dim_customer");
        join.setLeftColumn("public.dim_customer.id");
        join.setRightColumn("public.fact_order.customer_id");
        return join;
    }

    @Test
    void shouldRejectFilterOutsideMetricWhitelistEvenWhenJoinResolved() {
        // T3.1 放开跨表 Join 后，过滤字段曾绕过 allowedDimensions 直接读未授权表（ADR-0003 判据 6）
        QueryDsl dsl = TestFixtures.query();
        dsl.setFilters(List.of(filter("customer_tier", QueryDsl.Operator.EQ, List.of("VIP"))));
        dsl.setResolvedJoins(List.of(joinToCustomer()));
        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
        assertEquals(DIMENSION_NOT_ALLOWED.getCode(), ex.getCode());
    }

    @Test
    void shouldRejectTimeFieldOutsideMetricWhitelist() {
        QueryDsl dsl = TestFixtures.query();
        QueryDsl.TimeRange range = new QueryDsl.TimeRange();
        range.setField("customer_tier");
        range.setFrom(Instant.parse("2026-10-01T00:00:00Z"));
        range.setTo(Instant.parse("2026-11-01T00:00:00Z"));
        range.setSource(QueryDsl.TimeRange.SOURCE_EXPRESSION);
        dsl.setTimeRange(range);
        dsl.setTimeExpressionRaw("上个月");
        dsl.setTimePatternId("tep.last_month");
        dsl.setTimeParserVersion("time-parser-1.0");
        dsl.setResolvedJoins(List.of(joinToCustomer()));
        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
        assertEquals(DIMENSION_NOT_ALLOWED.getCode(), ex.getCode());
    }

    @Test
    void shouldRejectExtraValuesForSingleValuedOperator() {
        QueryDsl dsl = TestFixtures.query();
        dsl.setFilters(List.of(filter(TestFixtures.DIMENSION_ID,
                QueryDsl.Operator.EQ, List.of("华东", "华南"))));
        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
        assertEquals(DSL_INVALID.getCode(), ex.getCode());
    }

    @Test
    void shouldAllowMultipleValuesForInOperator() {
        QueryDsl dsl = TestFixtures.query();
        dsl.setFilters(List.of(filter(TestFixtures.DIMENSION_ID,
                QueryDsl.Operator.IN, List.of("华东", "华南"))));
        assertDoesNotThrow(() -> validator.validate(dsl, manifest));
    }
}

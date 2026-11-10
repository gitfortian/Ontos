package com.ontos.module.query.core.dsl;

import java.time.Instant;
import java.util.List;

import com.ontos.framework.common.exception.ServiceException;
import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.ontology.core.SemanticManifest;
import com.ontos.module.query.core.QueryProperties;
import com.ontos.module.query.core.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.ontos.module.ontology.enums.ErrorCodeConstants.DIMENSION_NOT_ALLOWED;
import static com.ontos.module.query.enums.ErrorCodeConstants.ABSOLUTE_DATE_REJECTED;
import static com.ontos.module.query.enums.ErrorCodeConstants.CALIBER_AMBIGUOUS;
import static com.ontos.module.query.enums.ErrorCodeConstants.ANALYSIS_MODE_NOT_IMPLEMENTED;
import static com.ontos.module.query.enums.ErrorCodeConstants.DSL_INVALID;
import static com.ontos.module.query.enums.ErrorCodeConstants.FUNNEL_EVENT_NOT_FOUND;
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
        // 白名单放行不够，未实现的模式必须 fail-closed（PoC-04）。
        // 已实现 QUERY/LOOKUP/LOOKUP_BATCH/YOY_MOM/FUNNEL/RETENTION/ATTRIBUTION；DISCOVERY 仍未实现（M6 决策 3）。
        manifest.getSemantic().getMetrics().get(0).setAllowedModes(List.of("QUERY", "DISCOVERY"));
        QueryDsl dsl = TestFixtures.query();
        dsl.setMode(QueryDsl.AnalysisMode.DISCOVERY);
        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
        assertEquals(ANALYSIS_MODE_NOT_IMPLEMENTED.getCode(), ex.getCode());
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
    void shouldRejectFunnelWithoutPublishedEventMapping() {
        // FUNNEL 已由 T3.3a 实现：schema 通过后必须因「清单未发布事件定义」fail-closed
        QueryDsl dsl = TestFixtures.query();
        dsl.setMode(QueryDsl.AnalysisMode.FUNNEL);
        dsl.setAnalysisParams(funnelParams());
        dsl.setMetricId(null); // funnel 按事件主体计数，不接受 metricId
        dsl.getAnalysisParams().getCohortWindow().setFrom(Instant.parse("2026-10-01T00:00:00Z"));
        dsl.getAnalysisParams().getCohortWindow().setTo(Instant.parse("2026-11-01T00:00:00Z"));
        dsl.setDimensions(List.of());
        dsl.getAnalysisParams().setSubjectPropertyId(TestFixtures.CUSTOMER_SUBJECT_PROPERTY);
        PublishedManifest noEvents = TestFixtures.funnelManifest();
        noEvents.getOntology().setEvents(List.of());
        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validate(dsl, noEvents));
        assertEquals(FUNNEL_EVENT_NOT_FOUND.getCode(), ex.getCode());
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
    void shouldAcceptYoyMomWhenTimeRangePresent() {
        manifest.getSemantic().getMetrics().get(0)
                .setAllowedModes(List.of("QUERY", "FUNNEL", "YOY_MOM"));
        QueryDsl dsl = TestFixtures.query();
        dsl.setMode(QueryDsl.AnalysisMode.YOY_MOM);
        QueryDsl.AnalysisParams params = new QueryDsl.AnalysisParams();
        params.setPeriod("MONTH");
        dsl.setAnalysisParams(params);
        QueryDsl.TimeRange range = new QueryDsl.TimeRange();
        range.setField(TestFixtures.TIME_DIMENSION_ID);
        range.setFrom(Instant.parse("2026-01-01T00:00:00Z"));
        range.setTo(Instant.parse("2026-10-31T23:59:59Z"));
        range.setSource(QueryDsl.TimeRange.SOURCE_EXPRESSION);
        dsl.setTimeRange(range);
        dsl.setTimeExpressionRaw("今年");
        dsl.setTimePatternId("tep.this_year");
        dsl.setTimeParserVersion("time-parser-1.0");
        assertDoesNotThrow(() -> validator.validate(dsl, manifest));
    }

    @Test
    void shouldRejectYoyMomWithoutTimeRange() {
        manifest.getSemantic().getMetrics().get(0)
                .setAllowedModes(List.of("QUERY", "FUNNEL", "YOY_MOM"));
        QueryDsl dsl = TestFixtures.query();
        dsl.setMode(QueryDsl.AnalysisMode.YOY_MOM);
        QueryDsl.AnalysisParams params = new QueryDsl.AnalysisParams();
        params.setPeriod("MONTH");
        dsl.setAnalysisParams(params);
        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
        assertEquals(DSL_INVALID.getCode(), ex.getCode());
    }

    // ---------- M3/W2：口径变体 ----------

    private SemanticManifest.CaliberVariant caliberVariant(String id) {
        SemanticManifest.VariantCondition condition = new SemanticManifest.VariantCondition();
        condition.setFieldId(TestFixtures.DIMENSION_ID);
        condition.setOperator("EQ");
        condition.setValues(List.of("华东"));
        SemanticManifest.CaliberVariant variant = new SemanticManifest.CaliberVariant();
        variant.setVariantId(id);
        variant.setAlias(List.of("华东口径"));
        variant.setCondition(condition);
        variant.setVersion("1.0.0");
        return variant;
    }

    @Test
    void shouldRequireExplicitVariantWhenMetricHasCalibers() {
        manifest.getSemantic().getMetrics().get(0)
                .setCaliberVariants(List.of(caliberVariant("gmv.online"), caliberVariant("gmv.offline")));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> validator.validate(TestFixtures.query(), manifest));
        assertEquals(CALIBER_AMBIGUOUS.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("gmv.online"), "错误信息必须带候选变体: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("gmv.offline"));
    }

    @Test
    void shouldRejectVariantNotBelongingToMetric() {
        manifest.getSemantic().getMetrics().get(0).setCaliberVariants(List.of(caliberVariant("gmv.online")));
        QueryDsl dsl = TestFixtures.query();
        dsl.setCaliberVariantId("metric.other.v1");
        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
        assertEquals(DSL_INVALID.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("不属于该指标"));
    }

    @Test
    void shouldAcceptExplicitCaliberVariant() {
        manifest.getSemantic().getMetrics().get(0).setCaliberVariants(List.of(caliberVariant("gmv.online")));
        QueryDsl dsl = TestFixtures.query();
        dsl.setCaliberVariantId("gmv.online");
        assertDoesNotThrow(() -> validator.validate(dsl, manifest));
    }

    @Test
    void shouldRejectVariantOnMetricWithoutVariants() {
        QueryDsl dsl = TestFixtures.query();
        dsl.setCaliberVariantId("metric.gmv.online");
        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
        assertEquals(DSL_INVALID.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("没有口径变体"));
    }

    // ---------- M2 收口后深度审核回归（D49–D59）----------

    private QueryDsl yoyMomDsl(QueryDsl.AnalysisParams params, List<String> dimensions) {
        manifest.getSemantic().getMetrics().get(0)
                .setAllowedModes(List.of("QUERY", "FUNNEL", "YOY_MOM"));
        QueryDsl dsl = TestFixtures.query();
        dsl.setMode(QueryDsl.AnalysisMode.YOY_MOM);
        dsl.setDimensions(dimensions);
        dsl.setAnalysisParams(params);
        dsl.setTimeExpressionRaw("今年");
        QueryDsl.TimeRange range = new QueryDsl.TimeRange();
        range.setField(TestFixtures.TIME_DIMENSION_ID);
        range.setFrom(Instant.parse("2026-01-01T00:00:00Z"));
        range.setTo(Instant.parse("2026-12-31T16:00:00Z"));
        range.setSource(QueryDsl.TimeRange.SOURCE_EXPRESSION);
        dsl.setTimeRange(range);
        dsl.setTimePatternId("tep.this_year");
        dsl.setTimeParserVersion("time-parser-1.0");
        return dsl;
    }

    @Test
    void shouldRejectYoyMomWithoutAnalysisParams() {
        // 深度审核 D50：原先一路漏到编译器触发 NPE（500），必须在校验层拒绝
        QueryDsl dsl = yoyMomDsl(null, List.of());
        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
        assertEquals(DSL_INVALID.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("analysisParams"));
    }

    @Test
    void shouldRejectYoyMomWithBlankPeriod() {
        // Set.of(...).contains(null) 会抛 NPE，必须先判空
        QueryDsl.AnalysisParams params = new QueryDsl.AnalysisParams();
        QueryDsl dsl = yoyMomDsl(params, List.of());
        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
        assertEquals(DSL_INVALID.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("period"));
    }

    @Test
    void shouldRejectYoyMomGroupingByTimeDimension() {
        // 时间维度由 period 承担；再按日分组会产出「月值重复」的错位结果
        QueryDsl.AnalysisParams params = new QueryDsl.AnalysisParams();
        params.setPeriod("MONTH");
        QueryDsl dsl = yoyMomDsl(params, List.of(TestFixtures.TIME_DIMENSION_ID));
        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
        assertEquals(DSL_INVALID.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("时间维度"));
    }

    @Test
    void shouldRejectLookupFieldsOnAggregateMode() {
        // 深度审核 D52：原先静默忽略，用户要的属性凭空消失却返回 code=0
        QueryDsl dsl = TestFixtures.query();
        dsl.setEntityId(TestFixtures.CUSTOMER_CONCEPT);
        dsl.setAttributes(List.of(TestFixtures.CUSTOMER_EMAIL_PROPERTY));
        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
        assertEquals(DSL_INVALID.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("lookup"));
    }

    @Test
    void shouldRejectLookupFieldsOnCompositeContainer() {
        QueryDsl dsl = new QueryDsl();
        dsl.setManifestId("manifest-001");
        dsl.setIdentityPropertyId(TestFixtures.CUSTOMER_ID_PROPERTY);
        QueryDsl.SubIntent sub = new QueryDsl.SubIntent();
        sub.setSubIntentId("s1");
        sub.setMetricId(TestFixtures.METRIC_ID);
        dsl.setSubIntents(List.of(sub));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> validator.validateComposite(dsl, manifest));
        assertEquals(DSL_INVALID.getCode(), ex.getCode());
    }

    @Test
    void shouldRejectBooleanMetricOnAggregateMode() {
        QueryDsl dsl = TestFixtures.query();
        dsl.setBooleanMetricId(TestFixtures.BOOLEAN_METRIC_ID);
        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
        assertEquals(DSL_INVALID.getCode(), ex.getCode());
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

    // ---- 复合意图容器校验（M2/W4/T4.3）----

    private static QueryDsl compositeDsl(QueryDsl.SubIntent... subs) {
        QueryDsl dsl = new QueryDsl();
        dsl.setManifestId("manifest-001");
        dsl.setMode(QueryDsl.AnalysisMode.QUERY);
        dsl.setSubIntents(List.of(subs));
        return dsl;
    }

    private static QueryDsl.SubIntent sub(String id, String metricId) {
        QueryDsl.SubIntent sub = new QueryDsl.SubIntent();
        sub.setSubIntentId(id);
        sub.setMetricId(metricId);
        sub.setTimeExpressionRaw("近 30 天");
        return sub;
    }

    @Test
    void shouldAcceptValidComposite() {
        QueryDsl dsl = compositeDsl(sub("s1", TestFixtures.METRIC_ID), sub("s2", TestFixtures.METRIC_ID));
        assertDoesNotThrow(() -> validator.validateComposite(dsl, manifest));
    }

    @Test
    void shouldRejectCompositeWithTopLevelMetric() {
        QueryDsl dsl = compositeDsl(sub("s1", TestFixtures.METRIC_ID));
        dsl.setMetricId(TestFixtures.METRIC_ID);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> validator.validateComposite(dsl, manifest));
        assertEquals(DSL_INVALID.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("顶层 metricId"));
    }

    @Test
    void shouldRejectCompositeWithTopLevelDimensions() {
        QueryDsl dsl = compositeDsl(sub("s1", TestFixtures.METRIC_ID));
        dsl.setDimensions(List.of(TestFixtures.DIMENSION_ID));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> validator.validateComposite(dsl, manifest));
        assertEquals(DSL_INVALID.getCode(), ex.getCode());
    }

    @Test
    void shouldRejectCompositeWithoutSubIntents() {
        QueryDsl dsl = new QueryDsl();
        dsl.setManifestId("manifest-001");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> validator.validateComposite(dsl, manifest));
        assertEquals(DSL_INVALID.getCode(), ex.getCode());
    }

    @Test
    void shouldRejectResultDependentSubIntent() {
        QueryDsl.SubIntent dependent = sub("s2", TestFixtures.METRIC_ID);
        dependent.setDependsOn(List.of("s1"));
        QueryDsl dsl = compositeDsl(sub("s1", TestFixtures.METRIC_ID), dependent);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> validator.validateComposite(dsl, manifest));
        assertEquals(DSL_INVALID.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("结果依赖"));
    }

    @Test
    void shouldRejectNonQuerySubIntent() {
        QueryDsl.SubIntent lookupSub = sub("s1", TestFixtures.METRIC_ID);
        lookupSub.setMode(QueryDsl.AnalysisMode.LOOKUP);
        QueryDsl dsl = compositeDsl(lookupSub);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> validator.validateComposite(dsl, manifest));
        assertEquals(DSL_INVALID.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("仅支持 QUERY"));
    }
}

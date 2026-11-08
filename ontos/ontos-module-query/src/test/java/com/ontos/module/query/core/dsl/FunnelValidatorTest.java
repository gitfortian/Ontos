package com.ontos.module.query.core.dsl;

import java.util.List;

import com.ontos.framework.common.exception.ServiceException;
import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.query.core.QueryProperties;
import com.ontos.module.query.core.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.ontos.module.query.enums.ErrorCodeConstants.DSL_INVALID;
import static com.ontos.module.query.enums.ErrorCodeConstants.FUNNEL_DIMENSION_UNSUPPORTED;
import static com.ontos.module.query.enums.ErrorCodeConstants.FUNNEL_EVENT_MAPPING_MISSING;
import static com.ontos.module.query.enums.ErrorCodeConstants.FUNNEL_EVENT_NOT_FOUND;
import static com.ontos.module.query.enums.ErrorCodeConstants.FUNNEL_TIME_WINDOW_MISSING;
import static com.ontos.module.query.enums.ErrorCodeConstants.SENSITIVE_BULK_EXPORT_REJECTED;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** W3/T3.3a：funnel 入口校验（ADR-0012 的 fail-closed 约束）。 */
class FunnelValidatorTest {

    private DslValidator validator;
    private PublishedManifest manifest;

    @BeforeEach
    void setUp() {
        validator = new DslValidator(TestFixtures.queryProperties());
        manifest = TestFixtures.funnelManifest();
    }

    @Test
    void shouldRejectSensitiveDimensionBulkFunnel() {
        QueryProperties properties = TestFixtures.queryProperties();
        properties.setSensitiveUngroupedLimit(10);
        DslValidator strict = new DslValidator(properties);
        manifest.getSemantic().getDimensions().get(0).setSensitive(true);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> strict.validate(TestFixtures.funnelDsl(), manifest));
        assertEquals(SENSITIVE_BULK_EXPORT_REJECTED.getCode(), ex.getCode());
    }

    @Test
    void shouldAcceptValidFunnel() {
        assertDoesNotThrow(() -> validator.validate(TestFixtures.funnelDsl(), manifest));
    }

    @Test
    void shouldRejectWhenEventMappingMissing() {
        manifest.getOntology().getEvents().get(0).setEventTypeProperty(null);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> validator.validate(TestFixtures.funnelDsl(), manifest));
        assertEquals(FUNNEL_EVENT_MAPPING_MISSING.getCode(), ex.getCode());
    }

    @Test
    void shouldRejectStepWithoutUniqueEventDefinition() {
        QueryDsl dsl = TestFixtures.funnelDsl();
        dsl.getAnalysisParams().setSteps(List.of("ORDER_PLACED", "NOT_PUBLISHED"));
        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
        assertEquals(FUNNEL_EVENT_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void shouldRejectDimensionOutsideSubjectEntity() {
        manifest.getSemantic().getDimensions().get(0).setTable("orders");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> validator.validate(TestFixtures.funnelDsl(), manifest));
        assertEquals(FUNNEL_DIMENSION_UNSUPPORTED.getCode(), ex.getCode());
    }

    @Test
    void shouldRejectUnresolvedCohortWindow() {
        QueryDsl dsl = TestFixtures.funnelDsl();
        dsl.getAnalysisParams().getCohortWindow().setFrom(null);
        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
        assertEquals(FUNNEL_TIME_WINDOW_MISSING.getCode(), ex.getCode());
    }

    @Test
    void shouldRejectMetricIdAndTopLevelTimeRange() {
        QueryDsl withMetric = TestFixtures.funnelDsl();
        withMetric.setMetricId("gmv");
        assertEquals(DSL_INVALID.getCode(), assertThrows(ServiceException.class,
                () -> validator.validate(withMetric, manifest)).getCode());

        QueryDsl withTime = TestFixtures.funnelDsl();
        QueryDsl.TimeRange range = new QueryDsl.TimeRange();
        range.setField("order_date");
        withTime.setTimeRange(range);
        assertEquals(DSL_INVALID.getCode(), assertThrows(ServiceException.class,
                () -> validator.validate(withTime, manifest)).getCode());
    }

    @Test
    void shouldRejectFiltersInsteadOfSilentlyIgnoringThem() {
        // 深度审核 D58：步进 CTE 没有接 filters 的位置，静默忽略会让「华东漏斗」返回全国数字
        QueryDsl dsl = TestFixtures.funnelDsl();
        QueryDsl.Filter filter = new QueryDsl.Filter();
        filter.setFieldId(TestFixtures.CUSTOMER_TIER_DIMENSION);
        filter.setOperator(QueryDsl.Operator.EQ);
        filter.setValues(List.of("gold"));
        dsl.setFilters(List.of(filter));

        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
        assertEquals(DSL_INVALID.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("filters"));
    }

    @Test
    void shouldRejectStepsWithDifferentPhysicalMapping() {
        // 深度审核 D53：编译器以首事件的判定/时间/主体列为整条 CTE 的口径，
        // 后续步骤映射不同必须拒绝，不能按首事件静默解读
        manifest.getOntology().getEvents().get(1)
                .setParticipantProperty(TestFixtures.CUSTOMER_SUBJECT_PROPERTY);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> validator.validate(TestFixtures.funnelDsl(), manifest));
        assertEquals(FUNNEL_EVENT_MAPPING_MISSING.getCode(), ex.getCode());
    }

}

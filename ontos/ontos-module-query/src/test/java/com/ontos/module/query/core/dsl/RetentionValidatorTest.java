package com.ontos.module.query.core.dsl;

import java.time.Instant;
import java.util.List;

import com.ontos.framework.common.exception.ServiceException;
import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.ontology.core.SemanticManifest;
import com.ontos.module.query.core.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.ontos.module.query.enums.ErrorCodeConstants.DSL_INVALID;
import static com.ontos.module.query.enums.ErrorCodeConstants.RETENTION_DIMENSION_UNSUPPORTED;
import static com.ontos.module.query.enums.ErrorCodeConstants.RETENTION_EVENT_NOT_FOUND;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M6/T1.3：retention 入口校验（模式规格 v1.1 §1 + 阶段中审核 D-5/D-6 编码约束）。
 *
 * <p>断言口径：每条负例都必须命中**具体**结构化原因码，不能只断言「抛异常」——
 * 否则「拒绝对了但理由错了」会被放过（例如把事件不存在报成维度不可达）。
 */
class RetentionValidatorTest {

    private DslValidator validator;
    private PublishedManifest manifest;

    @BeforeEach
    void setUp() {
        validator = new DslValidator(TestFixtures.queryProperties());
        manifest = TestFixtures.retentionManifest();
    }

    @Test
    void shouldAcceptValidRetention() {
        QueryDsl dsl = TestFixtures.retentionDsl();
        assertDoesNotThrow(() -> validator.validate(dsl, manifest));
        assertEquals(100, dsl.getLimit(), "校验器回填生效 limit");
    }

    @Test
    void shouldRejectMetricAnchor() {
        QueryDsl dsl = TestFixtures.retentionDsl();
        dsl.setMetricId(TestFixtures.METRIC_ID);
        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
        assertEquals(DSL_INVALID.getCode(), ex.getCode());
    }

    @Test
    void shouldRejectAbsoluteDateChannel() {
        QueryDsl dsl = TestFixtures.retentionDsl();
        dsl.setExplicitRange(new QueryDsl.TimeRange());
        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
        assertEquals(DSL_INVALID.getCode(), ex.getCode());
    }

    @Test
    void shouldRejectFiltersInsteadOfSilentlyIgnoring() {
        QueryDsl dsl = TestFixtures.retentionDsl();
        QueryDsl.Filter filter = new QueryDsl.Filter();
        filter.setFieldId(TestFixtures.CUSTOMER_TIER_DIMENSION);
        filter.setOperator(QueryDsl.Operator.EQ);
        filter.setValues(List.of("gold"));
        dsl.setFilters(List.of(filter));
        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
        assertEquals(DSL_INVALID.getCode(), ex.getCode());
    }

    @Test
    void shouldRejectLookupFields() {
        QueryDsl dsl = TestFixtures.retentionDsl();
        dsl.setEntityId(TestFixtures.CUSTOMER_CONCEPT);
        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
        assertEquals(DSL_INVALID.getCode(), ex.getCode());
    }

    @Test
    void shouldRejectMissingCohortEvent() {
        QueryDsl dsl = TestFixtures.retentionDsl();
        dsl.getAnalysisParams().setCohortEvent(null);
        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
        assertEquals(DSL_INVALID.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("cohortEvent"), ex.getMessage());
    }

    @Test
    void shouldRejectUnknownEvent() {
        QueryDsl dsl = TestFixtures.retentionDsl();
        dsl.getAnalysisParams().setReturnEvent("NOT_PUBLISHED");
        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
        assertEquals(RETENTION_EVENT_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void shouldRejectEventMappingMissing() {
        manifest.getOntology().getEvents().get(0).setParticipantProperty(null);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> validator.validate(TestFixtures.retentionDsl(), manifest));
        assertEquals(RETENTION_EVENT_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void shouldRejectInvalidPeriodUnit() {
        QueryDsl dsl = TestFixtures.retentionDsl();
        dsl.getAnalysisParams().setPeriodUnit("QUARTER");
        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
        assertEquals(DSL_INVALID.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("WEEK"), ex.getMessage());
    }

    @Test
    void shouldRejectPeriodsOutOfRange() {
        QueryDsl dsl = TestFixtures.retentionDsl();
        dsl.getAnalysisParams().setPeriods(13);
        ServiceException exOne = assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
        assertEquals(DSL_INVALID.getCode(), exOne.getCode());

        QueryDsl tooFew = TestFixtures.retentionDsl();
        tooFew.getAnalysisParams().setPeriods(1);
        ServiceException exTwo = assertThrows(ServiceException.class, () -> validator.validate(tooFew, manifest));
        assertEquals(DSL_INVALID.getCode(), exTwo.getCode());

        QueryDsl missing = TestFixtures.retentionDsl();
        missing.getAnalysisParams().setPeriods(null);
        ServiceException exThree = assertThrows(ServiceException.class, () -> validator.validate(missing, manifest));
        assertEquals(DSL_INVALID.getCode(), exThree.getCode());
    }

    @Test
    void shouldRejectEventDimensionFromNonSubjectTable() {
        SemanticManifest.Dimension region = new SemanticManifest.Dimension();
        region.setId(TestFixtures.DIMENSION_ID);
        region.setName("大区");
        region.setTable(TestFixtures.TABLE);
        region.setColumn(TestFixtures.TABLE + ".region");
        manifest.getSemantic().setDimensions(List.of(
                manifest.getSemantic().getDimensions().get(0), region));
        QueryDsl dsl = TestFixtures.retentionDsl();
        dsl.setDimensions(List.of(TestFixtures.DIMENSION_ID));

        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
        assertEquals(RETENTION_DIMENSION_UNSUPPORTED.getCode(), ex.getCode());
    }

    @Test
    void shouldRejectMixingFunnelParameters() {
        QueryDsl dsl = TestFixtures.retentionDsl();
        dsl.getAnalysisParams().setStepWindow("7d");
        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
        assertEquals(DSL_INVALID.getCode(), ex.getCode());
    }

    @Test
    void shouldRejectTamperedTimeField() {
        QueryDsl dsl = TestFixtures.retentionDsl();
        dsl.getTimeRange().setField(TestFixtures.EVENT_PARTICIPANT_PROPERTY);
        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
        assertEquals(DSL_INVALID.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("timeRange.field"), ex.getMessage());
    }

    @Test
    void shouldRejectUnresolvedWindow() {
        QueryDsl dsl = TestFixtures.retentionDsl();
        dsl.setTimeRange(null);
        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
        assertEquals(DSL_INVALID.getCode(), ex.getCode());

        QueryDsl noPattern = TestFixtures.retentionDsl();
        noPattern.setTimePatternId(null);
        ServiceException exTwo = assertThrows(ServiceException.class,
                () -> validator.validate(noPattern, manifest));
        assertEquals(DSL_INVALID.getCode(), exTwo.getCode());
    }

    @Test
    void shouldRejectWindowStartAfterEnd() {
        QueryDsl dsl = TestFixtures.retentionDsl();
        dsl.getTimeRange().setFrom(Instant.parse("2026-11-09T00:00:00Z"));
        dsl.getTimeRange().setTo(Instant.parse("2026-10-01T00:00:00Z"));
        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
        assertEquals(DSL_INVALID.getCode(), ex.getCode());
    }

}

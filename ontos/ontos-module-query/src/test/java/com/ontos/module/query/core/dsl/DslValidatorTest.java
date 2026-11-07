package com.ontos.module.query.core.dsl;

import java.time.Instant;
import java.util.List;

import com.ontos.framework.common.exception.ServiceException;
import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.query.core.QueryProperties;
import com.ontos.module.query.core.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.ontos.module.query.enums.ErrorCodeConstants.ANALYSIS_MODE_NOT_IMPLEMENTED;
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
}

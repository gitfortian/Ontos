package com.ontos.module.query.core.dsl;

import java.util.List;

import com.ontos.framework.common.exception.ServiceException;
import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.ontology.core.SemanticManifest;
import com.ontos.module.query.core.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.ontos.module.ontology.enums.ErrorCodeConstants.ASSET_MODE_NOT_ALLOWED;
import static com.ontos.module.query.enums.ErrorCodeConstants.ATTRIBUTION_DIMENSION_UNSUPPORTED;
import static com.ontos.module.query.enums.ErrorCodeConstants.ATTRIBUTION_EVENT_NOT_FOUND;
import static com.ontos.module.query.enums.ErrorCodeConstants.ATTRIBUTION_MODEL_NOT_IMPLEMENTED;
import static com.ontos.module.query.enums.ErrorCodeConstants.DSL_INVALID;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M6/T1.4：attribution 入口校验（模式规格 v1.1 §2 + 聚合锚点约束）。
 *
 * <p>断言口径：每条负例必须命中**具体**结构化原因码——「拒绝对了但理由错了」会被放过。
 */
class AttributionValidatorTest {

    private DslValidator validator;
    private PublishedManifest manifest;

    @BeforeEach
    void setUp() {
        validator = new DslValidator(TestFixtures.queryProperties());
        manifest = TestFixtures.attributionManifest();
    }

    @Test
    void shouldAcceptValidAttribution() {
        QueryDsl dsl = TestFixtures.attributionDsl();
        assertDoesNotThrow(() -> validator.validate(dsl, manifest));
        assertEquals(100, dsl.getLimit());
    }

    @Test
    void shouldRejectMetricWithoutAttributionMode() {
        SemanticManifest.Metric metric = manifest.getSemantic().getMetrics().get(0);
        metric.setAllowedModes(List.of("QUERY"));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> validator.validate(TestFixtures.attributionDsl(), manifest));
        assertEquals(ASSET_MODE_NOT_ALLOWED.getCode(), ex.getCode());
    }

    @Test
    void shouldRejectMissingEventPair() {
        QueryDsl dsl = TestFixtures.attributionDsl();
        dsl.getAnalysisParams().setTouchEvent(null);
        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
        assertEquals(DSL_INVALID.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("touchEvent"), ex.getMessage());
    }

    @Test
    void shouldRejectInvalidWindow() {
        QueryDsl dsl = TestFixtures.attributionDsl();
        dsl.getAnalysisParams().setAttributionWindow("90d");
        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
        assertEquals(DSL_INVALID.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("7d"), ex.getMessage());
    }

    @Test
    void shouldRejectDeclaredButUnimplementedModel() {
        for (String model : List.of("first_touch", "linear")) {
            QueryDsl dsl = TestFixtures.attributionDsl();
            dsl.getAnalysisParams().setModel(model);
            ServiceException ex = assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
            assertEquals(ATTRIBUTION_MODEL_NOT_IMPLEMENTED.getCode(), ex.getCode(), model);
        }
    }

    @Test
    void shouldRejectUnknownModel() {
        QueryDsl dsl = TestFixtures.attributionDsl();
        dsl.getAnalysisParams().setModel("position_based");
        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
        assertEquals(DSL_INVALID.getCode(), ex.getCode());
    }

    @Test
    void shouldRejectUnknownTouchEvent() {
        QueryDsl dsl = TestFixtures.attributionDsl();
        dsl.getAnalysisParams().setTouchEvent("NOT_PUBLISHED");
        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
        assertEquals(ATTRIBUTION_EVENT_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void shouldRejectTouchEventWithoutChannelProperty() {
        // 触点事件未声明 channelPropertyId：渠道物理来源缺失，禁止用自由文本或命名习惯替代
        manifest.getOntology().getEvents().get(2).setChannelPropertyId(null);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> validator.validate(TestFixtures.attributionDsl(), manifest));
        assertEquals(ATTRIBUTION_EVENT_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void shouldRejectEventMappingAcrossTables() {
        manifest.getOntology().getProperties().stream()
                .filter(property -> TestFixtures.EVENT_CHANNEL_PROPERTY.equals(property.getId()))
                .findFirst().orElseThrow()
                .setColumn("channel_touch.channel_id");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> validator.validate(TestFixtures.attributionDsl(), manifest));
        assertEquals(ATTRIBUTION_EVENT_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void shouldRejectDimensionOutsideMetricTable() {
        SemanticManifest.Dimension tier = new SemanticManifest.Dimension();
        tier.setId("dim.customer_tier");
        tier.setTable("customer");
        tier.setColumn("customer.tier");
        manifest.getSemantic().setDimensions(List.of(
                manifest.getSemantic().getDimensions().get(0), tier));
        QueryDsl dsl = TestFixtures.attributionDsl();
        dsl.setDimensions(List.of("dim.customer_tier"));

        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
        assertEquals(ATTRIBUTION_DIMENSION_UNSUPPORTED.getCode(), ex.getCode());
    }

    @Test
    void shouldRejectFiltersAndVariantAndAbsoluteDate() {
        QueryDsl withFilter = TestFixtures.attributionDsl();
        QueryDsl.Filter filter = new QueryDsl.Filter();
        filter.setFieldId(TestFixtures.CHANNEL_DIMENSION_ID);
        filter.setOperator(QueryDsl.Operator.EQ);
        filter.setValues(List.of("线上"));
        withFilter.setFilters(List.of(filter));
        assertEquals(DSL_INVALID.getCode(), assertThrows(ServiceException.class,
                () -> validator.validate(withFilter, manifest)).getCode());

        QueryDsl withVariant = TestFixtures.attributionDsl();
        withVariant.setCaliberVariantId("cv.online");
        assertEquals(DSL_INVALID.getCode(), assertThrows(ServiceException.class,
                () -> validator.validate(withVariant, manifest)).getCode());

        QueryDsl withExplicit = TestFixtures.attributionDsl();
        withExplicit.setExplicitRange(new QueryDsl.TimeRange());
        assertEquals(DSL_INVALID.getCode(), assertThrows(ServiceException.class,
                () -> validator.validate(withExplicit, manifest)).getCode());
    }

    @Test
    void shouldRejectTamperedTimeField() {
        QueryDsl dsl = TestFixtures.attributionDsl();
        dsl.getTimeRange().setField(TestFixtures.EVENT_ORDER_PROPERTY);
        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
        assertEquals(DSL_INVALID.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("timeRange.field"), ex.getMessage());
    }

    @Test
    void shouldRejectMixingRetentionParams() {
        QueryDsl dsl = TestFixtures.attributionDsl();
        dsl.getAnalysisParams().setCohortEvent("ORDER_PLACED");
        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
        assertEquals(DSL_INVALID.getCode(), ex.getCode());
    }

    @Test
    void shouldRejectUnresolvedWindow() {
        QueryDsl dsl = TestFixtures.attributionDsl();
        dsl.setTimeRange(null);
        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
        assertEquals(DSL_INVALID.getCode(), ex.getCode());
    }

}

package com.ontos.module.catalog.service.asset;

import com.ontos.framework.common.exception.ServiceException;
import com.ontos.module.catalog.core.SemanticAssetType;
import com.ontos.module.catalog.support.TestManifests;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 草稿校验（B1/W3）：合并生效 v22 清单后只返回本资产 violations。
 */
class SemanticAssetDraftValidatorTest {

    private SemanticAssetDraftValidator validator;

    @BeforeEach
    void setUp() {
        validator = new SemanticAssetDraftValidator(
                TestManifests.manifestService(TestManifests.activeV22()));
    }

    @Test
    void shouldValidateMetricDraftAgainstActiveManifest() {
        SemanticAssetDraftValidator.ValidationResult result = validator.validate(
                SemanticAssetType.METRIC,
                metricDraft("metric.gmv", "GMV 改名", "property.order.amount", "SUM"));

        assertTrue(result.valid(), result.summary());
    }

    @Test
    void shouldRejectMissingPropertyReference() {
        SemanticAssetDraftValidator.ValidationResult result = validator.validate(
                SemanticAssetType.METRIC,
                metricDraft("metric.gmv", "GMV", "property.order.not_exists", "SUM"));

        assertFalse(result.valid());
        assertTrue(result.violations().stream()
                        .anyMatch(v -> "metric.metric.gmv".equals(v.location())),
                result.summary());
        assertTrue(result.summary().contains("property.order.not_exists"), result.summary());
    }

    @Test
    void shouldValidateOverriddenMetricWithoutCrossAssetNoise() {
        SemanticAssetDraftValidator.ValidationResult result = validator.validate(
                SemanticAssetType.METRIC,
                metricDraft("metric.gmv", "GMV", "property.order.amount", "AVG"));

        assertTrue(result.valid(), result.summary());
    }

    @Test
    void shouldValidateDimensionDraftAgainstActiveManifest() {
        SemanticAssetDraftValidator.ValidationResult result = validator.validate(
                SemanticAssetType.DIMENSION, dimensionDraft("dim.region", "区域改名"));

        assertTrue(result.valid(), result.summary());
    }

    @Test
    void shouldRejectEmptyDefinition() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> validator.validate(SemanticAssetType.METRIC, "  "));
        assertEquals(1_005_000_107, ex.getCode());
    }

    @Test
    void shouldRejectTypePrefixMismatch() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> validator.validate(SemanticAssetType.METRIC,
                        metricDraft("dim.region", "区域", "property.order.amount", "SUM")));
        assertEquals(1_005_000_107, ex.getCode());
    }

    private static String metricDraft(String id, String name, String propertyId, String aggregation) {
        return """
                {"id":"%s","name":"%s","summary":"s","aliases":["a"],
                 "objectId":"concept.order","propertyId":"%s","aggregation":"%s",
                 "allowedModes":["QUERY"],"allowedDimensions":["dim.region"],"sensitive":false}
                """.formatted(id, name, propertyId, aggregation);
    }

    private static String dimensionDraft(String id, String name) {
        return """
                {"id":"%s","name":"%s","summary":"s","aliases":["a"],
                 "objectId":"concept.order","propertyId":"property.order.region","sensitive":false}
                """.formatted(id, name);
    }
}

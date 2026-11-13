package com.ontos.module.catalog.service.asset;

import com.ontos.framework.common.exception.ServiceException;
import com.ontos.module.catalog.core.SemanticAssetType;
import com.ontos.module.catalog.dal.dataobject.SemanticAssetRecord;
import com.ontos.module.catalog.support.InMemorySemanticAssetRepository;
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
                new InMemorySemanticAssetRepository(),
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

    @Test
    void crossDraftReferencesResolveThroughReviewingBatch() {
        // B1/P1-B：合并视图含全部审核中草稿——「新对象 + 挂在其上的属性」在提交阶段即可互相引用
        InMemorySemanticAssetRepository drafts = new InMemorySemanticAssetRepository();
        drafts.insert(reviewing("concept", "concept.warehouse",
                "{\"id\":\"concept.warehouse\",\"name\":\"仓库\",\"domain\":\"order\","
                        + "\"aliases\":[\"仓储\"],\"summary\":\"订单发货的仓库\","
                        + "\"identityProperty\":\"property.warehouse.id\"}"));
        drafts.insert(reviewing("property", "property.warehouse.id",
                "{\"id\":\"property.warehouse.id\",\"name\":\"仓库编号\","
                        + "\"conceptId\":\"concept.warehouse\",\"column\":\"orders.region\","
                        + "\"valueType\":\"string\",\"aliases\":[\"仓库\"],"
                        + "\"summary\":\"仓库唯一编号\",\"filterable\":true,"
                        + "\"sensitivity\":\"INTERNAL\",\"lookupProjectable\":true,"
                        + "\"existenceSensitive\":false}"));
        SemanticAssetDraftValidator batchAware = new SemanticAssetDraftValidator(
                drafts, TestManifests.manifestService(TestManifests.activeV22()));

        SemanticAssetDraftValidator.ValidationResult conceptResult = batchAware.validate(
                SemanticAssetType.CONCEPT,
                "concept.warehouse",
                "{\"id\":\"concept.warehouse\",\"name\":\"仓库\",\"domain\":\"order\","
                        + "\"aliases\":[\"仓储\"],\"summary\":\"订单发货的仓库\","
                        + "\"identityProperty\":\"property.warehouse.id\"}");
        assertTrue(conceptResult.valid(), String.valueOf(conceptResult.violations()));

        SemanticAssetDraftValidator.ValidationResult propertyResult = batchAware.validate(
                SemanticAssetType.PROPERTY,
                "property.warehouse.id",
                "{\"id\":\"property.warehouse.id\",\"name\":\"仓库编号\","
                        + "\"conceptId\":\"concept.warehouse\",\"column\":\"orders.region\","
                        + "\"valueType\":\"string\",\"aliases\":[\"仓库\"],"
                        + "\"summary\":\"仓库唯一编号\",\"filterable\":true,"
                        + "\"sensitivity\":\"INTERNAL\",\"lookupProjectable\":true,"
                        + "\"existenceSensitive\":false}");
        assertTrue(propertyResult.valid(), String.valueOf(propertyResult.violations()));
    }

    private static SemanticAssetRecord reviewing(String assetType, String id, String definition) {
        SemanticAssetRecord record = new SemanticAssetRecord();
        record.setAssetType(assetType);
        record.setId(id);
        record.setName(id);
        record.setSummary("冒烟");
        record.setStatus("reviewing");
        record.setRevision(0);
        record.setDefinitionJson(definition);
        return record;
    }
}

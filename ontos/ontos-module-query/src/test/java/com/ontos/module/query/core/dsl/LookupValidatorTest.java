package com.ontos.module.query.core.dsl;

import java.util.List;

import com.ontos.framework.common.exception.ServiceException;
import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.query.core.QueryProperties;
import com.ontos.module.query.core.TestFixtures;
import com.ontos.module.ontology.core.OntologySnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.ontos.module.query.enums.ErrorCodeConstants.DSL_INVALID;
import static com.ontos.module.query.enums.ErrorCodeConstants.LOOKUP_ATTRIBUTE_NOT_PROJECTABLE;
import static com.ontos.module.query.enums.ErrorCodeConstants.LOOKUP_BATCH_LIMIT_EXCEEDED;
import static com.ontos.module.query.enums.ErrorCodeConstants.LOOKUP_CROSS_TABLE_UNSUPPORTED;
import static com.ontos.module.query.enums.ErrorCodeConstants.LOOKUP_IDENTITY_REQUIRED;
import static com.ontos.module.query.enums.ErrorCodeConstants.SENSITIVE_BULK_EXPORT_REJECTED;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** W3/T3.2：lookup / lookup_batch 的入口校验（v1.4 §6.3.1）。 */
class LookupValidatorTest {

    private DslValidator validator;
    private PublishedManifest manifest;

    @BeforeEach
    void setUp() {
        QueryProperties properties = TestFixtures.queryProperties();
        properties.setMaxBatchSize(3);
        validator = new DslValidator(properties);
        manifest = TestFixtures.lookupManifest();
    }

    @Test
    void shouldAcceptSingleAndBatchLookup() {
        assertDoesNotThrow(() -> validator.validate(TestFixtures.singleLookup(), manifest));
        assertDoesNotThrow(() -> validator.validate(TestFixtures.batchLookup(), manifest));
    }

    @Test
    void shouldAllowExistenceSensitiveIdentityWithoutReadableValue() {
        // g-l04：邮箱是存在性敏感且不可过滤，但允许作为标识「可查不可看值」
        QueryDsl dsl = TestFixtures.singleLookup();
        dsl.setIdentity(newIdentity(TestFixtures.CUSTOMER_EMAIL_PROPERTY, "zhang@example.com"));
        assertDoesNotThrow(() -> validator.validate(dsl, manifest));
    }

    @Test
    void shouldRejectMissingEntity() {
        QueryDsl dsl = TestFixtures.singleLookup();
        dsl.setEntityId("concept.unknown");
        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
        assertEquals(LOOKUP_IDENTITY_REQUIRED.getCode(), ex.getCode());
    }

    @Test
    void shouldRejectEmptyAttributes() {
        QueryDsl dsl = TestFixtures.singleLookup();
        dsl.setAttributes(List.of());
        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
        assertEquals(LOOKUP_ATTRIBUTE_NOT_PROJECTABLE.getCode(), ex.getCode());
    }

    @Test
    void shouldRejectNonProjectableAttribute() {
        QueryDsl dsl = TestFixtures.singleLookup();
        dsl.setAttributes(List.of(TestFixtures.CUSTOMER_TIER_PROPERTY));
        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
        assertEquals(LOOKUP_ATTRIBUTE_NOT_PROJECTABLE.getCode(), ex.getCode());
    }

    @Test
    void shouldRejectAttributeOfAnotherEntity() {
        QueryDsl dsl = TestFixtures.singleLookup();
        dsl.setAttributes(List.of(TestFixtures.ORDER_NO_PROPERTY));
        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
        assertEquals(LOOKUP_ATTRIBUTE_NOT_PROJECTABLE.getCode(), ex.getCode());
    }

    @Test
    void shouldRejectCrossTableProjection() {
        // 同实体、不同物理表（customer vs customer_profile）才是「投影跨表」，
        // 属性属于别的实体由 016 拒绝，两者错误码不混用
        QueryDsl dsl = TestFixtures.batchLookup();
        dsl.setAttributes(List.of(TestFixtures.CUSTOMER_EMAIL_PROPERTY, TestFixtures.CUSTOMER_BIO_PROPERTY));
        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
        assertEquals(LOOKUP_CROSS_TABLE_UNSUPPORTED.getCode(), ex.getCode());
    }

    @Test
    void shouldRejectSingleLookupWithoutIdentity() {
        QueryDsl dsl = TestFixtures.singleLookup();
        dsl.setIdentity(null);
        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
        assertEquals(LOOKUP_IDENTITY_REQUIRED.getCode(), ex.getCode());
    }

    @Test
    void shouldRejectSingleLookupLimitOtherThanOne() {
        QueryDsl dsl = TestFixtures.singleLookup();
        dsl.setLimit(5);
        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
        assertEquals(DSL_INVALID.getCode(), ex.getCode());
    }

    @Test
    void shouldRejectBatchOverConfiguredLimit() {
        QueryDsl dsl = TestFixtures.batchLookup();
        dsl.setIdentities(List.of("c-1", "c-2", "c-3", "c-4"));
        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
        assertEquals(LOOKUP_BATCH_LIMIT_EXCEEDED.getCode(), ex.getCode());
    }

    @Test
    void shouldRejectBatchWhenLimitWouldTruncateIdentities() {
        // limit 小于标识数会静默把后面的 identity 误判为不可用，必须拒绝而非截断
        QueryDsl dsl = TestFixtures.batchLookup();
        dsl.setLimit(1);
        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
        assertEquals(LOOKUP_BATCH_LIMIT_EXCEEDED.getCode(), ex.getCode());
    }

    @Test
    void shouldRejectBlankIdentityValue() {
        QueryDsl dsl = TestFixtures.batchLookup();
        dsl.setIdentities(List.of("c-1", " "));
        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
        assertEquals(LOOKUP_IDENTITY_REQUIRED.getCode(), ex.getCode());
    }

    @Test
    void shouldRejectSensitiveBulkLookupOverLimit() {
        QueryProperties properties = TestFixtures.queryProperties();
        properties.setMaxBatchSize(3);
        properties.setSensitiveUngroupedLimit(1);
        DslValidator strict = new DslValidator(properties);
        manifest.getOntology().propertyIndex().get(TestFixtures.CUSTOMER_EMAIL_PROPERTY)
                .setSensitivity(OntologySnapshot.Sensitivity.PII);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> strict.validate(TestFixtures.batchLookup(), manifest));
        assertEquals(SENSITIVE_BULK_EXPORT_REJECTED.getCode(), ex.getCode());
    }

    @Test
    void shouldAllowSensitiveBulkLookupWithinLimit() {
        QueryProperties properties = TestFixtures.queryProperties();
        properties.setMaxBatchSize(3);
        properties.setSensitiveUngroupedLimit(2);
        DslValidator strict = new DslValidator(properties);
        manifest.getOntology().propertyIndex().get(TestFixtures.CUSTOMER_EMAIL_PROPERTY)
                .setSensitivity(OntologySnapshot.Sensitivity.PII);
        assertDoesNotThrow(() -> strict.validate(TestFixtures.batchLookup(), manifest));
    }

    @Test
    void shouldNotApplyBulkGuardToNonSensitiveAttributes() {
        QueryProperties properties = TestFixtures.queryProperties();
        properties.setMaxBatchSize(3);
        properties.setSensitiveUngroupedLimit(1);
        DslValidator strict = new DslValidator(properties);
        // email 在夹具里是 existenceSensitive，改用普通编号属性才是真正的「非敏感」
        QueryDsl dsl = TestFixtures.batchLookup();
        dsl.setAttributes(List.of(TestFixtures.CUSTOMER_ID_PROPERTY));
        assertDoesNotThrow(() -> strict.validate(dsl, manifest));
    }

    @Test
    void shouldRejectBatchWithoutIdentities() {
        QueryDsl dsl = TestFixtures.batchLookup();
        dsl.setIdentities(List.of());
        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
        assertEquals(LOOKUP_IDENTITY_REQUIRED.getCode(), ex.getCode());
    }

    @Test
    void shouldRejectLookupWithMetricOrTimeWindow() {
        QueryDsl withMetric = TestFixtures.singleLookup();
        withMetric.setMetricId("gmv");
        assertEquals(DSL_INVALID.getCode(), assertThrows(ServiceException.class,
                () -> validator.validate(withMetric, manifest)).getCode());

        QueryDsl withTime = TestFixtures.singleLookup();
        QueryDsl.TimeRange range = new QueryDsl.TimeRange();
        range.setField("order_date");
        withTime.setTimeRange(range);
        assertEquals(DSL_INVALID.getCode(), assertThrows(ServiceException.class,
                () -> validator.validate(withTime, manifest)).getCode());
    }

    private static QueryDsl.LookupIdentity newIdentity(String propertyId, String value) {
        QueryDsl.LookupIdentity identity = new QueryDsl.LookupIdentity();
        identity.setPropertyId(propertyId);
        identity.setValue(value);
        return identity;
    }

}

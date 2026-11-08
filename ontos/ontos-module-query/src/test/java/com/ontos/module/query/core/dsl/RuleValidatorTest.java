package com.ontos.module.query.core.dsl;

import java.util.Map;

import com.ontos.framework.common.exception.ServiceException;
import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.query.core.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.ontos.module.query.enums.ErrorCodeConstants.DSL_INVALID;
import static com.ontos.module.query.enums.ErrorCodeConstants.RULE_EXPRESSION_INVALID;
import static com.ontos.module.query.enums.ErrorCodeConstants.RULE_NOT_FOUND;
import static com.ontos.module.query.enums.ErrorCodeConstants.RULE_NOT_PUBLISHED;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** W3/T3.4：布尔判定列的入口校验（Rule 只认 published）。 */
class RuleValidatorTest {

    private DslValidator validator;
    private PublishedManifest manifest;

    @BeforeEach
    void setUp() {
        validator = new DslValidator(TestFixtures.queryProperties());
        manifest = TestFixtures.ruleManifest();
    }

    @Test
    void shouldAcceptPublishedBooleanMetric() {
        assertDoesNotThrow(() -> validator.validate(TestFixtures.ruleLookupDsl(), manifest));
    }

    @Test
    void shouldRejectNonBooleanMetric() {
        QueryDsl dsl = TestFixtures.ruleLookupDsl();
        dsl.setBooleanMetricId(TestFixtures.RULE_METRIC_ID); // 普通指标，没有 ruleId
        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validate(dsl, manifest));
        assertEquals(DSL_INVALID.getCode(), ex.getCode());
    }

    @Test
    void shouldRejectDraftRule() {
        manifest.getRules().get(0).setStatus("draft");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> validator.validate(TestFixtures.ruleLookupDsl(), manifest));
        assertEquals(RULE_NOT_PUBLISHED.getCode(), ex.getCode());
    }

    @Test
    void shouldRejectMissingRule() {
        manifest.setRules(java.util.List.of());
        ServiceException ex = assertThrows(ServiceException.class,
                () -> validator.validate(TestFixtures.ruleLookupDsl(), manifest));
        assertEquals(RULE_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void shouldRejectInvalidExpressionTriple() {
        manifest.getRules().get(0).setExpression(Map.of(
                "metricId", TestFixtures.RULE_METRIC_ID, "operator", "IN", "threshold", 50000));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> validator.validate(TestFixtures.ruleLookupDsl(), manifest));
        assertEquals(RULE_EXPRESSION_INVALID.getCode(), ex.getCode());
    }

}

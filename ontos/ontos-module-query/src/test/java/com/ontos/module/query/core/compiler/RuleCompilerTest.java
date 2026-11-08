package com.ontos.module.query.core.compiler;

import java.util.List;
import java.util.Map;

import com.ontos.framework.common.exception.ServiceException;
import com.ontos.module.ontology.core.OntologySnapshot;
import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.query.core.TestFixtures;
import org.junit.jupiter.api.Test;

import static com.ontos.module.query.enums.ErrorCodeConstants.RULE_EXPRESSION_INVALID;
import static com.ontos.module.query.enums.ErrorCodeConstants.RULE_NOT_PUBLISHED;
import static com.ontos.module.query.enums.ErrorCodeConstants.RULE_SUBJECT_PATH_NOT_UNIQUE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** W3/T3.4：布尔指标编译期展开为 Rule 子查询（M2 规格 §3.1 决策 2）。 */
class RuleCompilerTest {

    private final SqlCompiler compiler = new SqlCompiler();

    @Test
    void shouldExpandBooleanMetricAsHavingSubquery() {
        CompiledQuery compiled = compiler.compile(
                TestFixtures.ruleLookupDsl(), TestFixtures.ruleManifest());
        String sql = compiled.getSql().toLowerCase();

        assertEquals(TestFixtures.BOOLEAN_METRIC_ID, compiled.getBooleanMetricId());
        assertTrue(sql.contains(" in (select"), "主体 IN 子查询: " + sql);
        assertTrue(sql.contains("group by"), "按主体分组: " + sql);
        assertTrue(sql.contains("having sum("), "HAVING 聚合判定: " + sql);
        assertTrue(sql.contains("metric.high_value_customer"), "布尔列别名: " + sql);
        assertTrue(compiled.getBindValues().stream()
                        .anyMatch(value -> String.valueOf(value).contains("50000")),
                "阈值必须参数化绑定: " + compiled.getBindValues());
    }

    @Test
    void shouldRejectDraftRuleDefensively() {
        PublishedManifest manifest = TestFixtures.ruleManifest();
        manifest.getRules().get(0).setStatus("draft");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> compiler.compile(TestFixtures.ruleLookupDsl(), manifest));
        assertEquals(RULE_NOT_PUBLISHED.getCode(), ex.getCode());
    }

    @Test
    void shouldRejectUnsupportedOperatorDefensively() {
        PublishedManifest manifest = TestFixtures.ruleManifest();
        manifest.getRules().get(0).setExpression(Map.of(
                "metricId", TestFixtures.RULE_METRIC_ID, "operator", "IN", "threshold", 50000));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> compiler.compile(TestFixtures.ruleLookupDsl(), manifest));
        assertEquals(RULE_EXPRESSION_INVALID.getCode(), ex.getCode());
    }

    @Test
    void shouldRejectAmbiguousSubjectPathDefensively() {
        PublishedManifest manifest = TestFixtures.ruleManifest();
        OntologySnapshot.Relation first = manifest.getOntology().getRelations().get(0);
        OntologySnapshot.Relation second = new OntologySnapshot.Relation();
        second.setId("r.anotherCustomerOrder");
        second.setFrom(first.getFrom());
        second.setTo(first.getTo());
        second.setFromColumn(first.getFromColumn());
        second.setToColumn(first.getToColumn());
        second.setCardinality("1:N");
        second.setQueryable(true);
        manifest.getOntology().setRelations(List.of(first, second));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> compiler.compile(TestFixtures.ruleLookupDsl(), manifest));
        assertEquals(RULE_SUBJECT_PATH_NOT_UNIQUE.getCode(), ex.getCode());
    }

}

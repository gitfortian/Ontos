package com.ontos.module.query.core.compiler;

import java.util.List;

import com.ontos.framework.common.exception.ServiceException;
import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.query.core.TestFixtures;
import com.ontos.module.query.core.dsl.QueryDsl;
import org.junit.jupiter.api.Test;

import static com.ontos.module.query.enums.ErrorCodeConstants.FUNNEL_EVENT_MAPPING_MISSING;
import static com.ontos.module.query.enums.ErrorCodeConstants.FUNNEL_EVENT_NOT_FOUND;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** W3/T3.3a：funnel 步进 CTE 编译（analysis-mode 规格 v1 §1.3、ADR-0012）。 */
class FunnelCompilerTest {

    private final SqlCompiler compiler = new SqlCompiler();

    @Test
    void shouldCompileSteppedCteWithPreviousStepDenominator() {
        CompiledQuery compiled = compiler.compile(TestFixtures.funnelDsl(), TestFixtures.funnelManifest());
        String sql = compiled.getSql().toLowerCase();

        assertEquals("FUNNEL", compiled.getMode());
        assertTrue(sql.startsWith("with \"cohort\" as"), "首步 cohort 必须是 CTE: " + sql);
        assertTrue(sql.contains("\"step_2\""), "第二步必须展开为独立 CTE: " + sql);
        assertTrue(sql.contains("count(distinct"), "按主体去重计数: " + sql);
        assertTrue(sql.contains("conversion_2"), "必须产出转化率列: " + sql);
        assertTrue(sql.contains("nullif"), "分母为零输出 null: " + sql);
        assertTrue(sql.contains("nullif(\"q\".\"step_1\", 0)"),
                "conversion_2 的分母必须是上一步 step_1，不能误用当前步: " + sql);
        assertTrue(sql.contains("cast(\"q\".\"step_2\" as numeric) / nullif"),
                "分子必须是当前步: " + sql);
        assertTrue(sql.contains("7 days"), "stepWindow 转 interval: " + sql);
        assertTrue(compiled.getBindValues().contains("ORDER_PLACED"), "事件类型值来自清单映射");
        assertTrue(compiled.getBindValues().contains("REFUND"), "事件类型值来自清单映射");
    }

    @Test
    void shouldRejectMissingEventMappingDefensively() {
        PublishedManifest manifest = TestFixtures.funnelManifest();
        manifest.getOntology().getEvents().get(0).setEventTypeProperty(null);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> compiler.compile(TestFixtures.funnelDsl(), manifest));
        assertEquals(FUNNEL_EVENT_MAPPING_MISSING.getCode(), ex.getCode());
    }

    @Test
    void shouldRejectStepWithoutEventDefinition() {
        QueryDsl dsl = TestFixtures.funnelDsl();
        dsl.getAnalysisParams().setSteps(List.of("ORDER_PLACED", "NOT_PUBLISHED"));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> compiler.compile(dsl, TestFixtures.funnelManifest()));
        assertEquals(FUNNEL_EVENT_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void shouldApplyGlobalFiltersToCohortAndEveryStep() {
        // M6/T1.5：全局过滤必须在 cohort 与每个步进 CTE 同时生效——
        // 只过滤 cohort 会把「被过滤主体的后续行为」算回转化
        QueryDsl dsl = TestFixtures.funnelDsl();
        QueryDsl.Filter filter = new QueryDsl.Filter();
        filter.setFieldId(TestFixtures.CUSTOMER_TIER_DIMENSION);
        filter.setOperator(QueryDsl.Operator.EQ);
        filter.setValues(List.of("gold"));
        dsl.setFilters(List.of(filter));

        CompiledQuery compiled = compiler.compile(dsl, TestFixtures.funnelManifest());
        String sql = compiled.getSql().toLowerCase();

        assertEquals(2, occurrences(sql, "\"customer\".\"tier\" = ?"),
                "cohort 与 step_2 各出现一次过滤条件（维度投影列不计）: " + sql);
        assertTrue(compiled.getBindValues().contains("gold"), "过滤值参数化进绑定");
        assertTrue(sql.contains("join \"customer\""), "过滤主体维度必须引入主体表: " + sql);
    }

    @Test
    void shouldJoinSubjectTableWhenOnlyFiltersPresent() {
        QueryDsl dsl = TestFixtures.funnelDsl();
        dsl.setDimensions(List.of());
        QueryDsl.Filter filter = new QueryDsl.Filter();
        filter.setFieldId(TestFixtures.CUSTOMER_TIER_DIMENSION);
        filter.setOperator(QueryDsl.Operator.EQ);
        filter.setValues(List.of("normal"));
        dsl.setFilters(List.of(filter));

        CompiledQuery compiled = compiler.compile(dsl, TestFixtures.funnelManifest());
        String sql = compiled.getSql().toLowerCase();

        assertTrue(sql.contains("join \"customer\""),
                "无分组维度但存在全局过滤时仍须 JOIN 主体表: " + sql);
        assertEquals(2, occurrences(sql, "\"customer\".\"tier\""));
    }

    private static int occurrences(String text, String token) {
        int count = 0;
        int index = text.indexOf(token);
        while (index >= 0) {
            count++;
            index = text.indexOf(token, index + token.length());
        }
        return count;
    }

}

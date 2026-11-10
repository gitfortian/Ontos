package com.ontos.module.query.core.compiler;

import java.util.ArrayList;
import java.util.List;

import com.ontos.framework.common.exception.ServiceException;
import com.ontos.module.ontology.core.OntologySnapshot;
import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.query.core.TestFixtures;
import com.ontos.module.query.core.dsl.QueryDsl;
import org.junit.jupiter.api.Test;

import static com.ontos.module.query.enums.ErrorCodeConstants.ATTRIBUTION_EVENT_NOT_FOUND;
import static com.ontos.module.query.enums.ErrorCodeConstants.DSL_INVALID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M6/T1.4：attribution 末次触点构造器（模式规格 v1.1 §2.2）。
 *
 * <p>确定性断言：回溯窗口左闭右开、`(occurred_at, dedup_key)` 双降序取一、
 * UNATTRIBUTED 显式桶、指标经清单关系 Join、无联系人转化的匿名排除；Join 列不得来自命名推断。
 */
class AttributionCompilerTest {

    private final SqlCompiler compiler = new SqlCompiler();

    @Test
    void shouldCompileLastTouchAttribution() {
        CompiledQuery compiled = compiler.compile(TestFixtures.attributionDsl(),
                TestFixtures.attributionManifest());
        String sql = compiled.getSql().toLowerCase();

        assertEquals("ATTRIBUTION", compiled.getMode());
        assertTrue(sql.startsWith("with \"conversions\" as"), "转化 CTE 先行: " + sql);
        assertTrue(sql.contains("\"attributed\" as"), "渠道映射 CTE: " + sql);
        assertTrue(sql.contains("\"facts\" as"), "事实 CTE: " + sql);
        assertTrue(sql.contains("order by \"t\".\"occurred_at\" desc, \"t\".\"dedup_key\" desc"),
                "last_touch 全序显式（时间 + 去重键双降序）: " + sql);
        assertTrue(sql.contains("cast('30 days' as interval)"), "回溯窗口来自封闭枚举: " + sql);
        assertTrue(sql.contains("coalesce(\"f\".\"channel_name\", 'unattributed')"),
                "无触点转化进显式 UNATTRIBUTED 桶: " + sql);
        assertTrue(sql.contains("left outer join \"channel\""), "触点渠道左连 channel 主数据: " + sql);
        assertTrue(sql.contains("join \"orders\""), "指标值经清单关系 Join 订单: " + sql);
        assertTrue(sql.contains("\"attributed_metric_value\""), "归因指标值列: " + sql);
        assertTrue(sql.contains("\"converted_events\""), "转化数: " + sql);
        assertTrue(sql.contains("\"touched_events\""), "命中触点数: " + sql);
        assertTrue(sql.contains("count(distinct \"f\".\"conversion_id\")"), "转化按事件去重: " + sql);
        assertTrue(compiled.getBindValues().contains("ORDER_PLACED"), "转化事件类型值来自清单");
        assertTrue(compiled.getBindValues().contains("CHANNEL_TOUCH"), "触点事件类型值来自清单");
        assertEquals("metric.gmv", compiled.getMetricId());
    }

    @Test
    void shouldCompileWithoutChannelDimension() {
        QueryDsl dsl = TestFixtures.attributionDsl();
        dsl.setDimensions(List.of());
        CompiledQuery compiled = compiler.compile(dsl, TestFixtures.attributionManifest());
        String sql = compiled.getSql().toLowerCase();

        assertTrue(!sql.contains("unattributed"),
                "不含渠道维度时不产出 UNATTRIBUTED 分组行: " + sql);
    }

    @Test
    void shouldRejectWhenMetricEventRelationMissing() {
        PublishedManifest manifest = TestFixtures.attributionManifest();
        manifest.getOntology().setRelations(List.of());

        ServiceException ex = assertThrows(ServiceException.class,
                () -> compiler.compile(TestFixtures.attributionDsl(), manifest));
        assertEquals(DSL_INVALID.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("关联关系"), ex.getMessage());
    }

    @Test
    void shouldRejectAmbiguousMetricEventRelation() {
        PublishedManifest manifest = TestFixtures.attributionManifest();
        List<OntologySnapshot.Relation> relations =
                new ArrayList<>(manifest.getOntology().getRelations());
        OntologySnapshot.Relation reverse = new OntologySnapshot.Relation();
        reverse.setId("ontology.Event.recordsOrder");
        reverse.setFrom("concept.event");
        reverse.setTo("concept.order");
        reverse.setFromColumn("event.order_id");
        reverse.setToColumn("orders.id");
        reverse.setQueryable(true);
        relations.add(reverse);
        manifest.getOntology().setRelations(relations);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> compiler.compile(TestFixtures.attributionDsl(), manifest));
        assertEquals(DSL_INVALID.getCode(), ex.getCode());
    }

    @Test
    void shouldRejectEventWithoutChannelPropertyDefensively() {
        PublishedManifest manifest = TestFixtures.attributionManifest();
        manifest.getOntology().getEvents().get(2).setChannelPropertyId(null);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> compiler.compile(TestFixtures.attributionDsl(), manifest));
        assertEquals(ATTRIBUTION_EVENT_NOT_FOUND.getCode(), ex.getCode());
    }

}

package com.ontos.module.query.controller.admin;

import java.util.List;
import java.util.Set;

import com.ontos.framework.web.core.context.RequestContext;
import com.ontos.module.query.controller.admin.vo.QueryPlanRespVO;
import com.ontos.module.query.core.dsl.QueryDsl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * plan 回放的呈现层脱敏（W5）：物理 SQL/绑定值/Join 物理列仅开发态或 debug 角色可见；
 * 业务关系路径对所有人保留。
 */
class QueryControllerMaskTest {

    private static QueryPlanRespVO planWithPhysicalFields() {
        QueryPlanRespVO vo = new QueryPlanRespVO();
        vo.setSql("select sum(\"orders\".\"amount\") from \"orders\"");
        vo.setBindValues(List.of("2026-10-09T16:00:00.000Z", 500));
        QueryDsl.ResolvedJoin join = new QueryDsl.ResolvedJoin();
        join.setRelationId("Order.attributedToChannel");
        join.setDirection("LEFT");
        join.setCardinality("many_to_one");
        join.setTable("channel");
        join.setLeftColumn("orders.channel_id");
        join.setRightColumn("channel.id");
        vo.setJoins(List.of(join));
        return vo;
    }

    @Test
    void keepsPhysicalFieldsForDevModeAndDebugRole() {
        RequestContext dev = new RequestContext("t", "u", "s", 0, null, "tr", Set.of(), true, null);
        QueryPlanRespVO masked = QueryController.maskForPresentation(planWithPhysicalFields(), dev);
        assertEquals("select sum(\"orders\".\"amount\") from \"orders\"", masked.getSql());

        RequestContext debug = new RequestContext("t", "u", "s", 0, null, "tr", Set.of("debug"),
                false, null);
        assertEquals(500, QueryController
                .maskForPresentation(planWithPhysicalFields(), debug).getBindValues().get(1));
    }

    @Test
    void stripsPhysicalFieldsForRegularUserButKeepsBusinessPath() {
        RequestContext user = new RequestContext("t", "u", "s", 0, null, "tr", Set.of(), false, null);

        QueryPlanRespVO masked = QueryController.maskForPresentation(planWithPhysicalFields(), user);

        assertNull(masked.getSql());
        assertNull(masked.getBindValues());
        assertEquals(1, masked.getJoins().size());
        QueryDsl.ResolvedJoin join = masked.getJoins().get(0);
        assertEquals("Order.attributedToChannel", join.getRelationId());
        assertEquals("LEFT", join.getDirection());
        assertEquals("many_to_one", join.getCardinality());
        assertNull(join.getTable(), "物理表名不出接口");
        assertNull(join.getLeftColumn(), "物理列名不出接口");
        assertNull(join.getRightColumn(), "物理列名不出接口");
    }

    @Test
    void keepsNullJoinsAsNullAndReturnsSameInstanceWhenAllowed() {
        RequestContext user = new RequestContext("t", "u", "s", 0, null, "tr", Set.of(), false, null);
        QueryPlanRespVO noJoins = new QueryPlanRespVO();
        noJoins.setSql("select 1");
        assertNull(QueryController.maskForPresentation(noJoins, user).getJoins());

        RequestContext dev = new RequestContext("t", "u", "s", 0, null, "tr", Set.of(), true, null);
        QueryPlanRespVO vo = planWithPhysicalFields();
        assertSame(vo, QueryController.maskForPresentation(vo, dev));
    }
}

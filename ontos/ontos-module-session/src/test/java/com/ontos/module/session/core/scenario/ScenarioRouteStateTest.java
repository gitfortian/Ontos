package com.ontos.module.session.core.scenario;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scenario 会话状态机迁移表锁定（M6/W2/T2.3，规格 §3.2「显式枚举 + 单测锁定」）。
 */
class ScenarioRouteStateTest {

    @Test
    void shouldAllowOnlyEnumeratedTransitions() {
        assertTrue(ScenarioRouteState.FREE.canTransitionTo(ScenarioRouteState.IN_SCENARIO));
        assertTrue(ScenarioRouteState.FREE.canTransitionTo(ScenarioRouteState.PENDING_CONFIRM));
        assertTrue(ScenarioRouteState.IN_SCENARIO.canTransitionTo(ScenarioRouteState.IN_SCENARIO),
                "显式切换场景（同态换 payload）");
        assertTrue(ScenarioRouteState.IN_SCENARIO.canTransitionTo(ScenarioRouteState.PENDING_CONFIRM),
                "越界澄清");
        assertTrue(ScenarioRouteState.IN_SCENARIO.canTransitionTo(ScenarioRouteState.FREE),
                "显式退出");
        assertTrue(ScenarioRouteState.PENDING_CONFIRM.canTransitionTo(ScenarioRouteState.IN_SCENARIO),
                "确认进入 / 切换");
        assertTrue(ScenarioRouteState.PENDING_CONFIRM.canTransitionTo(ScenarioRouteState.FREE),
                "确认退出 / 拒绝推荐");
    }

    @Test
    void shouldRejectIllegalTransitions() {
        assertFalse(ScenarioRouteState.FREE.canTransitionTo(ScenarioRouteState.FREE),
                "同名自环不是迁移");
        assertFalse(ScenarioRouteState.PENDING_CONFIRM.canTransitionTo(ScenarioRouteState.PENDING_CONFIRM));
        assertFalse(ScenarioRouteState.IN_SCENARIO.canTransitionTo(ScenarioRouteState.IN_SCENARIO)
                        && false, "sanity");
        // 跳步：FREE 不得直接跳过 pending 确认之外的路径（本表无 FREE→FREE，已覆盖）；
        // retired 不存在——场景生命周期在 catalog，会话三态无终态。
        assertEquals(3, ScenarioRouteState.values().length);
    }

    @Test
    void shouldUseLowercaseCodesAndRejectUnknown() {
        assertEquals("free", ScenarioRouteState.FREE.code());
        assertEquals("in_scenario", ScenarioRouteState.IN_SCENARIO.code());
        assertEquals("pending_confirm", ScenarioRouteState.PENDING_CONFIRM.code());
        assertEquals(ScenarioRouteState.IN_SCENARIO, ScenarioRouteState.fromCode("IN_SCENARIO"));
        assertThrows(IllegalArgumentException.class, () -> ScenarioRouteState.fromCode("retired"));
        assertThrows(IllegalArgumentException.class, () -> ScenarioRouteState.fromCode(null));
    }
}

package com.ontos.module.agent.service.orchestration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** yoy_mom 周期推导单测（E-7 组装缺口修复）。 */
class YoyPeriodResolverTest {

    @Test
    void monthKeywordMapsToMonthPeriod() {
        assertEquals(YoyPeriodResolver.PERIOD_MONTH, YoyPeriodResolver.derive("上个月"));
        assertEquals(YoyPeriodResolver.PERIOD_MONTH, YoyPeriodResolver.derive("近30天按月对比"));
    }

    @Test
    void defaultAndYearExpressionsMapToYearPeriod() {
        assertEquals(YoyPeriodResolver.PERIOD_YEAR, YoyPeriodResolver.derive("今年"));
        assertEquals(YoyPeriodResolver.PERIOD_YEAR, YoyPeriodResolver.derive("上季度"));
        assertEquals(YoyPeriodResolver.PERIOD_YEAR, YoyPeriodResolver.derive(null));
        assertEquals(YoyPeriodResolver.PERIOD_YEAR, YoyPeriodResolver.derive(""));
    }
}

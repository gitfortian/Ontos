package com.ontos.module.query.core.time;

import java.time.LocalDate;
import java.util.List;

import com.ontos.module.ontology.core.ManifestPayload.TimeExpressionPattern;
import com.ontos.module.query.core.time.TimeExpressionParser.ParseResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeExpressionParserTest {

    /** 与首发清单 timeExpressionPatterns 一致的模式集（2026.11.08-first）。 */
    private List<TimeExpressionPattern> patterns() {
        TimeExpressionPattern lastNDays = new TimeExpressionPattern();
        lastNDays.setPatternId("tep.last_n_days");
        lastNDays.setPattern("近 N 天");
        lastNDays.setGranularity("DAY");
        lastNDays.setOffsetUnit("DAY");
        lastNDays.setOffsetAmount(1);

        TimeExpressionPattern lastMonth = new TimeExpressionPattern();
        lastMonth.setPatternId("tep.last_month");
        lastMonth.setPattern("上个月");
        lastMonth.setGranularity("MONTH");

        TimeExpressionPattern thisMonth = new TimeExpressionPattern();
        thisMonth.setPatternId("tep.this_month");
        thisMonth.setPattern("这个月");
        thisMonth.setGranularity("MONTH");

        return List.of(lastNDays, lastMonth, thisMonth);
    }

    private final TimeExpressionParser parser = new TimeExpressionParser(patterns());

    private final LocalDate today = LocalDate.of(2026, 11, 8);

    @Test
    void shouldResolveLastNDaysInclusiveOfToday() {
        // 近 3 天 = [11-06, 11-09)，含今天（ADR-0004 2.4）；断言按上海时区
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME;
        ParseResult result = parser.parse("近 3 天", today);
        assertEquals("2026-11-06T00:00:00+08:00", result.resolved().from().atZone(java.time.ZoneId.of("Asia/Shanghai")).format(fmt));
        assertEquals("2026-11-09T00:00:00+08:00", result.resolved().to().atZone(java.time.ZoneId.of("Asia/Shanghai")).format(fmt));
        assertEquals("tep.last_n_days", result.resolved().patternId());
        assertEquals(TimeExpressionParser.PARSER_VERSION, result.resolved().version());
    }

    @Test
    void shouldResolveLastMonthAsFullPreviousMonth() {
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME;
        ParseResult result = parser.parse("上个月", today);
        // 11-08 → 上个月 = [2026-10-01, 2026-11-01)，断言按上海时区
        assertEquals("2026-10-01T00:00:00+08:00", result.resolved().from().atZone(java.time.ZoneId.of("Asia/Shanghai")).format(fmt));
        assertEquals("2026-11-01T00:00:00+08:00", result.resolved().to().atZone(java.time.ZoneId.of("Asia/Shanghai")).format(fmt));
        assertEquals("tep.last_month", result.resolved().patternId());
    }

    @Test
    void shouldResolveThisMonth() {
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME;
        ParseResult result = parser.parse("这个月", today);
        assertEquals("2026-11-01T00:00:00+08:00", result.resolved().from().atZone(java.time.ZoneId.of("Asia/Shanghai")).format(fmt));
        assertEquals("2026-12-01T00:00:00+08:00", result.resolved().to().atZone(java.time.ZoneId.of("Asia/Shanghai")).format(fmt));
    }

    @Test
    void shouldResolveLastMonthAcrossYearBoundary() {
        // 跨年边界：1 月的「上个月」= 去年 12 月
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME;
        ParseResult result = parser.parse("上个月", LocalDate.of(2026, 1, 15));
        assertEquals("2025-12-01T00:00:00+08:00", result.resolved().from().atZone(java.time.ZoneId.of("Asia/Shanghai")).format(fmt));
        assertEquals("2026-01-01T00:00:00+08:00", result.resolved().to().atZone(java.time.ZoneId.of("Asia/Shanghai")).format(fmt));
    }

    @Test
    void shouldFailOnUnmatchedExpression() {
        ParseResult result = parser.parse("上季度", today);
        assertNull(result.resolved());
        assertTrue(result.failReason().contains("未命中"));
    }

    @Test
    void shouldFailOnBlankExpression() {
        assertNull(parser.parse(null, today).resolved());
        assertNull(parser.parse("  ", today).resolved());
    }

    @Test
    void shouldFailOnNonPositiveN() {
        assertNull(parser.parse("近 0 天", today).resolved());
        assertNull(parser.parse("近 -1 天", today).resolved());
    }

    @Test
    void shouldFailWhenNoPatternsPublished() {
        TimeExpressionParser empty = new TimeExpressionParser(List.of());
        assertNull(empty.parse("上个月", today).resolved());
    }

    @Test
    void shouldTreatOverflowingNumberAsUnmatched() {
        // 深度审核 D51：Integer.parseInt 超长数字原先抛 NumberFormatException（500）；
        // 解析器应判定「未命中」，由调用方给出 TIME_PARSE_FAILED 业务错误
        ParseResult result = parser.parse("近 99999999999999999999 天", today);
        assertNull(result.resolved());
        assertTrue(result.failReason().contains("未命中"));
        assertNull(parser.parse("近 100001 天", today).resolved(), "超过上限同样按未命中处理");
    }

}

package com.ontos.module.query.core.time;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import com.ontos.framework.common.exception.ServiceException;
import com.ontos.module.ontology.core.ManifestPayload.TimeExpressionPattern;
import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.query.core.TestFixtures;
import com.ontos.module.query.core.dsl.QueryDsl;
import org.junit.jupiter.api.Test;

import static com.ontos.module.query.enums.ErrorCodeConstants.ATTRIBUTION_EVENT_NOT_FOUND;
import static com.ontos.module.query.enums.ErrorCodeConstants.DSL_INVALID;
import static com.ontos.module.query.enums.ErrorCodeConstants.TIME_PARSE_FAILED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * M6/T1.4：attribution 转化窗解析（模式规格 v1.1 §2.1）。
 *
 * <p>硬边界：客户端不得占用 timeRange（服务端按转化事件 timeProperty 回填）；
 * 转化窗必须命中已发布模式；未知转化事件即结构化拒绝。
 */
class AttributionTimeResolverTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Clock CLOCK = Clock.fixed(
            LocalDate.of(2026, 11, 10).atStartOfDay(ZONE).toInstant(), ZONE);

    private final TimeWindowResolver resolver = new TimeWindowResolver(CLOCK);

    private static PublishedManifest manifest() {
        TimeExpressionPattern lastNDays = new TimeExpressionPattern();
        lastNDays.setPatternId("tep.last_n_days");
        lastNDays.setPattern("近 N 天");
        lastNDays.setGranularity("DAY");
        PublishedManifest manifest = TestFixtures.attributionManifest();
        manifest.setTimeExpressionPatterns(List.of(lastNDays));
        return manifest;
    }

    @Test
    void shouldResolveWindowAndBackfillConversionTimeProperty() {
        QueryDsl dsl = TestFixtures.attributionDsl();
        dsl.setTimeRange(null);
        dsl.setTimePatternId(null);
        dsl.setTimeParserVersion(null);
        dsl.setTimeExpressionRaw("近 14 天");

        resolver.resolve(dsl, manifest());

        QueryDsl.TimeRange range = dsl.getTimeRange();
        assertNotNull(range);
        assertEquals(QueryDsl.TimeRange.SOURCE_EXPRESSION, range.getSource());
        assertEquals(TestFixtures.EVENT_TIME_PROPERTY, range.getField());
        assertEquals(Instant.parse("2026-10-27T16:00:00Z"), range.getFrom());
        assertEquals(Instant.parse("2026-11-10T16:00:00Z"), range.getTo());
        assertEquals("tep.last_n_days", dsl.getTimePatternId());
    }

    @Test
    void shouldRejectClientSubmittedTimeRange() {
        QueryDsl dsl = TestFixtures.attributionDsl();
        dsl.setTimeExpressionRaw("近 14 天");
        QueryDsl.TimeRange forged = new QueryDsl.TimeRange();
        forged.setField("dim.channel");
        dsl.setTimeRange(forged);

        ServiceException ex = assertThrows(ServiceException.class, () -> resolver.resolve(dsl, manifest()));
        assertEquals(DSL_INVALID.getCode(), ex.getCode());
    }

    @Test
    void shouldRejectUnknownConversionEvent() {
        QueryDsl dsl = TestFixtures.attributionDsl();
        dsl.setTimeRange(null);
        dsl.getAnalysisParams().setConversionEvent("NOT_PUBLISHED");

        ServiceException ex = assertThrows(ServiceException.class, () -> resolver.resolve(dsl, manifest()));
        assertEquals(ATTRIBUTION_EVENT_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void shouldRejectUnpublishedTimePattern() {
        QueryDsl dsl = TestFixtures.attributionDsl();
        dsl.setTimeRange(null);
        dsl.setTimeExpressionRaw("近 8 周");

        ServiceException ex = assertThrows(ServiceException.class, () -> resolver.resolve(dsl, manifest()));
        assertEquals(TIME_PARSE_FAILED.getCode(), ex.getCode());
    }

}

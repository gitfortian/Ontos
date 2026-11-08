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

import static com.ontos.module.query.enums.ErrorCodeConstants.DSL_INVALID;
import static com.ontos.module.query.enums.ErrorCodeConstants.TIME_PARSE_FAILED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** M2/T2.3 接线：相对时间 / 显式日期二通道与服务端解析版本回填。 */
class TimeWindowResolverTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Clock CLOCK = Clock.fixed(
            LocalDate.of(2026, 11, 8).atStartOfDay(ZONE).toInstant(), ZONE);

    private final TimeWindowResolver resolver = new TimeWindowResolver(CLOCK);

    private static PublishedManifest manifest() {
        TimeExpressionPattern lastNDays = new TimeExpressionPattern();
        lastNDays.setPatternId("tep.last_n_days");
        lastNDays.setPattern("近 N 天");
        lastNDays.setGranularity("DAY");

        TimeExpressionPattern lastMonth = new TimeExpressionPattern();
        lastMonth.setPatternId("tep.last_month");
        lastMonth.setPattern("上个月");
        lastMonth.setGranularity("MONTH");

        PublishedManifest manifest = TestFixtures.manifest();
        manifest.setTimeExpressionPatterns(List.of(lastNDays, lastMonth));
        return manifest;
    }

    private static QueryDsl dslWithTimeField() {
        QueryDsl dsl = TestFixtures.query();
        QueryDsl.TimeRange range = new QueryDsl.TimeRange();
        range.setField(TestFixtures.TIME_DIMENSION_ID);
        dsl.setTimeRange(range);
        return dsl;
    }

    @Test
    void shouldResolveRelativeExpressionIntoServerOwnedTimeRange() {
        QueryDsl dsl = dslWithTimeField();
        dsl.setTimeExpressionRaw("近 3 天");

        resolver.resolve(dsl, manifest());

        QueryDsl.TimeRange range = dsl.getTimeRange();
        assertEquals(QueryDsl.TimeRange.SOURCE_EXPRESSION, range.getSource());
        assertEquals(TestFixtures.TIME_DIMENSION_ID, range.getField());
        assertEquals(Instant.parse("2026-11-05T16:00:00Z"), range.getFrom());
        assertEquals(Instant.parse("2026-11-08T16:00:00Z"), range.getTo());
        assertEquals("tep.last_n_days", dsl.getTimePatternId());
        assertEquals(TimeExpressionParser.PARSER_VERSION, dsl.getTimeParserVersion());
    }

    @Test
    void shouldResolveMonthExpression() {
        QueryDsl dsl = dslWithTimeField();
        dsl.setTimeExpressionRaw("上个月");

        resolver.resolve(dsl, manifest());

        assertEquals(Instant.parse("2026-09-30T16:00:00Z"), dsl.getTimeRange().getFrom());
        assertEquals(Instant.parse("2026-10-31T16:00:00Z"), dsl.getTimeRange().getTo());
        assertEquals("tep.last_month", dsl.getTimePatternId());
    }

    @Test
    void shouldRejectUnmatchedPattern() {
        QueryDsl dsl = dslWithTimeField();
        dsl.setTimeExpressionRaw("上季度");

        ServiceException ex = assertThrows(ServiceException.class, () -> resolver.resolve(dsl, manifest()));
        assertEquals(TIME_PARSE_FAILED.getCode(), ex.getCode());
    }

    @Test
    void shouldRejectMissingTimeField() {
        QueryDsl dsl = TestFixtures.query();
        dsl.setTimeExpressionRaw("近 3 天");

        ServiceException ex = assertThrows(ServiceException.class, () -> resolver.resolve(dsl, manifest()));
        assertEquals(TIME_PARSE_FAILED.getCode(), ex.getCode());
    }

    @Test
    void shouldPassThroughExplicitRangeWithoutParsing() {
        QueryDsl dsl = TestFixtures.query();
        QueryDsl.TimeRange explicit = new QueryDsl.TimeRange();
        explicit.setField(TestFixtures.TIME_DIMENSION_ID);
        explicit.setFrom(Instant.parse("2026-01-01T00:00:00Z"));
        explicit.setTo(Instant.parse("2026-02-01T00:00:00Z"));
        dsl.setExplicitRange(explicit);

        resolver.resolve(dsl, manifest());

        assertSame(explicit, dsl.getTimeRange());
        assertEquals(QueryDsl.TimeRange.SOURCE_EXPLICIT, dsl.getTimeRange().getSource());
        assertNull(dsl.getTimePatternId());
        assertNull(dsl.getTimeParserVersion());
    }

    @Test
    void shouldRejectBothTimeChannels() {
        QueryDsl dsl = dslWithTimeField();
        dsl.setTimeExpressionRaw("近 3 天");
        dsl.setExplicitRange(new QueryDsl.TimeRange());

        ServiceException ex = assertThrows(ServiceException.class, () -> resolver.resolve(dsl, manifest()));
        assertEquals(DSL_INVALID.getCode(), ex.getCode());
    }

    @Test
    void shouldClearClientForgedParserMetadata() {
        QueryDsl dsl = dslWithTimeField();
        dsl.setTimePatternId("forged-pattern");
        dsl.setTimeParserVersion("forged-version");

        resolver.resolve(dsl, manifest());

        assertNull(dsl.getTimePatternId());
        assertNull(dsl.getTimeParserVersion());
        assertNull(dsl.getTimeRange().getSource());
    }

}

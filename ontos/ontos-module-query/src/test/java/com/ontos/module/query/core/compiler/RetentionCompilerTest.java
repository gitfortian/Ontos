package com.ontos.module.query.core.compiler;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import com.ontos.framework.common.exception.ServiceException;
import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.query.core.TestFixtures;
import com.ontos.module.query.core.dsl.QueryDsl;
import org.junit.jupiter.api.Test;

import static com.ontos.module.query.enums.ErrorCodeConstants.DSL_INVALID;
import static com.ontos.module.query.enums.ErrorCodeConstants.RETENTION_EVENT_NOT_FOUND;
import static com.ontos.module.query.enums.ErrorCodeConstants.RETENTION_PERIOD_INCOMPLETE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M6/T1.3：retention 留存构造器（模式规格 v1.1 §1.2）。
 *
 * <p>确定性断言：周期起点（周一 / 每月 1 日）、未完整周期排除、留存率无除零路径、
 * cohort 与 return 事件映射一致；观察锚点固定时钟，结果不随真实日期漂移。
 */
class RetentionCompilerTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 2026-11-10 周二：本周（11-09 起）未完整，最后完整周 = 11-02 起。 */
    private static final Clock CLOCK = Clock.fixed(
            LocalDate.of(2026, 11, 10).atStartOfDay(ZONE).toInstant(), ZONE);

    private final SqlCompiler compiler = new SqlCompiler(CLOCK);

    @Test
    void shouldCompileCohortRetentionCte() {
        CompiledQuery compiled = compiler.compile(TestFixtures.retentionDsl(), TestFixtures.retentionManifest());
        String sql = compiled.getSql().toLowerCase();

        assertEquals("RETENTION", compiled.getMode());
        assertTrue(sql.startsWith("with \"cohort_first\" as"), "首次事件必须是 CTE: " + sql);
        assertTrue(sql.contains("\"cohort\" as"), "cohort 周期骨架 CTE: " + sql);
        assertTrue(sql.contains("\"retained\" as"), "留存判定 CTE: " + sql);
        assertTrue(sql.contains("date_trunc('week'"), "自然周周期: " + sql);
        assertTrue(sql.contains("generate_series"), "观察期序号展开: " + sql);
        assertTrue(sql.contains("filter (where"), "留存计数按 FILTER 聚合: " + sql);
        assertTrue(sql.contains("retention_rate"), "留存率列: " + sql);
        assertTrue(sql.contains("nullif"), "分母为零无除零路径: " + sql);
        assertTrue(sql.contains("count(distinct"), "主体去重计数: " + sql);
        assertTrue(compiled.getBindValues().contains("ORDER_PLACED"), "事件类型值来自清单映射");
        assertTrue(compiled.getBindValues().contains(java.sql.Timestamp.valueOf("2026-11-10 00:00:00")),
                "完整周期锚点进绑定值（上海今日）: " + compiled.getBindValues());
        assertEquals(TestFixtures.CUSTOMER_TIER_DIMENSION, compiled.getDimensions().get(0));
    }

    @Test
    void shouldCompileMonthlyPeriod() {
        QueryDsl dsl = TestFixtures.retentionDsl();
        dsl.getAnalysisParams().setPeriodUnit("MONTH");
        dsl.getAnalysisParams().setPeriods(6);

        CompiledQuery compiled = compiler.compile(dsl, TestFixtures.retentionManifest());

        assertTrue(compiled.getSql().toLowerCase().contains("date_trunc('month'"),
                "MONTH 单位: " + compiled.getSql());
    }

    @Test
    void shouldRejectWindowWithoutCompleteCohortPeriod() {
        // 上海 11-09 所在周尚未结束（最后完整周从 11-02 起）→ 窗内没有任何完整 cohort 期
        QueryDsl dsl = TestFixtures.retentionDsl();
        dsl.getTimeRange().setFrom(Instant.parse("2026-11-09T00:00:00Z"));
        dsl.getTimeRange().setTo(Instant.parse("2026-11-10T00:00:00Z"));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> compiler.compile(dsl, TestFixtures.retentionManifest()));
        assertEquals(RETENTION_PERIOD_INCOMPLETE.getCode(), ex.getCode());
    }

    @Test
    void shouldAcceptWindowEndingInCompletePeriod() {
        // 上海 11-02 起的一周已在 11-09 结束 → 完整期存在，允许编译
        QueryDsl dsl = TestFixtures.retentionDsl();
        dsl.getTimeRange().setFrom(Instant.parse("2026-11-02T00:00:00Z"));
        dsl.getTimeRange().setTo(Instant.parse("2026-11-09T00:00:00Z"));

        CompiledQuery compiled = compiler.compile(dsl, TestFixtures.retentionManifest());
        assertEquals("RETENTION", compiled.getMode());
    }

    @Test
    void shouldRejectInconsistentEventMapping() {
        QueryDsl dsl = TestFixtures.retentionDsl();
        // cohort=ORDER_PLACED / return=REFUND：两条事件的物理时间列被改成不同属性，
        // 同一 CTE 无法同时承载，必须拒绝而不是按首事件解读（静默错数）
        dsl.getAnalysisParams().setReturnEvent("REFUND");
        PublishedManifest manifest = TestFixtures.retentionManifest();
        manifest.getOntology().getEvents().get(1)
                .setTimeProperty(TestFixtures.EVENT_TYPE_PROPERTY);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> compiler.compile(dsl, manifest));
        assertEquals(DSL_INVALID.getCode(), ex.getCode());
    }

    @Test
    void shouldRejectUnknownReturnEventDefensively() {
        QueryDsl dsl = TestFixtures.retentionDsl();
        dsl.getAnalysisParams().setReturnEvent("NOT_PUBLISHED");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> compiler.compile(dsl, TestFixtures.retentionManifest()));
        assertEquals(RETENTION_EVENT_NOT_FOUND.getCode(), ex.getCode());
    }

}

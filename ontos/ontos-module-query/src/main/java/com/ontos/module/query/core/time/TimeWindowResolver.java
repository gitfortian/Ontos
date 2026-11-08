package com.ontos.module.query.core.time;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.query.core.dsl.QueryDsl;
import com.ontos.module.query.core.time.TimeExpressionParser.ParseResult;
import org.springframework.stereotype.Component;

import static com.ontos.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.ontos.module.query.enums.ErrorCodeConstants.DSL_INVALID;
import static com.ontos.module.query.enums.ErrorCodeConstants.FUNNEL_TIME_WINDOW_MISSING;
import static com.ontos.module.query.enums.ErrorCodeConstants.TIME_PARSE_FAILED;

/**
 * 时间窗接线（M2/T2.3 + W2.5）：把 DSL 的时间表达换算成服务端产出的 timeRange。
 *
 * <p>三条硬性边界：
 * 1. 相对时间只走 timeExpressionRaw → 清单 pattern → 解析器，未命中即拒绝，禁止猜测；
 * 2. 绝对日期只走 explicitRange（用户显式输入），由本类标记 source=EXPLICIT；
 * 3. 服务端回填 timePatternId / timeParserVersion 并随 dsl_json 进审计，
 *    DslValidator 要求 source=EXPRESSION 时这两个字段必须存在，客户端无法伪造解析产物。
 *
 * <p>timeRange.field 是时间维度资产 ID（不是物理列名），由调用方随 DSL 提供；
 * 缺失时拒绝，避免服务端替用户猜时间字段。
 */
@Component
public class TimeWindowResolver {

    private final Clock clock;

    public TimeWindowResolver() {
        this(Clock.system(ZoneId.of(TimeExpressionParser.ZONE)));
    }

    /** 显式注入时钟，便于跨月/跨年边界的确定性单测。 */
    public TimeWindowResolver(Clock clock) {
        this.clock = clock;
    }

    public void resolve(QueryDsl dsl, PublishedManifest manifest) {
        // 先清空服务端回填字段：无论客户端是否夹带，都以本次解析结果为准
        dsl.setTimePatternId(null);
        dsl.setTimeParserVersion(null);

        // W3/T3.3a：funnel 的时间窗在 analysisParams.cohortWindow，不在 timeRange
        if (dsl.getMode() == QueryDsl.AnalysisMode.FUNNEL) {
            resolveCohort(dsl, manifest);
            return;
        }

        String raw = dsl.getTimeExpressionRaw();
        boolean hasRaw = raw != null && !raw.isBlank();
        boolean hasExplicit = dsl.getExplicitRange() != null;
        if (hasRaw && hasExplicit) {
            throw exception(DSL_INVALID, "相对时间与用户显式日期不可同时存在");
        }

        if (hasExplicit) {
            QueryDsl.TimeRange explicit = dsl.getExplicitRange();
            explicit.setSource(QueryDsl.TimeRange.SOURCE_EXPLICIT);
            dsl.setTimeRange(explicit);
            return;
        }
        if (!hasRaw) {
            // 没有时间表达：保留客户端可能提交的 timeRange，交给 DslValidator 按来源标记 fail-closed
            return;
        }

        String field = dsl.getTimeRange() == null ? null : dsl.getTimeRange().getField();
        if (field == null || field.isBlank()) {
            throw exception(TIME_PARSE_FAILED, "缺少 timeRange.field（时间维度资产 ID）");
        }
        ParseResult result = new TimeExpressionParser(manifest.getTimeExpressionPatterns())
                .parse(raw, LocalDate.now(clock));
        if (result.resolved() == null) {
            throw exception(TIME_PARSE_FAILED, result.failReason());
        }

        QueryDsl.TimeRange resolved = new QueryDsl.TimeRange();
        resolved.setField(field);
        resolved.setFrom(result.resolved().from());
        resolved.setTo(result.resolved().to());
        resolved.setSource(QueryDsl.TimeRange.SOURCE_EXPRESSION);
        dsl.setTimeRange(resolved);
        dsl.setTimePatternId(result.resolved().patternId());
        dsl.setTimeParserVersion(result.resolved().version());
    }

    /**
     * funnel cohort 窗口解析（analysis-mode 规格 v1 §1.1）：只接受相对时间表达，
     * 结果回填 CohortWindow 并随 dsl_json 进审计；未命中即拒绝，禁止猜测。
     */
    private void resolveCohort(QueryDsl dsl, PublishedManifest manifest) {
        QueryDsl.AnalysisParams params = dsl.getAnalysisParams();
        QueryDsl.CohortWindow window = params == null ? null : params.getCohortWindow();
        if (window == null || window.getTimeExpressionRaw() == null
                || window.getTimeExpressionRaw().isBlank()) {
            throw exception(FUNNEL_TIME_WINDOW_MISSING, "cohortWindow.timeExpressionRaw");
        }
        ParseResult result = new TimeExpressionParser(manifest.getTimeExpressionPatterns())
                .parse(window.getTimeExpressionRaw(), LocalDate.now(clock));
        if (result.resolved() == null) {
            throw exception(TIME_PARSE_FAILED, result.failReason());
        }
        window.setFrom(result.resolved().from());
        window.setTo(result.resolved().to());
        window.setSource(QueryDsl.TimeRange.SOURCE_EXPRESSION);
        window.setPatternId(result.resolved().patternId());
        window.setParserVersion(result.resolved().version());
    }

}

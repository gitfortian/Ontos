package com.ontos.module.query.core.dsl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.ontology.core.SemanticManifest;
import com.ontos.module.query.core.QueryProperties;
import org.springframework.stereotype.Component;

import static com.ontos.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.ontos.framework.common.exception.util.ServiceExceptionUtil.invalidParamException;
import static com.ontos.module.ontology.enums.ErrorCodeConstants.ASSET_MODE_NOT_ALLOWED;
import static com.ontos.module.ontology.enums.ErrorCodeConstants.DIMENSION_NOT_ALLOWED;
import static com.ontos.module.query.enums.ErrorCodeConstants.ABSOLUTE_DATE_REJECTED;
import static com.ontos.module.query.enums.ErrorCodeConstants.ANALYSIS_MODE_NOT_IMPLEMENTED;
import static com.ontos.module.query.enums.ErrorCodeConstants.CROSS_TABLE_UNSUPPORTED;
import static com.ontos.module.query.enums.ErrorCodeConstants.DSL_INVALID;
import static com.ontos.module.query.enums.ErrorCodeConstants.SENSITIVE_BULK_EXPORT_REJECTED;

/**
 * DSL 校验器：只做结构与授权校验，不执行查询。
 *
 * <p>校验顺序固定：模式参数 → 子意图 → 指标 → 分析模式 → 维度 → 过滤 → 时间窗 → 行数/敏感闸门。
 * 任一步失败即整单拒绝，不返回部分结果。
 *
 * <p>M2/W2.5：补上 analysis_params（T3.0 规格）、sub_intents 结构、时间来源二通道、
 * 以及敏感资产批量导出的编译侧 guardrail（完整累计检测仍归 M4）。
 */
@Component
public class DslValidator {

    private static final Pattern STEP_WINDOW = Pattern.compile("^[1-9]\\d*[dhm]$");
    private static final Set<String> YOY_MOM_PERIODS = Set.of("MONTH", "YEAR");

    private final QueryProperties properties;

    public DslValidator(QueryProperties properties) {
        this.properties = properties;
    }

    public void validate(QueryDsl dsl, PublishedManifest manifest) {
        if (dsl == null) {
            throw invalidParamException("查询 DSL 不能为空");
        }
        if (dsl.getMetricId() == null || dsl.getMetricId().isBlank()) {
            throw exception(DSL_INVALID, "metricId 不能为空");
        }
        SemanticManifest semantic = manifest.getSemantic();
        SemanticManifest.Metric metric = semantic.requireMetric(dsl.getMetricId());
        QueryDsl.AnalysisMode mode = dsl.getMode() == null ? QueryDsl.AnalysisMode.QUERY : dsl.getMode();

        validateAnalysisParams(dsl, mode);
        validateSubIntents(dsl, semantic);

        if (!metric.getAllowedModes().contains(mode.name())) {
            throw exception(ASSET_MODE_NOT_ALLOWED, mode.name());
        }
        // fail-closed（PoC-04 缺陷修复）：W3 构造器落地前只有 QUERY 有确定性构造，
        // 清单声明了其他 allowed_modes 也必须拒绝，防止非 QUERY 模式静默编译成聚合 SQL。
        if (mode != QueryDsl.AnalysisMode.QUERY) {
            throw exception(ANALYSIS_MODE_NOT_IMPLEMENTED, mode.name());
        }

        List<String> dimensionIds = dsl.getDimensions() == null ? List.of() : dsl.getDimensions();
        List<SemanticManifest.Dimension> resolvedDimensions = new ArrayList<>();
        for (String dimensionId : dimensionIds) {
            SemanticManifest.Dimension dimension = semantic.requireDimension(dimensionId);
            resolvedDimensions.add(dimension);
            if (!metric.getAllowedDimensions().contains(dimensionId)) {
                throw exception(DIMENSION_NOT_ALLOWED, dimensionId);
            }
            if (!Objects.equals(dimension.getTable(), metric.getTable())
                    && !joinCovers(dsl, dimension.getTable())) {
                throw exception(CROSS_TABLE_UNSUPPORTED, dimensionId + " -> " + metric.getTable());
            }
        }

        List<QueryDsl.Filter> filters = dsl.getFilters() == null ? List.of() : dsl.getFilters();
        for (QueryDsl.Filter filter : filters) {
            SemanticManifest.Dimension dimension = semantic.requireDimension(filter.getFieldId());
            if (!Objects.equals(dimension.getTable(), metric.getTable())
                    && !joinCovers(dsl, dimension.getTable())) {
                throw exception(CROSS_TABLE_UNSUPPORTED, filter.getFieldId() + " -> " + metric.getTable());
            }
            if (filter.getOperator() == null) {
                throw exception(DSL_INVALID, "过滤条件缺少 operator");
            }
            List<String> values = filter.getValues() == null ? List.of() : filter.getValues();
            if (values.isEmpty()) {
                throw exception(DSL_INVALID, "过滤条件缺少 values: " + filter.getFieldId());
            }
            if (filter.getOperator() == QueryDsl.Operator.BETWEEN && values.size() != 2) {
                throw exception(DSL_INVALID, "BETWEEN 需要两个值: " + filter.getFieldId());
            }
        }

        validateTimeRange(dsl, semantic, metric);

        int limit = dsl.getLimit() == null ? properties.getMaxRows() : dsl.getLimit();
        if (limit < 1 || limit > properties.getMaxRows()) {
            throw exception(DSL_INVALID, "limit 必须在 [1, " + properties.getMaxRows() + "]");
        }
        boolean sensitive = metric.isSensitive()
                || resolvedDimensions.stream().anyMatch(SemanticManifest.Dimension::isSensitive);
        if (sensitive && dimensionIds.isEmpty() && limit > properties.getSensitiveUngroupedLimit()) {
            throw exception(SENSITIVE_BULK_EXPORT_REJECTED,
                    metric.getId() + " 无维度分组且 limit=" + limit);
        }
    }

    /**
     * 时间窗来源二通道（M2/T2.2 + W2.5）：
     * EXPRESSION 必须带服务端解析版本与原始表达；EXPLICIT 必须来自 explicitRange 且值一致。
     * 客户端直接塞 from/to 而无合法来源标记 = 伪造绝对日期，一律拒绝。
     */
    private void validateTimeRange(QueryDsl dsl, SemanticManifest semantic, SemanticManifest.Metric metric) {
        QueryDsl.TimeRange range = dsl.getTimeRange();
        if (range == null) {
            return;
        }
        String source = range.getSource();
        if (QueryDsl.TimeRange.SOURCE_EXPRESSION.equals(source)) {
            if (isBlank(dsl.getTimeExpressionRaw())) {
                throw exception(ABSOLUTE_DATE_REJECTED,
                        "source=EXPRESSION 但缺少 time_expression_raw：解析器产出不可伪造");
            }
            if (isBlank(dsl.getTimePatternId()) || isBlank(dsl.getTimeParserVersion())) {
                throw exception(ABSOLUTE_DATE_REJECTED,
                        "source=EXPRESSION 缺少服务端解析产物（patternId / parserVersion）");
            }
        } else if (QueryDsl.TimeRange.SOURCE_EXPLICIT.equals(source)) {
            QueryDsl.TimeRange explicit = dsl.getExplicitRange();
            if (!isBlank(dsl.getTimeExpressionRaw()) || explicit == null
                    || !Objects.equals(range.getFrom(), explicit.getFrom())
                    || !Objects.equals(range.getTo(), explicit.getTo())
                    || !Objects.equals(range.getField(), explicit.getField())) {
                throw exception(ABSOLUTE_DATE_REJECTED, "显式日期必须来自 explicitRange 且与 timeRange 一致");
            }
        } else {
            throw exception(ABSOLUTE_DATE_REJECTED,
                    "timeRange 缺少合法来源标记：相对时间请走 timeExpressionRaw，绝对日期仅限 explicitRange");
        }

        if (range.getFrom() == null || range.getTo() == null) {
            throw exception(DSL_INVALID, "timeRange 需要同时提供 from 与 to");
        }
        if (range.getFrom().isAfter(range.getTo())) {
            throw exception(DSL_INVALID, "timeRange 起点晚于终点");
        }
        if (isBlank(range.getField())) {
            throw exception(DSL_INVALID, "timeRange 缺少 field");
        }
        SemanticManifest.Dimension dimension = semantic.requireDimension(range.getField());
        if (!Objects.equals(dimension.getTable(), metric.getTable())
                && !joinCovers(dsl, dimension.getTable())) {
            throw exception(CROSS_TABLE_UNSUPPORTED, range.getField() + " -> " + metric.getTable());
        }
    }

    /** W3/T3.1：跨表字段只有被服务端 UNIQUE 路径覆盖时才放行。 */
    private static boolean joinCovers(QueryDsl dsl, String table) {
        List<QueryDsl.ResolvedJoin> joins = dsl.getResolvedJoins();
        if (joins == null || table == null) {
            return false;
        }
        for (QueryDsl.ResolvedJoin join : joins) {
            if (table.equals(join.getTable())
                    || columnBelongsTo(join.getLeftColumn(), table)
                    || columnBelongsTo(join.getRightColumn(), table)) {
                return true;
            }
        }
        return false;
    }

    private static boolean columnBelongsTo(String column, String table) {
        if (column == null) {
            return false;
        }
        int lastDot = column.lastIndexOf('.');
        return lastDot > 0 && table.equals(column.substring(0, lastDot));
    }

    /** funnel / yoy_mom 的 analysis_params 契约（analysis-mode 规格 v1）。W3 构造器据此实现。 */
    private void validateAnalysisParams(QueryDsl dsl, QueryDsl.AnalysisMode mode) {
        QueryDsl.AnalysisParams params = dsl.getAnalysisParams();
        if (params == null) {
            return;
        }
        boolean funnel = mode == QueryDsl.AnalysisMode.FUNNEL;
        boolean yoyMom = mode == QueryDsl.AnalysisMode.YOY_MOM;
        if (!funnel && !yoyMom) {
            throw exception(DSL_INVALID, "analysisParams 仅允许 funnel / yoy_mom 模式");
        }
        if (funnel) {
            List<String> steps = params.getSteps() == null ? List.of() : params.getSteps();
            if (steps.size() < 2) {
                throw exception(DSL_INVALID, "funnel steps 至少需要 2 步（规格 v1 §1.1）");
            }
            if (new HashSet<>(steps).size() != steps.size()) {
                throw exception(DSL_INVALID, "funnel steps 不可重复");
            }
            if (params.getCohortWindow() == null || isBlank(params.getCohortWindow().getTimeExpressionRaw())) {
                throw exception(DSL_INVALID, "funnel 缺少 cohortWindow.timeExpressionRaw");
            }
            if (isBlank(params.getStepWindow()) || !STEP_WINDOW.matcher(params.getStepWindow()).matches()) {
                throw exception(DSL_INVALID, "funnel stepWindow 形如 7d / 12h");
            }
            if (isBlank(params.getSubjectPropertyId())) {
                throw exception(DSL_INVALID, "funnel 缺少 subjectPropertyId（本体属性 ID）");
            }
            if (!"first_occurrence".equals(params.getDeduplication())
                    || !"previous_step".equals(params.getConversionDenominator())) {
                throw exception(DSL_INVALID, "funnel 去重/分母口径须按规格 v1 定档");
            }
            if (!isBlank(params.getPeriod())) {
                throw exception(DSL_INVALID, "funnel 不接受 period");
            }
            return;
        }
        if (!YOY_MOM_PERIODS.contains(params.getPeriod())) {
            throw exception(DSL_INVALID, "yoy_mom period 仅允许 MONTH / YEAR");
        }
        if (params.getSteps() != null && !params.getSteps().isEmpty()) {
            throw exception(DSL_INVALID, "yoy_mom 不接受 steps");
        }
        if (params.getStepWindow() != null || params.getSubjectPropertyId() != null
                || params.getDeduplication() != null || params.getConversionDenominator() != null) {
            throw exception(DSL_INVALID, "yoy_mom 不接受 funnel 专属参数");
        }
    }

    /** 复合意图结构校验：M2 只支持无依赖子集，结果依赖类直接拒绝并转澄清（全链路归 M6）。 */
    private void validateSubIntents(QueryDsl dsl, SemanticManifest semantic) {
        List<QueryDsl.SubIntent> subIntents = dsl.getSubIntents();
        if (subIntents == null || subIntents.isEmpty()) {
            return;
        }
        Set<String> ids = new HashSet<>();
        for (QueryDsl.SubIntent sub : subIntents) {
            if (isBlank(sub.getSubIntentId()) || !ids.add(sub.getSubIntentId())) {
                throw exception(DSL_INVALID, "subIntents 的 subIntentId 必填且唯一");
            }
            if (isBlank(sub.getMetricId())) {
                throw exception(DSL_INVALID, "subIntents 缺少 metricId: " + sub.getSubIntentId());
            }
            semantic.requireMetric(sub.getMetricId());
            if (sub.getDependsOn() != null && !sub.getDependsOn().isEmpty()) {
                throw exception(DSL_INVALID, "M2 不支持结果依赖子意图，转澄清（全链路归 M6）");
            }
        }
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

}

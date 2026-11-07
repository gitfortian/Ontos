package com.ontos.module.query.core.dsl;

import java.util.List;
import java.util.Objects;

import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.ontology.core.SemanticManifest;
import com.ontos.module.query.core.QueryProperties;
import org.springframework.stereotype.Component;

import static com.ontos.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.ontos.framework.common.exception.util.ServiceExceptionUtil.invalidParamException;
import static com.ontos.module.ontology.enums.ErrorCodeConstants.ASSET_MODE_NOT_ALLOWED;
import static com.ontos.module.ontology.enums.ErrorCodeConstants.DIMENSION_NOT_ALLOWED;
import static com.ontos.module.query.enums.ErrorCodeConstants.CROSS_TABLE_UNSUPPORTED;
import static com.ontos.module.query.enums.ErrorCodeConstants.DSL_INVALID;

/**
 * DSL 校验器：只做结构与授权校验，不执行查询。
 *
 * <p>校验顺序固定：指标 → 分析模式 → 维度 → 过滤 → 时间窗 → 行数上限。
 * 任一步失败即整单拒绝，不返回部分结果。
 */
@Component
public class DslValidator {

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
        if (!metric.getAllowedModes().contains(mode.name())) {
            throw exception(ASSET_MODE_NOT_ALLOWED, mode.name());
        }

        List<String> dimensions = dsl.getDimensions() == null ? List.of() : dsl.getDimensions();
        for (String dimensionId : dimensions) {
            SemanticManifest.Dimension dimension = semantic.requireDimension(dimensionId);
            if (!metric.getAllowedDimensions().contains(dimensionId)) {
                throw exception(DIMENSION_NOT_ALLOWED, dimensionId);
            }
            if (!Objects.equals(dimension.getTable(), metric.getTable())) {
                throw exception(CROSS_TABLE_UNSUPPORTED, dimensionId + " -> " + metric.getTable());
            }
        }

        List<QueryDsl.Filter> filters = dsl.getFilters() == null ? List.of() : dsl.getFilters();
        for (QueryDsl.Filter filter : filters) {
            SemanticManifest.Dimension dimension = semantic.requireDimension(filter.getFieldId());
            if (!Objects.equals(dimension.getTable(), metric.getTable())) {
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

        if (dsl.getTimeRange() != null) {
            QueryDsl.TimeRange range = dsl.getTimeRange();
            if (range.getFrom() == null || range.getTo() == null) {
                throw exception(DSL_INVALID, "timeRange 需要同时提供 from 与 to");
            }
            if (range.getFrom().isAfter(range.getTo())) {
                throw exception(DSL_INVALID, "timeRange 起点晚于终点");
            }
            if (range.getField() == null || range.getField().isBlank()) {
                throw exception(DSL_INVALID, "timeRange 缺少 field");
            }
            SemanticManifest.Dimension dimension = semantic.requireDimension(range.getField());
            if (!Objects.equals(dimension.getTable(), metric.getTable())) {
                throw exception(CROSS_TABLE_UNSUPPORTED, range.getField() + " -> " + metric.getTable());
            }
        }

        int limit = dsl.getLimit() == null ? properties.getMaxRows() : dsl.getLimit();
        if (limit < 1 || limit > properties.getMaxRows()) {
            throw exception(DSL_INVALID, "limit 必须在 [1, " + properties.getMaxRows() + "]");
        }
    }

}

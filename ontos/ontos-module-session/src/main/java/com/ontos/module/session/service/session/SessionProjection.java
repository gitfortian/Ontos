package com.ontos.module.session.service.session;

import java.util.List;
import java.util.Map;

import com.ontos.module.query.core.dsl.QueryDsl;

/**
 * 会话实体投影（M4 收口审核 F-1 模型）：本轮已采纳 DSL 的实体全集——
 * 服务端状态 = 投影全量覆盖 + 显式澄清选择追加，不做 NL 级增量合并。
 *
 * <p>「覆盖/追加/清除/沿用」四规则由 LLM 在 prompt 会话上下文中完成（本轮 DSL 即合并结果）；
 * 服务端只投影结果，可判定、可测试（投影矩阵：以本轮 DSL 内容断言状态）。
 */
public record SessionProjection(
        List<String> metricIds,
        List<String> dimensionIds,
        List<String> propertyIds,
        String entityId,
        String timeExpressionRaw) {

    public static SessionProjection of(QueryDsl queryDsl) {
        List<String> metrics = queryDsl.getMetricId() != null
                ? List.of(queryDsl.getMetricId())
                : (queryDsl.getBooleanMetricId() != null
                        ? List.of(queryDsl.getBooleanMetricId()) : List.of());
        List<String> dimensions = new java.util.ArrayList<>();
        if (queryDsl.getDimensions() != null) {
            dimensions.addAll(queryDsl.getDimensions());
        }
        if (queryDsl.getFilters() != null) {
            for (QueryDsl.Filter filter : queryDsl.getFilters()) {
                if (filter.getFieldId() != null && !dimensions.contains(filter.getFieldId())) {
                    dimensions.add(filter.getFieldId());
                }
            }
        }
        if (queryDsl.getTimeRange() != null && queryDsl.getTimeRange().getField() != null
                && !dimensions.contains(queryDsl.getTimeRange().getField())) {
            dimensions.add(queryDsl.getTimeRange().getField());
        }
        List<String> properties = queryDsl.getAttributes() == null
                ? List.of() : List.copyOf(queryDsl.getAttributes());
        return new SessionProjection(
                List.copyOf(metrics), List.copyOf(dimensions), properties,
                queryDsl.getEntityId(), queryDsl.getTimeExpressionRaw());
    }

    public Map<String, Object> toJson() {
        Map<String, Object> json = new java.util.LinkedHashMap<>();
        json.put("metrics", metricIds);
        json.put("dimensions", dimensionIds);
        json.put("properties", propertyIds);
        json.put("entity", entityId == null ? "" : entityId);
        json.put("time", timeExpressionRaw == null ? "" : timeExpressionRaw);
        return json;
    }

    /** 投影是否为空（首轮或清空后）：指代消解仅在非空投影上生效。 */
    public boolean isEmpty() {
        return metricIds.isEmpty() && dimensionIds.isEmpty() && propertyIds.isEmpty()
                && (entityId == null || entityId.isBlank());
    }
}

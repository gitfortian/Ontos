package com.ontos.module.agent.service.orchestration;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.ontology.core.SemanticManifest;

/**
 * 相对时间的时间维度推导（M7/W5/T5.3，E-7 首跑暴露的 P1 缺陷修复）。
 *
 * <p>问题：LLM 只输出 {@code time_expression_raw}（"上个月"），不输出物理时间字段；
 * 而 {@code TimeWindowResolver} 在解析相对时间时要求 {@code timeRange.field} 已存在，
 * 于是所有「带时间表达的 LLM 提案」都会 `TIME_PARSE_FAILED`。确定性路径因为
 * {@code time_expression_raw} 恒为 null（不设时间窗）而没有暴露该缺陷。
 *
 * <p>口径：时间维度只能来自**已发布清单**——取指标 {@code allowedDimensions} 中第一个
 * 物理列 `valueType` 为日期/时间的维度；找不到就返回 null，由原有 fail-closed 拒绝
 * （绝不猜列、不默认取第一个维度）。
 */
final class TimeDimensionResolver {

    /** 清单中可承载时间窗的属性类型（小写比较）。 */
    private static final Set<String> DATE_TYPES = Set.of("date", "datetime", "timestamp", "timestamptz");

    private TimeDimensionResolver() {
    }

    /**
     * @return 可作时间窗的维度 ID；无可用维度返回 null
     */
    static String resolve(PublishedManifest manifest, String metricId) {
        if (manifest == null || manifest.getSemantic() == null || metricId == null || metricId.isBlank()) {
            return null;
        }
        SemanticManifest semantic = manifest.getSemantic();
        SemanticManifest.Metric metric = findMetric(semantic, metricId);
        if (metric == null) {
            return null;
        }
        for (String dimensionId : metric.getAllowedDimensions()) {
            SemanticManifest.Dimension dimension = semantic.getDimensions().stream()
                    .filter(item -> item.getId().equals(dimensionId))
                    .findFirst()
                    .orElse(null);
            if (dimension == null) {
                continue;
            }
            String physical = dimension.getTable() + "." + dimension.getColumn();
            if (isDateProperty(manifest, physical)) {
                return dimensionId;
            }
        }
        return null;
    }

    /** 变体指标（metric.x.variant）回退到基础指标取维度约束。 */
    private static SemanticManifest.Metric findMetric(SemanticManifest semantic, String metricId) {
        List<SemanticManifest.Metric> metrics = semantic.getMetrics();
        for (SemanticManifest.Metric metric : metrics) {
            if (metric.getId().equals(metricId)) {
                return metric;
            }
        }
        int lastDot = metricId.lastIndexOf('.');
        if (lastDot > 0) {
            String baseId = metricId.substring(0, lastDot);
            for (SemanticManifest.Metric metric : metrics) {
                if (metric.getId().equals(baseId)) {
                    return metric;
                }
            }
        }
        return null;
    }

    private static boolean isDateProperty(PublishedManifest manifest, String physicalColumn) {
        if (manifest.getOntology() == null || manifest.getOntology().getProperties() == null) {
            return false;
        }
        return manifest.getOntology().getProperties().stream()
                .anyMatch(property -> physicalColumn.equalsIgnoreCase(property.getColumn())
                        && property.getValueType() != null
                        && DATE_TYPES.contains(property.getValueType().toLowerCase(Locale.ROOT)));
    }
}

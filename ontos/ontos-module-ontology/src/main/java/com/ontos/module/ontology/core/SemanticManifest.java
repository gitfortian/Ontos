package com.ontos.module.ontology.core;

import java.util.List;

import com.ontos.module.ontology.enums.ErrorCodeConstants;
import lombok.Data;

import static com.ontos.framework.common.exception.util.ServiceExceptionUtil.exception;

/**
 * 已发布语义清单中的指标与维度定义。只读、版本化，运行时不得修改。
 *
 * <p>编译器只认这里的物理映射；LLM 不输出表名、列名或 SQL 表达式。
 */
@Data
public class SemanticManifest {

    private String semanticVersion;
    private List<Metric> metrics = List.of();
    private List<Dimension> dimensions = List.of();

    public Metric requireMetric(String metricId) {
        return metrics.stream()
                .filter(metric -> metric.getId().equals(metricId))
                .findFirst()
                .orElseThrow(() -> exception(ErrorCodeConstants.ASSET_NOT_FOUND, metricId));
    }

    public Dimension requireDimension(String dimensionId) {
        return dimensions.stream()
                .filter(dimension -> dimension.getId().equals(dimensionId))
                .findFirst()
                .orElseThrow(() -> exception(ErrorCodeConstants.ASSET_NOT_FOUND, dimensionId));
    }

    public enum Aggregation {
        SUM,
        COUNT,
        COUNT_DISTINCT,
        AVG,
        MIN,
        MAX
    }

    @Data
    public static class Metric {
        private String id;
        private String name;
        /** 一句话业务说明（业务化命名规格必填字段）。 */
        private String summary;
        /** 常见叫法（同义词 / 口语 / 缩写），进检索词库；业务化规格要求至少 1 条。 */
        private List<String> aliases = List.of();
        private String table;
        private String column;
        private Aggregation aggregation;
        private List<String> allowedModes = List.of();
        private List<String> allowedDimensions = List.of();
        private boolean sensitive;
    }

    @Data
    public static class Dimension {
        private String id;
        private String name;
        /** 一句话业务说明（业务化命名规格必填字段）。 */
        private String summary;
        /** 常见叫法，进检索词库；业务化规格要求至少 1 条。 */
        private List<String> aliases = List.of();
        private String table;
        private String column;
        private boolean sensitive;
    }
}

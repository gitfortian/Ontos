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
        /** 布尔判定指标：引用已发布的 Rule，此时无物理列与聚合（二选一，结构校验器把关）。 */
        private String ruleId;
        private String table;
        private String column;
        private Aggregation aggregation;
        private List<String> allowedModes = List.of();
        private List<String> allowedDimensions = List.of();
        private boolean sensitive;
        /**
         * 口径变体（v1.4 §5.4）：同一指标下的声明式细分口径。
         * 变体只追加确定性过滤条件，不覆盖聚合与物理列；跨表条件在 M3 明确拒绝。
         */
        private List<CaliberVariant> caliberVariants = List.of();
    }

    /** 口径变体：ID 进 DSL 与审计，condition 复用「字段 + 算子 + 值」三元组。 */
    @Data
    public static class CaliberVariant {
        private String variantId;
        /** 口径别名，用于澄清候选展示与检索提示。 */
        private List<String> alias = List.of();
        private VariantCondition condition;
        private String version;
    }

    /** 声明式条件：算子为封闭枚举，禁止内联表达式。 */
    @Data
    public static class VariantCondition {
        private String fieldId;
        /** EQ / IN / NOT_IN / GTE / LTE / BETWEEN */
        private String operator;
        private List<String> values = List.of();
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

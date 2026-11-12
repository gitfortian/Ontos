package com.ontos.module.ontology.core;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ontos.module.ontology.enums.ErrorCodeConstants;
import lombok.Data;

import static com.ontos.framework.common.exception.util.ServiceExceptionUtil.exception;

/**
 * 已发布语义清单中的指标与维度定义。只读、版本化，运行时不得修改。
 *
 * <p>编译器只认这里的物理映射；LLM 不输出表名、列名或 SQL 表达式。
 *
 * <p><b>B0 双形态</b>（docs/03-plans/stages/B0）：v19 用 {@code table + column} 直绑物理列；
 * v20 用 {@code objectId + propertyId} 引用本体对象属性，物理名只由 {@code Property.column} 持有。
 * 两形态互斥，混装一律拒绝——判定收口在 {@link Metric#bindingForm()} / {@link Dimension#bindingForm()}。
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

    /** 物理绑定形态（B0）：读取侧唯一判定入口，编译器与校验器共用，不得各自实现。 */
    public enum BindingForm {
        /** v19：只带 {@code table + column}。 */
        V19_PHYSICAL,
        /** v20：只带 {@code objectId + propertyId}。 */
        V20_SEMANTIC,
        /** 布尔判定指标：只带 {@code ruleId}，无物理/语义绑定。 */
        RULE_ONLY,
        /** 混装或完全缺绑定：拒绝。 */
        REJECT
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
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
        /** v20：业务对象 ID（如 {@code concept.order}）；常规指标必填。 */
        private String objectId;
        /** v20：对象属性 ID（如 {@code property.order.amount}）；对象级计数时为 null。 */
        private String propertyId;
        private List<String> allowedModes = List.of();
        private List<String> allowedDimensions = List.of();
        private boolean sensitive;
        /**
         * 口径变体（v1.4 §5.4）：同一指标下的声明式细分口径。
         * 变体只追加确定性过滤条件，不覆盖聚合与物理列；跨表条件在 M3 明确拒绝。
         */
        private List<CaliberVariant> caliberVariants = List.of();

        /** 绑定形态判定（B0）：见 {@link BindingForm}。 */
        public BindingForm bindingForm() {
            boolean physical = !blank(table) || !blank(column);
            boolean semantic = !blank(objectId) || !blank(propertyId);
            if (!blank(ruleId)) {
                return physical || semantic ? BindingForm.REJECT : BindingForm.RULE_ONLY;
            }
            if (physical && semantic) {
                return BindingForm.REJECT;
            }
            if (physical) {
                return BindingForm.V19_PHYSICAL;
            }
            return semantic ? BindingForm.V20_SEMANTIC : BindingForm.REJECT;
        }
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
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Dimension {
        private String id;
        private String name;
        /** 一句话业务说明（业务化命名规格必填字段）。 */
        private String summary;
        /** 常见叫法，进检索词库；业务化规格要求至少 1 条。 */
        private List<String> aliases = List.of();
        private String table;
        private String column;
        /** v20：业务对象 ID（必填）。 */
        private String objectId;
        /** v20：对象属性 ID（必填）。 */
        private String propertyId;
        private boolean sensitive;

        /** 绑定形态判定（B0）：见 {@link BindingForm}。 */
        public BindingForm bindingForm() {
            boolean physical = !blank(table) || !blank(column);
            boolean semantic = !blank(objectId) || !blank(propertyId);
            if (physical && semantic) {
                return BindingForm.REJECT;
            }
            if (physical) {
                return BindingForm.V19_PHYSICAL;
            }
            return semantic ? BindingForm.V20_SEMANTIC : BindingForm.REJECT;
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}

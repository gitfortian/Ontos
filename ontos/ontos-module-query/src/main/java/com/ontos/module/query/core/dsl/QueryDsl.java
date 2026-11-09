package com.ontos.module.query.core.dsl;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * 类型化查询 DSL。只引用已授权资产 ID，不出现物理表名、Join 条件或 SQL 表达式。
 */
@Data
public class QueryDsl {

    /** 必填：请求固定到单一发布清单，保证可复现。 */
    private String manifestId;
    private String scenarioId;
    private AnalysisMode mode = AnalysisMode.QUERY;
    private String metricId;
    private List<String> dimensions = new ArrayList<>();
    private List<Filter> filters = new ArrayList<>();
    private TimeRange timeRange;
    /** 相对时间表达（如「上个月」「近 7 天」），由服务端时间解析器换算；LLM 不产出绝对日期。 */
    private String timeExpressionRaw;
    /**
     * 用户显式给出的绝对日期区间（唯一放行绝对日期的通道）。
     * 服务端校验后复制进 timeRange 并标记 source=EXPLICIT；LLM 输出契约禁止产出该字段。
     */
    private TimeRange explicitRange;
    /** 复合意图的逐子意图（M2/W4 逐子独立编译；M2 遇到 dependsOn 即拒绝转澄清）。 */
    private List<SubIntent> subIntents = new ArrayList<>();
    /** funnel / yoy_mom 的模式参数，契约见 analysis-mode 规格 v1。 */
    private AnalysisParams analysisParams;
    /**
     * lookup / lookup_batch 的投影实体（Concept ID）。这两类模式不需要 metric：
     * v1.4 §6.3.1 明确 lookup 允许 metrics / dimensions 为空。
     */
    private String entityId;
    /** lookup 投影属性（Property ID），必须属于 entity 且 lookupProjectable；M2 要求同表。 */
    private List<String> attributes = new ArrayList<>();
    /** lookup 单实体标识（propertyId + value）；property 必须属于 entity 且可定位。 */
    private LookupIdentity identity;
    /** lookup_batch 的标识值列表：按请求顺序逐行对齐，缺失行统一「不可用」。 */
    private List<String> identities = new ArrayList<>();
    /** lookup_batch 的标识属性（Property ID）。 */
    private String identityPropertyId;
    /** lookup / lookup_batch 的布尔判定列（引用 ruleId 型 Metric），编译期展开为 Rule 子查询。 */
    private String booleanMetricId;
    /** 以下两字段由服务端 TimeWindowResolver 回填并随 dsl_json 进审计；客户端提交由 DslCodec 拒绝。 */
    private String timePatternId;
    private String timeParserVersion;
    /** 服务端 JoinPathResolver 回填的 UNIQUE 路径（W3/T3.1）；随 dsl_json 进审计，客户端提交由 DslCodec 拒绝。 */
    private List<ResolvedJoin> resolvedJoins = new ArrayList<>();
    /**
     * 结果行数上限。默认 null 表示「由模式定档」：QUERY 取 max-rows、lookup 取 1、
     * lookup_batch 取 max-batch-size；显式值必须落在对应上限内。校验器会把生效值写回。
     */
    private Integer limit;
    private String variantHint;
    /**
     * 口径变体（M3/W2，v1.4 §5.4）：多口径指标必须显式选择，缺失转 CALIBER_AMBIGUOUS，
     * 禁止 LLM 二选一；编译期把变体的声明式条件追加到 WHERE。
     */
    private String caliberVariantId;

    @Data
    public static class Filter {
        private String fieldId;
        private Operator operator;
        private List<String> values = new ArrayList<>();
    }

    /**
     * 时间窗：{@code source} 标记来源——用户显式日期为 EXPLICIT（唯一放行路径），
     * 服务端解析器产出为 EXPRESSION（M2/T2.3）；两者皆无来源 = LLM 伪造绝对日期，校验拒绝。
     */
    @Data
    public static class TimeRange {
        public static final String SOURCE_EXPLICIT = "EXPLICIT";
        public static final String SOURCE_EXPRESSION = "EXPRESSION";
        private String field;
        private Instant from;
        private Instant to;
        private String source;
    }

    /** 复合意图子项：只携带已发布资产 ID，不携带物理名与 SQL。 */
    @Data
    public static class SubIntent {
        private String subIntentId;
        private String metricId;
        private AnalysisMode mode = AnalysisMode.QUERY;
        private List<String> dimensions = new ArrayList<>();
        private List<Filter> filters = new ArrayList<>();
        private String timeExpressionRaw;
        /**
         * 子意图自己的时间维度（只承载 {@code field}）：相对时间由服务端解析器换算后回填 from/to。
         * 子意图没有 explicitRange，M2 表达不了「子意图内的绝对日期」——这是有意的窄口，
         * 避免复合请求绕过「绝对日期只走 explicitRange」的唯一放行路径。
         */
        private TimeRange timeRange;
        /** M2 仅支持无依赖子集；非空即拒绝并转澄清（全链路归 M6）。 */
        private List<String> dependsOn = new ArrayList<>();
    }

    /** funnel / yoy_mom 的 analysis_params（字段名按 DSL camelCase 契约，语义见规格 v1）。 */
    @Data
    public static class AnalysisParams {
        private List<String> steps = new ArrayList<>();
        private CohortWindow cohortWindow;
        private String stepWindow;
        private String subjectPropertyId;
        private String deduplication;
        private String conversionDenominator;
        /** yoy_mom：MONTH | YEAR 封闭枚举，QUARTER 归 M6。 */
        private String period;
    }

    @Data
    public static class CohortWindow {
        /** 用户/LLM 给出的相对时间表达；客户端唯一可提交的字段。 */
        private String timeExpressionRaw;
        /** 以下由服务端 TimeWindowResolver 回填，客户端提交由 DslCodec 拒绝。 */
        private Instant from;
        private Instant to;
        private String source;
        private String patternId;
        private String parserVersion;
    }

    /** lookup 单实体标识：只引用本体属性 ID + 值，不携带物理列。 */
    @Data
    public static class LookupIdentity {
        private String propertyId;
        private String value;
    }

    /**
     * 一条已解析的 Join 边（服务端回填，物理列只来自已发布清单的 Relation）。
     * leftColumn / rightColumn 为限定物理列名，ON 条件由 jOOQ 结构化构造。
     */
    @Data
    public static class ResolvedJoin {
        private String relationId;
        private String direction;
        /** 本次 JOIN 需要引入的表（尚未在 FROM 中的那一端）。 */
        private String table;
        private String leftColumn;
        private String rightColumn;
        private String cardinality;
    }

    public enum Operator {
        EQ,
        IN,
        NOT_IN,
        GTE,
        LTE,
        BETWEEN
    }

    /** 编译器内置、封闭枚举；扩展需代码变更与版本发布。 */
    public enum AnalysisMode {
        QUERY,
        LOOKUP,
        LOOKUP_BATCH,
        FUNNEL,
        YOY_MOM,
        ATTRIBUTION,
        RETENTION,
        DISCOVERY
    }
}

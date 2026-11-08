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
    private Integer limit = 100;
    private String variantHint;

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

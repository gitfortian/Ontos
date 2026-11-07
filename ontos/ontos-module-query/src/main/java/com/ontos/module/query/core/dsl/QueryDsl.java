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
    private Integer limit = 100;
    private String variantHint;

    @Data
    public static class Filter {
        private String fieldId;
        private Operator operator;
        private List<String> values = new ArrayList<>();
    }

    @Data
    public static class TimeRange {
        private String field;
        private Instant from;
        private Instant to;
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

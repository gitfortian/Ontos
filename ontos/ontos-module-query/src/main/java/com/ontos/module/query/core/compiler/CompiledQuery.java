package com.ontos.module.query.core.compiler;

import java.time.Instant;
import java.util.List;

import com.ontos.module.query.core.dsl.QueryDsl;
import lombok.Data;

/**
 * 编译产物：参数化 SQL + 绑定值 + 版本锚点。dry-plan 与正式执行共用同一产物。
 */
@Data
public class CompiledQuery {

    private String manifestId;
    private String contentHash;
    /** 分析模式；lookup 系列没有 metricId，用 entityId 标识投影对象。 */
    private String mode;
    private String metricId;
    /** W3/T3.2：lookup 投影实体与属性（Property ID），供 dry-plan 展示与批量结果对齐。 */
    private String entityId;
    private List<String> attributes;
    /** W3/T3.4：布尔判定列所引用的 ruleId 型 Metric，供 dry-plan 展示与审计回放。 */
    private String booleanMetricId;
    /** lookup_batch 的请求标识顺序，执行后按此顺序逐行判定可用性。 */
    private List<String> identities;
    private List<String> dimensions;
    /** W3/T3.1：服务端解析出的 UNIQUE Join 路径，供 dry-plan 展示与审计回放。 */
    private List<QueryDsl.ResolvedJoin> joins;
    private String dialect;
    private String sql;
    private List<Object> bindValues;
    private int appliedLimit;
    private Instant compiledAt;

}

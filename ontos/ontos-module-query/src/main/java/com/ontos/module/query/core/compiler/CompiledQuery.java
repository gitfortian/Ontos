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
    private String metricId;
    private List<String> dimensions;
    /** W3/T3.1：服务端解析出的 UNIQUE Join 路径，供 dry-plan 展示与审计回放。 */
    private List<QueryDsl.ResolvedJoin> joins;
    private String dialect;
    private String sql;
    private List<Object> bindValues;
    private int appliedLimit;
    private Instant compiledAt;

}

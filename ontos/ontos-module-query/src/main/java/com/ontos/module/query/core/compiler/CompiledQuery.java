package com.ontos.module.query.core.compiler;

import java.time.Instant;
import java.util.List;

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
    private String dialect;
    private String sql;
    private List<Object> bindValues;
    private int appliedLimit;
    private Instant compiledAt;

}

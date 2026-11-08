package com.ontos.module.query.core.executor;

import java.util.List;
import java.util.Map;

import lombok.Data;

@Data
public class QueryResult {

    private String manifestId;
    private String contentHash;
    private List<String> columns;
    private List<Map<String, Object>> rows;
    private int rowCount;
    private boolean truncated;
    private long elapsedMs;
    /** W3/T3.2：lookup / lookup_batch 逐行可用性，按请求顺序对齐；非 lookup 模式为 null。 */
    private List<LookupRow> lookupRows;
    /** W4/T4.4：本次结果是否来自 query_cache（命中即未重新编译执行）。 */
    private boolean cached;

    /**
     * 逐行存在性保护（v1.4 §8.2）：不可用行不区分「不存在」与「无权限」，
     * values 为空，调用方无法据此推断主体是否存在。
     */
    public record LookupRow(String identity, boolean available, Map<String, Object> values) {
    }

}

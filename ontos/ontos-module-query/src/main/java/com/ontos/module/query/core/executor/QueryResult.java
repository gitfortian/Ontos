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

}

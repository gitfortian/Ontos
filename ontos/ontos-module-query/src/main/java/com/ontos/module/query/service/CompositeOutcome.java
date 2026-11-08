package com.ontos.module.query.service;

import java.util.List;

import com.ontos.module.query.core.executor.QueryResult;

/**
 * 复合意图执行产物（M2/W4/T4.3，无依赖子集）。
 *
 * <p>每个子意图各自保留独立的编译产物与结果集，不合并 SQL、不共享中间结果；
 * {@code requestId} 是父请求，子请求 ID 由「父 ID:子意图 ID」构成并进审计与产物表。
 */
public record CompositeOutcome(String requestId, int totalRowCount, List<SubResult> results) {

    /** 单个子意图的结果：subIntentId 与请求里的一致，调用方可逐项对齐。 */
    public record SubResult(String subIntentId, QueryResult result) {
    }

}

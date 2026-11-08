package com.ontos.module.query.core.executor;

/**
 * dry-run 产物（M2/W4/T4.1）：执行计划 + 受限执行结果。
 *
 * <p>两者来自同一份 {@link com.ontos.module.query.core.compiler.CompiledQuery}，
 * 前端调试页可以同时展示「估算成本」与「实际返回」，避免计划与结果口径分叉。
 */
public record DryRunOutcome(QueryPlan plan, QueryResult result) {
}

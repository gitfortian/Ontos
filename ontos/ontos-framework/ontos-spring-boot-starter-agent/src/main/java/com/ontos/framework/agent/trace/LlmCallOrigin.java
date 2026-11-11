package com.ontos.framework.agent.trace;

/**
 * 一次 Agent 构建的调用来源（M7/W5/T5.1）。
 *
 * <p>身份在**请求线程**上解析后作为不可变数据传入，不依赖 Reactor 线程的 ThreadLocal
 * （模型流在哪个线程完成由框架决定，不能假设它继承请求上下文）。
 *
 * <p>{@code tenantId} 为空时追踪直接跳过——没有租户的调用记录落库会破坏租户隔离，
 * 宁可丢一条观测数据也不写脏行。
 */
public record LlmCallOrigin(String tenantId, String traceId, String sessionId, Integer turnIndex,
                            String purpose) {

    public static LlmCallOrigin of(String tenantId, String traceId, String sessionId,
                                   Integer turnIndex, String purpose) {
        return new LlmCallOrigin(tenantId, traceId, sessionId, turnIndex, purpose);
    }

    /** 是否可落库（租户维度是唯一硬前提）。 */
    public boolean traceable() {
        return tenantId != null && !tenantId.isBlank();
    }
}

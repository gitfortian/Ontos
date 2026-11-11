package com.ontos.framework.common.trace;

/**
 * 单次模型调用的追踪记录（M7/W5/T5.1）。
 *
 * <p>只记录**元数据**：不存提示词与模型输出原文（v1.4 附录 A.4 脱敏约束），
 * 成本与排障所需的信息（模型名、token 用量、耗时、终态）足够定位问题。
 *
 * @param status {@code success} / {@code error} / {@code cancelled}（超时取消同属取消）
 */
public record LlmCallRecord(String tenantId, String traceId, String sessionId, Integer turnIndex,
                            String purpose, String model, int inputTokens, int outputTokens,
                            int totalTokens, long latencyMs, String status, String errorType,
                            String errorMessage, String finishReason) {
}

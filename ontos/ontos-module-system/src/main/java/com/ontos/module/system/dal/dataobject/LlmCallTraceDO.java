package com.ontos.module.system.dal.dataobject;

import lombok.Data;

/**
 * 模型调用追踪记录（M7/W5/T5.1，V20）：只追加，不存提示词与输出原文。
 */
@Data
public class LlmCallTraceDO {

    private Long id;
    private String tenantId;
    private String traceId;
    private String sessionId;
    private Integer turnIndex;
    private String purpose;
    private String model;
    private Integer inputTokens;
    private Integer outputTokens;
    private Integer totalTokens;
    private Long latencyMs;
    private String status;
    private String errorType;
    private String errorMessage;
    private String finishReason;
    private String createdAt;
}

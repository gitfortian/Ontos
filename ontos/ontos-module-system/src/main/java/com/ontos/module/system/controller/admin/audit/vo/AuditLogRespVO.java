package com.ontos.module.system.controller.admin.audit.vo;

import lombok.Data;

/**
 * 审计日志响应。
 */
@Data
public class AuditLogRespVO {

    private Long id;
    private String requestId;
    private String parentRequestId;
    private String subIntentId;
    private String tenantId;
    private String userId;
    private String sessionId;
    private Integer turnIndex;
    private String manifestId;
    private String action;
    private String metricId;
    private String dsl;
    private String sqlText;
    private Integer rowCount;
    private String reasonCode;
    private String traceId;
    private String createdAt;

}

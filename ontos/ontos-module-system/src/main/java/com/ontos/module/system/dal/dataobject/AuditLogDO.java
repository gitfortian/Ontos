package com.ontos.module.system.dal.dataobject;

import lombok.Data;

/**
 * 审计日志数据对象。审计只追加，不提供更新/删除入口。
 */
@Data
public class AuditLogDO {

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

package com.ontos.module.system.service.audit;

import lombok.Data;

/**
 * 审计写入 DTO。调用方（query 模块）负责填充，system 模块不反向依赖 query 类型。
 */
@Data
public class AuditLogCreateReqDTO {

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
    private String dslJson;
    private String sqlText;
    private Integer rowCount;
    private String reasonCode;
    /** 写入该审计行时的生效策略快照版本（M4/W1）；由 AuditService 统一解析填充，调用方不传。 */
    private String policyVersion;
    /** 关联审批单（敏感触发 / 令牌放行的审计行），W3/T3.2。 */
    private String approvalId;
    /** 敏感计数器哈希化增量（§4.8），与 sensitive_query_event.context_delta 同值同源。 */
    private String sensitiveCountersDelta;
    private String traceId;
    /** 状态迁移起点/终点与触发原因（action=STATE_TRANSITION 行，M1/T6.3）。 */
    private String fromState;
    private String toState;
    private String stateReason;
    /** 请求是否来自开发模拟身份（dev-headers 回退）；生产恒为 false。 */
    private Boolean devMode;

}

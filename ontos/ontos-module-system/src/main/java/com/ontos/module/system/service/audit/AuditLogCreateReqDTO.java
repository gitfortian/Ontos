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
    private String traceId;
    /** 状态迁移起点/终点与触发原因（action=STATE_TRANSITION 行，M1/T6.3）。 */
    private String fromState;
    private String toState;
    private String stateReason;
    /** 请求是否来自开发模拟身份（dev-headers 回退）；生产恒为 false。 */
    private Boolean devMode;

}

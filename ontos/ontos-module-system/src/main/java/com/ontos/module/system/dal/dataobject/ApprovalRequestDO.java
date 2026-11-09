package com.ontos.module.system.dal.dataobject;

import lombok.Data;

/**
 * 审批单数据对象。时间字段以数据库文本形式承载，避免跨 Jackson 版本的时区差异。
 */
@Data
public class ApprovalRequestDO {

    private String id;
    private String tenantId;
    private String userId;
    private String sessionId;
    private Integer turnIndex;
    private String kind;
    private String status;
    private String payload;
    private String reason;
    private String requestedAt;
    private String decidedAt;
    private String decidedBy;
    /** 放行目标指纹（APPROVED 时生成；换 filters 不放行）。 */
    private String grantFingerprint;
    /** 令牌消费时间（null=未消费，单次有效）。 */
    private String grantConsumedAt;

}

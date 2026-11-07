package com.ontos.module.system.controller.admin.approval.vo;

import lombok.Data;

/**
 * 审批单响应。payload 为原始 JSON 文本，前端按需展开。
 */
@Data
public class ApprovalRespVO {

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

}

package com.ontos.module.system.controller.admin.approval.vo;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 审批决策请求。决策人只能来自认证上下文，禁止由请求体指定。
 */
@Data
public class ApprovalDecideReqVO {

    @NotBlank(message = "审批结论不能为空")
    private String decision;

    private String reason;

}

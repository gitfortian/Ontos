package com.ontos.module.system.controller.admin.audit.vo;

import lombok.Data;

/** 失败样本退回回归集请求（M7/W4/T4.3）：只允许 dev / validation。 */
@Data
public class FailureSampleReturnReqVO {

    private String targetSet;
    private String note;
}

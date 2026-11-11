package com.ontos.module.system.controller.admin.audit.vo;

import lombok.Data;

/** 失败样本状态流转请求（M7/W4/T4.1）。 */
@Data
public class FailureSampleStatusReqVO {

    /** open / triaged / fixed / ignored（regressed 只能走回流通道）。 */
    private String toStatus;
    private String note;
}

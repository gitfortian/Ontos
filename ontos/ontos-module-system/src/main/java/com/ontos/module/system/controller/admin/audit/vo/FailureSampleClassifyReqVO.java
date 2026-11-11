package com.ontos.module.system.controller.admin.audit.vo;

import lombok.Data;

/** 失败样本根因分类请求（M7/W4/T4.2，闭词表 FailureCauseCategory）。 */
@Data
public class FailureSampleClassifyReqVO {

    private String category;
    private String note;
}

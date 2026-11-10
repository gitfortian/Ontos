package com.ontos.module.system.controller.admin.audit.vo;

import lombok.Data;

/**
 * 失败看板样本明细 VO（与 dal DO 解耦——ArchUnit controllers_must_not_touch_dal）。
 */
@Data
public class FailureSampleItemVO {

    private Long id;
    private String setId;
    private String questionId;
    private String runId;
    private String scenarioEntryMode;
    private String queryShape;
    private String turnType;
    private Boolean isComposite;
    private String riskType;
    private String expectedTerminal;
    private String actualTerminal;
    private Long errorCode;
    private String reason;
    private String createdAt;

}

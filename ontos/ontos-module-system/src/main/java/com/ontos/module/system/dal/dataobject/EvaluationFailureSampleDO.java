package com.ontos.module.system.dal.dataobject;

import lombok.Data;

/**
 * 评测失败样本数据对象（M6/W5/T5.1，OBL-M7 交付物 ④）。
 * 只追加（与 audit_log 同规）；纠正样本回流走题集升版流程，不翻本表状态。
 */
@Data
public class EvaluationFailureSampleDO {

    private Long id;
    private String tenantId;
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

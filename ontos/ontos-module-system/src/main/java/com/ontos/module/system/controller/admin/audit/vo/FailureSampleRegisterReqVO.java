package com.ontos.module.system.controller.admin.audit.vo;

import java.util.List;

import lombok.Data;

/**
 * 评测失败样本批量登记请求（M6/W5/T5.1）。
 * 写入方 = 评测 runner（test-runner / smoke-runner）；runId 标识同一评测批次。
 */
@Data
public class FailureSampleRegisterReqVO {

    /** 题集标识（如 test-v3 / smoke-v3）。 */
    private String setId;

    /** 评测运行 ID（同一次评测批次的关联键）。 */
    private String runId;

    private List<Item> samples;

    /** 单条失败样本。 */
    @Data
    public static class Item {

        private String questionId;
        private String scenarioEntryMode;
        private String queryShape;
        private String turnType;
        private Boolean isComposite;
        private String riskType;
        private String expectedTerminal;
        private String actualTerminal;
        private Long errorCode;
        private String reason;

    }

}

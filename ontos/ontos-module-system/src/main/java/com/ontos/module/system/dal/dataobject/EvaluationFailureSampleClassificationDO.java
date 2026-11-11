package com.ontos.module.system.dal.dataobject;

import lombok.Data;

/**
 * 失败样本根因分类事件（M7/W4/T4.2，V19）：只追加；当前分类由最新事件派生。
 */
@Data
public class EvaluationFailureSampleClassificationDO {

    private Long id;
    private String tenantId;
    private Long sampleId;
    /** FailureCauseCategory.code（闭词表）。 */
    private String category;
    private String actor;
    private String note;
    private String createdAt;
}

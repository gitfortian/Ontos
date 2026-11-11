package com.ontos.module.system.dal.dataobject;

import lombok.Data;

/**
 * 失败样本状态事件（M7/W4/T4.1，V19）：只追加；当前态由最新事件派生。
 */
@Data
public class EvaluationFailureSampleStatusEventDO {

    private Long id;
    private String tenantId;
    private Long sampleId;
    /** 初始 open 时为空。 */
    private String fromStatus;
    private String toStatus;
    private String actor;
    private String note;
    private String createdAt;
}

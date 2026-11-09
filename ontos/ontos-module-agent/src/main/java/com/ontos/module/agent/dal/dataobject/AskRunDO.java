package com.ontos.module.agent.dal.dataobject;

import lombok.Data;

/**
 * AG-UI run 聚合根（ask_run，M5/W1 V14）。
 *
 * <p>状态机（规格 §3.7，终态不可回退，合法迁移由 {@link com.ontos.module.agent.service.run.AskRunStateService}
 * 显式枚举并单测锁定）：
 * {@code PENDING → RUNNING → (WAITING_APPROVAL → RUNNING) → FINISHED}，FAILED / EXPIRED 终态。
 */
@Data
public class AskRunDO {

    private String runId;
    private String tenantId;
    private String userId;
    private String sessionId;
    private Integer turnIndex;
    private String status;
    private String createdAt;
    private String updatedAt;

    public static final String PENDING = "PENDING";
    public static final String RUNNING = "RUNNING";
    public static final String WAITING_APPROVAL = "WAITING_APPROVAL";
    public static final String FINISHED = "FINISHED";
    public static final String FAILED = "FAILED";
    public static final String EXPIRED = "EXPIRED";

}

package com.ontos.module.system.dal.dataobject;

import lombok.Data;

/**
 * 策略快照行（M4/W1，policy_snapshot 表）。快照不可变：授权语义只认 active 版本。
 */
@Data
public class PolicySnapshotDO {

    private String snapshotId;
    private String tenantId;
    private String policyVersion;
    /** payload JSON 原文（roles / assignments / assets 三段），由 PolicySnapshotService 反序列化。 */
    private String payload;
    private boolean active;
    private String createdAt;
    private String createdBy;
}

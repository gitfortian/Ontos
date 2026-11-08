package com.ontos.module.query.dal.dataobject;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 编译产物 DO（M2/W4/T4.5）。
 *
 * <p>同一 (tenant, user, requestId) 只保留最近一次编译产物：dry-plan / dry-run / compile / execute
 * 共用同一份 CompiledQuery，回放端点按 request_id 取最新槽位。
 */
@Data
public class QueryArtifactDO {

    private String tenantId;
    private String userId;
    private String requestId;
    private String sessionId;
    private String manifestId;
    private String contentHash;
    private String mode;
    private String metricId;
    private String entityId;
    private String sqlText;
    /** JSON 数组文本（原始 JSONB::text），由调用方解析，DO 不感知序列化框架。 */
    private String bindValues;
    private String joins;
    private Integer appliedLimit;
    private String planStatus;
    private Double estimatedCost;
    private Long estimatedRows;
    private String planJson;
    /** 是否经 dry-run 真实执行校验；回放端点据此还原 verified 状态。 */
    private boolean verified;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

}

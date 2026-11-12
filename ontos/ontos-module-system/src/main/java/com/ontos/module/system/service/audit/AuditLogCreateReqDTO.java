package com.ontos.module.system.service.audit;

import java.math.BigDecimal;

import lombok.Data;

/**
 * 审计写入 DTO。调用方（query 模块）负责填充，system 模块不反向依赖 query 类型。
 *
 * <p>扩展列（V15 / §12.1）全部可空：自由问数与未命中资产的审计行不写这些列。
 */
@Data
public class AuditLogCreateReqDTO {

    private String requestId;
    private String parentRequestId;
    private String subIntentId;
    private String tenantId;
    private String userId;
    private String sessionId;
    private Integer turnIndex;
    private String manifestId;
    private String action;
    private String metricId;
    private String dslJson;
    private String sqlText;
    private Integer rowCount;
    private String reasonCode;
    /** 写入该审计行时的生效策略快照版本（M4/W1）；由 AuditService 统一解析填充，调用方不传。 */
    private String policyVersion;
    /** 关联审批单（敏感触发 / 令牌放行的审计行），W3/T3.2。 */
    private String approvalId;
    /** 敏感计数器哈希化增量（§4.8），与 sensitive_query_event.context_delta 同值同源。 */
    private String sensitiveCountersDelta;
    private String traceId;
    /** 状态迁移起点/终点与触发原因（action=STATE_TRANSITION 行，M1/T6.3）。 */
    private String fromState;
    private String toState;
    private String stateReason;
    /** 请求是否来自开发模拟身份（dev-headers 回退）；生产恒为 false。 */
    private Boolean devMode;

    // ---------- 扩展列（V15，「G-8 / §12.1」）----------

    /** 命中的 Scenario（未进入场景时为 null）。 */
    private String scenarioId;
    /** 命中 Scenario 的版本（清单内版本，非草稿版本）。 */
    private String scenarioVersion;
    /** manual_only / recommend_or_confirm / auto_if_confident。 */
    private String scenarioEntryMode;
    /** 六值：session_inherit / explicit / confirmed_recommendation / auto_confident / cold_start_selected / fallback_free_query。 */
    private String scenarioEntrySource;
    /** pending_confirm 是否由用户确认（伪造确认在入口被拒，不会落审计）。 */
    private Boolean scenarioConfirmByUser;
    /** 推荐/自动进入的置信度（0–1），仅推荐路径非空。 */
    private BigDecimal scenarioRecommendationConfidence;
    /** 本次执行使用的 Skill（未走 Skill 时为 null）。 */
    private String skillId;
    /** Skill 版本（与清单 skills 段一致）。 */
    private String skillVersion;
    /** 命中的 VQR（候选集外引用已被完整性闸门拦截）。 */
    private String vqrId;
    /** VQR 版本（与清单 vqrs 段一致）。 */
    private String vqrVersion;
    /** 近似降级的原模式（action=MODE_APPROXIMATED 时非空）。 */
    private String approximatedFromMode;

}

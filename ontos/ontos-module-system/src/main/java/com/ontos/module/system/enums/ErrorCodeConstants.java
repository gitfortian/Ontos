package com.ontos.module.system.enums;

import com.ontos.framework.common.exception.ErrorCode;

/**
 * system 模块错误码段：1_003_000_000 ~ 1_003_999_999。
 */
public interface ErrorCodeConstants {

    ErrorCode APPROVAL_NOT_FOUND = new ErrorCode(1_003_000_001, "审批单不存在或已被处理");
    ErrorCode APPROVAL_DECISION_INVALID = new ErrorCode(1_003_000_002, "审批结论只能是 APPROVED 或 REJECTED");
    ErrorCode POLICY_SNAPSHOT_MISSING = new ErrorCode(1_003_000_003, "租户没有生效的策略快照（fail-closed）: {}");
    ErrorCode POLICY_SNAPSHOT_INVALID = new ErrorCode(1_003_000_004, "策略快照内容非法，拒绝加载: {}");
    ErrorCode POLICY_ASSET_NOT_ALLOWED = new ErrorCode(1_003_000_005, "资产未授权（策略快照 allowlist，不区分未登记与无权限）: {}");
    ErrorCode POLICY_LABEL_INDIVIDUAL_REJECTED = new ErrorCode(1_003_000_006, "label 型判定结果不支持个体返回，可查看聚合分布或走审批: {}");
    ErrorCode POLICY_SNAPSHOT_VERSION_CONFLICT = new ErrorCode(1_003_000_007, "激活目标版本不存在或已被占用: {}");
    // §4.8.3 对外统一文案：所有触发分支（转审批/拒答）共用一个码，不泄露「哪一个条件先触发」；
    // 细分触发分支只进审计 reason_code（管理面），占位符为审批单 ID
    ErrorCode SENSITIVE_QUERY_SEQUENCE = new ErrorCode(1_003_000_008,
            "该查询涉及敏感资产的累计访问已达风控阈值，本次结果不予返回，请提交审批后重试（审批单: {}）");
    ErrorCode SENSITIVE_EVENT_INVALID = new ErrorCode(1_003_000_009, "敏感事件草稿非法: {}");
    ErrorCode APPROVAL_SELF_DECISION = new ErrorCode(1_003_000_010, "决策人不能审批自己发起的单据");
    ErrorCode APPROVAL_PAYLOAD_INVALID = new ErrorCode(1_003_000_011, "审批单 payload 缺少放行指纹所需字段");

}

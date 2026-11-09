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

}

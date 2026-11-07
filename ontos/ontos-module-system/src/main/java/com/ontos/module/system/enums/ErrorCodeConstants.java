package com.ontos.module.system.enums;

import com.ontos.framework.common.exception.ErrorCode;

/**
 * system 模块错误码段：1_003_000_000 ~ 1_003_999_999。
 */
public interface ErrorCodeConstants {

    ErrorCode APPROVAL_NOT_FOUND = new ErrorCode(1_003_000_001, "审批单不存在或已被处理");
    ErrorCode APPROVAL_DECISION_INVALID = new ErrorCode(1_003_000_002, "审批结论只能是 APPROVED 或 REJECTED");

}

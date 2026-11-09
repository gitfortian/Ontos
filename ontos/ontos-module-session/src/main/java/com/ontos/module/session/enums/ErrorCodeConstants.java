package com.ontos.module.session.enums;

import com.ontos.framework.common.exception.ErrorCode;

/**
 * session 模块错误码段：1_004_000_000 ~ 1_004_999_999。
 */
public interface ErrorCodeConstants {

    ErrorCode SESSION_TURN_CONFLICT = new ErrorCode(1_004_000_001,
            "会话轮次冲突（并发提交），请重试: {}");
    ErrorCode SESSION_CLARIFICATION_CHOICE_INVALID = new ErrorCode(1_004_000_002,
            "澄清选择无效（不属于本轮候选）: {}");

}

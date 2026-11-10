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
    ErrorCode SCENARIO_MANUAL_UNAUTHORIZED = new ErrorCode(1_004_000_003,
            "Scenario 不可用（未发布 / 无权限 / 已失效）: {}");
    ErrorCode SCENARIO_STATE_INVALID = new ErrorCode(1_004_000_004,
            "Scenario 状态迁移非法: {} -> {}");

}

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
    ErrorCode SCENARIO_MODE_FORBIDDEN = new ErrorCode(1_004_000_005,
            "分析模式不在场景允许范围内: {} 不允许 {}");
    ErrorCode SCENARIO_CALIBER_CONFLICT = new ErrorCode(1_004_000_006,
            "与场景冻结口径冲突: {} 需要 {}");
    ErrorCode SCENARIO_SCOPE_VIOLATION = new ErrorCode(1_004_000_007,
            "引用资产超出场景范围: {} 不在 {} 的 scope 内");

}

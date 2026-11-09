package com.ontos.module.agent.enums;

import com.ontos.framework.common.exception.ErrorCode;

/**
 * ask 编排错误码段：1_005_000_xxx。
 */
public interface AskErrorCodeConstants {

    ErrorCode ASK_RUN_CONFLICT = new ErrorCode(1_005_000_001,
            "请求冲突: {}");
    ErrorCode ASK_RUN_NOT_FOUND = new ErrorCode(1_005_000_002,
            "run 不存在: {}");
    ErrorCode ASK_RUN_STATE_ILLEGAL = new ErrorCode(1_005_000_003,
            "run 状态迁移非法: {}");
    ErrorCode ASK_PROPOSAL_REJECTED = new ErrorCode(1_005_000_004,
            "意图提案未通过完整性校验: {}");
    ErrorCode ASK_MODE_REJECTED = new ErrorCode(1_005_000_005,
            "分析模式不被该指标支持: {}");

}

package com.ontos.module.query.enums;

import com.ontos.framework.common.exception.ErrorCode;

/**
 * Query 模块错误码，占用 [1 002 000 000, 1 003 000 000)。
 */
public interface ErrorCodeConstants {

    ErrorCode DSL_INVALID = new ErrorCode(1_002_000_000, "查询 DSL 校验失败: {}");
    ErrorCode CROSS_TABLE_UNSUPPORTED = new ErrorCode(1_002_000_001, "当前编译器不支持跨表查询: {}");
    ErrorCode QUERY_TIMEOUT = new ErrorCode(1_002_000_002, "查询超时，请缩小时间范围");
    ErrorCode QUERY_EXECUTION_FAILED = new ErrorCode(1_002_000_003, "查询执行失败");
    ErrorCode QUERY_NOT_READ_ONLY = new ErrorCode(1_002_000_004, "仅允许只读查询");
    ErrorCode RESULT_LIMIT_EXCEEDED = new ErrorCode(1_002_000_005, "结果行数超过上限: {}");
    ErrorCode ANALYSIS_MODE_NOT_IMPLEMENTED = new ErrorCode(1_002_000_006, "分析模式尚未实现确定性编译，已拒绝执行: {}");

}

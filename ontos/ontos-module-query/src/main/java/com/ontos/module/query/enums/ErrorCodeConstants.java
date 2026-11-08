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
    ErrorCode REQUEST_ALREADY_EXECUTED = new ErrorCode(1_002_000_007, "该请求已执行过（request_id 幂等）: {}");
    ErrorCode TIME_PARSE_FAILED = new ErrorCode(1_002_000_008, "相对时间解析失败: {}");
    ErrorCode ABSOLUTE_DATE_REJECTED = new ErrorCode(1_002_000_009, "绝对日期被拒绝（LLM 不产出绝对日期）: {}");
    ErrorCode SENSITIVE_BULK_EXPORT_REJECTED = new ErrorCode(1_002_000_010, "敏感资产禁止无分组的大批量导出: {}");
    ErrorCode DSL_UNKNOWN_FIELD = new ErrorCode(1_002_000_011, "DSL 存在未知字段（fail-closed）: {}");
    ErrorCode PATH_AMBIGUOUS = new ErrorCode(1_002_000_012, "跨表路径存在并列候选，须澄清（禁止私自选择）: {}");
    ErrorCode PATH_UNREACHABLE = new ErrorCode(1_002_000_013, "跨表路径不可达（跳数/授权子图/业务对象）: {}");
    ErrorCode PATH_GRANULARITY_VIOLATION = new ErrorCode(1_002_000_014, "跨表路径存在粒度扩散，违反一行一事实: {}");

}

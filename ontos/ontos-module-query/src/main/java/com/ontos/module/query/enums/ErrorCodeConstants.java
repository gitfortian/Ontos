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
    ErrorCode LOOKUP_IDENTITY_REQUIRED = new ErrorCode(1_002_000_015, "lookup 缺少合法唯一标识: {}");
    ErrorCode LOOKUP_ATTRIBUTE_NOT_PROJECTABLE = new ErrorCode(1_002_000_016, "属性不可投影或不属于该实体: {}");
    ErrorCode LOOKUP_CROSS_TABLE_UNSUPPORTED = new ErrorCode(1_002_000_017, "lookup 投影属性跨表，M2 仅支持单表投影: {}");
    ErrorCode LOOKUP_NOT_UNIQUE = new ErrorCode(1_002_000_018, "标识属性命中多行，单实体 lookup 拒绝猜测: {}");
    ErrorCode LOOKUP_BATCH_LIMIT_EXCEEDED = new ErrorCode(1_002_000_019, "批量 lookup 超过单批上限或 limit 非法: {}");
    ErrorCode ATTRIBUTE_NOT_AVAILABLE = new ErrorCode(1_002_000_020, "属性存在性敏感且当前身份不可读: {}");
    ErrorCode FUNNEL_EVENT_MAPPING_MISSING = new ErrorCode(1_002_000_021, "funnel 事件缺少物理判定映射（ADR-0012）: {}");
    ErrorCode FUNNEL_EVENT_NOT_FOUND = new ErrorCode(1_002_000_022, "funnel 步骤找不到唯一事件定义: {}");
    ErrorCode FUNNEL_DIMENSION_UNSUPPORTED = new ErrorCode(1_002_000_023, "funnel 维度必须属于主体实体: {}");
    ErrorCode FUNNEL_TIME_WINDOW_MISSING = new ErrorCode(1_002_000_024, "funnel cohort 窗口缺失或未解析: {}");
    ErrorCode RULE_NOT_FOUND = new ErrorCode(1_002_000_025, "布尔指标引用的 Rule 未发布或不存在: {}");
    ErrorCode RULE_NOT_PUBLISHED = new ErrorCode(1_002_000_026, "Rule 状态不是 published，拒绝求值: {}");
    ErrorCode RULE_EXPRESSION_INVALID = new ErrorCode(1_002_000_027, "Rule 表达式三元组非法: {}");
    ErrorCode RULE_SUBJECT_PATH_NOT_UNIQUE = new ErrorCode(1_002_000_028, "规则主体与指标表的连接关系不唯一或缺失: {}");

}

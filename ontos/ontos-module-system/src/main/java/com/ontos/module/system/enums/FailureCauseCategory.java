package com.ontos.module.system.enums;

/**
 * 失败根因分类（M7/W4/T4.2，M7 规格 §3.6）：对齐 v1.4 §12.3 原因码族，闭词表 30 类。
 *
 * <p>只做分类，不改变失败样本的判定；分类事件只追加，当前分类由最新事件派生。
 * 新增类别只能追加（与错误码纪律一致），不得复用已发布 code。
 */
public enum FailureCauseCategory {

    TERM_MISSING("TERM_MISSING", "术语/口语别名缺失"),
    ENTITY_AMBIGUOUS("ENTITY_AMBIGUOUS", "实体歧义"),
    CALIBER_CONFLICT("CALIBER_CONFLICT", "指标口径冲突"),
    CALIBER_UNDEFINED("CALIBER_UNDEFINED", "口径未定义/变体不明"),
    SCENARIO_ENTRY_MISS("SCENARIO_ENTRY_MISS", "Scenario 入口未命中/误入"),
    SCENARIO_UNAUTHORIZED("SCENARIO_UNAUTHORIZED", "Scenario 无权/失效"),
    SCENARIO_SCOPE("SCENARIO_SCOPE", "Scenario scope 越界"),
    SCENARIO_CALIBER_OVERRIDE("SCENARIO_CALIBER_OVERRIDE", "frozen_caliber 覆盖尝试"),
    MODE_MISMATCH("MODE_MISMATCH", "Analysis Mode 识别错误"),
    SKILL_ROUTING("SKILL_ROUTING", "Skill 路由错误"),
    METRIC_MODE_NOT_ALLOWED("METRIC_MODE_NOT_ALLOWED", "指标与模式不匹配"),
    ATTRIBUTE_UNAVAILABLE("ATTRIBUTE_UNAVAILABLE", "属性不可用/存在性保护"),
    RULE_MISSING("RULE_MISSING", "Rule 未发布/依赖断裂"),
    CROSS_DOMAIN("CROSS_DOMAIN", "跨域组合"),
    SENSITIVE_SEQUENCE("SENSITIVE_SEQUENCE", "累计敏感触发"),
    PROMPT_INJECTION("PROMPT_INJECTION", "提示注入疑似"),
    OUT_OF_CAPABILITY("OUT_OF_CAPABILITY", "能力边界外"),
    INTENT_UNDISCOVERED("INTENT_UNDISCOVERED", "意图未发现"),
    TIME_UNRESOLVED("TIME_UNRESOLVED", "时间表达未解析"),
    MODE_APPROXIMATED("MODE_APPROXIMATED", "近似降级"),
    USER_REJECTED("USER_REJECTED", "用户纠正"),
    COMPOSITE_SUB_FAILED("COMPOSITE_SUB_FAILED", "复合子查询失败"),
    MULTI_TURN_LOST("MULTI_TURN_LOST", "多轮指代/上下文失败"),
    PARAM_MISSING("PARAM_MISSING", "参数缺失"),
    DSL_INVALID("DSL_INVALID", "DSL 非法"),
    PERMISSION_DENIED("PERMISSION_DENIED", "权限不足"),
    GRAIN_CONFLICT("GRAIN_CONFLICT", "Join 粒度冲突"),
    COMPILE_FAILED("COMPILE_FAILED", "SQL 编译失败"),
    DB_EXECUTION_FAILED("DB_EXECUTION_FAILED", "数据库执行失败"),
    RESULT_QUALITY("RESULT_QUALITY", "结果质量异常");

    private final String code;
    private final String label;

    FailureCauseCategory(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    /** 未知码返回 null（调用方 fail-closed 拒绝）。 */
    public static FailureCauseCategory fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (FailureCauseCategory category : values()) {
            if (category.code.equalsIgnoreCase(code.trim())) {
                return category;
            }
        }
        return null;
    }
}

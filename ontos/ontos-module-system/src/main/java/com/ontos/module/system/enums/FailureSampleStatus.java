package com.ontos.module.system.enums;

/**
 * 失败样本状态机（M7/W4/T4.1，M7 规格 §3.6）。
 *
 * <p>迁移规则（确定性、非法迁移拒绝）：
 * <pre>
 * OPEN      → TRIAGED
 * TRIAGED   → FIXED / IGNORED
 * FIXED     → TRIAGED（修复未通过，重新分诊）/ REGRESSED（仅回流 API）
 * REGRESSED → TRIAGED（回归失败重新打开）
 * IGNORED   → TRIAGED（重新评估）
 * </pre>
 * REGRESSED 只能由「退回回归集」通道写入，普通状态流转 API 不得直接置位。
 */
public enum FailureSampleStatus {

    OPEN("open"),
    TRIAGED("triaged"),
    FIXED("fixed"),
    REGRESSED("regressed"),
    IGNORED("ignored");

    private final String code;

    FailureSampleStatus(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** 未知码返回 null（调用方 fail-closed 拒绝，不静默落到 OPEN）。 */
    public static FailureSampleStatus fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (FailureSampleStatus status : values()) {
            if (status.code.equalsIgnoreCase(code.trim())) {
                return status;
            }
        }
        return null;
    }

    /** 普通状态流转是否合法（REGRESSED 目标由回流通道单独处理）。 */
    public boolean canTransitionTo(FailureSampleStatus target) {
        if (target == null) {
            return false;
        }
        return switch (this) {
            case OPEN -> target == TRIAGED;
            case TRIAGED -> target == FIXED || target == IGNORED;
            case FIXED -> target == TRIAGED || target == REGRESSED;
            case REGRESSED -> target == TRIAGED;
            case IGNORED -> target == TRIAGED;
        };
    }
}

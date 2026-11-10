package com.ontos.module.session.core.scenario;

import java.util.Set;

/**
 * Scenario 入口模式（v1.4 §5.7 三值，M6/W2/T2.3）：只控制入口推荐与自动生效策略，
 * 不改变确定性路由规则（ADR-0004 §2.1 闸门仍须三条全满足才启用）。
 */
public enum ScenarioEntryMode {

    /** 仅用户显式选择，不参与推荐。 */
    MANUAL_ONLY,
    /** 可推荐，需用户确认后生效。 */
    RECOMMEND_OR_CONFIRM,
    /** 唯一候选 + 高置信（确定性 hints 唯一命中）时自动生效。 */
    AUTO_IF_CONFIDENT;

    public static final Set<String> CODES =
            Set.of("manual_only", "recommend_or_confirm", "auto_if_confident");

    /** 清单/审计使用的稳定取值。 */
    public String code() {
        return name().toLowerCase();
    }

    /** 是否可被推荐（推荐后必须走 pending_confirm 确认）。 */
    public boolean recommendable() {
        return this == RECOMMEND_OR_CONFIRM || this == AUTO_IF_CONFIDENT;
    }

    /** 是否允许唯一命中时自动进入（不征询）。 */
    public boolean autoEnterable() {
        return this == AUTO_IF_CONFIDENT;
    }

    /** 反解；未知值拒绝（不猜测、不缺省）。 */
    public static ScenarioEntryMode fromCode(String code) {
        for (ScenarioEntryMode mode : values()) {
            if (mode.code().equalsIgnoreCase(code)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("未知 entryMode: " + code);
    }
}

package com.ontos.module.session.core.scenario;

import java.util.Map;
import java.util.Set;

/**
 * Scenario 会话状态机（M6/W2/T2.3，对齐 M4 run 状态机风格：迁移封闭枚举，先判定后转移）。
 *
 * <p>三态：{@code free}（自由问数）/ {@code in_scenario(id)}（场景内）/ {@code pending_confirm(id)}
 * （待确认：推荐确认或越界切场景/退出澄清）。跨会话不沿用（M5 既有口径）。
 */
public enum ScenarioRouteState {

    FREE,
    IN_SCENARIO,
    PENDING_CONFIRM;

    /**
     * 封闭迁移表：
     * <ul>
     *   <li>FREE → IN_SCENARIO（显式选择 / 冷启动选择 / auto 唯一置信命中）；</li>
     *   <li>FREE → PENDING_CONFIRM（recommend_or_confirm 推荐待确认）；</li>
     *   <li>IN_SCENARIO → IN_SCENARIO（显式切换到另一个已授权场景）；</li>
     *   <li>IN_SCENARIO → PENDING_CONFIRM（问题越界，澄清「切场景 / 退出」）；</li>
     *   <li>IN_SCENARIO → FREE（显式退出）；</li>
     *   <li>PENDING_CONFIRM → IN_SCENARIO（确认进入 / 切换）；</li>
     *   <li>PENDING_CONFIRM → FREE（确认退出 / 拒绝推荐）。</li>
     * </ul>
     * 未登记的迁移一律非法（含同名自环——FREE → FREE 不是迁移）。
     */
    private static final Map<ScenarioRouteState, Set<ScenarioRouteState>> ALLOWED = Map.of(
            FREE, Set.of(IN_SCENARIO, PENDING_CONFIRM),
            IN_SCENARIO, Set.of(IN_SCENARIO, PENDING_CONFIRM, FREE),
            PENDING_CONFIRM, Set.of(IN_SCENARIO, FREE));

    public boolean canTransitionTo(ScenarioRouteState target) {
        // M6 全量深度审核 D-14：不可变 Set.of(...).contains(null) 会抛 NPE——
        // 未知/空目标态必须结构化拒绝（fail-closed），不能变成 500。
        if (target == null) {
            return false;
        }
        return ALLOWED.getOrDefault(this, Set.of()).contains(target);
    }

    public String code() {
        return name().toLowerCase();
    }

    /** 文本状态反解；未知值拒绝。 */
    public static ScenarioRouteState fromCode(String code) {
        for (ScenarioRouteState state : values()) {
            if (state.code().equalsIgnoreCase(code)) {
                return state;
            }
        }
        throw new IllegalArgumentException("未知 Scenario 会话状态: " + code);
    }
}

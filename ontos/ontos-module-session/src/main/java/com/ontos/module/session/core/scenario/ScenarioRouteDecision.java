package com.ontos.module.session.core.scenario;

import java.util.List;

/**
 * Scenario 路由决策结果（M6/W2/T2.3）。决策是纯函数输出，不落库；
 * 状态迁移由 {@code ScenarioSessionService} 按封闭迁移表校验并持久化。
 */
public record ScenarioRouteDecision(Action action,
                                    ScenarioRouteState targetState,
                                    String scenarioId,
                                    ScenarioEntrySource source,
                                    String reasonCode,
                                    List<PendingChoice> candidates) {

    public enum Action {
        /** 进入 / 切换场景（显式选择、冷启动点选、auto 唯一置信命中）。 */
        ENTER_SCENARIO,
        /** 推荐待确认（FREE → pending_confirm）。 */
        ASK_CONFIRM,
        /** 场景内问题越界 → pending_confirm 澄清「切场景或退出」。 */
        CLARIFY_OUT_OF_SCOPE,
        /** 显式退出场景。 */
        EXIT_SCENARIO,
        /** 未命中或低置信 → 回退自由问数（提示可选入口）。 */
        FALLBACK_FREE,
        /** 显式提交的 scenarioId 未授权 / 未发布 / 已失效 → 拒绝，不静默回退。 */
        REJECT_UNAUTHORIZED,
        /** 无状态变化（场景内沿用 / 等待确认 / 非法退出请求）。 */
        STAY
    }

    /**
     * pending_confirm 候选（复用 M5 澄清回传校验：candidateId 必须属于候选集合，
     * 伪造选择被拒——ADR-0017 D3 / G-7）。
     */
    public record PendingChoice(String candidateId, String label, String assetId) {
    }

    public static final String REASON_UNAUTHORIZED = "SCENARIO_MANUAL_UNAUTHORIZED";
    public static final String REASON_LOW_CONFIDENCE = "SCENARIO_AUTO_LOW_CONFIDENCE_FALLBACK";
    /** pending_confirm 里「退出场景」候选的固定 ID。 */
    public static final String EXIT_CANDIDATE_ID = "scenario:__exit__";

    public ScenarioRouteDecision {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    /** 是否产生会话状态变化（需要持久化）。 */
    public boolean hasStateChange() {
        return action == Action.ENTER_SCENARIO || action == Action.ASK_CONFIRM
                || action == Action.EXIT_SCENARIO || action == Action.CLARIFY_OUT_OF_SCOPE;
    }
}

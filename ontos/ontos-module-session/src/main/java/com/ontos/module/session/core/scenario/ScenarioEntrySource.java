package com.ontos.module.session.core.scenario;

import java.util.Set;

/**
 * Scenario 入口来源（M6 规格 §12.1 六值）：进审计 `scenario_entry_source`。
 * 写入审计归 T2.6；本枚举先封闭口径，路由决策必须携带。
 */
public enum ScenarioEntrySource {

    /** 场景内沿用（问题未越界）。 */
    SESSION_INHERIT,
    /** 用户显式选择（请求 DTO 的 scenarioId）。 */
    EXPLICIT,
    /** 推荐后经 pending_confirm 确认生效。 */
    CONFIRMED_RECOMMENDATION,
    /** auto_if_confident 唯一命中自动进入。 */
    AUTO_CONFIDENT,
    /** 冷启动地图中点选（T2.5）。 */
    COLD_START_SELECTED,
    /** 自动推荐未命中/低置信，回退自由问数。 */
    FALLBACK_FREE_QUERY;

    public String code() {
        return name().toLowerCase();
    }

    public static ScenarioEntrySource fromCode(String code) {
        for (ScenarioEntrySource source : values()) {
            if (source.code().equalsIgnoreCase(code)) {
                return source;
            }
        }
        throw new IllegalArgumentException("未知 scenarioEntrySource: " + code);
    }

    public static final Set<String> CODES = Set.of(
            "session_inherit", "explicit", "confirmed_recommendation",
            "auto_confident", "cold_start_selected", "fallback_free_query");
}

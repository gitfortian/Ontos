package com.ontos.module.agent.service.orchestration;

import java.util.Locale;

/**
 * yoy_mom 周期推导（M7/W2 E-7，与 {@link TimeDimensionResolver} 同类的组装缺口修复）。
 *
 * <p>问题：`DslValidator` 要求 yoy_mom 必须带 `analysisParams.period`（封闭枚举 MONTH | YEAR），
 * 但 ask 的提案 Schema 没有 analysisParams 槽位，服务端组装也从不设置——任何 LLM 的 yoy_mom
 * 提案都会 `yoy_mom 缺少 analysisParams`。模型因此学会对同比问题输出 out_of_capability。
 *
 * <p>口径：从 `time_expression_raw` 的字面推导——含「季度」→ MONTH 口径不可表达 QUARTER
 * （QUARTER 归 M6），按 YEAR 处理并留待规格扩展；含「月」→ MONTH；其余（今年 / 去年 / 同比）
 * → YEAR。找不到时间表达时默认 YEAR（同比的最常见口径）。
 */
final class YoyPeriodResolver {

    /** 与 DslValidator.YOY_MOM_PERIODS 一致（MONTH | YEAR）。 */
    static final String PERIOD_MONTH = "MONTH";
    static final String PERIOD_YEAR = "YEAR";

    private YoyPeriodResolver() {
    }

    static String derive(String timeExpressionRaw) {
        String text = timeExpressionRaw == null ? "" : timeExpressionRaw.toLowerCase(Locale.ROOT);
        if (text.contains("月")) {
            return PERIOD_MONTH;
        }
        return PERIOD_YEAR;
    }
}

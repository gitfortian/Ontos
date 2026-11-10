package com.ontos.module.query.core.skill;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ontos.module.query.core.dsl.QueryDsl;
import org.springframework.stereotype.Component;

import static com.ontos.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.ontos.module.query.enums.ErrorCodeConstants.DSL_INVALID;

/**
 * {@code skill.query.approx}（M6/W3/T3.3）：聚合查询能力的注册化——**近似降级机制的首发样例**
 * （v1.4 §5.8 / M6 §3.3「首发注册 ≥1 条样例验证机制闭环」）。
 *
 * <p>槽位（与 ask 编排的提案槽位同词表）：metricId（必填）/ dimensions（可选）/
 * timeExpressionRaw（可选）/ limit（可选）。
 *
 * <p>清单 v15 中本 Skill 声明 {@code approximationOf=skill.funnel.standard}——场景内漏斗
 * 模式不可用（allowed_analysis_modes 不含 funnel）时，经用户同意（M5 澄清回传通道，
 * ADR-0017 D3）以本能力做聚合近似；审计记 MODE_APPROXIMATED + 原请求 mode。
 */
@Component
public class QuerySkill implements SkillHandler {

    public static final String SKILL_ID = "skill.query.approx";

    /** 槽位词表与 ask 提案槽位一致（snake_case）——近似执行的槽位直接来自提案；analysis_mode 随提案带入但近似固定 QUERY。 */
    private static final Set<String> SLOTS =
            Set.of("metric_id", "dimensions", "time_expression_raw", "limit", "analysis_mode");
    private static final Set<String> SLOTS_CAMEL =
            Set.of("metricId", "dimensions", "timeExpressionRaw", "limit");

    @Override
    public List<String> builtinRefs() {
        return List.of("builtin.QUERY.approx");
    }

    @Override
    public SkillDescriptor descriptor() {
        return new SkillDescriptor(SKILL_ID, "聚合查询（漏斗近似）",
                "以聚合聚合口径近似回答漏斗类问题（预声明近似，需用户同意）",
                List.of(
                        new SkillDescriptor.SkillSlot("metric_id", true, "指标（Metric ID）"),
                        new SkillDescriptor.SkillSlot("dimensions", false, "维度（Dimension ID 列表）"),
                        new SkillDescriptor.SkillSlot("time_expression_raw", false, "相对时间表达（原样，由服务端解析）"),
                        new SkillDescriptor.SkillSlot("limit", false, "行数上限")));
    }

    @Override
    public QueryDsl build(Map<String, Object> slots, String manifestId) {
        Map<String, Object> input = slots == null ? Map.of() : slots;
        for (String key : input.keySet()) {
            if (!SLOTS.contains(key) && !SLOTS_CAMEL.contains(key)) {
                throw exception(DSL_INVALID, "skill.query.approx 未知槽位: " + key);
            }
        }
        Object metricId = input.getOrDefault("metric_id", input.get("metricId"));
        if (!(metricId instanceof String metric) || metric.isBlank()) {
            throw exception(DSL_INVALID, "skill.query.approx 缺必填槽位: metric_id");
        }
        QueryDsl dsl = new QueryDsl();
        dsl.setManifestId(manifestId);
        dsl.setMetricId(metric);
        dsl.setMode(QueryDsl.AnalysisMode.QUERY);
        if (input.get("dimensions") instanceof List<?> dimensions) {
            dimensions.forEach(dimension -> {
                if (dimension instanceof String s && !s.isBlank()) {
                    dsl.getDimensions().add(s);
                }
            });
        }
        Object timeRaw = input.getOrDefault("time_expression_raw", input.get("timeExpressionRaw"));
        if (timeRaw instanceof String time && !time.isBlank()) {
            dsl.setTimeExpressionRaw(time);
        }
        if (input.get("limit") instanceof Number number) {
            dsl.setLimit(number.intValue());
        }
        return dsl;
    }
}

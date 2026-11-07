package com.ontos.framework.agent.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import com.ontos.framework.common.util.json.JsonUtils;

/**
 * 意图提案输出契约：对模型输出做程序化校验，是 G2 重开判定（ADR-0006）的修复本体。
 *
 * <p>契约来源是系统提示词（prompts/system.md）的「终态判定」表与一致性要求，此处不复制数值以外
 * 的叙述，只把可判定规则编码为校验：
 * <ul>
 *   <li>输出必须是单个 JSON 对象（容忍 ``` 围栏与首尾散文，剥除后解析）；</li>
 *   <li>{@code terminal} 必填且取值封闭：query / composite / discovery / clarification / out_of_capability；</li>
 *   <li>逐终态必填字段与互斥要求（如 query/composite 时 clarification 与 discovery 必须为空）；</li>
 *   <li>澄清四要素缺一不可；reason_code 取值封闭；query/composite 子意图数量约束；</li>
 *   <li>禁止 SQL 特征（硬性边界 1）；候选集 ID 白名单校验（硬性边界 2，候选集可用时）。</li>
 * </ul>
 *
 * <p>校验器是纯函数、不感知 Spring；候选集白名单通过 {@link Supplier} 注入，
 * 返回空集合表示当前无候选集，语义校验跳过（交给模型按提示词输出能力发现）。
 */
public final class IntentProposalContract {

    /** 校验违规：code 供修复反馈与日志归因，detail 给模型看的一句话修正指引。 */
    public record Violation(String code, String detail) {
        @Override
        public String toString() {
            return code + ": " + detail;
        }
    }

    public record ValidationResult(boolean valid, List<Violation> violations, Map<String, Object> parsed) {
    }

    static final Set<String> TERMINALS =
            Set.of("query", "composite", "discovery", "clarification", "out_of_capability");
    static final Set<String> REASON_CODES = Set.of("OUT_OF_MANIFEST", "NO_RULE", "OUT_OF_SCOPE");
    static final Set<String> ANALYSIS_MODES = Set.of(
            "query", "lookup", "lookup_batch", "funnel", "yoy_mom", "attribution", "retention", "discovery");
    private static final Set<String> CLARIFICATION_FIELDS =
            Set.of("ambiguity", "candidates", "default_suggestion", "degraded_behavior");

    /** 硬性边界 1 的机器可判版本：SQL 围栏、select…from 形状、写操作动词。 */
    private static final Pattern SQL_LEAK = Pattern.compile(
            "(?i)(```sql|\\bselect\\b[\\s\\S]{0,200}?\\bfrom\\b|\\binsert\\s+into\\b"
                    + "|\\bdelete\\s+from\\b|\\bupdate\\s+\\w+\\s+set\\b|\\bdrop\\s+table\\b)");

    private final Supplier<Set<String>> candidateIds;

    public IntentProposalContract(Supplier<Set<String>> candidateIds) {
        this.candidateIds = candidateIds == null ? Set::of : candidateIds;
    }

    public ValidationResult validate(String text) {
        List<Violation> violations = new ArrayList<>();
        Map<String, Object> parsed = parseObject(text, violations);
        if (parsed == null) {
            return new ValidationResult(false, violations, null);
        }
        if (SQL_LEAK.matcher(text).find()) {
            violations.add(new Violation("SQL_LEAK", "输出中出现 SQL 片段。你只做语义匹配，绝不输出 SQL、表名或列名。"));
        }

        String terminal = stringField(parsed, "terminal");
        if (terminal == null) {
            violations.add(new Violation("MISSING_TERMINAL",
                    "缺少 terminal 字段。必须显式输出 terminal ∈ [query, composite, discovery, clarification, out_of_capability]。"));
            return finish(violations, parsed);
        }
        if (!TERMINALS.contains(terminal)) {
            violations.add(new Violation("UNKNOWN_TERMINAL",
                    "terminal 值 \"" + terminal + "\" 不在封闭枚举内。"));
            return finish(violations, parsed);
        }

        checkTerminalConsistency(parsed, terminal, violations);
        checkSubIntents(parsed, terminal, violations);
        checkReasonCode(parsed, terminal, violations);
        checkCandidateIds(parsed, terminal, violations);
        return finish(violations, parsed);
    }

    private void checkTerminalConsistency(Map<String, Object> parsed, String terminal, List<Violation> violations) {
        boolean needsDiscovery = Boolean.TRUE.equals(parsed.get("needs_discovery"));
        Object clarification = parsed.get("clarification");
        Object discovery = parsed.get("discovery");
        boolean outOfCapability = Boolean.TRUE.equals(parsed.get("out_of_capability"));

        if (needsDiscovery && clarification != null) {
            violations.add(new Violation("TERMINAL_CONFLICT",
                    "needs_discovery=true 与 clarification 同时出现。命中即停：只允许一种终态。"));
        }
        switch (terminal) {
            case "query", "composite" -> {
                if (clarification != null) {
                    violations.add(new Violation("TERMINAL_CONFLICT",
                            "terminal=" + terminal + " 时 clarification 必须为 null。"));
                }
                if (needsDiscovery) {
                    violations.add(new Violation("TERMINAL_CONFLICT",
                            "terminal=" + terminal + " 时 needs_discovery 必须为 false。"));
                }
                if (discovery != null) {
                    violations.add(new Violation("TERMINAL_CONFLICT",
                            "terminal=" + terminal + " 时 discovery 必须为 null。"));
                }
            }
            case "discovery" -> {
                if (!needsDiscovery) {
                    violations.add(new Violation("TERMINAL_CONFLICT",
                            "terminal=discovery 时 needs_discovery 必须为 true。"));
                }
                if (discovery == null) {
                    violations.add(new Violation("MISSING_DISCOVERY",
                            "terminal=discovery 时必须给出 discovery 对象（metrics/dimensions/analysis_modes）。"));
                }
                if (clarification != null || outOfCapability) {
                    violations.add(new Violation("TERMINAL_CONFLICT",
                            "terminal=discovery 时不得同时表达 clarification 或 out_of_capability。"));
                }
            }
            case "clarification" -> {
                if (clarification == null) {
                    violations.add(new Violation("MISSING_CLARIFICATION",
                            "terminal=clarification 时必须给出 clarification 对象。"));
                } else if (clarification instanceof Map<?, ?> box) {
                    List<String> missing = CLARIFICATION_FIELDS.stream()
                            .filter(field -> !box.containsKey(field))
                            .toList();
                    if (!missing.isEmpty()) {
                        violations.add(new Violation("MISSING_CLARIFICATION",
                                "澄清四要素缺一不可，缺少: " + String.join(", ", missing) + "。不得只问「你指的是什么」。"));
                    }
                }
                if (needsDiscovery || discovery != null) {
                    violations.add(new Violation("TERMINAL_CONFLICT",
                            "terminal=clarification 时不得同时表达 discovery。"));
                }
            }
            case "out_of_capability" -> {
                if (!outOfCapability) {
                    violations.add(new Violation("TERMINAL_CONFLICT",
                            "terminal=out_of_capability 时 out_of_capability 必须为 true。"));
                }
            }
            default -> {
                // UNKNOWN_TERMINAL 已在上层记录
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void checkSubIntents(Map<String, Object> parsed, String terminal, List<Violation> violations) {
        if (!"query".equals(terminal) && !"composite".equals(terminal)) {
            return;
        }
        Object raw = parsed.get("sub_intents");
        if (!(raw instanceof List<?> subIntents) || subIntents.isEmpty()) {
            violations.add(new Violation("MISSING_SUB_INTENTS",
                    "terminal=" + terminal + " 时必须给出非空 sub_intents 数组。"));
            return;
        }
        if ("query".equals(terminal) && subIntents.size() != 1) {
            violations.add(new Violation("SUB_INTENT_COUNT",
                    "terminal=query 要求恰好 1 个子意图，实际 " + subIntents.size() + " 个；多个独立意图请用 composite。"));
        }
        for (Object item : subIntents) {
            if (!(item instanceof Map<?, ?> subIntent)) {
                violations.add(new Violation("MISSING_SUB_INTENTS", "sub_intents 元素必须是对象。"));
                continue;
            }
            String analysisMode = stringField((Map<String, Object>) subIntent, "analysis_mode");
            if (analysisMode != null && !ANALYSIS_MODES.contains(analysisMode.toLowerCase())) {
                violations.add(new Violation("UNKNOWN_ANALYSIS_MODE",
                        "analysis_mode \"" + analysisMode + "\" 不在封闭枚举内。"));
            }
        }
    }

    private void checkReasonCode(Map<String, Object> parsed, String terminal, List<Violation> violations) {
        boolean outOfCapability = Boolean.TRUE.equals(parsed.get("out_of_capability"));
        String reasonCode = stringField(parsed, "reason_code");
        if (outOfCapability) {
            if (reasonCode == null) {
                violations.add(new Violation("MISSING_REASON_CODE",
                        "out_of_capability=true 时必须给 reason_code ∈ [OUT_OF_MANIFEST, NO_RULE, OUT_OF_SCOPE]。"));
            } else if (!REASON_CODES.contains(reasonCode)) {
                violations.add(new Violation("UNKNOWN_REASON_CODE",
                        "reason_code \"" + reasonCode + "\" 不在封闭枚举内。"));
            }
            return;
        }
        // 「部分可做」例外：query/composite 允许 out_of_capability=true 与子意图并存，但必须有原因码；
        // 其余终态带 out_of_capability=true 视为终态冲突
        if (reasonCode != null && !"query".equals(terminal) && !"composite".equals(terminal)) {
            violations.add(new Violation("TERMINAL_CONFLICT",
                    "terminal=" + terminal + " 时不应输出 reason_code。"));
        }
    }

    private void checkCandidateIds(Map<String, Object> parsed, String terminal, List<Violation> violations) {
        Set<String> allowed = candidateIds.get();
        if (allowed.isEmpty() || !"query".equals(terminal) && !"composite".equals(terminal)) {
            return;
        }
        Object raw = parsed.get("sub_intents");
        if (!(raw instanceof List<?> subIntents)) {
            return;
        }
        for (Object item : subIntents) {
            if (!(item instanceof Map<?, ?> subIntent)) {
                continue;
            }
            String metricId = stringField((Map<String, Object>) subIntent, "metric_id");
            if (metricId != null && !allowed.contains(metricId)) {
                violations.add(new Violation("ID_NOT_IN_CANDIDATES",
                        "metric_id \"" + metricId + "\" 不在候选集内，视为不存在；请改用候选集内的 ID 或输出 out_of_capability=true。"));
            }
            Object dimensions = subIntent.get("dimensions");
            if (dimensions instanceof List<?> dimensionIds) {
                for (Object dimensionId : dimensionIds) {
                    if (dimensionId instanceof String id && !allowed.contains(id)) {
                        violations.add(new Violation("ID_NOT_IN_CANDIDATES",
                                "dimension \"" + id + "\" 不在候选集内，视为不存在。"));
                    }
                }
            }
        }
    }

    private static ValidationResult finish(List<Violation> violations, Map<String, Object> parsed) {
        return new ValidationResult(violations.isEmpty(), List.copyOf(violations), parsed);
    }

    /** 剥围栏与散文后解析为 JSON 对象；解析失败记录 NOT_JSON 并返回 null。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseObject(String text, List<Violation> violations) {
        String candidate = extractJson(text);
        if (candidate == null) {
            violations.add(new Violation("NOT_JSON",
                    "输出不是单个 JSON 对象。重新输出：只含一个 JSON 对象，不要 Markdown 代码块、不要解释文字。"));
            return null;
        }
        try {
            Object parsed = JsonUtils.parseObject(candidate, Object.class);
            if (parsed instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
        } catch (RuntimeException ignored) {
            // 落入下方统一 NOT_JSON
        }
        violations.add(new Violation("NOT_JSON",
                "输出无法解析为 JSON 对象。重新输出：只含一个 JSON 对象，不要 Markdown 代码块、不要解释文字。"));
        return null;
    }

    static String extractJson(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return text.substring(start, end + 1);
    }

    private static String stringField(Map<String, Object> map, String field) {
        Object value = map.get(field);
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    /** 把校验违规渲染为给模型的修正反馈（供 hook 注入 gotoReasoning）。 */
    public static String renderRepairFeedback(List<Violation> violations) {
        StringBuilder sb = new StringBuilder("你的上一条输出违反了意图提案契约，未下发给用户。逐条修正：\n");
        for (Violation violation : violations) {
            sb.append("- ").append(violation.toString()).append('\n');
        }
        sb.append("重新输出：只含一个 JSON 对象（不要 Markdown 代码块、不要解释文字），")
                .append("terminal 取封闭枚举值，其余字段按终态判定表给出。");
        return sb.toString();
    }

    /** 超出修复次数后的确定性兜底输出：结构化拒答，不让脏输出流向用户（fail-closed）。 */
    public static String rejectFallback() {
        Map<String, Object> reject = new LinkedHashMap<>();
        reject.put("scenario_id", null);
        reject.put("terminal", "out_of_capability");
        reject.put("sub_intents", List.of());
        reject.put("needs_discovery", false);
        reject.put("discovery", null);
        reject.put("clarification", null);
        reject.put("out_of_capability", true);
        reject.put("reason_code", "OUT_OF_SCOPE");
        // TODO(M1/T2.5): 对外文案改接 copy_template；当前 JSON 是机器可读协议输出，不经终端文案渲染
        return JsonUtils.toJsonString(reject);
    }

}

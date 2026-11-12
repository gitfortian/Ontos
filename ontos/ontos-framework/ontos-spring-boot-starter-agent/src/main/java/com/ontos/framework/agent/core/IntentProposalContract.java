package com.ontos.framework.agent.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import com.ontos.framework.common.agent.CandidateAsset;
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

    /**
     * 模型输入里会话投影的展示前缀（由编排层拼进 user 消息）。
     *
     * <p>只用于让模型识别投影块，<b>不得作为校验依据</b>：用户话术里同样可能出现该字符串。
     * 「本轮是否注入投影」走 {@link #validate(String, String, boolean)} 的显式入参。
     */
    public static final String SESSION_PROJECTION_MARKER = "上一轮已采纳会话投影";

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

    private final Supplier<List<CandidateAsset>> vocabularySupplier;
    private final java.util.function.Function<String, List<CandidateAsset>> questionVocabularySupplier;
    private final java.util.function.Predicate<String> projectionInjected;

    public IntentProposalContract(Supplier<List<CandidateAsset>> vocabularySupplier) {
        this.vocabularySupplier = vocabularySupplier == null ? List::of : vocabularySupplier;
        this.questionVocabularySupplier = question -> this.vocabularySupplier.get();
        this.projectionInjected = question -> false;
    }

    /** 问题感知词表：修复轮运行在异步线程时，实现方可用 userQuestion 定位本轮候选。 */
    public static IntentProposalContract questionAware(
            java.util.function.Function<String, List<CandidateAsset>> vocabularySupplier) {
        return questionAware(vocabularySupplier, question -> false);
    }

    /**
     * 问题感知词表 + 投影注入判定。
     *
     * <p>「本轮输入是否由服务端注入了会话投影」只能由服务端状态回答（本轮确实 prime 了上一轮
     * 已执行 DSL 的语义槽位），不能靠提示词文本特征推断——用户话术同样可能包含标记字符串。
     */
    public static IntentProposalContract questionAware(
            java.util.function.Function<String, List<CandidateAsset>> vocabularySupplier,
            java.util.function.Predicate<String> projectionInjected) {
        return new IntentProposalContract(vocabularySupplier, projectionInjected);
    }

    private IntentProposalContract(java.util.function.Function<String, List<CandidateAsset>> questionSupplier,
                                   java.util.function.Predicate<String> projectionInjected) {
        this.vocabularySupplier = null;
        this.questionVocabularySupplier = questionSupplier == null ? question -> List.of() : questionSupplier;
        this.projectionInjected = projectionInjected == null ? question -> false : projectionInjected;
    }

    /** 无用户问题上下文的校验（单测与兼容入口）：跳过「点名资产」判定，其余规则不变。 */
    public ValidationResult validate(String text) {
        return validate(text, null);
    }

    public ValidationResult validate(String text, String userQuestion) {
        return validate(text, userQuestion,
                userQuestion != null && projectionInjected.test(userQuestion));
    }

    /**
     * 显式声明本轮输入是否携带服务端注入的会话投影。
     *
     * <p>多轮增量话术（如「那上个月呢」）本身不点名任何候选资产，投影继承是服务端行为，
     * 因此由调用方（编排层）直接传值，而不是从 userQuestion 文本里找标记——标记文本对用户可见可伪造。
     */
    public ValidationResult validate(String text, String userQuestion, boolean projectionInjected) {
        List<CandidateAsset> vocabulary = userQuestion == null
                ? (vocabularySupplier == null ? List.of() : vocabularySupplier.get())
                : questionVocabularySupplier.apply(userQuestion);
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
        checkAssetMentioned(parsed, terminal, userQuestion, vocabulary, projectionInjected, violations);
        if (!TERMINALS.contains(terminal)) {
            violations.add(new Violation("UNKNOWN_TERMINAL",
                    "terminal 值 \"" + terminal + "\" 不在封闭枚举内。"));
            return finish(violations, parsed);
        }

        checkTerminalConsistency(parsed, terminal, violations);
        checkSubIntents(parsed, terminal, violations);
        checkReasonCode(parsed, terminal, violations);
        checkCandidateIds(parsed, terminal, vocabulary, violations);
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
                } else if (discovery instanceof Map<?, ?> box) {
                    List<String> missing = java.util.stream.Stream.of("metrics", "dimensions", "analysis_modes")
                            .filter(key -> !box.containsKey(key))
                            .toList();
                    if (!missing.isEmpty()) {
                        violations.add(new Violation("MISSING_DISCOVERY",
                                "discovery 对象三键必须同时给出（无内容给空数组），缺少: " + String.join(", ", missing) + "。"));
                    }
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

    private void checkCandidateIds(Map<String, Object> parsed, String terminal,
                                   List<CandidateAsset> vocabulary, List<Violation> violations) {
        Set<String> allowed = candidateAssetIds(vocabulary);
        if (allowed.isEmpty()) {
            return;
        }
        if ("query".equals(terminal) || "composite".equals(terminal)) {
            checkSubIntentIds(parsed, allowed, violations);
        }
        if ("discovery".equals(terminal) && parsed.get("discovery") instanceof Map<?, ?> discovery) {
            checkWhitelistedIds(discovery.get("metrics"), "metric_id", allowed, violations);
            checkWhitelistedIds(discovery.get("dimensions"), "dimension_id", allowed, violations);
        }
    }

    /** 候选资产 ID 全集（硬性边界 2 的白名单），由当轮词表派生。 */
    private Set<String> candidateAssetIds(List<CandidateAsset> vocabulary) {
        return (vocabulary == null ? List.<CandidateAsset>of() : vocabulary).stream()
                .map(CandidateAsset::assetId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** 暴露给编排层完整性对账：与当前 validate 使用同一份问题感知授权词表。 */
    public Set<String> candidateAssetIds(String userQuestion) {
        List<CandidateAsset> vocabulary = userQuestion == null
                ? (vocabularySupplier == null ? List.of() : vocabularySupplier.get())
                : questionVocabularySupplier.apply(userQuestion);
        return candidateAssetIds(vocabulary);
    }

    /**
     * 机械闸门（检索瀑布第一级的前置）：执行类终态（query/composite）要求用户问题
     * 点名了至少一个候选资产的名称或别名——「泛化问法必须澄清」由此从模型判断变成字符串匹配。
     * 问题为空（单测/兼容入口）或词表为空时跳过。
     */
    private void checkAssetMentioned(Map<String, Object> parsed, String terminal,
                                     String userQuestion, List<CandidateAsset> vocabulary,
                                     boolean projectionInjected, List<Violation> violations) {
        if (userQuestion == null || userQuestion.isBlank() || vocabulary.isEmpty()) {
            return;
        }
        if (!"query".equals(terminal) && !"composite".equals(terminal)) {
            return;
        }
        String normalizedQuestion = userQuestion.toLowerCase();
        // 多轮投影由服务端注入：上一轮语义槽位已在投影里，增量话术常常不再点名资产。
        // 是否注入只认服务端状态（primed 投影非空），不做文本匹配——标记字符串用户也能写出来。
        if (projectionInjected) {
            return;
        }
        String analysisMode = firstAnalysisMode(parsed);
        // LOOKUP / LOOKUP_BATCH 由身份值驱动；用户可能只给 c-1001，不点名 metric/dimension。
        // FUNNEL / RETENTION 事件词也不是资产词。这四类不能套用聚合问法的 ASSET_NOT_MENTIONED。
        if (analysisMode != null
                && Set.of("lookup", "lookup_batch", "funnel", "retention").contains(analysisMode)) {
            return;
        }
        boolean mentioned = vocabulary.stream()
                .anyMatch(asset -> asset.terms().stream()
                        .anyMatch(term -> normalizedQuestion.contains(term.toLowerCase())));
        if (!mentioned) {
            violations.add(new Violation("ASSET_NOT_MENTIONED",
                    "用户问题没有提及任何候选指标或维度（如「最近业绩怎么样」「看看销售情况」）。"
                            + "必须输出 terminal=clarification，在候选中列出可用指标让用户选择，禁止自行猜测指标执行。"));
        }
    }

    /** 读取首个子意图的分析模式；无子意图 / 非法形态时返回 null。 */
    private static String firstAnalysisMode(Map<String, Object> parsed) {
        if (!(parsed.get("sub_intents") instanceof List<?> subIntents) || subIntents.isEmpty()
                || !(subIntents.get(0) instanceof Map<?, ?> box)) {
            return null;
        }
        return stringField((Map<String, Object>) box, "analysis_mode");
    }

    @SuppressWarnings("unchecked")
    private void checkSubIntentIds(Map<String, Object> parsed, Set<String> allowed, List<Violation> violations) {
        Object raw = parsed.get("sub_intents");
        if (!(raw instanceof List<?> subIntents)) {
            return;
        }
        for (Object item : subIntents) {
            if (!(item instanceof Map<?, ?> subIntent)) {
                continue;
            }
            Map<String, Object> box = (Map<String, Object>) subIntent;
            checkWhitelistedId(stringField(box, "metric_id"), "metric_id", allowed, violations);
            // ADR-0023 v1.1：布尔判定指标也是候选资产，防止伪造 booleanMetricId 绕过词表。
            checkWhitelistedId(stringField(box, "boolean_metric_id"), "boolean_metric_id",
                    allowed, violations);
            checkWhitelistedIds(box.get("dimensions"), null, allowed, violations);
            // LOOKUP 属性投影同样是候选资产；防止伪造 attributes 绕过授权词表。
            checkWhitelistedIds(box.get("attributes"), null, allowed, violations);
            // 硬性边界 2：filters 的字段同样是候选集维度，不得引用白名单之外的 ID
            if (box.get("filters") instanceof List<?> filters) {
                for (Object filter : filters) {
                    if (filter instanceof Map<?, ?> filterBox) {
                        checkWhitelistedId(stringField((Map<String, Object>) filterBox, "field_id"),
                                "filters[].field_id", allowed, violations);
                    }
                }
            }
        }
    }

    /** 列表元素两种形态都支持：字符串 ID（dimensions）或对象（discovery.metrics 的 {"metric_id":...}）。 */
    private void checkWhitelistedIds(Object ids, String label, Set<String> allowed, List<Violation> violations) {
        if (!(ids instanceof List<?> idList)) {
            return;
        }
        for (Object element : idList) {
            if (element instanceof Map<?, ?> box && label != null) {
                checkWhitelistedId(stringField((Map<String, Object>) box, label), label, allowed, violations);
            } else if (element instanceof String s) {
                checkWhitelistedId(s, label, allowed, violations);
            }
        }
    }

    private void checkWhitelistedId(String id, String label, Set<String> allowed, List<Violation> violations) {
        if (id != null && !allowed.contains(id)) {
            String field = label == null ? "dimension" : label;
            violations.add(new Violation("ID_NOT_IN_CANDIDATES",
                    field + " \"" + id + "\" 不在候选集内，视为不存在；请改用候选集内的 ID 或输出 out_of_capability=true。"));
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

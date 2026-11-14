package com.ontos.module.agent.service.terminal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ontos.framework.web.core.context.RequestContext;
import com.ontos.module.agent.service.event.AskEventService;
import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.ontology.core.SemanticManifest;
import com.ontos.module.ontology.service.manifest.ManifestService;
import com.ontos.module.query.service.copy.CopyTemplateService;
import com.ontos.module.session.service.retrieval.ClarificationPayload;
import com.ontos.module.session.service.retrieval.RetrievedCandidate;
import com.ontos.module.session.service.session.SessionStateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 结构化终态装配器（M5/T4.1，规格 §3.4）：五类终态 + composite 透传的载荷契约统一定点。
 *
 * <p>终态经 {@code CUSTOM} 事件下发（{@code name=ontos.terminal}），载荷结构在此冻结：
 * {@code terminal / requestId / planRef / reasonCode / copy / body}——
 * 原因码文案一律经 {@link CopyTemplateService} 渲染（M3 白名单占位符 + GENERIC 兜底），
 * 禁止把原始码或占位符原文透给用户。
 *
 * <p>clarification 终态同时把候选持久化到会话状态（R5：下一轮回传的 candidateId
 * 必须属于该集合，伪造选择原子拒绝）。
 */
@Slf4j
@Service
public class TerminalAssembler {

    /** CUSTOM 事件名：终态载荷的唯一下发形态。 */
    public static final String TERMINAL_EVENT_NAME = "ontos.terminal";

    private final CopyTemplateService copyTemplateService;
    private final SessionStateService sessionStateService;
    private final AssetLabelResolver assetLabelResolver;
    private final ManifestService manifestService;

    public TerminalAssembler(CopyTemplateService copyTemplateService,
                             SessionStateService sessionStateService,
                             AssetLabelResolver assetLabelResolver,
                             ManifestService manifestService) {
        this.copyTemplateService = copyTemplateService;
        this.sessionStateService = sessionStateService;
        this.assetLabelResolver = assetLabelResolver;
        this.manifestService = manifestService;
    }

    /**
     * 结果列元数据（前端表格表头 / 图表轴 / 数值右对齐的唯一来源）。
     *
     * <p>{@code key} 只用于取值（形如 {@code dim.region}、{@code value}），**不上屏**；
     * 展示一律用 {@code label}（清单里的业务名）。role 让前端决定图表轴与对齐方式，
     * 不靠猜列名。
     */
    public record Column(String key, String label, String role, boolean numeric) {
    }

    /** 结果统计（行数 / 是否截断 / 耗时）：页面直接展示，不用前端反推。 */
    public record QueryStats(int rowCount, boolean truncated, long elapsedMs) {
    }

    /** 复用（回答）终态：结果 + 列元数据 + 编译产物回放引用（/plan/{requestId}）。 */
    public Map<String, Object> query(String requestId, QueryStats stats, List<?> rows,
                                     List<Column> columns, boolean degraded) {
        Map<String, Object> terminal = base("query", requestId);
        terminal.put("planRef", "/api/v1/query/plan/" + requestId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rowCount", stats == null ? 0 : stats.rowCount());
        body.put("rows", rows == null ? List.of() : rows);
        body.put("columns", columns == null ? List.of() : columns);
        if (stats != null) {
            body.put("truncated", stats.truncated());
            body.put("elapsedMs", stats.elapsedMs());
        }
        terminal.put("body", body);
        terminal.put("degraded", degraded);
        return terminal;
    }

    /** composite 终态：透传 M2 复合通道（不拆分；PoC #8 归 M6）。 */
    public Map<String, Object> composite(String requestId, Object subResults, boolean degraded) {
        Map<String, Object> terminal = base("composite", requestId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("subResults", subResults == null ? List.of() : subResults);
        terminal.put("body", body);
        terminal.put("degraded", degraded);
        return terminal;
    }

    /**
     * 澄清终态：四要素 + 候选持久化（R5 校验的数据基础）。
     *
     * <p>O-2：确定性降级也可能落到澄清（如模型调用超时），必须把 {@code degraded} 透出去——
     * 否则与「模型主动澄清」在对外响应里无法区分，评测侧会把降级路径误判成模型行为。
     */
    public Map<String, Object> clarification(RequestContext context, ClarificationPayload payload,
                                             boolean degraded) {
        Map<String, Object> terminal = base("clarification", context.traceId());
        Map<String, Object> body = new LinkedHashMap<>();
        if (payload == null) {
            body.put("ambiguity", "未识别到具体指标");
            body.put("candidates", List.of());
            body.put("defaultSuggestion", null);
            body.put("skippable", true);
            // 空候选澄清（LLM 对无命中话术的兜底）同样给真实示例，避免「没有任何提示」感
            body.put("suggestions", buildSuggestions());
        } else {
            body.put("ambiguity", payload.mention());
            // 候选给 label（资产业务名）：matchedTerm 是检索词（「订单收」这类用户叫法），
            // 同名不同资产时用户无法分辨——展示名与回传值分离（A.4：ID 不上屏）。
            // 同一资产的多个同义词命中合并为一张卡（检索词表一物多名是常态，不去重会
            // 出现 5 张「订单数」卡片）；其余叫法进 aliases。
            java.util.LinkedHashMap<String, Map<String, Object>> merged = new java.util.LinkedHashMap<>();
            for (RetrievedCandidate candidate : payload.candidates()) {
                Map<String, Object> box = merged.computeIfAbsent(candidate.assetId(), id -> {
                    Map<String, Object> created = new LinkedHashMap<>();
                    created.put("assetId", id);
                    created.put("assetType", candidate.assetType());
                    created.put("matchedTerm", candidate.matchedTerm());
                    created.put("label", assetLabelResolver.label(id));
                    created.put("aliases", new ArrayList<String>());
                    return created;
                });
                @SuppressWarnings("unchecked")
                List<String> aliases = (List<String>) box.get("aliases");
                if (!candidate.matchedTerm().equals(box.get("matchedTerm"))
                        && !aliases.contains(candidate.matchedTerm())) {
                    aliases.add(candidate.matchedTerm());
                }
            }
            body.put("candidates", List.copyOf(merged.values()));
            // defaultSuggestion 可能是资产 ID（确定性规划器传 assetId）也可能是文案：
            // 形如 ID 时翻成业务名，绝不把 ID 透给用户。
            body.put("defaultSuggestion", suggestable(payload.defaultSuggestion()));
            body.put("skippable", payload.skippable());
            // R5：候选持久化——下一轮 clarificationChoice 的合法性以此集合为准
            persistClarification(context, payload);
        }
        terminal.put("body", body);
        terminal.put("degraded", degraded);
        return terminal;
    }

    /** discovery 终态：授权 scope 内能力清单（已发布引用，不由 LLM 生成）。展示用业务名。 */
    public Map<String, Object> discovery(List<RetrievedCandidate> metrics,
                                         List<RetrievedCandidate> dimensions) {
        Map<String, Object> terminal = base("discovery", null);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("metrics", metrics == null ? List.of() : metrics.stream()
                .map(candidate -> Map.of("assetId", candidate.assetId(),
                        "matchedTerm", candidate.matchedTerm(),
                        "label", assetLabelResolver.label(candidate.assetId())))
                .toList());
        body.put("dimensions", dimensions == null ? List.of() : dimensions.stream()
                .map(candidate -> Map.of("assetId", candidate.assetId(),
                        "matchedTerm", candidate.matchedTerm(),
                        "label", assetLabelResolver.label(candidate.assetId())))
                .toList());
        terminal.put("body", body);
        return terminal;
    }

    /** 拒答/引导终态：原因码 + 模板文案（GENERIC 兜底，不透原文）。 */
    public Map<String, Object> outOfCapability(RequestContext context, String reasonCode) {
        Map<String, Object> terminal = base("out_of_capability", context.traceId());
        terminal.put("reasonCode", reasonCode);
        terminal.put("copy", renderCopy(reasonCode, Map.of()));
        // W6.1 无命中友好引导：寒暄/超范围时下发真实可问的示例（指标×维度业务名拼装），
        // 前端渲染为可点击建议——「直接结束没有任何提示」的修复。
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("suggestions", buildSuggestions());
        terminal.put("body", body);
        return terminal;
    }

    /**
     * 真实可问的示例问题（引导用）：从生效清单拼「近 30 天各{维度}的{指标}」——
     * 全部业务名、时间词走已发布模式（tep.last_n_days），不编造资产。
     * ruleId 型布尔指标跳过（不能进 QUERY 示例）。
     */
    private List<String> buildSuggestions() {
        PublishedManifest manifest = manifestService.getActiveManifestOrNull();
        if (manifest == null || manifest.getSemantic() == null) {
            return List.of();
        }
        List<SemanticManifest.Dimension> dimensions = manifest.getSemantic().getDimensions();
        List<String> suggestions = new ArrayList<>();
        for (SemanticManifest.Metric metric : manifest.getSemantic().getMetrics()) {
            if (suggestions.size() >= 3) {
                break;
            }
            String metricName = metric.getName();
            if (metricName == null || metricName.isBlank() || metric.getRuleId() != null) {
                continue;
            }
            SemanticManifest.Dimension dimension = dimensions.isEmpty()
                    ? null : dimensions.get(suggestions.size() % dimensions.size());
            if (dimension != null && dimension.getName() != null && !dimension.getName().isBlank()) {
                suggestions.add("近 30 天各" + dimension.getName() + "的" + metricName + "是多少");
            } else {
                suggestions.add("近 30 天" + metricName + "是多少");
            }
        }
        return suggestions;
    }

    /** HITL 等待中间态：run 已进入 WAITING_APPROVAL，终态经事件通道跟踪。 */
    public Map<String, Object> waiting(String runId, String approvalId) {
        Map<String, Object> terminal = base("waiting", runId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", runId);
        body.put("approvalId", approvalId);
        body.put("hint", "审批等待中：完成决策后经 GET /api/v1/ask/run/{runId}/events 获取终态");
        terminal.put("body", body);
        return terminal;
    }

    /** 敏感序列收尾终态（审批拒绝 / TTL 到期）。 */
    public Map<String, Object> sensitiveClosed(RequestContext context, String reasonCode,
                                               String approvalId) {
        Map<String, Object> terminal = base("out_of_capability", context.traceId());
        terminal.put("reasonCode", reasonCode);
        terminal.put("copy", renderCopy(reasonCode, approvalId == null
                ? Map.of() : Map.of("approval_id", approvalId)));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("approvalId", approvalId);
        terminal.put("body", body);
        return terminal;
    }

    /** 事件通道下发（CUSTOM ontos.terminal）：终态即事件，重放端可还原。 */
    public void emitTerminal(AskEventService eventService, String runId, Map<String, Object> terminal) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("name", TERMINAL_EVENT_NAME);
        event.put("value", terminal);
        eventService.emit(runId, AskEventService.CUSTOM, event);
    }

    /** 默认建议：形如资产 ID 时翻业务名，其余（真实文案）原样。 */
    private String suggestable(String suggestion) {
        if (suggestion == null || suggestion.isBlank()) {
            return null;
        }
        return assetLabelResolver.looksLikeAssetId(suggestion)
                ? assetLabelResolver.label(suggestion) : suggestion;
    }

    private Map<String, Object> base(String terminal, String requestId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("terminal", terminal);
        if (requestId != null) {
            payload.put("requestId", requestId);
        }
        return payload;
    }

    private Map<String, Object> renderCopy(String reasonCode, Map<String, Object> placeholders) {
        try {
            CopyTemplateService.RenderedCopy rendered =
                    copyTemplateService.render(reasonCode, null, placeholders);
            Map<String, Object> copy = new LinkedHashMap<>();
            copy.put("text", rendered.text());
            copy.put("templateId", rendered.templateId());
            return copy;
        } catch (RuntimeException ex) {
            // 文案渲染失败不阻断终态：GENERIC 兜底，仍失败则如实标注（不透 {x} 原文）
            log.warn("[renderCopy][文案渲染失败（{}），GENERIC 兜底]", ex.toString());
            Map<String, Object> copy = new LinkedHashMap<>();
            copy.put("text", "这次没能完成查询，可以换个说法或缩小范围后再试。");
            copy.put("templateId", "GENERIC_FALLBACK");
            copy.put("fallback", true);
            return copy;
        }
    }

    private void persistClarification(RequestContext context, ClarificationPayload payload) {
        try {
            // T32-8：场景确认/近似同意候选由场景通道持久化（PendingChoice.assetId=裸场景 ID，
            // 回传键=candidateId 前缀格式）——透出层候选把 candidateId 放在 assetId 槽，
            // 若在此覆盖会把回传键写坏（confirm 校验 scenariosById.get(assetId) 必然 miss）
            List<SessionStateService.ClarificationCandidate> candidates = payload.candidates().stream()
                    .filter(candidate -> !"scenario".equals(candidate.assetType())
                            && !"approximation".equals(candidate.assetType()))
                    .map(candidate -> new SessionStateService.ClarificationCandidate(
                            candidate.assetId(), candidate.matchedTerm(), candidate.assetId()))
                    .toList();
            if (candidates.isEmpty()) {
                // T32-8：本轮候选全部是场景/近似类（场景通道已持久化）——跳过覆盖，
                // 空候选覆盖会把场景通道写入的正确候选抹成 NULL
                return;
            }
            sessionStateService.savePendingClarification(context,
                    new SessionStateService.PendingClarification(
                            payload.mention(), candidates, payload.defaultSuggestion(),
                            "skip_and_discover"));
        } catch (RuntimeException ex) {
            // 持久化失败不阻断澄清终态：下一轮回传会被 R5 校验拒绝（fail-closed 方向正确）
            log.warn("[persistClarification][候选持久化失败: {}]", ex.toString());
        }
    }

}

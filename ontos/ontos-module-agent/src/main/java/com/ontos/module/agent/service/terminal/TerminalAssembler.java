package com.ontos.module.agent.service.terminal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ontos.framework.web.core.context.RequestContext;
import com.ontos.module.agent.service.event.AskEventService;
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

    public TerminalAssembler(CopyTemplateService copyTemplateService,
                             SessionStateService sessionStateService) {
        this.copyTemplateService = copyTemplateService;
        this.sessionStateService = sessionStateService;
    }

    /** 复用（回答）终态：结果 + 编译产物回放引用（/plan/{requestId}）。 */
    public Map<String, Object> query(String requestId, int rowCount, List<?> rows, boolean degraded) {
        Map<String, Object> terminal = base("query", requestId);
        terminal.put("planRef", "/api/v1/query/plan/" + requestId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rowCount", rowCount);
        body.put("rows", rows == null ? List.of() : rows);
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
        } else {
            body.put("ambiguity", payload.mention());
            body.put("candidates", payload.candidates().stream()
                    .map(candidate -> Map.of("assetId", candidate.assetId(),
                            "assetType", candidate.assetType(), "matchedTerm", candidate.matchedTerm()))
                    .toList());
            body.put("defaultSuggestion", payload.defaultSuggestion());
            body.put("skippable", payload.skippable());
            // R5：候选持久化——下一轮 clarificationChoice 的合法性以此集合为准
            persistClarification(context, payload);
        }
        terminal.put("body", body);
        terminal.put("degraded", degraded);
        return terminal;
    }

    /** discovery 终态：授权 scope 内能力清单（已发布引用，不由 LLM 生成）。 */
    public Map<String, Object> discovery(List<RetrievedCandidate> metrics,
                                         List<RetrievedCandidate> dimensions) {
        Map<String, Object> terminal = base("discovery", null);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("metrics", metrics == null ? List.of() : metrics.stream()
                .map(candidate -> Map.of("assetId", candidate.assetId(),
                        "matchedTerm", candidate.matchedTerm()))
                .toList());
        body.put("dimensions", dimensions == null ? List.of() : dimensions.stream()
                .map(candidate -> Map.of("assetId", candidate.assetId(),
                        "matchedTerm", candidate.matchedTerm()))
                .toList());
        terminal.put("body", body);
        return terminal;
    }

    /** 拒答/引导终态：原因码 + 模板文案（GENERIC 兜底，不透原文）。 */
    public Map<String, Object> outOfCapability(RequestContext context, String reasonCode) {
        Map<String, Object> terminal = base("out_of_capability", context.traceId());
        terminal.put("reasonCode", reasonCode);
        terminal.put("copy", renderCopy(reasonCode, Map.of()));
        terminal.put("body", Map.of());
        return terminal;
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

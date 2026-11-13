package com.ontos.module.agent.controller.admin.agui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.ontos.framework.common.util.json.JsonUtils;
import com.ontos.framework.web.core.context.RequestContext;
import com.ontos.framework.web.core.context.RequestContextHolder;
import com.ontos.module.agent.service.orchestration.AskOrchestrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AG-UI 溯源网关（ADR-0027）：`/agent/ui/ontos`。
 *
 * <p>**只做协议形状转换，不做业务逻辑**——编排、闸门、审计全部复用
 * {@code AskOrchestrationService.executeTurnStreaming}（与 /api/v1/ask/** 同一条链路）；
 * 本类只把内嵌事件摊平成标准 AG-UI 扁平事件，使 CopilotKit Headless
 * （selfManagedAgents + HttpAgent）可以直连，恢复附录 A 主路线。
 *
 * <p>身份仍由网关注入的 {@code X-Ontos-*} 头（dev 模式默认 demo）——CopilotKit 不承载身份。
 * 客户端断开只停展示，编排照常执行到终态并落审计（与 /ask/runs/stream 一致）。
 *
 * <p>摊平规则（只动形状，不动语义）：事件类型名不变；内嵌 JSON 摊平为顶层字段；
 * {@code seq} 原样保留（前端归并键）；step / 工具摘要等扩展字段原样透传；
 * INTERRUPT / STATE 转 CUSTOM 下发（客户端按 name 路由，不新增六类之外的事件类型）。
 */
@Tag(name = "agui · 溯源网关")
@RestController
public class AguiGatewayController {

    private static final long STREAM_TIMEOUT_MILLIS = 5 * 60 * 1000L;

    private final AskOrchestrationService orchestrationService;

    public AguiGatewayController(AskOrchestrationService orchestrationService) {
        this.orchestrationService = orchestrationService;
    }

    @PostMapping(value = "/agent/ui/ontos", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "AG-UI 标准端点（CopilotKit 直连；协议形状转换，业务走编排主链路）")
    public SseEmitter run(@RequestBody AguiRunInput input) {
        RequestContext context = RequestContextHolder.require();
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        AtomicBoolean closed = new AtomicBoolean(false);
        AtomicBoolean anyEvent = new AtomicBoolean(false);

        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> {
            closed.set(true);
            completeQuietly(emitter);
        });
        emitter.onError(ex -> closed.set(true));

        String runId = input.runId() == null || input.runId().isBlank()
                ? java.util.UUID.randomUUID().toString()
                : input.runId();
        String threadId = input.threadId() == null || input.threadId().isBlank()
                ? context.sessionId()
                : input.threadId();

        Consumer<Map<String, Object>> live = event -> {
            if (closed.get()) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().data(event, MediaType.APPLICATION_JSON));
                anyEvent.set(true);
            } catch (Exception ex) {
                closed.set(true);
            }
        };

        // 协议生命周期补全：编排层只发 CONTENT/END 类事件，AG-UI 客户端要求
        // 文本消息有 START→CONTENT→END 配对——网关按需合成（仍是形状转换）。
        boolean[] messageStarted = {false};
        // 工具调用配对：START/END 的 seq 不同（持久化各自记一条），AG-UI 要求
        // END 的 toolCallId 与 START 一致——按 toolName 记住 START 的 seq。
        java.util.Map<String, Long> toolStartSeq = new LinkedHashMap<>();
        Consumer<Map<String, Object>> flattened = event -> {
            long seq = event.get("seq") instanceof Number number ? number.longValue() : 0L;
            String type = String.valueOf(event.get("type"));
            Object payload = event.get("payload");
            Map<String, Object> parsed = payload instanceof String text
                    ? parsePayload(text)
                    : (payload instanceof Map<?, ?> box ? castMap(box) : Map.of());
            boolean isToolStart = "TOOL_CALL_START".equals(type);
            boolean isToolEnd = "TOOL_CALL_END".equals(type);
            if (isToolStart) {
                String toolName = String.valueOf(parsed.get("toolName"));
                toolStartSeq.put(toolName, seq);
            }
            if (isToolEnd && parsed.get("toolName") != null) {
                seq = toolStartSeq.getOrDefault(String.valueOf(parsed.get("toolName")), seq);
            }
            // AG-UI 客户端 enforce 会剥离协议外的扩展字段（argsSummary/resultSummary/label/seq），
            // 工具的业务摘要必须走 CUSTOM 承载（{name,value} 是协议允许的形态）；
            // TOOL_CALL_* 只保留协议合规字段（toolCallId/toolCallName/args）供 CopilotKit 内部状态。
            if (isToolStart || isToolEnd) {
                live.accept(flatten(type, parsed, seq, runId, threadId));
                Map<String, Object> tool = new LinkedHashMap<>();
                tool.put("seq", seq);
                tool.put("type", "CUSTOM");
                tool.put("name", "ontos.tool");
                tool.put("value", Map.of(
                        "phase", isToolStart ? "start" : "end",
                        "toolName", parsed.get("toolName") == null ? "" : parsed.get("toolName"),
                        "argsSummary", parsed.getOrDefault("argsSummary", List.of()),
                        "resultSummary", parsed.getOrDefault("resultSummary", List.of()),
                        "status", parsed.getOrDefault("status", ""),
                        "elapsedMs", parsed.getOrDefault("elapsedMs", 0L)));
                live.accept(tool);
                return;
            }
            if ("TEXT_MESSAGE_CONTENT".equals(type)) {
                if (!messageStarted[0]) {
                    Map<String, Object> start = new LinkedHashMap<>();
                    start.put("seq", seq);
                    start.put("type", "TEXT_MESSAGE_START");
                    start.put("messageId", runId + "-m");
                    start.put("role", "assistant");
                    live.accept(start);
                    messageStarted[0] = true;
                }
                live.accept(flatten(type, parsed, seq, runId, threadId));
                return;
            }
            if ("RUN_FINISHED".equals(type) && messageStarted[0]) {
                Map<String, Object> end = new LinkedHashMap<>();
                end.put("seq", seq);
                end.put("type", "TEXT_MESSAGE_END");
                end.put("messageId", runId + "-m");
                live.accept(end);
                messageStarted[0] = false;
            }
            live.accept(flatten(type, parsed, seq, runId, threadId));
        };

        orchestrationService.executeTurnStreaming(context, input.utterance(),
                input.clarificationChoice(), input.variantHint(),
                flattened,
                terminal -> completeQuietly(emitter),
                error -> {
                    if (!anyEvent.get() && !closed.get()) {
                        // 编排在 RUNNING 前失败不会有 RUN_ERROR 事件——补一条，避免前端空流
                        Map<String, Object> flat = new LinkedHashMap<>();
                        flat.put("seq", 0L);
                        flat.put("type", "RUN_ERROR");
                        flat.put("runId", runId);
                        flat.put("message", error.getMessage() == null
                                ? "执行失败" : error.getMessage());
                        live.accept(flat);
                    }
                    completeQuietly(emitter);
                });
        return emitter;
    }

    /** 内嵌事件 → 标准 AG-UI 扁平事件（只动形状，不动语义）。 */
    private static Map<String, Object> flatten(String type, Map<String, Object> payload,
                                               long seq, String runId, String threadId) {
        Map<String, Object> flat = new LinkedHashMap<>();
        flat.put("seq", seq);
        // 定位用：AG-UI 部分事件 schema 带 threadId/runId，统一补齐（enforce 会剥离
        // 不认识的字段，多余不破坏协议）。
        flat.put("threadId", threadId);
        flat.put("runId", runId);
        switch (type) {
            case "RUN_STARTED" -> {
                flat.put("type", "RUN_STARTED");
                flat.put("threadId", threadId);
                flat.put("runId", runId);
            }
            case "RUN_FINISHED" -> {
                flat.put("type", "RUN_FINISHED");
                Object terminal = payload == null ? null : payload.get("terminal");
                if (terminal != null) {
                    flat.put("terminal", terminal);
                }
            }
            case "RUN_ERROR" -> {
                flat.put("type", "RUN_ERROR");
                flat.put("message", payload == null ? null : payload.get("message"));
            }
            case "TEXT_MESSAGE_CONTENT" -> {
                flat.put("type", "TEXT_MESSAGE_CONTENT");
                flat.put("messageId", runId + "-m");
                flat.put("delta", payload == null ? null : payload.get("delta"));
            }
            case "TOOL_CALL_START", "TOOL_CALL_END" -> {
                flat.put("type", type);
                flat.put("toolCallId", "tool-" + seq);
                // AG-UI 标准字段名是 toolCallName（编排层内部叫 toolName，转换时对齐）
                flat.put("toolCallName", payload == null ? null : payload.get("toolName"));
                flat.putAll(payload == null ? Map.of() : payload);
            }
            case "CUSTOM" -> {
                flat.put("type", "CUSTOM");
                flat.put("name", payload == null ? null : payload.get("name"));
                flat.put("value", payload == null ? null : payload.get("value"));
            }
            case "INTERRUPT", "STATE" -> {
                flat.put("type", "CUSTOM");
                flat.put("name", "ontos." + type.toLowerCase());
                flat.put("value", payload);
            }
            default -> {
                flat.put("type", "CUSTOM");
                flat.put("name", "ontos.unknown");
                flat.put("value", payload);
            }
        }
        return flat;
    }

    /** 解析内嵌 payload 文本（容错：坏 JSON 退回空 Map，事件不中断）。 */
    private static Map<String, Object> parsePayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = JsonUtils.parseMap(payloadJson);
            return parsed == null ? Map.of() : parsed;
        } catch (RuntimeException ex) {
            return Map.of();
        }
    }

    private static Map<String, Object> castMap(Map<?, ?> box) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : box.entrySet()) {
            out.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return out;
    }

    private static void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (RuntimeException ignored) {
            // 连接已结束
        }
    }
}

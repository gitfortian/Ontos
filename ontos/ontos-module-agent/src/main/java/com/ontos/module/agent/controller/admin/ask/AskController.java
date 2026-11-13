package com.ontos.module.agent.controller.admin.ask;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.ontos.framework.common.pojo.CommonResult;
import com.ontos.framework.common.util.json.JsonUtils;
import com.ontos.framework.web.core.context.RequestContext;
import com.ontos.framework.web.core.context.RequestContextHolder;
import com.ontos.module.agent.service.event.AskEventService;
import com.ontos.module.agent.service.orchestration.AskOrchestrationService;
import com.ontos.module.agent.service.run.AskRunStateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static com.ontos.framework.common.pojo.CommonResult.success;

/**
 * ask 对话主干端点（M5/T3.1/T3.3）。
 *
 * <p>run 创建是同步 REST（返回终态载荷）；事件经 {@code afterSeq} 只读重放（断线重连幂等）。
 * AG-UI 实时 SSE 由 AgentScope starter 的 {@code /run/{agentId}} 通道承载（不自研事件模型）。
 */
@Tag(name = "ask · 会话编排")
@RestController
@RequestMapping("/api/v1/ask")
public class AskController {

    private final AskOrchestrationService orchestrationService;
    private final AskEventService eventService;
    private final AskRunStateService runStateService;

    public AskController(AskOrchestrationService orchestrationService,
                         AskEventService eventService,
                         AskRunStateService runStateService) {
        this.orchestrationService = orchestrationService;
        this.eventService = eventService;
        this.runStateService = runStateService;
    }

    /** 流式连接超时（5 分钟，与 AG-UI run-timeout 对齐）。 */
    private static final long STREAM_TIMEOUT_MILLIS = 5 * 60 * 1000L;

    /** 收尾：已断开 / 已超时的连接再次 complete 会抛 IllegalStateException，吞掉即可。 */
    private static void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (RuntimeException ignored) {
            // 连接已结束
        }
    }

    public record AskRunRequest(String utterance, String clarificationChoice, String variantHint) {
    }

    @PostMapping("/runs")
    @Operation(summary = "发起一轮 ask 对话（run 创建 + 编排 + 终态）")
    public CommonResult<Map<String, Object>> createRun(@RequestBody AskRunRequest request) {
        RequestContext context = RequestContextHolder.require();
        Map<String, Object> terminal = orchestrationService.executeTurn(
                context, request.utterance(), request.clarificationChoice(), request.variantHint());
        return success(terminal);
    }

    /**
     * 流式 ask（SSE）：与 {@link #createRun} **同一条编排链路**，差别只是事件实时推送。
     *
     * <p>事件形状与 {@code GET /run/{runId}/events} 的存量重放完全一致
     * （{@code {seq, type, payload}}，payload 为 JSON 文本），前端可同一套解析。
     *
     * <p>超时取 5 分钟（与 AG-UI run-timeout 对齐）：审批等待期间连接不能先断。
     * 生产网关若对空闲连接有超时，需放宽或补心跳——本地 Vite 代理无空闲超时。
     */
    @PostMapping(value = "/runs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "发起一轮 ask 对话（SSE 实时事件 + 终态）")
    public SseEmitter createRunStream(@RequestBody AskRunRequest request) {
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

        Consumer<Map<String, Object>> live = event -> {
            if (closed.get()) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().data(event, MediaType.APPLICATION_JSON));
                anyEvent.set(true);
            } catch (Exception ex) {
                // 客户端断开：停止推送；编排继续执行到终态并落库（审计不可丢）
                closed.set(true);
            }
        };

        orchestrationService.executeTurnStreaming(context, request.utterance(),
                request.clarificationChoice(), request.variantHint(), live,
                terminal -> completeQuietly(emitter),
                error -> {
                    // 失败若发生在 RUN_STARTED 之前，编排层不会发 RUN_ERROR——补一条，避免前端空流
                    if (!anyEvent.get() && !closed.get()) {
                        Map<String, Object> payload = Map.of("message",
                                error.getMessage() == null ? "执行失败" : error.getMessage());
                        Map<String, Object> event = Map.of(
                                "seq", 0L,
                                "type", AskEventService.RUN_ERROR,
                                "payload", JsonUtils.toJsonString(payload));
                        live.accept(event);
                    }
                    completeQuietly(emitter);
                });
        return emitter;
    }

    @GetMapping("/run/{runId}/events")
    @Operation(summary = "run 事件只读重放（afterSeq 断线重连；重复请求不触发任何执行）")
    public CommonResult<List<Map<String, Object>>> replayEvents(
            @PathVariable("runId") String runId,
            @RequestParam(value = "afterSeq", defaultValue = "0") long afterSeq) {
        RequestContext context = RequestContextHolder.require();
        // 归属校验：非本租户/本用户的 run 一律 NOT_FOUND（不泄露存在性）；重放本身只读
        runStateService.require(runId, context);
        return success(eventService.replayAfter(runId, afterSeq));
    }

}

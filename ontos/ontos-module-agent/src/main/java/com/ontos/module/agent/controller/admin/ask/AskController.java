package com.ontos.module.agent.controller.admin.ask;

import java.util.List;
import java.util.Map;

import com.ontos.framework.common.pojo.CommonResult;
import com.ontos.framework.web.core.context.RequestContext;
import com.ontos.framework.web.core.context.RequestContextHolder;
import com.ontos.module.agent.service.event.AskEventService;
import com.ontos.module.agent.service.orchestration.AskOrchestrationService;
import com.ontos.module.agent.service.run.AskRunStateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

    public record AskRunRequest(String utterance, String clarificationChoice) {
    }

    @PostMapping("/runs")
    @Operation(summary = "发起一轮 ask 对话（run 创建 + 编排 + 终态）")
    public CommonResult<Map<String, Object>> createRun(@RequestBody AskRunRequest request) {
        RequestContext context = RequestContextHolder.require();
        Map<String, Object> terminal = orchestrationService.executeTurn(
                context, request.utterance(), request.clarificationChoice());
        return success(terminal);
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

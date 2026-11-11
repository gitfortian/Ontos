package com.ontos.module.system.controller.admin.audit;

import java.util.List;
import java.util.Map;

import com.ontos.framework.common.pojo.CommonResult;
import com.ontos.framework.web.core.context.RequestContext;
import com.ontos.framework.web.core.context.RequestContextHolder;
import com.ontos.module.system.service.audit.LlmCallTraceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static com.ontos.framework.common.pojo.CommonResult.success;

/**
 * 模型调用追踪查询（M7/W5/T5.1）：按 traceId 串起一次请求的全部模型调用。
 *
 * <p>只读；租户取自 {@link RequestContext}（不接收请求体租户，避免越权观测）。
 */
@Tag(name = "管理后台 - 模型调用追踪")
@RestController
@RequestMapping("/api/v1/evaluation/llm-calls")
public class EvaluationLlmTraceController {

    private final LlmCallTraceService traceService;

    public EvaluationLlmTraceController(LlmCallTraceService traceService) {
        this.traceService = traceService;
    }

    @GetMapping
    @Operation(summary = "模型调用追踪（traceId 为空时返回最近调用）")
    public CommonResult<List<Map<String, Object>>> list(
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false, defaultValue = "50") Integer limit) {
        RequestContext context = RequestContextHolder.require();
        int size = limit == null ? 50 : limit;
        if (traceId == null || traceId.isBlank()) {
            return success(traceService.recent(context.tenantId(), size));
        }
        return success(traceService.listByTrace(context.tenantId(), traceId.trim(), size));
    }
}

package com.ontos.module.agent.controller.admin.ask;

import com.ontos.framework.common.pojo.CommonResult;
import com.ontos.framework.web.core.context.RequestContext;
import com.ontos.framework.web.core.context.RequestContextHolder;
import com.ontos.module.agent.service.feedback.AskFeedbackService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.ontos.framework.common.pojo.CommonResult.success;

/**
 * ask 反馈端点（W3 结果看板 👍👎）：结构化反馈落库（V25 ask_feedback）。
 *
 * <p>归属（tenant/user/session）一律取请求上下文，前端声明无效——与审计同纪律。
 */
@Tag(name = "ask · 结果反馈")
@RestController
@RequestMapping("/api/v1/ask")
public class AskFeedbackController {

    private final AskFeedbackService feedbackService;

    public AskFeedbackController(AskFeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    public record AskFeedbackRequest(String runId, String requestId, String rating,
                                     String reasonCode, String comment) {
    }

    @PostMapping("/feedback")
    @Operation(summary = "提交结果反馈（👍/👎 + DOWN 细分原因；归属取请求上下文）")
    public CommonResult<Long> submit(@RequestBody AskFeedbackRequest request) {
        RequestContext context = RequestContextHolder.require();
        AskFeedbackService.Request domain = new AskFeedbackService.Request(
                context.tenantId(), context.userId(), context.sessionId(),
                request.runId(), request.requestId(),
                request.rating(), request.reasonCode(), request.comment());
        return success(feedbackService.submit(domain));
    }
}

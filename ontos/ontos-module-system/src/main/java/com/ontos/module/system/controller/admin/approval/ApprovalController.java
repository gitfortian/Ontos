package com.ontos.module.system.controller.admin.approval;

import com.ontos.framework.common.pojo.CommonResult;
import com.ontos.framework.common.pojo.PageResult;
import com.ontos.framework.web.core.context.RequestContext;
import com.ontos.framework.web.core.context.RequestContextHolder;
import com.ontos.module.system.controller.admin.approval.vo.ApprovalDecideReqVO;
import com.ontos.module.system.controller.admin.approval.vo.ApprovalRespVO;
import com.ontos.module.system.service.approval.ApprovalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static com.ontos.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 审批")
@RestController
@RequestMapping("/api/v1/approvals")
public class ApprovalController {

    private final ApprovalService approvalService;
    private final com.ontos.module.system.service.approval.HitlNotifier hitlNotifier;

    public ApprovalController(ApprovalService approvalService,
                             com.ontos.module.system.service.approval.HitlNotifier hitlNotifier) {
        this.approvalService = approvalService;
        this.hitlNotifier = hitlNotifier;
    }

    @GetMapping
    @Operation(summary = "分页查询审批单（按租户隔离）")
    public CommonResult<PageResult<ApprovalRespVO>> page(
            @RequestParam(required = false) String status,
            @RequestParam(required = false, defaultValue = "1") Integer pageNo,
            @RequestParam(required = false, defaultValue = "20") Integer pageSize) {
        RequestContext context = RequestContextHolder.require();
        return success(approvalService.page(context.tenantId(), status, pageNo, pageSize));
    }

    @GetMapping(value = "/hitl-events", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "订阅审批 HITL 事件流（SSE）：敏感触发时下发 INTERRUPT 事件（M4 最小通道）")
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter hitlEvents() {
        RequestContext context = RequestContextHolder.require();
        return hitlNotifier.subscribe(context);
    }

    @PostMapping("/{id}/decide")
    @Operation(summary = "批准或拒绝审批单；决策人取自认证上下文")
    public CommonResult<Boolean> decide(@PathVariable("id") String id,
                                        @Valid @RequestBody ApprovalDecideReqVO reqVO) {
        RequestContext context = RequestContextHolder.require();
        approvalService.decide(id, context.tenantId(), reqVO.getDecision(),
                reqVO.getReason(), context.userId());
        return success(true);
    }

}

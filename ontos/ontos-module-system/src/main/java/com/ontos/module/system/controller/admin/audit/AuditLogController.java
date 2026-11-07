package com.ontos.module.system.controller.admin.audit;

import com.ontos.framework.common.pojo.CommonResult;
import com.ontos.framework.common.pojo.PageResult;
import com.ontos.framework.web.core.context.RequestContext;
import com.ontos.framework.web.core.context.RequestContextHolder;
import com.ontos.module.system.controller.admin.audit.vo.AuditLogRespVO;
import com.ontos.module.system.service.audit.AuditLogQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static com.ontos.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 审计日志")
@RestController
@RequestMapping("/api/v1/audit-logs")
public class AuditLogController {

    private final AuditLogQueryService auditLogQueryService;

    public AuditLogController(AuditLogQueryService auditLogQueryService) {
        this.auditLogQueryService = auditLogQueryService;
    }

    @GetMapping
    @Operation(summary = "分页查询审计日志（按租户隔离，只追加）")
    public CommonResult<PageResult<AuditLogRespVO>> page(
            @RequestParam(required = false) String action,
            @RequestParam(required = false, defaultValue = "1") Integer pageNo,
            @RequestParam(required = false, defaultValue = "20") Integer pageSize) {
        RequestContext context = RequestContextHolder.require();
        return success(auditLogQueryService.page(context.tenantId(), action, pageNo, pageSize));
    }

}

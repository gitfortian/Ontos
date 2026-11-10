package com.ontos.module.system.controller.admin.audit;

import java.util.Map;

import com.ontos.framework.common.pojo.CommonResult;
import com.ontos.framework.web.core.context.RequestContext;
import com.ontos.framework.web.core.context.RequestContextHolder;
import com.ontos.module.system.controller.admin.audit.vo.FailureDashboardRespVO;
import com.ontos.module.system.controller.admin.audit.vo.FailureSampleRegisterReqVO;
import com.ontos.module.system.service.audit.FailureSampleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static com.ontos.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 失败看板")
@RestController
@RequestMapping("/api/v1/evaluation/failure-samples")
public class EvaluationFailureController {

    private final FailureSampleService failureSampleService;

    public EvaluationFailureController(FailureSampleService failureSampleService) {
        this.failureSampleService = failureSampleService;
    }

    @PostMapping
    @Operation(summary = "批量登记评测失败样本（评测 runner 消费，只追加）")
    public CommonResult<Map<String, Object>> register(
            @RequestBody FailureSampleRegisterReqVO reqVO) {
        RequestContext context = RequestContextHolder.require();
        int inserted = failureSampleService.register(context.tenantId(), reqVO);
        return success(Map.of("inserted", inserted, "runId", reqVO == null ? null : reqVO.getRunId()));
    }

    @GetMapping("/dashboard")
    @Operation(summary = "失败看板五维下钻（G-5）+ 最近样本明细（一次返回全部五维）")
    public CommonResult<FailureDashboardRespVO> dashboard(
            @RequestParam String setId,
            @RequestParam(required = false, defaultValue = "50") Integer recentLimit) {
        RequestContext context = RequestContextHolder.require();
        return success(failureSampleService.dashboard(context.tenantId(), setId,
                recentLimit == null ? 50 : recentLimit));
    }

}

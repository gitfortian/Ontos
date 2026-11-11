package com.ontos.module.system.controller.admin.audit;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.ontos.framework.common.pojo.CommonResult;
import com.ontos.framework.web.core.context.RequestContext;
import com.ontos.framework.web.core.context.RequestContextHolder;
import com.ontos.module.system.controller.admin.audit.vo.FailureDashboardRespVO;
import com.ontos.module.system.controller.admin.audit.vo.FailureSampleClassifyReqVO;
import com.ontos.module.system.controller.admin.audit.vo.FailureSampleRegisterReqVO;
import com.ontos.module.system.controller.admin.audit.vo.FailureSampleReturnReqVO;
import com.ontos.module.system.controller.admin.audit.vo.FailureSampleStatusReqVO;
import com.ontos.module.system.enums.FailureCauseCategory;
import com.ontos.module.system.service.audit.FailureSampleLifecycleService;
import com.ontos.module.system.service.audit.FailureSampleService;
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

@Tag(name = "管理后台 - 失败看板")
@RestController
@RequestMapping("/api/v1/evaluation/failure-samples")
public class EvaluationFailureController {

    private final FailureSampleService failureSampleService;
    private final FailureSampleLifecycleService lifecycleService;

    public EvaluationFailureController(FailureSampleService failureSampleService,
                                       FailureSampleLifecycleService lifecycleService) {
        this.failureSampleService = failureSampleService;
        this.lifecycleService = lifecycleService;
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
    @Operation(summary = "失败看板五维下钻（G-5）+ 单维联动过滤 + 最近样本明细")
    public CommonResult<FailureDashboardRespVO> dashboard(
            @RequestParam String setId,
            @RequestParam(required = false, defaultValue = "50") Integer recentLimit,
            @RequestParam(required = false) String drillDimension,
            @RequestParam(required = false) String drillValue) {
        RequestContext context = RequestContextHolder.require();
        return success(failureSampleService.dashboard(context.tenantId(), setId,
                recentLimit == null ? 50 : recentLimit, drillDimension, drillValue));
    }

    @GetMapping("/categories")
    @Operation(summary = "根因分类闭词表（前端分类下拉的唯一来源）")
    public CommonResult<List<Map<String, String>>> categories() {
        return success(Arrays.stream(FailureCauseCategory.values())
                .map(category -> Map.of("code", category.code(), "label", category.label()))
                .toList());
    }

    @GetMapping("/{id}")
    @Operation(summary = "失败样本详情（当前状态 + 根因分类 + 状态/分类事件历史）")
    public CommonResult<Map<String, Object>> detail(
            @PathVariable("id") Long id,
            @RequestParam(required = false, defaultValue = "50") Integer eventLimit) {
        RequestContext context = RequestContextHolder.require();
        return success(lifecycleService.detail(context.tenantId(), id,
                eventLimit == null ? 50 : eventLimit));
    }

    @PostMapping("/{id}/status")
    @Operation(summary = "失败样本状态流转（open/triaged/fixed/ignored；regressed 走回流通道）")
    public CommonResult<Map<String, Object>> transition(
            @PathVariable("id") Long id,
            @RequestBody FailureSampleStatusReqVO reqVO) {
        RequestContext context = RequestContextHolder.require();
        return success(lifecycleService.transition(context.tenantId(), id,
                reqVO == null ? null : reqVO.getToStatus(), context.userId(),
                reqVO == null ? null : reqVO.getNote()));
    }

    @PostMapping("/{id}/classify")
    @Operation(summary = "失败样本根因分类（§12.3 原因码族闭词表）")
    public CommonResult<Map<String, Object>> classify(
            @PathVariable("id") Long id,
            @RequestBody FailureSampleClassifyReqVO reqVO) {
        RequestContext context = RequestContextHolder.require();
        return success(lifecycleService.classify(context.tenantId(), id,
                reqVO == null ? null : reqVO.getCategory(), context.userId(),
                reqVO == null ? null : reqVO.getNote()));
    }

    @PostMapping("/{id}/return-to-regression")
    @Operation(summary = "失败样本退回回归集（只允许 dev / validation；生成 regressionSampleId）")
    public CommonResult<Map<String, Object>> returnToRegression(
            @PathVariable("id") Long id,
            @RequestBody FailureSampleReturnReqVO reqVO) {
        RequestContext context = RequestContextHolder.require();
        return success(lifecycleService.returnToRegression(context.tenantId(), id,
                reqVO == null ? null : reqVO.getTargetSet(), context.userId(),
                reqVO == null ? null : reqVO.getNote()));
    }

}

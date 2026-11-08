package com.ontos.module.query.controller.admin.vo;

import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * dry-run 响应：dry-plan 的全部计划字段 + 受限执行结果（M2/W4/T4.1、T5.2）。
 *
 * <p>结果与正式执行共用同一套展示转换（掩码），调试通道不是脱敏旁路。
 */
@Schema(description = "管理后台 - dry-run Response VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class QueryDryRunRespVO extends QueryPlanRespVO {

    @Schema(description = "结果列")
    private List<String> columns;

    @Schema(description = "结果行（已按清单做展示转换）")
    private List<Map<String, Object>> rows;

    @Schema(description = "返回行数")
    private Integer rowCount;

    @Schema(description = "是否因 dry-run 行数上限被截断")
    private Boolean truncated;

    @Schema(description = "执行耗时（毫秒）")
    private Long elapsedMs;

    @Schema(description = "lookup / lookup_batch 的逐行可用性")
    private List<QueryExecuteRespVO.LookupRowVO> lookupRows;

}

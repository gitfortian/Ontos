package com.ontos.module.query.controller.admin.vo;

import java.util.List;

import com.ontos.module.query.core.dsl.QueryDsl;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - dry-plan / dry-run Response VO")
@Data
public class QueryPlanRespVO {

    @Schema(description = "清单 ID")
    private String manifestId;

    @Schema(description = "清单内容哈希")
    private String contentHash;

    @Schema(description = "分析模式")
    private String mode;

    @Schema(description = "参数化 SQL（与正式执行同一编译产物）")
    private String sql;

    @Schema(description = "绑定值")
    private List<Object> bindValues;

    @Schema(description = "生效行数上限")
    private Integer appliedLimit;

    @Schema(description = "服务端解析出的 Join 路径")
    private List<QueryDsl.ResolvedJoin> joins;

    @Schema(description = "EXPLAIN (FORMAT JSON) 原始计划")
    private String planJson;

    @Schema(description = "估算成本（Total Cost）")
    private Double estimatedCost;

    @Schema(description = "估算行数（Plan Rows）")
    private Long estimatedRows;

    @Schema(description = "计划状态：OK / BLOCKED")
    private String status;

    @Schema(description = "BLOCKED 原因")
    private String blockReason;

    @Schema(description = "dry-run 是否已完成真实数据库零行校验")
    private Boolean verified;

}

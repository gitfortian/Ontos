package com.ontos.module.query.controller.admin.vo;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 查询编译（dry-plan）Response VO")
@Data
public class QueryCompileRespVO {

    @Schema(description = "清单 ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private String manifestId;

    @Schema(description = "清单内容哈希", requiredMode = Schema.RequiredMode.REQUIRED)
    private String contentHash;

    @Schema(description = "指标 ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private String metricId;

    @Schema(description = "分组维度 ID 列表")
    private List<String> dimensions;

    @Schema(description = "SQL 方言", example = "postgresql")
    private String dialect;

    @Schema(description = "参数化 SQL", requiredMode = Schema.RequiredMode.REQUIRED)
    private String sql;

    @Schema(description = "绑定值")
    private List<Object> bindValues;

    @Schema(description = "生效行数上限")
    private Integer appliedLimit;

}

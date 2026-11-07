package com.ontos.module.query.controller.admin.vo;

import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 查询执行 Response VO")
@Data
public class QueryExecuteRespVO {

    @Schema(description = "清单 ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private String manifestId;

    @Schema(description = "清单内容哈希", requiredMode = Schema.RequiredMode.REQUIRED)
    private String contentHash;

    @Schema(description = "列名列表", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<String> columns;

    @Schema(description = "结果行", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<Map<String, Object>> rows;

    @Schema(description = "返回行数", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer rowCount;

    @Schema(description = "是否因上限被截断", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean truncated;

    @Schema(description = "执行耗时（毫秒）")
    private Long elapsedMs;

}

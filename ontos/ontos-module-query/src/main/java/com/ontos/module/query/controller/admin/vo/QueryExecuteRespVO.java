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

    @Schema(description = "是否来自结果缓存（true 表示未重新编译执行）")
    private Boolean cached;

    @Schema(description = "lookup 逐行可用性（按请求顺序；不可用行不区分不存在/无权限）")
    private List<LookupRowVO> lookupRows;

    @Schema(description = "lookup 单行结果")
    @Data
    public static class LookupRowVO {
        @Schema(description = "请求标识值")
        private String identity;
        @Schema(description = "该行是否可用")
        private Boolean available;
        @Schema(description = "投影属性值（属性 ID → 值，已按 displayHints 转换）")
        private Map<String, Object> values;
    }

}

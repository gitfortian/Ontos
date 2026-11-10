package com.ontos.module.query.controller.admin.vo;

import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 复合意图执行响应（M2/W4/T4.3）：逐子结果各自隔离，不合并 SQL。
 *
 * <p>没有任何「合并后的大结果」字段——子意图之间不共享中间结果，
 * 前端按键 subIntentId 与请求里的子意图对齐展示。
 */
@Schema(description = "管理后台 - 复合意图执行 Response VO")
@Data
public class QueryCompositeRespVO {

    @Schema(description = "父请求 ID（子请求为 父ID:子意图ID）")
    private String requestId;

    @Schema(description = "全部子意图返回行数之和")
    private Integer totalRowCount;

    @Schema(description = "逐子意图结果，顺序与请求一致")
    private List<SubResultVO> results;

    @Schema(description = "单个子意图的执行结果")
    @Data
    public static class SubResultVO {

        @Schema(description = "子意图 ID")
        private String subIntentId;

        @Schema(description = "该子失败的业务码（部分失败可解释；成功为 null，失败时其余结果字段为空）")
        private Integer errorCode;

        @Schema(description = "该子失败原因（文案模板渲染前的结构化原因）")
        private String errorMessage;

        @Schema(description = "清单 ID")
        private String manifestId;

        @Schema(description = "清单内容哈希")
        private String contentHash;

        @Schema(description = "列名列表")
        private List<String> columns;

        @Schema(description = "结果行")
        private List<Map<String, Object>> rows;

        @Schema(description = "返回行数")
        private Integer rowCount;

        @Schema(description = "是否因上限被截断")
        private Boolean truncated;

        @Schema(description = "执行耗时（毫秒）")
        private Long elapsedMs;

        @Schema(description = "lookup 逐行可用性（子意图为 QUERY 时为空）")
        private List<QueryExecuteRespVO.LookupRowVO> lookupRows;

    }

}

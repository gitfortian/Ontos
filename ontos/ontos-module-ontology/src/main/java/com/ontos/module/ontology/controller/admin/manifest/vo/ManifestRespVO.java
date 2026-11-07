package com.ontos.module.ontology.controller.admin.manifest.vo;

import java.time.LocalDateTime;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 发布清单 Response VO")
@Data
public class ManifestRespVO {

    @Schema(description = "清单 ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "2026.11.06-1")
    private String manifestId;

    @Schema(description = "内容哈希（SHA-256）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String contentHash;

    @Schema(description = "本体版本", requiredMode = Schema.RequiredMode.REQUIRED)
    private String ontologyVersion;

    @Schema(description = "语义资产版本", requiredMode = Schema.RequiredMode.REQUIRED)
    private String semanticVersion;

    @Schema(description = "策略版本", requiredMode = Schema.RequiredMode.REQUIRED)
    private String policyVersion;

    @Schema(description = "规则版本", requiredMode = Schema.RequiredMode.REQUIRED)
    private String ruleVersion;

    @Schema(description = "时间解析器版本", requiredMode = Schema.RequiredMode.REQUIRED)
    private String timeParserVersion;

    @Schema(description = "文案模板版本", requiredMode = Schema.RequiredMode.REQUIRED)
    private String copyTemplateVersion;

    @Schema(description = "发布时间")
    private LocalDateTime createdAt;

    @Schema(description = "指标 ID 列表")
    private List<String> metricIds;

    @Schema(description = "概念 ID 列表")
    private List<String> conceptIds;

}

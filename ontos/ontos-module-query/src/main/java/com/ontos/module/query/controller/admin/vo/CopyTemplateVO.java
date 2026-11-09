package com.ontos.module.query.controller.admin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 原因码文案 Response VO")
@Data
public class CopyTemplateVO {

    @Schema(description = "模板 ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private String templateId;

    @Schema(description = "生效原因码", requiredMode = Schema.RequiredMode.REQUIRED)
    private String reasonCode;

    @Schema(description = "语言", requiredMode = Schema.RequiredMode.REQUIRED)
    private String locale;

    @Schema(description = "渲染后的用户文案", requiredMode = Schema.RequiredMode.REQUIRED)
    private String text;

    @Schema(description = "模板版本")
    private String version;

    @Schema(description = "是否走了 GENERIC 兜底（true 表示原原因码无模板或渲染失败）")
    private boolean fallback;
}

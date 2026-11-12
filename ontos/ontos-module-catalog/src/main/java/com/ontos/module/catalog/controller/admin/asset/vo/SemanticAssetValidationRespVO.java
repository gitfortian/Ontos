package com.ontos.module.catalog.controller.admin.asset.vo;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

@Schema(description = "管理后台 - 语义资产草稿校验 Response VO")
@Data
public class SemanticAssetValidationRespVO {

    @Schema(description = "是否通过")
    private boolean valid;

    @Schema(description = "未通过项（只含本资产）")
    private List<ViolationVO> violations;

    public SemanticAssetValidationRespVO(boolean valid, List<ViolationVO> violations) {
        this.valid = valid;
        this.violations = violations;
    }

    @Schema(description = "校验未通过项")
    @Data
    @AllArgsConstructor
    public static class ViolationVO {

        @Schema(description = "位置（类型名 + '.' + 资产 ID）")
        private String location;

        @Schema(description = "说明")
        private String message;
    }
}

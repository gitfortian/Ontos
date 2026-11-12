package com.ontos.module.catalog.controller.admin.asset.vo;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 语义资产草稿校验 Request VO")
@Data
public class SemanticAssetValidateReqVO {

    /** 可选：传入时校验入参（保存前预检），省略时校验已存草稿。 */
    @Schema(description = "待校验的资产定义；省略则校验已存草稿")
    private Object definition;

    /** 未声明的请求字段（如 owner / reviewer / status）：收集后由 controller 显式拒绝，不静默忽略。 */
    @JsonAnySetter
    private Map<String, Object> unknownFields = new LinkedHashMap<>();
}

package com.ontos.module.catalog.controller.admin.asset.vo;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 语义资产草稿创建 Request VO")
@Data
public class SemanticAssetCreateReqVO {

    /** 用 Object 接收：数组/标量也能进入业务校验并返回 ASSET_DEFINITION_INVALID，而不是框架 400。 */
    @Schema(description = "资产定义（JSON 对象）", requiredMode = Schema.RequiredMode.REQUIRED)
    private Object definition;

    /** 未声明的请求字段（如 owner / reviewer / status）：收集后由 controller 显式拒绝，不静默忽略。 */
    @JsonAnySetter
    private Map<String, Object> unknownFields = new LinkedHashMap<>();
}

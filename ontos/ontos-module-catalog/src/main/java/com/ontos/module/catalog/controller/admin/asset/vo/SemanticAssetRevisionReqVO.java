package com.ontos.module.catalog.controller.admin.asset.vo;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 语义资产状态迁移 Request VO（只带乐观锁版本）")
@Data
public class SemanticAssetRevisionReqVO {

    @Schema(description = "期望的当前 revision（乐观锁）", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long expectedRevision;

    /** 未声明的请求字段（如 owner / reviewer / status）：收集后由 controller 显式拒绝。 */
    @JsonAnySetter
    private Map<String, Object> unknownFields = new LinkedHashMap<>();
}

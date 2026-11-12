package com.ontos.module.catalog.controller.admin.asset.vo;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 语义资产草稿 Response VO")
@Data
public class SemanticAssetRespVO {

    @Schema(description = "资产类型：concept / property / relation / metric / dimension")
    private String assetType;

    @Schema(description = "资产 ID")
    private String id;

    @Schema(description = "业务名")
    private String name;

    @Schema(description = "一句话说明")
    private String summary;

    @Schema(description = "draft / reviewing / published / retired")
    private String status;

    @Schema(description = "负责人")
    private String owner;

    @Schema(description = "审核人")
    private String reviewer;

    @Schema(description = "资产版本")
    private String version;

    @Schema(description = "乐观锁版本")
    private long revision;

    @Schema(description = "资产定义（JSON 对象）")
    private Object definition;

    @Schema(description = "发布时间")
    private Instant publishedAt;

    @Schema(description = "最后更新时间")
    private Instant updatedAt;
}

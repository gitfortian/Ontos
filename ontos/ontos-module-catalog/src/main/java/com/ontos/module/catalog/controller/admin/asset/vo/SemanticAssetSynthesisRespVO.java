package com.ontos.module.catalog.controller.admin.asset.vo;

import java.util.List;
import java.util.Map;

import com.ontos.module.catalog.service.asset.SemanticAssetSynthesisReport;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

@Schema(description = "管理后台 - 语义资产合成发布 Response VO")
@Data
public class SemanticAssetSynthesisRespVO {

    @Schema(description = "新清单 ID")
    private String manifestId;

    @Schema(description = "新清单 content hash")
    private String contentHash;

    @Schema(description = "合成前的生效清单 ID")
    private String previousManifestId;

    @Schema(description = "本次随清单发布的资产")
    private List<PublishedAssetVO> published;

    @Schema(description = "新清单各类资产计数")
    private Map<String, Integer> assetCounts;

    @Schema(description = "本次发布被依赖影响分析移除的 VQR")
    private List<String> invalidatedVqrs;

    public static SemanticAssetSynthesisRespVO from(SemanticAssetSynthesisReport report) {
        SemanticAssetSynthesisRespVO vo = new SemanticAssetSynthesisRespVO();
        vo.setManifestId(report.manifestId());
        vo.setContentHash(report.contentHash());
        vo.setPreviousManifestId(report.previousManifestId());
        vo.setPublished(report.published().stream()
                .map(asset -> new PublishedAssetVO(asset.assetType(), asset.id()))
                .toList());
        vo.setAssetCounts(report.assetCounts());
        vo.setInvalidatedVqrs(report.invalidatedVqrs());
        return vo;
    }

    @Data
    @AllArgsConstructor
    public static class PublishedAssetVO {

        @Schema(description = "资产类型")
        private String assetType;

        @Schema(description = "资产 ID")
        private String id;
    }
}

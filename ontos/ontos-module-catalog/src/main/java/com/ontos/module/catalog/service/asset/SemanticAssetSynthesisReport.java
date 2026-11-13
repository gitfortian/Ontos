package com.ontos.module.catalog.service.asset;

import java.util.List;
import java.util.Map;

/**
 * 合成发布报告（B1/P1-B）：一次「审核中批次 → 新生效清单」的完整回执。
 */
public record SemanticAssetSynthesisReport(String manifestId,
                                           String contentHash,
                                           String previousManifestId,
                                           List<AssetRef> published,
                                           Map<String, Integer> assetCounts,
                                           List<String> invalidatedVqrs) {

    /** 本次随清单发布的资产。 */
    public record AssetRef(String assetType, String id) {
    }
}

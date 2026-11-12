package com.ontos.module.catalog.service.asset;

import java.time.Instant;

import com.ontos.module.catalog.dal.dataobject.SemanticAssetRecord;

/**
 * 语义资产草稿的服务层视图（B1/W4）。
 *
 * <p>存在理由：ArchUnit 规定 controller 不得依赖 `..dal..`，而 controller 又需要读记录字段。
 * 用 service 层视图隔断，而不是放宽架构规则。
 */
public record SemanticAssetView(String assetType, String id, String name, String summary,
                                String status, String owner, String reviewer, String version,
                                long revision, String definitionJson,
                                Instant publishedAt, Instant updatedAt) {

    public static SemanticAssetView of(SemanticAssetRecord record) {
        return new SemanticAssetView(record.getAssetType(), record.getId(), record.getName(),
                record.getSummary(), record.getStatus(), record.getOwner(), record.getReviewer(),
                record.getVersion(), record.getRevision(), record.getDefinitionJson(),
                record.getPublishedAt(), record.getUpdatedAt());
    }
}

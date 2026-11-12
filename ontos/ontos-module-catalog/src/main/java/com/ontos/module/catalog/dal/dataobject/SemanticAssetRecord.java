package com.ontos.module.catalog.dal.dataobject;

import java.time.Instant;

import lombok.Data;

/**
 * 语义资产草稿记录（B1/W1）：状态与生命周期列在表上，资产内容在 definition JSON。
 *
 * <p>definition 保持原始 JSON 字符串而非具体类型：concept / property / relation / metric /
 * dimension 五类结构不同，记录层不做类型耦合；反序列化与校验在 service 层按 {@code assetType} 分派。
 *
 * <p>表只服务管理面；运行时永远读已发布清单的 ontology / semantic 段，不读本表草稿。
 */
@Data
public class SemanticAssetRecord {

    /** concept | property | relation | metric | dimension */
    private String assetType;
    private String id;
    private String name;
    private String summary;
    /** draft | reviewing | published | retired */
    private String status;
    private String owner;
    private String reviewer;
    private String version;
    /** 乐观锁版本：每次保存或状态迁移 +1。 */
    private long revision;
    /** 资产内容原始 JSON（对象形状），由校验器按类型解析。 */
    private String definitionJson;
    private Instant publishedAt;
    private Instant updatedAt;

    public boolean isDraft() {
        return "draft".equals(status);
    }
}

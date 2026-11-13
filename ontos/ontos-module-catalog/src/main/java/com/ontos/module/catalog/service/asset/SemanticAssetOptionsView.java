package com.ontos.module.catalog.service.asset;

import java.util.List;

/**
 * 语义资产编辑器的候选元数据（B1/P1）：只读当前生效清单，供前端做「对象 → 属性」联动、
 * 维度/模式多选，以及本体建模页的对象/属性/关联浏览。
 *
 * <p>刻意不返回物理列：编辑器只需要 ID 与业务名；物理名仍由服务端在编译期解析（AGENTS.md §4）。
 */
public record SemanticAssetOptionsView(List<ConceptOption> concepts,
                                       List<PropertyOption> properties,
                                       List<AssetOption> dimensions,
                                       List<AssetOption> metrics,
                                       List<RelationOption> relations) {

    public record AssetOption(String id, String name) {
    }

    /** 业务对象：带领域与属性数，供本体建模页直接展示。 */
    public record ConceptOption(String id, String name, String domain, String summary,
                                int propertyCount) {
    }

    /** 属性选项带 conceptId：前端据此过滤「所选业务对象下的属性」。 */
    public record PropertyOption(String id, String name, String conceptId, String valueType,
                                 String summary, boolean filterable) {
    }

    /** 业务关联：带起止对象，供本体建模页展示关系两端。 */
    public record RelationOption(String id, String name, String summary, String from, String to) {
    }

    public static SemanticAssetOptionsView empty() {
        return new SemanticAssetOptionsView(List.of(), List.of(), List.of(), List.of(), List.of());
    }
}

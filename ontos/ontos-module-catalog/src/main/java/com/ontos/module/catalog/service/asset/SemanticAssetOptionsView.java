package com.ontos.module.catalog.service.asset;

import java.util.List;

/**
 * 语义资产编辑器的候选元数据（B1/P1）：只读当前生效清单，供前端做「对象 → 属性」联动与维度/模式多选。
 *
 * <p>刻意不返回物理列：编辑器只需要 ID 与业务名；物理名仍由服务端在编译期解析（AGENTS.md §4）。
 */
public record SemanticAssetOptionsView(List<AssetOption> concepts,
                                       List<PropertyOption> properties,
                                       List<AssetOption> dimensions,
                                       List<AssetOption> metrics,
                                       List<AssetOption> relations) {

    public record AssetOption(String id, String name) {
    }

    /** 属性选项带 conceptId：前端据此过滤「所选业务对象下的属性」。 */
    public record PropertyOption(String id, String name, String conceptId, String valueType) {
    }

    public static SemanticAssetOptionsView empty() {
        return new SemanticAssetOptionsView(List.of(), List.of(), List.of(), List.of(), List.of());
    }
}

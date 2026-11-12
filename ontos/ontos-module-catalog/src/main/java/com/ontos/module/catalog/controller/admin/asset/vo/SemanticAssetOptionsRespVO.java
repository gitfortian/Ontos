package com.ontos.module.catalog.controller.admin.asset.vo;

import java.util.List;

import com.ontos.module.catalog.service.asset.SemanticAssetOptionsView;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

@Schema(description = "管理后台 - 语义资产编辑候选元数据 Response VO")
@Data
public class SemanticAssetOptionsRespVO {

    @Schema(description = "业务对象")
    private List<OptionVO> concepts;

    @Schema(description = "业务属性（带 conceptId，供前端按对象过滤）")
    private List<PropertyOptionVO> properties;

    @Schema(description = "分析维度")
    private List<OptionVO> dimensions;

    @Schema(description = "指标")
    private List<OptionVO> metrics;

    @Schema(description = "业务关联")
    private List<OptionVO> relations;

    public static SemanticAssetOptionsRespVO from(SemanticAssetOptionsView view) {
        SemanticAssetOptionsRespVO vo = new SemanticAssetOptionsRespVO();
        vo.setConcepts(view.concepts().stream().map(OptionVO::from).toList());
        vo.setProperties(view.properties().stream().map(PropertyOptionVO::from).toList());
        vo.setDimensions(view.dimensions().stream().map(OptionVO::from).toList());
        vo.setMetrics(view.metrics().stream().map(OptionVO::from).toList());
        vo.setRelations(view.relations().stream().map(OptionVO::from).toList());
        return vo;
    }

    @Data
    @AllArgsConstructor
    public static class OptionVO {

        @Schema(description = "资产 ID")
        private String id;

        @Schema(description = "业务名")
        private String name;

        static OptionVO from(SemanticAssetOptionsView.AssetOption option) {
            return new OptionVO(option.id(), option.name());
        }
    }

    @Data
    @AllArgsConstructor
    public static class PropertyOptionVO {

        @Schema(description = "属性 ID")
        private String id;

        @Schema(description = "业务名")
        private String name;

        @Schema(description = "所属业务对象 ID")
        private String conceptId;

        @Schema(description = "值类型")
        private String valueType;

        static PropertyOptionVO from(SemanticAssetOptionsView.PropertyOption option) {
            return new PropertyOptionVO(option.id(), option.name(), option.conceptId(),
                    option.valueType());
        }
    }
}

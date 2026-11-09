package com.ontos.module.query.controller.admin.vo;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 能力描述 Response VO")
@Data
public class SkillDescriptorVO {

    @Schema(description = "能力 ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private String skillId;

    @Schema(description = "能力名", requiredMode = Schema.RequiredMode.REQUIRED)
    private String name;

    @Schema(description = "一句话说明")
    private String summary;

    @Schema(description = "槽位契约")
    private List<SlotVO> slots;

    @Schema(description = "能力槽位")
    @Data
    public static class SlotVO {

        @Schema(description = "槽位名", requiredMode = Schema.RequiredMode.REQUIRED)
        private String name;

        @Schema(description = "是否必填")
        private boolean required;

        @Schema(description = "说明")
        private String description;
    }
}

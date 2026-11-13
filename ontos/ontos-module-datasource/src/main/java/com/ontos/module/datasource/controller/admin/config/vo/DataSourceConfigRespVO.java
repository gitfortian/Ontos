package com.ontos.module.datasource.controller.admin.config.vo;

import java.time.OffsetDateTime;
import java.util.Map;

import com.ontos.module.datasource.service.config.DatasourceConfigService;
import io.swagger.v3.oas.annotations.media.Schema;

/** 注册数据源响应（脱敏）：secret 字段为掩码，明文/密文不出服务。 */
@Schema(description = "数据源配置 Response VO")
public class DataSourceConfigRespVO {

    @Schema(description = "ID")
    private String id;

    @Schema(description = "名称")
    private String name;

    @Schema(description = "插件类型")
    private String type;

    @Schema(description = "配置（secret 字段为掩码）")
    private Map<String, Object> config;

    @Schema(description = "是否已存储凭据")
    private boolean hasSecret;

    @Schema(description = "创建时间")
    private OffsetDateTime createdAt;

    @Schema(description = "更新时间")
    private OffsetDateTime updatedAt;

    public static DataSourceConfigRespVO from(DatasourceConfigService.ConfigView view) {
        DataSourceConfigRespVO vo = new DataSourceConfigRespVO();
        vo.id = view.id();
        vo.name = view.name();
        vo.type = view.type();
        vo.config = view.config();
        vo.hasSecret = view.hasSecret();
        vo.createdAt = view.createdAt();
        vo.updatedAt = view.updatedAt();
        return vo;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Map<String, Object> getConfig() {
        return config;
    }

    public void setConfig(Map<String, Object> config) {
        this.config = config;
    }

    public boolean isHasSecret() {
        return hasSecret;
    }

    public void setHasSecret(boolean hasSecret) {
        this.hasSecret = hasSecret;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}

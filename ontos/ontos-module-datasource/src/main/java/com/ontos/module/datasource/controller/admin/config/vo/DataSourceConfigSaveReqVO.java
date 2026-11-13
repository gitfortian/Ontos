package com.ontos.module.datasource.controller.admin.config.vo;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/** 注册数据源请求（B2 Phase 2 前半）：type 注册后不可改；secrets 缺省 = 编辑时保留原凭据。 */
@Schema(description = "注册/编辑数据源配置 Request VO")
@JsonIgnoreProperties(ignoreUnknown = false)
public class DataSourceConfigSaveReqVO {

    @Schema(description = "名称（唯一）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    private String name;

    @Schema(description = "插件类型（仅注册时必填；编辑忽略）")
    private String type;

    @Schema(description = "连接配置（secret 字段可一并传入，服务端剥离加密）")
    private Map<String, Object> config;

    @Schema(description = "凭据字段（编辑时缺省 = 保留原凭据）")
    private Map<String, String> secrets;

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

    public Map<String, String> getSecrets() {
        return secrets;
    }

    public void setSecrets(Map<String, String> secrets) {
        this.secrets = secrets;
    }
}

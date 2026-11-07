package com.ontos.module.ontology.service.manifest;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "ontos.manifest")
public class ManifestProperties {

    /** 启动时尝试加载当前生效清单；加载失败不阻止启动，但业务接口 fail-closed。 */
    private boolean startupLoad = true;

}

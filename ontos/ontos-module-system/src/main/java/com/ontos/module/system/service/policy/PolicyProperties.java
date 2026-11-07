package com.ontos.module.system.service.policy;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "ontos.policy")
public class PolicyProperties {

    /** 仅限本地开发：无条件放行。生产必须关闭，否则违反 fail-closed。 */
    private boolean devAllowAll = false;

}

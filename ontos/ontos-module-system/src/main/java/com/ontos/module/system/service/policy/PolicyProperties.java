package com.ontos.module.system.service.policy;

import java.util.List;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "ontos.policy")
public class PolicyProperties {

    /** 仅限本地开发：无条件放行。生产必须关闭，否则违反 fail-closed。 */
    private boolean devAllowAll = false;

    /**
     * 授权域（T6.2 最小实现）：规划子图按域收窄，域外资产不可见。
     * 空列表 = 全域（仅 local 调试语义）；真实「身份 → 域」映射由 M4 网关策略接管。
     */
    private List<String> authorizedDomains = List.of();

}

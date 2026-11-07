package com.ontos.config;

import com.ontos.module.ontology.service.manifest.AuthorizedDomainsSupplier;
import com.ontos.module.system.service.policy.PolicyProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 授权域供给装配（M1/T6.2）：把 ontology 的 AuthorizedDomainsSupplier 端口
 * 接到 system 策略配置（dev-allow-all = 全域调试；否则取配置的授权域列表）。
 * 真实「身份 → 域」映射由 M4 网关策略接管。
 */
@Configuration
public class AuthorizedDomainsConfiguration {

    @Bean
    public AuthorizedDomainsSupplier authorizedDomainsSupplier(PolicyProperties policyProperties) {
        return () -> {
            if (policyProperties.isDevAllowAll()) {
                return java.util.Set.of(); // 全域调试态（规划器空集合语义）
            }
            return java.util.Set.copyOf(policyProperties.getAuthorizedDomains());
        };
    }

}

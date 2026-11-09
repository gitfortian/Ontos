package com.ontos.config;

import com.ontos.framework.web.core.context.RequestContextHolder;
import com.ontos.module.ontology.service.publish.PublishGuard;
import com.ontos.module.system.service.policy.PolicyService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 发布闸门装配：把 ontology 的 PublishGuard 端口接到 system 策略与认证上下文。
 *
 * <p>端口模式对齐 AgentContextProvider——ontology 模块不依赖 web/system（ArchUnit 守护），
 * 装配点（server）负责接线。策略保持 fail-closed：M4 策略快照落地前，
 * 非 local profile 下发布链路整体 403。
 */
@Configuration
public class PublishGuardConfiguration {

    @Bean
    public PublishGuard publishGuard(PolicyService policyService) {
        return new PublishGuard() {
            @Override
            public void requirePublishAllowed(String assetId) {
                // M4：发布是管理动作，不属于业务资产 allowlist（AssetType 无对应类型），
                // 走管理面闸门（语义与 M0 一致：仅 dev 放行，其余 403）；管理角色模型归 M6/M8。
                policyService.checkManagementAllowed(RequestContextHolder.require());
            }

            @Override
            public String currentPublisher() {
                return RequestContextHolder.require().userId();
            }
        };
    }

}

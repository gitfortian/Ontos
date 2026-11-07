package com.ontos.framework.agent.config;

import com.ontos.framework.agent.core.OntosAgentFactory;
import io.agentscope.spring.boot.agui.common.AguiAgentRegistryCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;

/**
 * AG-UI 端点：注册 ontos agent 工厂，由 AgentScope starter 暴露 /agent/ontos（SSE）。
 *
 * <p>前端协议事件（TEXT_MESSAGE_* / TOOL_CALL_* / RUN_*）由框架适配器生成，
 * 不自研 SSE 事件模型。生产入口必须由网关做认证与限流。
 */
@Configuration
@ConditionalOnProperty(prefix = "ontos.agent", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OntosAgentConfiguration {

    @Bean
    public OntosAgentFactory ontosAgentFactory(AgentProperties properties, ResourceLoader resourceLoader) {
        return new OntosAgentFactory(properties, resourceLoader);
    }

    @Bean
    public AguiAgentRegistryCustomizer ontosAguiAgentRegistryCustomizer(OntosAgentFactory agentFactory) {
        return registry -> registry.registerFactory("ontos", agentFactory::create);
    }

}

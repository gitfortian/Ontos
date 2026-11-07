package com.ontos.framework.agent.config;

import com.ontos.framework.agent.core.OntosAgentFactory;
import com.ontos.framework.common.agent.AgentContextProvider;
import io.agentscope.spring.boot.agui.common.AguiAgentRegistryCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;

import java.util.List;

/**
 * AG-UI 端点：注册 ontos agent 工厂，由 AgentScope starter 暴露 {prefix}/run/{agentId}（SSE）。
 *
 * <p>前端协议事件（TEXT_MESSAGE_* / TOOL_CALL_* / RUN_*）由框架适配器生成，
 * 不自研 SSE 事件模型。生产入口必须由网关做认证与限流。
 *
 * <p>候选集实现由业务模块提供（当前是 ontology 的发布清单），本模块只依赖
 * {@link AgentContextProvider} 接口，不做反向依赖。
 */
@Configuration
@ConditionalOnProperty(prefix = "ontos.agent", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OntosAgentConfiguration {

    @Bean
    public OntosAgentFactory ontosAgentFactory(AgentProperties properties, ResourceLoader resourceLoader,
                                               ObjectProvider<AgentContextProvider> contextProviders) {
        List<AgentContextProvider> providers = contextProviders.orderedStream().toList();
        return new OntosAgentFactory(properties, resourceLoader, providers);
    }

    @Bean
    public AguiAgentRegistryCustomizer ontosAguiAgentRegistryCustomizer(OntosAgentFactory agentFactory) {
        return registry -> registry.registerFactory("ontos", agentFactory::create);
    }

}

package com.ontos.framework.agent.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import com.ontos.framework.agent.config.AgentProperties;
import io.agentscope.core.agent.Agent;
import io.agentscope.harness.agent.HarnessAgent;
import org.springframework.core.io.ResourceLoader;

/**
 * Agent 工厂：每次请求创建一个新的 HarnessAgent 实例。
 *
 * <p>模型解析刻意放在 {@link #create()} 中而非构造期：缺少模型扩展或 API Key 时，
 * 确定性链路（/api/v1/**）仍可正常启动，只有真正调用 AG-UI 才会失败。
 * Bean 由 {@code OntosAgentConfiguration} 条件注册，关闭 agent 时本类完全不实例化。
 *
 * <p>身份不放在 Builder 里，请求级身份由 AG-UI 适配器传入的 RuntimeContext 决定。
 */
public class OntosAgentFactory {

    private final AgentProperties properties;
    private final ResourceLoader resourceLoader;

    public OntosAgentFactory(AgentProperties properties, ResourceLoader resourceLoader) {
        this.properties = properties;
        this.resourceLoader = resourceLoader;
    }

    public Agent create() {
        return HarnessAgent.builder()
                .name("ontos")
                .agentId("ontos")
                .sysPrompt(readPrompt())
                .model(properties.getModel())
                .workspace(properties.getWorkspace())
                .maxIters(properties.getMaxIters())
                .build();
    }

    private String readPrompt() {
        String location = properties.getSystemPrompt();
        try {
            return resourceLoader.getResource(location).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("读取系统提示词失败: " + location, ex);
        }
    }

}

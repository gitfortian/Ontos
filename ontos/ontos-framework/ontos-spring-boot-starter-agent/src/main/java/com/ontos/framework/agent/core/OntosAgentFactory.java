package com.ontos.framework.agent.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import com.ontos.framework.agent.config.AgentProperties;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.harness.agent.HarnessAgent;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.StringUtils;

/**
 * Agent 工厂：每次请求创建一个新的 HarnessAgent 实例。
 *
 * <p>模型解析刻意放在 {@link #create()} 中而非构造期：缺少凭据时，确定性链路（/api/v1/**）
 * 仍可正常启动，只有真正调用 AG-UI 才会失败。Bean 由 {@code OntosAgentConfiguration} 条件注册，
 * 关闭 agent 时本类完全不实例化。
 *
 * <p>凭据通过 {@link ModelCreationContext} 显式传入，不依赖 provider 约定环境变量名；
 * 同一个进程可同时持有多个不同端点的模型实例，缓存键包含上下文指纹（由框架负责），不会串号。
 *
 * <p>身份不放在 Builder 里，请求级身份由 AG-UI 适配器传入的 RuntimeContext 决定。
 */
public class OntosAgentFactory {

    private static final String DEFAULT_PROVIDER = "openai";

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
                .model(resolveModel())
                .workspace(properties.getWorkspace())
                .maxIters(properties.getMaxIters())
                .build();
    }

    /**
     * 解析模型。{@code model} 已带 provider 前缀时直接透传，否则按 OpenAI 协议族拼装。
     *
     * <p>失败一律抛 {@link IllegalStateException} 并带出可执行的排查线索，不吞异常降级成
     * 「能启动但每次回答都是空」——那会让配置错误在业务层表现为模型胡说。
     */
    private Model resolveModel() {
        String configured = properties.getModel();
        if (!StringUtils.hasText(configured)) {
            throw new IllegalStateException("模型未配置：请设置 ontos.agent.model");
        }
        String qualified = configured.contains(":") ? configured.trim() : DEFAULT_PROVIDER + ":" + configured.trim();

        ModelCreationContext.Builder context = ModelCreationContext.builder();
        if (StringUtils.hasText(properties.getApiKey())) {
            context.apiKey(properties.getApiKey().trim());
        }
        if (StringUtils.hasText(properties.getBaseUrl())) {
            context.baseUrl(properties.getBaseUrl().trim());
        }

        try {
            return ModelRegistry.resolve(qualified, context.build());
        } catch (RuntimeException ex) {
            throw new IllegalStateException(
                    "模型不可用：" + qualified + "。请检查 ontos.agent.api-key（环境变量 ONTOS_AGENT_API_KEY 或 OPENAI_API_KEY）"
                            + " 与 ontos.agent.base-url 是否正确；底层原因：" + ex.getMessage(),
                    ex);
        }
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

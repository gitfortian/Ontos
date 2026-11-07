package com.ontos.framework.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent 配置。
 *
 * <p>{@link #model} 形如 {@code provider:model-id}，前缀由
 * {@code agentscope-extensions-model-openai} 通过 ServiceLoader 注册，内置五个 provider：
 * <ul>
 *   <li>{@code openai:} —— 任意 OpenAI 协议兼容端点，端点由 {@code OPENAI_BASE_URL} 指定，默认官方地址</li>
 *   <li>{@code deepseek:} —— {@code DEEPSEEK_API_KEY}</li>
 *   <li>{@code kimi:} —— {@code KIMI_API_KEY} / {@code MOONSHOT_API_KEY}</li>
 *   <li>{@code glm:} —— {@code GLM_API_KEY} / {@code ZAI_API_KEY} / {@code ZHIPUAI_API_KEY}</li>
 *   <li>{@code minimax:} —— {@code MINIMAX_API_KEY}</li>
 * </ul>
 *
 * <p>凭据一律走环境变量，不落配置文件、不进仓库；缺失时确定性链路仍可启动，
 * 只有真正调用 AG-UI 才会失败（见 {@code OntosAgentFactory#create()} 的说明）。
 */
@Data
@ConfigurationProperties(prefix = "ontos.agent")
public class AgentProperties {

    /** 关闭后不注册 AG-UI agent，用于只跑确定性链路的集成环境。 */
    private boolean enabled = true;
    private String model = "openai:gpt-4o-mini";
    private String workspace = ".agentscope/workspace";
    private String systemPrompt = "classpath:prompts/system.md";
    private int maxIters = 12;

}

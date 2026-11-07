package com.ontos.framework.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent 配置。model 形如 dashscope:qwen-plus，需配合 Maven profile 引入模型扩展。
 */
@Data
@ConfigurationProperties(prefix = "ontos.agent")
public class AgentProperties {

    /** 关闭后不注册 AG-UI agent，用于只跑确定性链路的集成环境。 */
    private boolean enabled = true;
    private String model = "dashscope:qwen-plus";
    private String workspace = ".agentscope/workspace";
    private String systemPrompt = "classpath:prompts/system.md";
    private int maxIters = 12;

}

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
                // 安全边界：HarnessAgent 默认会装配 execute / write_file / edit_file / web_fetch /
                // agent_spawn 等通用工具，对「只做意图提案」的问数 agent 属越权面——
                // 模型可以据此读写宿主机文件、执行命令、访问外网、派生无限子 agent。
                // 实测：未关闭时模型会自发 glob_files / read_file / grep_files 找候选集，
                // 并通过 agent_spawn 派生 general-purpose-subagent，单次会话拖到 20 分钟以上。
                // M0 没有任何业务工具，因此把通用能力全部关掉；M1 接入本体检索时
                // 用 toolkit(...) 显式注册只读业务工具，而不是重新打开这些。
                .disableFilesystemTools()
                .disableShellTool()
                .disableWebTools()
                .disableDynamicSkills()
                .disableDefaultWorkspaceSkills()
                // meta tool 即 agent_spawn / agent_send / agent_list 一族，关闭后不再派生子 agent
                .enableMetaTool(false)
                // 注意：不要在这里传 toolkit(new Toolkit())。实测传空 Toolkit 会反而触发框架
                // 装配全套默认工具（22 个，含 execute/web_fetch），会话跑满超时且事件粒度被拆散。
                // memory_* / session_* / task_* / agent_* 这 13 个暂无公开开关关闭，
                // 登记为 M1 待办：用 PermissionContextState 的 deny 规则按工具名收敛。
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

package com.ontos.framework.agent.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import com.ontos.framework.agent.config.AgentProperties;
import com.ontos.framework.agent.trace.LlmCallOrigin;
import com.ontos.framework.agent.trace.LlmCallOriginProvider;
import com.ontos.framework.common.trace.LlmCallTraceSink;
import com.ontos.framework.agent.trace.TracingModel;
import com.ontos.framework.common.agent.AgentContextProvider;
import com.ontos.framework.common.agent.CandidateAsset;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.session.SessionHistoryMode;
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
 * <p>候选集由 {@link AgentContextProvider} 在每次构建时提供并追加到系统提示词：
 * 没有候选集时模型只能退化成「能力发现」或编造 ID——这是 PoC #3 终态重建失败的主因。
 *
 * <p>身份不放在 Builder 里，请求级身份由 AG-UI 适配器传入的 RuntimeContext 决定。
 */
public class OntosAgentFactory {

    private static final String DEFAULT_PROVIDER = "openai";

    private final AgentProperties properties;
    private final ResourceLoader resourceLoader;
    private final List<AgentContextProvider> contextProviders;
    private final List<LlmCallTraceSink> traceSinks;
    private final LlmCallOriginProvider originProvider;

    public OntosAgentFactory(AgentProperties properties, ResourceLoader resourceLoader) {
        this(properties, resourceLoader, List.of());
    }

    public OntosAgentFactory(AgentProperties properties, ResourceLoader resourceLoader,
                             List<AgentContextProvider> contextProviders) {
        this(properties, resourceLoader, contextProviders, List.of(), null);
    }

    /**
     * @param traceSinks     模型调用追踪落点（M7/W5/T5.1）；为空则不装饰模型，零开销
     * @param originProvider AG-UI 路径的调用来源解析；为空则 {@link #create()} 不记录追踪
     */
    public OntosAgentFactory(AgentProperties properties, ResourceLoader resourceLoader,
                             List<AgentContextProvider> contextProviders,
                             List<LlmCallTraceSink> traceSinks, LlmCallOriginProvider originProvider) {
        this.properties = properties;
        this.resourceLoader = resourceLoader;
        this.contextProviders = contextProviders == null ? List.of() : List.copyOf(contextProviders);
        this.traceSinks = traceSinks == null ? List.of() : List.copyOf(traceSinks);
        this.originProvider = originProvider;
    }

    /** 无显式来源：AG-UI 等由框架按请求创建的路径，经 {@link LlmCallOriginProvider} 解析身份。 */
    public Agent create() {
        return create(originProvider == null ? null : originProvider.current());
    }

    /**
     * 带调用来源创建（M7/W5/T5.1）：来源只影响追踪，不影响提示词、工具与模型参数。
     * 来源为空或不可落库时跳过追踪（不写无租户的脏行）。
     */
    public Agent create(LlmCallOrigin origin) {
        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name("ontos")
                .agentId("ontos")
                .sysPrompt(buildSystemPrompt())
                .model(traceModel(resolveModel(), origin))
                .workspace(properties.getWorkspace())
                .maxIters(properties.getMaxIters())
                // 每次构建使用独立 session：AguiAgentRegistry 只接受 Supplier<Agent>，
                // 注册层拿不到 AG-UI 的 threadId，若沿用默认 session 会让所有请求共享历史——
                // 实测 messages 从 2 一路涨到 11，越跑越慢直至 token 超限返回空。
                // 代价是 M0 不支持多轮上下文；M1 需要按 threadId 维护 agent 实例
                // （或在适配层把 threadId 映射成 sessionId），不能靠默认值。
                .defaultSessionId("ontos-" + UUID.randomUUID())
                // 会话历史改用 LEGACY 模式：默认的 EVENT_LOG 模式会在 session log 里做
                // 「未完成工具调用的显式对账」，配合每请求新 session 会抛
                // SessionLogException: Explicit reconciliation required for unknown tool calls，
                // 表现为请求立刻返回空内容（实测 20 次里 7 次）。
                .sessionHistoryMode(SessionHistoryMode.LEGACY)
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
                .enableMetaTool(false);
        // 输出契约校验（M1/T1.0，G2 修复项）：不合法的意图提案触发修复轮，超限 fail-closed；
        // 基于现役 middleware 扩展点（legacy hook 已标记删除，PoC-03 §5 的 API 漂移登记项）
        if (properties.isOutputValidation()) {
            builder.middleware(new IntentProposalRepairMiddleware(buildContract(), properties));
        }
        // 注意：不要在这里传 toolkit(new Toolkit())。实测传空 Toolkit 会反而触发框架
        // 装配全套默认工具（22 个，含 execute/web_fetch），会话跑满超时且事件粒度被拆散。
        // memory_* / session_* / task_* / agent_* 这 13 个暂无公开开关关闭，
        // 登记为 M1 待办：用 PermissionContextState 的 deny 规则按工具名收敛。
        return builder.build();
    }

    /**
     * 包装模型调用追踪（M7/W5/T5.1）。没有 sink 或没有可用来源时原样返回——
     * 未配置追踪的部署不该多一层装饰器（也不会产生空记录）。
     */
    private Model traceModel(Model model, LlmCallOrigin origin) {
        if (traceSinks.isEmpty() || origin == null || !origin.traceable()) {
            return model;
        }
        return new TracingModel(model, traceSinks, origin);
    }

    /** 候选资产词表：聚合全部 Provider，供输出契约做 ID 白名单与「点名资产」判定。 */
    private IntentProposalContract buildContract() {
        List<AgentContextProvider> providers = this.contextProviders;
        return new IntentProposalContract(() -> {
            java.util.List<CandidateAsset> vocabulary = new java.util.ArrayList<>();
            for (AgentContextProvider provider : providers) {
                vocabulary.addAll(provider.candidateVocabulary());
            }
            return java.util.List.copyOf(vocabulary);
        });
    }

    /** 系统提示词 + 候选集。候选集为空时保持提示词原样，不追加空段落。 */
    private String buildSystemPrompt() {
        String base = readPrompt();
        String candidates = contextProviders.stream()
                .map(AgentContextProvider::candidateContext)
                .filter(StringUtils::hasText)
                .collect(Collectors.joining("\n\n"));
        return candidates.isEmpty() ? base : base + "\n\n" + candidates;
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

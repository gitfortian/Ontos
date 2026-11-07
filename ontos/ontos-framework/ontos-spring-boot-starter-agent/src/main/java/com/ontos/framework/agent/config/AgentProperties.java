package com.ontos.framework.agent.config;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent 与模型配置，按标准 OpenAI 三件套组织：{@code model} + {@code base-url} + {@code api-key}。
 *
 * <p>不绑定具体厂商：只要目标服务兼容 OpenAI 的 {@code /chat/completions} 协议，
 * 把 {@code base-url} 指过去即可。凭据走环境变量注入，不写进仓库。
 *
 * <p>{@code model} 也允许写成 {@code provider:model-id} 的完整形式（如 {@code deepseek:deepseek-chat}），
 * 此时直接透传、不再拼 {@code openai:} 前缀——留给将来需要厂商特化表单的场景，当前不必用。
 */
@Data
@ConfigurationProperties(prefix = "ontos.agent")
public class AgentProperties {

    /** 关闭后不注册 AG-UI agent，用于只跑确定性链路的集成环境。 */
    private boolean enabled = true;

    /** 模型名，如 gpt-4o-mini / qwen-plus / deepseek-chat；也接受 provider:model-id 完整形式。 */
    private String model = "gpt-4o-mini";

    /** OpenAI 协议兼容端点，含版本段，如 https://api.openai.com/v1。 */
    private String baseUrl = "https://api.openai.com/v1";

    /** 密钥。唯一来源是环境变量；缺失时确定性链路照常启动，AG-UI 调用会失败并给出明确提示。 */
    @ToString.Exclude
    private String apiKey;

    private String workspace = ".agentscope/workspace";
    private String systemPrompt = "classpath:prompts/system.md";
    private int maxIters = 12;

    /** 输出契约校验开关（G2 修复项，ADR-0006）：关闭仅限本地排障，生产必须保持 true。 */
    private boolean outputValidation = true;

    /** 输出未过契约时的最大静默修复次数；超限改写为确定性拒答（fail-closed）。 */
    private int outputMaxRepairs = 2;

}

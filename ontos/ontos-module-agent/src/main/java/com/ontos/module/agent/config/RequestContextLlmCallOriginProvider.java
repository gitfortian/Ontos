package com.ontos.module.agent.config;

import com.ontos.framework.agent.trace.LlmCallOrigin;
import com.ontos.framework.agent.trace.LlmCallOriginProvider;
import com.ontos.framework.web.core.context.RequestContext;
import com.ontos.framework.web.core.context.RequestContextHolder;
import org.springframework.stereotype.Component;

/**
 * AG-UI 路径的模型调用来源解析（M7/W5/T5.1）。
 *
 * <p>AG-UI 端点由 AgentScope starter 按请求创建 agent，创建点在框架层、拿不到业务上下文；
 * 本实现从 {@link RequestContextHolder} 读身份（该路径同样是 Servlet 请求，过滤器已绑定上下文）。
 * 上下文缺失时返回 {@code null}——不记录，而不是写一条无租户的记录。
 *
 * <p>ask 编排路径不经过本类：它显式传入 {@code purpose=ask.intent} 的来源。
 */
@Component
public class RequestContextLlmCallOriginProvider implements LlmCallOriginProvider {

    /** AG-UI 直连端点的调用目的标记（与 ask 编排区分）。 */
    static final String PURPOSE_AGUI = "agui.run";

    @Override
    public LlmCallOrigin current() {
        RequestContext context = RequestContextHolder.get();
        if (context == null) {
            return null;
        }
        return LlmCallOrigin.of(context.tenantId(), context.traceId(), context.sessionId(),
                context.turnIndex(), PURPOSE_AGUI);
    }
}

package com.ontos.framework.web.core.context;

import java.util.Set;

/**
 * 服务端解析出的请求上下文。身份只能来自认证链路（网关注入并覆写），
 * 绝不允许由用户问题文本或请求体覆盖。
 */
public record RequestContext(
        String tenantId,
        String userId,
        String sessionId,
        int turnIndex,
        String manifestId,
        String traceId,
        Set<String> roles,
        boolean devMode,
        String approvalId) {

    public RequestContext {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }

    /** 兼容旧构造（无审批令牌）：现有调用点渐进迁移。 */
    public RequestContext(String tenantId, String userId, String sessionId, int turnIndex,
                          String manifestId, String traceId, Set<String> roles, boolean devMode) {
        this(tenantId, userId, sessionId, turnIndex, manifestId, traceId, roles, devMode, null);
    }

    public String sessionKey() {
        return tenantId + "|" + userId + "|" + sessionId;
    }
}

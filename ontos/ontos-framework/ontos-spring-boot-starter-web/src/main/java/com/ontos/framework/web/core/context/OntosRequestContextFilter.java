package com.ontos.framework.web.core.context;

import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.ontos.framework.web.config.WebProperties;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 从可信头构建请求上下文。
 *
 * <p>本过滤器不做鉴权本身，只做「身份解析 + 上下文传递」：缺少身份时上下文为空，
 * 业务入口调用 {@link RequestContextHolder#require()} 时 fail-closed。
 * 生产环境必须由网关校验凭证并覆写 X-Ontos-* 头，禁止浏览器直传。
 */
@Component("ontosRequestContextFilter")
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class OntosRequestContextFilter extends OncePerRequestFilter {

    private final WebProperties properties;

    public OntosRequestContextFilter(WebProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = header(request, OntosHeaders.REQUEST_ID, UUID.randomUUID().toString());
        MDC.put("traceId", traceId);
        try {
            String tenantId = header(request, OntosHeaders.TENANT, null);
            String userId = header(request, OntosHeaders.USER, null);
            boolean devMode = false;

            if (properties.getSecurity().isDevHeaders()
                    && (!StringUtils.hasText(tenantId) || !StringUtils.hasText(userId))) {
                tenantId = StringUtils.hasText(tenantId) ? tenantId : "demo";
                userId = StringUtils.hasText(userId) ? userId : "demo";
                devMode = true;
            }

            if (StringUtils.hasText(tenantId) && StringUtils.hasText(userId)) {
                String sessionId = header(request, OntosHeaders.SESSION, "default");
                int turnIndex = parseTurn(header(request, OntosHeaders.TURN, "0"));
                String manifestId = header(request, OntosHeaders.MANIFEST, null);
                Set<String> roles = parseRoles(header(request, OntosHeaders.ROLES, ""));
                RequestContextHolder.set(new RequestContext(
                        tenantId, userId, sessionId, turnIndex, manifestId, traceId, roles, devMode));
            }

            response.setHeader("X-Request-Id", traceId);
            chain.doFilter(request, response);
        } finally {
            RequestContextHolder.clear();
            MDC.remove("traceId");
        }
    }

    private static String header(HttpServletRequest request, String name, String fallback) {
        String value = request.getHeader(name);
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private static int parseTurn(String raw) {
        try {
            return Math.max(0, Integer.parseInt(raw));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static Set<String> parseRoles(String raw) {
        if (!StringUtils.hasText(raw)) {
            return Set.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .collect(Collectors.toUnmodifiableSet());
    }
}

package com.ontos.framework.web.core.context;

import com.ontos.framework.common.exception.ServiceException;
import com.ontos.framework.common.exception.enums.GlobalErrorCodeConstants;

/**
 * 请求上下文 ThreadLocal。异步/流式分支必须显式传递上下文，不能依赖线程继承。
 */
public final class RequestContextHolder {

    private static final ThreadLocal<RequestContext> CURRENT = new ThreadLocal<>();

    private RequestContextHolder() {
    }

    public static void set(RequestContext context) {
        CURRENT.set(context);
    }

    public static RequestContext get() {
        return CURRENT.get();
    }

    public static RequestContext require() {
        RequestContext context = CURRENT.get();
        if (context == null) {
            throw new ServiceException(GlobalErrorCodeConstants.UNAUTHORIZED);
        }
        return context;
    }

    public static String traceId() {
        RequestContext context = CURRENT.get();
        return context == null ? null : context.traceId();
    }

    public static void clear() {
        CURRENT.remove();
    }
}

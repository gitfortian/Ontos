package com.ontos.framework.agent.trace;

/**
 * 当前请求的调用来源解析（M7/W5/T5.1）。
 *
 * <p>AG-UI 端点的 agent 由框架按请求创建，创建点不在业务模块里，拿不到 {@code RequestContext}；
 * 由业务模块提供本接口实现（从请求上下文读身份），框架在 {@code create()} 时回调。
 * 没有实现 Bean 时返回 {@code null}，即该路径不记录追踪。
 */
public interface LlmCallOriginProvider {

    LlmCallOrigin current();
}

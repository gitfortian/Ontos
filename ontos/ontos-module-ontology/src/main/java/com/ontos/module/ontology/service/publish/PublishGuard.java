package com.ontos.module.ontology.service.publish;

/**
 * 发布闸门端口：身份与策略的解析在装配点实现（对齐 AgentContextProvider 的端口模式），
 * 保持 ontology 模块不依赖 web 上下文与 system 策略（依赖方向由 ArchUnit 守护）。
 *
 * <p>实现语义：{@link #requirePublishAllowed} 在策略未放行时抛 FORBIDDEN（fail-closed）——
 * M4 策略快照落地前，非 local profile 下发布链路整体 403。
 */
public interface PublishGuard {

    /** 策略闸门：未放行即抛异常（fail-closed）。 */
    void requirePublishAllowed(String assetId);

    /** 当前发布人（取自认证上下文）。 */
    String currentPublisher();

}

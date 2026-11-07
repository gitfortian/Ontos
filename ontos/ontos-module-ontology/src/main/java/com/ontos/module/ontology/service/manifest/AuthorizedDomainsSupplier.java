package com.ontos.module.ontology.service.manifest;

import java.util.Set;

/**
 * 授权域供给端口（M1/T6.2 最小实现，端口模式对齐 AgentContextProvider / PublishGuard）：
 * 本体模块不依赖 system 策略（ArchUnit 守护），实现在装配点从策略配置读取。
 *
 * <p>语义：返回授权域集合，规划子图按域收窄；空集合 = 全域调试态（仅 local 语义）。
 * 真实「身份 → 域」映射由 M4 网关策略接管。
 */
public interface AuthorizedDomainsSupplier {

    /** 授权域集合；空集合表示全域调试态。 */
    Set<String> get();

}

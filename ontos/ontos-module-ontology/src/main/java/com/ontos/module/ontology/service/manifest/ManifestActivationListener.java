package com.ontos.module.ontology.service.manifest;

/**
 * 清单激活后的副作用端口（M2/W4/T4.4）。
 *
 * <p>为什么是端口而不是直接调用：ontology 不能反向依赖 ontos-module-query（ArchUnit 守护方向），
 * 而「激活后回收旧版本缓存键」的实现天然需要 query 侧的缓存仓储。实现方由 query 模块提供，
 * 这里只定义时机。
 *
 * <p>契约：实现方必须自行吞掉失败——缓存清理是空间回收，不允许影响激活结果
 * （ADR-0008 决策 2：正确性不依赖清理）。
 */
public interface ManifestActivationListener {

    /**
     * @param manifestId 刚被激活的清单 ID；实现方据此回收「其它版本」的缓存键
     */
    void afterManifestActivated(String manifestId);

}

/**
 * 资产管理面（M6/W2，M6 规格 §3.0 G-1）：Scenario / Skill / VQR 的资产生命周期、
 * 发布流水线与发布校验。
 *
 * <p><b>边界</b>：本模块只有管理面职责。运行时（入口路由、Skill 注册表、VQR 候选匹配）
 * 一律读已发布清单的 scenarios / skills / vqrs 扩展段，经 {@code PublishedManifest} 消费；
 * query / system / session / agent 不得依赖本模块，只有 server 负责装配。
 */
package com.ontos.module.catalog;

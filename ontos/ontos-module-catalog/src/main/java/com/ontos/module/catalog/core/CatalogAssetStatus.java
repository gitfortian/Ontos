package com.ontos.module.catalog.core;

import java.util.Map;
import java.util.Set;

/**
 * Scenario / Skill / VQR 的统一资产生命周期（M6 规格 §3.2–§3.4、ADR-0004 §2.3 同构）。
 *
 * <p>与清单内 {@code rules.status} 的枚举保持一致（draft / reviewing / published / retired），
 * 避免同一套资产生命周期出现两种词汇。回滚不是状态迁移：以「新版本 draft → published、
 * 旧版本 retired」表达，清单不可变、只增版本。
 *
 * <p>只有 published 资产允许进入发布清单；draft / reviewing / retired 一律不得被运行时读取。
 */
public enum CatalogAssetStatus {

    DRAFT,
    REVIEWING,
    PUBLISHED,
    RETIRED;

    private static final Map<CatalogAssetStatus, Set<CatalogAssetStatus>> ALLOWED = Map.of(
            DRAFT, Set.of(REVIEWING, RETIRED),
            REVIEWING, Set.of(DRAFT, PUBLISHED),
            PUBLISHED, Set.of(RETIRED));

    /** 清单/审计使用的稳定取值（小写，与 rules.status 同形）。 */
    public String code() {
        return name().toLowerCase();
    }

    public boolean isTerminal() {
        return this == RETIRED;
    }

    /** 迁移合法性：终态无出边，未登记的起点一律非法（沿用请求状态机的封闭口径）。 */
    public boolean canTransitionTo(CatalogAssetStatus target) {
        return ALLOWED.getOrDefault(this, Set.of()).contains(target);
    }
}

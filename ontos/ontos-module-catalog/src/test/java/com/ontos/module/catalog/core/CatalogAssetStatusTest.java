package com.ontos.module.catalog.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 资产生命周期状态机锁定（M6/T2.1）：与 rules.status 同词表，回滚以新版本表达。
 */
class CatalogAssetStatusTest {

    @Test
    void shouldAllowOnlyForwardLifecycle() {
        assertTrue(CatalogAssetStatus.DRAFT.canTransitionTo(CatalogAssetStatus.REVIEWING));
        assertTrue(CatalogAssetStatus.REVIEWING.canTransitionTo(CatalogAssetStatus.PUBLISHED));
        assertTrue(CatalogAssetStatus.REVIEWING.canTransitionTo(CatalogAssetStatus.DRAFT));
        assertTrue(CatalogAssetStatus.PUBLISHED.canTransitionTo(CatalogAssetStatus.RETIRED));
        assertTrue(CatalogAssetStatus.DRAFT.canTransitionTo(CatalogAssetStatus.RETIRED));
    }

    @Test
    void shouldRejectSkippingReviewAndLeavingRetired() {
        assertFalse(CatalogAssetStatus.DRAFT.canTransitionTo(CatalogAssetStatus.PUBLISHED),
                "草稿不得跳过人工审核直接发布");
        assertFalse(CatalogAssetStatus.PUBLISHED.canTransitionTo(CatalogAssetStatus.DRAFT),
                "回滚以新版本表达，已发布版本不得回退成草稿");
        for (CatalogAssetStatus target : CatalogAssetStatus.values()) {
            assertFalse(CatalogAssetStatus.RETIRED.canTransitionTo(target), "retired 是终态");
        }
    }

    @Test
    void shouldUseRuleStatusVocabulary() {
        assertEquals("draft", CatalogAssetStatus.DRAFT.code());
        assertEquals("reviewing", CatalogAssetStatus.REVIEWING.code());
        assertEquals("published", CatalogAssetStatus.PUBLISHED.code());
        assertEquals("retired", CatalogAssetStatus.RETIRED.code());
        assertTrue(CatalogAssetStatus.RETIRED.isTerminal());
        assertFalse(CatalogAssetStatus.PUBLISHED.isTerminal());
    }
}

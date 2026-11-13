package com.ontos.module.catalog.service.asset;

import java.time.Instant;

import com.ontos.framework.common.exception.ServiceException;
import com.ontos.module.catalog.dal.dataobject.SemanticAssetRecord;
import com.ontos.module.catalog.support.InMemorySemanticAssetRepository;
import com.ontos.module.catalog.support.TestManifests;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 生效资产修订：清单为内容事实源、待发布草稿拒改、published 回迁清 published_at、并发显式冲突。 */
class SemanticAssetRevisionServiceTest {

    private final InMemorySemanticAssetRepository repository = new InMemorySemanticAssetRepository();
    private final SemanticAssetRevisionService service = new SemanticAssetRevisionService(
            repository, TestManifests.manifestService(TestManifests.activeV22()));

    @Test
    void seedsDraftFromManifestWhenRowAbsent() {
        // v22 基线资产不经草稿工作流，草稿表无行：修订按清单定义播种
        SemanticAssetView view = service.revise("concept", "concept.customer", "alice");

        assertEquals("draft", view.status());
        assertEquals("alice", view.owner());
        assertEquals(0L, view.revision());
        assertTrue(view.definitionJson().contains("\"concept.customer\""));
        assertTrue(view.definitionJson().contains("客户"));
        assertEquals("客户", repository.find("concept", "concept.customer").orElseThrow().getName());
    }

    @Test
    void revisePublishedRowBackToDraftClearingPublishedAt() {
        repository.insert(published("concept", "concept.customer", 2));

        SemanticAssetView view = service.revise("concept", "concept.customer", "bob");

        assertEquals("draft", view.status());
        assertNull(view.publishedAt());
        assertEquals(3L, view.revision());
        // 内容以生效清单为准：行内滞留副本被清单定义覆盖
        assertTrue(view.definitionJson().contains("\"concept.customer\""));
    }

    @Test
    void reviseRejectedWhenPendingDraftExists() {
        repository.insert(pending("draft"));

        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.revise("concept", "concept.customer", "alice"));
        assertEquals(1_005_000_103, exception.getCode());
    }

    @Test
    void reviseRejectedWhenAssetNotInManifest() {
        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.revise("concept", "concept.not-there", "alice"));
        assertEquals(1_005_000_101, exception.getCode());
    }

    @Test
    void reviseRejectedWhenManifestMissing() {
        SemanticAssetRevisionService withoutManifest = new SemanticAssetRevisionService(
                repository, TestManifests.manifestService(null));

        ServiceException exception = assertThrows(ServiceException.class,
                () -> withoutManifest.revise("concept", "concept.customer", "alice"));
        assertEquals(1_005_000_106, exception.getCode());
    }

    @Test
    void concurrentMigrationFailsLoudly() {
        repository.insert(published("concept", "concept.customer", 2));
        // 模拟并发迁移：reviseToDraft 恒 0 行必须显式冲突，不静默
        SemanticAssetRevisionService conflicting = new SemanticAssetRevisionService(
                new InMemorySemanticAssetRepository() {
                    @Override
                    public java.util.Optional<SemanticAssetRecord> find(String assetType, String id) {
                        return repository.find(assetType, id);
                    }

                    @Override
                    public int reviseToDraft(String assetType, String id, String definitionJson,
                                             String name, String summary, long expectedRevision) {
                        return 0;
                    }
                }, TestManifests.manifestService(TestManifests.activeV22()));

        ServiceException exception = assertThrows(ServiceException.class,
                () -> conflicting.revise("concept", "concept.customer", "alice"));
        assertEquals(1_005_000_104, exception.getCode());
    }

    private static SemanticAssetRecord published(String assetType, String id, long revision) {
        SemanticAssetRecord record = new SemanticAssetRecord();
        record.setAssetType(assetType);
        record.setId(id);
        record.setName("滞留副本");
        record.setSummary("清单回滚后可能不一致的行内副本");
        record.setStatus("published");
        record.setOwner("tester");
        record.setVersion("1");
        record.setRevision(revision);
        record.setDefinitionJson("{\"id\":\"concept.customer\",\"name\":\"旧名\"}");
        record.setPublishedAt(Instant.now());
        return record;
    }

    private static SemanticAssetRecord pending(String status) {
        SemanticAssetRecord record = new SemanticAssetRecord();
        record.setAssetType("concept");
        record.setId("concept.customer");
        record.setName("待发布");
        record.setSummary("已有 draft/reviewing 行时修订必须拒绝");
        record.setStatus(status);
        record.setOwner("tester");
        record.setVersion("1");
        record.setRevision(0);
        record.setDefinitionJson("{\"id\":\"concept.customer\",\"name\":\"待发布\"}");
        return record;
    }
}

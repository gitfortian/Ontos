package com.ontos.module.catalog.service.asset;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.ontos.framework.common.exception.ServiceException;
import com.ontos.module.catalog.dal.dataobject.SemanticAssetRecord;
import com.ontos.module.catalog.dal.mysql.SemanticAssetRepository;
import com.ontos.module.catalog.support.InMemorySemanticAssetRepository;
import com.ontos.module.catalog.support.StubPublishService;
import com.ontos.module.catalog.support.TestManifests;
import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.ontology.service.manifest.ManifestService;
import com.ontos.module.ontology.service.publish.PublishService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 合成发布编排：批次合并、ID 递增、成批校验拒绝与状态落定。 */
class SemanticAssetSynthesisServiceTest {

    private static final DateTimeFormatter ID_DATE = DateTimeFormatter.ofPattern("yyyy.MM.dd");

    private final InMemorySemanticAssetRepository repository = new InMemorySemanticAssetRepository();
    private final StubPublishService publishService = new StubPublishService();
    private final List<String> activated = new ArrayList<>();
    private final ManifestService manifestService = new ManifestService() {
        @Override
        public PublishedManifest getActiveManifestOrNull() {
            return TestManifests.activeV22();
        }

        @Override
        public PublishedManifest validateManifest(String requestedManifestId) {
            throw new UnsupportedOperationException("test");
        }

        @Override
        public Optional<PublishedManifest> getManifest(String manifestId) {
            return Optional.empty();
        }

        @Override
        public void loadActiveManifest() {
        }

        @Override
        public PublishedManifest activate(String manifestId) {
            activated.add(manifestId);
            return TestManifests.activeV22();
        }
    };
    private final SemanticAssetSynthesisService service =
            new SemanticAssetSynthesisService(repository, manifestService, publishService);

    private static SemanticAssetRecord reviewing(String assetType, String id, String definition) {
        SemanticAssetRecord record = new SemanticAssetRecord();
        record.setAssetType(assetType);
        record.setId(id);
        record.setName(id);
        record.setSummary("冒烟");
        record.setStatus("reviewing");
        record.setOwner("tester");
        record.setVersion("1");
        record.setRevision(0);
        record.setDefinitionJson(definition);
        return record;
    }

    @Test
    void emptyReviewingBatchIsRejected() {
        ServiceException exception = assertThrows(ServiceException.class, service::synthesize);
        assertEquals(1_005_000_109, exception.getCode());
    }

    @Test
    void synthesizesBatchIntoNewManifestAndMarksPublished() {
        repository.insert(reviewing("concept", "concept.warehouse",
                "{\"id\":\"concept.warehouse\",\"name\":\"仓库\"}"));
        repository.insert(reviewing("property", "property.warehouse.name",
                "{\"id\":\"property.warehouse.name\",\"conceptId\":\"concept.warehouse\"}"));

        SemanticAssetSynthesisReport report = service.synthesize();

        String expectedPrefix = LocalDate.now().format(ID_DATE) + "-";
        assertEquals(expectedPrefix + "first", report.manifestId());
        assertEquals("hash-" + report.manifestId(), report.contentHash());
        assertEquals("2026.11.12-twenty-second", report.previousManifestId());
        assertEquals(2, report.published().size());
        assertEquals(List.of(report.manifestId()), activated);

        PublishService.PublishRequest confirm = publishService.requests().stream()
                .filter(request -> !request.dryRun())
                .findFirst()
                .orElseThrow();
        assertTrue(String.valueOf(confirm.payload()).contains("concept.warehouse"));
        assertTrue(String.valueOf(confirm.payload()).contains("property.warehouse.name"));

        assertNotNull(repository.find("concept", "concept.warehouse").orElseThrow()
                .getPublishedAt());
        assertEquals("published", repository.find("property", "property.warehouse.name")
                .orElseThrow().getStatus());
    }

    @Test
    void occupiedManifestIdIncrementsOrdinal() {
        repository.insert(reviewing("concept", "concept.warehouse",
                "{\"id\":\"concept.warehouse\",\"name\":\"仓库\"}"));
        publishService.alreadyExistsFor(manifestId -> manifestId.endsWith("-first"));

        SemanticAssetSynthesisReport report = service.synthesize();

        assertEquals(LocalDate.now().format(ID_DATE) + "-second", report.manifestId());
    }

    @Test
    void validationRejectionAbortsBatch() {
        repository.insert(reviewing("concept", "concept.warehouse",
                "{\"id\":\"concept.warehouse\",\"name\":\"仓库\"}"));
        publishService.rejectWith("payload.ontology.badField: 未知字段");

        ServiceException exception = assertThrows(ServiceException.class, service::synthesize);
        assertEquals(1_005_000_111, exception.getCode());
        assertTrue(exception.getMessage().contains("未知字段"));
    }

    @Test
    void concurrentRevisionDuringMarkPublishedFailsLoudly() {
        repository.insert(reviewing("concept", "concept.warehouse",
                "{\"id\":\"concept.warehouse\",\"name\":\"仓库\"}"));
        // 模拟落定阶段的并发修订：markPublished 0 行（期望 revision 落空）必须显式冲突，不静默
        SemanticAssetSynthesisService failing = new SemanticAssetSynthesisService(
                new InMemorySemanticAssetRepository() {
                    @Override
                    public List<SemanticAssetRecord> findAllByStatus(String status) {
                        return repository.findAllByStatus(status);
                    }

                    @Override
                    public int markPublished(String assetType, String id, long expectedRevision) {
                        return 0;
                    }
                }, manifestService, publishService);

        ServiceException exception = assertThrows(ServiceException.class, failing::synthesize);
        assertEquals(1_005_000_104, exception.getCode());
    }
}

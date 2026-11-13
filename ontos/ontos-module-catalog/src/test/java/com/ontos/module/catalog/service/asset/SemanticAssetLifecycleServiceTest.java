package com.ontos.module.catalog.service.asset;

import java.util.List;

import com.ontos.framework.common.exception.ServiceException;
import com.ontos.module.catalog.core.SemanticAssetType;
import com.ontos.module.catalog.service.asset.SemanticAssetView;
import com.ontos.module.catalog.support.InMemorySemanticAssetRepository;
import com.ontos.module.ontology.core.ManifestStructureValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 语义资产草稿生命周期（B1/W2）：状态机只放行 draft ⇄ reviewing、乐观锁、ID 不可变、类型前缀。
 */
class SemanticAssetLifecycleServiceTest {

    private InMemorySemanticAssetRepository repository;
    private SemanticAssetLifecycleService service;

    @BeforeEach
    void setUp() {
        repository = new InMemorySemanticAssetRepository();
        service = new SemanticAssetLifecycleService(repository, fixedValidator(true));
    }

    @Test
    void shouldCreateDraftWithDefaults() {
        SemanticAssetView record = service.createDraft("metric", metricJson("metric.gmv", "GMV"), "alice");

        assertEquals("draft", record.status());
        assertEquals("alice", record.owner());
        assertEquals("0.1.0", record.version());
        assertEquals(0L, record.revision());
        assertEquals("GMV", record.name());
    }

    @Test
    void shouldRejectDuplicateDraft() {
        service.createDraft("metric", metricJson("metric.gmv", "GMV"), "alice");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.createDraft("metric", metricJson("metric.gmv", "GMV"), "bob"));
        assertEquals(1_005_000_102, ex.getCode());
    }

    @Test
    void shouldRejectIdPrefixMismatch() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.createDraft("metric", metricJson("dim.region", "区域"), "alice"));
        assertEquals(1_005_000_107, ex.getCode());
    }

    @Test
    void shouldSubmitForReview() {
        service.createDraft("metric", metricJson("metric.gmv", "GMV"), "alice");

        SemanticAssetView record = service.submitReview("metric", "metric.gmv", 0L);

        assertEquals("reviewing", record.status());
        assertEquals(1L, record.revision());
    }

    @Test
    void shouldWithdrawReview() {
        service.createDraft("metric", metricJson("metric.gmv", "GMV"), "alice");
        service.submitReview("metric", "metric.gmv", 0L);

        SemanticAssetView record = service.withdrawReview("metric", "metric.gmv", 1L);

        assertEquals("draft", record.status());
        assertEquals(2L, record.revision());
    }

    @Test
    void shouldRejectWithdrawWhenDraft() {
        service.createDraft("metric", metricJson("metric.gmv", "GMV"), "alice");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.withdrawReview("metric", "metric.gmv", 0L));
        assertEquals(1_005_000_103, ex.getCode());
    }

    @Test
    void shouldRejectSaveWhenReviewing() {
        service.createDraft("metric", metricJson("metric.gmv", "GMV"), "alice");
        service.submitReview("metric", "metric.gmv", 0L);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.saveDraft("metric", "metric.gmv", metricJson("metric.gmv", "GMV2"), 1L));
        assertEquals(1_005_000_103, ex.getCode());
    }

    @Test
    void shouldRejectStaleRevision() {
        service.createDraft("metric", metricJson("metric.gmv", "GMV"), "alice");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.saveDraft("metric", "metric.gmv", metricJson("metric.gmv", "GMV2"), 5L));
        assertEquals(1_005_000_104, ex.getCode());
    }

    @Test
    void shouldRejectIdChangeOnSave() {
        service.createDraft("metric", metricJson("metric.gmv", "GMV"), "alice");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.saveDraft("metric", "metric.gmv",
                        metricJson("metric.paid_gmv", "改 ID"), 0L));
        assertEquals(1_005_000_108, ex.getCode());
    }

    @Test
    void shouldRejectSubmitWhenValidationFails() {
        SemanticAssetLifecycleService failing = new SemanticAssetLifecycleService(
                repository, fixedValidator(false));
        failing.createDraft("metric", metricJson("metric.gmv", "GMV"), "alice");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> failing.submitReview("metric", "metric.gmv", 0L));
        assertEquals(1_005_000_106, ex.getCode());
        assertTrue(ex.getMessage().contains("bad"));
    }

    private static SemanticAssetDraftValidator fixedValidator(boolean valid) {
        return new SemanticAssetDraftValidator(null, null) {
            @Override
            public ValidationResult validate(SemanticAssetType type, String definitionJson) {
                return valid
                        ? new ValidationResult(true, List.of())
                        : new ValidationResult(false, List.of(
                                new ManifestStructureValidator.Violation("metric.metric.gmv", "bad")));
            }
        };
    }

    private static String metricJson(String id, String name) {
        return """
                {"id":"%s","name":"%s","summary":"s"}
                """.formatted(id, name);
    }
}

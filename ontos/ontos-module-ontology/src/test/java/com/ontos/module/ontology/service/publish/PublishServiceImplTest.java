package com.ontos.module.ontology.service.publish;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.ontos.framework.common.exception.ServiceException;
import com.ontos.module.ontology.core.ManifestPayload;
import com.ontos.module.ontology.core.OntologySnapshot;
import com.ontos.module.ontology.core.SemanticManifest;
import com.ontos.module.ontology.dal.mysql.PublishedSnapshotRepository;
import com.ontos.module.ontology.service.publish.PublishService.PublishRequest;
import com.ontos.module.ontology.service.publish.PublishService.PublishReportVO;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublishServiceImplTest {

    private final PublishedSnapshotRepository repository = mock(PublishedSnapshotRepository.class);
    private final PublishGuard guard = mock(PublishGuard.class);
    private final PublishServiceImpl service = new PublishServiceImpl(repository, guard);

    /** 最小合法载荷（业务字段齐全）。 */
    private ManifestPayload validPayload() {
        SemanticManifest.Metric metric = new SemanticManifest.Metric();
        metric.setId("metric.gmv");
        metric.setName("GMV");
        metric.setSummary("成交金额合计");
        metric.setAliases(List.of("成交金额"));
        metric.setTable("orders");
        metric.setColumn("amount");
        metric.setAggregation(SemanticManifest.Aggregation.SUM);
        metric.setAllowedModes(List.of("QUERY"));
        SemanticManifest semantic = new SemanticManifest();
        semantic.setSemanticVersion("sem-1");
        semantic.setMetrics(List.of(metric));
        OntologySnapshot ontology = new OntologySnapshot();
        ontology.setOntologyVersion("ont-1");
        ontology.setConcepts(List.of());
        ontology.setRelations(List.of());
        ManifestPayload payload = new ManifestPayload();
        payload.setSemantic(semantic);
        payload.setOntology(ontology);
        return payload;
    }

    private PublishRequest request(boolean dryRun) {
        return new PublishRequest("manifest-test", "git-ref", dryRun, validPayload());
    }

    @Test
    void shouldPassDryRunWhenPayloadValid() {
        when(repository.selectByManifestId("manifest-test")).thenReturn(Optional.empty());

        PublishReportVO report = service.publish(request(true));

        assertTrue(report.valid());
        verify(repository).insertPublishRecord("manifest-test", "dry-run", true, "git-ref", null);
        verify(repository, never()).insertSnapshot(anyString(), anyString(), anyString());
    }

    @Test
    void shouldReportViolationsOnDryRunWithoutInsert() {
        ManifestPayload payload = validPayload();
        payload.getSemantic().getMetrics().get(0).setAliases(List.of()); // 业务化必填缺失
        when(repository.selectByManifestId("manifest-test")).thenReturn(Optional.empty());

        PublishReportVO report = service.publish(new PublishRequest("manifest-test", null, true, payload));

        assertFalse(report.valid());
        assertTrue(report.violations().stream().anyMatch(v -> v.contains("aliases")));
        verify(repository, never()).insertSnapshot(anyString(), anyString(), anyString());
    }

    @Test
    void shouldRejectPublishWhenViolationsPresent() {
        ManifestPayload payload = validPayload();
        payload.getSemantic().getMetrics().get(0).setColumn("orders.Amount"); // 物理名违规
        when(repository.selectByManifestId("manifest-test")).thenReturn(Optional.empty());
        when(guard.currentPublisher()).thenReturn("tester");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.publish(new PublishRequest("manifest-test", "git-ref", false, payload)));

        assertTrue(ex.getMessage().contains("ADR-0007"));
        verify(repository, never()).insertSnapshot(anyString(), anyString(), anyString());
    }

    @Test
    void shouldRejectPublishWhenManifestIdExists() {
        when(repository.selectByManifestId("manifest-test")).thenReturn(Optional.of(new com.ontos.module.ontology.dal.dataobject.PublishedSnapshotDO()));

        ServiceException ex = assertThrows(ServiceException.class, () -> service.publish(request(false)));

        assertTrue(ex.getMessage().contains("不可变"));
        verify(repository, never()).insertSnapshot(anyString(), anyString(), anyString());
    }

    @Test
    void shouldInsertWithComputedHashOnConfirm() {
        when(repository.selectByManifestId("manifest-test")).thenReturn(Optional.empty());
        when(guard.currentPublisher()).thenReturn("tester");
        when(repository.insertSnapshot(Mockito.eq("manifest-test"), anyString(), Mockito.eq("tester")))
                .thenReturn("abc123");

        PublishReportVO report = service.publish(request(false));

        assertTrue(report.valid());
        assertEquals("abc123", report.contentHash());
        verify(repository).insertSnapshot(Mockito.eq("manifest-test"), anyString(), anyString());
        verify(repository).insertPublishRecord("manifest-test", "abc123", false, "git-ref", "tester");
    }

}

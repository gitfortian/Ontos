package com.ontos.module.catalog.service.scenario;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.ontos.framework.common.exception.ServiceException;
import com.ontos.module.catalog.dal.dataobject.ScenarioRecord;
import com.ontos.module.catalog.dal.mysql.ScenarioRepository;
import com.ontos.module.ontology.core.ManifestPayload;
import com.ontos.module.ontology.core.OntologySnapshot;
import com.ontos.module.ontology.core.SemanticManifest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scenario 生命周期与依赖影响（M6/W2/T2.2）：状态机、发布阻断、回滚、影响反查。
 */
class ScenarioLifecycleServiceTest {

    private InMemoryScenarioRepository repository;
    private ScenarioLifecycleService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryScenarioRepository();
        service = new ScenarioLifecycleService(repository, new ScenarioDependencyAnalyzer());
    }

    @Test
    void shouldCreateDraftWithDefaults() {
        ManifestPayload.ScenarioMeta definition = scenario();
        definition.setOwner(null);
        definition.setVersion(null);
        ScenarioRecord record = service.createDraft(definition, "alice");

        assertEquals("draft", record.getStatus());
        assertEquals("alice", record.getOwner());
        assertEquals("0.1.0", record.getVersion());
        assertEquals("draft", record.getDefinition().getStatus());
        assertNull(record.getPublishedAt());
    }

    @Test
    void shouldPublishOnlyAfterReview() {
        service.createDraft(scenario(), "alice");
        service.submitForReview("scenario.sales");

        ServiceException notReviewed = assertThrows(ServiceException.class,
                () -> service.publish("scenario.sales", payload()));
        assertEquals(1_005_000_004, notReviewed.getCode());

        service.review("scenario.sales", "bob", true);
        ScenarioRecord published = service.publish("scenario.sales", payload());
        assertEquals("published", published.getStatus());
        assertEquals("bob", published.getReviewer());
        assertNotNull(published.getPublishedAt());
    }

    @Test
    void shouldRejectPublishWhenTargetManifestMissesAssets() {
        ManifestPayload.ScenarioMeta definition = scenario();
        definition.getSemanticScope().setMetrics(List.of("metric.unknown"));
        service.createDraft(definition, "alice");
        service.submitForReview("scenario.sales");
        service.review("scenario.sales", "bob", true);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.publish("scenario.sales", payload()));
        assertEquals(1_005_000_005, ex.getCode());
        assertTrue(ex.getMessage().contains("metric.unknown"));
    }

    @Test
    void shouldRejectIllegalTransition() {
        service.createDraft(scenario(), "alice");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.publish("scenario.sales", payload()));
        assertEquals(1_005_000_003, ex.getCode());
    }

    @Test
    void shouldRetireAndKeepPublishedAt() {
        ScenarioRecord published = publishThroughReview();
        ScenarioRecord retired = service.retire("scenario.sales");

        assertEquals("retired", retired.getStatus());
        assertEquals(published.getPublishedAt(), retired.getPublishedAt());
    }

    @Test
    void shouldRollbackToNextPatchDraft() {
        publishThroughReview();
        ManifestPayload.ScenarioMeta snapshot = scenario();
        snapshot.setVersion("1.0.0");

        ScenarioRecord rolledBack = service.rollback("scenario.sales", snapshot, "carol");
        assertEquals("draft", rolledBack.getStatus());
        assertEquals("1.0.1", rolledBack.getVersion());
        assertNull(rolledBack.getReviewer());
        assertNull(rolledBack.getPublishedAt());
        assertEquals("project-team", rolledBack.getOwner(), "回滚保留原 owner，操作人只作缺省值");
    }

    @Test
    void shouldRejectRollbackOnDraft() {
        service.createDraft(scenario(), "alice");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.rollback("scenario.sales", scenario(), "carol"));
        assertEquals(1_005_000_003, ex.getCode());
    }

    @Test
    void shouldRejectDuplicateCreate() {
        service.createDraft(scenario(), "alice");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.createDraft(scenario(), "alice"));
        assertEquals(1_005_000_002, ex.getCode());
    }

    @Test
    void shouldRejectRollbackSnapshotWithNonSemverVersion() {
        publishThroughReview();
        ManifestPayload.ScenarioMeta snapshot = scenario();
        snapshot.setVersion("2026.11");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.rollback("scenario.sales", snapshot, "carol"));
        assertEquals(1_005_000_008, ex.getCode());
    }

    @Test
    void shouldReportImpactOnlyForPublishedScenarios() {
        service.createDraft(scenario(), "alice");
        assertTrue(service.impact("metric.gmv").isEmpty(), "草稿不进影响面");

        service.submitForReview("scenario.sales");
        service.review("scenario.sales", "bob", true);
        service.publish("scenario.sales", payload());

        assertEquals(List.of("scenario.sales"), service.impact("metric.gmv"));
        assertEquals(List.of("scenario.sales"), service.impact("dim.region"));
        assertTrue(service.impact("metric.unknown").isEmpty());
    }

    @Test
    void shouldRejectReviewRejectionPathBackToDraft() {
        service.createDraft(scenario(), "alice");
        service.submitForReview("scenario.sales");
        ScenarioRecord rejected = service.review("scenario.sales", "bob", false);
        assertEquals("draft", rejected.getStatus());
        assertNull(rejected.getReviewer());
    }

    private ScenarioRecord publishThroughReview() {
        service.createDraft(scenario(), "alice");
        service.submitForReview("scenario.sales");
        service.review("scenario.sales", "bob", true);
        return service.publish("scenario.sales", payload());
    }

    private ManifestPayload payload() {
        OntologySnapshot ontology = new OntologySnapshot();
        ontology.setOntologyVersion("2026.11.9");
        OntologySnapshot.Concept order = new OntologySnapshot.Concept();
        order.setId("concept.order");
        order.setName("订单");
        ontology.setConcepts(List.of(order));
        OntologySnapshot.Property property = new OntologySnapshot.Property();
        property.setId("property.order.amount");
        property.setConceptId("concept.order");
        ontology.setProperties(List.of(property));

        SemanticManifest semantic = new SemanticManifest();
        semantic.setSemanticVersion("2026.11.9");
        SemanticManifest.Metric metric = new SemanticManifest.Metric();
        metric.setId("metric.gmv");
        metric.setName("成交金额");
        metric.setSummary("已支付订单金额");
        metric.setAliases(List.of("GMV"));
        metric.setTable("orders");
        metric.setColumn("amount");
        metric.setAggregation(SemanticManifest.Aggregation.SUM);
        metric.setAllowedModes(List.of("QUERY"));
        SemanticManifest.Dimension dimension = new SemanticManifest.Dimension();
        dimension.setId("dim.region");
        dimension.setName("地区");
        dimension.setSummary("订单所属地区");
        dimension.setAliases(List.of("区域"));
        dimension.setTable("orders");
        dimension.setColumn("region");
        semantic.setMetrics(List.of(metric));
        semantic.setDimensions(List.of(dimension));

        ManifestPayload payload = new ManifestPayload();
        payload.setOntology(ontology);
        payload.setSemantic(semantic);
        return payload;
    }

    private ManifestPayload.ScenarioMeta scenario() {
        ManifestPayload.ScenarioMeta scenario = new ManifestPayload.ScenarioMeta();
        scenario.setId("scenario.sales");
        scenario.setName("销售分析");
        scenario.setSummary("围绕交易域的日常销售分析");
        scenario.setEntryMode("recommend_or_confirm");
        scenario.setOwner("project-team");
        scenario.setVersion("1.0.0");
        scenario.setStatus("published");
        scenario.setDomainId("domain.trade");
        ManifestPayload.OntologyScope ontology = new ManifestPayload.OntologyScope();
        ontology.setRootConcepts(List.of("concept.order"));
        ontology.setMaxPathHops(3);
        scenario.setOntologyScope(ontology);
        ManifestPayload.SemanticScope semantic = new ManifestPayload.SemanticScope();
        semantic.setMetrics(List.of("metric.gmv"));
        semantic.setDimensions(List.of("dim.region"));
        scenario.setSemanticScope(semantic);
        scenario.setAllowedAnalysisModes(List.of("query"));
        scenario.setEntryHints(List.of("成交金额分析"));
        return scenario;
    }

    private static final class InMemoryScenarioRepository implements ScenarioRepository {
        private final Map<String, ScenarioRecord> rows = new LinkedHashMap<>();

        @Override
        public Optional<ScenarioRecord> findById(String id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public List<ScenarioRecord> findAll() {
            return new ArrayList<>(rows.values());
        }

        @Override
        public void insert(ScenarioRecord record) {
            rows.put(record.getId(), record);
        }

        @Override
        public void update(ScenarioRecord record) {
            rows.put(record.getId(), record);
        }
    }
}

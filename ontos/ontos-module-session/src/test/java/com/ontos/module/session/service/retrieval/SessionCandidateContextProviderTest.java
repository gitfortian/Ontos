package com.ontos.module.session.service.retrieval;

import java.util.List;
import java.util.Set;

import com.ontos.module.ontology.service.manifest.ManifestService;
import com.ontos.module.ontology.core.OntologySnapshot;
import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.ontology.core.SemanticManifest;
import com.ontos.module.system.service.policy.PolicySnapshotService;
import com.ontos.framework.common.agent.CandidateAsset;
import com.ontos.framework.web.core.context.RequestContext;
import com.ontos.framework.web.core.context.RequestContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 候选域收敛单测（M7/W2 E-7）：retrieval 域下提示词词表 = 本轮对账口径
 * （dslReady ∪ fuzzy ∪ prevTurn），与来源完整性对账严格一致；
 * dslReady 为空（discovery/澄清态）与 full 域回退全量清单。
 */
class SessionCandidateContextProviderTest {

    private final ManifestService manifestService = Mockito.mock(ManifestService.class);
    private final PolicySnapshotService policySnapshotService =
            Mockito.mock(PolicySnapshotService.class);
    private final SessionCandidateContextProvider provider =
            new SessionCandidateContextProvider(manifestService, policySnapshotService);

    @BeforeEach
    void setUp() {
        Mockito.when(manifestService.getActiveManifestOrNull()).thenReturn(manifest());
        Mockito.when(policySnapshotService.authorizedAssetIds(Mockito.any())).thenReturn(Set.of(
                "metric|metric.gmv", "metric|metric.order_count",
                "dimension|dim.region", "property|property.customer.tier"));
        RequestContextHolder.set(new RequestContext("t1", "u1", "s1", 1, null, "tr1",
                Set.of(), false));
    }

    @AfterEach
    void tearDown() {
        provider.clearPrimed("批量查 c-1001、c-9999 的等级");
        provider.clearPrimed();
        RequestContextHolder.clear();
    }

    private static PublishedManifest manifest() {
        PublishedManifest manifest = new PublishedManifest();
        SemanticManifest semantic = new SemanticManifest();
        SemanticManifest.Metric gmv = new SemanticManifest.Metric();
        gmv.setId("metric.gmv");
        gmv.setName("GMV");
        gmv.setAliases(List.of("成交金额"));
        SemanticManifest.Metric orderCount = new SemanticManifest.Metric();
        orderCount.setId("metric.order_count");
        orderCount.setName("订单数");
        orderCount.setAliases(List.of("单量"));
        semantic.setMetrics(List.of(gmv, orderCount));
        SemanticManifest.Dimension region = new SemanticManifest.Dimension();
        region.setId("dim.region");
        region.setName("区域");
        region.setAliases(List.of());
        semantic.setDimensions(List.of(region));
        manifest.setSemantic(semantic);
        OntologySnapshot ontology = new OntologySnapshot();
        OntologySnapshot.Property tier = new OntologySnapshot.Property();
        tier.setId("property.customer.tier");
        tier.setName("客户等级");
        tier.setAliases(List.of("等级"));
        ontology.setProperties(List.of(tier));
        manifest.setOntology(ontology);
        return manifest;
    }

    private static com.ontos.module.session.service.retrieval.RetrievedCandidate
            candidate(String assetType, String assetId) {
        return new com.ontos.module.session.service.retrieval.RetrievedCandidate(
                assetId, assetType, assetId, 2, false, 60);
    }

    @Test
    void retrievalScopeTrimsSinglePrimedCandidate() {
        provider.setRetrievalScope(true);
        provider.prime(new RetrievalOutcome(
                List.of(candidate("metric", "metric.gmv")), List.of(), List.of(),
                List.of(), false, "hash"));

        List<CandidateAsset> vocabulary = provider.candidateVocabulary();
        assertEquals(1, vocabulary.size(), "收敛到对账口径：只留本轮命中的 gmv");
        assertEquals("metric.gmv", vocabulary.get(0).assetId());
    }

    @Test
    void retrievalScopeTrimsToReconciliationSet() {
        provider.setRetrievalScope(true);
        provider.prime(new RetrievalOutcome(
                List.of(candidate("metric", "metric.gmv"),
                        candidate("dimension", "dim.region")),
                List.of(candidate("property", "property.customer.tier")),
                List.of(), List.of(), false, "hash"));

        List<CandidateAsset> vocabulary = provider.candidateVocabulary();
        assertEquals(3, vocabulary.size(), "词表 = 对账口径（dslReady ∪ fuzzy ∪ prevTurn）");
        assertTrue(vocabulary.stream().allMatch(asset -> Set.of(
                "metric.gmv", "dim.region", "property.customer.tier").contains(asset.assetId())));
        assertEquals("GMV", vocabulary.get(0).displayName());
    }

    @Test
    void emptyDslReadyFallsBackToFullVocabulary() {
        provider.setRetrievalScope(true);
        provider.prime(new RetrievalOutcome(List.of(), List.of(), List.of(),
                List.of(), false, "hash"));

        // 全量 = 2 指标 + 1 维度 + 1 属性（授权集内的全部清单资产）
        assertEquals(4, provider.candidateVocabulary().size(),
                "dslReady 为空（discovery/澄清态）回退全量，能力发现不受影响");
    }

    @Test
    void fullScopeIgnoresPrimedOutcome() {
        provider.setRetrievalScope(false);
        provider.prime(new RetrievalOutcome(
                List.of(candidate("metric", "metric.gmv")), List.of(), List.of(),
                List.of(), false, "hash"));

        assertEquals(4, provider.candidateVocabulary().size(),
                "full 域不收敛：向后兼容");
    }

    @Test
    void questionScopedPrimedCandidateSurvivesAsyncRepairThread() {
        provider.setRetrievalScope(true);
        String question = "批量查 c-1001、c-9999 的等级";
        provider.prime(new RetrievalOutcome(
                        List.of(candidate("property", "property.customer.tier")),
                        List.of(), List.of(), List.of(), false, "hash"),
                question);

        List<CandidateAsset> vocabulary = provider.candidateVocabulary(question);
        assertEquals(1, vocabulary.size(),
                "问题感知视图必须能在无 ThreadLocal 的修复线程还原本轮候选");
        assertEquals("property.customer.tier", vocabulary.get(0).assetId());
    }
}

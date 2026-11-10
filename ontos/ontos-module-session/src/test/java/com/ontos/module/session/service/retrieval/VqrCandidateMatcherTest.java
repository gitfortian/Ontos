package com.ontos.module.session.service.retrieval;

import java.util.List;
import java.util.Map;

import com.ontos.module.ontology.core.ManifestPayload;
import com.ontos.module.ontology.core.PublishedManifest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** VQR 候选匹配（M6/W3/T3.5，瀑布第 7 步）：确定性模板匹配 + 场景限缩 + 优先级。 */
class VqrCandidateMatcherTest {

    private final VqrCandidateMatcher matcher = new VqrCandidateMatcher();

    @Test
    void patternMatchExtractsParams() {
        List<RetrievalOutcome.VqrCandidate> hits = matcher.match(
                "近 7 天各渠道成交金额", manifest(), null);
        assertEquals(1, hits.size());
        assertEquals("vqr.sales.daily_gmv_by_channel", hits.get(0).vqrId());
        assertEquals("7", hits.get(0).params().get("days"), "槽位参数已提取");
        assertEquals("近{days}天各渠道成交金额", hits.get(0).matchedPattern());
    }

    @Test
    void noMatchWhenUtteranceDoesNotFitPattern() {
        assertTrue(matcher.match("看下订单数", manifest(), null).isEmpty(),
                "非模板形状话术不命中");
        assertTrue(matcher.match("", manifest(), null).isEmpty(), "空话术不命中");
    }

    @Test
    void inScenarioRestrictsToScenarioReferencedVqrs() {
        PublishedManifest manifest = manifest();
        // scenario.sales 引用 vqr.sales.*；vqr.global.daily_gmv 不在引用集 → 场景内不命中
        assertTrue(matcher.match("近 7 天各渠道成交金额", manifest, "scenario.sales").stream()
                        .allMatch(hit -> hit.vqrId().startsWith("vqr.sales.")),
                "场景内命中限场景引用集");
        // free 态：全部 published VQR 可命中
        List<RetrievalOutcome.VqrCandidate> freeHits =
                matcher.match("近 7 天整体成交金额", manifest, null);
        assertTrue(freeHits.stream().anyMatch(hit -> "vqr.global.daily_gmv".equals(hit.vqrId())),
                "自由问数不限场景引用集");
    }

    @Test
    void scenarioDefaultVqrIdsSortFirst() {
        // 场景只引用 global（sales 不在引用集）；同话术只可能命中场景引用集内的 global——
        // 若两个都在引用集内则按 pattern 长度降序（更具体优先）
        PublishedManifest manifest = manifest();
        manifest.getScenarios().get(0).setDefaultVqrIds(List.of("vqr.global.daily_gmv"));
        List<RetrievalOutcome.VqrCandidate> hits =
                matcher.match("近 7 天整体成交金额", manifest, "scenario.sales");
        assertEquals(1, hits.size(), "仅场景引用集内（global）命中");
        assertEquals("vqr.global.daily_gmv", hits.get(0).vqrId());
    }

    @Test
    void draftVqrNeverMatches() {
        PublishedManifest manifest = manifest();
        manifest.getVqrs().forEach(vqr -> vqr.setStatus("draft"));
        assertTrue(matcher.match("近 7 天各渠道成交金额", manifest, null).isEmpty(),
                "未发布不参与匹配");
    }

    // ---------- fixtures ----------

    private static PublishedManifest manifest() {
        PublishedManifest manifest = new PublishedManifest();
        manifest.setManifestId("m-test");

        ManifestPayload.VqrMeta sales = new ManifestPayload.VqrMeta();
        sales.setVqrId("vqr.sales.daily_gmv_by_channel");
        sales.setName("近 N 天各渠道成交金额");
        sales.setStatus("published");
        sales.setQuestionPattern("近{days}天各渠道成交金额");

        ManifestPayload.VqrMeta global = new ManifestPayload.VqrMeta();
        global.setVqrId("vqr.global.daily_gmv");
        global.setName("近 N 天成交金额");
        global.setStatus("published");
        global.setQuestionPattern("近{days}天整体成交金额");

        manifest.setVqrs(List.of(sales, global));

        ManifestPayload.ScenarioMeta scenario = new ManifestPayload.ScenarioMeta();
        scenario.setId("scenario.sales");
        scenario.setStatus("published");
        scenario.setDefaultVqrIds(List.of());
        manifest.setScenarios(List.of(scenario));
        return manifest;
    }
}

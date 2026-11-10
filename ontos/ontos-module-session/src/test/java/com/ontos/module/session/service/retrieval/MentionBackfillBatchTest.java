package com.ontos.module.session.service.retrieval;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ontos.framework.common.util.json.JsonUtils;
import com.ontos.framework.web.core.context.RequestContext;
import com.ontos.module.ontology.core.ManifestPayload;
import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.ontology.service.manifest.ManifestService;
import com.ontos.module.system.service.policy.PolicySnapshotService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import tools.jackson.core.type.TypeReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T2.4：中文提及样本集 300 条回填跑批（PoC #5 口径，M7 并行义务在本阶段落地）。
 *
 * <p>口径（技术选型 §7 #5 / ADR-0005 冻结初值）：
 * ① Top-5 召回率；② **模糊候选进入 DSL 次数为 0**（硬断言，逐条检查）。
 * 真实清单 v8 + 冻结样本，结果写证据 JSON（append-only 原则：新版本文件，不覆写历史）。
 *
 * <p>跑批环境：全授权（样本评测的是检索层质量，授权是瀑布的前置过滤器——
 * 授权裁剪的单独语义由 {@link MentionRetrievalServiceTest} 覆盖）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MentionBackfillBatchTest {

    private static final Path REPO_ROOT = Path.of("..", "..");
    private static final Path MANIFEST_JSON = REPO_ROOT.resolve("ontos-assets/first-release-v10.json");
    private static final Path SAMPLES_JSON =
            REPO_ROOT.resolve("docs/06-verification/evaluation/中文提及样本集-v1.json");
    /**
     * 跑批输出写到 target/ 软名：v1 冻结证据（docs/06-verification/evaluation/提及样本回填-M5W2证据-v1.json）
     * 是 M5/W2 已验收交付物，不得被每次 mvn test 覆写（M6/T1.2 审核发现）。
     * 本测试固定读 v10 清单资产（与冻结证据同源），保证历史证据可复算；评估生效清单的跑批另走脚本。
     */
    private static final Path EVIDENCE_JSON = Path.of("target", "提及样本回填-latest.json");

    private MentionRetrievalService service;
    private List<Map<String, Object>> cases;
    private final RequestContext context =
            new RequestContext("batch", "batch", "batch", 1, null, "batch", Set.of(), false);

    private final List<Map<String, Object>> perCase = new ArrayList<>();

    @BeforeAll
    void setUp() throws Exception {
        String manifestJson = Files.readString(MANIFEST_JSON, StandardCharsets.UTF_8);
        ManifestPayload payload = JsonUtils.parseObject(manifestJson, ManifestPayload.class);
        PublishedManifest manifest = new PublishedManifest();
        manifest.setManifestId("2026.11.09-tenth");
        manifest.setContentHash(PublishedManifest.sha256(manifestJson));
        manifest.setPayloadJson(manifestJson);
        manifest.setSemantic(payload.getSemantic());
        manifest.setOntology(payload.getOntology());

        ManifestService manifestService = mock(ManifestService.class);
        when(manifestService.getActiveManifestOrNull()).thenReturn(manifest);
        PolicySnapshotService policySnapshotService = mock(PolicySnapshotService.class);
        when(policySnapshotService.authorizedAssetIds(any()))
                .thenReturn(allAssetKeys(manifest.getSemantic(), manifest.getOntology()));
        service = new MentionRetrievalService(manifestService, policySnapshotService);

        Map<String, Object> sampleSet = JsonUtils.parseObject(
                Files.readString(SAMPLES_JSON, StandardCharsets.UTF_8),
                new TypeReference<Map<String, Object>>() { });
        cases = castList(sampleSet.get("cases"));
        assertEquals(300, cases.size(), "冻结样本集 300 条，不得增删");
    }

    @Test
    void backfillAllMentionsAndProduceEvidence() throws Exception {
        int top5Hit = 0;
        int directHit = 0;
        int clarifyHit = 0;
        int dslFuzzyPollution = 0;
        Map<String, int[]> missByCategory = new LinkedHashMap<>();
        Map<String, int[]> statByCategory = new LinkedHashMap<>();

        for (Map<String, Object> item : cases) {
            String id = String.valueOf(item.get("id"));
            String utterance = String.valueOf(item.get("utterance"));
            String expected = expectedAssetId(String.valueOf(item.get("expectedAssetId")));
            String category = String.valueOf(item.get("category"));
            boolean shouldReachDsl = Boolean.parseBoolean(String.valueOf(item.get("shouldReachDsl")));

            RetrievalOutcome outcome = service.resolve(utterance, context, null);
            boolean hitTop5 = outcome.top5().stream()
                    .anyMatch(candidate -> candidate.assetId().equals(expected));
            boolean inDslReady = outcome.dslReady().stream()
                    .anyMatch(candidate -> candidate.assetId().equals(expected));
            boolean inClarify = outcome.clarifications().stream()
                    .flatMap(payload -> payload.candidates().stream())
                    .anyMatch(candidate -> candidate.assetId().equals(expected))
                    || outcome.fuzzyOnly().stream()
                            .anyMatch(candidate -> candidate.assetId().equals(expected));
            boolean pollution = outcome.dslReady().stream().anyMatch(RetrievedCandidate::fuzzy);
            if (pollution) {
                dslFuzzyPollution += 1;
            }
            if (hitTop5) {
                top5Hit += 1;
            }
            if (inDslReady) {
                directHit += 1;
            }
            if (shouldReachDsl ? inDslReady : inClarify) {
                clarifyHit += 1;
            }
            statByCategory.computeIfAbsent(category, key -> new int[3]);
            statByCategory.get(category)[0] += 1;
            statByCategory.get(category)[1] += hitTop5 ? 1 : 0;
            statByCategory.get(category)[2] += inDslReady ? 1 : 0;
            if (!hitTop5) {
                missByCategory.computeIfAbsent(category, key -> new int[0]);
            }
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("id", id);
            record.put("category", category);
            record.put("utterance", utterance);
            record.put("expected", expected);
            record.put("top5Hit", hitTop5);
            record.put("directHit", inDslReady);
            record.put("clarifyHit", inClarify);
            record.put("dslFuzzyPollution", pollution);
            if (!hitTop5) {
                record.put("top5", outcome.top5().stream()
                        .map(RetrievedCandidate::assetId).toList());
            }
            perCase.add(record);
        }

        // 硬断言 1：模糊候选进入 DSL 次数为 0（PoC #5 零污染判据，逐条）
        assertEquals(0, dslFuzzyPollution, "模糊候选进入 DSL 次数必须为 0");

        // 硬断言 2：非拼音类（synonym/colloquial/typo，255 条）Top-5 召回 ≥95%
        // （PoC #5 召回判据；拼音类按定档 ③ 归 M7 评测后决策，此处如实统计不掺入门槛）
        int nonPinyinTotal = 0;
        int nonPinyinHit = 0;
        for (Map.Entry<String, int[]> entry : statByCategory.entrySet()) {
            if (!"pinyin".equals(entry.getKey())) {
                nonPinyinTotal += entry.getValue()[0];
                nonPinyinHit += entry.getValue()[1];
            }
        }
        double nonPinyinRate = nonPinyinTotal == 0 ? 0 : (double) nonPinyinHit / nonPinyinTotal;
        assertTrue(nonPinyinRate >= 0.95,
                "非拼音类 Top-5 召回率须 ≥95%（PoC #5 口径；实测 "
                        + String.format("%.2f%%", nonPinyinRate * 100) + "）");

        // 证据 JSON（PoC 证据规范：可复算、含环境与逐条明细）
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("setId", "中文提及样本集-v1");
        evidence.put("manifestId", "2026.11.09-tenth");
        evidence.put("ranAt", java.time.OffsetDateTime.now().toString());
        evidence.put("total", cases.size());
        evidence.put("top5Hit", top5Hit);
        evidence.put("top5RateAll", round4((double) top5Hit / cases.size()));
        evidence.put("directDslHit", directHit);
        evidence.put("categoryExpectationHit", clarifyHit);
        evidence.put("dslFuzzyPollution", dslFuzzyPollution);
        evidence.put("nonPinyinTop5Rate", round4(nonPinyinRate));
        evidence.put("note", "M5/W2 T2.4 回填：pinyin 类按定档 ③ 未实现拼音检索，"
                + "Top-5 全量口径与门槛差距如实登记，拼音归 M7 评测后决策");
        evidence.put("cases", perCase);
        Files.writeString(EVIDENCE_JSON, JsonUtils.toJsonString(evidence),
                StandardCharsets.UTF_8);
        System.out.printf("[PoC #5 backfill] total=%d top5=%d(%.2f%%) nonPinyin=%.2f%% "
                        + "direct=%d clarify=%d pollution=%d%n",
                cases.size(), top5Hit, (double) top5Hit / cases.size() * 100,
                nonPinyinRate * 100, directHit, clarifyHit, dslFuzzyPollution);
    }

    /** 样本 ID（ADR-0004 规划期前缀）→ 实际清单 ID：entity.→concept.、attr.→property.。 */
    private static String expectedAssetId(String sampleId) {
        if (sampleId.startsWith("entity.")) {
            return "concept." + sampleId.substring("entity.".length());
        }
        if (sampleId.startsWith("attr.")) {
            return "property." + sampleId.substring("attr.".length());
        }
        return sampleId;
    }

    private static Set<String> allAssetKeys(com.ontos.module.ontology.core.SemanticManifest semantic,
                                            com.ontos.module.ontology.core.OntologySnapshot ontology) {
        Set<String> keys = new java.util.HashSet<>();
        semantic.getMetrics().forEach(metric -> keys.add("metric|" + metric.getId()));
        semantic.getDimensions().forEach(dimension -> keys.add("dimension|" + dimension.getId()));
        ontology.getConcepts().forEach(concept -> keys.add("entity|" + concept.getId()));
        ontology.getProperties().forEach(property -> keys.add("property|" + property.getId()));
        return keys;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(Object value) {
        return (List<Map<String, Object>>) value;
    }

    private static double round4(double value) {
        return Math.round(value * 10000) / 10000.0;
    }

}

package com.ontos.module.session.service.retrieval;

import java.util.List;
import java.util.Set;

import com.ontos.framework.web.core.context.RequestContext;
import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.ontology.service.manifest.ManifestService;
import com.ontos.module.session.service.session.SessionProjection;
import com.ontos.module.system.service.policy.PolicySnapshotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 检索瀑布单测（M5 规格 T2.1–T2.3）：
 * 分层语义（L1/L2/L3）、授权裁剪、**模糊候选进 DSL=0 硬断言**、澄清四要素、指代消解。
 */
class MentionRetrievalServiceTest {

    private final ManifestService manifestService = mock(ManifestService.class);
    private final PolicySnapshotService policySnapshotService = mock(PolicySnapshotService.class);
    private final RequestContext context =
            new RequestContext("t1", "u1", "s1", 1, null, "tr1", Set.of(), false);

    @BeforeEach
    void setUp() {
        when(manifestService.getActiveManifestOrNull()).thenReturn(manifest());
        // 默认全部授权（键格式 type|assetId）
        when(policySnapshotService.authorizedAssetIds(any())).thenReturn(Set.of(
                "metric|metric.gmv", "metric|metric.order_count", "metric|metric.refund_amount",
                "dimension|dim.region", "dimension|dim.channel",
                "entity|concept.order", "entity|concept.customer",
                "property|property.order.order_no", "property|property.customer.email"));
    }

    private MentionRetrievalService service() {
        return new MentionRetrievalService(manifestService, policySnapshotService,
                scenarioScopeService(), new VqrCandidateMatcher());
    }

    /** 缺省场景桩：无场景清单 → free 态 STAY（授权全域透传）。 */
    private com.ontos.module.session.service.scenario.ScenarioScopeService scenarioScopeService() {
        com.ontos.module.session.service.scenario.ScenarioSessionService sessionService =
                org.mockito.Mockito.mock(com.ontos.module.session.service.scenario.ScenarioSessionService.class);
        org.mockito.Mockito.when(sessionService.read(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new com.ontos.module.session.service.scenario.ScenarioSessionService
                        .ScenarioSessionView(
                        com.ontos.module.session.core.scenario.ScenarioRouteState.FREE, null));
        org.mockito.Mockito.when(sessionService.apply(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(stayFree());
        return new com.ontos.module.session.service.scenario.ScenarioScopeService(sessionService);
    }

    /** 无场景清单的缺省路由：STAY（free 态不迁移、不落库）。 */
    private static com.ontos.module.session.core.scenario.ScenarioRouteDecision stayFree() {
        return new com.ontos.module.session.core.scenario.ScenarioRouteDecision(
                com.ontos.module.session.core.scenario.ScenarioRouteDecision.Action.STAY,
                com.ontos.module.session.core.scenario.ScenarioRouteState.FREE, null,
                com.ontos.module.session.core.scenario.ScenarioEntrySource.SESSION_INHERIT,
                null, List.of());
    }


    // ---------- 分层：L1 精确 ----------

    @Test
    void exactUtteranceHitsLayer1() {
        RetrievalOutcome outcome = service().resolve("成交金额", context, null);
        assertFalse(outcome.dslReady().isEmpty());
        RetrievedCandidate top = outcome.dslReady().get(0);
        assertEquals("metric.gmv", top.assetId());
        assertEquals(1, top.layer());
        assertFalse(top.fuzzy());
    }

    @Test
    void assetIdLiteralInUtteranceHitsLayer1() {
        RetrievalOutcome outcome = service().resolve("metric.gmv 最近趋势", context, null);
        assertTrue(outcome.dslReady().stream()
                .anyMatch(candidate -> candidate.assetId().equals("metric.gmv")
                        && candidate.layer() == 1));
    }

    // ---------- 分层：L2 词典 ----------

    @Test
    void utteranceContainingAliasHitsLayer2() {
        RetrievalOutcome outcome = service().resolve("帮我查一下销售额", context, null);
        assertFalse(outcome.dslReady().isEmpty(), "L2 命中应进入 dslReady");
        assertEquals(2, outcome.dslReady().get(0).layer());
        assertEquals("metric.gmv", outcome.dslReady().get(0).assetId());
    }

    @Test
    void layer1WinsOverLayer2ForSameAsset() {
        // 「销售额」全等（L1）与「帮我查一下销售额」包含（L2）指向同一资产，全等时是 L1
        RetrievalOutcome exact = service().resolve("销售额", context, null);
        assertEquals(1, exact.dslReady().get(0).layer());
        RetrievalOutcome contained = service().resolve("查销售额", context, null);
        assertEquals(2, contained.dslReady().get(0).layer());
    }

    // ---------- 模糊候选进 DSL=0（硬断言） ----------

    @Test
    void fuzzyHitNeverEntersDslReady() {
        // 「订丹」：编辑距离 1 命中「订单」，但 dslReady 必须为空（fuzzy 只进澄清/模糊层）
        RetrievalOutcome outcome = service().resolve("帮我查一下订丹", context, null);
        assertTrue(outcome.dslReady().isEmpty(),
                "模糊候选进 DSL=0：typo 命中不得进入 dslReady");
        assertTrue(outcome.fuzzyOnly().stream()
                .anyMatch(candidate -> candidate.assetId().equals("concept.order")
                        && candidate.fuzzy()));
    }

    @Test
    void fuzzyHitStaysOutOfDslReadyEvenWithoutDictionaryHit() {
        // 「订单数」精确 L2 命中 metric.order_count；同资产的模糊层不得覆盖
        RetrievalOutcome outcome = service().resolve("查一下订丹数", context, null);
        // 「订丹数」滑窗 len3 → 「订丹数」距离「订单数」=1 → 模糊命中 metric.order_count；
        // 同时没有任何 L1/L2 → 全部在 fuzzyOnly
        assertTrue(outcome.dslReady().isEmpty());
        assertTrue(outcome.fuzzyOnly().stream()
                .anyMatch(candidate -> candidate.assetId().equals("metric.order_count")));
    }

    // ---------- 授权裁剪 ----------

    @Test
    void unauthorizedAssetNeverSurfaces() {
        when(policySnapshotService.authorizedAssetIds(any())).thenReturn(Set.of(
                "metric|metric.gmv", "dimension|dim.region"));
        RetrievalOutcome outcome = service().resolve("查一下邮箱和销售额", context, null);
        // 邮箱（property.customer.email）未授权 → 不得出现；销售额正常
        assertTrue(outcome.dslReady().stream().noneMatch(candidate ->
                candidate.assetId().equals("property.customer.email")));
        assertTrue(outcome.dslReady().stream()
                .anyMatch(candidate -> candidate.assetId().equals("metric.gmv")));
    }

    @Test
    void missingSnapshotFailsClosed() {
        when(policySnapshotService.authorizedAssetIds(any()))
                .thenThrow(new RuntimeException("快照缺失"));
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> service().resolve("销售额", context, null),
                "无快照租户瀑布整体不可用（fail-closed）");
    }

    // ---------- 澄清四要素（T2.3） ----------

    @Test
    void ambiguousTermProducesClarificationWithContract() {
        // 构造同词多资产：refund_amount 与 gmv 共享别名「金额」→ 由 setUp 清单保证
        RetrievalOutcome outcome = service().resolve("帮我看下金额", context, null);
        assertFalse(outcome.clarifications().isEmpty(), "歧义词应触发澄清");
        ClarificationPayload payload = outcome.clarifications().get(0);
        assertTrue(payload.valid(), "四要素契约：候选 2–5、带 ID、默认建议、可跳过");
        assertTrue(payload.candidates().size() >= 2 && payload.candidates().size() <= 5);
        assertEquals(true, payload.skippable());
        assertNotNull(payload.defaultSuggestion());
    }

    @Test
    void fuzzyOnlyHitsProduceClarificationNotDsl() {
        // 「订丹」→ concept.order（距离1）、「订耽号」→ property.order.order_no（订单号，距离1）；
        // 话术不含任何完整词条目，确保纯模糊层
        RetrievalOutcome outcome = service().resolve("查一下订丹和订耽号", context, null);
        assertTrue(outcome.dslReady().isEmpty());
        assertTrue(outcome.fuzzyOnly().size() >= 2, "两个不同资产的模糊命中应可澄清");
    }

    // ---------- 指代消解（T2.2） ----------

    @Test
    void projectionSuppliesPrevTurnCandidatesWhenNoMention() {
        SessionProjection projection = new SessionProjection(
                List.of("metric.gmv"), List.of("dim.region"), List.of(), null, "近 7 天");
        RetrievalOutcome outcome = service().resolve("帮我看一下", context, projection);
        assertTrue(outcome.prevTurn().stream()
                .anyMatch(candidate -> candidate.assetId().equals("metric.gmv")));
        assertTrue(outcome.prevTurn().stream()
                .anyMatch(candidate -> candidate.assetId().equals("dim.region")));
        assertFalse(outcome.projectionStale());
    }

    @Test
    void staleProjectionIsRejectedAtomically() {
        // gmv 被降权：投影整体失效 → prevTurn 清空 + stale 标记（部分降权不得继续用）
        when(policySnapshotService.authorizedAssetIds(any())).thenReturn(Set.of(
                "dimension|dim.region", "metric|metric.order_count"));
        SessionProjection projection = new SessionProjection(
                List.of("metric.gmv"), List.of("dim.region"), List.of(), null, null);
        RetrievalOutcome outcome = service().resolve("帮我看一下", context, projection);
        assertTrue(outcome.prevTurn().isEmpty(), "失效投影整体作废");
        assertTrue(outcome.projectionStale());
    }

    @Test
    void freshUtteranceMentionWinsOverProjection() {
        // 本轮显式提及优先于指代：说「销售额」就用本轮，不用上轮投影
        SessionProjection projection = new SessionProjection(
                List.of("metric.refund_amount"), List.of(), List.of(), null, null);
        RetrievalOutcome outcome = service().resolve("查一下销售额", context, projection);
        assertTrue(outcome.prevTurn().isEmpty(), "有显式提及时不做指代消解");
        assertEquals("metric.gmv", outcome.dslReady().get(0).assetId());
    }

    // ---------- 场景路由接线（M6/W2/T2.4） ----------

    @Test
    void inScenarioCandidatesAreTrimmedToScenarioScope() {
        // 场景内说「订单数」→ 命中场景 scope 内指标；场景上下文随结果透出
        MentionRetrievalService inScenario = inScenarioService(enterDecision(),
                com.ontos.module.session.core.scenario.ScenarioRouteState.IN_SCENARIO,
                "scenario.sales");
        RetrievalOutcome outcome = inScenario.resolve("订单数", context, null);
        assertTrue(outcome.dslReady().stream()
                .anyMatch(candidate -> candidate.assetId().equals("metric.order_count")));
        assertEquals("scenario.sales", outcome.scenario().scenarioId());
        assertTrue(outcome.scenario().allowedModes().contains("query"));
    }

    @Test
    void outOfScopeMentionTriggersScenarioClarifyAndHidesCandidate() {
        // 场景内说「退款额」（授权但不在场景 scope）→ 候选隐藏 + 越界澄清（M5 回传通道）
        MentionRetrievalService inScenario = inScenarioService(clarifyDecision(),
                com.ontos.module.session.core.scenario.ScenarioRouteState.IN_SCENARIO,
                "scenario.sales");
        RetrievalOutcome outcome = inScenario.resolve("退款额", context, null);
        assertTrue(outcome.dslReady().isEmpty(), "越界资产不得进 DSL");
        assertEquals(1, outcome.clarifications().size());
        var payload = outcome.clarifications().get(0);
        assertTrue(payload.valid(), "场景澄清满足四要素（含补齐的留在场景选项）");
        assertTrue(payload.candidates().stream()
                .anyMatch(candidate -> "scenario:scenario.sales".equals(candidate.assetId())),
                "补齐「留在场景」候选（单场景清单只有退出时）");
    }

    @Test
    void askConfirmDecisionSurfacesScenarioRecommendation() {
        // free 态 hint 命中 recommend 场景 → 推荐确认候选透出（进入/留在自由问数）
        MentionRetrievalService free = inScenarioService(new com.ontos.module.session.core.scenario.ScenarioRouteDecision(
                com.ontos.module.session.core.scenario.ScenarioRouteDecision.Action.ASK_CONFIRM,
                com.ontos.module.session.core.scenario.ScenarioRouteState.PENDING_CONFIRM,
                "scenario.sales", null, null,
                List.of(new com.ontos.module.session.core.scenario.ScenarioRouteDecision.PendingChoice(
                                "scenario:scenario.sales", "进入场景：销售分析", "scenario.sales"),
                        new com.ontos.module.session.core.scenario.ScenarioRouteDecision.PendingChoice(
                                com.ontos.module.session.core.scenario.ScenarioRouteDecision.EXIT_CANDIDATE_ID,
                                "留在自由问数", null))));
        RetrievalOutcome outcome = free.resolve("看看订单趋势", context, null);
        // 话术可能同时触发模糊命中澄清（既有行为）；场景推荐载荷必须存在且满足四要素
        var scenarioPayload = outcome.clarifications().stream()
                .filter(candidate -> "scenario:scenario.sales".equals(candidate.defaultSuggestion()))
                .findFirst().orElse(null);
        assertTrue(scenarioPayload != null, "推荐确认候选必须透出");
        assertTrue(scenarioPayload.valid());
        assertTrue(scenarioPayload.candidates().stream().anyMatch(candidate ->
                        com.ontos.module.session.core.scenario.ScenarioRouteDecision.EXIT_CANDIDATE_ID
                                .equals(candidate.assetId())),
                "退出候选在列");
        // 推荐轮不透出场景上下文（澄清轮编排层不组装 DSL）
        assertTrue(outcome.scenario() == null);
    }

    @Test
    void scenarioRecommendationTakesPrecedenceOverAmbiguity() {
        // T24-4：clarificationTerminal 只取首个载荷——场景推荐必须排在歧义/模糊之前
        MentionRetrievalService free = inScenarioService(new com.ontos.module.session.core.scenario.ScenarioRouteDecision(
                com.ontos.module.session.core.scenario.ScenarioRouteDecision.Action.ASK_CONFIRM,
                com.ontos.module.session.core.scenario.ScenarioRouteState.PENDING_CONFIRM,
                "scenario.sales", null, null,
                List.of(new com.ontos.module.session.core.scenario.ScenarioRouteDecision.PendingChoice(
                                "scenario:scenario.sales", "进入场景：销售分析", "scenario.sales"),
                        new com.ontos.module.session.core.scenario.ScenarioRouteDecision.PendingChoice(
                                com.ontos.module.session.core.scenario.ScenarioRouteDecision.EXIT_CANDIDATE_ID,
                                "留在自由问数", null))));
        // 「金额」命中 gmv 与 refund_amount（歧义/模糊）+「订单趋势」命中 hint（推荐）
        RetrievalOutcome outcome = free.resolve("看下金额和订单趋势", context, null);
        assertTrue(outcome.clarifications().size() >= 2, "歧义/模糊与场景推荐并存");
        assertEquals("scenario:scenario.sales", outcome.clarifications().get(0).defaultSuggestion(),
                "场景推荐排首位（入口级语义优先于资产级歧义）");
    }

    private MentionRetrievalService inScenarioService(
            com.ontos.module.session.core.scenario.ScenarioRouteDecision decision) {
        return inScenarioService(decision,
                com.ontos.module.session.core.scenario.ScenarioRouteState.FREE, null);
    }

    /** 当前视图可指定：裁剪按当前态（in_scenario 会话本轮可见域受场景 scope 约束）。 */
    private MentionRetrievalService inScenarioService(
            com.ontos.module.session.core.scenario.ScenarioRouteDecision decision,
            com.ontos.module.session.core.scenario.ScenarioRouteState viewState,
            String viewScenarioId) {
        com.ontos.module.session.service.scenario.ScenarioSessionService sessionService =
                org.mockito.Mockito.mock(com.ontos.module.session.service.scenario.ScenarioSessionService.class);
        org.mockito.Mockito.when(sessionService.read(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new com.ontos.module.session.service.scenario.ScenarioSessionService
                        .ScenarioSessionView(viewState, viewScenarioId));
        org.mockito.Mockito.when(sessionService.apply(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(decision);
        com.ontos.module.session.service.scenario.ScenarioScopeService scopeService =
                new com.ontos.module.session.service.scenario.ScenarioScopeService(sessionService);
        return new MentionRetrievalService(manifestService, policySnapshotService, scopeService,
                new VqrCandidateMatcher());
    }

    private static com.ontos.module.session.core.scenario.ScenarioRouteDecision enterDecision() {
        return new com.ontos.module.session.core.scenario.ScenarioRouteDecision(
                com.ontos.module.session.core.scenario.ScenarioRouteDecision.Action.ENTER_SCENARIO,
                com.ontos.module.session.core.scenario.ScenarioRouteState.IN_SCENARIO,
                "scenario.sales",
                com.ontos.module.session.core.scenario.ScenarioEntrySource.AUTO_CONFIDENT,
                null, List.of());
    }

    private static com.ontos.module.session.core.scenario.ScenarioRouteDecision clarifyDecision() {
        return new com.ontos.module.session.core.scenario.ScenarioRouteDecision(
                com.ontos.module.session.core.scenario.ScenarioRouteDecision.Action.CLARIFY_OUT_OF_SCOPE,
                com.ontos.module.session.core.scenario.ScenarioRouteState.PENDING_CONFIRM,
                "scenario.sales", null, null,
                List.of(new com.ontos.module.session.core.scenario.ScenarioRouteDecision.PendingChoice(
                        com.ontos.module.session.core.scenario.ScenarioRouteDecision.EXIT_CANDIDATE_ID,
                        "退出场景，回到自由问数", null)));
    }

    // ---------- R-29 variantHint 排序（M6/W4/T4.4） ----------

    @Test
    void variantHintBoostsMatchedCandidateWithinSameLayer() {
        // 「金额」同词命中多资产（L2）——variantHint 命中的候选同层排前；候选集不变
        MentionRetrievalService service = service();
        RetrievalOutcome withHint = service.resolve("看下金额", context, null, "refund");
        RetrievalOutcome withoutHint = service.resolve("看下金额", context, null, null);
        var withIds = withHint.dslReady().stream()
                .map(RetrievedCandidate::assetId).toList();
        var withoutIds = withoutHint.dslReady().stream()
                .map(RetrievedCandidate::assetId).toList();
        assertEquals(withoutIds.size(), withIds.size(), "只重排不增删（候选集不变）");
        assertTrue(withIds.indexOf("metric.refund_amount") < withoutIds.indexOf("metric.refund_amount"),
                "hint 命中的候选排前: with=" + withIds + " without=" + withoutIds);
    }

    @Test
    void variantHintDoesNotCrossLayers() {
        // hint 只在层内加权：输出层级必须非降序（L1 候选仍在全部 L2 之前——加权不越级）
        MentionRetrievalService service = service();
        RetrievalOutcome outcome = service.resolve("看下金额和订单数", context, null, "refund");
        var layers = outcome.dslReady().stream().map(RetrievedCandidate::layer).toList();
        for (int i = 1; i < layers.size(); i++) {
            assertTrue(layers.get(i) >= layers.get(i - 1),
                    "层级非降序（加权不越级）: " + layers);
        }
        // 被提示的候选在同层内排前
        var ids = outcome.dslReady().stream().map(RetrievedCandidate::assetId).toList();
        assertTrue(ids.indexOf("metric.refund_amount") < ids.lastIndexOf("metric.gmv")
                        || ids.indexOf("metric.refund_amount") < ids.indexOf("metric.order_count"),
                "hint 候选层内提前: " + ids);
    }

    // ---------- contextHash ----------

    @Test
    void contextHashStableForSameInputs() {
        String first = service().resolve("销售额", context, null).contextHash();
        String second = service().resolve("订单数", context, null).contextHash();
        assertEquals(first, second, "同清单同授权 → 同 contextHash（与话术无关）");
    }

    @Test
    void contextHashChangesWithAuthorization() {
        String before = service().resolve("销售额", context, null).contextHash();
        when(policySnapshotService.authorizedAssetIds(any()))
                .thenReturn(Set.of("metric|metric.gmv"));
        String after = service().resolve("销售额", context, null).contextHash();
        assertFalse(before.equals(after), "授权集合变化 → contextHash 变化（Proposal 对账基础）");
    }

    // ---------- 编辑距离（L3 基础件） ----------

    @Test
    void editDistanceBasics() {
        assertEquals(0, MentionRetrievalService.editDistance("订单", "订单"));
        assertEquals(1, MentionRetrievalService.editDistance("订丹", "订单"));
        assertEquals(1, MentionRetrievalService.editDistance("订单好", "订单号"));
        assertEquals(1, MentionRetrievalService.editDistance("高价质客户", "高价值客户"));
        assertEquals(3, MentionRetrievalService.editDistance("abc", "def"));
    }

    // ---------- fixture ----------

    /** 贴近首发清单 v8 的测试清单（别名/歧义词/模糊词均可控）。 */
    private static PublishedManifest manifest() {
        PublishedManifest manifest = new PublishedManifest();
        manifest.setManifestId("m-test");
        manifest.setContentHash("hash-test");

        // M6/W2/T2.4：场景清单（销售分析：gmv/order_count/region + hint）
        com.ontos.module.ontology.core.ManifestPayload.ScenarioMeta sales =
                new com.ontos.module.ontology.core.ManifestPayload.ScenarioMeta();
        sales.setId("scenario.sales");
        sales.setName("销售分析");
        sales.setStatus("published");
        sales.setEntryMode("auto_if_confident");
        sales.setEntryHints(List.of("订单趋势"));
        com.ontos.module.ontology.core.ManifestPayload.OntologyScope ontologyScope =
                new com.ontos.module.ontology.core.ManifestPayload.OntologyScope();
        ontologyScope.setRootConcepts(List.of("concept.order", "concept.customer"));
        ontologyScope.setExcludedConcepts(List.of());
        sales.setOntologyScope(ontologyScope);
        com.ontos.module.ontology.core.ManifestPayload.SemanticScope semanticScope =
                new com.ontos.module.ontology.core.ManifestPayload.SemanticScope();
        semanticScope.setMetrics(List.of("metric.gmv", "metric.order_count"));
        semanticScope.setDimensions(List.of("dim.region"));
        sales.setSemanticScope(semanticScope);
        sales.setAllowedAnalysisModes(List.of("query"));
        manifest.setScenarios(List.of(sales));

        com.ontos.module.ontology.core.SemanticManifest semantic =
                new com.ontos.module.ontology.core.SemanticManifest();
        semantic.setMetrics(List.of(
                metric("metric.gmv", "GMV", List.of("成交金额", "成交额", "销售额", "流水", "金额")),
                metric("metric.order_count", "订单数", List.of("出了多少单", "单量")),
                metric("metric.refund_amount", "退款金额", List.of("退款额", "金额"))));
        semantic.setDimensions(List.of(
                dimension("dim.region", "区域", List.of("大区", "地区")),
                dimension("dim.channel", "渠道", List.of("途径"))));
        manifest.setSemantic(semantic);

        com.ontos.module.ontology.core.OntologySnapshot ontology =
                new com.ontos.module.ontology.core.OntologySnapshot();
        com.ontos.module.ontology.core.OntologySnapshot.Concept order =
                new com.ontos.module.ontology.core.OntologySnapshot.Concept();
        order.setId("concept.order");
        order.setName("订单");
        order.setAliases(List.of("订货"));
        com.ontos.module.ontology.core.OntologySnapshot.Concept customer =
                new com.ontos.module.ontology.core.OntologySnapshot.Concept();
        customer.setId("concept.customer");
        customer.setName("客户");
        customer.setAliases(List.of("顾客"));
        ontology.setConcepts(List.of(order, customer));

        com.ontos.module.ontology.core.OntologySnapshot.Property email =
                new com.ontos.module.ontology.core.OntologySnapshot.Property();
        email.setId("property.customer.email");
        email.setName("邮箱");
        email.setAliases(List.of("电子邮件"));
        com.ontos.module.ontology.core.OntologySnapshot.Property orderNo =
                new com.ontos.module.ontology.core.OntologySnapshot.Property();
        orderNo.setId("property.order.order_no");
        orderNo.setName("订单号");
        orderNo.setAliases(List.of("单号"));
        ontology.setProperties(List.of(email, orderNo));
        manifest.setOntology(ontology);
        return manifest;
    }

    private static com.ontos.module.ontology.core.SemanticManifest.Metric metric(
            String id, String name, List<String> aliases) {
        com.ontos.module.ontology.core.SemanticManifest.Metric metric =
                new com.ontos.module.ontology.core.SemanticManifest.Metric();
        metric.setId(id);
        metric.setName(name);
        metric.setAliases(aliases);
        return metric;
    }

    private static com.ontos.module.ontology.core.SemanticManifest.Dimension dimension(
            String id, String name, List<String> aliases) {
        com.ontos.module.ontology.core.SemanticManifest.Dimension dimension =
                new com.ontos.module.ontology.core.SemanticManifest.Dimension();
        dimension.setId(id);
        dimension.setName(name);
        dimension.setAliases(aliases);
        return dimension;
    }

}

package com.ontos.module.session.service.retrieval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.ontos.framework.web.core.context.RequestContext;
import com.ontos.module.ontology.core.ManifestPayload;
import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.ontology.service.manifest.ManifestService;
import com.ontos.module.session.service.session.SessionProjection;
import com.ontos.module.system.service.policy.PolicySnapshotService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 检索瀑布（M5 规格 §3.2 / T2.1–T2.3）：授权裁剪 → L1 精确 → L2 词典 → L3 受限模糊 →
 * 指代消解 → 概念歧义澄清四要素 → 候选集 + context_hash。
 *
 * <p>硬边界（roadmap 冻结）：**模糊候选进入 DSL 次数为 0**——L3 命中只进
 * {@link RetrievalOutcome#fuzzyOnly()}（澄清候选来源），与 L1/L2 的
 * {@link RetrievalOutcome#dslReady()} 从数据结构上隔离；单测锁定。
 *
 * <p>授权裁剪用 {@link PolicySnapshotService#authorizedAssetIds}（W1 F-4 批量视图，
 * effect=allow ∧ 角色交集）做候选范围预过滤；无快照租户瀑布整体不可用
 * （requireActive 抛错，fail-closed，与判定层一致）。执行路径的逐资产 check 仍完整生效。
 */
@Slf4j
@Service
public class MentionRetrievalService {

    /** L3 受限模糊最大编辑距离（M5 初值 1；M7 评测后按 PoC #5 数据调整）。 */
    static final int FUZZY_MAX_DISTANCE = 1;

    private final ManifestService manifestService;
    private final PolicySnapshotService policySnapshotService;
    private volatile MentionIndex cachedIndex;

    private final com.ontos.module.session.service.scenario.ScenarioScopeService scenarioScopeService;
    /** 瀑布第 7 步：VQR 候选匹配（T3.5）。 */
    private final VqrCandidateMatcher vqrCandidateMatcher;

    @Autowired
    public MentionRetrievalService(ManifestService manifestService,
                                   PolicySnapshotService policySnapshotService,
                                   com.ontos.module.session.service.scenario.ScenarioScopeService scenarioScopeService,
                                   VqrCandidateMatcher vqrCandidateMatcher) {
        this.manifestService = manifestService;
        this.policySnapshotService = policySnapshotService;
        this.scenarioScopeService = scenarioScopeService;
        this.vqrCandidateMatcher = vqrCandidateMatcher;
    }

    /**
     * 解析一轮话术中的资产提及。
     *
     * @param utterance  用户原话（原样输入，不做预处理约定）
     * @param context    请求上下文（授权裁剪按 tenant+roles+userId）
     * @param projection 上一轮会话投影（指代消解来源；null = 首轮无指代）
     */
    public RetrievalOutcome resolve(String utterance, RequestContext context,
                                    SessionProjection projection) {
        return resolve(utterance, context, projection, null);
    }

    /**
     * R-29（M6/W4/T4.4）：variantHint 参与候选**排序权重**——词面匹配的指标候选加权提前；
     * 只重排不增删：候选集不变、授权裁剪结果不变（不扩候选集、不绕授权裁剪）。
     */
    public RetrievalOutcome resolve(String utterance, RequestContext context,
                                    SessionProjection projection, String variantHint) {
        PublishedManifest manifest = manifestService.getActiveManifestOrNull();
        if (manifest == null || manifest.getSemantic() == null) {
            // 无生效清单：能力发现是编排层职责，瀑布不假装有候选
            return new RetrievalOutcome(List.of(), List.of(), List.of(), List.of(), false, "");
        }
        MentionIndex index = indexFor(manifest);
        // 授权裁剪：候选范围预过滤（无快照租户 fail-closed，requireActive 抛错向上传播）
        Set<String> authorizedKeys = policySnapshotService.authorizedAssetIds(context);

        String text = utterance == null ? "" : utterance.strip();

        // M6/W2/T2.4：Scenario Entry Router 在授权裁剪后、L1 前完成入口确认与 scope 裁剪
        //（§7.5 步 2–3）；in_scenario 时裁剪键集 = 授权 ∩ 场景 scope（只收窄不扩大）。
        var scoped = scenarioScopeService.resolve(context, text, manifest, authorizedKeys);
        Set<String> scopedKeys = scoped.scopedAssetKeys();

        List<RetrievedCandidate> exact = new ArrayList<>();
        List<RetrievedCandidate> dictionary = new ArrayList<>();
        List<RetrievedCandidate> exactOutside = new ArrayList<>();
        List<RetrievedCandidate> dictionaryOutside = new ArrayList<>();
        List<RetrievedCandidate> fuzzy = new ArrayList<>();

        // L1 精确：话术全等 name/alias，或话术内出现资产 ID 字面量（高级用户直接引用）
        if (!text.isEmpty()) {
            for (MentionIndex.IndexEntry entry : indexTerms(index)) {
                if (!authorizedKeys.contains(key(entry.assetType(), entry.assetId()))) {
                    continue;
                }
                if (text.equals(entry.term())
                        || text.toLowerCase().contains(entry.assetId().toLowerCase())) {
                    RetrievedCandidate candidate = new RetrievedCandidate(entry.assetId(),
                            entry.assetType(), entry.term(), 1, false, 100);
                    if (scopedKeys.contains(key(entry.assetType(), entry.assetId()))) {
                        exact.add(candidate);
                    } else {
                        // 场景内不可见但授权域内可见：越界信号（触发 out-of-scope 澄清）
                        exactOutside.add(candidate);
                    }
                }
            }
        }

        // L2 词典：话术包含词（子串包含）；同词命中多资产 = 天然歧义（转澄清）
        if (!text.isEmpty()) {
            for (Map.Entry<String, List<MentionIndex.IndexEntry>> termEntry
                    : index.terms().entrySet()) {
                String term = termEntry.getKey();
                if (!text.contains(term)) {
                    continue;
                }
                for (MentionIndex.IndexEntry entry : termEntry.getValue()) {
                    if (!authorizedKeys.contains(key(entry.assetType(), entry.assetId()))) {
                        continue;
                    }
                    RetrievedCandidate candidate = new RetrievedCandidate(entry.assetId(),
                            entry.assetType(), term, 2, false, 60 + term.length());
                    if (scopedKeys.contains(key(entry.assetType(), entry.assetId()))) {
                        dictionary.add(candidate);
                    } else {
                        dictionaryOutside.add(candidate);
                    }
                }
            }
        }
        // 同词多资产：词条目重复出现（term+assetId 去重由 candidateDistinct 承担）

        // 越界澄清（§3.2）：场景内 L1/L2 全空且授权域外有命中 → 问题超出当前场景范围，
        // 二次路由产出 CLARIFY_OUT_OF_SCOPE 并落 pending_confirm（复用 M5 回传校验通道）。
        List<ClarificationPayload> clarifications = new ArrayList<>();
        com.ontos.module.session.core.scenario.ScenarioRouteDecision decision =
                scoped.decision();
        if (scoped.scenarioId() != null && exact.isEmpty() && dictionary.isEmpty()
                && (!exactOutside.isEmpty() || !dictionaryOutside.isEmpty())) {
            decision = scenarioScopeService.clarifyOutOfScope(context, manifest, authorizedKeys);
            ClarificationPayload payload = scenarioClarification(decision,
                    scoped.scenarioId(), "问题超出当前场景范围");
            if (payload != null) {
                clarifications.add(payload);
            }
        }

        // L3 受限模糊：滑窗编辑距离 ≤1；只补充 L1/L2 未命中的资产（不得覆盖精确/词典命中）；
        // 模糊候选只看场景裁剪后的键集（越界资产不进澄清候选）
        if (!text.isEmpty()) {
            Set<String> alreadyHit = new TreeSet<>();
            exact.forEach(candidate -> alreadyHit.add(candidate.assetId()));
            dictionary.forEach(candidate -> alreadyHit.add(candidate.assetId()));
            fuzzyMatch(text, index, scopedKeys, alreadyHit, fuzzy);
        }

        // 澄清四要素：同词多资产歧义（≥2 资产）或模糊命中（≥2 资产）+ 场景确认/越界澄清。
        // T24-4：编排层 clarificationTerminal 只取首个载荷——场景推荐/越界澄清是入口级语义，
        // 必须优先于资产级歧义/模糊（否则场景入口被淹没，pending 状态与澄清载荷脱节）。
        clarifications.addAll(buildClarifications(text, dictionary, fuzzy));
        if (decision.action() == com.ontos.module.session.core.scenario.ScenarioRouteDecision.Action.ASK_CONFIRM) {
            ClarificationPayload payload = scenarioClarification(decision, null, "是否进入场景");
            if (payload != null) {
                clarifications.add(0, payload);
            }
        }

        // 指代消解（T2.2）：本轮无 L1/L2 命中且有会话投影 → 投影实体候选；失效投影标记
        List<RetrievedCandidate> prevTurn = new ArrayList<>();
        boolean projectionStale = false;
        if (exact.isEmpty() && dictionary.isEmpty() && projection != null) {
            collectProjectionCandidates(projection, index, scopedKeys, prevTurn);
            projectionStale = prevTurn.isEmpty() && !projection.isEmpty();
        }

        // 场景上下文（仅 dslReady 非空才有 DSL 组装；澄清/待确认轮编排层不组装 DSL）
        RetrievalOutcome.ScenarioContext scenarioContext = null;
        if (decision.targetState() == com.ontos.module.session.core.scenario.ScenarioRouteState.IN_SCENARIO
                && scoped.scenarioId() != null && clarifications.isEmpty()) {
            var scenario = scoped.scenario();
            scenarioContext = new RetrievalOutcome.ScenarioContext(scoped.scenarioId(),
                    decision.source() == null ? null : decision.source().code(),
                    scenario == null ? List.of() : scenario.getAllowedAnalysisModes(),
                    calibersOf(scenario));
        }

        // 瀑布第 7 步（§7.5，T3.5）：VQR 候选匹配——确定性模板匹配已发布 VQR，
        // 场景内限于场景引用集（defaultVqrIds ∪ coldStartSamples）；命中带已提取参数，
        // 编排提案引用 vqrId（候选集外引用被完整性闸门拦截；命中后仍走 execute 全链重校验）
        List<RetrievalOutcome.VqrCandidate> vqrCandidates = vqrCandidateMatcher.match(
                text, manifest, scoped.scenarioId());

        // R-29：variantHint 排序权重——只重排 dslReady（模糊/prevTurn 不加），候选集不变
        List<RetrievedCandidate> dslReady = applyVariantHintBoost(
                distinct(exact, dictionary), variantHint);

        return new RetrievalOutcome(dslReady, distinct(fuzzy), prevTurn,
                clarifications, projectionStale,
                contextHash(manifest, authorizedKeys, scopedKeys), scenarioContext, vqrCandidates);
    }

    /**
     * variantHint 词面加权（R-29）：matchedTerm / assetId 包含 hint 的候选 +100 分后按既有
     * 比较器重排——同层级内被提示的候选排前；层级不跨越（L1 仍先于 L2，不产生越级）。
     * 返回新列表（输入可能来自不可变 toList()，就地 clear 会 uoe）。
     */
    private static List<RetrievedCandidate> applyVariantHintBoost(
            List<RetrievedCandidate> candidates, String variantHint) {
        if (variantHint == null || variantHint.isBlank() || candidates.isEmpty()) {
            return candidates;
        }
        String hint = variantHint.strip();
        List<RetrievedCandidate> boosted = new ArrayList<>(candidates.size());
        for (RetrievedCandidate candidate : candidates) {
            boolean hit = candidate.matchedTerm() != null
                    && candidate.matchedTerm().contains(hint)
                    || (candidate.assetId() != null && candidate.assetId().contains(hint));
            boosted.add(hit ? new RetrievedCandidate(candidate.assetId(), candidate.assetType(),
                    candidate.matchedTerm(), candidate.layer(), candidate.fuzzy(),
                    candidate.score() + 100) : candidate);
        }
        java.util.Collections.sort(boosted);
        return java.util.List.copyOf(boosted);
    }

    /** 场景决策 → 澄清载荷（候选 = 进入/切换/退出；不足 2 个时补「留在场景改写问法」）。 */
    private ClarificationPayload scenarioClarification(
            com.ontos.module.session.core.scenario.ScenarioRouteDecision decision,
            String currentScenarioId, String mention) {
        List<RetrievedCandidate> candidates = new ArrayList<>();
        for (var choice : decision.candidates()) {
            candidates.add(new RetrievedCandidate(choice.candidateId(), "scenario",
                    choice.label(), 0, false, 0));
        }
        if (candidates.size() == 1 && currentScenarioId != null) {
            // 单场景清单的越界澄清只有退出候选：补「留在场景」选项（确认回到同场景）
            candidates.add(new RetrievedCandidate(
                    com.ontos.module.session.core.scenario.ScenarioRouter.candidateIdOf(currentScenarioId),
                    "scenario", "留在场景内改写问法", 0, false, 0));
        }
        if (candidates.size() < 2) {
            return null;
        }
        return new ClarificationPayload(mention, candidates, candidates.get(0).assetId(), true);
    }

    /** frozen_calibers → 名称到值映射（时间绑定冲突校验在直连 guard；此处供审计与编排）。 */
    private static Map<String, String> calibersOf(ManifestPayload.ScenarioMeta scenario) {
        if (scenario == null || scenario.getSemanticScope() == null
                || scenario.getSemanticScope().getFrozenCalibers() == null) {
            return Map.of();
        }
        Map<String, String> values = new LinkedHashMap<>();
        scenario.getSemanticScope().getFrozenCalibers().forEach((name, caliber) -> {
            if (caliber.getValue() != null) {
                values.put(name, caliber.getValue());
            }
        });
        return values;
    }

    /** L1+L2 合并去重（同资产同词保留高分），dslReady 出口——只有这里的内容允许进 DSL。 */
    private List<RetrievedCandidate> distinct(List<RetrievedCandidate> exact,
                                              List<RetrievedCandidate> dictionary) {
        List<RetrievedCandidate> merged = new ArrayList<>(exact);
        merged.addAll(dictionary);
        java.util.Collections.sort(merged);
        return merged.stream()
                .collect(java.util.stream.Collectors.toMap(RetrievedCandidate::assetId,
                        candidate -> candidate, (first, second) -> first,
                        java.util.LinkedHashMap::new))
                .values().stream().sorted().toList();
    }

    private List<RetrievedCandidate> distinct(List<RetrievedCandidate> fuzzy) {
        java.util.Collections.sort(fuzzy);
        return fuzzy.stream()
                .collect(java.util.stream.Collectors.toMap(RetrievedCandidate::assetId,
                        candidate -> candidate, (first, second) -> first,
                        java.util.LinkedHashMap::new))
                .values().stream().sorted().toList();
    }

    /** 澄清四要素装配（T2.3）：候选 2–5、带资产 ID、默认建议、可跳过。 */
    private List<ClarificationPayload> buildClarifications(String text,
                                                           List<RetrievedCandidate> dictionary,
                                                           List<RetrievedCandidate> fuzzy) {
        List<ClarificationPayload> payloads = new ArrayList<>();
        // 同词多资产歧义：按命中词分组，词内 ≥2 资产即澄清
        Map<String, List<RetrievedCandidate>> byTerm = new java.util.LinkedHashMap<>();
        dictionary.forEach(candidate ->
                byTerm.computeIfAbsent(candidate.matchedTerm(), key -> new ArrayList<>())
                        .add(candidate));
        for (Map.Entry<String, List<RetrievedCandidate>> entry : byTerm.entrySet()) {
            List<RetrievedCandidate> candidates = entry.getValue().stream()
                    .sorted().limit(5).toList();
            if (candidates.size() >= 2) {
                payloads.add(new ClarificationPayload(entry.getKey(), candidates,
                        candidates.get(0).assetId(), true));
            }
        }
        // 模糊命中 ≥2 资产：受限模糊只能以澄清形式呈现（零进 DSL）
        if (fuzzy.size() >= 2) {
            List<RetrievedCandidate> candidates = fuzzy.stream().limit(5).toList();
            payloads.add(new ClarificationPayload(text, candidates,
                    candidates.get(0).assetId(), true));
        }
        return payloads;
    }

    /** 指代消解：投影实体按授权+清单存在性复核；任一失效即整体标记 stale（上下文不可信）。 */
    private void collectProjectionCandidates(SessionProjection projection, MentionIndex index,
                                             Set<String> authorizedKeys,
                                             List<RetrievedCandidate> prevTurn) {
        boolean stale = false;
        for (String metricId : projection.metricIds()) {
            stale |= !authorizedKeys.contains("metric|" + metricId)
                    || indexTerms(index).stream().noneMatch(entry -> entry.assetId().equals(metricId));
            prevTurn.add(new RetrievedCandidate(metricId, "metric", "prev_turn", 0, false, 80));
        }
        for (String dimensionId : projection.dimensionIds()) {
            stale |= !authorizedKeys.contains("dimension|" + dimensionId);
            prevTurn.add(new RetrievedCandidate(dimensionId, "dimension", "prev_turn", 0, false, 80));
        }
        for (String propertyId : projection.propertyIds()) {
            stale |= !authorizedKeys.contains("property|" + propertyId);
            prevTurn.add(new RetrievedCandidate(propertyId, "property", "prev_turn", 0, false, 80));
        }
        if (projection.entityId() != null && !projection.entityId().isBlank()) {
            stale |= !authorizedKeys.contains("entity|" + projection.entityId());
            prevTurn.add(new RetrievedCandidate(projection.entityId(), "entity", "prev_turn",
                    0, false, 80));
        }
        if (stale) {
            // 失效投影整体作废：部分降权的上下文继续用会拼出越权组合
            prevTurn.clear();
        }
    }

    /** L3 滑窗：对每个词取 utterance 上长度 n-1/n/n+1 的窗口，编辑距离 ≤1 即命中。 */
    private void fuzzyMatch(String text, MentionIndex index, Set<String> authorizedKeys,
                            Set<String> alreadyHit, List<RetrievedCandidate> fuzzy) {
        for (Map.Entry<Integer, List<MentionIndex.IndexEntry>> lengthGroup
                : index.termsByLength().entrySet()) {
            int termLength = lengthGroup.getKey();
            for (int windowLength = Math.max(termLength - 1, 2);
                    windowLength <= termLength + 1 && windowLength <= text.length();
                    windowLength++) {
                for (int start = 0; start + windowLength <= text.length(); start++) {
                    String window = text.substring(start, start + windowLength);
                    for (MentionIndex.IndexEntry entry : lengthGroup.getValue()) {
                        if (alreadyHit.contains(entry.assetId())
                                || !authorizedKeys.contains(key(entry.assetType(), entry.assetId()))) {
                            continue;
                        }
                        if (editDistance(window, entry.term()) <= FUZZY_MAX_DISTANCE) {
                            fuzzy.add(new RetrievedCandidate(entry.assetId(), entry.assetType(),
                                    window, 3, true,
                                    30 + entry.term().length() - FUZZY_MAX_DISTANCE));
                        }
                    }
                }
            }
        }
    }

    /** 经典 DP 编辑距离（两行滚动，词长 ≤ 8 级别无性能考量）。 */
    static int editDistance(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int substitution = previous[j - 1]
                        + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitution,
                        Math.min(previous[j] + 1, current[j - 1] + 1));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }

    private List<MentionIndex.IndexEntry> indexTerms(MentionIndex index) {
        List<MentionIndex.IndexEntry> all = new ArrayList<>();
        index.terms().values().forEach(all::addAll);
        return all;
    }

    /** 候选集内容哈希：清单 contentHash + 授权键集合（排序）——Proposal 对账的基准。 */
    /** 场景裁剪参与哈希：同授权不同场景（或同场景不同裁剪）的候选集上下文必须可区分。 */
    static String contextHash(PublishedManifest manifest, Set<String> authorizedKeys,
                              Set<String> scopedKeys) {
        java.util.List<String> combined = new ArrayList<>(authorizedKeys);
        scopedKeys.forEach(key -> combined.add("scope|" + key));
        return contextHash(manifest, new java.util.LinkedHashSet<>(combined));
    }

    static String contextHash(PublishedManifest manifest, Set<String> authorizedKeys) {
        TreeSet<String> ordered = new TreeSet<>(authorizedKeys);
        StringBuilder material = new StringBuilder(manifest.getContentHash()).append('|');
        ordered.forEach(key -> material.append(key).append(','));
        return PublishedManifest.sha256(material.toString());
    }

    private static String key(String assetType, String assetId) {
        return assetType + "|" + assetId;
    }

    /** 索引获取：清单切换即整体重建（清单内容不可变，无增量更新需求）。 */
    private MentionIndex indexFor(PublishedManifest manifest) {
        MentionIndex index = cachedIndex;
        if (index == null || !index.forManifest(manifest)) {
            index = MentionIndex.build(manifest);
            cachedIndex = index;
        }
        return index;
    }
}

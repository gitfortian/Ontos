package com.ontos.module.session.service.retrieval;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.ontos.framework.web.core.context.RequestContext;
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

    @Autowired
    public MentionRetrievalService(ManifestService manifestService,
                                   PolicySnapshotService policySnapshotService) {
        this.manifestService = manifestService;
        this.policySnapshotService = policySnapshotService;
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
        PublishedManifest manifest = manifestService.getActiveManifestOrNull();
        if (manifest == null || manifest.getSemantic() == null) {
            // 无生效清单：能力发现是编排层职责，瀑布不假装有候选
            return new RetrievalOutcome(List.of(), List.of(), List.of(), List.of(), false, "");
        }
        MentionIndex index = indexFor(manifest);
        // 授权裁剪：候选范围预过滤（无快照租户 fail-closed，requireActive 抛错向上传播）
        Set<String> authorizedKeys = policySnapshotService.authorizedAssetIds(context);

        String text = utterance == null ? "" : utterance.strip();
        List<RetrievedCandidate> exact = new ArrayList<>();
        List<RetrievedCandidate> dictionary = new ArrayList<>();
        List<RetrievedCandidate> fuzzy = new ArrayList<>();

        // L1 精确：话术全等 name/alias，或话术内出现资产 ID 字面量（高级用户直接引用）
        if (!text.isEmpty()) {
            for (MentionIndex.IndexEntry entry : indexTerms(index)) {
                if (!authorizedKeys.contains(key(entry.assetType(), entry.assetId()))) {
                    continue;
                }
                if (text.equals(entry.term())
                        || text.toLowerCase().contains(entry.assetId().toLowerCase())) {
                    exact.add(new RetrievedCandidate(entry.assetId(), entry.assetType(),
                            entry.term(), 1, false, 100));
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
                    dictionary.add(new RetrievedCandidate(entry.assetId(), entry.assetType(),
                            term, 2, false, 60 + term.length()));
                }
            }
        }
        // 同词多资产：词条目重复出现（term+assetId 去重由 candidateDistinct 承担）

        // L3 受限模糊：滑窗编辑距离 ≤1；只补充 L1/L2 未命中的资产（不得覆盖精确/词典命中）
        if (!text.isEmpty()) {
            Set<String> alreadyHit = new TreeSet<>();
            exact.forEach(candidate -> alreadyHit.add(candidate.assetId()));
            dictionary.forEach(candidate -> alreadyHit.add(candidate.assetId()));
            fuzzyMatch(text, index, authorizedKeys, alreadyHit, fuzzy);
        }

        // 澄清四要素：同词多资产歧义（≥2 资产）或模糊命中（≥2 资产）
        List<ClarificationPayload> clarifications = buildClarifications(text, dictionary, fuzzy);

        // 指代消解（T2.2）：本轮无 L1/L2 命中且有会话投影 → 投影实体候选；失效投影标记
        List<RetrievedCandidate> prevTurn = new ArrayList<>();
        boolean projectionStale = false;
        if (exact.isEmpty() && dictionary.isEmpty() && projection != null) {
            collectProjectionCandidates(projection, index, authorizedKeys, prevTurn);
            projectionStale = prevTurn.isEmpty() && !projection.isEmpty();
        }

        return new RetrievalOutcome(distinct(exact, dictionary), distinct(fuzzy), prevTurn,
                clarifications, projectionStale, contextHash(manifest, authorizedKeys));
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

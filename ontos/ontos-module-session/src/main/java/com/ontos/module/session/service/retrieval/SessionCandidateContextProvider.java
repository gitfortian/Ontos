package com.ontos.module.session.service.retrieval;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.ontos.framework.common.agent.AgentContextProvider;
import com.ontos.framework.common.agent.CandidateAsset;
import com.ontos.framework.web.core.context.RequestContext;
import com.ontos.framework.web.core.context.RequestContextHolder;
import com.ontos.module.ontology.core.OntologySnapshot;
import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.ontology.core.SemanticManifest;
import com.ontos.module.ontology.service.manifest.ManifestService;
import com.ontos.module.system.service.policy.PolicySnapshotService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 授权裁剪后的候选集供给（M5 规格 T2.1「AgentContextProvider 注入」）。
 *
 * <p>在 ontology 模块 {@code ManifestCandidateContextProvider}（全量清单候选）之上，
 * 叠加 M4 授权子图过滤（{@link PolicySnapshotService#authorizedAssetIds}，effect=allow ∧
 * 角色交集）——模型可见词表 = 用户可查资产，从源头消除「引用了无权资产」的输出面；
 * 无生效清单或无快照时返回空（提示词约定走能力发现，不编造 ID）。
 *
 * <p>标记 {@code @Primary}：W3 编排器装配合同 Supplier 时以授权裁剪版为准，
 * 全量版保留为管理端/诊断用途。
 */
@Slf4j
@Primary
@Component
public class SessionCandidateContextProvider implements AgentContextProvider {

    private final ManifestService manifestService;
    private final PolicySnapshotService policySnapshotService;

    /**
     * E-7（M7/W2）候选域开关：{@code full} = 全量授权清单（默认，向后兼容）；
     * {@code retrieval} = 提示词词表收敛到**本轮对账口径**（dslReady ∪ fuzzy ∪ prevTurn）。
     *
     * <p>收敛的依据：来源完整性对账（{@code ProposalIntegrityService.candidateIds}）只认
     * 检索候选——全量词表让模型「看得到却引不得」，引用即触发 ID_NOT_IN_SET 澄清，
     * 这是首跑 47% 过澄清率的一个根因。收敛后提示词与对账口径严格一致；
     * dslReady 为空（澄清 / discovery 态）时回退全量，保证能力发现不受影响。
     */
    @org.springframework.beans.factory.annotation.Value("${ontos.session.candidate-scope:full}")
    private String candidateScope;

    /** 本轮检索结果（由 ask 编排在调用模型前 prime；AG-UI 等路径不 prime → 全量）。 */
    private final ThreadLocal<RetrievalOutcome> primedOutcome = new ThreadLocal<>();

    /**
     * 问题感知候选快照：AgentScope 修复轮运行在异步线程，拿不到请求线程 ThreadLocal。
     * key=用户问题 hash，value=本轮检索结果 + prime 时已解析的授权键；用后即清。
     */
    private final java.util.concurrent.ConcurrentMap<Integer, PrimedCandidate> primedByQuestion =
            new java.util.concurrent.ConcurrentHashMap<>();

    private record PrimedCandidate(RetrievalOutcome outcome, Set<String> authorizedKeys) {
    }

    public SessionCandidateContextProvider(ManifestService manifestService,
                                           PolicySnapshotService policySnapshotService) {
        this.manifestService = manifestService;
        this.policySnapshotService = policySnapshotService;
    }

    /** ask 编排在请求线程上注入本轮检索结果（Reactor 线程不可依赖 ThreadLocal，故显式传值）。 */
    public void prime(RetrievalOutcome outcome) {
        primedOutcome.set(outcome);
    }

    /** 同时为异步修复轮建立问题感知候选快照；question 为空时只保留同线程 prime。 */
    public void prime(RetrievalOutcome outcome, String question) {
        prime(outcome);
        if (question == null || question.isBlank()) {
            return;
        }
        RequestContext context = RequestContextHolder.get();
        if (context == null) {
            return;
        }
        try {
            primedByQuestion.put(question.hashCode(),
                    new PrimedCandidate(outcome, policySnapshotService.authorizedAssetIds(context)));
        } catch (RuntimeException ex) {
            log.warn("[prime][异步候选授权解析失败，修复轮将回退全量: {}]", ex.toString());
        }
    }

    /** 用后即清，防止线程池复用串话。 */
    public void clearPrimed() {
        primedOutcome.remove();
    }

    public void clearPrimed(String question) {
        if (question != null) {
            primedByQuestion.remove(question.hashCode());
        }
    }

    boolean retrievalScopeEnabled() {
        return "retrieval".equalsIgnoreCase(candidateScope);
    }

    /** 测试与装配期显式置域（生产走配置）。 */
    void setRetrievalScope(boolean enabled) {
        candidateScope = enabled ? "retrieval" : "full";
    }

    @Override
    public List<CandidateAsset> candidateVocabulary(String userQuestion) {
        if (userQuestion == null || userQuestion.isBlank()) {
            return candidateVocabulary();
        }
        PrimedCandidate primed = primedByQuestion.get(userQuestion.hashCode());
        if (primed == null) {
            return candidateVocabulary();
        }
        PublishedManifest manifest = manifestService.getActiveManifestOrNull();
        if (manifest == null || manifest.getSemantic() == null) {
            return List.of();
        }
        RetrievalOutcome outcome = primed.outcome();
        if (retrievalScopeEnabled() && outcome != null && !outcome.dslReady().isEmpty()) {
            List<CandidateAsset> scoped = vocabularyFromOutcome(manifest, outcome, primed.authorizedKeys());
            if (!scoped.isEmpty()) {
                return List.copyOf(scoped);
            }
        }
        return fullVocabulary(manifest, primed.authorizedKeys());
    }

    @Override
    public List<CandidateAsset> candidateVocabulary() {
        PublishedManifest manifest = manifestService.getActiveManifestOrNull();
        if (manifest == null || manifest.getSemantic() == null) {
            return List.of();
        }
        Set<String> authorizedKeys;
        try {
            RequestContext context = RequestContextHolder.get();
            if (context == null) {
                return List.of();
            }
            authorizedKeys = policySnapshotService.authorizedAssetIds(context);
        } catch (RuntimeException ex) {
            // 无快照租户：候选集为空（fail-closed，能力发现兜底），不在提示词注入通道抛错
            log.warn("[candidateVocabulary][授权视图不可用，候选集置空: {}]", ex.toString());
            return List.of();
        }
        // E-7 收敛口径：本轮检索已有可执行候选时，词表收敛到对账口径（提示词 ⊆ 对账集）
        RetrievalOutcome primed = primedOutcome.get();
        if (retrievalScopeEnabled() && primed != null && !primed.dslReady().isEmpty()) {
            List<CandidateAsset> scoped = vocabularyFromOutcome(manifest, primed, authorizedKeys);
            if (!scoped.isEmpty()) {
                return List.copyOf(scoped);
            }
            // 检索候选解析失败（清单漂移等）→ 回退全量，不给模型一个空词表
        }
        return fullVocabulary(manifest, authorizedKeys);
    }

    /** 全量授权词表：metric / dimension / entity / property / published VQR。 */
    private List<CandidateAsset> fullVocabulary(PublishedManifest manifest, Set<String> authorizedKeys) {
        List<CandidateAsset> vocabulary = new ArrayList<>();
        SemanticManifest semantic = manifest.getSemantic();
        if (semantic.getMetrics() != null) {
            semantic.getMetrics().forEach(metric -> {
                if (authorizedKeys.contains("metric|" + metric.getId())) {
                    vocabulary.add(new CandidateAsset(metric.getId(), metric.getName(),
                            Set.copyOf(metric.getAliases())));
                }
            });
        }
        if (semantic.getDimensions() != null) {
            semantic.getDimensions().forEach(dimension -> {
                if (authorizedKeys.contains("dimension|" + dimension.getId())) {
                    vocabulary.add(new CandidateAsset(dimension.getId(), dimension.getName(),
                            Set.copyOf(dimension.getAliases())));
                }
            });
        }
        OntologySnapshot ontology = manifest.getOntology();
        if (ontology != null) {
            if (ontology.getConcepts() != null) {
                ontology.getConcepts().forEach(concept -> {
                    if (authorizedKeys.contains("entity|" + concept.getId())) {
                        vocabulary.add(new CandidateAsset(concept.getId(), concept.getName(),
                                Set.copyOf(concept.getAliases())));
                    }
                });
            }
            if (ontology.getProperties() != null) {
                ontology.getProperties().forEach(property -> {
                    if (authorizedKeys.contains("property|" + property.getId())) {
                        vocabulary.add(new CandidateAsset(property.getId(), property.getName(),
                                Set.copyOf(property.getAliases())));
                    }
                });
            }
        }
        // T3.5：published VQR 进词表（提案可引用 vqr_id；越权防线在 execute 全链 RLS/策略，
        // 词表只约束「提案引用的 ID 必须真实存在」）
        if (manifest.getVqrs() != null) {
            manifest.getVqrs().stream()
                    .filter(vqr -> "published".equals(vqr.getStatus()))
                    .forEach(vqr -> vocabulary.add(
                            new CandidateAsset(vqr.getVqrId(), vqr.getName(), java.util.Set.of())));
        }
        return List.copyOf(vocabulary);
    }

    /**
     * 从本轮检索候选构造词表：id 解析回清单资产（拿 displayName 与别名）；
     * 解析不到的 id（如 vqr 模板）以 id 兜底。授权键二次校验（候选本就经过裁剪，防御性）。
     */
    private List<CandidateAsset> vocabularyFromOutcome(PublishedManifest manifest,
                                                       RetrievalOutcome outcome,
                                                       Set<String> authorizedKeys) {
        SemanticManifest semantic = manifest.getSemantic();
        OntologySnapshot ontology = manifest.getOntology();
        List<CandidateAsset> vocabulary = new ArrayList<>();
        Set<String> seen = new java.util.LinkedHashSet<>();
        java.util.function.BiConsumer<String, String> addIfKnown = (assetKey, assetId) -> {
            if (!seen.add(assetId) || !authorizedKeys.contains(assetKey + "|" + assetId)) {
                return;
            }
            if (semantic != null) {
                SemanticManifest.Metric metric = semantic.getMetrics() == null ? null
                        : semantic.getMetrics().stream()
                                .filter(item -> item.getId().equals(assetId)).findFirst().orElse(null);
                if (metric != null) {
                    vocabulary.add(new CandidateAsset(assetId, metric.getName(),
                            Set.copyOf(metric.getAliases())));
                    return;
                }
                SemanticManifest.Dimension dimension = semantic.getDimensions() == null ? null
                        : semantic.getDimensions().stream()
                                .filter(item -> item.getId().equals(assetId)).findFirst().orElse(null);
                if (dimension != null) {
                    vocabulary.add(new CandidateAsset(assetId, dimension.getName(),
                            Set.copyOf(dimension.getAliases())));
                    return;
                }
            }
            if (ontology != null && ontology.getProperties() != null) {
                OntologySnapshot.Property property = ontology.getProperties().stream()
                        .filter(item -> item.getId().equals(assetId)).findFirst().orElse(null);
                if (property != null) {
                    vocabulary.add(new CandidateAsset(assetId, property.getName(),
                            Set.copyOf(property.getAliases())));
                    return;
                }
                OntologySnapshot.Concept concept = ontology.getConcepts() == null ? null
                        : ontology.getConcepts().stream()
                                .filter(item -> item.getId().equals(assetId)).findFirst().orElse(null);
                if (concept != null) {
                    vocabulary.add(new CandidateAsset(assetId, concept.getName(),
                            Set.copyOf(concept.getAliases())));
                    return;
                }
            }
            // 清单里找不到（vqr 等）：id 兜底，保证对账口径内的 id 不从词表消失
            vocabulary.add(new CandidateAsset(assetId, assetId, java.util.Set.of()));
        };
        outcome.dslReady().forEach(candidate ->
                addIfKnown.accept(candidate.assetType(), candidate.assetId()));
        outcome.fuzzyOnly().forEach(candidate ->
                addIfKnown.accept(candidate.assetType(), candidate.assetId()));
        outcome.prevTurn().forEach(candidate ->
                addIfKnown.accept(candidate.assetType(), candidate.assetId()));
        return vocabulary;
    }

    @Override
    public String candidateContext() {
        List<CandidateAsset> vocabulary = candidateVocabulary();
        if (vocabulary.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder("## 可用资产候选集（只能引用其中的 ID）\n\n");
        for (CandidateAsset asset : vocabulary) {
            builder.append("- `").append(asset.assetId()).append("` ").append(asset.displayName());
            if (!asset.aliases().isEmpty()) {
                builder.append("（也叫 ").append(String.join("、", asset.aliases())).append("）");
            }
            builder.append('\n');
        }
        return builder.toString();
    }
}

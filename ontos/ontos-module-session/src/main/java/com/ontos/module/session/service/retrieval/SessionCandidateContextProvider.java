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

    public SessionCandidateContextProvider(ManifestService manifestService,
                                           PolicySnapshotService policySnapshotService) {
        this.manifestService = manifestService;
        this.policySnapshotService = policySnapshotService;
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

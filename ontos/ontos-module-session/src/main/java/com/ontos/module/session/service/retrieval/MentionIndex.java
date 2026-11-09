package com.ontos.module.session.service.retrieval;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ontos.module.ontology.core.OntologySnapshot;
import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.ontology.core.SemanticManifest;
import lombok.extern.slf4j.Slf4j;

/**
 * 检索词表内存索引（M5 规格 T2.1，v1.1 F-2/F-3 口径）。
 *
 * <p>词表来源：指标/维度/业务对象/信息项的 {@code name + aliases} 与跨资产补充词（synonyms）。
 * **子串包含匹配 + Java 内存编辑距离**，不引分词器与 DB 扩展（定档 ③ 与 F-2/F-3 修正）；
 * 首发清单 35 资产规模下线性扫描无性能问题，索引在清单变化时整体重建。
 *
 * <p>索引与授权正交：本索引只做「词 → 资产」的机械映射，授权裁剪由
 * {@link MentionRetrievalService} 按 {@code authorizedAssetIds} 在查询期过滤——
 * 清单索引全局一份，授权按租户/用户在运行时收窄。
 */
@Slf4j
public final class MentionIndex {

    /** 单字词不进索引：中文单字提歧义面过大（「单」「额」等），检索质量由 ≥2 词保障。 */
    static final int MIN_TERM_LENGTH = 2;

    private final String manifestId;
    private final String contentHash;
    /** 词 → 条目列表（同一词可指向多资产 = 天然歧义源）。 */
    private final Map<String, List<IndexEntry>> termIndex;
    /** 全部词表（L3 滑窗编辑距离用），已按长度分组。 */
    private final Map<Integer, List<IndexEntry>> termsByLength;

    private MentionIndex(String manifestId, String contentHash,
                         Map<String, List<IndexEntry>> termIndex,
                         Map<Integer, List<IndexEntry>> termsByLength) {
        this.manifestId = manifestId;
        this.contentHash = contentHash;
        this.termIndex = Map.copyOf(termIndex);
        this.termsByLength = Map.copyOf(termsByLength);
    }

    /**
     * 从生效清单构建索引。清单内容不可变（payload 哈希锁定），构建一次永久有效，
     * 直至清单切换（调用方按 {@code forManifest} 的 hash 判断重建）。
     */
    public static MentionIndex build(PublishedManifest manifest) {
        Map<String, List<IndexEntry>> termIndex = new LinkedHashMap<>();
        Map<Integer, List<IndexEntry>> termsByLength = new HashMap<>();
        SemanticManifest semantic = manifest.getSemantic();
        OntologySnapshot ontology = manifest.getOntology();

        if (semantic != null) {
            if (semantic.getMetrics() != null) {
                semantic.getMetrics().forEach(metric ->
                        addAsset(termIndex, termsByLength, metric.getId(), "metric",
                                metric.getName(), metric.getAliases()));
            }
            if (semantic.getDimensions() != null) {
                semantic.getDimensions().forEach(dimension ->
                        addAsset(termIndex, termsByLength, dimension.getId(), "dimension",
                                dimension.getName(), dimension.getAliases()));
            }
        }
        if (ontology != null) {
            if (ontology.getConcepts() != null) {
                ontology.getConcepts().forEach(concept ->
                        addAsset(termIndex, termsByLength, concept.getId(), "entity",
                                concept.getName(), concept.getAliases()));
            }
            if (ontology.getProperties() != null) {
                ontology.getProperties().forEach(property ->
                        addAsset(termIndex, termsByLength, property.getId(), "property",
                                property.getName(), property.getAliases()));
            }
            if (ontology.getSynonyms() != null) {
                // 跨资产补充词：term → assetId 指向既有资产，资产类型从已登记条目反查
                for (OntologySnapshot.Synonym synonym : ontology.getSynonyms()) {
                    addTerm(termIndex, termsByLength, synonym.getTerm(),
                            new IndexEntry(synonym.getAssetId(), assetTypeOf(synonym.getAssetId()),
                                    synonym.getTerm(), "synonym"));
                }
            }
        }
        log.info("[build][mention index built: manifest={} terms={}]", manifest.getManifestId(),
                termIndex.size());
        return new MentionIndex(manifest.getManifestId(), manifest.getContentHash(),
                termIndex, termsByLength);
    }

    private static String assetTypeOf(String assetId) {
        if (assetId.startsWith("metric.")) {
            return "metric";
        }
        if (assetId.startsWith("dim.")) {
            return "dimension";
        }
        if (assetId.startsWith("property.")) {
            return "property";
        }
        return "entity";
    }

    private static void addAsset(Map<String, List<IndexEntry>> termIndex,
                                 Map<Integer, List<IndexEntry>> termsByLength,
                                 String assetId, String assetType, String name, List<String> aliases) {
        if (name != null && !name.isBlank()) {
            addTerm(termIndex, termsByLength, name.strip(),
                    new IndexEntry(assetId, assetType, name.strip(), "name"));
        }
        if (aliases != null) {
            for (String alias : aliases) {
                if (alias != null && !alias.isBlank()) {
                    addTerm(termIndex, termsByLength, alias.strip(),
                            new IndexEntry(assetId, assetType, alias.strip(), "alias"));
                }
            }
        }
    }

    private static void addTerm(Map<String, List<IndexEntry>> termIndex,
                                Map<Integer, List<IndexEntry>> termsByLength,
                                String term, IndexEntry entry) {
        if (term.length() < MIN_TERM_LENGTH) {
            return;
        }
        termIndex.computeIfAbsent(term, key -> new ArrayList<>()).add(entry);
        termsByLength.computeIfAbsent(term.length(), key -> new ArrayList<>()).add(entry);
    }

    /** 与 {@link #build} 的清单一致性判断：id+contentHash 不同即需要重建。 */
    public boolean forManifest(PublishedManifest manifest) {
        return manifest != null
                && this.manifestId.equals(manifest.getManifestId())
                && this.contentHash.equals(manifest.getContentHash());
    }

    public String manifestId() {
        return manifestId;
    }

    public String contentHash() {
        return contentHash;
    }

    /** L1/L2：词的精确条目（含同词多资产歧义）。 */
    public List<IndexEntry> exact(String term) {
        return termIndex.getOrDefault(term, List.of());
    }

    /** 全部词表（只读视图：词 → 条目），L2 子串匹配与授权遍历用。 */
    public Map<String, List<IndexEntry>> terms() {
        return termIndex;
    }

    /** 按词长分组的全部词表（只读视图），L3 滑窗编辑距离用。 */
    public Map<Integer, List<IndexEntry>> termsByLength() {
        return termsByLength;
    }

    /** L3：指定长度的全部词表条目（滑窗编辑距离用）。 */
    public List<IndexEntry> termsOfLength(int length) {
        return termsByLength.getOrDefault(length, List.of());
    }

    public int termCount() {
        return termIndex.size();
    }

    /**
     * 索引条目：词到资产的映射。{@code source} 记录词条来源（name/alias/synonym），
     * 便于澄清候选展示与评测归因。
     */
    public record IndexEntry(String assetId, String assetType, String term, String source) {
    }

}

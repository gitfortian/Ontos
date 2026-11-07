package com.ontos.framework.common.agent;

import java.util.Set;

/**
 * 候选资产的词表：供输出契约做「问题是否点名了资产」的机械判定（检索瀑布第一级的前置）。
 *
 * <p>{@code displayName} 与 {@code aliases} 共同构成匹配词表——业务化命名规格
 * （docs/04-specs/ontology-业务化命名-规格-v1.md）要求两者必填，词表随清单发布而完善。
 *
 * @param assetId     资产 ID（metric.x / dim.x 等）
 * @param displayName 业务名（如「成交金额」「GMV」）
 * @param aliases     常见叫法（同义词 / 口语 / 缩写），可空
 */
public record CandidateAsset(String assetId, String displayName, Set<String> aliases) {

    public CandidateAsset {
        aliases = aliases == null ? Set.of() : Set.copyOf(aliases);
    }

    /** 参与匹配的全部词（业务名 + 别名），已去空。 */
    public Set<String> terms() {
        java.util.Set<String> terms = new java.util.HashSet<>(aliases);
        if (displayName != null && !displayName.isBlank()) {
            terms.add(displayName);
        }
        return Set.copyOf(terms);
    }

}

package com.ontos.module.session.service.retrieval;

import java.util.List;

/**
 * 概念歧义澄清载荷（M5 规格 T2.3 四要素）。
 *
 * <p>四要素契约（缺一不可，单测锁定）：
 * ① 候选 2–5 个；② 每个候选带资产 ID；③ 默认建议（取排序首位）；④ 可跳过标记
 * （跳过 = 用户不选择，进入能力发现/改写问法，不强制决策）。
 *
 * @param mention           触发澄清的原文提及片段
 * @param candidates        候选资产（2–5 个，已按得分排序）
 * @param defaultSuggestion 默认建议的资产 ID（候选首位；用户可一键确认）
 * @param skippable         是否可跳过（M5 固定 true：澄清永不强制，跳过走改写问法）
 */
public record ClarificationPayload(String mention, List<RetrievedCandidate> candidates,
                                   String defaultSuggestion, boolean skippable) {

    public ClarificationPayload {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    /** 四要素契约校验：候选数量 2–5、带资产 ID、默认建议在候选内。 */
    public boolean valid() {
        if (candidates.size() < 2 || candidates.size() > 5) {
            return false;
        }
        if (candidates.stream().anyMatch(candidate ->
                candidate.assetId() == null || candidate.assetId().isBlank())) {
            return false;
        }
        if (defaultSuggestion == null
                || candidates.stream().noneMatch(candidate -> candidate.assetId().equals(defaultSuggestion))) {
            return false;
        }
        return true;
    }

}

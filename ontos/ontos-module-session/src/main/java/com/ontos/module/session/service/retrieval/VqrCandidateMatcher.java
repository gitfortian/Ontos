package com.ontos.module.session.service.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ontos.module.ontology.core.ManifestPayload;
import com.ontos.module.ontology.core.PublishedManifest;
import org.springframework.stereotype.Component;

/**
 * VQR 候选匹配（v1.4 §7.5 第 7 步，M6/W3/T3.5）：话术与已发布 VQR 的 questionPattern
 * 做**确定性**模板匹配——{x} 槽位转捕获组，全串匹配后提取参数；命中产出
 * {@link RetrievalOutcome.VqrCandidate}（带已提取参数），编排提案引用 vqrId。
 *
 * <p>命中范围（场景是业务域切片，只收窄不放宽）：
 * <ul>
 *   <li>in_scenario：场景 defaultVqrIds ∪ coldStartSamples 中的已发布 VQR；</li>
 *   <li>free：全部已发布 VQR（§5.10：default 仅影响候选优先级，不构成额外授权）。</li>
 * </ul>
 * 排序：defaultVqrIds 命中优先，同优先级按 pattern 长度降序（更具体的模板先出）。
 * 匹配失败/无模板命中返回空——编排走既有路径，行为零变化。
 */
@Component
public class VqrCandidateMatcher {

    /** questionPattern 的 {param} 槽位。 */
    private static final Pattern SLOT = Pattern.compile("\\{([A-Za-z0-9_]+)}");

    /**
     * @param utterance         用户话术（trim 后全串匹配）
     * @param manifest          生效清单
     * @param currentScenarioId 当前场景（null = 自由问数）
     * @return 命中的 VQR 候选（已提取参数）；无命中为空
     */
    public List<RetrievalOutcome.VqrCandidate> match(String utterance, PublishedManifest manifest,
                                                     String currentScenarioId) {
        String text = utterance == null ? "" : utterance.strip();
        if (text.isEmpty() || manifest == null || manifest.getVqrs() == null) {
            return List.of();
        }
        boolean inScenario = currentScenarioId != null && !currentScenarioId.isBlank();
        // 场景引用集（in_scenario 时的命中白名单）
        java.util.Set<String> scenarioRefs = new java.util.LinkedHashSet<>();
        if (inScenario && manifest.getScenarios() != null) {
            for (ManifestPayload.ScenarioMeta scenario : manifest.getScenarios()) {
                if (currentScenarioId.equals(scenario.getId())) {
                    if (scenario.getDefaultVqrIds() != null) {
                        scenarioRefs.addAll(scenario.getDefaultVqrIds());
                    }
                    if (scenario.getColdStartSamples() != null) {
                        scenarioRefs.addAll(scenario.getColdStartSamples());
                    }
                }
            }
        }
        List<RetrievalOutcome.VqrCandidate> hits = new ArrayList<>();
        for (ManifestPayload.VqrMeta vqr : manifest.getVqrs()) {
            if (!"published".equals(vqr.getStatus())) {
                continue;
            }
            if (inScenario && !scenarioRefs.contains(vqr.getVqrId())) {
                continue;
            }
            Map<String, String> params = matchPattern(text, vqr.getQuestionPattern());
            if (params != null) {
                hits.add(new RetrievalOutcome.VqrCandidate(vqr.getVqrId(), vqr.getName(),
                        params, vqr.getQuestionPattern()));
            }
        }
        // defaultVqrIds 优先（场景内命中集与优先级同源）；同优先级按 pattern 长度降序
        hits.sort(Comparator
                .comparing((RetrievalOutcome.VqrCandidate candidate) ->
                        scenarioRefs.contains(candidate.vqrId()) ? 0 : 1)
                .thenComparing(candidate -> candidate.matchedPattern().length(),
                        Comparator.reverseOrder()));
        return List.copyOf(hits);
    }

    /** 模板匹配：{x} 槽位转捕获组全串匹配；命中返回参数表（保持槽位顺序），不命中返回 null。 */
    private Map<String, String> matchPattern(String text, String questionPattern) {
        if (questionPattern == null || questionPattern.isBlank()) {
            return null;
        }
        Matcher slots = SLOT.matcher(questionPattern);
        List<String> names = new ArrayList<>();
        StringBuilder regex = new StringBuilder("^");
        int last = 0;
        while (slots.find()) {
            regex.append(Pattern.quote(questionPattern.substring(last, slots.start())));
            regex.append("(.+?)");
            names.add(slots.group(1));
            last = slots.end();
        }
        regex.append(Pattern.quote(questionPattern.substring(last)));
        regex.append("$");
        Matcher matcher = Pattern.compile(regex.toString()).matcher(text);
        if (!matcher.matches()) {
            return null;
        }
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 0; i < names.size(); i++) {
            params.put(names.get(i), matcher.group(i + 1).strip());
        }
        return params;
    }
}

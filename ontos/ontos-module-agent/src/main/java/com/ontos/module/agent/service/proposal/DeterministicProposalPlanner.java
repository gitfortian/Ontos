package com.ontos.module.agent.service.proposal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.ontology.core.SemanticManifest;
import com.ontos.module.ontology.service.manifest.ManifestService;
import com.ontos.module.session.service.retrieval.RetrievalOutcome;
import com.ontos.module.session.service.retrieval.RetrievedCandidate;
import org.springframework.stereotype.Service;

/**
 * 确定性意图提案（M5/T3.1：{@code ontos.agent.enabled=false} 的降级路径）。
 *
 * <p>与 LLM 路径产出**同形状**的 Proposal（同一合同校验、同一完整性对账、同一终态装配），
 * 保证降级不降边界：模型缺席时系统仍能以纯确定性方式回答「点名了资产」的问题，
 * 其余走澄清 / 能力发现。产出声明 {@code degraded=true}（对外可见，规格 T3.1「降级并声明」）。
 *
 * <p>不做 NL 理解：只消费检索瀑布的机械命中（L1/L2 dslReady / 澄清 / 投影）——
 * 模糊候选不进确定性提案（与硬断言同口径）。
 */
@Service
public class DeterministicProposalPlanner {

    private final ManifestService manifestService;

    public DeterministicProposalPlanner(ManifestService manifestService) {
        this.manifestService = manifestService;
    }

    /** 从检索瀑布产出确定性提案（terminal ∈ query / clarification / discovery）。 */
    public Map<String, Object> plan(String utterance, RetrievalOutcome outcome,
                                    boolean clarificationConsumed) {
        return plan(utterance, outcome, clarificationConsumed, false);
    }

    /**
     * @param approximationConsumed 本轮已消费近似同意（T3.3）：用户已做出降级决策，
     *                              本轮新产生的歧义澄清不再问——强制走 query 组装
     *                              （近似槽位与提案槽位同词表；无指标命中时契约兜底拒答）
     */
    public Map<String, Object> plan(String utterance, RetrievalOutcome outcome,
                                    boolean clarificationConsumed, boolean approximationConsumed) {
        // 1. VQR 完整命中（T35-2，瀑布第 7 步产出）：questionPattern 全串匹配 + 参数提取成功
        //    = 用户话术整体落在一个人工审核的参数化模板上——模板已消歧（维度/口径在模板内定死），
        //    优先于资产级歧义澄清（与 T24-4「入口级语义优先」同一原则）；澄清存在时若模板未命中
        //    则维持澄清。命中后仍走 execute 全链重校验（§11.4），不是绕过校验的捷径。
        if (outcome.vqrCandidates() != null && !outcome.vqrCandidates().isEmpty()) {
            RetrievalOutcome.VqrCandidate hit = outcome.vqrCandidates().get(0);
            Map<String, Object> subIntent = new LinkedHashMap<>();
            subIntent.put("vqr_id", hit.vqrId());
            subIntent.put("params", new LinkedHashMap<>(hit.params()));
            subIntent.put("analysis_mode", "query");
            return proposal("query", List.of(subIntent), null, null, outcome);
        }

        // 2. 瀑布已给出澄清（同词多资产歧义 / 模糊候选）→ 原样透传四要素；
        //    近似同意轮与澄清裁决轮都跳过（用户已决策，本轮歧义不再问）——
        //    clarificationConsumed 与 approximationConsumed 同型：缺后者是历史缺陷
        //    （裁决过的歧义下一轮原样复现，用户点候选等于死循环）。
        if (!outcome.clarifications().isEmpty() && !approximationConsumed
                && !clarificationConsumed) {
            com.ontos.module.session.service.retrieval.ClarificationPayload payload =
                    outcome.clarifications().get(0);
            Map<String, Object> clarification = new LinkedHashMap<>();
            clarification.put("ambiguity", payload.mention());
            clarification.put("candidates", payload.candidates().stream()
                    .map(candidate -> candidateMap(candidate)).toList());
            clarification.put("default_suggestion", payload.defaultSuggestion());
            clarification.put("degraded_behavior", "skip_and_discover");
            return proposal("clarification", null, clarification, null, outcome);
        }

        // 2. 本轮精确命中指标 → query；澄清已裁决但无指标命中 → 落到下方自然分支
        //    （引导「要哪个指标」/ 能力发现）。历史缺陷：clarificationConsumed 无指标时
        //    强制产出 metric_id=null 的 query，被 DSL 校验以 500 拒绝——裁决后无指标
        //    应该是引导而非异常，且引导候选是指标列表，与上一轮歧义不同，不会死循环。
        RetrievedCandidate metric = outcome.dslReady().stream()
                .filter(candidate -> "metric".equals(candidate.assetType()))
                .findFirst().orElse(null);
        if (metric != null) {
            // E7/bool-08 修复：ruleId 型布尔指标只允许 LOOKUP / LOOKUP_BATCH，
            // 不得落入普通 QUERY 分支（历史缺陷会把「是不是高价值客户」查成
            // metric.high_value_customer / QUERY，被资产模式闸门拒绝）。
            if (isRuleBooleanMetric(metric.assetId())) {
                return booleanLookupProposal(utterance, metric.assetId(), outcome);
            }
            Map<String, Object> subIntent = new LinkedHashMap<>();
            subIntent.put("metric_id", metric.assetId());
            subIntent.put("analysis_mode", "query");
            subIntent.put("dimensions", outcome.dslReady().stream()
                    .filter(candidate -> "dimension".equals(candidate.assetType()))
                    .map(RetrievedCandidate::assetId).toList());
            subIntent.put("filters", List.of());
            subIntent.put("time_expression_raw", null);
            return proposal("query", List.of(subIntent), null, null, outcome);
        }
        // clarificationConsumed 且无指标命中：继续向下走 2.5 / 3 / 4 段（引导或发现），
        // 不再回到同一处歧义澄清，也不产出空 DSL——「已裁决」只豁免歧义澄清这一件事。

        // 2.5 命中信息项（property）而无指标 → LOOKUP 模式（M3 语义：身份投影 + 属性回显）；
        // entity 按命名约定反查（property.<concept>.<name> → concept.<concept>）
        RetrievedCandidate property = outcome.dslReady().stream()
                .filter(candidate -> "property".equals(candidate.assetType()))
                .findFirst().orElse(null);
        if (property != null) {
            // 身份值（确定性提取）：utterance 中的标识形如 c-1001；缺失则转澄清（不猜身份）
            java.util.regex.Matcher identityMatcher = java.util.regex.Pattern
                    .compile("[a-zA-Z]+-\\d+").matcher(utterance == null ? "" : utterance);
            String identityValue = identityMatcher.find() ? identityMatcher.group() : null;
            if (identityValue == null) {
                Map<String, Object> clarification = new LinkedHashMap<>();
                clarification.put("ambiguity", utterance);
                clarification.put("candidates", List.of());
                clarification.put("default_suggestion", null);
                clarification.put("degraded_behavior", "ask_identity");
                return proposal("clarification", null, clarification, null, outcome);
            }
            Map<String, Object> subIntent = new LinkedHashMap<>();
            subIntent.put("metric_id", null);
            subIntent.put("analysis_mode", "lookup");
            // entity 不进提案：concept 由编排层从 property.conceptId 反查（服务端推衍，
            // 不参与 LLM 输出对账——确定性提案的推衍字段与模型输出字段分离）
            subIntent.put("attributes", outcome.dslReady().stream()
                    .filter(candidate -> "property".equals(candidate.assetType()))
                    .map(RetrievedCandidate::assetId).toList());
            subIntent.put("identity_value", identityValue);
            subIntent.put("filters", List.of());
            return proposal("query", List.of(subIntent), null, null, outcome);
        }

        // 3. 命中了维度/实体但没有指标 → 泛化问法，引导澄清（候选=可用指标）
        if (!outcome.dslReady().isEmpty() || !outcome.prevTurn().isEmpty()) {
            Map<String, Object> clarification = new LinkedHashMap<>();
            clarification.put("ambiguity", utterance);
            clarification.put("candidates", List.of()); // 服务端终态装配时补 discovery 候选
            clarification.put("default_suggestion", null);
            clarification.put("degraded_behavior", "list_metrics");
            return proposal("clarification", null, clarification, null, outcome);
        }

        // 4. 完全无命中 → 能力发现（合同要求 discovery 三键对象同时给出）
        Map<String, Object> discovery = new LinkedHashMap<>();
        discovery.put("metrics", List.of());
        discovery.put("dimensions", List.of());
        discovery.put("analysis_modes", List.of());
        Map<String, Object> proposal = proposal("discovery", null, null, true, outcome);
        proposal.put("discovery", discovery);
        return proposal;
    }

    private Map<String, Object> candidateMap(RetrievedCandidate candidate) {
        Map<String, Object> box = new LinkedHashMap<>();
        box.put("asset_id", candidate.assetId());
        box.put("asset_type", candidate.assetType());
        return box;
    }

    /** 是否为 ruleId 型布尔指标；清单缺失时按普通指标处理，不猜测。 */
    private boolean isRuleBooleanMetric(String metricId) {
        PublishedManifest manifest = manifestService.getActiveManifestOrNull();
        if (manifest == null || manifest.getSemantic() == null || manifest.getSemantic().getMetrics() == null) {
            return false;
        }
        return manifest.getSemantic().getMetrics().stream()
                .filter(metric -> metric.getId().equals(metricId))
                .findFirst()
                .map(metric -> metric.getRuleId() != null && !metric.getRuleId().isBlank())
                .orElse(false);
    }

    /**
     * 布尔判定走实体直查：identity 从话术确定性提取，attributes 只取本轮 dslReady 的 property；
     * 身份缺失转澄清，不猜 c-xxxx；属性缺失仍表达 LOOKUP，由后续校验 fail-closed。
     */
    private Map<String, Object> booleanLookupProposal(String utterance, String booleanMetricId,
                                                      RetrievalOutcome outcome) {
        java.util.regex.Matcher identityMatcher = java.util.regex.Pattern
                .compile("[a-zA-Z]+-\\d+").matcher(utterance == null ? "" : utterance);
        String identityValue = identityMatcher.find() ? identityMatcher.group() : null;
        if (identityValue == null) {
            Map<String, Object> clarification = new LinkedHashMap<>();
            clarification.put("ambiguity", utterance);
            clarification.put("candidates", List.of());
            clarification.put("default_suggestion", null);
            clarification.put("degraded_behavior", "ask_identity");
            return proposal("clarification", null, clarification, null, outcome);
        }

        Map<String, Object> subIntent = new LinkedHashMap<>();
        subIntent.put("metric_id", null);
        subIntent.put("analysis_mode", "lookup");
        subIntent.put("boolean_metric_id", booleanMetricId);
        subIntent.put("attributes", outcome.dslReady().stream()
                .filter(candidate -> "property".equals(candidate.assetType()))
                .map(RetrievedCandidate::assetId)
                .toList());
        subIntent.put("identity_value", identityValue);
        subIntent.put("filters", List.of());
        return proposal("query", List.of(subIntent), null, null, outcome);
    }

    private Map<String, Object> proposal(String terminal, List<Map<String, Object>> subIntents,
                                         Map<String, Object> clarification, Boolean needsDiscovery,
                                         RetrievalOutcome outcome) {
        Map<String, Object> proposal = new LinkedHashMap<>();
        proposal.put("terminal", terminal);
        proposal.put("sub_intents", subIntents == null ? List.of() : subIntents);
        proposal.put("needs_discovery", Boolean.TRUE.equals(needsDiscovery));
        proposal.put("clarification", clarification);
        proposal.put("out_of_capability", false);
        proposal.put("degraded", true);
        return proposal;
    }
}

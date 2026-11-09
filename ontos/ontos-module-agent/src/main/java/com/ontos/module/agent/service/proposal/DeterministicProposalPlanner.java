package com.ontos.module.agent.service.proposal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /** 从检索瀑布产出确定性提案（terminal ∈ query / clarification / discovery）。 */
    public Map<String, Object> plan(String utterance, RetrievalOutcome outcome,
                                    boolean clarificationConsumed) {
        // 1. 瀑布已给出澄清（同词多资产歧义 / 模糊候选）→ 原样透传四要素
        if (!outcome.clarifications().isEmpty()) {
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

        // 2. 消费过澄清选择或本轮精确命中指标 → query
        RetrievedCandidate metric = outcome.dslReady().stream()
                .filter(candidate -> "metric".equals(candidate.assetType()))
                .findFirst().orElse(null);
        if (clarificationConsumed || metric != null) {
            Map<String, Object> subIntent = new LinkedHashMap<>();
            subIntent.put("metric_id", metric == null ? null : metric.assetId());
            subIntent.put("analysis_mode", "query");
            subIntent.put("dimensions", outcome.dslReady().stream()
                    .filter(candidate -> "dimension".equals(candidate.assetType()))
                    .map(RetrievedCandidate::assetId).toList());
            subIntent.put("filters", List.of());
            subIntent.put("time_expression_raw", null);
            return proposal("query", List.of(subIntent), null, null, outcome);
        }

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

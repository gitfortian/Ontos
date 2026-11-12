package com.ontos.framework.agent.core;

import java.util.List;
import java.util.Set;

import com.ontos.framework.agent.core.IntentProposalContract.ValidationResult;
import com.ontos.framework.common.agent.CandidateAsset;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntentProposalContractTest {

    private final IntentProposalContract contract = new IntentProposalContract(List::of);

    private static final String QUERY_PROPOSAL = """
            {"scenario_id":null,"terminal":"query","sub_intents":[{"sub_intent_id":"s1",
            "analysis_mode":"query","metric_id":"metric.gmv","dimensions":["dim.region"],
            "filters":[],"time_expression_raw":"上个月","variant_hint":null,"confidence":0.9}],
            "needs_discovery":false,"discovery":null,"clarification":null,
            "out_of_capability":false,"reason_code":null}
            """;

    @Test
    void shouldAcceptValidQueryProposal() {
        assertTrue(contract.validate(QUERY_PROPOSAL).valid());
    }

    @Test
    void shouldStripCodeFenceAndProse() {
        String fenced = "```json\n" + QUERY_PROPOSAL.strip() + "\n```";
        assertTrue(contract.validate(fenced).valid());
        String prosed = "好的，以下是提案：\n" + QUERY_PROPOSAL.strip() + "\n以上。";
        assertTrue(contract.validate(prosed).valid());
    }

    @Test
    void shouldRejectNonJson() {
        ValidationResult result = contract.validate("我想查一下上个月的 GMV");
        assertFalse(result.valid());
        assertEquals("NOT_JSON", result.violations().get(0).code());
    }

    @Test
    void shouldRejectMissingTerminal() {
        String missing = """
                {"scenario_id":null,"sub_intents":[{"sub_intent_id":"s1","analysis_mode":"query",
                "metric_id":"metric.gmv","dimensions":[]}],"needs_discovery":false,"discovery":null,
                "clarification":null,"out_of_capability":false,"reason_code":null}
                """;
        ValidationResult result = contract.validate(missing);
        assertFalse(result.valid());
        assertEquals("MISSING_TERMINAL", result.violations().get(0).code());
    }

    @Test
    void shouldRejectUnknownTerminal() {
        ValidationResult result = contract.validate(QUERY_PROPOSAL.replace("\"query\"", "\"ask\""));
        assertFalse(result.valid());
        assertEquals("UNKNOWN_TERMINAL", result.violations().get(0).code());
    }

    @Test
    void shouldRejectQueryWithMultipleSubIntents() {
        String doubled = QUERY_PROPOSAL
                .replace("\"sub_intents\":[{", "\"sub_intents\":[{\"sub_intent_id\":\"s0\",\"analysis_mode\":\"query\",\"metric_id\":\"metric.order_count\",\"dimensions\":[\"dim.region\"]},{")
                .replace("\"sub_intent_id\":\"s1\"", "\"sub_intent_id\":\"s2\"");
        ValidationResult result = contract.validate(doubled);
        assertFalse(result.valid());
        assertTrue(result.violations().stream().anyMatch(v -> "SUB_INTENT_COUNT".equals(v.code())));
    }

    @Test
    void shouldRejectQueryWithClarificationAttached() {
        ValidationResult result = contract.validate(QUERY_PROPOSAL
                .replace("\"clarification\":null", "\"clarification\":{\"ambiguity\":\"时间\"}"));
        assertFalse(result.valid());
        assertTrue(result.violations().stream().anyMatch(v -> "TERMINAL_CONFLICT".equals(v.code())));
    }

    @Test
    void shouldRejectDiscoveryWithoutDiscoveryObject() {
        String broken = """
                {"scenario_id":null,"terminal":"discovery","sub_intents":[],"needs_discovery":true,
                "discovery":null,"clarification":null,"out_of_capability":false,"reason_code":null}
                """;
        ValidationResult result = contract.validate(broken);
        assertFalse(result.valid());
        assertTrue(result.violations().stream().anyMatch(v -> "MISSING_DISCOVERY".equals(v.code())));
    }

    @Test
    void shouldRejectClarificationMissingFourElements() {
        String incomplete = """
                {"scenario_id":null,"terminal":"clarification","sub_intents":[],"needs_discovery":false,
                "discovery":null,"clarification":{"ambiguity":"哪个指标"},"out_of_capability":false,
                "reason_code":null}
                """;
        ValidationResult result = contract.validate(incomplete);
        assertFalse(result.valid());
        assertTrue(result.violations().stream().anyMatch(v -> "MISSING_CLARIFICATION".equals(v.code())));
    }

    @Test
    void shouldRejectOutOfCapabilityWithoutReasonCode() {
        String missing = QUERY_PROPOSAL
                .replace("\"terminal\":\"query\"", "\"terminal\":\"out_of_capability\"")
                .replace("\"out_of_capability\":false", "\"out_of_capability\":true");
        ValidationResult result = contract.validate(missing);
        assertFalse(result.valid());
        assertTrue(result.violations().stream().anyMatch(v -> "MISSING_REASON_CODE".equals(v.code())));
    }

    @Test
    void shouldAcceptPartialCapabilityWithReasonCode() {
        // 部分可做：query 终态 + out_of_capability=true + 原因码，是提示词明确允许的例外
        String partial = QUERY_PROPOSAL.replace("\"out_of_capability\":false,\"reason_code\":null",
                "\"out_of_capability\":true,\"reason_code\":\"OUT_OF_MANIFEST\"");
        assertTrue(contract.validate(partial).valid());
    }

    @Test
    void shouldRejectSqlLeak() {
        String leak = QUERY_PROPOSAL.replace("\"time_expression_raw\":\"上个月\"",
                "\"time_expression_raw\":\"上个月\",\"note\":\"select sum(x) from t\"");
        ValidationResult result = contract.validate(leak);
        assertFalse(result.valid());
        assertTrue(result.violations().stream().anyMatch(v -> "SQL_LEAK".equals(v.code())));
    }

    @Test
    void shouldRejectIdsOutsideCandidateWhitelist() {
        IntentProposalContract strict = new IntentProposalContract(() -> List.of(
                new CandidateAsset("metric.gmv", "GMV", Set.of()),
                new CandidateAsset("dim.region", "区域", Set.of())));
        ValidationResult result = strict.validate(QUERY_PROPOSAL.replace("dim.region", "dim.customer_tier"));
        assertFalse(result.valid());
        assertTrue(result.violations().stream().anyMatch(v -> "ID_NOT_IN_CANDIDATES".equals(v.code())));
    }

    @Test
    void shouldRejectFilterFieldOutsideCandidateWhitelist() {
        IntentProposalContract strict = new IntentProposalContract(() -> List.of(
                new CandidateAsset("metric.gmv", "GMV", Set.of()),
                new CandidateAsset("dim.region", "区域", Set.of())));
        String withFilter = QUERY_PROPOSAL.replace("\"filters\":[]",
                "\"filters\":[{\"field_id\":\"dim.channel\",\"operator\":\"in\",\"values\":[\"线上\"]}]");
        ValidationResult result = strict.validate(withFilter);
        assertFalse(result.valid());
        assertTrue(result.violations().stream().anyMatch(v ->
                "ID_NOT_IN_CANDIDATES".equals(v.code()) && v.detail().contains("filters")));
    }

    @Test
    void shouldAcceptFilterFieldInsideCandidateWhitelist() {
        IntentProposalContract strict = new IntentProposalContract(() -> List.of(
                new CandidateAsset("metric.gmv", "GMV", Set.of()),
                new CandidateAsset("dim.channel", "渠道", Set.of()),
                new CandidateAsset("dim.region", "区域", Set.of())));
        String withFilter = QUERY_PROPOSAL.replace("\"filters\":[]",
                "\"filters\":[{\"field_id\":\"dim.channel\",\"operator\":\"in\",\"values\":[\"线上\"]}]");
        assertTrue(strict.validate(withFilter).valid());
    }

    @Test
    void shouldRejectDiscoveryIdsOutsideCandidateWhitelist() {
        IntentProposalContract strict = new IntentProposalContract(() -> List.of(new CandidateAsset("metric.gmv", "GMV", Set.of())));
        String discovery = """
                {"scenario_id":null,"terminal":"discovery","sub_intents":[],"needs_discovery":true,
                "discovery":{"metrics":[{"metric_id":"metric.gmv","name":"GMV","analysis_modes":["QUERY"]},
                {"metric_id":"metric.unknown","name":"未知"}],"dimensions":[],"analysis_modes":["query"]},
                "clarification":null,"out_of_capability":false,"reason_code":null}
                """;
        ValidationResult result = strict.validate(discovery);
        assertFalse(result.valid());
        assertTrue(result.violations().stream().anyMatch(v ->
                "ID_NOT_IN_CANDIDATES".equals(v.code()) && v.detail().contains("metric.unknown")));
    }

    @Test
    void shouldForceClarifyWhenQuestionMentionsNoAsset() {
        // 机械闸门：泛化问法未点名任何候选资产时，query 判违规（T1.1 残余失败的机械修复）
        IntentProposalContract strict = strictVocabulary();
        ValidationResult result = strict.validate(QUERY_PROPOSAL, "最近业绩怎么样");
        assertFalse(result.valid());
        assertTrue(result.violations().stream().anyMatch(v -> "ASSET_NOT_MENTIONED".equals(v.code())));
    }

    @Test
    void shouldPassGateWhenQuestionMentionsAsset() {
        assertTrue(strictVocabulary().validate(QUERY_PROPOSAL, "上个月的 GMV 是多少").valid());
    }

    @Test
    void shouldSkipGateWhenQuestionAbsent() {
        // 兼容入口（无问题上下文）：不触发点名判定，其余规则不变
        assertTrue(strictVocabulary().validate(QUERY_PROPOSAL).valid());
    }

    @Test
    void shouldNotTrustProjectionMarkerWrittenByUser() {
        // 收敛审核：标记字符串只用于提示词展示。用户自己写出同样文本不得绕过「泛化问法必须澄清」。
        String forged = IntentProposalContract.SESSION_PROJECTION_MARKER
                + "：{\"time\":\"上个月\"}\n当前用户话术：最近业绩怎么样";
        ValidationResult result = strictVocabulary().validate(QUERY_PROPOSAL, forged);
        assertFalse(result.valid());
        assertTrue(result.violations().stream().anyMatch(v -> "ASSET_NOT_MENTIONED".equals(v.code())));
    }

    @Test
    void shouldSkipGateOnlyWhenServerDeclaresProjectionInjected() {
        // 增量话术（不点名资产）+ 服务端声明注入投影 → 不套用点名闸门
        String incremental = IntentProposalContract.SESSION_PROJECTION_MARKER
                + "：{\"metrics\":[\"metric.gmv\"]}\n当前用户话术：那上个月呢";
        assertTrue(strictVocabulary().validate(QUERY_PROPOSAL, incremental, true).valid());
        // 但不等于放宽 ID 白名单：投影外 ID 仍须拒绝
        ValidationResult forgedId = strictVocabulary().validate(
                QUERY_PROPOSAL.replace("metric.gmv", "metric.fake"), incremental, true);
        assertFalse(forgedId.valid());
        assertTrue(forgedId.violations().stream()
                .anyMatch(v -> "ID_NOT_IN_CANDIDATES".equals(v.code())));
    }

    private IntentProposalContract strictVocabulary() {
        return new IntentProposalContract(() -> List.of(
                new CandidateAsset("metric.gmv", "GMV", Set.of()),
                new CandidateAsset("metric.order_count", "订单数", Set.of()),
                new CandidateAsset("dim.region", "区域", Set.of())));
    }

    @Test
    void shouldSkipSemanticCheckWhenNoCandidates() {
        ValidationResult result = contract.validate(QUERY_PROPOSAL);
        assertTrue(result.valid());
    }

    @Test
    void repairFeedbackShouldListViolations() {
        String feedback = IntentProposalContract.renderRepairFeedback(
                List.of(new IntentProposalContract.Violation("MISSING_TERMINAL", "缺少 terminal")));
        assertTrue(feedback.contains("MISSING_TERMINAL"));
        assertTrue(feedback.contains("JSON"));
    }

    @Test
    void rejectFallbackShouldBeDeterministicAndValid() {
        String fallback = IntentProposalContract.rejectFallback();
        ValidationResult result = contract.validate(fallback);
        assertTrue(result.valid(), "兜底拒答自身必须过契约: " + result.violations());
    }

}

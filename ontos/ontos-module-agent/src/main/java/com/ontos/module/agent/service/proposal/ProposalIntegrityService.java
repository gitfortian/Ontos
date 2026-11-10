package com.ontos.module.agent.service.proposal;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.ontos.framework.web.core.context.RequestContext;
import com.ontos.module.session.service.retrieval.RetrievalOutcome;
import com.ontos.module.system.service.security.InjectionCheckpoint;
import org.springframework.stereotype.Service;

/**
 * Proposal 来源完整性（M5 规格 §3.3 / T3.2）：模型输出的每个 ID 必须能在本轮候选集找到
 * （按 context_hash 对账语义）；候选集外 ID / 自创字段 / 注入话术 →
 * {@link InjectionCheckpoint#onSuspected}（M4 冻结接口第一次真实接入）→ 澄清或拒答 + 完整审计。
 *
 * <p>这是合同校验（{@code IntentProposalContract}，middleware 层）之后的**第二轮独立对账**：
 * 合同管输出形状，本服务管「本轮上下文的真实性」——候选集随租户/轮次变化，只有编排层能对账。
 */

@Service
public class ProposalIntegrityService {

    /** LLM 不允许输出的字段（规划器/澄清/变体选择都是服务端职责，出现即嫌疑）。 */
    private static final Set<String> FORBIDDEN_FIELDS = Set.of(
            "scenario_id", "skill_id", "rule_id", "variant_id", "variantId",
            "sql", "table", "column", "join");

    /**
     * 注入话术启发（PROMPT_TEXT）：命中即上报告警，不阻断常规问法。
     *
     * <p>T51-3（P1，test 集 v2 注入族真服务实测发现）：原启发仅覆盖
     * 「忽略以上规则 / 你是管理员 / system prompt」三种形态，中文注入变体
     * （忽略<b>之前的</b>指令——「的」字断开、<b>我是</b>管理员——主语替换、
     * 你现在是 <b>DBA</b>、数据库<b>密码</b>、<b>union</b>/select 等物理 SQL 关键词、
     * 服务器<b>绝对路径</b>、<b>tenant_id</b> 租户篡改）全部漏检，
     * 10 道注入题仅 1 题拒答。扩充为逐族关键词（fail-closed：宁拒不漏；
     * 正常分析问法不含这些词，误伤面由冒烟 20 题回归兜底）。
     */
    private static final Pattern PROMPT_INJECTION = Pattern.compile(
            "(?i)("
                    + "忽略(以上|之前|所有)?(的)?(所有)?(规则|指令|提示|限制|约束)"
                    + "|无视(以上|所有)?(的)?(规则|指令|提示)"
                    + "|(你|我)?(现在)?是\\s*(管理员|admin|系统|dba|开发者|运维|root|超级管理员)"
                    + "|扮演\\s*(系统|管理员|dba)"
                    + "|system\\s*prompt|developer\\s*mode|提示词"
                    + "|越权|绕过(权限|校验|审批)"
                    + "|密码|password|credential|凭据"
                    + "|绝对路径|服务器上的路径|文件路径|安装路径|配置文件"
                    + "|\\b(select|insert|update|delete|drop|truncate|alter|union)\\b"
                    + "|tenant_id|(别的|其他|其它)租户"
                    + ")");

    private final InjectionCheckpoint injectionCheckpoint;

    public ProposalIntegrityService(InjectionCheckpoint injectionCheckpoint) {
        this.injectionCheckpoint = injectionCheckpoint;
    }

    /**
     * 校验结论：{@code passed=true} 可继续组 DSL；false 时 {@code action} 指明编排动作
     * （CLARIFY = 用瀑布候选救一次，REJECT = 确定性拒答）。
     */
    public IntegrityResult check(Map<String, Object> proposal, RetrievalOutcome outcome,
                                 String userUtterance, RequestContext context) {
        // 1. 用户话术注入启发（先于 Proposal 检查：话术污染影响整轮）
        //    T51-2（P2，smoke-runner-v2 实测发现）：审计统一走 injectionCheckpoint（M4 冻结
        //    审计路径，AuditBackedInjectionCheckpoint 构造合法 JSON）——原冗余 audit() 直写
        //    把原始话术塞进 JSONB dsl 列，必然失败（invalid input syntax for type json），
        //    与 checkpoint 双写漂移且每次注入产生一条失败告警。
        if (userUtterance != null && PROMPT_INJECTION.matcher(userUtterance).find()) {
            String sample = truncate(userUtterance);
            injectionCheckpoint.onSuspected(context, InjectionCheckpoint.SOURCE_PROMPT_TEXT,
                    sample, "utterance matched injection heuristics");
            return new IntegrityResult(false, IntegrityAction.REJECT, "PROMPT_INJECTION_SUSPECTED");
        }

        // 2. LLM 禁输字段（自创 scenario/skill/rule/variant/物理列）：顶层与 sub_intents 逐项
        if (hasForbiddenField(proposal)
                || (proposal.get("sub_intents") instanceof List<?> subIntents
                        && subIntents.stream()
                                .filter(Map.class::isInstance)
                                .map(item -> (Map<?, ?>) item)
                                .anyMatch(this::hasForbiddenField))) {
            String detail = "forbidden field in proposal";
            injectionCheckpoint.onSuspected(context,
                    InjectionCheckpoint.SOURCE_CANDIDATE_OUT_OF_SET, detail, detail);
            return new IntegrityResult(false, IntegrityAction.REJECT, "FORBIDDEN_FIELD");
        }

        // 3. 候选集对账：sub_intents 的全部资产 ID 必须在本轮候选集（dslReady ∪ fuzzyOnly ∪ prevTurn）
        Set<String> inScope = candidateIds(outcome);
        Object raw = proposal.get("sub_intents");
        if (raw instanceof List<?> subIntents) {
            for (Object item : subIntents) {
                if (!(item instanceof Map<?, ?> box)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> subIntent = (Map<String, Object>) box;
                String violation = firstOutOfScopeId(subIntent, inScope);
                if (violation != null) {
                    String detail = "out-of-set id: " + violation;
                    injectionCheckpoint.onSuspected(context,
                            InjectionCheckpoint.SOURCE_CANDIDATE_OUT_OF_SET, truncate(violation), detail);
                    // 候选集外 ID：用瀑布候选救一次（澄清），不直接拒答
                    return new IntegrityResult(false, IntegrityAction.CLARIFY,
                            "ID_NOT_IN_SET:" + violation);
                }
            }
        }
        return new IntegrityResult(true, null, null);
    }

    private boolean hasForbiddenField(Map<?, ?> box) {
        return FORBIDDEN_FIELDS.stream().anyMatch(field ->
                box.containsKey(field) && !isNullField(box.get(field)));
    }

    private String firstOutOfScopeId(Map<String, Object> subIntent, Set<String> inScope) {
        // T3.5：vqr_id 先于 metric_id——VQR 命中提案引用模板而非散装资产
        String vqrId = stringOf(subIntent.get("vqr_id"));
        if (vqrId != null && !inScope.contains(vqrId)) {
            return vqrId;
        }
        String metricId = stringOf(subIntent.get("metric_id"));
        if (metricId != null && !inScope.contains(metricId)) {
            return metricId;
        }
        if (subIntent.get("dimensions") instanceof List<?> dimensions) {
            for (Object dimension : dimensions) {
                String id = dimension instanceof String s ? s : null;
                if (id != null && !inScope.contains(id)) {
                    return id;
                }
            }
        }
        if (subIntent.get("filters") instanceof List<?> filters) {
            for (Object filter : filters) {
                if (filter instanceof Map<?, ?> filterBox) {
                    String fieldId = stringOf(filterBox.get("field_id"));
                    if (fieldId != null && !inScope.contains(fieldId)) {
                        return fieldId;
                    }
                }
            }
        }
        String entityId = stringOf(subIntent.get("entity_id"));
        if (entityId != null && !inScope.contains(entityId)) {
            return entityId;
        }
        return null;
    }

    private Set<String> candidateIds(RetrievalOutcome outcome) {
        Set<String> ids = new java.util.HashSet<>();
        outcome.dslReady().forEach(candidate -> ids.add(candidate.assetId()));
        outcome.fuzzyOnly().forEach(candidate -> ids.add(candidate.assetId()));
        outcome.prevTurn().forEach(candidate -> ids.add(candidate.assetId()));
        // T3.5：VQR 命中候选进对账集——提案的 vqr_id 必须在本轮瀑布命中（§7.5 完整性）
        if (outcome.vqrCandidates() != null) {
            outcome.vqrCandidates().forEach(candidate -> ids.add(candidate.vqrId()));
        }
        return ids;
    }

    private static boolean isNullField(Object value) {
        return value == null || (value instanceof String s && s.isBlank());
    }

    private static String stringOf(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    private static String truncate(String text) {
        return text.length() <= 200 ? text : text.substring(0, 200);
    }

    /** 校验动作：CLARIFY = 瀑布候选救一次；REJECT = 确定性拒答。 */
    public enum IntegrityAction { CLARIFY, REJECT }

    public record IntegrityResult(boolean passed, IntegrityAction action, String reason) {
    }

}

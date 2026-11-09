package com.ontos.module.session.service.session;

import java.util.List;
import java.util.Map;

import com.ontos.framework.common.util.json.JsonUtils;
import com.ontos.framework.web.core.context.RequestContext;
import com.ontos.module.session.dal.dataobject.SessionStateDO;
import com.ontos.module.session.dal.mysql.SessionStateMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import static com.ontos.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.ontos.module.session.enums.ErrorCodeConstants.SESSION_CLARIFICATION_CHOICE_INVALID;
import static com.ontos.module.session.enums.ErrorCodeConstants.SESSION_TURN_CONFLICT;

/**
 * 会话权威状态机（M5/W1，规格 v1.2 §3.1）：
 *
 * <ul>
 *   <li>三条件（tenant|user|session）收敛，键隔离即跨会话不沿用——新会话一律空态起步；</li>
 *   <li>run 创建 = turn 占用（{@link #occupyTurn} 原子，v1.2 R2），冲突即 409 语义；</li>
 *   <li>投影（v1.1 F-1）：服务端状态 = 本轮已采纳 DSL 的实体投影（全量覆盖）+ 澄清选择追加；
 *       「覆盖/追加/清除/沿用」四规则由 LLM 在 prompt 会话上下文中完成，服务端只投影结果；</li>
 *   <li>澄清候选持久化（{@code pending_clarification}）与回传校验（v1.2 R5）：
 *       {@code candidateId} 不在本轮候选集合内即拒绝——伪造选择不能绕过澄清。</li>
 * </ul>
 *
 * <p>客户端请求 DTO 白名单（sessionId/文本/澄清选择）由编排层（W3）执行；
 * 任何上下文字段出现在请求体都会被 DTO 反序列化拒绝。
 */
@Slf4j
@Service
public class SessionStateService {

    private final SessionStateMapper mapper;
    private final SessionProperties properties;

    public SessionStateService(SessionStateMapper mapper, SessionProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
    }

    /** 读会话快照（不存在 = 空态；新会话一律空态起步）。 */
    public SessionStateDO read(String tenantId, String userId, String sessionId) {
        return mapper.select(tenantId, userId, sessionId).orElseGet(() -> {
            SessionStateDO empty = new SessionStateDO();
            empty.setTenantId(tenantId);
            empty.setUserId(userId);
            empty.setSessionId(sessionId);
            empty.setTurnIndex(0);
            empty.setResolvedEntities("{}");
            empty.setRecentTurns("[]");
            return empty;
        });
    }

    /** 开会话（幂等；无行则插入 turn_index=0）。 */
    public void ensure(String tenantId, String userId, String sessionId) {
        mapper.ensure(tenantId, userId, sessionId);
    }

    /**
     * run 创建 = turn 占用（原子）。占用成功返回分配的 turn_index（expectedTurn + 1）；
     * 冲突（并发同会话轮次 / 重复提交）抛 {@code SESSION_TURN_CONFLICT}（409 语义）。
     * 调用方须先 {@link #ensure} 或确认行已存在。
     */
    public int occupyTurn(String tenantId, String userId, String sessionId, int expectedTurn) {
        ensure(tenantId, userId, sessionId);
        if (!mapper.occupyTurn(tenantId, userId, sessionId, expectedTurn)) {
            throw exception(SESSION_TURN_CONFLICT, String.valueOf(expectedTurn));
        }
        return expectedTurn + 1;
    }

    /**
     * 实体投影（query/composite 终态执行成功后调用）：全量覆盖 resolved_entities、
     * recent_turns 追加并裁剪到深度上限、last_terminal 更新。
     * 澄清/拒答/discovery 终态**不投影**（无已采纳 DSL，状态保持）。
     */
    public void project(RequestContext context, SessionProjection projection, String terminal,
                        String requestId) {
        Map<String, Object> summary = new java.util.LinkedHashMap<>();
        summary.put("turn", read(context.tenantId(), context.userId(), context.sessionId())
                .getTurnIndex());
        summary.put("terminal", terminal);
        summary.put("requestId", requestId);
        summary.put("projection", projection.toJson());
        mapper.project(context.tenantId(), context.userId(), context.sessionId(),
                JsonUtils.toJsonString(projection.toJson()),
                JsonUtils.toJsonString(summary), terminal, properties.getRecentTurns());
    }

    /** 保存澄清候选（覆盖写——新一轮澄清替换旧的未答候选）。 */
    public void savePendingClarification(RequestContext context, PendingClarification clarification) {
        mapper.savePendingClarification(context.tenantId(), context.userId(),
                context.sessionId(), JsonUtils.toJsonString(clarification));
    }

    /**
     * 澄清选择消费（原子）：candidateId 必须属于当前待答候选集合——
     * 伪造选择返回 false 并抛 {@code SESSION_CLARIFICATION_CHOICE_INVALID}（v1.2 R5）；
     * 命中则清除 pending 并返回候选（编排层据此组装下一轮）。
     */
    public ClarificationCandidate consumeClarificationChoice(RequestContext context,
                                                             String candidateId) {
        if (candidateId == null || candidateId.isBlank()) {
            throw exception(SESSION_CLARIFICATION_CHOICE_INVALID, "<空>");
        }
        if (!mapper.consumeClarificationChoice(context.tenantId(), context.userId(),
                context.sessionId(), candidateId)) {
            throw exception(SESSION_CLARIFICATION_CHOICE_INVALID, candidateId);
        }
        log.info("[consumeClarificationChoice][澄清已选择: session={} candidate={}]",
                context.sessionId(), candidateId);
        return new ClarificationCandidate(candidateId, null, null);
    }

    /** 清理超期未答澄清（审批 TTL 同型口径：调用方在编排路径上按需调用，W3 接线）。 */
    public void clearPendingClarification(RequestContext context) {
        mapper.savePendingClarification(context.tenantId(), context.userId(),
                context.sessionId(), null);
    }

    /** 澄清候选（四要素的最小持久化形态）。 */
    public record PendingClarification(String ambiguity, List<ClarificationCandidate> candidates,
                                       String defaultSuggestion, String degradedBehavior) {
    }

    /** 候选项：candidateId 是回传校验的键。 */
    public record ClarificationCandidate(String candidateId, String label, String assetId) {
    }
}

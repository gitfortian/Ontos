package com.ontos.module.session.service.scenario;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.ontos.framework.common.util.json.JsonUtils;
import com.ontos.framework.web.core.context.RequestContext;
import com.ontos.module.ontology.core.ManifestPayload;
import com.ontos.module.session.core.scenario.ScenarioRouteDecision;
import com.ontos.module.session.core.scenario.ScenarioEntrySource;
import com.ontos.module.session.core.scenario.ScenarioRouteState;
import com.ontos.module.session.core.scenario.ScenarioRouter;
import com.ontos.module.session.dal.dataobject.SessionStateDO;
import com.ontos.module.session.dal.mysql.SessionStateMapper;
import com.ontos.module.session.service.session.SessionStateService;
import lombok.Data;
import org.springframework.stereotype.Service;

import static com.ontos.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.ontos.module.session.enums.ErrorCodeConstants.SCENARIO_MANUAL_UNAUTHORIZED;
import static com.ontos.module.session.enums.ErrorCodeConstants.SCENARIO_STATE_INVALID;
import static com.ontos.module.session.enums.ErrorCodeConstants.SESSION_CLARIFICATION_CHOICE_INVALID;

/**
 * Scenario 会话状态服务（M6/W2/T2.3）：路由决策 → 封闭迁移校验 → 持久化；
 * pending_confirm 的确认/退出复用 M5 澄清回传校验通道（ADR-0017 D3 / G-7）。
 *
 * <p>状态存 `session_state.scenario_state`（V1 JSONB 列）：
 * `{"state": "...", "scenarioId": "...", "entrySource": "...", "confirmByUser": ...}`。
 * pending_confirm 建立时状态与澄清候选**同一条 UPDATE** 写入（同生同灭）。
 * 后两列是决策期信息（V15 审计 `scenario_entry_source` / `scenario_confirm_by_user`），
 * 随状态同事务持久化，供 {@code ScenarioRouteGuard.lastDecision} 投影到审计；
 * `scenario_recommendation_confidence` 按 M6 规格决策 4 不产生数值，恒不落库。
 */
@Service
public class ScenarioSessionService {

    private final SessionStateService sessionStateService;
    private final SessionStateMapper mapper;

    public ScenarioSessionService(SessionStateService sessionStateService, SessionStateMapper mapper) {
        this.sessionStateService = sessionStateService;
        this.mapper = mapper;
    }

    /** 会话场景状态视图。 */
    public record ScenarioSessionView(ScenarioRouteState state, String scenarioId) {
    }

    /** 最近一次路由决策的审计投影（只含 V15 两列有真实来源的决策期信息）。 */
    public record ScenarioDecisionView(String entrySource, Boolean confirmByUser) {
    }

    /** 内部持久化形态。 */
    @Data
    public static class SessionScenarioState {
        private String state;
        private String scenarioId;
        /** 六值入口来源 code（{@link ScenarioEntrySource#code()}）。 */
        private String entrySource;
        /** pending_confirm 是否由用户确认。 */
        private Boolean confirmByUser;
    }

    /** 读会话场景状态（无行 / 空态 = free）。 */
    public ScenarioSessionView read(RequestContext context) {
        SessionStateDO row = sessionStateService.read(context.tenantId(), context.userId(),
                context.sessionId());
        return parse(row.getScenarioState());
    }

    /** 读最近一次路由决策（无行 / 旧格式状态 = 无决策信息，审计列留空，不猜）。 */
    public ScenarioDecisionView readDecision(RequestContext context) {
        SessionStateDO row = sessionStateService.read(context.tenantId(), context.userId(),
                context.sessionId());
        return parseDecision(row.getScenarioState());
    }

    /**
     * 应用路由决策：状态变化按封闭迁移表校验后持久化；
     * pending_confirm 建立时同时写入澄清候选（复用 M5 通道）。
     * REJECT_UNAUTHORIZED 在此转为业务异常（调用方无需再判）。
     */
    public ScenarioRouteDecision apply(RequestContext context, ScenarioRouter.RouteInput input) {
        ScenarioSessionView current = read(context);
        ScenarioRouter.RouteInput effective = withCurrentState(input, current);
        ScenarioRouteDecision decision = ScenarioRouter.decide(effective);
        if (decision.action() == ScenarioRouteDecision.Action.REJECT_UNAUTHORIZED) {
            throw exception(SCENARIO_MANUAL_UNAUTHORIZED, input.explicitScenarioId());
        }
        if (!decision.hasStateChange()) {
            return decision;
        }
        ScenarioRouteState from = current.state();
        if (!from.canTransitionTo(decision.targetState())) {
            throw exception(SCENARIO_STATE_INVALID, from.code(), decision.targetState().code());
        }
        persist(context, decision);
        return decision;
    }

    /**
     * pending_confirm 确认（G-7 / ADR-0017 D3）：candidateId 必须属于候选集合（M5 原子校验）；
     * 确认进入/切换 → in_scenario，确认退出 → free。目标场景再次校验已发布 + 已授权。
     */
    public ScenarioRouteDecision confirm(RequestContext context, String candidateId,
                                         Map<String, ManifestPayload.ScenarioMeta> scenariosById,
                                         java.util.Set<String> authorizedScenarioIds) {
        SessionStateDO row = sessionStateService.read(context.tenantId(), context.userId(),
                context.sessionId());
        ScenarioSessionView current = parse(row.getScenarioState());
        if (current.state() != ScenarioRouteState.PENDING_CONFIRM) {
            throw exception(SCENARIO_STATE_INVALID, current.state().code(), "confirm");
        }
        PendingClarificationView pending = parsePending(row.getPendingClarification());
        Optional<PendingClarificationView.CandidateView> chosen = pending.candidates.stream()
                .filter(candidate -> candidate.candidateId.equals(candidateId))
                .findFirst();
        if (chosen.isEmpty()) {
            throw exception(SESSION_CLARIFICATION_CHOICE_INVALID, candidateId);
        }
        PendingClarificationView.CandidateView candidate = chosen.get();
        ScenarioRouteState target;
        String scenarioId;
        if (ScenarioRouteDecision.EXIT_CANDIDATE_ID.equals(candidateId)) {
            target = ScenarioRouteState.FREE;
            scenarioId = null;
        } else {
            ManifestPayload.ScenarioMeta meta = scenariosById.get(candidate.assetId());
            if (meta == null || !"published".equals(meta.getStatus())
                    || !authorizedScenarioIds.contains(candidate.assetId())) {
                throw exception(SCENARIO_MANUAL_UNAUTHORIZED, candidate.assetId());
            }
            target = ScenarioRouteState.IN_SCENARIO;
            scenarioId = candidate.assetId();
        }
        if (!current.state().canTransitionTo(target)) {
            // 消费前判定（失败无副作用）；表内 PENDING_CONFIRM → IN_SCENARIO/FREE 恒合法，此处为防御
            throw exception(SCENARIO_STATE_INVALID, current.state().code(), target.code());
        }
        sessionStateService.ensure(context.tenantId(), context.userId(), context.sessionId());
        // T23-7 修复：候选校验 + 清候选 + 写状态原子完成（M5 同款 containment 判定）；
        // 并发下候选已被另一请求消费 → 返回 false 拒绝，不存在「候选已清状态未写」的中间态
        if (!mapper.consumeClarificationAndUpdateScenarioState(context.tenantId(), context.userId(),
                context.sessionId(), candidateId, JsonUtils.toJsonString(state(target, scenarioId,
                        target == ScenarioRouteState.IN_SCENARIO
                                ? ScenarioEntrySource.CONFIRMED_RECOMMENDATION : null,
                        target == ScenarioRouteState.IN_SCENARIO ? Boolean.TRUE : null)))) {
            throw exception(SESSION_CLARIFICATION_CHOICE_INVALID, candidateId);
        }
        return new ScenarioRouteDecision(
                target == ScenarioRouteState.IN_SCENARIO
                        ? ScenarioRouteDecision.Action.ENTER_SCENARIO
                        : ScenarioRouteDecision.Action.EXIT_SCENARIO,
                target, scenarioId,
                target == ScenarioRouteState.IN_SCENARIO
                        ? ScenarioEntrySource.CONFIRMED_RECOMMENDATION
                        : null,
                null, List.of());
    }

    /**
     * 近似降级同意请求（T3.3，ADR-0017 D3）：候选集合 = {接受近似(approx:<skillId>), 拒绝(exit)}，
     * 原子写入 pending_clarification（**scenario_state 不变**——近似不是场景迁移）；
     * 回传校验复用 M5 通道（伪造 candidateId 被拒）。返回候选 ID 供编排层透出。
     */
    public String requestApproximation(RequestContext context, String approxSkillId, String label) {
        sessionStateService.ensure(context.tenantId(), context.userId(), context.sessionId());
        String candidateId = APPROX_CANDIDATE_PREFIX + approxSkillId;
        SessionStateService.PendingClarification clarification =
                new SessionStateService.PendingClarification("mode_approximation",
                        List.of(new SessionStateService.ClarificationCandidate(
                                        candidateId, label, approxSkillId),
                                new SessionStateService.ClarificationCandidate(
                                        ScenarioRouteDecision.EXIT_CANDIDATE_ID, "拒绝近似，按无能力处理", null)),
                        candidateId, null);
        mapper.updateClarificationOnly(context.tenantId(), context.userId(), context.sessionId(),
                JsonUtils.toJsonString(clarification));
        return candidateId;
    }

    /** 近似同意候选的前缀（与场景确认候选 scenario: 前缀区分）。 */
    public static final String APPROX_CANDIDATE_PREFIX = "approx:";

    /** 显式退出场景（in_scenario → free）。 */
    public ScenarioRouteDecision exit(RequestContext context) {
        sessionStateService.ensure(context.tenantId(), context.userId(), context.sessionId());
        ScenarioSessionView current = read(context);
        if (current.state() != ScenarioRouteState.IN_SCENARIO) {
            throw exception(SCENARIO_STATE_INVALID, current.state().code(), "exit");
        }
        if (!current.state().canTransitionTo(ScenarioRouteState.FREE)) {
            throw exception(SCENARIO_STATE_INVALID, current.state().code(), "free");
        }
        mapper.updateScenarioState(context.tenantId(), context.userId(), context.sessionId(),
                JsonUtils.toJsonString(state(ScenarioRouteState.FREE, null, null, null)));
        return new ScenarioRouteDecision(ScenarioRouteDecision.Action.EXIT_SCENARIO,
                ScenarioRouteState.FREE, null, null, null, List.of());
    }

    private void persist(RequestContext context, ScenarioRouteDecision decision) {
        sessionStateService.ensure(context.tenantId(), context.userId(), context.sessionId());
        ScenarioEntrySource source = decision.source();
        // V15：pending_confirm 用户确认 / 冷启动点选为 true，其余（含自动进入、显式直连）为 false；
        // 置信度不落库（M6 规格决策 4 确定性口径，不产生数值）。
        Boolean confirmByUser = source == ScenarioEntrySource.CONFIRMED_RECOMMENDATION
                || source == ScenarioEntrySource.COLD_START_SELECTED ? Boolean.TRUE : Boolean.FALSE;
        String json = JsonUtils.toJsonString(
                state(decision.targetState(), decision.scenarioId(), source, confirmByUser));
        if (decision.candidates().isEmpty()) {
            mapper.updateScenarioState(context.tenantId(), context.userId(), context.sessionId(), json);
            return;
        }
        List<SessionStateService.ClarificationCandidate> candidates = decision.candidates().stream()
                .map(choice -> new SessionStateService.ClarificationCandidate(
                        choice.candidateId(), choice.label(), choice.assetId()))
                .toList();
        SessionStateService.PendingClarification clarification =
                new SessionStateService.PendingClarification("scenario_confirm", candidates, null, null);
        mapper.updateScenarioStateWithClarification(context.tenantId(), context.userId(),
                context.sessionId(), json, JsonUtils.toJsonString(clarification));
    }

    private ScenarioRouter.RouteInput withCurrentState(ScenarioRouter.RouteInput input, ScenarioSessionView current) {
        return new ScenarioRouter.RouteInput(current.state(), current.scenarioId(), input.explicitScenarioId(),
                input.explicitSource(), input.exitRequested(), input.outOfScope(),
                input.hintMatches(), input.scenariosById(), input.authorizedScenarioIds(),
                input.candidateScenarios());
    }

    private ScenarioSessionView parse(String json) {
        if (json == null || json.isBlank()) {
            return new ScenarioSessionView(ScenarioRouteState.FREE, null);
        }
        SessionScenarioState state = JsonUtils.parseObject(json, SessionScenarioState.class);
        if (state == null || state.getState() == null || state.getState().isBlank()) {
            return new ScenarioSessionView(ScenarioRouteState.FREE, null);
        }
        return new ScenarioSessionView(ScenarioRouteState.fromCode(state.getState()),
                state.getScenarioId());
    }

    private ScenarioDecisionView parseDecision(String json) {
        if (json == null || json.isBlank()) {
            return new ScenarioDecisionView(null, null);
        }
        SessionScenarioState state = JsonUtils.parseObject(json, SessionScenarioState.class);
        // 兼容 B0 之前写入的两字段旧状态：解析不报错，决策信息按「无」处理
        return state == null ? new ScenarioDecisionView(null, null)
                : new ScenarioDecisionView(state.getEntrySource(), state.getConfirmByUser());
    }

    private PendingClarificationView parsePending(String json) {
        if (json == null || json.isBlank()) {
            return new PendingClarificationView(null, List.of(), null, null);
        }
        SessionStateService.PendingClarification clarification =
                JsonUtils.parseObject(json, SessionStateService.PendingClarification.class);
        if (clarification == null) {
            return new PendingClarificationView(null, List.of(), null, null);
        }
        List<PendingClarificationView.CandidateView> candidates = new ArrayList<>();
        for (SessionStateService.ClarificationCandidate candidate : clarification.candidates()) {
            candidates.add(new PendingClarificationView.CandidateView(candidate.candidateId(),
                    candidate.label(), candidate.assetId()));
        }
        return new PendingClarificationView(clarification.ambiguity(), candidates,
                clarification.defaultSuggestion(), clarification.degradedBehavior());
    }

    private SessionScenarioState state(ScenarioRouteState state, String scenarioId,
                                       ScenarioEntrySource source, Boolean confirmByUser) {
        SessionScenarioState result = new SessionScenarioState();
        result.setState(state.code());
        result.setScenarioId(scenarioId);
        result.setEntrySource(source == null ? null : source.code());
        result.setConfirmByUser(confirmByUser);
        return result;
    }

    /** 澄清候选只读视图。 */
    public record PendingClarificationView(String ambiguity, List<CandidateView> candidates,
                                           String defaultSuggestion, String degradedBehavior) {
        public record CandidateView(String candidateId, String label, String assetId) {
        }
    }
}

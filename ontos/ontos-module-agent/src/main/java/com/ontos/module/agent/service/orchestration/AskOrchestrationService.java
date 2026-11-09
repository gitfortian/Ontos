package com.ontos.module.agent.service.orchestration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ontos.framework.agent.config.AgentProperties;
import com.ontos.framework.agent.core.IntentProposalContract;
import com.ontos.framework.agent.core.OntosAgentFactory;
import com.ontos.framework.common.util.json.JsonUtils;
import com.ontos.framework.web.core.context.RequestContext;
import com.ontos.module.agent.dal.dataobject.AskRunDO;
import com.ontos.module.agent.service.event.AskEventService;
import com.ontos.module.agent.service.proposal.DeterministicProposalPlanner;
import com.ontos.module.agent.service.proposal.ProposalIntegrityService;
import com.ontos.module.agent.service.run.AskRunStateService;
import com.ontos.module.ontology.service.manifest.ManifestService;
import com.ontos.module.query.core.dsl.QueryDsl;
import com.ontos.module.query.service.QueryService;
import com.ontos.module.session.service.retrieval.MentionRetrievalService;
import com.ontos.module.session.service.retrieval.RetrievalOutcome;
import com.ontos.module.session.service.retrieval.SessionCandidateContextProvider;
import com.ontos.module.session.service.session.SessionProjection;
import com.ontos.module.session.service.session.SessionStateService;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import static com.ontos.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.ontos.module.agent.enums.AskErrorCodeConstants.ASK_MODE_REJECTED;
import static com.ontos.module.agent.enums.AskErrorCodeConstants.ASK_PROPOSAL_REJECTED;

/**
 * ask 编排服务（M5/T3.1 主链）：run 创建 → 检索瀑布 → Proposal（LLM / 确定性降级）→
 * 合同校验 → 来源完整性对账 → QueryDsl **服务端组装** → execute 全链 → 结构化终态。
 *
 * <p>硬边界（M5 规格 §3.3）：
 * <ul>
 *   <li>LLM 唯一职责是产出意图提案；**查询执行是编排代码的内部步骤，对 LLM 不可见**
 *       （F-5 P0：不暴露 execute_query 工具、不组装参数）；</li>
 *   <li>故障即拒答：模型超时/异常/反复违规 → 确定性降级或 {@code out_of_capability}，不无限重试；</li>
 *   <li>编排无自有进程内结果缓存（M4 F-5 前置）；结果复用仅经 {@code QueryService} 缓存键。</li>
 * </ul>
 *
 * <p>候选集白名单只认 {@link SessionCandidateContextProvider}（授权裁剪版）——
 * 全量版 Provider（管理面/诊断）不得进入 LLM 词表，否则白名单对账失效。
 */
@Slf4j
@Service
public class AskOrchestrationService {

    /** 模型单次调用超时（毫秒）：故障即拒答，不悬挂 run。 */
    static final long MODEL_TIMEOUT_MILLIS = 60_000L;

    private final AskRunStateService runStateService;
    private final SessionStateService sessionStateService;
    private final MentionRetrievalService retrievalService;
    private final ProposalIntegrityService integrityService;
    private final DeterministicProposalPlanner deterministicPlanner;
    private final AskEventService eventService;
    private final QueryService queryService;
    private final AgentProperties agentProperties;
    private final ObjectProvider<OntosAgentFactory> agentFactoryProvider;
    private final SessionCandidateContextProvider candidateProvider;
    private final ManifestService manifestService;

    @Autowired
    public AskOrchestrationService(AskRunStateService runStateService,
                                   SessionStateService sessionStateService,
                                   MentionRetrievalService retrievalService,
                                   ProposalIntegrityService integrityService,
                                   DeterministicProposalPlanner deterministicPlanner,
                                   AskEventService eventService,
                                   QueryService queryService,
                                   AgentProperties agentProperties,
                                   ObjectProvider<OntosAgentFactory> agentFactoryProvider,
                                   SessionCandidateContextProvider candidateProvider,
                                   ManifestService manifestService) {
        this.runStateService = runStateService;
        this.sessionStateService = sessionStateService;
        this.retrievalService = retrievalService;
        this.integrityService = integrityService;
        this.deterministicPlanner = deterministicPlanner;
        this.eventService = eventService;
        this.queryService = queryService;
        this.agentProperties = agentProperties;
        this.agentFactoryProvider = agentFactoryProvider;
        this.candidateProvider = candidateProvider;
        this.manifestService = manifestService;
    }

    private IntentProposalContract contract() {
        return new IntentProposalContract(candidateProvider::candidateVocabulary);
    }

    /** 一轮对话：返回终态载荷（W3 同步 REST；HITL 内联轮询在 W4 完善）。 */
    public Map<String, Object> executeTurn(RequestContext context, String utterance,
                                           String clarificationChoice) {
        // run 创建 = turn 占用（乐观锁）：按会话当前 turn_index 占用（冲突即 409 由客户端重试），
        // 请求头 X-Ontos-Turn 是轮次声明，不做占用基准
        var state = sessionStateService.read(context.tenantId(), context.userId(),
                context.sessionId());
        int currentTurn = state == null || state.getTurnIndex() == null
                ? 0 : state.getTurnIndex();
        int occupiedTurn = sessionStateService.occupyTurn(context.tenantId(), context.userId(),
                context.sessionId(), currentTurn);
        AskRunDO run = runStateService.create(context, occupiedTurn);
        String runId = run.getRunId();
        boolean reachedRunning = false;
        try {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("utterance", utterance == null ? "" : utterance);
            if (clarificationChoice != null) {
                input.put("clarificationChoice", clarificationChoice);
            }
            // 身份字段进 input（PoC #10 服务端注入侧脱敏验证：AskEventService 落库前统一抹除）
            input.put("userId", context.userId());
            input.put("tenantId", context.tenantId());
            eventService.emit(runId, AskEventService.RUN_STARTED,
                    Map.of("threadId", context.sessionId(), "input", input));
            runStateService.transition(runId, AskRunDO.PENDING, AskRunDO.RUNNING);
            reachedRunning = true;

            Map<String, Object> terminal = doExecute(context, runId, utterance, clarificationChoice);

            runStateService.transition(runId, AskRunDO.RUNNING, AskRunDO.FINISHED);
            eventService.emit(runId, AskEventService.RUN_FINISHED,
                    Map.of("terminal", String.valueOf(terminal.get("terminal"))));
            return terminal;
        } catch (RuntimeException ex) {
            if (reachedRunning) {
                // 先判定后转移：只有 RUNNING 才落到 FAILED（终态规则由状态机服务强制）
                safeFail(runId);
                eventService.emit(runId, AskEventService.RUN_ERROR,
                        Map.of("message", String.valueOf(ex.getMessage())));
            }
            throw ex;
        }
    }

    private Map<String, Object> doExecute(RequestContext context, String runId,
                                          String utterance, String clarificationChoice) {
        // 1. 检索瀑布（授权裁剪内）；指代消解来源 = 会话投影（resolved_entities JSON）
        SessionProjection projection = readProjection(context);
        boolean consumed = false;
        if (clarificationChoice != null && !clarificationChoice.isBlank()) {
            sessionStateService.consumeClarificationChoice(context, clarificationChoice);
            consumed = true;
        }
        RetrievalOutcome outcome = retrievalService.resolve(utterance, context, projection);

        // 2. Proposal：LLM（enabled 且可用）或确定性降级
        Map<String, Object> proposal =
                proposeWithFallback(context, runId, utterance, outcome, consumed);

        // 3. 合同校验（确定性/LLM 输出同闸门）+ 来源完整性对账
        IntentProposalContract.ValidationResult validation =
                contract().validate(JsonUtils.toJsonString(proposal), utterance);
        if (!validation.valid()) {
            // 确定性输出也必须过合同；不过即编排缺陷，fail-closed 拒答
            log.warn("[doExecute][proposal 违反输出契约: {}]", validation.violations());
            return outOfCapability("PROPOSAL_CONTRACT_INVALID");
        }
        ProposalIntegrityService.IntegrityResult integrity =
                integrityService.check(proposal, outcome, utterance, context);
        if (!integrity.passed()) {
            if (integrity.action() == ProposalIntegrityService.IntegrityAction.REJECT) {
                return outOfCapability(integrity.reason());
            }
            // CLARIFY：候选集外 ID → 用瀑布澄清候选救一次
            if (!outcome.clarifications().isEmpty()) {
                return clarificationTerminal(outcome);
            }
            return outOfCapability(integrity.reason());
        }

        // 4. 终态分派
        String terminal = String.valueOf(proposal.get("terminal"));
        return switch (terminal) {
            case "query" -> executeQueryTerminal(context, runId, proposal);
            case "composite" -> executeCompositeTerminal(context, proposal);
            case "clarification" -> clarificationTerminal(outcome);
            case "discovery" -> discoveryTerminal(outcome);
            default -> outOfCapability("OUT_OF_SCOPE");
        };
    }

    /** LLM 提案（enabled 时）；模型故障降级确定性提案（对外声明 degraded）——不悬挂、不无限重试。 */
    private Map<String, Object> proposeWithFallback(RequestContext context, String runId,
                                                    String utterance, RetrievalOutcome outcome,
                                                    boolean clarificationConsumed) {
        if (agentProperties.isEnabled()) {
            OntosAgentFactory factory = agentFactoryProvider.getIfAvailable();
            if (factory != null) {
                try {
                    Map<String, Object> llmProposal = callModel(factory, utterance);
                    if (llmProposal != null) {
                        return llmProposal;
                    }
                } catch (RuntimeException ex) {
                    log.warn("[proposeWithFallback][模型故障，降级确定性提案: {}]", ex.toString());
                    eventService.emit(runId, AskEventService.TEXT_MESSAGE,
                            Map.of("delta", "（模型暂不可用，已切换确定性检索模式）"));
                }
            }
        }
        return deterministicPlanner.plan(utterance, outcome, clarificationConsumed);
    }

    /** 调用模型产出意图提案；修复反馈逐轮注入，超限返回契约兜底拒答（fail-closed）。 */
    private Map<String, Object> callModel(OntosAgentFactory factory, String utterance) {
        var agent = factory.create();
        List<IntentProposalContract.Violation> lastViolations = List.of();
        for (int attempt = 0; attempt <= agentProperties.getOutputMaxRepairs(); attempt++) {
            Msg prompt = lastViolations.isEmpty()
                    ? Msg.builder().role(MsgRole.USER).textContent(utterance).build()
                    : Msg.builder().role(MsgRole.USER)
                            .textContent(IntentProposalContract.renderRepairFeedback(lastViolations)
                                    + "\n\n用户问题：" + utterance)
                            .build();
            Msg reply = agent.call(prompt)
                    .block(java.time.Duration.ofMillis(MODEL_TIMEOUT_MILLIS));
            String output = textOf(reply);
            IntentProposalContract.ValidationResult result = contract().validate(output, utterance);
            if (result.valid()) {
                return result.parsed();
            }
            lastViolations = result.violations();
        }
        log.warn("[callModel][模型输出连续 {} 次未过契约，确定性拒答]",
                agentProperties.getOutputMaxRepairs() + 1);
        return JsonUtils.parseMap(IntentProposalContract.rejectFallback());
    }

    private static String textOf(Msg reply) {
        if (reply == null) {
            return "";
        }
        return reply.getContentBlocks(TextBlock.class).stream()
                .map(TextBlock::getText)
                .reduce("", (left, right) -> left + right);
    }

    // ---------- 终态装配（W3 初装；W4/T4.1 完善载荷契约与文案模板） ----------

    @SuppressWarnings("unchecked")
    private Map<String, Object> executeQueryTerminal(RequestContext context, String runId,
                                                     Map<String, Object> proposal) {
        Object raw = proposal.get("sub_intents");
        if (!(raw instanceof List<?> subIntents) || subIntents.isEmpty()
                || !(subIntents.get(0) instanceof Map<?, ?> box)) {
            return outOfCapability("PROPOSAL_CONTRACT_INVALID");
        }
        QueryDsl dsl = assembleDsl(context, (Map<String, Object>) box);

        // T3.4 第二级路由：Analysis Mode 从指标 allowed_modes 确定性过滤
        filterAnalysisMode(dsl);

        eventService.emit(runId, AskEventService.TOOL_CALL,
                Map.of("toolName", "execute_query", "argsScope", "server_assembled"));
        var result = queryService.execute(dsl);

        // 会话投影（F-1：每轮全量覆盖）
        sessionStateService.project(context, SessionProjection.of(dsl), "query", context.traceId());

        Map<String, Object> terminal = new LinkedHashMap<>();
        terminal.put("terminal", "query");
        terminal.put("requestId", context.traceId());
        terminal.put("rowCount", result == null ? 0 : result.getRowCount());
        terminal.put("rows", result == null ? List.of() : result.getRows());
        terminal.put("degraded", Boolean.TRUE.equals(proposal.get("degraded")));
        return terminal;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> executeCompositeTerminal(RequestContext context,
                                                         Map<String, Object> proposal) {
        QueryDsl dsl = new QueryDsl();
        dsl.setManifestId(activeManifestId(context));
        List<QueryDsl.SubIntent> children = new ArrayList<>();
        Object raw = proposal.get("sub_intents");
        if (raw instanceof List<?> items) {
            for (Object item : items) {
                if (item instanceof Map<?, ?> box) {
                    QueryDsl child = assembleDsl(context, (Map<String, Object>) box);
                    QueryDsl.SubIntent wrapper = new QueryDsl.SubIntent();
                    wrapper.setSubIntentId("sub-" + (children.size() + 1));
                    wrapper.setMetricId(child.getMetricId());
                    wrapper.setMode(child.getMode());
                    wrapper.setDimensions(child.getDimensions());
                    wrapper.setFilters(child.getFilters());
                    wrapper.setTimeExpressionRaw(child.getTimeExpressionRaw());
                    children.add(wrapper);
                }
            }
        }
        dsl.setSubIntents(children);
        // composite 透传 M2 复合通道（定档 ⑥）：不拆分编排，PoC #8 归 M6
        var outcome = queryService.executeComposite(dsl);
        Map<String, Object> terminal = new LinkedHashMap<>();
        terminal.put("terminal", "composite");
        terminal.put("requestId", context.traceId());
        terminal.put("degraded", Boolean.TRUE.equals(proposal.get("degraded")));
        terminal.put("subResults", outcome == null ? List.of() : outcome.results());
        return terminal;
    }

    private Map<String, Object> clarificationTerminal(RetrievalOutcome outcome) {
        var payload = outcome.clarifications().isEmpty() ? null : outcome.clarifications().get(0);
        Map<String, Object> terminal = new LinkedHashMap<>();
        terminal.put("terminal", "clarification");
        if (payload == null) {
            // 泛化问法：澄清候选由终态装配补 discovery 候选（W4/T4.1 完善）
            terminal.put("ambiguity", "未识别到具体指标");
            terminal.put("candidates", List.of());
            terminal.put("defaultSuggestion", null);
            terminal.put("skippable", true);
            return terminal;
        }
        terminal.put("ambiguity", payload.mention());
        terminal.put("candidates", payload.candidates().stream()
                .map(candidate -> Map.of("assetId", candidate.assetId(),
                        "assetType", candidate.assetType(), "matchedTerm", candidate.matchedTerm()))
                .toList());
        terminal.put("defaultSuggestion", payload.defaultSuggestion());
        terminal.put("skippable", payload.skippable());
        return terminal;
    }

    private Map<String, Object> discoveryTerminal(RetrievalOutcome outcome) {
        Map<String, Object> terminal = new LinkedHashMap<>();
        terminal.put("terminal", "discovery");
        // 授权 scope 内可用能力（已发布引用，不由 LLM 生成）
        terminal.put("metrics", outcome.dslReady().stream()
                .filter(candidate -> "metric".equals(candidate.assetType()))
                .map(CandidateView::of).toList());
        terminal.put("dimensions", outcome.dslReady().stream()
                .filter(candidate -> "dimension".equals(candidate.assetType()))
                .map(CandidateView::of).toList());
        return terminal;
    }

    /** 终态载荷不泄漏 session 内部 record 类型。 */
    private record CandidateView(String assetId, String assetType, String matchedTerm) {
        static CandidateView of(com.ontos.module.session.service.retrieval.RetrievedCandidate c) {
            return new CandidateView(c.assetId(), c.assetType(), c.matchedTerm());
        }
    }

    private Map<String, Object> outOfCapability(String reason) {
        Map<String, Object> terminal = new LinkedHashMap<>();
        terminal.put("terminal", "out_of_capability");
        terminal.put("reason", reason);
        return terminal;
    }

    // ---------- 服务端 DSL 组装与路由 ----------

    /** 服务端组装 QueryDsl（LLM 只提供语义槽位；物理映射/Join/时间窗由清单与编译器负责）。 */
    private QueryDsl assembleDsl(RequestContext context, Map<String, Object> subIntent) {
        QueryDsl dsl = new QueryDsl();
        dsl.setManifestId(activeManifestId(context));
        dsl.setMetricId(stringOf(subIntent.get("metric_id")));
        dsl.setMode(parseMode(stringOf(subIntent.get("analysis_mode"))));
        if (subIntent.get("dimensions") instanceof List<?> dimensions) {
            dimensions.forEach(dimension -> {
                if (dimension instanceof String s && !s.isBlank()) {
                    dsl.getDimensions().add(s);
                }
            });
        }
        if (subIntent.get("filters") instanceof List<?> filters) {
            for (Object filter : filters) {
                if (filter instanceof Map<?, ?> box) {
                    QueryDsl.Filter parsed = new QueryDsl.Filter();
                    parsed.setFieldId(stringOf(box.get("field_id")));
                    parsed.setOperator(parseOperator(stringOf(box.get("op"))));
                    if (box.get("values") instanceof List<?> values) {
                        values.forEach(value -> parsed.getValues().add(String.valueOf(value)));
                    }
                    dsl.getFilters().add(parsed);
                }
            }
        }
        dsl.setTimeExpressionRaw(stringOf(subIntent.get("time_expression_raw")));
        return dsl;
    }

    /** T3.4 第二级路由：analysis_mode 必须在指标 allowed_modes 内（确定性过滤，非模型判断）。 */
    private void filterAnalysisMode(QueryDsl dsl) {
        if (dsl.getMetricId() == null || dsl.getMode() == null
                || dsl.getMode() == QueryDsl.AnalysisMode.QUERY) {
            return;
        }
        List<String> allowedModes = allowedModesOf(dsl.getMetricId());
        if (allowedModes == null) {
            throw exception(ASK_MODE_REJECTED, dsl.getMode() + "（指标不可用）");
        }
        if (!allowedModes.contains(dsl.getMode().name())) {
            throw exception(ASK_MODE_REJECTED,
                    dsl.getMode() + "（指标 " + dsl.getMetricId() + " 仅支持 " + allowedModes + "）");
        }
    }

    /** ask 链路的清单来源：当前生效清单（编排层自动携带，不要求请求头 X-Ontos-Manifest）。 */
    private String activeManifestId(RequestContext context) {
        var manifest = manifestService.getActiveManifestOrNull();
        if (manifest != null) {
            return manifest.getManifestId();
        }
        return context.manifestId();
    }

    private List<String> allowedModesOf(String metricId) {
        var manifest = manifestService.getActiveManifestOrNull();
        if (manifest == null || manifest.getSemantic() == null) {
            return null;
        }
        return manifest.getSemantic().getMetrics().stream()
                .filter(metric -> metric.getId().equals(metricId))
                .findFirst()
                .map(metric -> metric.getAllowedModes())
                .orElse(null);
    }

    private static QueryDsl.AnalysisMode parseMode(String mode) {
        if (mode == null) {
            return QueryDsl.AnalysisMode.QUERY;
        }
        try {
            return QueryDsl.AnalysisMode.valueOf(mode.toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw exception(ASK_MODE_REJECTED, mode);
        }
    }

    private static QueryDsl.Operator parseOperator(String op) {
        if (op == null) {
            return QueryDsl.Operator.EQ;
        }
        return switch (op) {
            case "=", "eq", "EQ" -> QueryDsl.Operator.EQ;
            case "in", "IN" -> QueryDsl.Operator.IN;
            case "not_in", "NOT_IN" -> QueryDsl.Operator.NOT_IN;
            case ">=", "gte", "GTE" -> QueryDsl.Operator.GTE;
            case "<=", "lte", "LTE" -> QueryDsl.Operator.LTE;
            case "between", "BETWEEN" -> QueryDsl.Operator.BETWEEN;
            default -> throw exception(ASK_PROPOSAL_REJECTED, "未知过滤算子: " + op);
        };
    }

    private static String stringOf(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    /** 会话投影读取：resolved_entities JSON → SessionProjection（空/损坏一律按空投影，不猜）。 */
    private SessionProjection readProjection(RequestContext context) {
        var state = sessionStateService.read(context.tenantId(), context.userId(),
                context.sessionId());
        if (state == null || state.getResolvedEntities() == null
                || state.getResolvedEntities().isBlank()) {
            return new SessionProjection(List.of(), List.of(), List.of(), null, null);
        }
        try {
            Map<String, Object> json = JsonUtils.parseMap(state.getResolvedEntities());
            return new SessionProjection(
                    stringList(json.get("metrics")),
                    stringList(json.get("dimensions")),
                    stringList(json.get("properties")),
                    stringOrNull(json.get("entity")),
                    stringOrNull(json.get("time")));
        } catch (RuntimeException ex) {
            log.warn("[readProjection][投影解析失败，按空投影处理: {}]", ex.toString());
            return new SessionProjection(List.of(), List.of(), List.of(), null, null);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(item -> item instanceof String s && !s.isBlank())
                .map(item -> (String) item).toList();
    }

    private static String stringOrNull(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    private void safeFail(String runId) {
        try {
            AskRunDO run = runStateService.require(runId, runContext());
            if (AskRunDO.RUNNING.equals(run.getStatus())) {
                runStateService.transition(runId, AskRunDO.RUNNING, AskRunDO.FAILED);
            }
        } catch (RuntimeException ignored) {
            // 收尾失败不掩盖原始异常
        }
    }

    private RequestContext runContext() {
        return com.ontos.framework.web.core.context.RequestContextHolder.require();
    }

}

package com.ontos.module.agent.service.orchestration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ontos.framework.agent.config.AgentProperties;
import com.ontos.framework.common.exception.ServiceException;
import com.ontos.module.agent.config.AskProperties;
import com.ontos.framework.agent.core.IntentProposalContract;
import com.ontos.framework.agent.core.OntosAgentFactory;
import com.ontos.framework.agent.trace.LlmCallOrigin;
import com.ontos.framework.common.util.json.JsonUtils;
import com.ontos.framework.web.core.context.RequestContext;
import com.ontos.module.agent.dal.dataobject.AskRunDO;
import com.ontos.module.agent.service.event.AskEventService;
import com.ontos.module.agent.service.event.AskStep;
import com.ontos.module.agent.service.proposal.DeterministicProposalPlanner;
import com.ontos.module.agent.service.proposal.ProposalIntegrityService;
import com.ontos.module.agent.service.run.AskRunStateService;
import com.ontos.module.agent.service.terminal.TerminalAssembler;
import com.ontos.module.system.dal.dataobject.ApprovalRequestDO;
import com.ontos.module.system.dal.mysql.ApprovalRequestMapper;
import com.ontos.module.system.service.approval.HitlNotifier;
import com.ontos.module.ontology.core.ManifestPayload;
import com.ontos.module.ontology.core.SemanticManifest;
import com.ontos.module.ontology.service.manifest.ManifestService;
import com.ontos.module.query.core.dsl.QueryDsl;
import com.ontos.module.query.service.QueryService;
import com.ontos.module.session.core.scenario.ScenarioRouteDecision;
import com.ontos.module.session.service.retrieval.ClarificationPayload;
import com.ontos.module.session.service.retrieval.MentionRetrievalService;
import com.ontos.module.session.service.retrieval.RetrievedCandidate;
import com.ontos.module.session.service.retrieval.RetrievalOutcome;
import com.ontos.module.session.service.retrieval.SessionCandidateContextProvider;
import com.ontos.module.session.service.scenario.ScenarioSessionService;
import com.ontos.module.session.service.session.SessionProjection;
import com.ontos.module.session.service.session.SessionStateService;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private final TerminalAssembler terminalAssembler;
    private final HitlNotifier hitlNotifier;
    private final ApprovalRequestMapper approvalRequestMapper;
    private final AskProperties askProperties;
    private final com.ontos.module.session.service.scenario.ScenarioSessionService scenarioSessionService;
    private final com.ontos.module.system.service.policy.PolicySnapshotService policySnapshotService;
    private final com.ontos.module.query.core.skill.SkillRegistry skillRegistry;
    private final ComplexAnalysisParamsResolver complexAnalysisParamsResolver;
    /** run 执行线程池（F-8）：HITL 轮询在执行线程内进行，不占 REST 线程。 */
    private final ExecutorService runExecutor;

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
                                   ManifestService manifestService,
                                   TerminalAssembler terminalAssembler,
                                   HitlNotifier hitlNotifier,
                                   ApprovalRequestMapper approvalRequestMapper,
                                   AskProperties askProperties,
                                   com.ontos.module.session.service.scenario.ScenarioSessionService scenarioSessionService,
                                   com.ontos.module.system.service.policy.PolicySnapshotService policySnapshotService,
                                   com.ontos.module.query.core.skill.SkillRegistry skillRegistry,
                                   ComplexAnalysisParamsResolver complexAnalysisParamsResolver) {
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
        this.terminalAssembler = terminalAssembler;
        this.hitlNotifier = hitlNotifier;
        this.approvalRequestMapper = approvalRequestMapper;
        this.askProperties = askProperties;
        this.scenarioSessionService = scenarioSessionService;
        this.policySnapshotService = policySnapshotService;
        this.skillRegistry = skillRegistry;
        this.complexAnalysisParamsResolver = complexAnalysisParamsResolver;
        this.runExecutor = Executors.newFixedThreadPool(
                Math.max(askProperties.getRunExecutorPool(), 1), runnable -> {
                    Thread thread = new Thread(runnable, "ask-run-executor");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    @PreDestroy
    void shutdownExecutor() {
        runExecutor.shutdownNow();
    }

    private IntentProposalContract contract() {
        return new IntentProposalContract(candidateProvider::candidateVocabulary);
    }

    /**
     * 一轮对话：run 在专用执行线程池内执行（HITL 轮询不占 REST 线程）；POST 同步等待至多
     * {@code ontos.ask.run-wait-timeout}，超时返回 waiting 中间态（run 继续执行，
     * 终态经事件重放通道跟踪——T4.2 HITL 内联的承载形态）。
     */
    public Map<String, Object> executeTurn(RequestContext context, String utterance,
                                           String clarificationChoice) {
        return executeTurn(context, utterance, clarificationChoice, null);
    }

    /** T4.4（R-29）：variantHint 随请求进瀑布——只参与候选排序权重（不扩候选集、不绕授权裁剪）。 */
    public Map<String, Object> executeTurn(RequestContext context, String utterance,
                                           String clarificationChoice, String variantHint) {
        var future = runExecutor.submit(
                () -> executeTurnInThread(context, utterance, clarificationChoice, variantHint));
        try {
            return future.get(askProperties.getRunWaitTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.ExecutionException ex) {
            if (ex.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(ex.getCause());
        } catch (TimeoutException ex) {
            // 同步窗口到期：run 仍在执行（可能等待审批），返回中间态由客户端经事件重放跟踪
            String runId = latestRunIdOf(context);
            if (runId == null) {
                // 竞态：run 已收尾（无活跃 run）——客户端按「重新查询/查历史」处理
                Map<String, Object> terminal = terminalAssembler.waiting(null, null);
                terminal.put("note", "run 已离开活跃状态，请经事件重放或审批列表查询结果");
                return terminal;
            }
            String approvalId = approvalIdOfRun(runId);
            return terminalAssembler.waiting(runId, approvalId);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ask 执行被中断", ex);
        }
    }

    /**
     * 流式一轮对话：编排仍在 run 执行线程内，事件经 {@code live} 同步推送；
     * 终态 / 异常通过回调交回调用方（SSE 端点负责收尾连接）。
     *
     * <p>与同步 {@link #executeTurn} 是**同一条链路**：不复制编排、不改判定顺序，
     * 区别只是事件多了一条实时出口（持久化、审计、fail-closed 行为完全一致）。
     *
     * <p>客户端断开只影响展示：编排继续执行到终态并落库（审计不可丢），
     * 断开方可用 {@code GET /ask/run/{runId}/events?afterSeq=} 补齐。
     */
    public void executeTurnStreaming(RequestContext context, String utterance,
                                     String clarificationChoice, String variantHint,
                                     java.util.function.Consumer<Map<String, Object>> live,
                                     java.util.function.Consumer<Map<String, Object>> onTerminal,
                                     java.util.function.Consumer<RuntimeException> onError) {
        runExecutor.submit(() -> {
            eventService.bindLiveChannel(live);
            try {
                onTerminal.accept(executeTurnInThread(context, utterance, clarificationChoice,
                        variantHint));
            } catch (RuntimeException ex) {
                onError.accept(ex);
            } finally {
                eventService.clearLiveChannel();
            }
        });
    }

    private String latestRunIdOf(RequestContext context) {
        return runStateService.latestRunId(context.tenantId(), context.userId(),
                context.sessionId());    }

    /** 审批 ID 从 run 的 INTERRUPT 事件提取（无则 null）。 */
    private String approvalIdOfRun(String runId) {
        for (Map<String, Object> event : eventService.replayAfter(runId, 0)) {
            if (AskEventService.INTERRUPT.equals(event.get("type"))) {
                Object payload = event.get("payload");
                if (payload instanceof String text) {
                    Matcher matcher = APPROVAL_ID_PATTERN.matcher(text);
                    // 正则无捕获组：取完整匹配（group(1) 同款缺陷——W4-5/W2 教训，静态审查再抓一次）
                    if (matcher.find()) {
                        return matcher.group(0);
                    }
                }
            }
        }
        return null;
    }

    /** run 执行主体（执行线程内；context 显式绑定线程——QueryService 链路依赖 RequestContextHolder）。 */
    private Map<String, Object> executeTurnInThread(RequestContext context, String utterance,
                                                    String clarificationChoice, String variantHint) {
        com.ontos.framework.web.core.context.RequestContextHolder.set(context);
        try {
            return doExecuteTurn(context, utterance, clarificationChoice, variantHint);
        } catch (SensitiveClosedException closed) {
            // 敏感收尾（拒绝/TTL）：终态已装配落事件、run 已置终态，直接返回载荷（不走 FAILED）
            return closed.terminal();
        } finally {
            com.ontos.framework.web.core.context.RequestContextHolder.clear();
        }
    }

    private Map<String, Object> doExecuteTurn(RequestContext context, String utterance,
                                              String clarificationChoice, String variantHint) {
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

            Map<String, Object> terminal = doExecute(context, runId, utterance, clarificationChoice,
                    variantHint);

            // 终态装配步骤：收口位置统一（各终态分支不各自埋点，避免重复与遗漏）
            eventService.emitStep(runId, AskStep.ASSEMBLE, AskStep.Status.DONE,
                    terminalDetail(terminal), null);
            runStateService.transition(runId, AskRunDO.RUNNING, AskRunDO.FINISHED);
            eventService.emit(runId, AskEventService.RUN_FINISHED,
                    Map.of("terminal", String.valueOf(terminal.get("terminal"))));
            return terminal;
        } catch (SensitiveClosedException closed) {
            // 敏感收尾（拒绝/TTL）：终态与 RUN_FINISHED 已落事件、run 已置终态——
            // 不 safeFail、不 emit RUN_ERROR（否则重放序列出现 FINISHED 后跟 ERROR 的矛盾）
            throw closed;
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
                                          String utterance, String clarificationChoice,
                                          String variantHint) {
        // 1. 检索瀑布（授权裁剪内）；指代消解来源 = 会话投影（resolved_entities JSON）
        SessionProjection projection = readProjection(context);
        boolean consumed = false;
        String approxSkillId = null;
        if (clarificationChoice != null && !clarificationChoice.isBlank()) {
            // T32-8 + T3.3：回传候选按前缀分流——场景确认走 confirm（状态迁移闭环），
            // 近似同意走降级执行；两者都经 M5 原子回传校验（伪造 candidateId 被拒）
            if (clarificationChoice.startsWith(
                    com.ontos.module.session.service.scenario.ScenarioSessionService.APPROX_CANDIDATE_PREFIX)) {
                sessionStateService.consumeClarificationChoice(context, clarificationChoice);
                consumed = true;
                // 回传键即近似承担者：approx:<skillId>（M5 消费返回的 assetId 为 null，不可用）
                approxSkillId = clarificationChoice.substring(
                        com.ontos.module.session.service.scenario.ScenarioSessionService.APPROX_CANDIDATE_PREFIX.length());
            } else if (clarificationChoice.startsWith("scenario:")) {
                scenarioSessionService.confirm(context, clarificationChoice,
                        scenariosById(), authorizedScenarioIds(context));
                // T51-1（P1，smoke-runner-v2 cold_start 流程实测发现）：场景确认只做状态迁移，
                // 不置 consumed——consumed 的规划语义是「指标歧义已裁决，本轮强制 query 组装」
                // （T3.3 近似同意沿用）。场景确认后当前话术应正常规划（无命中 → 场景内
                // discovery 冷启动承接）；强制 query 会在无资产话术（如「好」）下产出
                // metric_id=null 的提案并被输出契约拒绝（PROPOSAL_CONTRACT_INVALID），
                // 把「已进场景」掩盖成能力边界错误。
            } else {
                sessionStateService.consumeClarificationChoice(context, clarificationChoice);
                consumed = true;
            }
        }
        long retrieveStartedAt = stepStarted(runId, AskStep.RETRIEVE);
        RetrievalOutcome outcome = retrievalService.resolve(utterance, context, projection,
                variantHint);
        stepDone(runId, AskStep.RETRIEVE, retrievalDetail(outcome), retrieveStartedAt);

        // 2. Proposal：LLM（enabled 且可用）或确定性降级；近似同意轮强制 query 组装（T3.3）
        // 校验上下文必须与模型输入一致：多轮投影输入含服务端注入标记，不能退回原始短话术。
        // 投影是否注入只按服务端状态传值，不做文本匹配（标记串用户也能写出来）。
        boolean projectionInjected = projection != null && !projection.isEmpty();
        String contractQuestion = modelUserPrompt(utterance, projection);
        Map<String, Object> proposal;
        ProposalIntegrityService.IntegrityResult integrity;
        long understandStartedAt = stepStarted(runId, AskStep.UNDERSTAND);
        try {
            proposal = proposeWithFallback(context, runId, utterance, outcome, projection,
                    contractQuestion, consumed, approxSkillId != null);
            stepDone(runId, AskStep.UNDERSTAND, proposalDetail(proposal), understandStartedAt);

            // 3. 合同校验（确定性/LLM 输出同闸门）+ 来源完整性对账。
            long validateStartedAt = stepStarted(runId, AskStep.VALIDATE);
            IntentProposalContract.ValidationResult validation =
                    contract().validate(JsonUtils.toJsonString(proposal), contractQuestion, projectionInjected);
            if (!validation.valid()) {
                // 确定性输出也必须过合同；不过即编排缺陷，fail-closed 拒答
                log.warn("[doExecute][proposal 违反输出契约: {}]", validation.violations());
                stepFailed(runId, AskStep.VALIDATE, "输出契约未通过（" + validation.violations().size()
                        + " 项）", validateStartedAt);
                return outOfCapability(context, "PROPOSAL_CONTRACT_INVALID");
            }
            integrity = integrityService.check(proposal, outcome, utterance, context,
                    contract().candidateAssetIds(contractQuestion),
                    projection == null ? java.util.Set.of() : projection.assetIds());
            stepDone(runId, AskStep.VALIDATE, integrity.passed()
                    ? "合规校验与来源对账通过"
                    : "校验未通过，按原因码处置", validateStartedAt);
        } finally {
            // prime 生命周期覆盖到对账结束（O-3）：二次校验与模型修复轮同口径，
            // 都是「本轮检索收敛 + 投影继承」词表；提前清理会让这里回退全量授权词表。
            candidateProvider.clearPrimed(contractQuestion);
            candidateProvider.clearPrimed();
        }
        boolean degraded = Boolean.TRUE.equals(proposal.get("degraded"));
        if (!integrity.passed()) {
            if (integrity.action() == ProposalIntegrityService.IntegrityAction.REJECT) {
                return outOfCapability(context, integrity.reason());
            }
            // CLARIFY：候选集外 ID → 用瀑布澄清候选救一次
            if (!outcome.clarifications().isEmpty()) {
                return clarificationTerminal(context, outcome, degraded);
            }
            return outOfCapability(context, integrity.reason());
        }

        // 3.5 T3.3 近似同意：本轮用户已同意降级 → 用近似承担者的构造器执行（完整闸门），
        // 审计 MODE_APPROXIMATED + originalMode（近似槽位 = 提案槽位同词表）
        log.info("[approxBranch][approxSkillId={} terminal={} subIntentsType={}]", approxSkillId,
                proposal.get("terminal"),
                proposal.get("sub_intents") == null ? "null" : proposal.get("sub_intents").getClass().getSimpleName());
        if (approxSkillId != null) {
            Object raw = proposal.get("sub_intents");
            if (raw instanceof List<?> subIntents && !subIntents.isEmpty()
                    && subIntents.get(0) instanceof Map<?, ?> box) {
                String approximatedMode = approximatedModeOf(approxSkillId);
                eventService.emit(runId, AskEventService.TEXT_MESSAGE,
                        Map.of("delta", "已按您同意的近似方式执行（原请求模式不可用）"));
                // 槽位精确挑选（近似槽位词表 = 提案槽位的 metric_id/dimensions/time_expression_raw/limit），
                // 不透传整盒——box 里的 analysis_mode/filters 等槽位不属于 QUERY 近似构造器
                Map<String, Object> approxSlots = new java.util.LinkedHashMap<>();
                Map<String, Object> source = (Map<String, Object>) box;
                for (String key : List.of("metric_id", "dimensions", "time_expression_raw", "limit")) {
                    if (source.containsKey(key)) {
                        approxSlots.put(key, source.get(key));
                    }
                }
                var result = queryService.executeSkillApproximation(approxSkillId,
                        approxSlots, approximatedMode);
                sessionStateService.project(context, SessionProjection.of(
                                new QueryDsl() {{
                                    setMetricId(stringOf(((Map<String, Object>) box).get("metric_id")));
                                    setManifestId(activeManifestId(context));
                                }}),
                        "query", context.traceId());
                Map<String, Object> terminal = terminalAssembler.query(context.traceId(),
                        new TerminalAssembler.QueryStats(result.getRowCount(),
                                result.isTruncated(), result.getElapsedMs()),
                        result.getRows() == null ? List.of() : result.getRows(),
                        columnsOf(result.getColumns(), stringOf(((Map<String, Object>) box).get("metric_id"))),
                        false);
                terminalAssembler.emitTerminal(eventService, runId, terminal);
                return terminal;
            }
            return outOfCapability(context, "PROPOSAL_CONTRACT_INVALID");
        }

        // 4. 终态分派
        String terminal = String.valueOf(proposal.get("terminal"));
        return switch (terminal) {
            case "query" -> executeQueryTerminal(context, runId, proposal, outcome, utterance,
                    outcome.scenario());
            case "composite" -> executeCompositeTerminal(context, runId, proposal);
            case "clarification" -> clarificationTerminal(context, outcome, degraded);
            case "discovery" -> discoveryTerminal(context, outcome);
            default -> outOfCapability(context, "OUT_OF_SCOPE");
        };
    }

    /** 生效清单的已发布场景映射（场景确认的 status/entryMode 再校验数据源）。 */
    private Map<String, ManifestPayload.ScenarioMeta> scenariosById() {
        var manifest = manifestService.getActiveManifestOrNull();
        Map<String, ManifestPayload.ScenarioMeta> map = new java.util.LinkedHashMap<>();
        if (manifest != null && manifest.getScenarios() != null) {
            for (ManifestPayload.ScenarioMeta scenario : manifest.getScenarios()) {
                if ("published".equals(scenario.getStatus())) {
                    map.put(scenario.getId(), scenario);
                }
            }
        }
        return map;
    }

    /** 当前用户的场景授权集（授权集 scenario| 键）；无快照 fail-closed 由 policy 层抛错。 */
    private java.util.Set<String> authorizedScenarioIds(RequestContext context) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (String key : policySnapshotService.authorizedAssetIds(context)) {
            if (key.startsWith("scenario|")) {
                ids.add(key.substring("scenario|".length()));
            }
        }
        return java.util.Set.copyOf(ids);
    }

    /** 近似承担者声明的被近似模式（审计 originalMode；清单事实，声明缺失按 UNKNOWN）。 */
    private String approximatedModeOf(String approxSkillId) {
        var manifest = manifestService.getActiveManifestOrNull();
        if (manifest != null && manifest.getSkills() != null) {
            for (ManifestPayload.SkillMeta skill : manifest.getSkills()) {
                if (approxSkillId.equals(skill.getSkillId())
                        && skill.getApproximationOf() != null && manifest.getSkills() != null) {
                    for (ManifestPayload.SkillMeta target : manifest.getSkills()) {
                        if (target.getSkillId().equals(skill.getApproximationOf())) {
                            return target.getAnalysisMode() == null
                                    ? "UNKNOWN" : target.getAnalysisMode().toLowerCase();
                        }
                    }
                }
            }
        }
        return "UNKNOWN";
    }

    /** LLM 提案（enabled 时）；模型故障降级确定性提案（对外声明 degraded）——不悬挂、不无限重试。 */
    private Map<String, Object> proposeWithFallback(RequestContext context, String runId,
                                                    String utterance, RetrievalOutcome outcome,
                                                    SessionProjection projection, String modelPrompt,
                                                    boolean clarificationConsumed,
                                                    boolean approximationConsumed) {
        if (agentProperties.isEnabled()) {
            OntosAgentFactory factory = agentFactoryProvider.getIfAvailable();
            if (factory != null) {
                // E-7 词表收敛：提示词候选集 = 本轮对账口径（检索候选）；
                // prime/clear 必须同线程成对，由 doExecute 在「提案 + 二次校验 + 对账」之后统一清理
                // （O-3：提前清理会让二次校验回退全量授权词表，与模型可见词表不同口径）。
                // modelPrompt 同时注册异步修复轮快照：Reactor 线程拿不到请求 ThreadLocal。
                candidateProvider.prime(outcome, modelPrompt, projection);
                try {
                    Map<String, Object> llmProposal = callModel(factory, modelPrompt,
                            projection != null && !projection.isEmpty());
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
        return deterministicPlanner.plan(utterance, outcome, clarificationConsumed,
                approximationConsumed);
    }

    /**
     * 构造模型用户输入：多轮追问只给增量话术时，模型必须能看到上一轮已采纳语义槽位。
     * 投影只含语义 ID 与相对时间，不含物理表列；服务端仍负责最终 DSL 组装与安全闸门。
     */
    static String modelUserPrompt(String utterance, SessionProjection projection) {
        if (projection == null || projection.isEmpty()) {
            return utterance == null ? "" : utterance;
        }
        return IntentProposalContract.SESSION_PROJECTION_MARKER
                + "（语义槽位，供多轮继承；只含候选 ID，不代表本轮必须全部保留）："
                + JsonUtils.toJsonString(projection.toJson())
                + "\n当前用户话术：" + (utterance == null ? "" : utterance)
                + "\n请按系统提示词的多轮继承规则合并本轮完整语义槽位。";
    }

    /** 调用模型产出意图提案；修复反馈逐轮注入，超限返回契约兜底拒答（fail-closed）。 */
    private Map<String, Object> callModel(OntosAgentFactory factory, String modelPrompt,
                                          boolean projectionInjected) {
        // 调用来源在请求线程解析（T5.1）：模型流可能在别的线程完成，不能依赖那里的 ThreadLocal
        var agent = factory.create(llmCallOrigin());
        List<IntentProposalContract.Violation> lastViolations = List.of();
        for (int attempt = 0; attempt <= agentProperties.getOutputMaxRepairs(); attempt++) {
            Msg prompt = lastViolations.isEmpty()
                    ? Msg.builder().role(MsgRole.USER).textContent(modelPrompt).build()
                    : Msg.builder().role(MsgRole.USER)
                            .textContent(IntentProposalContract.renderRepairFeedback(lastViolations)
                                    + "\n\n当前输入：" + modelPrompt)
                            .build();
            Msg reply = agent.call(prompt)
                    .block(java.time.Duration.ofMillis(MODEL_TIMEOUT_MILLIS));
            String output = textOf(reply);
            IntentProposalContract.ValidationResult result =
                    contract().validate(output, modelPrompt, projectionInjected);
            if (result.valid()) {
                return result.parsed();
            }
            lastViolations = result.violations();
        }
        log.warn("[callModel][模型输出连续 {} 次未过契约，确定性拒答]",
                agentProperties.getOutputMaxRepairs() + 1);
        return JsonUtils.parseMap(IntentProposalContract.rejectFallback());
    }

    /**
     * 模型调用来源（T5.1）：身份取自请求上下文，缺失时不记录追踪（不写无租户行）。
     *
     * <p>purpose 标识**角色**而非单次调用：同一个 agent 实例在一次请求内可能产生多条记录
     * （意图提案 + Harness 的 MemoryConsolidator 后台整理，实测 1+2），
     * 成本统计必须按调用累加，不能假设「一次问数一次模型调用」。
     */
    private static LlmCallOrigin llmCallOrigin() {
        var context = com.ontos.framework.web.core.context.RequestContextHolder.get();
        if (context == null) {
            return null;
        }
        return LlmCallOrigin.of(context.tenantId(), context.traceId(), context.sessionId(),
                context.turnIndex(), "ask.agent");
    }

    private static String textOf(Msg reply) {
        if (reply == null) {
            return "";
        }
        return reply.getContentBlocks(TextBlock.class).stream()
                .map(TextBlock::getText)
                .reduce("", (left, right) -> left + right);
    }

    // ---------- 终态装配（T4.1 收敛到 TerminalAssembler） ----------

    /** M4 敏感触发对外码（§4.8.3 统一码）——异常消息内含审批单 ID。 */
    private static final int SENSITIVE_SEQUENCE_CODE = 1003000008;
    private static final Pattern APPROVAL_ID_PATTERN =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    /** VQR 命中终端（T3.5）：参数透传 queryService.executeVqr（模板绑定 + execute 全链 + VQR_HIT 审计）。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> executeVqrTerminal(RequestContext context, String runId,
                                                   Map<String, Object> box) {
        String vqrId = String.valueOf(box.get("vqr_id"));
        Map<String, Object> params = box.get("params") instanceof Map<?, ?> map
                ? new java.util.LinkedHashMap<>((Map<String, Object>) map) : Map.of();
        try {
            var result = queryService.executeVqr(vqrId, params);
            eventService.emit(runId, AskEventService.TEXT_MESSAGE,
                    Map.of("delta", "已按已审核的查询模板回答（" + vqrId + "）"));
            Map<String, Object> terminal = terminalAssembler.query(context.traceId(),
                    new TerminalAssembler.QueryStats(result.getRowCount(),
                            result.isTruncated(), result.getElapsedMs()),
                    result.getRows() == null ? List.of() : result.getRows(),
                    columnsOf(result.getColumns(), null), false);
            terminalAssembler.emitTerminal(eventService, runId, terminal);
            return terminal;
        } catch (RuntimeException ex) {
            // 命中后重校验拒绝（越权/失效/口径冲突）如实透出业务码——fail-closed 不静默降级
            log.info("[executeVqrTerminal][vqr({}) 拒绝: {}]", vqrId, ex.getMessage());
            throw ex;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> executeQueryTerminal(RequestContext context, String runId,
                                                     Map<String, Object> proposal,
                                                     RetrievalOutcome outcome,
                                                     String utterance,
                                                     RetrievalOutcome.ScenarioContext scenario) {
        Object raw = proposal.get("sub_intents");
        if (!(raw instanceof List<?> subIntents) || subIntents.isEmpty()
                || !(subIntents.get(0) instanceof Map<?, ?> box)) {
            return terminalAssembler.outOfCapability(context, "PROPOSAL_CONTRACT_INVALID");
        }
        // T3.5 VQR 命中：提案引用 vqr_id（完整性闸门已对账候选集）→ 模板绑定 + 既有 execute
        // 全链（八步重校验）；vqr_id 不经 assembleDsl（散装槽位路径），vqrId 不由本层再解释
        if (box.containsKey("vqr_id")) {
            long vqrRouteStartedAt = stepStarted(runId, AskStep.ROUTE);
            stepDone(runId, AskStep.ROUTE, "命中已审核的查询模板", vqrRouteStartedAt);
            return executeVqrTerminal(context, runId, (Map<String, Object>) box);
        }
        long composeStartedAt = stepStarted(runId, AskStep.COMPOSE);
        QueryDsl dsl = assembleDsl(context, (Map<String, Object>) box);

        // ADR-0023 v1.1：FUNNEL / RETENTION / ATTRIBUTION / LOOKUP_BATCH 的复杂参数由服务端补齐。
        // 模型缺事件、缺身份或指标不支持时，不猜参数：优先用检索澄清，否则结构化拒答。
        var manifest = manifestService.getActiveManifestOrNull();
        var paramResolution = complexAnalysisParamsResolver.resolve(dsl, utterance, manifest);
        if (!paramResolution.resolved()) {
            log.info("[complexParams][unresolved: {}]", paramResolution.reason());
            stepSkipped(runId, AskStep.COMPOSE, "复杂分析参数不足，未进入执行");
            return outcome.clarifications().isEmpty()
                    ? outOfCapability(context, paramResolution.reason())
                    : clarificationTerminal(context, outcome, Boolean.TRUE.equals(proposal.get("degraded")));
        }
        dsl = paramResolution.dsl();
        stepDone(runId, AskStep.COMPOSE, composeDetail(dsl), composeStartedAt);

        // M6/W2/T2.4（§3.2）：场景内模式候选 ∩ allowed_analysis_modes（确定性过滤，
        // 越界模式 fail-closed 拒答，不静默改写）；DSL scenarioId 由服务端路由结果盖章，
        // 不采信提案——直传越权在 query 侧 ScenarioRouteGuard 再拦一道（防御纵深）。
        // 场景路由判定发生在 DSL 构造之后（先有查询、再按场景收敛模式），
        // 所以 ROLE 步骤在 COMPOSE 之后下发——事件顺序即真实执行顺序，不按枚举顺序摆放。
        long routeStartedAt = stepStarted(runId, AskStep.ROUTE);
        boolean inScenario = scenario != null && scenario.scenarioId() != null;
        stepDone(runId, AskStep.ROUTE, inScenario
                ? "命中业务场景「" + scenarioLabel(scenario.scenarioId()) + "」"
                : "未命中业务场景，按自由问数处理", routeStartedAt);
        if (inScenario) {
            if (!scenario.allowedModes().isEmpty()
                    && (dsl.getMode() == null
                    || !scenario.allowedModes().contains(dsl.getMode().name().toLowerCase()))) {
                // T3.3（ADR-0017 D3）：被拒模式先查预声明近似——有承担者 → 征求同意
                //（M5 回传通道，候选 {接受近似, 拒绝}）；无声明 → 维持拒绝（不退化到自由 SQL）
                String rejectedMode = dsl.getMode() == null
                        ? null : dsl.getMode().name().toLowerCase();
                var approxCarriers = skillRegistry.approximationCarriersFor(
                        rejectedMode, scenario.scenarioId());
                if (approxCarriers.isEmpty()) {
                    return terminalAssembler.outOfCapability(context, "SCENARIO_MODE_FORBIDDEN");
                }
                var carrier = approxCarriers.get(0);
                String candidateId = scenarioSessionService.requestApproximation(context,
                        carrier.skill().getSkillId(),
                        "用「" + carrier.skill().getName() + "」近似回答（原模式不可用）");
                List<RetrievedCandidate> approxCandidates =
                        List.of(new RetrievedCandidate(candidateId, "approximation",
                                        "接受近似：" + carrier.skill().getName(), 0, false, 0),
                                new RetrievedCandidate(ScenarioRouteDecision.EXIT_CANDIDATE_ID,
                                        "approximation", "拒绝近似", 0, false, 0));
                return terminalAssembler.clarification(context, new ClarificationPayload(
                        "「" + rejectedMode + "」在当前场景不可用，是否用近似能力回答？",
                        approxCandidates, candidateId, true),
                        Boolean.TRUE.equals(proposal.get("degraded")));
            }
            dsl.setScenarioId(scenario.scenarioId());
        }

        // T3.4 第二级路由：Analysis Mode 从指标 allowed_modes 确定性过滤
        filterAnalysisMode(dsl);

        long executeStartedAt = stepStarted(runId, AskStep.EXECUTE);
        eventService.emitToolStart(runId, "execute_query", toolArgsSummary(dsl));
        ExecutedQuery executed;
        try {
            executed = executeWithHitl(context, runId, dsl);
        } catch (SensitiveClosedException closed) {
            // 审批拒绝 / TTL：不是执行故障，工具与步骤都按「已收尾」记录
            eventService.emitToolEnd(runId, "execute_query", "closed",
                    List.of("审批未放行"), elapsedMs(executeStartedAt));
            stepSkipped(runId, AskStep.EXECUTE, "审批未放行，已按原因码收尾");
            throw closed;
        } catch (RuntimeException ex) {
            eventService.emitToolEnd(runId, "execute_query", "failed",
                    List.of("执行未通过"), elapsedMs(executeStartedAt));
            stepFailed(runId, AskStep.EXECUTE, "执行未通过（已按原因码处置）", executeStartedAt);
            throw ex;
        }
        int rowCount = executed.result() == null ? 0 : executed.result().getRowCount();
        eventService.emitToolEnd(runId, "execute_query", "succeeded",
                toolResultSummary(executed.result()), elapsedMs(executeStartedAt));
        stepDone(runId, AskStep.EXECUTE, "返回 " + rowCount + " 行", executeStartedAt);

        // 会话投影（F-1：每轮全量覆盖）；requestId 与审计行同源（HITL 放行重放是 -replay 新 ID）
        sessionStateService.project(context, SessionProjection.of(dsl), "query",
                executed.requestId());

        // 审计与查询同一事务（审计写入失败即整单失败）——执行成功即已落库。
        // 这里没有独立时钟可测（同一事务内已提交），不发耗时，避免出现「0 ms」的假精确。
        eventService.emitStep(runId, AskStep.AUDIT, AskStep.Status.RUNNING, null, null);
        eventService.emitStep(runId, AskStep.AUDIT, AskStep.Status.DONE,
                "已随查询原子写入，可在审计页追溯", null);

        Map<String, Object> terminal = terminalAssembler.query(executed.requestId(),
                new TerminalAssembler.QueryStats(rowCount,
                        executed.result() != null && executed.result().isTruncated(),
                        executed.result() == null ? 0L : executed.result().getElapsedMs()),
                executed.result() == null ? List.of() : executed.result().getRows(),
                executed.result() == null ? List.of()
                        : columnsOf(executed.result().getColumns(), dsl.getMetricId()),
                Boolean.TRUE.equals(proposal.get("degraded")));
        terminalAssembler.emitTerminal(eventService, runId, terminal);
        return terminal;
    }

    /**
     * T4.2 HITL 内联：执行抛 1003000008（敏感累计/差分/个体探针触发）→ run 置
     * WAITING_APPROVAL + INTERRUPT 事件（复用 M4 HitlNotifier）→ DB 轮询审批状态
     * （1s 间隔 / TTL 上限）→ APPROVED 携令牌重放同一查询 → 放行；REJECTED / 超时收尾。
     */
    /** 执行结果 + 实际生效的 request_id（HITL 放行重放是新执行，ID 与首次触发不同）。 */
    private record ExecutedQuery(com.ontos.module.query.core.executor.QueryResult result,
                                 String requestId) {
    }

    private ExecutedQuery executeWithHitl(RequestContext context, String runId, QueryDsl dsl) {
        try {
            return new ExecutedQuery(queryService.execute(dsl), context.traceId());
        } catch (ServiceException ex) {
            if (ex.getCode() != SENSITIVE_SEQUENCE_CODE) {
                throw ex;
            }
            String approvalId = extractApprovalId(ex.getMessage());
            if (approvalId == null) {
                // 无审批单可等待（异常形态异常）：按拒答收尾，不悬挂
                throw ex;
            }
            return awaitApprovalAndReplay(context, runId, dsl, approvalId);
        }
    }

    private String extractApprovalId(String message) {
        if (message == null) {
            return null;
        }
        Matcher matcher = APPROVAL_ID_PATTERN.matcher(message);
        // 正则无捕获组：取完整匹配（group(1) 会 IndexOutOfBounds——W2 探针同款教训）
        return matcher.find() ? matcher.group(0) : null;
    }

    private ExecutedQuery awaitApprovalAndReplay(
            RequestContext context, String runId, QueryDsl dsl, String approvalId) {
        // 敏感触发：run → WAITING_APPROVAL（先判定后转移，状态机校验迁移合法性）
        runStateService.transition(runId, AskRunDO.RUNNING, AskRunDO.WAITING_APPROVAL);
        Map<String, Object> interrupt = new LinkedHashMap<>();
        interrupt.put("approvalId", approvalId);
        interrupt.put("decideEndpoint", "/api/v1/approvals/" + approvalId + "/decide");
        interrupt.put("reasonCode", "SENSITIVE_QUERY_SEQUENCE");
        eventService.emit(runId, AskEventService.INTERRUPT, interrupt);
        // M4 SSE 通道同步下发（前端审批卡片；无订阅者静默跳过——审批单已在 DB）
        hitlNotifier.notifyInterrupt(context.tenantId(), context.userId(), approvalId,
                "SENSITIVE_QUERY_SEQUENCE", String.valueOf(dsl.getMetricId()));

        var decision = pollApproval(context, approvalId);
        if (decision == null) {
            // TTL 到期：run EXPIRED（审批单仍在 TTL 内有效，用户可携令牌重查——M4 闭环不受影响）
            runStateService.transition(runId, AskRunDO.WAITING_APPROVAL, AskRunDO.EXPIRED);
            Map<String, Object> terminal =
                    terminalAssembler.sensitiveClosed(context, "SENSITIVE_QUERY_EXPIRED", approvalId);
            terminalAssembler.emitTerminal(eventService, runId, terminal);
            eventService.emit(runId, AskEventService.RUN_FINISHED,
                    Map.of("terminal", "out_of_capability"));
            throw new SensitiveClosedException(terminal);
        }
        if ("EXPIRED".equals(decision.getStatus())) {
            // 审批单被 TTL 扫描置 EXPIRED：与轮询超时同收尾（语义一致，不误报 REJECTED）
            runStateService.transition(runId, AskRunDO.WAITING_APPROVAL, AskRunDO.EXPIRED);
            Map<String, Object> terminal =
                    terminalAssembler.sensitiveClosed(context, "SENSITIVE_QUERY_EXPIRED", approvalId);
            terminalAssembler.emitTerminal(eventService, runId, terminal);
            eventService.emit(runId, AskEventService.RUN_FINISHED,
                    Map.of("terminal", "out_of_capability"));
            throw new SensitiveClosedException(terminal);
        }
        if (!"APPROVED".equals(decision.getStatus())) {
            // 决策拒绝：run → FAILED + 结构化收尾
            runStateService.transition(runId, AskRunDO.WAITING_APPROVAL, AskRunDO.FAILED);
            Map<String, Object> terminal =
                    terminalAssembler.sensitiveClosed(context, "SENSITIVE_QUERY_REJECTED", approvalId);
            terminalAssembler.emitTerminal(eventService, runId, terminal);
            eventService.emit(runId, AskEventService.RUN_FINISHED,
                    Map.of("terminal", "out_of_capability"));
            throw new SensitiveClosedException(terminal);
        }
        // APPROVED：恢复 RUNNING → 携 M4 令牌重放同一查询（QueryService 的 consumeGrant
        // 做指纹比对与单次消费；换 filters 不放行、复用拒绝）。
        // 重放是新执行：request_id 换新（原 ID 已被首次触发的 REJECTED 占用——幂等键不可复用）
        runStateService.transition(runId, AskRunDO.WAITING_APPROVAL, AskRunDO.RUNNING);
        RequestContext granted = new RequestContext(context.tenantId(), context.userId(),
                context.sessionId(), context.turnIndex(), context.manifestId(),
                context.traceId() + "-replay",
                context.roles(), context.devMode(), approvalId);
        com.ontos.framework.web.core.context.RequestContextHolder.set(granted);
        try {
            var result = queryService.execute(dsl);
            // 放行的审计（SENSITIVE_APPROVED_GRANT 由 QueryService 审计链路承载）；
            // requestId 返回 -replay 新 ID：投影/planRef 与审计行同源（回放链路不打断）
            return new ExecutedQuery(result, granted.traceId());
        } finally {
            com.ontos.framework.web.core.context.RequestContextHolder.clear();
        }
    }

    /** 审批状态轮询：1s 间隔（可配），上限 TTL；返回终态决策或 null（超时）。 */
    private ApprovalRequestDO pollApproval(RequestContext context, String approvalId) {
        long deadline = System.currentTimeMillis()
                + askProperties.getApprovalWaitLimit().toMillis();
        long interval = Math.max(askProperties.getApprovalPollInterval().toMillis(), 100);
        while (System.currentTimeMillis() < deadline && !Thread.currentThread().isInterrupted()) {
            var approval = approvalRequestMapper.selectById(approvalId, context.tenantId());
            if (approval.isPresent()
                    && !"PENDING".equals(approval.get().getStatus())) {
                return approval.get();
            }
            try {
                Thread.sleep(interval);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    /** 敏感收尾信号：终态已装配并落事件，向上传递给 executeTurn 直接返回（不走 FAILED）。 */
    private static class SensitiveClosedException extends RuntimeException {
        private final Map<String, Object> terminal;

        SensitiveClosedException(Map<String, Object> terminal) {
            super("sensitive closed", null, false, false);
            this.terminal = terminal;
        }

        Map<String, Object> terminal() {
            return terminal;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> executeCompositeTerminal(RequestContext context, String runId,
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
        Map<String, Object> terminal = terminalAssembler.composite(context.traceId(),
                outcome == null ? List.of() : outcome.results(),
                Boolean.TRUE.equals(proposal.get("degraded")));
        terminalAssembler.emitTerminal(eventService, runId, terminal);
        return terminal;
    }

    /**
     * 澄清终态（O-2）：degraded 必须从提案透传——模型超时后确定性降级也会走到澄清，
     * 不透传会让评测侧把降级路径当成模型主动澄清。
     */
    private Map<String, Object> clarificationTerminal(RequestContext context,
                                                      RetrievalOutcome outcome, boolean degraded) {
        var payload = outcome.clarifications().isEmpty() ? null : outcome.clarifications().get(0);
        return terminalAssembler.clarification(context, payload, degraded);
    }

    private Map<String, Object> discoveryTerminal(RequestContext context,
                                                  RetrievalOutcome outcome) {
        // 授权 scope 内可用能力（已发布引用，不由 LLM 生成）
        return terminalAssembler.discovery(
                outcome.dslReady().stream()
                        .filter(candidate -> "metric".equals(candidate.assetType())).toList(),
                outcome.dslReady().stream()
                        .filter(candidate -> "dimension".equals(candidate.assetType())).toList());
    }

    private Map<String, Object> outOfCapability(RequestContext context, String reason) {
        return terminalAssembler.outOfCapability(context, reason);
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
        // 相对时间的时间维度由清单推导（T5.3 P1 修复）：LLM 只给表达式、不给物理字段，
        // 而 TimeWindowResolver 解析相对时间要求 timeRange.field 已在位。
        // 仅 QUERY / YOY_MOM 设通用时间窗——FUNNEL 走 cohortWindow、ATTRIBUTION/RETENTION
        // 的 field 由服务端按事件回填（客户端字段一律拒绝，见 TimeWindowResolver）。
        if (dsl.getTimeRange() == null && dsl.getTimeExpressionRaw() != null
                && !dsl.getTimeExpressionRaw().isBlank()
                && (dsl.getMode() == QueryDsl.AnalysisMode.QUERY
                        || dsl.getMode() == QueryDsl.AnalysisMode.YOY_MOM)) {
            String timeDimension = TimeDimensionResolver.resolve(
                    manifestService.getActiveManifestOrNull(), dsl.getMetricId());
            if (timeDimension != null) {
                QueryDsl.TimeRange range = new QueryDsl.TimeRange();
                range.setField(timeDimension);
                dsl.setTimeRange(range);
            }
        }
        // yoy_mom 的 period 由服务端推导（E-7 组装缺口修复）：提案 Schema 无 analysisParams 槽位，
        // 而 DslValidator 要求 yoy_mom 必带 period（MONTH|YEAR）——不推导则同比问题永远无法经 ask 执行
        if (dsl.getMode() == QueryDsl.AnalysisMode.YOY_MOM
                && (dsl.getAnalysisParams() == null || dsl.getAnalysisParams().getPeriod() == null)) {
            QueryDsl.AnalysisParams params = dsl.getAnalysisParams() == null
                    ? new QueryDsl.AnalysisParams() : dsl.getAnalysisParams();
            params.setPeriod(YoyPeriodResolver.derive(dsl.getTimeExpressionRaw()));
            dsl.setAnalysisParams(params);
        }
        // 属性投影（LOOKUP 回显列）：提案的 attributes 是语义槽位，物理列由清单约束
        if (subIntent.get("attributes") instanceof List<?> attributes) {
            attributes.forEach(attribute -> {
                if (attribute instanceof String s && !s.isBlank()) {
                    dsl.getAttributes().add(s);
                }
            });
        }
        // E7/bool-08 修复：ruleId 型布尔指标通过专用槽位进入 LOOKUP，不伪装成普通 metric_id。
        String booleanMetricId = stringOf(subIntent.get("boolean_metric_id"));
        if (booleanMetricId != null) {
            dsl.setBooleanMetricId(booleanMetricId);
        }
        if (dsl.getMode() == QueryDsl.AnalysisMode.LOOKUP) {
            fillLookupIdentity(context, dsl, stringOf(subIntent.get("identity_value")));
        }
        return dsl;
    }

    /**
     * LOOKUP 身份补全（服务端推衍，M3 语义）：entityId 从首个投影属性的 {@code conceptId}
     * 反查（清单事实，不参与 LLM 输出对账）；identityPropertyId 取 concept.identityProperty；
     * identity.value 来自话术确定性提取。
     */
    private void fillLookupIdentity(RequestContext context, QueryDsl dsl, String identityValue) {
        if (identityValue == null || dsl.getAttributes() == null || dsl.getAttributes().isEmpty()) {
            return;
        }
        var manifest = manifestService.getActiveManifestOrNull();
        if (manifest == null || manifest.getOntology() == null) {
            return;
        }
        String conceptId = manifest.getOntology().getProperties().stream()
                .filter(property -> dsl.getAttributes().contains(property.getId()))
                .findFirst()
                .map(com.ontos.module.ontology.core.OntologySnapshot.Property::getConceptId)
                .orElse(null);
        if (conceptId == null) {
            return;
        }
        dsl.setEntityId(conceptId);
        manifest.getOntology().getConcepts().stream()
                .filter(concept -> concept.getId().equals(conceptId))
                .findFirst()
                .ifPresent(concept -> {
                    dsl.setIdentityPropertyId(concept.getIdentityProperty());
                    QueryDsl.LookupIdentity identity = new QueryDsl.LookupIdentity();
                    identity.setPropertyId(concept.getIdentityProperty());
                    identity.setValue(identityValue);
                    dsl.setIdentity(identity);
                });
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

    // ---------- 过程事件（过程可见性）：步骤是链路事实，detail 只放业务话术 ----------

    private long stepStarted(String runId, AskStep step) {
        eventService.emitStep(runId, step, AskStep.Status.RUNNING, null, null);
        return System.nanoTime();
    }

    private void stepDone(String runId, AskStep step, String detail, long startedAt) {
        eventService.emitStep(runId, step, AskStep.Status.DONE, detail, elapsedMs(startedAt));
    }

    private void stepFailed(String runId, AskStep step, String detail, long startedAt) {
        eventService.emitStep(runId, step, AskStep.Status.FAILED, detail, elapsedMs(startedAt));
    }

    private void stepSkipped(String runId, AskStep step, String detail) {
        eventService.emitStep(runId, step, AskStep.Status.SKIPPED, detail, null);
    }

    private static long elapsedMs(long startedAtNanos) {
        return Math.max(0, (System.nanoTime() - startedAtNanos) / 1_000_000);
    }

    /** 检索摘要：只出业务叫法与数量，不出资产 ID。 */
    private String retrievalDetail(RetrievalOutcome outcome) {
        List<String> terms = outcome.dslReady().stream()
                .map(RetrievedCandidate::matchedTerm)
                .filter(term -> term != null && !term.isBlank())
                .distinct()
                .limit(3)
                .toList();
        StringBuilder detail = new StringBuilder("命中 ")
                .append(outcome.dslReady().size()).append(" 项");
        if (!terms.isEmpty()) {
            detail.append("：").append(String.join("、", terms));
            if (outcome.dslReady().size() > terms.size()) {
                detail.append(" 等");
            }
        }
        if (!outcome.fuzzyOnly().isEmpty()) {
            detail.append("；模糊候选 ").append(outcome.fuzzyOnly().size()).append(" 项");
        }
        if (!outcome.clarifications().isEmpty()) {
            detail.append("；需要确认 ").append(outcome.clarifications().size()).append(" 处口径");
        }
        return detail.toString();
    }

    /** 提案摘要：指标 / 维度 / 时间窗 / 模式；ID 一律换成业务名。 */
    private String proposalDetail(Map<String, Object> proposal) {
        List<String> parts = new ArrayList<>();
        Object raw = proposal.get("sub_intents");
        if (raw instanceof List<?> items && !items.isEmpty() && items.get(0) instanceof Map<?, ?> box) {
            if (box.get("metric_id") instanceof String metricId && !metricId.isBlank()) {
                parts.add(labelOf(metricId));
            }
            if (box.get("dimensions") instanceof List<?> dimensions && !dimensions.isEmpty()) {
                List<String> labels = dimensions.stream()
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .map(this::labelOf)
                        .toList();
                parts.add("按" + String.join("、", labels));
            }
            if (box.get("time_expression_raw") instanceof String time && !time.isBlank()) {
                parts.add(time);
            }
            String mode = modeLabel(stringOf(box.get("analysis_mode")));
            if (mode != null) {
                parts.add(mode);
            }
        }
        if (Boolean.TRUE.equals(proposal.get("degraded"))) {
            parts.add("确定性模式");
        }
        return parts.isEmpty() ? "未解析出可用槽位" : String.join(" · ", parts);
    }

    private String composeDetail(QueryDsl dsl) {
        List<String> parts = new ArrayList<>();
        parts.add("指标 " + labelOf(dsl.getMetricId()));
        if (!dsl.getDimensions().isEmpty()) {
            List<String> labels = dsl.getDimensions().stream().map(this::labelOf).toList();
            parts.add("维度 " + String.join("、", labels));
        }
        if (!dsl.getFilters().isEmpty()) {
            parts.add("过滤 " + dsl.getFilters().size() + " 项");
        }
        if (dsl.getTimeExpressionRaw() != null && !dsl.getTimeExpressionRaw().isBlank()) {
            parts.add("时间 " + dsl.getTimeExpressionRaw());
        }
        String mode = modeLabel(dsl.getMode() == null ? null : dsl.getMode().name());
        if (mode != null) {
            parts.add(mode);
        }
        return String.join(" · ", parts);
    }

    private List<String> toolArgsSummary(QueryDsl dsl) {
        List<String> summary = new ArrayList<>();
        summary.add("指标 " + labelOf(dsl.getMetricId()));
        if (!dsl.getDimensions().isEmpty()) {
            List<String> labels = dsl.getDimensions().stream().map(this::labelOf).toList();
            summary.add("维度 " + String.join("、", labels));
        }
        if (dsl.getLimit() != null) {
            summary.add("上限 " + dsl.getLimit() + " 行");
        }
        return summary;
    }

    private List<String> toolResultSummary(com.ontos.module.query.core.executor.QueryResult result) {
        List<String> summary = new ArrayList<>();
        if (result == null) {
            summary.add("无返回结果");
            return summary;
        }
        summary.add("返回 " + result.getRowCount() + " 行");
        summary.add(result.isTruncated() ? "已按上限截断" : "未截断");
        summary.add(result.isCached() ? "命中结果缓存" : "只读连接执行");
        return summary;
    }

    /** 场景 ID → 业务场景名（场景名是资产内容，可对外；ID 不对外）。 */
    private String scenarioLabel(String scenarioId) {
        ManifestPayload.ScenarioMeta meta = scenariosById().get(scenarioId);
        return meta != null && meta.getName() != null && !meta.getName().isBlank()
                ? meta.getName() : "当前业务场景";
    }

    /** 资产 ID → 业务名。无清单或查不到时给中性文案，**不回退 ID**（ID 不上屏）。 */
    private String labelOf(String assetId) {
        if (assetId == null || assetId.isBlank()) {
            return "未指定";
        }
        var manifest = manifestService.getActiveManifestOrNull();
        if (manifest == null || manifest.getSemantic() == null) {
            return "未命名资产";
        }
        String label = manifest.getSemantic().getMetrics().stream()
                .filter(metric -> assetId.equals(metric.getId()))
                .map(SemanticManifest.Metric::getName)
                .filter(name -> name != null && !name.isBlank())
                .findFirst()
                .orElse(null);
        if (label == null) {
            label = manifest.getSemantic().getDimensions().stream()
                    .filter(dimension -> assetId.equals(dimension.getId()))
                    .map(SemanticManifest.Dimension::getName)
                    .filter(name -> name != null && !name.isBlank())
                    .findFirst()
                    .orElse(null);
        }
        return label == null ? "未命名资产" : label;
    }

    private static String modeLabel(String mode) {
        if (mode == null || mode.isBlank()) {
            return null;
        }
        return switch (mode.toUpperCase()) {
            case "QUERY" -> "聚合查询";
            case "LOOKUP" -> "明细查询";
            case "LOOKUP_BATCH" -> "批量明细";
            case "FUNNEL" -> "漏斗分析";
            case "YOY_MOM" -> "同环比";
            case "ATTRIBUTION" -> "归因分析";
            case "RETENTION" -> "留存分析";
            case "DISCOVERY" -> "能力发现";
            default -> null;
        };
    }

    /**
     * 结果列元数据：把 SQL 列别名（{@code value} / {@code dim.region} / {@code step_1}）
     * 翻成业务表头。列顺序沿用结果集顺序，**不排序**（排序会改变用户看到的列序）。
     *
     * <p>身份列（{@code __identity}、{@code subject}）不下发——它们只服务内部对齐，
     * 对用户没有意义，也就没有泄曝面。
     */
    private List<TerminalAssembler.Column> columnsOf(List<String> keys, String metricId) {
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        var manifest = manifestService.getActiveManifestOrNull();
        java.util.Set<String> dimensionIds = manifest == null || manifest.getSemantic() == null
                ? java.util.Set.of()
                : manifest.getSemantic().getDimensions().stream()
                        .map(SemanticManifest.Dimension::getId)
                        .collect(java.util.stream.Collectors.toSet());
        String metricLabel = metricId == null || metricId.isBlank() ? "指标值" : labelOf(metricId);
        List<TerminalAssembler.Column> columns = new ArrayList<>();
        for (String key : keys) {
            if (key == null || key.isBlank() || "__identity".equals(key) || "subject".equals(key)) {
                continue;
            }
            if ("value".equals(key) || "metric_value".equals(key)
                    || "attributed_metric_value".equals(key)) {
                columns.add(new TerminalAssembler.Column(key, metricLabel, "metric", true));
                continue;
            }
            if (dimensionIds.contains(key)) {
                columns.add(new TerminalAssembler.Column(key, labelOf(key), "dimension", false));
                continue;
            }
            TerminalAssembler.Column special = specialColumn(key);
            columns.add(special != null ? special
                    : new TerminalAssembler.Column(key, key, "other", false));
        }
        return columns;
    }

    /** 分析模式产出的固定列（语义由编译器决定，这里只是业务化命名，不是猜测）。 */
    private static TerminalAssembler.Column specialColumn(String key) {
        if (key.startsWith("step_")) {
            return new TerminalAssembler.Column(key, "第 " + key.substring(5) + " 步", "step", true);
        }
        if (key.startsWith("conversion_")) {
            return new TerminalAssembler.Column(key, "第 " + key.substring(11) + " 步转化率",
                    "conversion", true);
        }
        return switch (key) {
            case "cohort_period" -> new TerminalAssembler.Column(key, "同期群", "cohort", false);
            case "cohort_users" -> new TerminalAssembler.Column(key, "同期群用户数", "cohort", true);
            case "retained", "retained_users" ->
                    new TerminalAssembler.Column(key, "留存用户数", "cohort", true);
            case "retention_rate" -> new TerminalAssembler.Column(key, "留存率", "cohort", true);
            case "conversion_id" -> new TerminalAssembler.Column(key, "转化事件", "cohort", false);
            case "touched_events" -> new TerminalAssembler.Column(key, "触达人次", "cohort", true);
            case "converted_events" -> new TerminalAssembler.Column(key, "转化人次", "cohort", true);
            case "yoy" -> new TerminalAssembler.Column(key, "同比", "conversion", true);
            case "mom" -> new TerminalAssembler.Column(key, "环比", "conversion", true);
            case "period" -> new TerminalAssembler.Column(key, "时间周期", "cohort", false);
            case "period_index" -> new TerminalAssembler.Column(key, "周期序号", "cohort", true);
            case "channel_name" -> new TerminalAssembler.Column(key, "渠道", "dimension", false);
            case "attributed_channel_id" ->
                    new TerminalAssembler.Column(key, "归因渠道", "dimension", false);
            default -> null;
        };
    }

    @SuppressWarnings("unchecked")
    private static String terminalDetail(Map<String, Object> terminal) {
        String type = String.valueOf(terminal.get("terminal"));
        Map<String, Object> body = terminal.get("body") instanceof Map<?, ?> box
                ? (Map<String, Object>) box : Map.of();
        return switch (type) {
            case "query" -> "已返回 " + body.getOrDefault("rowCount", 0) + " 行结果";
            case "composite" -> "已返回 " + (body.get("subResults") instanceof List<?> list
                    ? list.size() : 0) + " 个子结果";
            case "clarification" -> "需要你确认口径后才能继续";
            case "discovery" -> "已返回当前可查询的能力清单";
            case "waiting" -> "查询在等待审批决策";
            case "out_of_capability" -> "已给出可回答范围";
            default -> "已完成";
        };
    }

    private RequestContext runContext() {
        return com.ontos.framework.web.core.context.RequestContextHolder.require();
    }

}

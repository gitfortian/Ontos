package com.ontos.module.query.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.Set;
import java.util.Optional;
import java.util.TreeSet;

import com.ontos.framework.common.exception.ServiceException;
import com.ontos.framework.common.util.json.JsonUtils;
import com.ontos.framework.web.core.context.RequestContext;
import com.ontos.framework.web.core.context.RequestContextHolder;
import com.ontos.module.ontology.core.OntologySnapshot;
import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.ontology.service.manifest.ManifestService;
import com.ontos.module.query.core.cache.QueryCacheKey;
import com.ontos.module.query.core.compiler.CompiledQuery;
import com.ontos.module.query.core.compiler.JoinPathResolver;
import com.ontos.module.query.core.compiler.SqlCompiler;
import com.ontos.module.query.core.dsl.DslValidator;
import com.ontos.module.query.core.dsl.QueryDsl;
import com.ontos.module.query.core.executor.DryRunOutcome;
import com.ontos.module.query.core.executor.QueryExecutor;
import com.ontos.module.query.core.executor.QueryPlan;
import com.ontos.module.query.core.executor.QueryResult;
import com.ontos.module.query.core.scenario.ScenarioRouteGuard;
import com.ontos.module.ontology.core.ManifestPayload;
import com.ontos.module.query.core.skill.ControlledCapabilities;
import com.ontos.module.query.core.skill.SkillHandler;
import com.ontos.module.query.core.skill.SkillRegistry;
import com.ontos.module.query.core.time.TimeExpressionParser;
import com.ontos.module.query.core.time.TimeWindowResolver;
import com.ontos.module.query.dal.dataobject.QueryArtifactDO;
import com.ontos.module.query.dal.mysql.QueryArtifactRepository;
import com.ontos.module.query.service.cache.QueryCacheService;
import com.ontos.module.system.service.audit.AuditLogCreateReqDTO;
import com.ontos.module.system.service.audit.AuditService;
import com.ontos.module.system.service.policy.PolicyService;
import com.ontos.module.system.service.policy.PolicySnapshotService;
import com.ontos.module.system.service.request.RequestStateService;
import com.ontos.module.system.service.request.RequestStatus;
import com.ontos.module.system.service.sensitive.SensitiveEventService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;

import static com.ontos.module.ontology.enums.ErrorCodeConstants.MANIFEST_MISMATCH;
import static com.ontos.module.ontology.enums.ErrorCodeConstants.MANIFEST_NOT_FOUND;
import static com.ontos.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.ontos.module.query.enums.ErrorCodeConstants.ARTIFACT_NOT_FOUND;
import static com.ontos.module.query.enums.ErrorCodeConstants.DEPENDENCY_VALUE_AMBIGUOUS;
import static com.ontos.module.query.enums.ErrorCodeConstants.DEPENDENCY_VALUE_MISSING;
import static com.ontos.module.query.enums.ErrorCodeConstants.DSL_INVALID;
import static com.ontos.module.query.enums.ErrorCodeConstants.SKILL_BATCH_LIMIT_EXCEEDED;
import static com.ontos.module.query.enums.ErrorCodeConstants.SKILL_CAPABILITY_MISSING;
import static com.ontos.module.query.enums.ErrorCodeConstants.SKILL_SCENARIO_FORBIDDEN;
import static com.ontos.module.query.enums.ErrorCodeConstants.LOOKUP_NOT_UNIQUE;
import static com.ontos.module.query.enums.ErrorCodeConstants.PLAN_COST_BLOCKED;
import static com.ontos.module.query.enums.ErrorCodeConstants.QUERY_EXECUTION_FAILED;
import static com.ontos.module.query.enums.ErrorCodeConstants.REQUEST_ALREADY_EXECUTED;
import static com.ontos.module.query.enums.ErrorCodeConstants.REQUEST_TERMINAL;
import static com.ontos.module.system.enums.ErrorCodeConstants.SENSITIVE_QUERY_SEQUENCE;

@Slf4j
@Service
public class QueryServiceImpl implements QueryService {

    /** 与 SqlCompiler 的投影别名保持一致，用于批量结果按标识回填。 */
    private static final String IDENTITY_ALIAS = "__identity";

    private final com.ontos.module.query.core.dependency.CompositeDependencyPlanner dependencyPlanner;
    private final ManifestService manifestService;
    private final DslValidator dslValidator;
    private final ScenarioRouteGuard scenarioRouteGuard;
    private final PolicyService policyService;
    private final SqlCompiler sqlCompiler;
    private final QueryExecutor queryExecutor;
    private final AuditService auditService;
    private final RequestStateService requestStateService;
    private final TimeWindowResolver timeWindowResolver;
    private final JoinPathResolver joinPathResolver;
    private final QueryArtifactRepository queryArtifactRepository;
    private final QueryCacheService queryCacheService;
    private final SkillRegistry skillRegistry;
    private final SensitiveEventService sensitiveEventService;
    private final com.ontos.module.system.service.approval.ApprovalService approvalService;

    public QueryServiceImpl(ManifestService manifestService,
                            DslValidator dslValidator,
                            ScenarioRouteGuard scenarioRouteGuard,
                            com.ontos.module.query.core.dependency.CompositeDependencyPlanner dependencyPlanner,
                            PolicyService policyService,
                            SqlCompiler sqlCompiler,
                            QueryExecutor queryExecutor,
                            AuditService auditService,
                            RequestStateService requestStateService,
                            TimeWindowResolver timeWindowResolver,
                            JoinPathResolver joinPathResolver,
                            QueryArtifactRepository queryArtifactRepository,
                            QueryCacheService queryCacheService,
                            SkillRegistry skillRegistry,
                            SensitiveEventService sensitiveEventService,
                            com.ontos.module.system.service.approval.ApprovalService approvalService) {
        this.manifestService = manifestService;
        this.dslValidator = dslValidator;
        this.scenarioRouteGuard = scenarioRouteGuard;
        this.dependencyPlanner = dependencyPlanner;
        this.policyService = policyService;
        this.sqlCompiler = sqlCompiler;
        this.queryExecutor = queryExecutor;
        this.auditService = auditService;
        this.requestStateService = requestStateService;
        this.timeWindowResolver = timeWindowResolver;
        this.joinPathResolver = joinPathResolver;
        this.queryArtifactRepository = queryArtifactRepository;
        this.queryCacheService = queryCacheService;
        this.skillRegistry = skillRegistry;
        this.sensitiveEventService = sensitiveEventService;
        this.approvalService = approvalService;
    }

    @Override
    public CompiledQuery compile(QueryDsl queryDsl) {
        RequestContext context = RequestContextHolder.require();
        String requestId = context.traceId();
        beginRequest(context, requestId);
        try {
            PublishedManifest manifest = prepare(queryDsl, context);
            CompiledQuery compiled = sqlCompiler.compile(queryDsl, manifest);
            // T4.5：编译产物先落库再推进状态，保证回放端点能取到「最近一次」产物
            persistArtifact(context, context.traceId(), compiled, null);
            requestStateService.transition(context, requestId, RequestStatus.PROPOSED, "编译完成（dry-plan）");
            auditService.record(buildAudit(context, "COMPILE", queryDsl, compiled, null, manifest));
            return compiled;
        } catch (ServiceException ex) {
            // 与 execute 对齐：fail-closed 拒绝也是合法终态，不能让 compile 分支停在初始态
            safeTransition(context, requestId, RequestStatus.REJECTED, truncate(ex.getMessage()));
            throw ex;
        } catch (RuntimeException ex) {
            safeTransition(context, requestId, RequestStatus.FAILED, truncate(String.valueOf(ex)));
            throw ex;
        }
    }

    @Override
    public QueryResult execute(QueryDsl queryDsl) {
        RequestContext context = RequestContextHolder.require();
        String requestId = context.traceId();
        // 幂等（ADR-0004 2.2）：request_id 是幂等键，已处于终态一律不接受重放
        beginRequest(context, requestId);
        try {
            Map<String, java.util.Set<String>> sensitivity = new LinkedHashMap<>();
            PublishedManifest manifest = prepare(queryDsl, context, sensitivity);
            // T4.4：命中即跳过编译与执行；但 prepare 一定先跑——授权与校验不能因为缓存被跳过
            String cacheKey = cacheKeyOf(context, manifest, queryDsl);
            if (cacheKey != null) {
                Optional<QueryResult> cached = queryCacheService.get(cacheKey);
                if (cached.isPresent()) {
                    // W4 溯源回放：命中路径此前不落编译产物，/plan/{requestId} 回放与溯源抽屉
                    // 对命中轮一律 404。编译是 (dsl, manifest) 的纯函数（纯内存、毫秒级），
                    // 这里补一次编译仅为落产物（不执行）——T4.4「跳过编译」的性能意图不受影响。
                    CompiledQuery hitCompiled = sqlCompiler.compile(queryDsl, manifest);
                    persistArtifact(context, context.traceId(), hitCompiled, null);
                    // 缓存命中同样记账并判定（M4/W2，§4.8「每请求一行」字面口径）：
                    // 命中返回数据本身就是一次敏感访问；拒答档触发后的「临时冻结」必须覆盖命中路径，
                    // 否则同参重复请求会绕过冻结拿到旧结果（E2E 实测暴露）。触发过记账的请求
                    // 不会入缓存（触发分支先于缓存回写），因此命中只发生在「历史已放行」的参数上。
                    QueryResult hit = cached.get();
                    SensitiveEventService.SensitiveDecision hitDecision = recordSensitiveEvents(
                            context, queryDsl, sensitivity, manifest, hit.getRowCount());
                    if (hitDecision.triggered() && !consumeGrantOrReject(context, hitDecision)) {
                        AuditLogCreateReqDTO hitAudit = buildCacheHitAudit(context, queryDsl,
                                manifest, hit.getRowCount(), hitDecision.auditReasonCode());
                        hitAudit.setApprovalId(hitDecision.approvalId());
                        hitAudit.setSensitiveCountersDelta(hitDecision.countersDelta());
                        auditService.record(hitAudit);
                        requestStateService.transition(context, requestId, RequestStatus.REJECTED,
                                "敏感累计触发（缓存命中路径）");
                        throw exception(SENSITIVE_QUERY_SEQUENCE, hitDecision.approvalId());
                    }
                    if (hitDecision.triggered()) {
                        auditService.record(buildCacheHitAudit(context, queryDsl, manifest,
                                hit.getRowCount(), "SENSITIVE_APPROVED_GRANT",
                                hitDecision.approvalId(), hitDecision.countersDelta()));
                    }
                    requestStateService.transition(context, requestId, RequestStatus.PROPOSED, "缓存命中");
                    requestStateService.transition(context, requestId, RequestStatus.EXECUTED,
                            "执行完成（缓存命中）");
                    requestStateService.recordRowCount(requestId, hit.getRowCount());
                    auditCacheHit(context, queryDsl, manifest, hit);
                    return hit;
                }
            }
            CompiledQuery compiled = sqlCompiler.compile(queryDsl, manifest);
            persistArtifact(context, context.traceId(), compiled, null);
            requestStateService.transition(context, requestId, RequestStatus.PROPOSED, "编译完成");
            QueryResult result = queryExecutor.execute(compiled, context);
            assembleLookupRows(queryDsl, manifest, compiled, result);
            // M4/W2（规格 §3.2）：执行成功后同事务记账 + 窗口判定；触发即不放行结果、不回写缓存。
            // EXECUTED 是终态（M4 收口审核 G-4）：先判定后转移——触发拒绝走 PROPOSED→REJECTED
            // 合法迁移，放行/未触发才置 EXECUTED，避免「结果未交付却停在 EXECUTED」的回放假象
            SensitiveEventService.SensitiveDecision decision = recordSensitiveEvents(
                    context, queryDsl, sensitivity, manifest, result.getRowCount());
            SensitiveEventService.SensitiveDecision sensitiveAudit = null;
            if (decision.triggered() && !consumeGrantOrReject(context, decision)) {
                // 触发请求全量审计（细分分支码只在审计，对外统一 SENSITIVE_QUERY_SEQUENCE）；
                // 状态 REJECTED 交由下方 catch 统一转移，避免重复迁移
                AuditLogCreateReqDTO triggerAudit = buildAudit(context, "EXECUTE", queryDsl,
                        compiled, result.getRowCount(), manifest);
                triggerAudit.setReasonCode(decision.auditReasonCode());
                triggerAudit.setApprovalId(decision.approvalId());
                triggerAudit.setSensitiveCountersDelta(decision.countersDelta());
                auditService.record(triggerAudit);
                throw exception(SENSITIVE_QUERY_SEQUENCE, decision.approvalId());
            }
            // 放行（含令牌放行）与未触发：此时才置终态 EXECUTED 并记录交付行数
            requestStateService.transition(context, requestId, RequestStatus.EXECUTED, "执行完成");
            requestStateService.recordRowCount(requestId, result.getRowCount());
            if (decision.triggered()) {
                // 令牌放行（T2.3）：审计行关联审批单，回放时可追到放行依据（W3/T3.2）
                AuditLogCreateReqDTO grantAudit = buildAudit(context, "EXECUTE", queryDsl,
                        compiled, result.getRowCount(), manifest);
                grantAudit.setReasonCode("SENSITIVE_APPROVED_GRANT");
                grantAudit.setApprovalId(decision.approvalId());
                grantAudit.setSensitiveCountersDelta(decision.countersDelta());
                auditService.record(grantAudit);
                if (cacheKey != null) {
                    queryCacheService.put(cacheKey, manifest.getManifestId(),
                            manifest.getContentHash(), String.valueOf(queryDsl.getMode()), result);
                }
                return result;
            }
            if (decision.countersDelta() != null) {
                sensitiveAudit = decision;
            }
            AuditLogCreateReqDTO audit = buildAudit(context, "EXECUTE", queryDsl, compiled,
                    result.getRowCount(), manifest);
            if (cacheKey != null) {
                audit.setReasonCode("CACHE_MISS");
            }
            if (sensitiveAudit != null) {
                // 计数器哈希化增量（W3/T3.2）：未触发的敏感访问同样留审计增量
                audit.setSensitiveCountersDelta(sensitiveAudit.countersDelta());
            }
            auditService.record(audit);
            if (cacheKey != null) {
                // 回写放在响应之前、但失败只降级 WARN（ADR-0008 决策 3）
                queryCacheService.put(cacheKey, manifest.getManifestId(), manifest.getContentHash(),
                        String.valueOf(queryDsl.getMode()), result);
            }
            return result;
        } catch (ServiceException ex) {
            // fail-closed 类拒绝（清单/策略/DSL 校验）：REJECTED 是合法终态
            safeTransition(context, requestId, RequestStatus.REJECTED, truncate(ex.getMessage()));
            throw ex;
        } catch (RuntimeException ex) {
            safeTransition(context, requestId, RequestStatus.FAILED, truncate(String.valueOf(ex)));
            throw ex;
        }
    }

    /**
     * M4/W2 T2.3 审批放行令牌（规格 §3.3）：请求携 X-Ontos-Approval 且指纹逐字段匹配
     * （user|tenant|sensitive_key|scope|filters）时单次消费放行；无令牌 / 指纹不符 /
     * 已消费 / 换 filters 一律拒绝——同一用户对同一敏感键的不同查询不自动放行。
     */
    private boolean consumeGrantOrReject(RequestContext context,
                                         SensitiveEventService.SensitiveDecision decision) {
        if (context.approvalId() == null || context.approvalId().isBlank()) {
            return false;
        }
        return approvalService.consumeGrant(context.tenantId(), context.userId(),
                context.approvalId(), decision.grantFingerprint());
    }

    /**
     * M4/W2 敏感记账：把收集到的敏感层展开为事件草稿（每个适用层一行，叠加 existence），
     * 交给 {@link SensitiveEventService} 在单事务内完成「记账 + 差分/个体检测 + 窗口判定」。
     * 收集器为空（全 L0）或展开后为空时不产生任何写入。
     */
    private SensitiveEventService.SensitiveDecision recordSensitiveEvents(
            RequestContext context, QueryDsl queryDsl, Map<String, java.util.Set<String>> sensitivity,
            PublishedManifest manifest, int rowCount) {
        if (sensitivity == null || sensitivity.isEmpty()) {
            return SensitiveEventService.SensitiveDecision.notTriggered();
        }
        String canonical = filtersCanonical(queryDsl);
        String filtersHash = sha256Hex(canonical);
        List<SensitiveEventService.DraftEvent> drafts = new ArrayList<>();
        for (Map.Entry<String, java.util.Set<String>> entry : sensitivity.entrySet()) {
            for (String layer : entry.getValue()) {
                drafts.add(new SensitiveEventService.DraftEvent(entry.getKey(), layer,
                        filtersHash, canonical, rowCount));
            }
        }
        if (drafts.isEmpty()) {
            return SensitiveEventService.SensitiveDecision.notTriggered();
        }
        return sensitiveEventService.recordAndEvaluate(context, context.traceId(), drafts);
    }

    /**
     * filters 规范化序列化（ADR-0002 差分判定的比对基准）：(field|operator|value) 三元组
     * 去重排序后按换行拼接；时间窗按 BETWEEN 三元组并入（时间窗收窄同样是收窄攻击面）；
     * lookup 身份条件同样并入（每值一个三元组，与 filters 的 IN 展开同构）——
     * LOOKUP_BATCH 的 identities 缩放正是差分攻击的主通道，不能游离在比对基准之外。
     * 空过滤 = 空串（空集哈希）。
     */
    static String filtersCanonical(QueryDsl queryDsl) {
        TreeSet<String> triples = new TreeSet<>();
        if (queryDsl.getFilters() != null) {
            for (QueryDsl.Filter filter : queryDsl.getFilters()) {
                if (filter.getFieldId() == null || filter.getOperator() == null) {
                    continue;
                }
                for (String value : filter.getValues()) {
                    triples.add(filter.getFieldId() + "|" + filter.getOperator().name() + "|" + value);
                }
            }
        }
        QueryDsl.TimeRange range = queryDsl.getTimeRange();
        if (range != null && range.getField() != null) {
            triples.add(range.getField() + "|BETWEEN|" + range.getFrom() + ".." + range.getTo());
        }
        // lookup 身份条件（每值一个三元组，保证「identities 集合差恰为 1」可判定）
        if (queryDsl.getIdentity() != null && queryDsl.getIdentity().getPropertyId() != null) {
            triples.add(queryDsl.getIdentity().getPropertyId() + "|EQ|"
                    + queryDsl.getIdentity().getValue());
        }
        if (queryDsl.getIdentityPropertyId() != null && queryDsl.getIdentities() != null) {
            for (String value : queryDsl.getIdentities()) {
                triples.add(queryDsl.getIdentityPropertyId() + "|IN|" + value);
            }
        }
        return String.join("\n", triples);
    }

    static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    /**
     * 能力调用（M3/W3）：能力层只负责「槽位 → 类型化 DSL」，资产存在性、授权、时间窗等
     * 全部走 {@link #execute} 的统一闸门；manifestId 由服务层注入当前生效清单，
     * 调用方不感知清单版本。能力来源以 SKILL_EXECUTE 审计行单独留痕。
     */
    @Override
    public QueryResult executeSkill(String skillId, Map<String, Object> slots) {
        RequestContext context = RequestContextHolder.require();
        PublishedManifest active = manifestService.getActiveManifestOrNull();
        if (active == null) {
            // 没有生效清单时业务必然不可用：fail-closed，与 validateManifest 的空态口径一致
            throw exception(MANIFEST_NOT_FOUND);
        }
        // T32-6 修复：清单**没有** skills 段（v13 及更早存量清单）= M3 直挂兼容态，
        // 按 M3 行为执行（闸门随清单声明走——无声明即无约束）；有 skills 段才进入严格注册制。
        boolean registrationMode = skillRegistry.isRegistrationMode(active);
        ManifestPayload.SkillMeta meta = registrationMode
                ? skillRegistry.manifestSkill(skillId)
                        .orElseThrow(() -> exception(DSL_INVALID, "未知能力: " + skillId))
                : new ManifestPayload.SkillMeta();
        if (!registrationMode) {
            SkillHandler legacyHandler = skillRegistry.legacyById(skillId)
                    .orElseThrow(() -> exception(DSL_INVALID, "未知能力: " + skillId));
            QueryDsl legacyDsl = legacyHandler.build(slots == null ? Map.of() : slots,
                    active.getManifestId());
            auditSkill(context, skillId, slots);
            return execute(legacyDsl);
        }
        // M6/W3/T3.2 编译期校验（注册制四条，全部先于 handler 解析——fail-closed 不泄露存在性）：
        // 未发布 / 不存在 / 注册但无内置映射同码拒绝；兼容态 meta 为空壳（闸门全放行 = M3 行为）
        // ① required_capabilities ⊆ 受控工具注册表满足集
        Set<String> missing = ControlledCapabilities.unsatisfiedOf(meta, active);
        if (!missing.isEmpty()) {
            throw exception(SKILL_CAPABILITY_MISSING, skillId, String.join(", ", missing));
        }
        // ② allowed_scenario_ids：空 = 全局可用；非空 = 当前会话必须在列（pending/free 视为不在）
        List<String> allowedScenarios = meta.getAllowedScenarioIds();
        if (allowedScenarios != null && !allowedScenarios.isEmpty()) {
            String currentScenario = scenarioRouteGuard.currentScenarioId(context);
            if (currentScenario == null || !allowedScenarios.contains(currentScenario)) {
                throw exception(SKILL_SCENARIO_FORBIDDEN, skillId, String.join(", ", allowedScenarios));
            }
        }
        // ③ rule.evaluate 能力随 ① 按受控注册表判定（Rule 注册中心可用且规则已发布才满足）
        SkillHandler handler = skillRegistry.byId(skillId)
                .orElseThrow(() -> exception(DSL_INVALID, "未知能力: " + skillId));
        QueryDsl dsl = handler.build(slots == null ? Map.of() : slots, active.getManifestId());
        // ④ max_batch_size 必须被 DSL 批量参数遵守（清单声明是硬上界）
        if (meta.getMaxBatchSize() != null && dsl.getLimit() != null
                && dsl.getLimit() > meta.getMaxBatchSize()) {
            throw exception(SKILL_BATCH_LIMIT_EXCEEDED, dsl.getLimit(), meta.getMaxBatchSize());
        }
        auditSkill(context, skillId, slots);
        return execute(dsl);
    }

    /** 近似降级执行（T3.3）：闸门同 executeSkill，审计 action=MODE_APPROXIMATED + originalMode。 */
    @Override
    public QueryResult executeSkillApproximation(String skillId, Map<String, Object> slots,
                                                 String originalMode) {
        RequestContext context = RequestContextHolder.require();
        PublishedManifest active = manifestService.getActiveManifestOrNull();
        if (active == null) {
            throw exception(MANIFEST_NOT_FOUND);
        }
        ManifestPayload.SkillMeta meta = skillRegistry.manifestSkill(skillId)
                .orElseThrow(() -> exception(DSL_INVALID, "未知能力: " + skillId));
        Set<String> missing = ControlledCapabilities.unsatisfiedOf(meta, active);
        if (!missing.isEmpty()) {
            throw exception(SKILL_CAPABILITY_MISSING, skillId, String.join(", ", missing));
        }
        List<String> allowedScenarios = meta.getAllowedScenarioIds();
        if (allowedScenarios != null && !allowedScenarios.isEmpty()) {
            String currentScenario = scenarioRouteGuard.currentScenarioId(context);
            if (currentScenario == null || !allowedScenarios.contains(currentScenario)) {
                throw exception(SKILL_SCENARIO_FORBIDDEN, skillId, String.join(", ", allowedScenarios));
            }
        }
        SkillHandler handler = skillRegistry.byId(skillId)
                .orElseThrow(() -> exception(DSL_INVALID, "未知能力: " + skillId));
        QueryDsl dsl = handler.build(slots == null ? Map.of() : slots, active.getManifestId());
        if (meta.getMaxBatchSize() != null && dsl.getLimit() != null
                && dsl.getLimit() > meta.getMaxBatchSize()) {
            throw exception(SKILL_BATCH_LIMIT_EXCEEDED, dsl.getLimit(), meta.getMaxBatchSize());
        }
        // 近似审计：独立于 SKILL_EXECUTE——回答「这次查询是用户同意的近似降级、原请求是什么模式」
        AuditLogCreateReqDTO audit = new AuditLogCreateReqDTO();
        audit.setRequestId(context.traceId());
        audit.setTenantId(context.tenantId());
        audit.setUserId(context.userId());
        audit.setSessionId(context.sessionId());
        audit.setTurnIndex(context.turnIndex());
        audit.setAction("MODE_APPROXIMATED");
        audit.setSkillId(skillId);
        audit.setSkillVersion(meta.getVersion());
        audit.setApproximatedFromMode(String.valueOf(originalMode));
        Map<String, Object> auditBody = new java.util.LinkedHashMap<>();
        auditBody.put("skillId", skillId);
        auditBody.put("originalMode", String.valueOf(originalMode));
        auditBody.put("slots", slots == null ? Map.of() : slots);
        audit.setDslJson(JsonUtils.toJsonString(auditBody));
        audit.setTraceId(context.traceId());
        audit.setDevMode(context.devMode());
        auditService.record(audit);
        return execute(dsl);
    }

    /** 模板占位符：${param.x}。 */
    private static final java.util.regex.Pattern PARAM_PLACEHOLDER =
            java.util.regex.Pattern.compile("\\$\\{param\\.([A-Za-z0-9_]+)}");

    /**
     * VQR 命中执行（T3.5，§10.3/§11.4）：published VQR 模板绑定参数 → 组装 DSL →
     * 既有 execute 全链（八步重校验天然覆盖：重绑版本/授权/模式/口径/编译/RLS/成本）。
     * 不保存绕过权限的最终 SQL——模板只含资产 ID 与参数占位符；绑定产物仍过全部校验。
     */
    @Override
    public QueryResult executeVqr(String vqrId, Map<String, Object> params) {
        RequestContext context = RequestContextHolder.require();
        PublishedManifest active = manifestService.getActiveManifestOrNull();
        if (active == null) {
            throw exception(MANIFEST_NOT_FOUND);
        }
        // 未知 / 未发布同形拒绝（fail-closed 不泄露存在性；注册制语义与 SkillRegistry 一致）
        ManifestPayload.VqrMeta meta = active.getVqrs() == null ? null
                : active.getVqrs().stream()
                        .filter(vqr -> vqrId.equals(vqr.getVqrId()))
                        .findFirst().orElse(null);
        if (meta == null || !"published".equals(meta.getStatus())) {
            throw exception(DSL_INVALID, "未知能力: " + vqrId);
        }
        Map<String, Object> template = meta.getDslTemplate() == null
                ? Map.of() : meta.getDslTemplate();
        QueryDsl dsl = bindTemplate(vqrId, meta, template, params, active.getManifestId());
        auditVqrHit(context, vqrId, meta.getVersion(), params);
        return execute(dsl);
    }

    /**
     * 模板绑定：占位符替换 + 资产 ID 直取。绑定失败（required 参数缺失 / 未知参数 /
     * 未知槽位 / mode 缺失）结构化拒绝——绑定期不做语义校验，DSL 校验由 execute 全链承担。
     */
    private QueryDsl bindTemplate(String vqrId, ManifestPayload.VqrMeta meta,
                                  Map<String, Object> template, Map<String, Object> params,
                                  String manifestId) {
        Map<String, Object> input = params == null ? Map.of() : params;
        // 未知参数拒绝（params 声明之外 = 拼写错误或越权注入）
        java.util.Set<String> declared = meta.getParamsSchema() == null
                ? java.util.Set.of() : meta.getParamsSchema().keySet();
        for (String key : input.keySet()) {
            if (!declared.contains(key)) {
                throw exception(DSL_INVALID, "VQR 参数未在 paramsSchema 声明: " + key);
            }
        }
        // M7/W4/T4.4（D-22）：paramsSchema 的 type / required / minimum / maximum 在绑定期校验，
        // 不依赖下游 TimeWindowResolver 兜底；越界/类型不符 fail-closed（DSL_INVALID）。
        validateParamSchema(meta.getParamsSchema(), input);
        QueryDsl dsl = new QueryDsl();
        dsl.setManifestId(manifestId);
        dsl.setVqrId(vqrId);
        dsl.setVqrVersion(meta.getVersion());
        for (Map.Entry<String, Object> entry : template.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String text) {
                value = substitute(entry.getKey(), text, input, declared);
            } else if (value instanceof List<?> list) {
                List<Object> bound = new java.util.ArrayList<>();
                for (Object item : list) {
                    bound.add(item instanceof String text
                            ? substitute(entry.getKey(), text, input, declared) : item);
                }
                value = bound;
            }
            applyTemplateField(dsl, entry.getKey(), value);
        }
        if (dsl.getMode() == null) {
            throw exception(DSL_INVALID, "VQR 模板缺少 mode: " + vqrId);
        }
        return dsl;
    }

    /**
     * 参数 Schema 运行时校验（M7/W4/T4.4，D-22）：type / required / minimum / maximum。
     *
     * <p>只校验 paramsSchema 已声明的键；数值接受 Number 或可解析数字字符串（HTTP/JSON 参数常见形态），
     * 类型不符或越界一律 fail-closed（DSL_INVALID，不新增错误码、不静默截断）。
     */
    private void validateParamSchema(Map<String, Object> schema, Map<String, Object> input) {
        if (schema == null || schema.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : schema.entrySet()) {
            String name = entry.getKey();
            if (!(entry.getValue() instanceof Map<?, ?> rule)) {
                continue;
            }
            Object value = input.get(name);
            if (value == null) {
                if (Boolean.TRUE.equals(rule.get("required"))) {
                    throw exception(DSL_INVALID, "VQR 必填参数缺失: " + name);
                }
                continue;
            }
            Object typeValue = rule.get("type");
            String type = typeValue == null ? null : String.valueOf(typeValue);
            if (type != null && !matchesParamType(type, value)) {
                throw exception(DSL_INVALID, "VQR 参数类型不符: " + name + " 期望 " + type);
            }
            BigDecimal numeric = numericValue(value);
            if (numeric == null) {
                continue;
            }
            Object minimum = rule.get("minimum");
            Object maximum = rule.get("maximum");
            if (minimum instanceof Number min
                    && numeric.compareTo(new BigDecimal(min.toString())) < 0) {
                throw exception(DSL_INVALID, "VQR 参数小于最小值: " + name + " minimum=" + min);
            }
            if (maximum instanceof Number max
                    && numeric.compareTo(new BigDecimal(max.toString())) > 0) {
                throw exception(DSL_INVALID, "VQR 参数大于最大值: " + name + " maximum=" + max);
            }
        }
    }

    private static boolean matchesParamType(String type, Object value) {
        BigDecimal numeric = numericValue(value);
        return switch (type) {
            case "integer" -> numeric != null && numeric.stripTrailingZeros().scale() <= 0;
            case "number" -> numeric != null;
            case "string" -> value instanceof String;
            case "boolean" -> value instanceof Boolean
                    || (value instanceof String text
                    && ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text)));
            // 未知类型声明不在运行时猜测（发布期已由 VqrValidator 约束 schema 形态）
            default -> true;
        };
    }

    private static BigDecimal numericValue(Object value) {
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        if (value instanceof String text && text.matches("-?\\d+(\\.\\d+)?")) {
            return new BigDecimal(text);
        }
        return null;
    }

    /** 单值替换：${param.x} → 参数值；required 参数缺失即拒绝（不注入空值）。 */
    private String substitute(String field, String text, Map<String, Object> params,
                              java.util.Set<String> declared) {
        Matcher matcher = PARAM_PLACEHOLDER.matcher(text);
        StringBuilder result = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            String name = matcher.group(1);
            Object value = params.get(name);
            if (value == null) {
                if (!declared.contains(name)) {
                    throw exception(DSL_INVALID, "VQR 占位符未声明: " + field);
                }
                throw exception(DSL_INVALID, "VQR 必填参数缺失: " + name);
            }
            result.append(text, last, matcher.start()).append(value);
            last = matcher.end();
        }
        result.append(text.substring(last));
        return result.toString();
    }

    /** 模板字段 → DSL（词表与 ask 提案一致：mode/metric_id/dimensions/time_expression_raw/limit）。 */
    private void applyTemplateField(QueryDsl dsl, String key, Object value) {
        switch (key) {
            case "mode" -> {
                if (value instanceof String mode && !mode.isBlank()) {
                    dsl.setMode(QueryDsl.AnalysisMode.valueOf(mode.trim().toUpperCase()));
                }
            }
            case "metric_id" -> {
                if (value instanceof String metric && !metric.isBlank()) {
                    dsl.setMetricId(metric);
                }
            }
            case "dimensions" -> {
                if (value instanceof List<?> dimensions) {
                    dimensions.forEach(dimension -> {
                        if (dimension instanceof String id && !id.isBlank()) {
                            dsl.getDimensions().add(id);
                        }
                    });
                }
            }
            case "time_expression_raw" -> {
                if (value instanceof String time && !time.isBlank()) {
                    dsl.setTimeExpressionRaw(time);
                }
            }
            case "time_dimension" -> {
                // 模板声明的时间维度资产 ID → timeRange.field（query 模式相对时间解析的前置）
                if (value instanceof String field && !field.isBlank()) {
                    QueryDsl.TimeRange timeRange = dsl.getTimeRange() == null
                            ? new QueryDsl.TimeRange() : dsl.getTimeRange();
                    timeRange.setField(field);
                    dsl.setTimeRange(timeRange);
                }
            }
            case "limit" -> {
                if (value instanceof Number number) {
                    dsl.setLimit(number.intValue());
                }
            }
            default -> {
                // 模板未知字段：清单结构校验（VqrValidator）不限制额外键——绑定期忽略，
                // 语义校验由 execute 全链承担（未知资产引用会被 manifest 校验拒绝）
            }
        }
    }

    /** VQR 命中审计：独立于 EXECUTE/SKILL_EXECUTE——回答「这次查询由哪个模板、什么版本、什么参数发起」。 */
    private void auditVqrHit(RequestContext context, String vqrId, String version,
                             Map<String, Object> params) {
        AuditLogCreateReqDTO audit = new AuditLogCreateReqDTO();
        audit.setRequestId(context.traceId());
        audit.setTenantId(context.tenantId());
        audit.setUserId(context.userId());
        audit.setSessionId(context.sessionId());
        audit.setTurnIndex(context.turnIndex());
        audit.setAction("VQR_HIT");
        // 专用列（V15）：运营可直接按 vqr_id 统计命中，无需解析 dsl JSON
        audit.setVqrId(vqrId);
        audit.setVqrVersion(version);
        Map<String, Object> auditBody = new java.util.LinkedHashMap<>();
        auditBody.put("vqrId", vqrId);
        auditBody.put("vqrVersion", version);
        auditBody.put("params", params == null ? Map.of() : params);
        audit.setDslJson(JsonUtils.toJsonString(auditBody));
        audit.setTraceId(context.traceId());
        audit.setDevMode(context.devMode());
        auditService.record(audit);
    }

    /** 能力来源审计：与 EXECUTE 行分开，回答「这次查询是由哪个能力、带什么槽位发起的」。 */
    private void auditSkill(RequestContext context, String skillId, Map<String, Object> slots) {
        AuditLogCreateReqDTO audit = new AuditLogCreateReqDTO();
        audit.setRequestId(context.traceId());
        audit.setTenantId(context.tenantId());
        audit.setUserId(context.userId());
        audit.setSessionId(context.sessionId());
        audit.setTurnIndex(context.turnIndex());
        audit.setAction("SKILL_EXECUTE");
        audit.setSkillId(skillId);
        // 版本取自已发布清单的 skills 段；查不到留空（不猜版本）
        skillRegistry.manifestSkill(skillId).ifPresent(meta -> audit.setSkillVersion(meta.getVersion()));
        audit.setDslJson(JsonUtils.toJsonString(Map.of("skillId", skillId, "slots",
                slots == null ? Map.of() : slots)));
        audit.setTraceId(context.traceId());
        audit.setDevMode(context.devMode());
        auditService.record(audit);
    }

    /**
     * 复合意图执行（M2/W4/T4.3，无依赖子集）。
     *
     * <p>容器本身不产生 SQL：逐子意图独立走「时间换算 → 路径规划 → 结构校验 → 策略授权 →
     * 编译」，全部通过后才执行，每子一份编译产物、一份审计行（带 parent_request_id +
     * sub_intent_id），结果各自隔离返回，不合并 SQL、不共享中间结果。
     *
     * <p>先校验编译、后统一执行（两阶段）：任何一个子意图结构/授权不合格时，
     * 不会出现「前面的子已经查过库、后面的子才被拒」的部分执行；失败仍是整单拒绝
     * （AGENTS.md §4.2 不返回部分结果），「部分失败可解释」归 M6。
     * 拒绝时先写一条 SUB_INTENT_REJECTED 审计，把失败定位钉在具体子意图上。
     */
    @Override
    public CompositeOutcome executeComposite(QueryDsl queryDsl) {
        RequestContext context = RequestContextHolder.require();
        String parentRequestId = context.traceId();
        beginRequest(context, parentRequestId);
        try {
            checkManifestHeader(queryDsl, context);
            PublishedManifest manifest = manifestService.validateManifest(queryDsl.getManifestId());
            dslValidator.validateComposite(queryDsl, manifest);
            requestStateService.transition(context, parentRequestId, RequestStatus.PROPOSED,
                    "复合意图结构校验通过");

            // 依赖路径判定（T4.1）：无依赖复合请求保持 M2 两阶段原路径（回归零变化）；
            // 有依赖请求按拓扑序逐子「校验→编译→执行→注入」——依赖子的取值必须来自
            // 父结果，无法先全量编译（阶段一原子性只对无依赖形状成立）。
            if (!dependencyPlanner.hasDependencies(queryDsl.getSubIntents())) {
                return executeCompositeIndependent(queryDsl, manifest, context, parentRequestId);
            }
            List<QueryDsl.SubIntent> ordered =
                    dependencyPlanner.topologicalOrder(queryDsl.getSubIntents());
            List<CompositeOutcome.SubResult> results = new ArrayList<>();
            Map<String, QueryResult> resultsBySubIntentId = new LinkedHashMap<>();
            int totalRowCount = 0;
            for (QueryDsl.SubIntent sub : ordered) {
                QueryDsl child = toChildDsl(queryDsl, sub);
                try {
                    if (sub.getResultRef() != null) {
                        // T4.2 结果注入（规格 composite-resultRef v1 §3/§4）：父结果取值 →
                        // filter_value 注入；取值失败（G-6）结构化失败该子，不注入空值，
                        // 不阻断其它子（部分失败可解释）
                        injectDependencyValue(sub, child, resultsBySubIntentId);
                    }
                    Map<String, java.util.Set<String>> subSensitivity = new LinkedHashMap<>();
                    prepareWith(child, context, manifest, subSensitivity);
                    CompiledQuery compiled = sqlCompiler.compile(child, manifest);
                    persistArtifact(context, childRequestId(parentRequestId, sub.getSubIntentId()),
                            compiled, null);
                    QueryResult result = queryExecutor.execute(compiled, context);
                    assembleLookupRows(child, manifest, compiled, result);
                    SensitiveEventService.SensitiveDecision decision = recordSensitiveEvents(
                            context, child, subSensitivity, manifest, result.getRowCount());
                    if (decision.triggered()) {
                        auditSubIntent(context, parentRequestId, sub, child,
                                compiled, result.getRowCount(),
                                SENSITIVE_QUERY_SEQUENCE.getCode());
                        throw exception(SENSITIVE_QUERY_SEQUENCE, decision.approvalId());
                    }
                    totalRowCount += result.getRowCount();
                    results.add(new CompositeOutcome.SubResult(sub.getSubIntentId(), result));
                    resultsBySubIntentId.put(sub.getSubIntentId(), result);
                    auditSubIntent(context, parentRequestId, sub, child,
                            compiled, result.getRowCount(), null);
                } catch (ServiceException ex) {
                    auditSubIntent(context, parentRequestId, sub, child, null, null, ex.getCode());
                    // G-6 / 注入失败：该子结构化失败（带原因码的空结果），不阻断其它子
                    results.add(new CompositeOutcome.SubResult(sub.getSubIntentId(), null,
                            ex.getCode(), truncate(ex.getMessage())));
                } catch (RuntimeException ex) {
                    // M7/W4/T4.4（D-22）：非业务异常不得整单 500；结构化失败 + 完整堆栈进日志，
                    // 对外只给通用执行失败码（不泄露内部细节，也不把 NPE 当业务拒绝）。
                    log.error("[composite][sub({}) 运行时失败]", sub.getSubIntentId(), ex);
                    auditSubIntent(context, parentRequestId, sub, child, null, null,
                            QUERY_EXECUTION_FAILED.getCode());
                    results.add(new CompositeOutcome.SubResult(sub.getSubIntentId(), null,
                            QUERY_EXECUTION_FAILED.getCode(), "子查询执行失败（内部原因已记录）"));
                }
            }
            requestStateService.transition(context, parentRequestId, RequestStatus.EXECUTED,
                    "复合意图执行完成");
            requestStateService.recordRowCount(parentRequestId, totalRowCount);
            auditComposite(context, queryDsl, manifest, totalRowCount);
            return new CompositeOutcome(parentRequestId, totalRowCount, results);
        } catch (ServiceException ex) {
            safeTransition(context, parentRequestId, RequestStatus.REJECTED, truncate(ex.getMessage()));
            throw ex;
        } catch (RuntimeException ex) {
            safeTransition(context, parentRequestId, RequestStatus.FAILED, truncate(String.valueOf(ex)));
            throw ex;
        }
    }

    /**
     * 无依赖复合请求（T4.3：部分失败可解释——单子失败不阻断其它子返回）。
     * 逐子「prepare → 编译 → 执行 → 记账」单循环；任一 ServiceException 该子结构化失败
     * （errorCode/message + result=null），失败子不产出部分结果、审计照落，其余子照常。
     * （M2 的「阶段一全编译 + 整单拒绝」原子性由规格 §3.5 部分失败语义取代——M6 行为。）
     */
    private CompositeOutcome executeCompositeIndependent(QueryDsl queryDsl,
                                                          PublishedManifest manifest,
                                                          RequestContext context,
                                                          String parentRequestId) {
        List<CompositeOutcome.SubResult> results = new ArrayList<>();
        int totalRowCount = 0;
        for (QueryDsl.SubIntent sub : queryDsl.getSubIntents()) {
            QueryDsl child = toChildDsl(queryDsl, sub);
            try {
                Map<String, java.util.Set<String>> subSensitivity = new LinkedHashMap<>();
                prepareWith(child, context, manifest, subSensitivity);
                CompiledQuery compiled = sqlCompiler.compile(child, manifest);
                persistArtifact(context, childRequestId(parentRequestId, sub.getSubIntentId()),
                        compiled, null);
                QueryResult result = queryExecutor.execute(compiled, context);
                assembleLookupRows(child, manifest, compiled, result);
                SensitiveEventService.SensitiveDecision decision = recordSensitiveEvents(
                        context, child, subSensitivity, manifest, result.getRowCount());
                if (decision.triggered()) {
                    auditSubIntent(context, parentRequestId, sub, child,
                            compiled, result.getRowCount(),
                            SENSITIVE_QUERY_SEQUENCE.getCode());
                    throw exception(SENSITIVE_QUERY_SEQUENCE, decision.approvalId());
                }
                totalRowCount += result.getRowCount();
                results.add(new CompositeOutcome.SubResult(sub.getSubIntentId(), result));
                auditSubIntent(context, parentRequestId, sub, child,
                        compiled, result.getRowCount(), null);
            } catch (ServiceException ex) {
                auditSubIntent(context, parentRequestId, sub, child, null, null, ex.getCode());
                results.add(new CompositeOutcome.SubResult(sub.getSubIntentId(), null,
                        ex.getCode(), truncate(ex.getMessage())));
            } catch (RuntimeException ex) {
                // M7/W4/T4.4（D-22）：同依赖复合分支——子级运行时失败结构化隔离，父请求不整体 500。
                log.error("[composite][sub({}) 运行时失败]", sub.getSubIntentId(), ex);
                auditSubIntent(context, parentRequestId, sub, child, null, null,
                        QUERY_EXECUTION_FAILED.getCode());
                results.add(new CompositeOutcome.SubResult(sub.getSubIntentId(), null,
                        QUERY_EXECUTION_FAILED.getCode(), "子查询执行失败（内部原因已记录）"));
            }
        }
        requestStateService.transition(context, parentRequestId, RequestStatus.EXECUTED,
                "复合意图执行完成");
        requestStateService.recordRowCount(parentRequestId, totalRowCount);
        auditComposite(context, queryDsl, manifest, totalRowCount);
        return new CompositeOutcome(parentRequestId, totalRowCount, results);
    }

    /**
     * 结果注入（T4.2，规格 §3/§4）：按 selector 从父结果取值写入子意图 filter——
     * single_row（0 行 → 045 / ≥2 行 → 046 / 1 行取列）、ordered_top（越界 → 045）；
     * 列缺失 → 045。取值原样进 filter.values（参数化绑定，无 SQL 拼接面）。
     */
    private void injectDependencyValue(QueryDsl.SubIntent sub, QueryDsl child,
                                       Map<String, QueryResult> resultsBySubIntentId) {
        QueryDsl.ResultRef ref = sub.getResultRef();
        QueryResult parent = resultsBySubIntentId.get(ref.getFromSubIntentId());
        String at = sub.getSubIntentId() + " <- " + ref.getFromSubIntentId();
        if (parent == null) {
            // 父子失败（前序子结构化失败没有结果）→ 取值缺失
            throw exception(DEPENDENCY_VALUE_MISSING, at + "（父结果不可用）");
        }
        String column = ref.getSelector().getColumn();
        List<String> columns = parent.getColumns() == null ? List.of() : parent.getColumns();
        int columnIndex = columns.indexOf(column);
        if (columnIndex < 0) {
            throw exception(DEPENDENCY_VALUE_MISSING, at + "（列不存在: " + column + "）");
        }
        int rowIndex;
        if ("single_row".equals(ref.getSelector().getType())) {
            if (parent.getRows() != null && parent.getRows().size() > 1) {
                throw exception(DEPENDENCY_VALUE_AMBIGUOUS,
                        at + "（父结果 " + parent.getRows().size() + " 行）");
            }
            rowIndex = 0;
        } else {
            rowIndex = ref.getSelector().getRow() - 1;
        }
        if (parent.getRows() == null || rowIndex < 0 || rowIndex >= parent.getRows().size()) {
            throw exception(DEPENDENCY_VALUE_MISSING, at + "（行缺失）");
        }
        Object cell = parent.getRows().get(rowIndex);
        Object value;
        if (cell instanceof Map<?, ?> rowMap) {
            // QueryResult 行形状 = Map（列名 → 值；M3 起 rows 不携带 __identity）
            value = rowMap.get(column);
        } else if (cell instanceof List<?> rowCells) {
            value = columnIndex < rowCells.size() ? rowCells.get(columnIndex) : null;
        } else {
            // 单列行形状（行即值）
            value = columnIndex == 0 ? cell : null;
        }
        if (value == null) {
            throw exception(DEPENDENCY_VALUE_MISSING, at + "（单元格为空）");
        }
        QueryDsl.Filter target = child.getFilters().get(ref.getInject().getFilterIndex());
        target.setValues(new ArrayList<>(List.of(String.valueOf(value))));
    }

    /** 阶段一产物：子意图 + 其子 DSL + 编译产物；阶段二只做「执行 + 审计」，不再校验。 */
    private record PreparedSub(QueryDsl.SubIntent sub, QueryDsl child, CompiledQuery compiled,
                               Map<String, java.util.Set<String>> sensitivity) {
    }

    /** 子请求 ID 独立可寻址（产物表与审计都按它落），冒号在路径段内是合法字符，回放不必转义。 */
    private static String childRequestId(String parentRequestId, String subIntentId) {
        return parentRequestId + ":" + subIntentId;
    }

    /** 容器 → 子 DSL：只搬运子意图自有的字段，容器级参数已在 validateComposite 阶段拒绝。 */
    private static QueryDsl toChildDsl(QueryDsl parent, QueryDsl.SubIntent sub) {
        QueryDsl child = new QueryDsl();
        child.setManifestId(parent.getManifestId());
        child.setScenarioId(parent.getScenarioId());
        child.setMode(sub.getMode() == null ? QueryDsl.AnalysisMode.QUERY : sub.getMode());
        child.setMetricId(sub.getMetricId());
        child.setDimensions(sub.getDimensions() == null ? new ArrayList<>() : sub.getDimensions());
        child.setFilters(sub.getFilters() == null ? new ArrayList<>() : sub.getFilters());
        child.setTimeExpressionRaw(sub.getTimeExpressionRaw());
        // 时间维度随子意图走：解析器据此把「近 N 天」换算成 from/to 并回填 source=EXPRESSION
        child.setTimeRange(sub.getTimeRange());
        // T4.4（R-27）：子意图口径变体下推——子 DSL 走既有单请求链路（M3 变体条件下推自动生效）
        child.setCaliberVariantId(sub.getCaliberVariantId());
        // 容器级 limit 作为每个子意图的默认上限；子意图没有独立 limit 字段，M2 不做过细覆盖
        child.setLimit(parent.getLimit());
        child.setVariantHint(parent.getVariantHint());
        return child;
    }

    private void auditSubIntent(RequestContext context, String parentRequestId, QueryDsl.SubIntent sub,
                                QueryDsl childDsl, CompiledQuery compiled, Integer rowCount,
                                Integer errorCode) {
        AuditLogCreateReqDTO audit = new AuditLogCreateReqDTO();
        audit.setRequestId(childRequestId(parentRequestId, sub.getSubIntentId()));
        audit.setParentRequestId(parentRequestId);
        audit.setSubIntentId(sub.getSubIntentId());
        audit.setTenantId(context.tenantId());
        audit.setUserId(context.userId());
        audit.setSessionId(context.sessionId());
        audit.setTurnIndex(context.turnIndex());
        audit.setManifestId(childDsl.getManifestId());
        audit.setAction(compiled == null ? "SUB_INTENT_REJECTED" : "EXECUTE");
        audit.setMetricId(childDsl.getMetricId());
        audit.setDslJson(JsonUtils.toJsonString(childDsl));
        if (compiled != null) {
            audit.setSqlText(compiled.getSql());
            audit.setRowCount(rowCount);
        }
        if (errorCode != null) {
            audit.setReasonCode(String.valueOf(errorCode));
        }
        audit.setTraceId(context.traceId());
        audit.setDevMode(context.devMode());
        auditService.record(audit);
    }

    /** 父级汇总审计：只记容器与总量，逐子的 SQL 与行数在各自子请求行里。 */
    private void auditComposite(RequestContext context, QueryDsl dsl, PublishedManifest manifest,
                                int totalRowCount) {
        AuditLogCreateReqDTO audit = new AuditLogCreateReqDTO();
        audit.setRequestId(context.traceId());
        audit.setTenantId(context.tenantId());
        audit.setUserId(context.userId());
        audit.setSessionId(context.sessionId());
        audit.setTurnIndex(context.turnIndex());
        audit.setManifestId(manifest.getManifestId());
        audit.setAction("COMPOSITE_EXECUTE");
        audit.setDslJson(JsonUtils.toJsonString(dsl));
        audit.setRowCount(totalRowCount);
        audit.setTraceId(context.traceId());
        audit.setDevMode(context.devMode());
        auditService.record(audit);
    }

    /**
     * dry-plan（M2 规格 §3.2）：编译产物 + EXPLAIN 计划与成本，不执行业务查询。
     *
     * <p>调试通道不推进 {@code request_state}：它不产出业务结果，也不参与幂等判定；
     * 若复用业务状态机，客户端重放同一个 X-Request-Id（已 EXECUTED / REJECTED）会触发非法迁移，
     * 把一次调试请求变成 500。调试动作只留审计痕迹（action=DRY_PLAN）。
     */
    @Override
    public QueryPlan dryPlan(QueryDsl queryDsl) {
        RequestContext context = RequestContextHolder.require();
        PublishedManifest manifest = prepare(queryDsl, context);
        CompiledQuery compiled = sqlCompiler.compile(queryDsl, manifest);
        QueryPlan plan = queryExecutor.explain(compiled, context);
        plan.setCompiled(compiled);
        persistArtifact(context, context.traceId(), compiled, plan);
        recordPlanAudit(context, "DRY_PLAN", queryDsl, compiled, plan, null, manifest);
        return plan;
    }

    /**
     * dry-run（M2 规格 §3.2/§3.3）：同一编译产物在更紧的超时与行数上限下受限执行。
     *
     * <p>成本超阈值时先落审计再拒绝（fail-closed 可追溯）；执行结果与正式通道走
     * 同一套展示转换（掩码），否则调试通道会变成绕过脱敏的旁路。
     */
    @Override
    public DryRunOutcome dryRun(QueryDsl queryDsl) {
        RequestContext context = RequestContextHolder.require();
        Map<String, java.util.Set<String>> sensitivity = new LinkedHashMap<>();
        PublishedManifest manifest = prepare(queryDsl, context, sensitivity);
        CompiledQuery compiled = sqlCompiler.compile(queryDsl, manifest);
        QueryPlan plan = queryExecutor.explain(compiled, context);
        plan.setCompiled(compiled);
        if (QueryPlan.BLOCKED.equals(plan.getStatus())) {
            // 被拦的计划同样要能回放「当时为什么被拦」，所以先落库再拒绝
            persistArtifact(context, context.traceId(), compiled, plan);
            recordPlanAudit(context, "DRY_RUN", queryDsl, compiled, plan, null, manifest);
            throw exception(PLAN_COST_BLOCKED, plan.getBlockReason());
        }
        QueryResult result = queryExecutor.executeDryRun(compiled, context);
        assembleLookupRows(queryDsl, manifest, compiled, result);
        // M4/W2：调试通道是真实执行同样返回数据，泄露面与正式通道相同——同口径记账与判定
        SensitiveEventService.SensitiveDecision decision = recordSensitiveEvents(
                context, queryDsl, sensitivity, manifest, result.getRowCount());
        plan.setVerified(true);
        // 校验完成后再落库：verified=true 是回放端点要还原的状态，不能提前写死 false
        persistArtifact(context, context.traceId(), compiled, plan);
        if (decision.triggered()) {
            recordPlanAudit(context, "DRY_RUN", queryDsl, compiled, plan,
                    result.getRowCount(), decision.auditReasonCode(), manifest);
            throw exception(SENSITIVE_QUERY_SEQUENCE, decision.approvalId());
        }
        recordPlanAudit(context, "DRY_RUN", queryDsl, compiled, plan, result.getRowCount(), manifest);
        return new DryRunOutcome(plan, result);
    }

    /** dry-plan / dry-run 结果进审计：action 区分动作，reasonCode 记录 OK / BLOCKED 判定。 */
    private void recordPlanAudit(RequestContext context, String action, QueryDsl queryDsl,
                                 CompiledQuery compiled, QueryPlan plan, Integer rowCount,
                                 PublishedManifest manifest) {
        AuditLogCreateReqDTO audit = buildAudit(context, action, queryDsl, compiled, rowCount, manifest);
        audit.setReasonCode(plan.getStatus());
        auditService.record(audit);
    }

    /** 触发敏感判定时的 dry-run 审计：reasonCode 记细分触发分支（覆盖 plan 状态码）。 */
    private void recordPlanAudit(RequestContext context, String action, QueryDsl queryDsl,
                                 CompiledQuery compiled, QueryPlan plan, Integer rowCount,
                                 String reasonCodeOverride, PublishedManifest manifest) {
        AuditLogCreateReqDTO audit = buildAudit(context, action, queryDsl, compiled, rowCount, manifest);
        audit.setReasonCode(reasonCodeOverride);
        auditService.record(audit);
    }

    /**
     * 按 request_id 回放最近一次编译产物（M2 规格 §3.2、E3）。
     *
     * <p>查询同时按租户与用户收敛：request_id 允许客户端用 X-Request-Id 指定，只按 ID 取会让
     * 别人用同一个 ID 读到本不属于他的 SQL 与绑定值。回放动作本身也进审计
     * （action=PLAN_REPLAY）：request_id 记被回放的目标、trace_id 记发起者，
     * 保证「谁看过哪次编译产物」可追溯。
     */
    @Override
    public QueryPlan replayPlan(String requestId) {
        RequestContext context = RequestContextHolder.require();
        QueryArtifactDO artifact = queryArtifactRepository
                .selectByRequest(context.tenantId(), context.userId(), requestId)
                .orElseThrow(() -> exception(ARTIFACT_NOT_FOUND, requestId));
        QueryPlan plan = new QueryPlan();
        plan.setCompiled(toCompiled(artifact));
        plan.setPlanJson(artifact.getPlanJson());
        // null 表示这次请求只做过编译（没有 EXPLAIN），前端据此区分「未估算」与「估算为 OK」
        plan.setStatus(artifact.getPlanStatus());
        if (artifact.getEstimatedCost() != null) {
            plan.setEstimatedCost(artifact.getEstimatedCost());
        }
        if (artifact.getEstimatedRows() != null) {
            plan.setEstimatedRows(artifact.getEstimatedRows());
        }
        plan.setVerified(artifact.isVerified());
        auditReplay(context, artifact);
        return plan;
    }

    /**
     * 编译产物落库（T4.5）：同一 (tenant, user, request_id) 覆盖为最近一次。
     * 绑定值可能含标识类值，落库前不做二次加工，访问侧按租户 + 用户收敛。
     */
    private void persistArtifact(RequestContext context, String requestId,
                                 CompiledQuery compiled, QueryPlan plan) {
        QueryArtifactDO artifact = new QueryArtifactDO();
        artifact.setTenantId(context.tenantId());
        artifact.setUserId(context.userId());
        artifact.setRequestId(requestId);
        artifact.setSessionId(context.sessionId());
        artifact.setManifestId(compiled.getManifestId());
        artifact.setContentHash(compiled.getContentHash());
        artifact.setMode(compiled.getMode());
        artifact.setMetricId(compiled.getMetricId());
        artifact.setEntityId(compiled.getEntityId());
        artifact.setSqlText(compiled.getSql());
        artifact.setBindValues(JsonUtils.toJsonString(
                compiled.getBindValues() == null ? List.of() : compiled.getBindValues()));
        artifact.setJoins(JsonUtils.toJsonString(
                compiled.getJoins() == null ? List.of() : compiled.getJoins()));
        artifact.setAppliedLimit(compiled.getAppliedLimit());
        if (plan != null) {
            artifact.setPlanStatus(plan.getStatus());
            artifact.setEstimatedCost(plan.getEstimatedCost());
            artifact.setEstimatedRows(plan.getEstimatedRows());
            artifact.setPlanJson(plan.getPlanJson());
            artifact.setVerified(plan.isVerified());
        }
        queryArtifactRepository.upsert(artifact);
    }

    private void auditReplay(RequestContext context, QueryArtifactDO artifact) {
        AuditLogCreateReqDTO audit = new AuditLogCreateReqDTO();
        audit.setRequestId(artifact.getRequestId());
        audit.setTenantId(context.tenantId());
        audit.setUserId(context.userId());
        audit.setSessionId(context.sessionId());
        audit.setTurnIndex(context.turnIndex());
        audit.setManifestId(artifact.getManifestId());
        audit.setAction("PLAN_REPLAY");
        audit.setMetricId(artifact.getMetricId());
        audit.setSqlText(artifact.getSqlText());
        audit.setTraceId(context.traceId());
        audit.setDevMode(context.devMode());
        auditService.record(audit);
    }

    /** 从持久化产物回装编译产物；只还原回放需要的字段（SQL / 绑定值 / 路径 / 版本锚点）。 */
    private static CompiledQuery toCompiled(QueryArtifactDO artifact) {
        CompiledQuery compiled = new CompiledQuery();
        compiled.setManifestId(artifact.getManifestId());
        compiled.setContentHash(artifact.getContentHash());
        compiled.setMode(artifact.getMode());
        compiled.setMetricId(artifact.getMetricId());
        compiled.setEntityId(artifact.getEntityId());
        compiled.setSql(artifact.getSqlText());
        compiled.setBindValues(JsonUtils.parseObject(artifact.getBindValues(),
                new TypeReference<List<Object>>() { }));
        compiled.setJoins(JsonUtils.parseObject(artifact.getJoins(),
                new TypeReference<List<QueryDsl.ResolvedJoin>>() { }));
        compiled.setAppliedLimit(artifact.getAppliedLimit() == null ? 0 : artifact.getAppliedLimit());
        compiled.setCompiledAt(artifact.getUpdatedAt().atZone(ZoneId.of(TimeExpressionParser.ZONE)).toInstant());
        return compiled;
    }

    /**
     * 缓存键；缓存关闭时返回 null，调用方据此完全跳过缓存分支。
     * 时间基准日取 Asia/Shanghai 的今天：跨日即换键，避免「近 7 天」复用昨日结果。
     */
    private String cacheKeyOf(RequestContext context, PublishedManifest manifest, QueryDsl queryDsl) {
        if (!queryCacheService.isEnabled()) {
            return null;
        }
        int appliedLimit = queryDsl.getLimit() == null ? 0 : queryDsl.getLimit();
        // 运行时生效快照版本入键（M4/W1 审核 F-2）：快照激活/降权即换键（容错 null → none 占位）
        return QueryCacheKey.of(context, manifest, queryDsl, appliedLimit,
                LocalDate.now(ZoneId.of(TimeExpressionParser.ZONE)),
                policyService.cacheVersion(context.tenantId()));
    }

    /** 缓存命中审计：没有新编译产物，因此不写 sql_text；reasonCode 标 CACHE_HIT 便于统计命中率。 */
    private void auditCacheHit(RequestContext context, QueryDsl queryDsl, PublishedManifest manifest,
                               QueryResult result) {
        auditService.record(buildCacheHitAudit(context, queryDsl, manifest,
                result.getRowCount(), "CACHE_HIT"));
    }

    /** 缓存命中路径的审计构造：无编译产物（不写 sql_text），reasonCode 由调用方给定。 */
    private AuditLogCreateReqDTO buildCacheHitAudit(RequestContext context, QueryDsl queryDsl,
                                                    PublishedManifest manifest, int rowCount,
                                                    String reasonCode) {
        return buildCacheHitAudit(context, queryDsl, manifest, rowCount, reasonCode, null, null);
    }

    private AuditLogCreateReqDTO buildCacheHitAudit(RequestContext context, QueryDsl queryDsl,
                                                    PublishedManifest manifest, int rowCount,
                                                    String reasonCode, String approvalId,
                                                    String countersDelta) {
        AuditLogCreateReqDTO audit = new AuditLogCreateReqDTO();
        audit.setRequestId(context.traceId());
        audit.setTenantId(context.tenantId());
        audit.setUserId(context.userId());
        audit.setSessionId(context.sessionId());
        audit.setTurnIndex(context.turnIndex());
        audit.setManifestId(manifest.getManifestId());
        audit.setAction("EXECUTE");
        audit.setMetricId(queryDsl.getMetricId());
        audit.setDslJson(JsonUtils.toJsonString(queryDsl));
        audit.setRowCount(rowCount);
        audit.setReasonCode(reasonCode);
        audit.setApprovalId(approvalId);
        audit.setSensitiveCountersDelta(countersDelta);
        audit.setTraceId(context.traceId());
        audit.setDevMode(context.devMode());
        attachScenarioAudit(audit, context, manifest, queryDsl);
        return audit;
    }

    /**
     * 场景审计字段（V15 / §12.1）：会话处于场景内时补 {@code scenario_id / version / entry_mode}
     * 及三列决策期信息。
     *
     * <p>版本与入口模式取自**已发布清单**的场景定义（静态属性），不是会话态；
     * {@code entry_source / confirm_by_user} 由 {@link ScenarioRouteGuard#lastDecision} 从
     * 「最近一次路由决策」投影（随会话状态同事务持久化）。
     * {@code recommendation_confidence} 按 M6 规格决策 4「不引入 LLM 置信度数值」恒为 null，
     * 不是缺失项。
     */
    private void attachScenarioAudit(AuditLogCreateReqDTO audit, RequestContext context,
                                     PublishedManifest manifest, QueryDsl queryDsl) {
        // 优先记**本次请求声明的场景**（直连 execute 不走会话状态提交，currentScenarioId 为 null）；
        // 未显式声明时回退到会话当前场景（ask 编排路径）。
        String declared = queryDsl == null ? null : queryDsl.getScenarioId();
        boolean explicit = declared != null && !declared.isBlank();
        String scenarioId = explicit ? declared : scenarioRouteGuard.currentScenarioId(context);
        if (scenarioId == null || scenarioId.isBlank()) {
            return;
        }
        audit.setScenarioId(scenarioId);
        // 显式直连：决策信息由本次请求推导（用户显式选择、非 pending_confirm 确认），
        // 不读会话历史，保证「审计记本次请求声明」而非会话当前态。
        ScenarioRouteGuard.DecisionAudit decision = explicit
                ? new ScenarioRouteGuard.DecisionAudit("explicit", Boolean.FALSE, null)
                : scenarioRouteGuard.lastDecision(context);
        if (decision != null) {
            audit.setScenarioEntrySource(decision.entrySource());
            audit.setScenarioConfirmByUser(decision.confirmByUser());
            audit.setScenarioRecommendationConfidence(decision.recommendationConfidence());
        }
        if (manifest == null || manifest.getScenarios() == null) {
            return;
        }
        manifest.getScenarios().stream()
                .filter(scenario -> scenarioId.equals(scenario.getId()))
                .findFirst()
                .ifPresent(scenario -> {
                    audit.setScenarioVersion(scenario.getVersion());
                    audit.setScenarioEntryMode(scenario.getEntryMode());
                });
    }

    private static String truncate(String text) {
        if (text == null) {
            return "unknown";
        }
        return text.length() <= 200 ? text : text.substring(0, 200);
    }

    /**
     * 请求入口：request_id 是幂等键（ADR-0004 2.2）。已处于终态的请求一律不接受再次驱动——
     * 否则后续 transition 会抛 IllegalStateException（非业务错误，客户端只会看到 500）。
     */
    private void beginRequest(RequestContext context, String requestId) {
        RequestStatus current = requestStateService.findState(requestId).orElse(null);
        if (current == RequestStatus.EXECUTED) {
            throw exception(REQUEST_ALREADY_EXECUTED, requestId);
        }
        if (current != null && current.isTerminal()) {
            throw exception(REQUEST_TERMINAL, current.name(), requestId);
        }
        requestStateService.initialize(context, requestId);
    }

    /**
     * 失败路径的状态推进：请求已是终态（或该迁移本就非法）时不再迁移，只记 WARN。
     * 否则「记录失败」这一步会自己抛 IllegalStateException，把真正的失败原因盖掉。
     */
    private void safeTransition(RequestContext context, String requestId, RequestStatus target, String reason) {
        RequestStatus current = requestStateService.findState(requestId).orElse(RequestStatus.RECEIVED);
        if (current.isTerminal() || !current.canTransitionTo(target)) {
            log.warn("[safeTransition][跳过非法迁移] {} {} -> {}", requestId, current, target);
            return;
        }
        requestStateService.transition(context, requestId, target, reason);
    }

    /**
     * 通用审计构造（COMPILE / EXECUTE / DRY_PLAN / DRY_RUN）。
     *
     * <p>B0 修复：主执行路径此前只挂了场景字段到缓存命中分支，导致 EXECUTE / DRY_* 审计行
     * 的 6 个场景扩展列全空。此处统一显式挂 {@link #attachScenarioAudit}；manifest 由调用方
     * 传入（与 compiled 同源），不做隐式 active 查询，避免快照切换时取错场景版本。
     */
    private AuditLogCreateReqDTO buildAudit(RequestContext context, String action, QueryDsl queryDsl,
                                            CompiledQuery compiled, Integer rowCount,
                                            PublishedManifest manifest) {
        AuditLogCreateReqDTO audit = new AuditLogCreateReqDTO();
        audit.setRequestId(context.traceId());
        audit.setTenantId(context.tenantId());
        audit.setUserId(context.userId());
        audit.setSessionId(context.sessionId());
        audit.setTurnIndex(context.turnIndex());
        audit.setManifestId(compiled.getManifestId());
        audit.setAction(action);
        audit.setMetricId(compiled.getMetricId());
        audit.setDslJson(JsonUtils.toJsonString(queryDsl));
        audit.setSqlText(compiled.getSql());
        audit.setRowCount(rowCount);
        audit.setTraceId(context.traceId());
        audit.setDevMode(context.devMode());
        attachScenarioAudit(audit, context, manifest, queryDsl);
        return audit;
    }

    /**
     * 校验清单一致性、DSL 结构与授权。任何一步失败都在触达数据库前终止。
     */
    private PublishedManifest prepare(QueryDsl queryDsl, RequestContext context) {
        return prepare(queryDsl, context, null);
    }

    /**
     * @param sensitivityCollector 敏感层收集器（assetId → 敏感层集合，可叠加 existence）；
     *                             null 表示调用方不执行业务查询（dry-plan）无需记账
     */
    private PublishedManifest prepare(QueryDsl queryDsl, RequestContext context,
                                      Map<String, java.util.Set<String>> sensitivityCollector) {
        if (queryDsl.getSubIntents() != null && !queryDsl.getSubIntents().isEmpty()) {
            // 复合请求本身没有指标，走单请求链路只会在 requireMetric(null) 上给出含糊错误
            throw exception(DSL_INVALID, "复合意图请使用 /api/v1/query/composite/execute");
        }
        // M6/W2/T2.4（ADR-0017 D1）：DSL scenarioId 服务端裁决前置——授权 + 已发布 + 模式/口径
        // 校验在 manifest 处理之前完成，直传越权不进入任何后续链路。
        scenarioRouteGuard.adjudicate(context, queryDsl.getScenarioId(),
                queryDsl.getMode() == null ? null : queryDsl.getMode().name().toLowerCase(),
                queryDsl.getTimeRange() == null ? null : queryDsl.getTimeRange().getField(),
                referencedAssetIds(queryDsl));
        checkManifestHeader(queryDsl, context);
        PublishedManifest manifest = manifestService.validateManifest(queryDsl.getManifestId());
        // M6 全量深度审核 D-1（P1）：状态提交延后到 prepare 全链成功（manifest / 时间解析 /
        // DSL 校验 / 路径规划 / 策略）之后——校验失败不写会话状态，满足「失败即拒、目标状态不变」。
        PublishedManifest prepared = prepareWith(queryDsl, context, manifest, sensitivityCollector);
        scenarioRouteGuard.commitExplicitRoute(context, queryDsl.getScenarioId());
        return prepared;
    }

    /** DSL 引用的语义资产 ID（供场景 scope 交集；T24-5）。服务端推衍字段（identityPropertyId 等）不重复收集。 */
    private static java.util.List<String> referencedAssetIds(QueryDsl queryDsl) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        if (queryDsl.getMetricId() != null) {
            ids.add(queryDsl.getMetricId());
        }
        if (queryDsl.getBooleanMetricId() != null) {
            ids.add(queryDsl.getBooleanMetricId());
        }
        if (queryDsl.getEntityId() != null) {
            ids.add(queryDsl.getEntityId());
        }
        ids.addAll(queryDsl.getDimensions());
        ids.addAll(queryDsl.getAttributes());
        if (queryDsl.getFilters() != null) {
            queryDsl.getFilters().forEach(filter -> {
                if (filter.getFieldId() != null) {
                    ids.add(filter.getFieldId());
                }
            });
        }
        return ids;
    }

    private static void checkManifestHeader(QueryDsl queryDsl, RequestContext context) {
        if (context.manifestId() != null && !context.manifestId().equals(queryDsl.getManifestId())) {
            throw exception(MANIFEST_MISMATCH, queryDsl.getManifestId());
        }
    }

    /**
     * 单个 DSL 的解析与授权（清单已加载）：时间换算 → 路径规划 → 结构校验 → 策略。
     * 复合意图逐子复用同一份清单，避免每个子意图都重新加载清单。
     */
    private PublishedManifest prepareWith(QueryDsl queryDsl, RequestContext context,
                                          PublishedManifest manifest,
                                          Map<String, java.util.Set<String>> sensitivityCollector) {
        if (!isLookup(queryDsl)) {
            // M2/T2.3 接线：相对时间先由服务端解析器换算，再进入结构与授权校验。
            // 解析结果（含 patternId / parserVersion）写入 DSL，随 buildAudit 的 dsl_json 进审计。
            timeWindowResolver.resolve(queryDsl, manifest);
            // W3/T3.1：跨表字段先由规划器解析 UNIQUE 路径，歧义/不可达在此拒绝。
            // funnel / retention 按事件主体编译、没有 metric，也不做本体路径规划，必须跳过规划器，
            // 否则 requireMetric(null) 会以「语义资产不存在」误拒合法事件锚点查询。
            if (!isEventAnchored(queryDsl)) {
                // R-18：模式授权必须先于路径规划。规则型指标没有物理表，
                // 先跑规划器会把「模式不支持」误报成 PATH_UNREACHABLE。
                dslValidator.validateMode(queryDsl, manifest);
                // attribution 的 timeRange.field 是转化事件时间属性（非维度），规划器会把它当维度解析；
                // 首发归因维度限「渠道 / 指标同表」、不需要路径展开，故与 retention 同型跳过规划。
                if (!isEventTimeBackfilled(queryDsl)) {
                    joinPathResolver.resolve(queryDsl, manifest);
                }
            }
        }
        dslValidator.validate(queryDsl, manifest);
        // M4/W1 策略判定（规格 v1.1 §3.1）：DSL 校验之后（资产存在性已由清单证明），
        // 错误码优先级：清单存在性 1001000003 → 策略授权 1003000005。
        // 主资产：lookup 以实体为授权资产；funnel 以事件主体为入口（无 metric，M2 语义），
        // 其授权由下方维度/属性引用面与事件映射承担；其余模式以指标为锚点。
        if (isLookup(queryDsl)) {
            collectSensitivity(sensitivityCollector, manifest, queryDsl.getEntityId(),
                    PolicySnapshotService.AssetType.ENTITY,
                    policyService.check(context, PolicySnapshotService.AssetType.ENTITY,
                            queryDsl.getEntityId()));
        } else if (!isEventAnchored(queryDsl)) {
            collectSensitivity(sensitivityCollector, manifest, queryDsl.getMetricId(),
                    PolicySnapshotService.AssetType.METRIC,
                    policyService.check(context, PolicySnapshotService.AssetType.METRIC,
                            queryDsl.getMetricId()));
        }
        // 引用面逐项判定：分组/过滤/时间维度走 DIMENSION，属性投影走 PROPERTY（用户×资产第二道闸门；
        // 清单级授权已由 requireAllowedDimension 证明资产在指标白名单内）。
        if (queryDsl.getDimensions() != null) {
            for (String dimensionId : queryDsl.getDimensions()) {
                collectSensitivity(sensitivityCollector, manifest, dimensionId,
                        PolicySnapshotService.AssetType.DIMENSION,
                        policyService.check(context, PolicySnapshotService.AssetType.DIMENSION,
                                dimensionId));
            }
        }
        if (queryDsl.getFilters() != null) {
            for (QueryDsl.Filter filter : queryDsl.getFilters()) {
                collectSensitivity(sensitivityCollector, manifest, filter.getFieldId(),
                        PolicySnapshotService.AssetType.DIMENSION,
                        policyService.check(context, PolicySnapshotService.AssetType.DIMENSION,
                                filter.getFieldId()));
            }
        }
        if (queryDsl.getTimeRange() != null && queryDsl.getTimeRange().getField() != null
                && !isEventTimeBackfilled(queryDsl)) {
            // retention / attribution 的 timeRange.field 是服务端按 EventDef.timeProperty 回填的事件时间属性
            // （不是维度资产、也不来自客户端），不参与 DIMENSION 判定；funnel 的时间在 cohortWindow。
            collectSensitivity(sensitivityCollector, manifest, queryDsl.getTimeRange().getField(),
                    PolicySnapshotService.AssetType.DIMENSION,
                    policyService.check(context, PolicySnapshotService.AssetType.DIMENSION,
                            queryDsl.getTimeRange().getField()));
        }
        if (queryDsl.getAttributes() != null) {
            for (String propertyId : queryDsl.getAttributes()) {
                collectSensitivity(sensitivityCollector, manifest, propertyId,
                        PolicySnapshotService.AssetType.PROPERTY,
                        policyService.check(context, PolicySnapshotService.AssetType.PROPERTY,
                                propertyId));
            }
        }
        if (queryDsl.getBooleanMetricId() != null) {
            // 布尔判定走个体闸门：label 级判定结果拒绝个体返回（聚合分布 M4 不可达已登记）
            collectSensitivity(sensitivityCollector, manifest, queryDsl.getBooleanMetricId(),
                    PolicySnapshotService.AssetType.METRIC,
                    policyService.checkIndividual(context, PolicySnapshotService.AssetType.METRIC,
                            queryDsl.getBooleanMetricId()));
        }
        return manifest;
    }

    /**
     * M4/W2 敏感分级收集（规格 §3.2 双源）：快照 class（pii/label）为主体，
     * 本体清单 {@code existenceSensitive=true} 的 PROPERTY 叠加 existence 层
     * （可叠加语义：如 email 兼 pii + existence，两层窗口独立累计）；
     * internal/public 且非存在性敏感 = L0，不进收集器（不记账）。
     */
    private static void collectSensitivity(Map<String, java.util.Set<String>> collector,
                                           PublishedManifest manifest, String assetId,
                                           PolicySnapshotService.AssetType assetType,
                                           PolicySnapshotService.SensitivityClass sensitivity) {
        if (collector == null || assetId == null) {
            return;
        }
        // 契约上不可达（check 放行必返回非 null 分级，异常走抛错路径）；防御性按 L0 处理并留痕
        PolicySnapshotService.SensitivityClass effective = sensitivity == null
                ? PolicySnapshotService.SensitivityClass.INTERNAL : sensitivity;
        if (sensitivity == null) {
            log.warn("[collectSensitivity][check 返回 null 分级（契约外），按 L0 处理: {}]", assetId);
        }
        java.util.Set<String> layers = collector.computeIfAbsent(assetId,
                key -> new TreeSet<>());
        switch (effective) {
            case PII -> layers.add(SensitiveEventService.CLASS_PII);
            case LABEL -> layers.add(SensitiveEventService.CLASS_LABEL);
            case EXISTENCE -> layers.add(SensitiveEventService.CLASS_EXISTENCE);
            case PUBLIC, INTERNAL -> {
                // L0 基线：本体清单存在性标记仍可叠加（双源口径）
            }
        }
        if (assetType == PolicySnapshotService.AssetType.PROPERTY) {
            OntologySnapshot.Property property = manifest.getOntology().propertyIndex().get(assetId);
            if (property != null && property.isExistenceSensitive()) {
                layers.add(SensitiveEventService.CLASS_EXISTENCE);
            }
        }
        if (layers.isEmpty()) {
            collector.remove(assetId);
        }
    }

    private static boolean isLookup(QueryDsl dsl) {
        return dsl.getMode() == QueryDsl.AnalysisMode.LOOKUP
                || dsl.getMode() == QueryDsl.AnalysisMode.LOOKUP_BATCH;
    }

    /**
     * 事件锚点模式（funnel / retention）：按事件主体聚合，没有 metric，不做本体路径规划；
     * 授权由维度/属性引用面与事件映射承担（M2 funnel 口径，M6 retention 延续）。
     */
    private static boolean isEventAnchored(QueryDsl dsl) {
        return dsl.getMode() == QueryDsl.AnalysisMode.FUNNEL
                || dsl.getMode() == QueryDsl.AnalysisMode.RETENTION;
    }

    /** 时间窗 field 由服务端按事件 timeProperty 回填的模式（retention / attribution）。 */
    private static boolean isEventTimeBackfilled(QueryDsl dsl) {
        return dsl.getMode() == QueryDsl.AnalysisMode.RETENTION
                || dsl.getMode() == QueryDsl.AnalysisMode.ATTRIBUTION;
    }

    /**
     * lookup 逐行存在性保护（v1.4 §8.2、M2/T3.2）。
     *
     * <p>单实体：命中 2 行视为标识不唯一，拒绝猜测；0/1 行都产出 1 条 lookupRows。
     * 批量：严格按请求 identities 顺序对齐，缺失行统一 available=false，不区分
     * 「不存在」与「无权限」；展示转换（mask）在返回前完成，审计仍记原始 SQL。
     */
    private static void assembleLookupRows(QueryDsl dsl, PublishedManifest manifest,
                                           CompiledQuery compiled, QueryResult result) {
        if (!isLookup(dsl)) {
            return;
        }
        List<Map<String, Object>> rows = result.getRows() == null ? List.of() : result.getRows();
        // 关键：rows 是对外返回的原始行，必须先就地做展示转换，否则 lookupRows 掩码而 rows
        // 仍带明文 PII，掩码形同虚设。
        List<Map<String, Object>> safeRows = new java.util.ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> safe = new java.util.LinkedHashMap<>(row);
            applyMask(manifest, compiled, safe);
            safeRows.add(safe);
        }
        List<QueryResult.LookupRow> lookupRows = new java.util.ArrayList<>();
        if (dsl.getMode() == QueryDsl.AnalysisMode.LOOKUP) {
            String identity = dsl.getIdentity() == null ? null : dsl.getIdentity().getValue();
            if (safeRows.size() > 1) {
                throw exception(LOOKUP_NOT_UNIQUE, String.valueOf(identity));
            }
            Map<String, Object> found = safeRows.isEmpty() ? null : safeRows.get(0);
            lookupRows.add(new QueryResult.LookupRow(identity, found != null, subset(found, compiled)));
        } else {
            Map<String, Map<String, Object>> byIdentity = new java.util.LinkedHashMap<>();
            for (Map<String, Object> row : safeRows) {
                Object id = row.get(IDENTITY_ALIAS);
                if (id != null) {
                    byIdentity.putIfAbsent(String.valueOf(id), row);
                }
            }
            for (String identity : compiled.getIdentities() == null ? List.<String>of() : compiled.getIdentities()) {
                Map<String, Object> found = byIdentity.get(identity);
                lookupRows.add(new QueryResult.LookupRow(identity, found != null, subset(found, compiled)));
            }
        }
        result.setLookupRows(lookupRows);
        // R-20 收口（M3/W3）：__identity 是批量对齐用的服务端内部列，
        // lookupRows 里已带 identity 与可用性，对外 rows 一律剥离，不给前端暴露内部约定。
        safeRows.forEach(row -> row.remove(IDENTITY_ALIAS));
        result.setRows(safeRows);
    }

    /** 只保留投影属性与布尔判定列（不含 __identity 等内部列）。 */
    private static Map<String, Object> subset(Map<String, Object> row, CompiledQuery compiled) {
        if (row == null) {
            return Map.of();
        }
        Map<String, Object> values = new java.util.LinkedHashMap<>();
        for (String attributeId : compiled.getAttributes() == null ? List.<String>of() : compiled.getAttributes()) {
            values.put(attributeId, row.get(attributeId));
        }
        // T3.4：布尔判定列是编译期生成的列，不在 attributes 中，必须一并进入结构化返回通道
        if (compiled.getBooleanMetricId() != null) {
            values.put(compiled.getBooleanMetricId(), row.get(compiled.getBooleanMetricId()));
        }
        return values;
    }

    /**
     * 展示转换（M2 只支持 mask）：displayHints 含 mask，或属性本身 existenceSensitive 时强制掩码。
     * 后者不依赖清单配置，避免「存在性敏感但漏配 hints」时返回明文。
     */
    private static void applyMask(PublishedManifest manifest, CompiledQuery compiled, Map<String, Object> row) {
        for (String attributeId : compiled.getAttributes() == null ? List.<String>of() : compiled.getAttributes()) {
            Object value = row.get(attributeId);
            if (value == null) {
                continue;
            }
            OntologySnapshot.Property property = manifest.getOntology().propertyIndex().get(attributeId);
            // T3.5 兜底：displayHints / existenceSensitive 漏配时，PII / LABEL 也必须掩码，
            // 不能把「清单没写 mask」当成可明文导出的授权。
            boolean sensitiveLevel = property != null
                    && (property.getSensitivity() == OntologySnapshot.Sensitivity.PII
                    || property.getSensitivity() == OntologySnapshot.Sensitivity.LABEL);
            boolean shouldMask = property != null && (property.isExistenceSensitive()
                    || sensitiveLevel
                    || (property.getDisplayHints() != null && property.getDisplayHints().contains("mask")));
            if (shouldMask) {
                row.put(attributeId, mask(String.valueOf(value)));
            }
        }
    }

    /** 显示层掩码：邮箱保留首字符与域名，其余保留首字符；不可逆，不参与任何授权判断。 */
    private static String mask(String value) {
        int at = value.indexOf('@');
        if (at > 0) {
            return value.charAt(0) + "***" + value.substring(at);
        }
        return value.length() <= 1 ? "***" : value.charAt(0) + "***";
    }

}

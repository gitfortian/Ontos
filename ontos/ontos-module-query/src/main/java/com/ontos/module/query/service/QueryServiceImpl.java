package com.ontos.module.query.service;

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
import static com.ontos.module.query.enums.ErrorCodeConstants.DSL_INVALID;
import static com.ontos.module.query.enums.ErrorCodeConstants.LOOKUP_NOT_UNIQUE;
import static com.ontos.module.query.enums.ErrorCodeConstants.PLAN_COST_BLOCKED;
import static com.ontos.module.query.enums.ErrorCodeConstants.REQUEST_ALREADY_EXECUTED;
import static com.ontos.module.query.enums.ErrorCodeConstants.REQUEST_TERMINAL;
import static com.ontos.module.system.enums.ErrorCodeConstants.SENSITIVE_QUERY_SEQUENCE;

@Slf4j
@Service
public class QueryServiceImpl implements QueryService {

    /** 与 SqlCompiler 的投影别名保持一致，用于批量结果按标识回填。 */
    private static final String IDENTITY_ALIAS = "__identity";

    private final ManifestService manifestService;
    private final DslValidator dslValidator;
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
            auditService.record(buildAudit(context, "COMPILE", queryDsl, compiled, null));
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
                    // 缓存命中同样记账并判定（M4/W2，§4.8「每请求一行」字面口径）：
                    // 命中返回数据本身就是一次敏感访问；拒答档触发后的「临时冻结」必须覆盖命中路径，
                    // 否则同参重复请求会绕过冻结拿到旧结果（E2E 实测暴露）。触发过记账的请求
                    // 不会入缓存（触发分支先于缓存回写），因此命中只发生在「历史已放行」的参数上。
                    QueryResult hit = cached.get();
                    SensitiveEventService.SensitiveDecision hitDecision = recordSensitiveEvents(
                            context, queryDsl, sensitivity, manifest, hit.getRowCount());
                    if (hitDecision.triggered()) {
                        AuditLogCreateReqDTO hitAudit = buildCacheHitAudit(context, queryDsl,
                                manifest, hit.getRowCount(), hitDecision.auditReasonCode());
                        auditService.record(hitAudit);
                        requestStateService.transition(context, requestId, RequestStatus.REJECTED,
                                "敏感累计触发（缓存命中路径）");
                        throw exception(SENSITIVE_QUERY_SEQUENCE, hitDecision.approvalId());
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
            requestStateService.transition(context, requestId, RequestStatus.EXECUTED, "执行完成");
            requestStateService.recordRowCount(requestId, result.getRowCount());
            // M4/W2（规格 §3.2）：执行成功后同事务记账 + 窗口判定；触发即不放行结果、不回写缓存
            SensitiveEventService.SensitiveDecision decision = recordSensitiveEvents(
                    context, queryDsl, sensitivity, manifest, result.getRowCount());
            if (decision.triggered() && !consumeGrantOrReject(context, decision)) {
                // 触发请求全量审计（细分分支码只在审计，对外统一 SENSITIVE_QUERY_SEQUENCE）；
                // 状态 REJECTED 交由下方 catch 统一转移，避免重复迁移
                AuditLogCreateReqDTO triggerAudit = buildAudit(context, "EXECUTE", queryDsl,
                        compiled, result.getRowCount());
                triggerAudit.setReasonCode(decision.auditReasonCode());
                auditService.record(triggerAudit);
                throw exception(SENSITIVE_QUERY_SEQUENCE, decision.approvalId());
            }
            AuditLogCreateReqDTO audit = buildAudit(context, "EXECUTE", queryDsl, compiled,
                    result.getRowCount());
            if (cacheKey != null) {
                audit.setReasonCode("CACHE_MISS");
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
        SkillHandler handler = skillRegistry.byId(skillId)
                .orElseThrow(() -> exception(DSL_INVALID, "未知能力: " + skillId));
        PublishedManifest active = manifestService.getActiveManifestOrNull();
        if (active == null) {
            // 没有生效清单时业务必然不可用：fail-closed，与 validateManifest 的空态口径一致
            throw exception(MANIFEST_NOT_FOUND);
        }
        QueryDsl dsl = handler.build(slots == null ? Map.of() : slots, active.getManifestId());
        auditSkill(context, skillId, slots);
        return execute(dsl);
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

            // 阶段一：逐子校验 + 编译 + 落产物（此时还没有任何业务查询发生）
            List<PreparedSub> prepared = new ArrayList<>();
            for (QueryDsl.SubIntent sub : queryDsl.getSubIntents()) {
                QueryDsl child = toChildDsl(queryDsl, sub);
                Map<String, java.util.Set<String>> subSensitivity = new LinkedHashMap<>();
                try {
                    prepareWith(child, context, manifest, subSensitivity);
                    CompiledQuery compiled = sqlCompiler.compile(child, manifest);
                    persistArtifact(context, childRequestId(parentRequestId, sub.getSubIntentId()),
                            compiled, null);
                    prepared.add(new PreparedSub(sub, child, compiled, subSensitivity));
                } catch (ServiceException ex) {
                    auditSubIntent(context, parentRequestId, sub, child, null, null, ex.getCode());
                    throw ex;
                }
            }

            // 阶段二：统一执行（全部子意图已确认可编译、已授权）
            List<CompositeOutcome.SubResult> results = new ArrayList<>();
            int totalRowCount = 0;
            for (PreparedSub item : prepared) {
                try {
                    QueryResult result = queryExecutor.execute(item.compiled(), context);
                    assembleLookupRows(item.child(), manifest, item.compiled(), result);
                    // M4/W2：每个子意图独立记账（各子各自授权与敏感面），触发即中断后续子（fail-closed）
                    SensitiveEventService.SensitiveDecision decision = recordSensitiveEvents(
                            context, item.child(), item.sensitivity(), manifest, result.getRowCount());
                    if (decision.triggered()) {
                        auditSubIntent(context, parentRequestId, item.sub(), item.child(),
                                item.compiled(), result.getRowCount(),
                                SENSITIVE_QUERY_SEQUENCE.getCode());
                        throw exception(SENSITIVE_QUERY_SEQUENCE, decision.approvalId());
                    }
                    totalRowCount += result.getRowCount();
                    results.add(new CompositeOutcome.SubResult(item.sub().getSubIntentId(), result));
                    auditSubIntent(context, parentRequestId, item.sub(), item.child(),
                            item.compiled(), result.getRowCount(), null);
                } catch (ServiceException ex) {
                    auditSubIntent(context, parentRequestId, item.sub(), item.child(),
                            item.compiled(), null, ex.getCode());
                    throw ex;
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
        recordPlanAudit(context, "DRY_PLAN", queryDsl, compiled, plan, null);
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
            recordPlanAudit(context, "DRY_RUN", queryDsl, compiled, plan, null);
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
                    result.getRowCount(), decision.auditReasonCode());
            throw exception(SENSITIVE_QUERY_SEQUENCE, decision.approvalId());
        }
        recordPlanAudit(context, "DRY_RUN", queryDsl, compiled, plan, result.getRowCount());
        return new DryRunOutcome(plan, result);
    }

    /** dry-plan / dry-run 结果进审计：action 区分动作，reasonCode 记录 OK / BLOCKED 判定。 */
    private void recordPlanAudit(RequestContext context, String action, QueryDsl queryDsl,
                                 CompiledQuery compiled, QueryPlan plan, Integer rowCount) {
        AuditLogCreateReqDTO audit = buildAudit(context, action, queryDsl, compiled, rowCount);
        audit.setReasonCode(plan.getStatus());
        auditService.record(audit);
    }

    /** 触发敏感判定时的 dry-run 审计：reasonCode 记细分触发分支（覆盖 plan 状态码）。 */
    private void recordPlanAudit(RequestContext context, String action, QueryDsl queryDsl,
                                 CompiledQuery compiled, QueryPlan plan, Integer rowCount,
                                 String reasonCodeOverride) {
        AuditLogCreateReqDTO audit = buildAudit(context, action, queryDsl, compiled, rowCount);
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
        audit.setTraceId(context.traceId());
        audit.setDevMode(context.devMode());
        return audit;
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

    private static AuditLogCreateReqDTO buildAudit(RequestContext context, String action, QueryDsl queryDsl,
                                                   CompiledQuery compiled, Integer rowCount) {
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
        checkManifestHeader(queryDsl, context);
        PublishedManifest manifest = manifestService.validateManifest(queryDsl.getManifestId());
        return prepareWith(queryDsl, context, manifest, sensitivityCollector);
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
            // funnel 按事件主体编译、没有 metric，也不做本体路径规划，必须跳过规划器，
            // 否则 requireMetric(null) 会以「语义资产不存在」误拒合法漏斗。
            if (queryDsl.getMode() != QueryDsl.AnalysisMode.FUNNEL) {
                // R-18：模式授权必须先于路径规划。规则型指标没有物理表，
                // 先跑规划器会把「模式不支持」误报成 PATH_UNREACHABLE。
                dslValidator.validateMode(queryDsl, manifest);
                joinPathResolver.resolve(queryDsl, manifest);
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
        } else if (queryDsl.getMode() != QueryDsl.AnalysisMode.FUNNEL) {
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
        if (queryDsl.getTimeRange() != null && queryDsl.getTimeRange().getField() != null) {
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

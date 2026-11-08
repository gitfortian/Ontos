package com.ontos.module.query.service;

import com.ontos.framework.common.exception.ServiceException;
import com.ontos.framework.common.util.json.JsonUtils;
import com.ontos.framework.web.core.context.RequestContext;
import com.ontos.framework.web.core.context.RequestContextHolder;
import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.ontology.service.manifest.ManifestService;
import com.ontos.module.query.core.compiler.CompiledQuery;
import com.ontos.module.query.core.compiler.JoinPathResolver;
import com.ontos.module.query.core.compiler.SqlCompiler;
import com.ontos.module.query.core.dsl.DslValidator;
import com.ontos.module.query.core.dsl.QueryDsl;
import com.ontos.module.query.core.executor.QueryExecutor;
import com.ontos.module.query.core.executor.QueryResult;
import com.ontos.module.query.core.time.TimeWindowResolver;
import com.ontos.module.system.service.audit.AuditLogCreateReqDTO;
import com.ontos.module.system.service.audit.AuditService;
import com.ontos.module.system.service.policy.PolicyService;
import com.ontos.module.system.service.request.RequestStateService;
import com.ontos.module.system.service.request.RequestStatus;
import org.springframework.stereotype.Service;

import static com.ontos.module.ontology.enums.ErrorCodeConstants.MANIFEST_MISMATCH;
import static com.ontos.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.ontos.module.query.enums.ErrorCodeConstants.REQUEST_ALREADY_EXECUTED;

@Service
public class QueryServiceImpl implements QueryService {

    private final ManifestService manifestService;
    private final DslValidator dslValidator;
    private final PolicyService policyService;
    private final SqlCompiler sqlCompiler;
    private final QueryExecutor queryExecutor;
    private final AuditService auditService;
    private final RequestStateService requestStateService;
    private final TimeWindowResolver timeWindowResolver;
    private final JoinPathResolver joinPathResolver;

    public QueryServiceImpl(ManifestService manifestService,
                            DslValidator dslValidator,
                            PolicyService policyService,
                            SqlCompiler sqlCompiler,
                            QueryExecutor queryExecutor,
                            AuditService auditService,
                            RequestStateService requestStateService,
                            TimeWindowResolver timeWindowResolver,
                            JoinPathResolver joinPathResolver) {
        this.manifestService = manifestService;
        this.dslValidator = dslValidator;
        this.policyService = policyService;
        this.sqlCompiler = sqlCompiler;
        this.queryExecutor = queryExecutor;
        this.auditService = auditService;
        this.requestStateService = requestStateService;
        this.timeWindowResolver = timeWindowResolver;
        this.joinPathResolver = joinPathResolver;
    }

    @Override
    public CompiledQuery compile(QueryDsl queryDsl) {
        RequestContext context = RequestContextHolder.require();
        String requestId = context.traceId();
        requestStateService.initialize(context, requestId);
        PublishedManifest manifest = prepare(queryDsl, context);
        CompiledQuery compiled = sqlCompiler.compile(queryDsl, manifest);
        requestStateService.transition(context, requestId, RequestStatus.PROPOSED, "编译完成（dry-plan）");
        auditService.record(buildAudit(context, "COMPILE", queryDsl, compiled, null));
        return compiled;
    }

    @Override
    public QueryResult execute(QueryDsl queryDsl) {
        RequestContext context = RequestContextHolder.require();
        String requestId = context.traceId();
        // 幂等（ADR-0004 2.2）：同 request_id 已执行 → 不重新执行（返回原状态以错误码明示；
        // 原结果回放依赖 M2 结果缓存落地后接通）
        if (requestStateService.findState(requestId).orElse(null) == RequestStatus.EXECUTED) {
            throw exception(REQUEST_ALREADY_EXECUTED, requestId);
        }
        requestStateService.initialize(context, requestId);
        try {
            PublishedManifest manifest = prepare(queryDsl, context);
            CompiledQuery compiled = sqlCompiler.compile(queryDsl, manifest);
            requestStateService.transition(context, requestId, RequestStatus.PROPOSED, "编译完成");
            QueryResult result = queryExecutor.execute(compiled, context);
            requestStateService.transition(context, requestId, RequestStatus.EXECUTED, "执行完成");
            requestStateService.recordRowCount(requestId, result.getRowCount());
            auditService.record(buildAudit(context, "EXECUTE", queryDsl, compiled, result.getRowCount()));
            return result;
        } catch (ServiceException ex) {
            // fail-closed 类拒绝（清单/策略/DSL 校验）：REJECTED 是合法终态
            requestStateService.transition(context, requestId, RequestStatus.REJECTED,
                    truncate(ex.getMessage()));
            throw ex;
        } catch (RuntimeException ex) {
            requestStateService.transition(context, requestId, RequestStatus.FAILED,
                    truncate(String.valueOf(ex)));
            throw ex;
        }
    }

    private static String truncate(String text) {
        if (text == null) {
            return "unknown";
        }
        return text.length() <= 200 ? text : text.substring(0, 200);
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
        if (context.manifestId() != null && !context.manifestId().equals(queryDsl.getManifestId())) {
            throw exception(MANIFEST_MISMATCH, queryDsl.getManifestId());
        }
        PublishedManifest manifest = manifestService.validateManifest(queryDsl.getManifestId());
        // M2/T2.3 接线：相对时间先由服务端解析器换算，再进入结构与授权校验。
        // 解析结果（含 patternId / parserVersion）写入 DSL，随 buildAudit 的 dsl_json 进审计。
        timeWindowResolver.resolve(queryDsl, manifest);
        // W3/T3.1：跨表字段先由规划器解析 UNIQUE 路径，歧义/不可达在此拒绝，
        // 校验器与编译器只消费服务端回填的 resolvedJoins。
        joinPathResolver.resolve(queryDsl, manifest);
        dslValidator.validate(queryDsl, manifest);
        policyService.check(context, queryDsl.getMetricId());
        return manifest;
    }

}

package com.ontos.module.query.service;

import com.ontos.framework.common.util.json.JsonUtils;
import com.ontos.framework.web.core.context.RequestContext;
import com.ontos.framework.web.core.context.RequestContextHolder;
import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.ontology.service.manifest.ManifestService;
import com.ontos.module.query.core.compiler.CompiledQuery;
import com.ontos.module.query.core.compiler.SqlCompiler;
import com.ontos.module.query.core.dsl.DslValidator;
import com.ontos.module.query.core.dsl.QueryDsl;
import com.ontos.module.query.core.executor.QueryExecutor;
import com.ontos.module.query.core.executor.QueryResult;
import com.ontos.module.system.service.audit.AuditLogCreateReqDTO;
import com.ontos.module.system.service.audit.AuditService;
import com.ontos.module.system.service.policy.PolicyService;
import org.springframework.stereotype.Service;

import static com.ontos.module.ontology.enums.ErrorCodeConstants.MANIFEST_MISMATCH;
import static com.ontos.framework.common.exception.util.ServiceExceptionUtil.exception;

@Service
public class QueryServiceImpl implements QueryService {

    private final ManifestService manifestService;
    private final DslValidator dslValidator;
    private final PolicyService policyService;
    private final SqlCompiler sqlCompiler;
    private final QueryExecutor queryExecutor;
    private final AuditService auditService;

    public QueryServiceImpl(ManifestService manifestService,
                            DslValidator dslValidator,
                            PolicyService policyService,
                            SqlCompiler sqlCompiler,
                            QueryExecutor queryExecutor,
                            AuditService auditService) {
        this.manifestService = manifestService;
        this.dslValidator = dslValidator;
        this.policyService = policyService;
        this.sqlCompiler = sqlCompiler;
        this.queryExecutor = queryExecutor;
        this.auditService = auditService;
    }

    @Override
    public CompiledQuery compile(QueryDsl queryDsl) {
        RequestContext context = RequestContextHolder.require();
        PublishedManifest manifest = prepare(queryDsl, context);
        CompiledQuery compiled = sqlCompiler.compile(queryDsl, manifest);
        auditService.record(buildAudit(context, "COMPILE", queryDsl, compiled, null));
        return compiled;
    }

    @Override
    public QueryResult execute(QueryDsl queryDsl) {
        RequestContext context = RequestContextHolder.require();
        PublishedManifest manifest = prepare(queryDsl, context);
        CompiledQuery compiled = sqlCompiler.compile(queryDsl, manifest);
        QueryResult result = queryExecutor.execute(compiled, context);
        auditService.record(buildAudit(context, "EXECUTE", queryDsl, compiled, result.getRowCount()));
        return result;
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
        dslValidator.validate(queryDsl, manifest);
        policyService.check(context, queryDsl.getMetricId());
        return manifest;
    }

}

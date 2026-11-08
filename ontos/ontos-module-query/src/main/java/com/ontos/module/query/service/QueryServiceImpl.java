package com.ontos.module.query.service;

import java.util.List;
import java.util.Map;

import com.ontos.framework.common.exception.ServiceException;
import com.ontos.framework.common.util.json.JsonUtils;
import com.ontos.framework.web.core.context.RequestContext;
import com.ontos.framework.web.core.context.RequestContextHolder;
import com.ontos.module.ontology.core.OntologySnapshot;
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
import static com.ontos.module.query.enums.ErrorCodeConstants.LOOKUP_NOT_UNIQUE;
import static com.ontos.module.query.enums.ErrorCodeConstants.REQUEST_ALREADY_EXECUTED;

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
        try {
            PublishedManifest manifest = prepare(queryDsl, context);
            CompiledQuery compiled = sqlCompiler.compile(queryDsl, manifest);
            requestStateService.transition(context, requestId, RequestStatus.PROPOSED, "编译完成（dry-plan）");
            auditService.record(buildAudit(context, "COMPILE", queryDsl, compiled, null));
            return compiled;
        } catch (ServiceException ex) {
            // 与 execute 对齐：fail-closed 拒绝也是合法终态，不能让 compile 分支停在初始态
            requestStateService.transition(context, requestId, RequestStatus.REJECTED,
                    truncate(ex.getMessage()));
            throw ex;
        } catch (RuntimeException ex) {
            requestStateService.transition(context, requestId, RequestStatus.FAILED,
                    truncate(String.valueOf(ex)));
            throw ex;
        }
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
            assembleLookupRows(queryDsl, manifest, compiled, result);
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
        if (!isLookup(queryDsl)) {
            // M2/T2.3 接线：相对时间先由服务端解析器换算，再进入结构与授权校验。
            // 解析结果（含 patternId / parserVersion）写入 DSL，随 buildAudit 的 dsl_json 进审计。
            timeWindowResolver.resolve(queryDsl, manifest);
            // W3/T3.1：跨表字段先由规划器解析 UNIQUE 路径，歧义/不可达在此拒绝，
            // 校验器与编译器只消费服务端回填的 resolvedJoins。
            joinPathResolver.resolve(queryDsl, manifest);
        }
        dslValidator.validate(queryDsl, manifest);
        // lookup 系列以实体为授权资产（没有 metricId）；其余模式仍以指标为锚点。
        policyService.check(context, isLookup(queryDsl) ? queryDsl.getEntityId() : queryDsl.getMetricId());
        return manifest;
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
        result.setRows(safeRows);

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
    }

    /** 只保留投影属性子集（不含 __identity 等内部列）。 */
    private static Map<String, Object> subset(Map<String, Object> row, CompiledQuery compiled) {
        if (row == null) {
            return Map.of();
        }
        Map<String, Object> values = new java.util.LinkedHashMap<>();
        for (String attributeId : compiled.getAttributes() == null ? List.<String>of() : compiled.getAttributes()) {
            values.put(attributeId, row.get(attributeId));
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
            boolean shouldMask = property != null && (property.isExistenceSensitive()
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

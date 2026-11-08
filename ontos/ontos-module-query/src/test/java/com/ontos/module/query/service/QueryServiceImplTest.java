package com.ontos.module.query.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.ontos.framework.common.exception.ServiceException;
import com.ontos.framework.web.core.context.RequestContext;
import com.ontos.framework.web.core.context.RequestContextHolder;
import com.ontos.module.ontology.core.ManifestPayload.TimeExpressionPattern;
import com.ontos.module.ontology.enums.ErrorCodeConstants;
import com.ontos.module.ontology.core.OntologySnapshot;
import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.ontology.service.manifest.ManifestService;
import com.ontos.module.query.core.TestFixtures;
import com.ontos.module.query.core.compiler.CompiledQuery;
import com.ontos.module.query.core.compiler.JoinPathResolver;
import com.ontos.module.query.core.compiler.SqlCompiler;
import com.ontos.module.query.core.dsl.DslValidator;
import com.ontos.module.query.core.dsl.QueryDsl;
import com.ontos.module.query.core.executor.DryRunOutcome;
import com.ontos.module.query.core.executor.QueryExecutor;
import com.ontos.module.query.core.executor.QueryPlan;
import com.ontos.module.query.core.executor.QueryResult;
import com.ontos.module.query.core.time.TimeWindowResolver;
import com.ontos.module.query.dal.dataobject.QueryArtifactDO;
import com.ontos.module.query.dal.mysql.QueryArtifactRepository;
import com.ontos.module.query.service.cache.QueryCacheService;
import com.ontos.module.system.service.audit.AuditLogCreateReqDTO;
import com.ontos.module.system.service.audit.AuditService;
import com.ontos.module.system.service.policy.PolicyService;
import com.ontos.module.system.service.request.RequestStateService;
import com.ontos.module.system.service.request.RequestStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static com.ontos.module.query.core.dsl.QueryDsl.TimeRange.SOURCE_EXPRESSION;
import static com.ontos.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.ontos.module.query.enums.ErrorCodeConstants.ARTIFACT_NOT_FOUND;
import static com.ontos.module.query.enums.ErrorCodeConstants.DSL_INVALID;
import static com.ontos.module.query.enums.ErrorCodeConstants.PLAN_COST_BLOCKED;
import static com.ontos.module.query.enums.ErrorCodeConstants.REQUEST_ALREADY_EXECUTED;
import static com.ontos.module.query.enums.ErrorCodeConstants.REQUEST_TERMINAL;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** W2.5 接线回归：相对时间在编译前解析，解析版本随 dsl_json 进审计。 */
class QueryServiceImplTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final ManifestService manifestService = mock(ManifestService.class);
    private final PolicyService policyService = mock(PolicyService.class);
    private final SqlCompiler sqlCompiler = mock(SqlCompiler.class);
    private final QueryExecutor queryExecutor = mock(QueryExecutor.class);
    private final AuditService auditService = mock(AuditService.class);
    private final RequestStateService requestStateService = mock(RequestStateService.class);
    private final QueryArtifactRepository queryArtifactRepository = mock(QueryArtifactRepository.class);
    private final QueryCacheService queryCacheService = mock(QueryCacheService.class);

    @BeforeEach
    void setUp() {
        RequestContextHolder.set(new RequestContext(
                "tenant-1", "user-1", "session-1", 1, null, "trace-1", Set.of(), true));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.clear();
    }

    private QueryServiceImpl service(TimeWindowResolver resolver) {
        return new QueryServiceImpl(manifestService, new DslValidator(TestFixtures.queryProperties()),
                policyService, sqlCompiler, queryExecutor, auditService, requestStateService, resolver,
                new JoinPathResolver(() -> Set.of()), queryArtifactRepository, queryCacheService);
    }

    private static PublishedManifest manifestWithPatterns() {
        TimeExpressionPattern lastNDays = new TimeExpressionPattern();
        lastNDays.setPatternId("tep.last_n_days");
        lastNDays.setPattern("近 N 天");
        lastNDays.setGranularity("DAY");
        PublishedManifest manifest = TestFixtures.manifest();
        manifest.setTimeExpressionPatterns(List.of(lastNDays));
        return manifest;
    }

    @Test
    void shouldResolveTimeBeforeCompileAndRecordParserVersionInAudit() {
        PublishedManifest manifest = manifestWithPatterns();
        when(manifestService.validateManifest("manifest-001")).thenReturn(manifest);

        CompiledQuery compiled = new CompiledQuery();
        compiled.setManifestId("manifest-001");
        compiled.setContentHash("hash-1");
        compiled.setMetricId(TestFixtures.METRIC_ID);
        compiled.setSql("select sum(amount) from public.fact_order");
        when(sqlCompiler.compile(any(), any())).thenReturn(compiled);

        QueryDsl dsl = TestFixtures.query();
        QueryDsl.TimeRange fieldOnly = new QueryDsl.TimeRange();
        fieldOnly.setField(TestFixtures.TIME_DIMENSION_ID);
        dsl.setTimeRange(fieldOnly);
        dsl.setTimeExpressionRaw("近 3 天");

        Clock fixed = Clock.fixed(LocalDate.of(2026, 11, 8).atStartOfDay(ZONE).toInstant(), ZONE);
        service(new TimeWindowResolver(fixed)).compile(dsl);

        assertEquals(SOURCE_EXPRESSION, dsl.getTimeRange().getSource());
        assertEquals("tep.last_n_days", dsl.getTimePatternId());
        assertEquals("time-parser-1.0", dsl.getTimeParserVersion());

        ArgumentCaptor<AuditLogCreateReqDTO> captor = ArgumentCaptor.forClass(AuditLogCreateReqDTO.class);
        verify(auditService).record(captor.capture());
        String auditDsl = captor.getValue().getDslJson();
        assertTrue(auditDsl.contains("tep.last_n_days"), "审计 dsl_json 应含 patternId");
        assertTrue(auditDsl.contains("time-parser-1.0"), "审计 dsl_json 应含解析器版本");
        assertTrue(auditDsl.contains("近 3 天"), "审计 dsl_json 应含原始时间表达");
    }

    @Test
    void shouldProtectBatchLookupRowExistenceAndMaskSensitiveValues() {
        when(manifestService.validateManifest("manifest-001")).thenReturn(TestFixtures.ruleManifest());
        when(requestStateService.findState(any())).thenReturn(java.util.Optional.empty());

        CompiledQuery compiled = new CompiledQuery();
        compiled.setManifestId("manifest-001");
        compiled.setContentHash("hash-1");
        compiled.setMode("LOOKUP_BATCH");
        compiled.setEntityId(TestFixtures.CUSTOMER_CONCEPT);
        compiled.setAttributes(List.of(TestFixtures.CUSTOMER_EMAIL_PROPERTY));
        compiled.setBooleanMetricId(TestFixtures.BOOLEAN_METRIC_ID);
        compiled.setIdentities(List.of("c-1", "c-2", "c-3"));
        compiled.setSql("select 1");
        when(sqlCompiler.compile(any(), any())).thenReturn(compiled);

        QueryResult result = new QueryResult();
        result.setManifestId("manifest-001");
        result.setContentHash("hash-1");
        result.setColumns(List.of("__identity", TestFixtures.CUSTOMER_EMAIL_PROPERTY));
        result.setRows(List.of(
                Map.of("__identity", "c-1", TestFixtures.CUSTOMER_EMAIL_PROPERTY, "zhang@example.com",
                        TestFixtures.BOOLEAN_METRIC_ID, true),
                Map.of("__identity", "c-3", TestFixtures.CUSTOMER_EMAIL_PROPERTY, "li@example.com",
                        TestFixtures.BOOLEAN_METRIC_ID, false)));
        result.setRowCount(2);
        when(queryExecutor.execute(any(), any())).thenReturn(result);

        service(new TimeWindowResolver()).execute(TestFixtures.batchLookup());

        List<QueryResult.LookupRow> lookupRows = result.getLookupRows();
        assertEquals(3, lookupRows.size(), "必须按请求 identities 顺序逐行对齐");
        assertEquals("c-1", lookupRows.get(0).identity());
        assertTrue(lookupRows.get(0).available());
        assertEquals("z***@example.com", lookupRows.get(0).values().get(TestFixtures.CUSTOMER_EMAIL_PROPERTY),
                "displayHints=mask 的属性必须掩码返回");
        assertEquals(Boolean.TRUE, lookupRows.get(0).values().get(TestFixtures.BOOLEAN_METRIC_ID),
                "编译期生成的布尔判定列也必须进入 lookupRows");
        assertEquals("c-2", lookupRows.get(1).identity());
        assertTrue(!lookupRows.get(1).available(), "缺失行统一不可用");
        assertTrue(lookupRows.get(1).values().isEmpty(), "不可用行不返回任何值，避免泄露存在性差异");
        assertTrue(lookupRows.get(2).available());
        assertEquals("z***@example.com", result.getRows().get(0).get(TestFixtures.CUSTOMER_EMAIL_PROPERTY),
                "rows 是另一条返回通道，必须同步掩码，否则掩码形同虚设");
    }

    @Test
    void shouldRejectSingleLookupWhenIdentityHitsMultipleRows() {
        when(manifestService.validateManifest("manifest-001")).thenReturn(TestFixtures.ruleManifest());
        when(requestStateService.findState(any())).thenReturn(java.util.Optional.empty());

        CompiledQuery compiled = new CompiledQuery();
        compiled.setManifestId("manifest-001");
        compiled.setContentHash("hash-1");
        compiled.setMode("LOOKUP");
        compiled.setEntityId(TestFixtures.CUSTOMER_CONCEPT);
        compiled.setAttributes(List.of(TestFixtures.CUSTOMER_EMAIL_PROPERTY));
        compiled.setSql("select 1");
        when(sqlCompiler.compile(any(), any())).thenReturn(compiled);

        QueryResult result = new QueryResult();
        result.setRows(List.of(
                Map.of("__identity", "c-1", TestFixtures.CUSTOMER_EMAIL_PROPERTY, "a@example.com"),
                Map.of("__identity", "c-1", TestFixtures.CUSTOMER_EMAIL_PROPERTY, "b@example.com")));
        result.setRowCount(2);
        when(queryExecutor.execute(any(), any())).thenReturn(result);

        assertThrows(ServiceException.class,
                () -> service(new TimeWindowResolver()).execute(TestFixtures.singleLookup()));
    }

    @Test
    void shouldCompileFunnelWithoutMetricLookup() {
        // 端到端回归：funnel 没有 metric，prepare 必须跳过 JoinPathResolver，
        // 否则规划器 requireMetric(null) 会以「语义资产不存在」误拒合法漏斗。
        PublishedManifest manifest = TestFixtures.funnelManifest();
        TimeExpressionPattern lastNDays = new TimeExpressionPattern();
        lastNDays.setPatternId("tep.last_n_days");
        lastNDays.setPattern("近 N 天");
        lastNDays.setGranularity("DAY");
        manifest.setTimeExpressionPatterns(List.of(lastNDays));
        when(manifestService.validateManifest("manifest-001")).thenReturn(manifest);

        CompiledQuery compiled = new CompiledQuery();
        compiled.setManifestId("manifest-001");
        compiled.setContentHash("hash-1");
        compiled.setSql("with cohort as (select 1) select 1");
        when(sqlCompiler.compile(any(), any())).thenReturn(compiled);

        assertDoesNotThrow(() -> service(new TimeWindowResolver()).compile(TestFixtures.funnelDsl()));
        verify(auditService).record(any());
    }

    @Test
    void shouldMaskPiiPropertyEvenWithoutDisplayHints() {
        // T3.5 兜底：清单只标 sensitivity=PII、没写 displayHints / existenceSensitive 时也必须掩码
        String phoneId = "property.customer.phone";
        PublishedManifest manifest = TestFixtures.lookupManifest();
        OntologySnapshot.Property phone = TestFixtures.property(
                phoneId, TestFixtures.CUSTOMER_CONCEPT, "customer.phone", true, false, false);
        phone.setSensitivity(OntologySnapshot.Sensitivity.PII);
        List<OntologySnapshot.Property> properties = new ArrayList<>(manifest.getOntology().getProperties());
        properties.add(phone);
        manifest.getOntology().setProperties(properties);
        when(manifestService.validateManifest("manifest-001")).thenReturn(manifest);
        when(requestStateService.findState(any())).thenReturn(java.util.Optional.empty());

        CompiledQuery compiled = new CompiledQuery();
        compiled.setManifestId("manifest-001");
        compiled.setContentHash("hash-1");
        compiled.setMode("LOOKUP");
        compiled.setAttributes(List.of(phoneId));
        compiled.setSql("select 1");
        when(sqlCompiler.compile(any(), any())).thenReturn(compiled);

        QueryResult result = new QueryResult();
        result.setColumns(List.of("__identity", phoneId));
        result.setRows(List.of(Map.of("__identity", "c-1", phoneId, "13800000000")));
        result.setRowCount(1);
        when(queryExecutor.execute(any(), any())).thenReturn(result);

        QueryDsl dsl = TestFixtures.singleLookup();
        dsl.setAttributes(List.of(phoneId));
        service(new TimeWindowResolver()).execute(dsl);

        assertEquals("1***", result.getLookupRows().get(0).values().get(phoneId));
        assertEquals("1***", result.getRows().get(0).get(phoneId), "rows 通道也必须同步掩码");
    }

    private static CompiledQuery plannedProduct() {
        CompiledQuery compiled = new CompiledQuery();
        compiled.setManifestId("manifest-001");
        compiled.setContentHash("hash-1");
        compiled.setMetricId(TestFixtures.METRIC_ID);
        compiled.setSql("select sum(amount) from public.fact_order");
        return compiled;
    }

    private static QueryPlan okPlan() {
        QueryPlan plan = new QueryPlan();
        plan.setPlanJson("[{\"Plan\":{\"Total Cost\":1.0}}]");
        plan.setEstimatedCost(1);
        plan.setStatus(QueryPlan.OK);
        return plan;
    }

    private void stubPlan(QueryPlan plan) {
        when(manifestService.validateManifest("manifest-001")).thenReturn(TestFixtures.manifest());
        when(sqlCompiler.compile(any(), any())).thenReturn(plannedProduct());
        when(queryExecutor.explain(any(), any())).thenReturn(plan);
    }

    @Test
    void shouldDryPlanReuseCompiledProductAndRecordAudit() {
        stubPlan(okPlan());

        QueryPlan result = service(new TimeWindowResolver()).dryPlan(TestFixtures.query());

        assertEquals(TestFixtures.METRIC_ID, result.getCompiled().getMetricId(),
                "dry-plan 必须复用同一编译产物");
        ArgumentCaptor<AuditLogCreateReqDTO> captor = ArgumentCaptor.forClass(AuditLogCreateReqDTO.class);
        verify(auditService).record(captor.capture());
        assertEquals("DRY_PLAN", captor.getValue().getAction());
        assertEquals(QueryPlan.OK, captor.getValue().getReasonCode());
        // 调试通道不推进业务状态机：重放已终态的 request_id 不应触发非法迁移
        verify(requestStateService, never()).transition(any(), any(), any(), any());
        verify(requestStateService, never()).initialize(any(), any());
    }

    @Test
    void shouldRejectBlockedDryRunAndStillRecordAudit() {
        QueryPlan blocked = okPlan();
        blocked.setStatus(QueryPlan.BLOCKED);
        blocked.setBlockReason("成本超阈值");
        stubPlan(blocked);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> service(new TimeWindowResolver()).dryRun(TestFixtures.query()));
        assertEquals(PLAN_COST_BLOCKED.getCode(), ex.getCode());
        verify(queryExecutor, never()).executeDryRun(any(), any());
        ArgumentCaptor<AuditLogCreateReqDTO> captor = ArgumentCaptor.forClass(AuditLogCreateReqDTO.class);
        verify(auditService).record(captor.capture());
        assertEquals("DRY_RUN", captor.getValue().getAction());
        assertEquals(QueryPlan.BLOCKED, captor.getValue().getReasonCode());
    }

    @Test
    void shouldExecuteDryRunWithLimitedExecutionAndRecordRowCount() {
        stubPlan(okPlan());
        QueryResult result = new QueryResult();
        result.setColumns(List.of("region", "gmv"));
        result.setRows(List.of(Map.of("region", "east", "gmv", 12)));
        result.setRowCount(1);
        when(queryExecutor.executeDryRun(any(), any())).thenReturn(result);

        DryRunOutcome outcome = service(new TimeWindowResolver()).dryRun(TestFixtures.query());

        assertTrue(outcome.plan().isVerified());
        assertEquals(1, outcome.result().getRowCount());
        verify(queryExecutor).executeDryRun(any(), any());
        ArgumentCaptor<AuditLogCreateReqDTO> captor = ArgumentCaptor.forClass(AuditLogCreateReqDTO.class);
        verify(auditService).record(captor.capture());
        assertEquals("DRY_RUN", captor.getValue().getAction());
        assertEquals(QueryPlan.OK, captor.getValue().getReasonCode());
        assertEquals(1, captor.getValue().getRowCount(), "dry-run 审计必须记录真实行数");

        // 深度审核回归：verified 必须在执行成功后落库，否则回放端点永远显示「未校验」
        ArgumentCaptor<QueryArtifactDO> artifactCaptor = ArgumentCaptor.forClass(QueryArtifactDO.class);
        verify(queryArtifactRepository).upsert(artifactCaptor.capture());
        assertTrue(artifactCaptor.getValue().isVerified(), "dry-run 产物必须带 verified=true");
        assertEquals(QueryPlan.OK, artifactCaptor.getValue().getPlanStatus());
    }

    @Test
    void shouldTransitionCompileRejectionToRejectedState() {
        // 深度审核回归：compile 分支此前缺 catch，fail-closed 拒绝会让状态机停在初始态
        when(manifestService.validateManifest("manifest-001")).thenReturn(TestFixtures.manifest());
        QueryDsl dsl = TestFixtures.query();
        dsl.setMetricId("not_published_metric");

        assertThrows(ServiceException.class,
                () -> service(new TimeWindowResolver()).compile(dsl));

        verify(requestStateService).transition(any(), eq("trace-1"), eq(RequestStatus.REJECTED), any());
    }

    @Test
    void shouldPersistCompiledArtifactForReplay() {
        when(manifestService.validateManifest("manifest-001")).thenReturn(TestFixtures.manifest());
        when(sqlCompiler.compile(any(), any())).thenReturn(plannedProduct());

        service(new TimeWindowResolver()).compile(TestFixtures.query());

        ArgumentCaptor<QueryArtifactDO> captor = ArgumentCaptor.forClass(QueryArtifactDO.class);
        verify(queryArtifactRepository).upsert(captor.capture());
        QueryArtifactDO artifact = captor.getValue();
        assertEquals("tenant-1", artifact.getTenantId());
        assertEquals("user-1", artifact.getUserId());
        assertEquals("trace-1", artifact.getRequestId());
        assertEquals("manifest-001", artifact.getManifestId());
        assertEquals("select sum(amount) from public.fact_order", artifact.getSqlText());
        assertTrue(artifact.getBindValues().startsWith("["), "绑定值必须以 JSON 数组落库");
        assertNull(artifact.getPlanStatus(), "无 EXPLAIN 的编译不写计划状态");
    }

    @Test
    void shouldReplayArtifactWithinTenantAndUser() {
        QueryArtifactDO artifact = new QueryArtifactDO();
        artifact.setRequestId("req-9");
        artifact.setManifestId("manifest-001");
        artifact.setContentHash("hash-1");
        artifact.setMode("QUERY");
        artifact.setMetricId(TestFixtures.METRIC_ID);
        artifact.setSqlText("select 1");
        artifact.setBindValues("[\"2026-10-09T16:00:00.000Z\", 500]");
        artifact.setJoins("[]");
        artifact.setAppliedLimit(500);
        artifact.setPlanStatus(QueryPlan.OK);
        artifact.setEstimatedCost(8.21);
        artifact.setEstimatedRows(3L);
        artifact.setPlanJson("[{\"Plan\":{}}]");
        artifact.setUpdatedAt(LocalDateTime.of(2026, 11, 8, 21, 0));
        when(queryArtifactRepository.selectByRequest("tenant-1", "user-1", "req-9"))
                .thenReturn(Optional.of(artifact));

        QueryPlan plan = service(new TimeWindowResolver()).replayPlan("req-9");

        assertEquals("select 1", plan.getCompiled().getSql());
        assertEquals(List.of("2026-10-09T16:00:00.000Z", 500), plan.getCompiled().getBindValues());
        assertEquals(500, plan.getCompiled().getAppliedLimit());
        assertEquals(8.21, plan.getEstimatedCost());
        assertEquals(3L, plan.getEstimatedRows());
        assertEquals(QueryPlan.OK, plan.getStatus());
        ArgumentCaptor<AuditLogCreateReqDTO> captor = ArgumentCaptor.forClass(AuditLogCreateReqDTO.class);
        verify(auditService).record(captor.capture());
        assertEquals("PLAN_REPLAY", captor.getValue().getAction());
        assertEquals("req-9", captor.getValue().getRequestId(), "审计记被回放的目标请求");
        assertEquals("trace-1", captor.getValue().getTraceId(), "trace 记发起者");
    }

    @Test
    void shouldRejectReplayWhenArtifactMissing() {
        when(queryArtifactRepository.selectByRequest(any(), any(), any())).thenReturn(Optional.empty());

        ServiceException ex = assertThrows(ServiceException.class,
                () -> service(new TimeWindowResolver()).replayPlan("req-missing"));

        assertEquals(ARTIFACT_NOT_FOUND.getCode(), ex.getCode());
        verify(auditService, never()).record(any());
    }

    // ---- 复合意图逐子编译（M2/W4/T4.3）----

    private static QueryDsl compositeDsl(int subCount) {
        QueryDsl dsl = new QueryDsl();
        dsl.setManifestId("manifest-001");
        dsl.setMode(QueryDsl.AnalysisMode.QUERY);
        List<QueryDsl.SubIntent> subs = new ArrayList<>();
        for (int i = 1; i <= subCount; i++) {
            QueryDsl.SubIntent sub = new QueryDsl.SubIntent();
            sub.setSubIntentId("s" + i);
            sub.setMetricId(TestFixtures.METRIC_ID);
            sub.setDimensions(List.of(TestFixtures.DIMENSION_ID));
            subs.add(sub);
        }
        dsl.setSubIntents(subs);
        return dsl;
    }

    private static QueryResult rows(int count) {
        QueryResult result = new QueryResult();
        result.setManifestId("manifest-001");
        result.setContentHash("hash-1");
        result.setColumns(List.of("value"));
        result.setRows(List.of(Map.of("value", count)));
        result.setRowCount(count);
        return result;
    }

    @Test
    void shouldCompileAndExecuteEachSubIntentIndependently() {
        when(manifestService.validateManifest("manifest-001")).thenReturn(TestFixtures.manifest());
        when(sqlCompiler.compile(any(), any())).thenReturn(plannedProduct());
        when(queryExecutor.execute(any(), any())).thenReturn(rows(2), rows(3));

        CompositeOutcome outcome = service(new TimeWindowResolver()).executeComposite(compositeDsl(2));

        assertEquals("trace-1", outcome.requestId());
        assertEquals(5, outcome.totalRowCount());
        assertEquals(List.of("s1", "s2"),
                outcome.results().stream().map(CompositeOutcome.SubResult::subIntentId).toList());
        // 逐子独立编译 + 独立执行，不合并 SQL
        verify(sqlCompiler, times(2)).compile(any(), any());
        verify(queryExecutor, times(2)).execute(any(), any());

        ArgumentCaptor<AuditLogCreateReqDTO> captor = ArgumentCaptor.forClass(AuditLogCreateReqDTO.class);
        verify(auditService, times(3)).record(captor.capture());
        List<AuditLogCreateReqDTO> audits = captor.getAllValues();
        assertEquals("EXECUTE", audits.get(0).getAction());
        assertEquals("trace-1:s1", audits.get(0).getRequestId());
        assertEquals("trace-1", audits.get(0).getParentRequestId());
        assertEquals("s1", audits.get(0).getSubIntentId());
        assertEquals(2, audits.get(0).getRowCount());
        assertEquals("trace-1:s2", audits.get(1).getRequestId());
        assertEquals("COMPOSITE_EXECUTE", audits.get(2).getAction());
        assertEquals(5, audits.get(2).getRowCount(), "父级审计记总量");

        verify(requestStateService).transition(any(), eq("trace-1"), eq(RequestStatus.EXECUTED), any());
        verify(requestStateService).recordRowCount("trace-1", 5);

        ArgumentCaptor<QueryArtifactDO> artifactCaptor = ArgumentCaptor.forClass(QueryArtifactDO.class);
        verify(queryArtifactRepository, times(2)).upsert(artifactCaptor.capture());
        assertEquals(List.of("trace-1:s1", "trace-1:s2"),
                artifactCaptor.getAllValues().stream().map(QueryArtifactDO::getRequestId).toList(),
                "子请求 ID 独立可寻址，回放端点按它取");
    }

    @Test
    void shouldRejectWholeCompositeWhenOneSubFails() {
        when(manifestService.validateManifest("manifest-001")).thenReturn(TestFixtures.manifest());
        when(sqlCompiler.compile(any(), any())).thenReturn(plannedProduct());
        when(queryExecutor.execute(any(), any())).thenReturn(rows(1));
        doThrow(exception(ARTIFACT_NOT_FOUND, "denied")).when(policyService).check(any(), any());

        ServiceException ex = assertThrows(ServiceException.class,
                () -> service(new TimeWindowResolver()).executeComposite(compositeDsl(2)));

        assertEquals(ARTIFACT_NOT_FOUND.getCode(), ex.getCode());
        verify(requestStateService).transition(any(), eq("trace-1"), eq(RequestStatus.REJECTED), any());
        // 两阶段：校验阶段就失败，不能有任何子意图真的查过库
        verify(queryExecutor, never()).execute(any(), any());
        // 失败也要把定位钉在具体子意图上
        ArgumentCaptor<AuditLogCreateReqDTO> captor = ArgumentCaptor.forClass(AuditLogCreateReqDTO.class);
        verify(auditService).record(captor.capture());
        assertEquals("SUB_INTENT_REJECTED", captor.getValue().getAction());
        assertEquals("s1", captor.getValue().getSubIntentId());
        assertEquals(String.valueOf(ARTIFACT_NOT_FOUND.getCode()), captor.getValue().getReasonCode());
    }

    @Test
    void shouldPropagateCompositeLimitToEachSubIntent() {
        // 深度审核 D60：容器级 limit 原先被子意图默认值覆盖（静默丢弃客户端设置）
        when(manifestService.validateManifest("manifest-001")).thenReturn(TestFixtures.manifest());
        when(sqlCompiler.compile(any(), any())).thenReturn(plannedProduct());
        when(queryExecutor.execute(any(), any())).thenReturn(rows(1));

        QueryDsl dsl = compositeDsl(1);
        dsl.setLimit(20);
        service(new TimeWindowResolver()).executeComposite(dsl);

        ArgumentCaptor<QueryDsl> captor = ArgumentCaptor.forClass(QueryDsl.class);
        verify(sqlCompiler).compile(captor.capture(), any());
        assertEquals(20, captor.getValue().getLimit(), "容器 limit 必须下放到子意图");
    }

    @Test
    void shouldRejectCompositeOnSingleRequestChannel() {
        when(manifestService.validateManifest("manifest-001")).thenReturn(TestFixtures.manifest());

        ServiceException ex = assertThrows(ServiceException.class,
                () -> service(new TimeWindowResolver()).compile(compositeDsl(1)));

        assertEquals(DSL_INVALID.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("composite"));
    }

    @Test
    void shouldRejectReplayOfTerminalRequestId() {
        // 深度审核回归：终态 request_id 重放会让后续 transition 抛 IllegalStateException → 500
        when(requestStateService.findState("trace-1")).thenReturn(Optional.of(RequestStatus.REJECTED));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> service(new TimeWindowResolver()).executeComposite(compositeDsl(1)));

        assertEquals(REQUEST_TERMINAL.getCode(), ex.getCode());
        verify(requestStateService, never()).initialize(any(), any());
    }

    @Test
    void shouldRejectReplayOfExecutedRequestId() {
        when(requestStateService.findState("trace-1")).thenReturn(Optional.of(RequestStatus.EXECUTED));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> service(new TimeWindowResolver()).execute(TestFixtures.query()));

        assertEquals(REQUEST_ALREADY_EXECUTED.getCode(), ex.getCode());
        verify(requestStateService, never()).initialize(any(), any());
    }

    // ---- 结果缓存（M2/W4/T4.4）----

    @Test
    void shouldCompileAndStoreOnCacheMiss() {
        when(queryCacheService.isEnabled()).thenReturn(true);
        when(queryCacheService.get(any())).thenReturn(Optional.empty());
        when(manifestService.validateManifest("manifest-001")).thenReturn(TestFixtures.manifest());
        when(sqlCompiler.compile(any(), any())).thenReturn(plannedProduct());
        when(queryExecutor.execute(any(), any())).thenReturn(rows(2));

        service(new TimeWindowResolver()).execute(TestFixtures.query());

        verify(queryExecutor).execute(any(), any());
        verify(queryCacheService).put(any(), eq("manifest-001"),
                eq(TestFixtures.manifest().getContentHash()), eq("QUERY"), any());
        ArgumentCaptor<AuditLogCreateReqDTO> captor = ArgumentCaptor.forClass(AuditLogCreateReqDTO.class);
        verify(auditService).record(captor.capture());
        assertEquals("CACHE_MISS", captor.getValue().getReasonCode());
    }

    @Test
    void shouldReturnCachedResultWithoutCompilingOrExecuting() {
        when(queryCacheService.isEnabled()).thenReturn(true);
        QueryResult cached = rows(3);
        cached.setCached(true);
        when(queryCacheService.get(any())).thenReturn(Optional.of(cached));
        when(manifestService.validateManifest("manifest-001")).thenReturn(TestFixtures.manifest());

        QueryResult result = service(new TimeWindowResolver()).execute(TestFixtures.query());

        assertTrue(result.isCached());
        assertEquals(3, result.getRowCount());
        // 命中后不得再编译或查库
        verify(sqlCompiler, never()).compile(any(), any());
        verify(queryExecutor, never()).execute(any(), any());
        verify(requestStateService).transition(any(), eq("trace-1"), eq(RequestStatus.EXECUTED), any());
        ArgumentCaptor<AuditLogCreateReqDTO> captor = ArgumentCaptor.forClass(AuditLogCreateReqDTO.class);
        verify(auditService).record(captor.capture());
        assertEquals("CACHE_HIT", captor.getValue().getReasonCode());
    }

    @Test
    void shouldBypassCacheEntirelyWhenDisabled() {
        when(queryCacheService.isEnabled()).thenReturn(false);
        when(manifestService.validateManifest("manifest-001")).thenReturn(TestFixtures.manifest());
        when(sqlCompiler.compile(any(), any())).thenReturn(plannedProduct());
        when(queryExecutor.execute(any(), any())).thenReturn(rows(1));

        service(new TimeWindowResolver()).execute(TestFixtures.query());

        verify(queryCacheService, never()).get(any());
        verify(queryCacheService, never()).put(any(), any(), any(), any(), any());
        ArgumentCaptor<AuditLogCreateReqDTO> captor = ArgumentCaptor.forClass(AuditLogCreateReqDTO.class);
        verify(auditService).record(captor.capture());
        assertNull(captor.getValue().getReasonCode(), "关闭缓存时不应打 CACHE_* 标记");
    }

    @Test
    void shouldRejectUnsupportedModeBeforePathPlanning() {
        // 深度审核 R-18：规则型指标没有物理表，模式授权必须先于路径规划，
        // 否则会先撞上 PATH_UNREACHABLE，把「模式不支持」误报成「路径不可达」。
        when(manifestService.validateManifest("manifest-001")).thenReturn(TestFixtures.ruleManifest());
        QueryDsl dsl = TestFixtures.query();
        dsl.setMetricId(TestFixtures.BOOLEAN_METRIC_ID);
        dsl.setDimensions(List.of());

        ServiceException ex = assertThrows(ServiceException.class,
                () -> service(new TimeWindowResolver()).compile(dsl));

        assertEquals(ErrorCodeConstants.ASSET_MODE_NOT_ALLOWED.getCode(), ex.getCode());
    }

}

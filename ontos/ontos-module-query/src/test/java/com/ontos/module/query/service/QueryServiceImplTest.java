package com.ontos.module.query.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ontos.framework.common.exception.ServiceException;
import com.ontos.framework.web.core.context.RequestContext;
import com.ontos.framework.web.core.context.RequestContextHolder;
import com.ontos.module.ontology.core.ManifestPayload.TimeExpressionPattern;
import com.ontos.module.ontology.core.OntologySnapshot;
import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.ontology.service.manifest.ManifestService;
import com.ontos.module.query.core.TestFixtures;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static com.ontos.module.query.core.dsl.QueryDsl.TimeRange.SOURCE_EXPRESSION;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
                new JoinPathResolver(() -> Set.of()));
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
        when(manifestService.validateManifest("manifest-001")).thenReturn(TestFixtures.lookupManifest());
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
        when(manifestService.validateManifest("manifest-001")).thenReturn(TestFixtures.lookupManifest());
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

}

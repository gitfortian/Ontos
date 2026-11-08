package com.ontos.module.query.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import com.ontos.framework.common.exception.ServiceException;
import com.ontos.framework.web.core.context.RequestContext;
import com.ontos.framework.web.core.context.RequestContextHolder;
import com.ontos.module.ontology.core.ManifestPayload.TimeExpressionPattern;
import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.ontology.service.manifest.ManifestService;
import com.ontos.module.query.core.TestFixtures;
import com.ontos.module.query.core.compiler.CompiledQuery;
import com.ontos.module.query.core.compiler.JoinPathResolver;
import com.ontos.module.query.core.compiler.SqlCompiler;
import com.ontos.module.query.core.dsl.DslValidator;
import com.ontos.module.query.core.dsl.QueryDsl;
import com.ontos.module.query.core.executor.QueryExecutor;
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

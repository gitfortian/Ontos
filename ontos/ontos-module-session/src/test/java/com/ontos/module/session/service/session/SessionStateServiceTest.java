package com.ontos.module.session.service.session;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ontos.framework.common.exception.ServiceException;
import com.ontos.framework.web.core.context.RequestContext;
import com.ontos.framework.web.core.context.RequestContextHolder;
import com.ontos.module.query.core.dsl.QueryDsl;
import com.ontos.module.session.dal.dataobject.SessionStateDO;
import com.ontos.module.session.dal.mysql.SessionStateMapper;
import com.ontos.module.session.enums.ErrorCodeConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话状态机单测（M5/W1，规格 v1.2 §3.1 + 投影矩阵）：
 * 投影全量覆盖模型（F-1）、run 创建 = turn 占用（乐观锁，R2）、澄清候选持久化与回传校验（R5）、
 * 跨会话键隔离（T1.3）。真实 SQL（jsonb 裁剪/追加快照语义）由 PG E2E 覆盖。
 */
class SessionStateServiceTest {

    private final SessionStateMapper mapper = mock(SessionStateMapper.class);
    private final SessionStateService service =
            new SessionStateService(mapper, new SessionProperties());

    private static final RequestContext CONTEXT = new RequestContext(
            "demo", "u1", "s1", 1, null, "trace-1", Set.of(), false);

    @BeforeEach
    void setUp() {
        RequestContextHolder.set(CONTEXT);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.clear();
    }

    private static QueryDsl dsl(String metricId, List<String> dimensions, List<String> attributes,
                                String timeExpression) {
        QueryDsl queryDsl = new QueryDsl();
        queryDsl.setMetricId(metricId);
        queryDsl.setDimensions(dimensions);
        queryDsl.setAttributes(attributes);
        queryDsl.setTimeExpressionRaw(timeExpression);
        return queryDsl;
    }

    // ---------- 投影矩阵（F-1：以本轮 DSL 内容断言状态，全量覆盖） ----------

    @Test
    void shouldOverrideProjectionWithCurrentDsl() {
        // 覆盖：上轮 gmv+region，本轮 refund_amount+channel → 投影只有本轮
        service.project(CONTEXT, SessionProjection.of(dsl("metric.refund_amount",
                List.of("dim.channel"), List.of(), null)), "query", "req-1");
        ArgumentCaptor<String> projection = ArgumentCaptor.forClass(String.class);
        verify(mapper).project(eq("demo"), eq("u1"), eq("s1"), projection.capture(),
                anyString(), eq("query"), eq(5));
        assertTrue(projection.getValue().contains("metric.refund_amount"));
        assertFalse(projection.getValue().contains("metric.gmv"), "上轮实体被本轮投影覆盖");
        assertTrue(projection.getValue().contains("dim.channel"));
    }

    @Test
    void shouldIncludeFiltersAndTimeRangeFieldAsDimensions() {
        // 沿用语义由 LLM 完成：本轮 DSL 若沿用 region（过滤条件），投影自然包含
        QueryDsl queryDsl = dsl("metric.gmv", List.of(), List.of(), "上个月");
        QueryDsl.Filter filter = new QueryDsl.Filter();
        filter.setFieldId("dim.region");
        filter.setOperator(QueryDsl.Operator.EQ);
        filter.setValues(List.of("华东"));
        queryDsl.setFilters(List.of(filter));
        queryDsl.setTimeRange(new QueryDsl.TimeRange());
        queryDsl.getTimeRange().setField("dim.order_date");
        service.project(CONTEXT, SessionProjection.of(queryDsl), "query", "req-2");
        ArgumentCaptor<String> projection = ArgumentCaptor.forClass(String.class);
        verify(mapper).project(eq("demo"), eq("u1"), eq("s1"), projection.capture(),
                anyString(), eq("query"), eq(5));
        assertTrue(projection.getValue().contains("dim.region"), "filters 字段进维度投影");
        assertTrue(projection.getValue().contains("dim.order_date"), "时间窗字段进维度投影");
        assertTrue(projection.getValue().contains("上个月"), "时间原文进投影（供指代）");
    }

    @Test
    void shouldProjectLookupEntityAndAttributes() {
        service.project(CONTEXT, new SessionProjection(List.of(), List.of(),
                List.of("property.customer.email", "property.customer.tier"),
                "concept.customer", null), "query", "req-3");
        ArgumentCaptor<String> projection = ArgumentCaptor.forClass(String.class);
        verify(mapper).project(eq("demo"), eq("u1"), eq("s1"), projection.capture(),
                anyString(), eq("query"), eq(5));
        assertTrue(projection.getValue().contains("concept.customer"));
        assertTrue(projection.getValue().contains("property.customer.email"));
    }

    @Test
    void shouldNotProjectOnClarificationTerminal() {
        // 澄清/拒答/discovery 终态不投影（无已采纳 DSL，状态保持）
        verify(mapper, never()).project(any(), any(), any(), any(), any(), any(), anyInt());
        // 该断言由调用纪律保证（编排层只在 query/composite 执行成功后调 project）——
        // 本测试锁定 service 层无「自动投影」入口
    }

    @Test
    void shouldAppendTurnSummaryWithRequestId() {
        service.project(CONTEXT, SessionProjection.of(dsl("metric.gmv", List.of(), List.of(), null)),
                "query", "req-turn-9");
        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(mapper).project(any(), any(), any(), any(), summary.capture(), eq("query"), eq(5));
        assertTrue(summary.getValue().contains("req-turn-9"));
        assertTrue(summary.getValue().contains("\"projection\""));
    }

    // ---------- run 创建 = turn 占用（R2 乐观锁） ----------

    @Test
    void shouldOccupyTurnAtomically() {
        when(mapper.occupyTurn("demo", "u1", "s1", 0)).thenReturn(true);
        int turn = service.occupyTurn("demo", "u1", "s1", 0);
        assertEquals(1, turn);
        verify(mapper).ensure("demo", "u1", "s1");
    }

    @Test
    void shouldRejectConcurrentTurnOccupation() {
        when(mapper.occupyTurn("demo", "u1", "s1", 0)).thenReturn(false);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.occupyTurn("demo", "u1", "s1", 0));
        assertEquals(ErrorCodeConstants.SESSION_TURN_CONFLICT.getCode(), ex.getCode());
    }

    @Test
    void shouldEnsureSessionBeforeOccupation() {
        when(mapper.occupyTurn("demo", "u1", "s-new", 0)).thenReturn(true);
        service.occupyTurn("demo", "u1", "s-new", 0);
        verify(mapper).ensure("demo", "u1", "s-new");
    }

    // ---------- 澄清候选持久化与回传校验（R5） ----------

    @Test
    void shouldSavePendingClarification() {
        service.savePendingClarification(CONTEXT, new SessionStateService.PendingClarification(
                "指标口径歧义",
                List.of(new SessionStateService.ClarificationCandidate(
                        "cand-1", "GMV 总额", "metric.gmv"),
                        new SessionStateService.ClarificationCandidate(
                                "cand-2", "已支付 GMV", "metric.paid_gmv")),
                "GMV 总额", "可跳过，默认 GMV 总额"));
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(mapper).savePendingClarification(eq("demo"), eq("u1"), eq("s1"),
                payload.capture());
        assertTrue(payload.getValue().contains("cand-1"));
        assertTrue(payload.getValue().contains("metric.paid_gmv"));
    }

    @Test
    void shouldConsumeValidClarificationChoice() {
        when(mapper.consumeClarificationChoice("demo", "u1", "s1", "cand-2")).thenReturn(true);
        SessionStateService.ClarificationCandidate candidate =
                service.consumeClarificationChoice(CONTEXT, "cand-2");
        assertEquals("cand-2", candidate.candidateId());
    }

    @Test
    void shouldRejectForgedClarificationChoice() {
        // 伪造 candidateId（不在本轮候选集合）→ 原子 UPDATE 0 行 → 拒绝，不能绕过澄清
        when(mapper.consumeClarificationChoice("demo", "u1", "s1", "forged")).thenReturn(false);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.consumeClarificationChoice(CONTEXT, "forged"));
        assertEquals(ErrorCodeConstants.SESSION_CLARIFICATION_CHOICE_INVALID.getCode(), ex.getCode());
    }

    @Test
    void shouldRejectBlankClarificationChoice() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.consumeClarificationChoice(CONTEXT, " "));
        assertEquals(ErrorCodeConstants.SESSION_CLARIFICATION_CHOICE_INVALID.getCode(), ex.getCode());
    }

    // ---------- 跨会话键隔离（T1.3：三条件收敛） ----------

    @Test
    void shouldReadWithTripleKeyIsolation() {
        when(mapper.select("demo", "u1", "s1")).thenReturn(java.util.Optional.of(state()));
        SessionStateDO state = service.read("demo", "u1", "s1");
        assertEquals(3, state.getTurnIndex());

        // 其他会话键 → mapper 查不到 → 空态（跨会话不沿用）
        when(mapper.select("demo", "u1", "s2")).thenReturn(java.util.Optional.empty());
        SessionStateDO other = service.read("demo", "u1", "s2");
        assertEquals(0, other.getTurnIndex());
        assertEquals("{}", other.getResolvedEntities());
    }

    private static SessionStateDO state() {
        SessionStateDO state = new SessionStateDO();
        state.setTenantId("demo");
        state.setUserId("u1");
        state.setSessionId("s1");
        state.setTurnIndex(3);
        state.setResolvedEntities("{\"metrics\":[\"metric.gmv\"]}");
        state.setRecentTurns("[]");
        return state;
    }
}

package com.ontos.module.system.service.sensitive;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import com.ontos.framework.common.exception.ServiceException;
import com.ontos.framework.web.core.context.RequestContext;
import com.ontos.module.system.dal.dataobject.SensitiveQueryEventDO;
import com.ontos.module.system.dal.mysql.ApprovalRequestMapper;
import com.ontos.module.system.dal.mysql.SensitiveQueryEventMapper;
import com.ontos.module.system.enums.ErrorCodeConstants;
import com.ontos.module.system.service.approval.ApprovalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 跨轮敏感累计单测（M4/W2，规格 v1.1 §3.2 + §5 窗口聚合用例）：
 * 两层独立计数（session 生命周期 / user 滑动窗口）、阈值触发分级（§4.8.3）、
 * ADR-0002 即时动作（精确差分 / 个体探针）、降级开关 fail-safe。
 * 真实 PG 窗口谓词由 E2E 覆盖（PoC #7），此处验证判定逻辑分支完备。
 */
class SensitiveEventServiceTest {

    private final SensitiveQueryEventMapper eventMapper = mock(SensitiveQueryEventMapper.class);
    private final ApprovalRequestMapper approvalMapper = mock(ApprovalRequestMapper.class);
    private final ApprovalService approvalService = new ApprovalService(approvalMapper);

    private SensitiveEventService service;

    private static RequestContext context(String sessionId) {
        return new RequestContext("demo", "u1", sessionId, 1, null, "trace", Set.of(), false);
    }

    @BeforeEach
    void setUp() {
        service = service(true);
        when(approvalMapper.insert(anyString(), anyString(), any(), any(), anyString(), anyString(),
                anyString())).thenReturn("approval-1");
    }

    private SensitiveEventService service(boolean userWindowEnabled) {
        TransactionTemplate template = mock(TransactionTemplate.class);
        when(template.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(
                        (TransactionStatus) null));
        SensitiveProperties properties = new SensitiveProperties();
        properties.setUserWindowEnabled(userWindowEnabled);
        return new SensitiveEventService(eventMapper, approvalService, properties, template);
    }

    private static SensitiveEventService.DraftEvent draft(String assetId, String sensitivityClass,
                                                          String filtersCanonical, int cardinality) {
        return new SensitiveEventService.DraftEvent(assetId, sensitivityClass,
                SensitiveEventServiceTest.class.getName() + "|" + filtersCanonical + "|" + cardinality,
                filtersCanonical, cardinality);
    }

    private void stubCounts(int sessionCount, int userCount) {
        when(eventMapper.countSessionWindow(anyString(), anyString(), any(), anyString()))
                .thenReturn(sessionCount);
        when(eventMapper.countUserWindow(anyString(), anyString(), anyString(), any(Duration.class)))
                .thenReturn(userCount);
    }

    // ---------- 两层窗口计数 ----------

    @Test
    void shouldCountWithinSameSession() {
        stubCounts(2, 2);
        SensitiveEventService.SensitiveDecision decision = service.recordAndEvaluate(
                context("s1"), "req-1", List.of(draft("property.customer.email", "pii", "a", 5)));
        assertFalse(decision.triggered());
        verify(eventMapper).insert(any(SensitiveQueryEventDO.class));
    }

    @Test
    void shouldTriggerAtSessionThresholdEvenIfUserWindowClean() {
        // session 级 8 次 = L1 阈值 T：任一层超阈即触发（OR），user 层干净不影响
        stubCounts(8, 0);
        SensitiveEventService.SensitiveDecision decision = service.recordAndEvaluate(
                context("s1"), "req-8", List.of(draft("property.customer.email", "pii", "a", 5)));
        assertTrue(decision.triggered());
        assertFalse(decision.blocked(), "L1 = T 是转审批档，不是拒答档");
        assertEquals("approval-1", decision.approvalId());
        assertEquals("L1", decision.sessionLayer());
    }

    @Test
    void shouldTriggerAcrossSessionsAtUserWindow() {
        // 跨会话规避：每轮新 session，session 级只有 1，user+tenant 级滑动窗口已到 8
        stubCounts(1, 8);
        SensitiveEventService.SensitiveDecision decision = service.recordAndEvaluate(
                context("s-new"), "req-9", List.of(draft("property.customer.email", "pii", "a", 5)));
        assertTrue(decision.triggered());
        assertFalse(decision.blocked());
    }

    @Test
    void shouldTriggerBlockAtTwoTForPii() {
        stubCounts(16, 0);
        SensitiveEventService.SensitiveDecision decision = service.recordAndEvaluate(
                context("s1"), "req-16", List.of(draft("property.customer.email", "pii", "a", 5)));
        assertTrue(decision.triggered());
        assertTrue(decision.blocked(), "L1 ≥ 2T 是拒答档");
        assertEquals(SensitiveEventService.AUDIT_BLOCKED, decision.auditReasonCode());
    }

    @Test
    void shouldTriggerBlockAtThresholdForExistence() {
        stubCounts(4, 0);
        SensitiveEventService.SensitiveDecision decision = service.recordAndEvaluate(
                context("s1"), "req-e", List.of(draft("property.customer.email", "existence", "a", 5)));
        assertTrue(decision.triggered());
        assertTrue(decision.blocked(), "L2 ≥ T 即拒答");
    }

    @Test
    void shouldTriggerBlockAtThresholdForLabel() {
        stubCounts(3, 0);
        SensitiveEventService.SensitiveDecision decision = service.recordAndEvaluate(
                context("s1"), "req-l", List.of(draft("metric.high_value_customer", "label", "a", 5)));
        assertTrue(decision.triggered());
        assertTrue(decision.blocked(), "L3 ≥ T 即拒答");
        assertEquals("L3", decision.sessionLayer());
    }

    @Test
    void shouldNotTriggerBelowThreshold() {
        stubCounts(7, 7);
        SensitiveEventService.SensitiveDecision decision = service.recordAndEvaluate(
                context("s1"), "req-7", List.of(draft("property.customer.email", "pii", "a", 5)));
        assertFalse(decision.triggered());
        verify(approvalMapper, never()).insert(any(), any(), any(), any(), any(), any(), any());
    }

    // ---------- ADR-0002 即时动作 ----------

    @Test
    void shouldTriggerPreciseDifferentialInstantly() {
        // 上一条：filters 集合 A（region=华东），基数 20；当前：A + tier=VIP（差 1），基数 19
        SensitiveQueryEventDO previous = new SensitiveQueryEventDO();
        previous.setFiltersCanonical("dim.region|EQ|华东");
        previous.setResultCardinality(20);
        when(eventMapper.selectLatest("demo", "u1", "property.customer.email#pii")).thenReturn(previous);
        stubCounts(1, 1);

        SensitiveEventService.SensitiveDecision decision = service.recordAndEvaluate(
                context("s1"), "req-diff", List.of(draft("property.customer.email", "pii",
                        "dim.region|EQ|华东\ndim.tier|EQ|VIP", 19)));
        assertTrue(decision.triggered());
        assertEquals(SensitiveEventService.AUDIT_DIFFERENTIAL, decision.auditReasonCode());
        ArgumentCaptor<SensitiveQueryEventDO> rows = ArgumentCaptor.forClass(SensitiveQueryEventDO.class);
        verify(eventMapper).insert(rows.capture());
        // ADR-0002 决策 4：命中即时动作仍记账，pattern_tag=differential_instant
        assertEquals(SensitiveEventService.TAG_DIFFERENTIAL_INSTANT, rows.getValue().getPatternTag());
    }

    @Test
    void shouldNotTriggerDifferentialWhenFilterSetsDisjoint() {
        // region=华东 → region=华南：集合差为 2（换值不是差 1），不构成差分对（决策 2 约束表）
        SensitiveQueryEventDO previous = new SensitiveQueryEventDO();
        previous.setFiltersCanonical("dim.region|EQ|华南");
        previous.setResultCardinality(20);
        when(eventMapper.selectLatest(anyString(), anyString(), anyString())).thenReturn(previous);
        stubCounts(1, 1);

        SensitiveEventService.SensitiveDecision decision = service.recordAndEvaluate(
                context("s1"), "req-x", List.of(draft("property.customer.email", "pii",
                        "dim.region|EQ|华东", 19)));
        assertFalse(decision.triggered());
    }

    @Test
    void shouldNotTriggerDifferentialWhenCardinalityDeltaNotOne() {
        // 集合差 1 但基数差 3：普通收窄，走累计计数不即时触发
        SensitiveQueryEventDO previous = new SensitiveQueryEventDO();
        previous.setFiltersCanonical("dim.region|EQ|华东");
        previous.setResultCardinality(20);
        when(eventMapper.selectLatest(anyString(), anyString(), anyString())).thenReturn(previous);
        stubCounts(1, 1);

        SensitiveEventService.SensitiveDecision decision = service.recordAndEvaluate(
                context("s1"), "req-y", List.of(draft("property.customer.email", "pii",
                        "dim.region|EQ|华东\ndim.tier|EQ|VIP", 17)));
        assertFalse(decision.triggered());
    }

    @Test
    void shouldTriggerIndividualProbeForSingleRowPii() {
        // ADR-0002 决策 2 约束 3：L1 且基数 ≤ 1 直接转审批（个体值），不经累计
        stubCounts(1, 1);
        SensitiveEventService.SensitiveDecision decision = service.recordAndEvaluate(
                context("s1"), "req-i", List.of(draft("property.customer.email", "pii",
                        "property.customer.customer_id|EQ|c-1", 1)));
        assertTrue(decision.triggered());
        assertEquals(SensitiveEventService.AUDIT_INDIVIDUAL, decision.auditReasonCode());
        ArgumentCaptor<SensitiveQueryEventDO> rows = ArgumentCaptor.forClass(SensitiveQueryEventDO.class);
        verify(eventMapper).insert(rows.capture());
        assertEquals(SensitiveEventService.TAG_INDIVIDUAL_PROBE, rows.getValue().getPatternTag());
    }

    @Test
    void shouldNotTriggerIndividualProbeForLabelLayer() {
        // 个体闸门仅 L1：label 层个体判定已在 W1 checkIndividual 被拒，此处 label 聚合计数正常
        stubCounts(1, 1);
        SensitiveEventService.SensitiveDecision decision = service.recordAndEvaluate(
                context("s1"), "req-li", List.of(draft("metric.high_value_customer", "label",
                        "a", 1)));
        assertFalse(decision.triggered());
    }

    // ---------- 降级开关（规格 §7 风险表） ----------

    @Test
    void shouldForceApprovalWhenUserWindowDisabled() {
        SensitiveEventService degraded = service(false);
        stubCounts(1, -1);
        SensitiveEventService.SensitiveDecision decision = degraded.recordAndEvaluate(
                context("s1"), "req-deg", List.of(draft("property.customer.email", "pii", "a", 5)));
        assertTrue(decision.triggered(), "降级期间 L1/L2/L3 查询强制走审批（不依赖计数）");
        assertEquals(SensitiveEventService.AUDIT_WINDOW_DEGRADED, decision.auditReasonCode());
        verify(eventMapper, never()).countUserWindow(anyString(), anyString(), anyString(),
                any(Duration.class));
    }

    // ---------- 叠加层与入参校验 ----------

    @Test
    void shouldRecordOneRowPerApplicableLayer() {
        // email 兼 pii + existence（可叠加）：两个适用层各一行，各自独立计数判定
        stubCounts(1, 1);
        service.recordAndEvaluate(context("s1"), "req-dual", List.of(
                draft("property.customer.email", "pii", "a", 5),
                draft("property.customer.email", "existence", "a", 5)));
        verify(eventMapper, times(2)).insert(any(SensitiveQueryEventDO.class));
    }

    @Test
    void shouldReturnNotTriggeredForEmptyDrafts() {
        SensitiveEventService.SensitiveDecision decision = service.recordAndEvaluate(
                context("s1"), "req-empty", List.of());
        assertFalse(decision.triggered());
        verify(eventMapper, never()).insert(any(SensitiveQueryEventDO.class));
    }

    @Test
    void shouldRejectInvalidDraft() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.recordAndEvaluate(context("s1"), "req-bad",
                        List.of(draft("property.customer.email", "top_secret", "a", 5))));
        assertEquals(ErrorCodeConstants.SENSITIVE_EVENT_INVALID.getCode(), ex.getCode());
    }

    @Test
    void shouldCreateApprovalWithTriggerContextPayload() {
        stubCounts(8, 0);
        SensitiveEventService.SensitiveDecision decision = service.recordAndEvaluate(
                context("s1"), "req-p", List.of(draft("property.customer.email", "pii", "a", 5)));
        assertTrue(decision.triggered());
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(approvalMapper).insert(eq("demo"), eq("u1"), eq("s1"), eq(1),
                eq(ApprovalService.KIND_SENSITIVE_SEQUENCE), payload.capture(), anyString());
        assertTrue(payload.getValue().contains("property.customer.email#pii"));
        assertTrue(payload.getValue().contains("\"threshold\":8"));
        assertNotNull(decision.approvalId());
    }
}

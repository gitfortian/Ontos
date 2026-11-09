package com.ontos.module.system.service.approval;

import java.util.Map;

import com.ontos.framework.common.exception.ServiceException;
import com.ontos.module.system.dal.dataobject.ApprovalRequestDO;
import com.ontos.module.system.dal.mysql.ApprovalRequestMapper;
import com.ontos.module.system.enums.ErrorCodeConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审批服务单测（M4/W2/T2.3）：决策人 ≠ 申请人、APPROVED 生成放行目标指纹、
 * 指纹公式一致性（决策侧与消费侧同源）、payload 反解防御。
 * 消费 SQL 的原子语义（单次有效/指纹逐字段比对）由真实库 E2E 覆盖。
 */
class ApprovalServiceTest {

    private final ApprovalRequestMapper mapper = mock(ApprovalRequestMapper.class);
    private final ApprovalService service = new ApprovalService(mapper);

    private static final String PAYLOAD = """
            {"requestId":"req-1","sensitiveKey":"property.customer.email#pii",
             "sensitivityClass":"pii","filtersHash":"abc123","scopeHash":"scope456",
             "sessionCount":8,"userWindowCount":0,"threshold":8}
            """;

    @BeforeEach
    void setUp() {
        when(mapper.selectById("apr-1", "demo")).thenReturn(java.util.Optional.of(request()));
    }

    private static ApprovalRequestDO request() {
        ApprovalRequestDO request = new ApprovalRequestDO();
        request.setId("apr-1");
        request.setTenantId("demo");
        request.setUserId("applicant-1");
        request.setKind(ApprovalService.KIND_SENSITIVE_SEQUENCE);
        request.setStatus("PENDING");
        request.setPayload(PAYLOAD);
        return request;
    }

    @Test
    void shouldRejectSelfDecision() {
        ServiceException ex = assertThrows(ServiceException.class, () -> service.decide(
                "apr-1", "demo", "APPROVED", null, "applicant-1"));
        assertEquals(ErrorCodeConstants.APPROVAL_SELF_DECISION.getCode(), ex.getCode());
        verify(mapper, org.mockito.Mockito.never()).decide(any(), any(), any(), any(), any(), any());
    }

    @Test
    void shouldGenerateFingerprintOnApprove() {
        when(mapper.decide(any(), any(), any(), any(), any(), any())).thenReturn(1);
        service.decide("apr-1", "demo", "APPROVED", null, "approver-1");

        ArgumentCaptor<String> fingerprint = ArgumentCaptor.forClass(String.class);
        verify(mapper).decide(eq("apr-1"), eq("demo"), eq("APPROVED"), isNull(),
                eq("approver-1"), fingerprint.capture());
        // 指纹公式：hash(userId|tenantId|sensitiveKey|scopeHash|filtersHash)——与消费侧同源
        String expected = ApprovalService.fingerprint("applicant-1", "demo",
                "property.customer.email#pii", "scope456", "abc123");
        assertEquals(expected, fingerprint.getValue());
        assertEquals(64, fingerprint.getValue().length());
    }

    @Test
    void shouldNotGenerateFingerprintOnReject() {
        when(mapper.decide(any(), any(), any(), any(), any(), any())).thenReturn(1);
        service.decide("apr-1", "demo", "REJECTED", "不批准", "approver-1");
        verify(mapper).decide(eq("apr-1"), eq("demo"), eq("REJECTED"), eq("不批准"),
                eq("approver-1"), isNull());
    }

    @Test
    void shouldFailClosedWhenPayloadMissingFingerprintFields() {
        ApprovalRequestDO broken = request();
        broken.setPayload("{\"requestId\":\"req-1\"}");
        when(mapper.selectById("apr-1", "demo")).thenReturn(java.util.Optional.of(broken));
        ServiceException ex = assertThrows(ServiceException.class, () -> service.decide(
                "apr-1", "demo", "APPROVED", null, "approver-1"));
        assertEquals(ErrorCodeConstants.APPROVAL_PAYLOAD_INVALID.getCode(), ex.getCode());
    }

    @Test
    void shouldRejectInvalidDecisionVerb() {
        ServiceException ex = assertThrows(ServiceException.class, () -> service.decide(
                "apr-1", "demo", "MAYBE", null, "approver-1"));
        assertEquals(ErrorCodeConstants.APPROVAL_DECISION_INVALID.getCode(), ex.getCode());
    }

    @Test
    void shouldDelegateGrantConsumption() {
        String fingerprint = ApprovalService.fingerprint("u1", "demo",
                "property.customer.email#pii", "scope", "hash");
        when(mapper.consumeGrant("demo", "u1", "apr-1", fingerprint)).thenReturn(true);
        assertTrue(service.consumeGrant("demo", "u1", "apr-1", fingerprint));
    }
}

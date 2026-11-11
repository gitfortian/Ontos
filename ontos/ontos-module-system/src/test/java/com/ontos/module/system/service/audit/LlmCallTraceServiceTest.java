package com.ontos.module.system.service.audit;

import com.ontos.framework.common.trace.LlmCallRecord;
import com.ontos.module.system.dal.dataobject.LlmCallTraceDO;
import com.ontos.module.system.dal.mysql.LlmCallTraceMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 模型调用追踪落库单测（M7/W5/T5.1）：
 * 字段映射与截断、无租户跳过、写入失败不阻断业务。
 */
class LlmCallTraceServiceTest {

    private final LlmCallTraceMapper mapper = mock(LlmCallTraceMapper.class);

    private LlmCallTraceService service;

    @BeforeEach
    void setUp() {
        service = new LlmCallTraceService(mapper);
    }

    private static LlmCallRecord record(String tenantId, String errorMessage) {
        return new LlmCallRecord(tenantId, "trace-9", "s-9", 3, "ask.intent", "gpt-4o-mini",
                120, 30, 150, 842L, "error", "ModelHttpException", errorMessage, "stop");
    }

    @Test
    void mapsAllFieldsAndTruncatesLongError() {
        String longMessage = "x".repeat(LlmCallTraceService.MAX_ERROR_MESSAGE + 100);

        service.record(record("demo", longMessage));

        ArgumentCaptor<LlmCallTraceDO> captor = ArgumentCaptor.forClass(LlmCallTraceDO.class);
        verify(mapper).insert(captor.capture());
        LlmCallTraceDO saved = captor.getValue();
        assertEquals("demo", saved.getTenantId());
        assertEquals("trace-9", saved.getTraceId());
        assertEquals("s-9", saved.getSessionId());
        assertEquals(3, saved.getTurnIndex());
        assertEquals("ask.intent", saved.getPurpose());
        assertEquals("gpt-4o-mini", saved.getModel());
        assertEquals(120, saved.getInputTokens());
        assertEquals(30, saved.getOutputTokens());
        assertEquals(150, saved.getTotalTokens());
        assertEquals(842L, saved.getLatencyMs());
        assertEquals("error", saved.getStatus());
        assertEquals("ModelHttpException", saved.getErrorType());
        assertEquals(LlmCallTraceService.MAX_ERROR_MESSAGE, saved.getErrorMessage().length(),
                "错误信息按列宽截断");
    }

    @Test
    void skipsRowsWithoutTenant() {
        service.record(record("  ", "boom"));
        service.record(null);

        verify(mapper, never()).insert(any());
    }

    @Test
    void writeFailureDoesNotPropagate() {
        doThrow(new IllegalStateException("DB down")).when(mapper).insert(any());

        assertDoesNotThrow(() -> service.record(record("demo", "boom")));
    }

    @Test
    void blankOptionalFieldsBecomeNull() {
        LlmCallRecord sparse = new LlmCallRecord("demo", " ", "", null, null, null,
                0, 0, 0, 1L, "success", null, null, null);

        service.record(sparse);

        ArgumentCaptor<LlmCallTraceDO> captor = ArgumentCaptor.forClass(LlmCallTraceDO.class);
        verify(mapper).insert(captor.capture());
        LlmCallTraceDO saved = captor.getValue();
        assertNull(saved.getTraceId());
        assertNull(saved.getSessionId());
        assertNull(saved.getPurpose());
        assertNull(saved.getModel());
        assertTrue(saved.getInputTokens() == 0);
    }
}

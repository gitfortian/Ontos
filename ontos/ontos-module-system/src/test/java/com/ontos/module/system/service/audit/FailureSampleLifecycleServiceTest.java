package com.ontos.module.system.service.audit;

import java.util.Map;

import com.ontos.framework.common.exception.ServiceException;
import com.ontos.module.system.dal.dataobject.EvaluationFailureSampleDO;
import com.ontos.module.system.dal.mysql.EvaluationFailureSampleLifecycleMapper;
import com.ontos.module.system.dal.mysql.EvaluationFailureSampleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static com.ontos.module.system.enums.ErrorCodeConstants.FAILURE_SAMPLE_CATEGORY_INVALID;
import static com.ontos.module.system.enums.ErrorCodeConstants.FAILURE_SAMPLE_INVALID;
import static com.ontos.module.system.enums.ErrorCodeConstants.FAILURE_SAMPLE_REGRESSION_INVALID;
import static com.ontos.module.system.enums.ErrorCodeConstants.FAILURE_SAMPLE_TRANSITION_INVALID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 失败样本生命周期单测（M7/W4/T4.1–T4.3）：
 * 状态机封闭、REGRESSED 只能走回流、回流目标禁止 test、分类闭词表、租户同形拒绝。
 */
class FailureSampleLifecycleServiceTest {

    private final EvaluationFailureSampleMapper sampleMapper =
            mock(EvaluationFailureSampleMapper.class);
    private final EvaluationFailureSampleLifecycleMapper lifecycleMapper =
            mock(EvaluationFailureSampleLifecycleMapper.class);

    private FailureSampleLifecycleService service;

    @BeforeEach
    void setUp() {
        service = new FailureSampleLifecycleService(sampleMapper, lifecycleMapper);
        EvaluationFailureSampleDO sample = new EvaluationFailureSampleDO();
        sample.setId(1L);
        sample.setSetId("test-v5");
        sample.setQuestionId("t-x");
        when(sampleMapper.selectById("demo", 1L)).thenReturn(sample);
    }

    @Test
    void openToTriagedIsAllowedAndEventAppended() {
        when(lifecycleMapper.selectLatestStatus("demo", 1L)).thenReturn(null);

        Map<String, Object> view = service.transition("demo", 1L, "triaged", "alice", "start");

        assertEquals("triaged", view.get("status"));
        verify(lifecycleMapper).insertStatusEvent("demo", 1L, "open", "triaged", "alice", "start");
    }

    @Test
    void illegalTransitionIsRejected() {
        when(lifecycleMapper.selectLatestStatus("demo", 1L)).thenReturn(null);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.transition("demo", 1L, "fixed", "alice", null));
        assertEquals(FAILURE_SAMPLE_TRANSITION_INVALID.getCode(), ex.getCode());
        verify(lifecycleMapper, never()).insertStatusEvent(anyString(), anyLong(),
                anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void regressedCannotBeSetByPlainTransition() {
        when(lifecycleMapper.selectLatestStatus("demo", 1L)).thenReturn("fixed");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.transition("demo", 1L, "regressed", "alice", null));
        assertEquals(FAILURE_SAMPLE_TRANSITION_INVALID.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("退回回归集"));
    }

    @Test
    void classifyRejectsUnknownCategory() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.classify("demo", 1L, "NOT_A_CATEGORY", "alice", null));
        assertEquals(FAILURE_SAMPLE_CATEGORY_INVALID.getCode(), ex.getCode());
        verify(lifecycleMapper, never()).insertClassification(anyString(), anyLong(),
                anyString(), anyString(), anyString());
    }

    @Test
    void classifyWritesClosedVocabularyCode() {
        service.classify("demo", 1L, "scenario_scope", "alice", "scope 越界");
        verify(lifecycleMapper).insertClassification("demo", 1L, "SCENARIO_SCOPE", "alice",
                "scope 越界");
    }

    @Test
    void returnToRegressionRequiresFixedStatus() {
        when(lifecycleMapper.selectLatestStatus("demo", 1L)).thenReturn("triaged");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.returnToRegression("demo", 1L, "dev", "alice", null));
        assertEquals(FAILURE_SAMPLE_TRANSITION_INVALID.getCode(), ex.getCode());
    }

    @Test
    void returnToRegressionRejectsTestTarget() {
        when(lifecycleMapper.selectLatestStatus("demo", 1L)).thenReturn("fixed");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.returnToRegression("demo", 1L, "test", "alice", null));
        assertEquals(FAILURE_SAMPLE_REGRESSION_INVALID.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("test 集冻结"));
    }

    @Test
    void returnToRegressionWritesEventWithTargetAndSampleId() {
        when(lifecycleMapper.selectLatestStatus("demo", 1L)).thenReturn("fixed");

        Map<String, Object> view = service.returnToRegression("demo", 1L, "DEV", "alice", "fix-123");

        assertEquals("regressed", view.get("status"));
        assertEquals("dev", view.get("targetSet"));
        assertTrue(String.valueOf(view.get("regressionSampleId")).startsWith("reg-1-"));
        ArgumentCaptor<String> note = ArgumentCaptor.forClass(String.class);
        verify(lifecycleMapper).insertStatusEvent(eq("demo"), eq(1L), eq("fixed"),
                eq("regressed"), eq("alice"), note.capture());
        assertTrue(note.getValue().contains("targetSet=dev"));
        assertTrue(note.getValue().contains("regressionSampleId=reg-1-"));
        assertTrue(note.getValue().contains("fix-123"));
    }

    @Test
    void unknownSampleIsRejectedSameShape() {
        when(sampleMapper.selectById("demo", 9L)).thenReturn(null);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.detail("demo", 9L, 50));
        assertEquals(FAILURE_SAMPLE_INVALID.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("不存在或不属于当前租户"));
    }
}

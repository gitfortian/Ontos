package com.ontos.module.system.service.audit;

import java.util.List;
import java.util.Map;

import com.ontos.framework.common.exception.ServiceException;
import com.ontos.module.system.controller.admin.audit.vo.FailureDashboardRespVO;
import com.ontos.module.system.controller.admin.audit.vo.FailureSampleRegisterReqVO;
import com.ontos.module.system.dal.dataobject.EvaluationFailureSampleDO;
import com.ontos.module.system.dal.mysql.EvaluationFailureSampleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static com.ontos.module.system.enums.ErrorCodeConstants.FAILURE_SAMPLE_INVALID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 失败看板服务单测（M6/W5/T5.1，OBL-M7 交付物 ④）：
 * 批量登记 fail-closed 校验（必填/批量上限/空批）、五维下钻聚合装配、最近样本。
 * SQL 谓词由真库 E2E 覆盖（runner 登记通路 + 前端消费）。
 */
class FailureSampleServiceTest {

    private final EvaluationFailureSampleMapper mapper = mock(EvaluationFailureSampleMapper.class);

    private FailureSampleService service;

    @BeforeEach
    void setUp() {
        service = new FailureSampleService(mapper);
    }

    private static FailureSampleRegisterReqVO.Item sample(String questionId) {
        FailureSampleRegisterReqVO.Item item = new FailureSampleRegisterReqVO.Item();
        item.setQuestionId(questionId);
        item.setScenarioEntryMode("manual");
        item.setQueryShape("scenario_mode");
        item.setTurnType("single");
        item.setIsComposite(false);
        item.setRiskType("normal");
        item.setExpectedTerminal("query");
        item.setActualTerminal("out_of_capability");
        item.setErrorCode(1004000007L);
        item.setReason("scope 越界");
        return item;
    }

    @Test
    void registerPersistsAllFieldsAndTenantScoped() {
        FailureSampleRegisterReqVO reqVO = new FailureSampleRegisterReqVO();
        reqVO.setSetId("test-v3");
        reqVO.setRunId("run-1");
        reqVO.setSamples(List.of(sample("sm-manual-05")));
        when(mapper.insertBatch(anyString(), anyList())).thenReturn(1);

        int inserted = service.register("demo", reqVO);

        assertEquals(1, inserted);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EvaluationFailureSampleDO>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(mapper).insertBatch(eq("demo"), captor.capture());
        EvaluationFailureSampleDO record = captor.getValue().get(0);
        assertEquals("test-v3", record.getSetId());
        assertEquals("run-1", record.getRunId());
        assertEquals("sm-manual-05", record.getQuestionId());
        assertEquals("manual", record.getScenarioEntryMode());
        assertEquals("scenario_mode", record.getQueryShape());
        assertEquals(Boolean.FALSE, record.getIsComposite());
        assertEquals(1004000007L, record.getErrorCode());
    }

    @Test
    void registerRejectsMissingMandatoryDims() {
        FailureSampleRegisterReqVO reqVO = new FailureSampleRegisterReqVO();
        reqVO.setSetId("test-v3");
        reqVO.setRunId("run-1");
        FailureSampleRegisterReqVO.Item item = sample("t-x");
        item.setRiskType(" ");
        reqVO.setSamples(List.of(item));

        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.register("demo", reqVO));
        assertEquals(FAILURE_SAMPLE_INVALID.getCode(), exception.getCode());
        verify(mapper, never()).insertBatch(anyString(), anyList());
    }

    @Test
    void registerRejectsMissingSetIdOrRunId() {
        FailureSampleRegisterReqVO noRun = new FailureSampleRegisterReqVO();
        noRun.setSetId("test-v3");
        noRun.setSamples(List.of(sample("t-x")));
        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.register("demo", noRun));
        assertEquals(FAILURE_SAMPLE_INVALID.getCode(), exception.getCode());
    }

    @Test
    void registerRejectsOversizedBatch() {
        FailureSampleRegisterReqVO reqVO = new FailureSampleRegisterReqVO();
        reqVO.setSetId("test-v3");
        reqVO.setRunId("run-1");
        reqVO.setSamples(java.util.stream.IntStream.range(0, FailureSampleService.MAX_BATCH_SIZE + 1)
                .mapToObj(i -> sample("t-" + i)).toList());
        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.register("demo", reqVO));
        assertEquals(FAILURE_SAMPLE_INVALID.getCode(), exception.getCode());
    }

    @Test
    void registerEmptyBatchIsNoop() {
        FailureSampleRegisterReqVO reqVO = new FailureSampleRegisterReqVO();
        reqVO.setSetId("test-v3");
        reqVO.setRunId("run-1");
        reqVO.setSamples(List.of());
        assertEquals(0, service.register("demo", reqVO));
        verify(mapper, never()).insertBatch(anyString(), anyList());
    }

    @Test
    void dashboardAssemblesAllFiveDimensions() {
        when(mapper.count("demo", "test-v3")).thenReturn(3L);
        // 单桩分流：risk_type 返回分布，其余维度空（Mockito 后置 anyString 桩会覆盖专用桩）
        when(mapper.drillDown(eq("demo"), eq("test-v3"), anyString()))
                .thenAnswer(invocation -> "risk_type".equals(invocation.getArgument(2))
                        ? List.of(Map.of("value", "prompt_injection", "count", 2L),
                                Map.of("value", "normal", "count", 1L))
                        : List.of());
        when(mapper.selectRecent("demo", "test-v3", 50)).thenReturn(List.of(new EvaluationFailureSampleDO()));

        FailureDashboardRespVO dashboard = service.dashboard("demo", "test-v3", 50);

        assertEquals(3L, dashboard.getTotal());
        assertEquals(EvaluationFailureSampleMapper.DRILL_DIMENSIONS.size(),
                dashboard.getDimensions().size());
        assertTrue(dashboard.getDimensions().containsKey("scenario_entry_mode"));
        assertTrue(dashboard.getDimensions().containsKey("is_composite"));
        assertEquals(2L, dashboard.getDimensions().get("risk_type").get(0).get("count"));
        assertEquals(1, dashboard.getRecentSamples().size());
        // 五维全部下钻（白名单逐维调用）
        for (String dimension : EvaluationFailureSampleMapper.DRILL_DIMENSIONS) {
            verify(mapper).drillDown("demo", "test-v3", dimension);
        }
    }

}

package com.ontos.module.agent.service.event;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.ontos.framework.common.util.json.JsonUtils;
import com.ontos.module.agent.dal.mysql.AskRunEventMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 过程事件契约（过程可见性扩展）：步骤 / 工具事件必须能被前端直接渲染，
 * 且实时下发与 afterSeq 重放**同形**——否则断线重连后过程面板会变形。
 */
class AskEventServiceTest {

    private final AskRunEventMapper mapper = Mockito.mock(AskRunEventMapper.class);
    private final AskEventService service = new AskEventService(mapper);

    @SuppressWarnings("unchecked")
    private static Map<String, Object> valueOf(String payloadJson) {
        Map<String, Object> event = JsonUtils.parseMap(payloadJson);
        return (Map<String, Object>) event.get("value");
    }

    @Test
    void stepEventCarriesKeyTitleStatusAndBusinessDetail() {
        Mockito.when(mapper.append(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
                .thenReturn(7L);

        service.emitStep("run-1", AskStep.RETRIEVE, AskStep.Status.DONE,
                "命中 2 项：成交金额、大区", 8L);

        ArgumentCaptor<String> type = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        Mockito.verify(mapper).append(Mockito.eq("run-1"), type.capture(), payload.capture());

        assertEquals(AskEventService.CUSTOM, type.getValue());
        Map<String, Object> event = JsonUtils.parseMap(payload.getValue());
        assertEquals(AskEventService.STEP_EVENT_NAME, event.get("name"));

        Map<String, Object> value = valueOf(payload.getValue());
        assertEquals("retrieve", value.get("stepKey"));
        assertEquals("匹配已发布资产", value.get("title"));
        assertEquals("done", value.get("status"));
        assertEquals("命中 2 项：成交金额、大区", value.get("detail"));
        assertEquals(8L, ((Number) value.get("elapsedMs")).longValue());
    }

    @Test
    void runningStepOmitsDetailAndElapsed() {
        Mockito.when(mapper.append(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
                .thenReturn(1L);

        service.emitStep("run-2", AskStep.UNDERSTAND, AskStep.Status.RUNNING, null, null);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        Mockito.verify(mapper).append(Mockito.eq("run-2"), Mockito.anyString(), payload.capture());
        Map<String, Object> value = valueOf(payload.getValue());
        assertEquals("running", value.get("status"));
        assertFalse(value.containsKey("detail"));
        assertFalse(value.containsKey("elapsedMs"));
    }

    @Test
    void toolEventsExposeBusinessSummaryOnly() {
        Mockito.when(mapper.append(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
                .thenReturn(3L);

        service.emitToolStart("run-3", "execute_query", List.of("指标 成交金额", "维度 大区"));
        service.emitToolEnd("run-3", "execute_query", "succeeded",
                List.of("返回 5 行", "未截断"), 128L);

        ArgumentCaptor<String> type = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        Mockito.verify(mapper, Mockito.times(2))
                .append(Mockito.eq("run-3"), type.capture(), payload.capture());

        assertEquals(List.of(AskEventService.TOOL_CALL_START, AskEventService.TOOL_CALL),
                type.getAllValues());
        Map<String, Object> end = JsonUtils.parseMap(payload.getAllValues().get(1));
        assertEquals("execute_query", end.get("toolName"));
        assertEquals("succeeded", end.get("status"));
        assertEquals(128L, ((Number) end.get("elapsedMs")).longValue());
        assertEquals(List.of("返回 5 行", "未截断"), end.get("resultSummary"));
    }

    @Test
    void liveChannelReceivesSameShapeAsReplay() {
        Mockito.when(mapper.append(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
                .thenReturn(9L);
        List<Map<String, Object>> pushed = new ArrayList<>();
        service.bindLiveChannel(pushed::add);
        try {
            service.emitStep("run-4", AskStep.EXECUTE, AskStep.Status.DONE, "返回 5 行", 128L);
        } finally {
            service.clearLiveChannel();
        }

        assertEquals(1, pushed.size());
        Map<String, Object> live = pushed.get(0);
        assertEquals(9L, live.get("seq"));
        assertEquals(AskEventService.CUSTOM, live.get("type"));
        // payload 为 JSON 文本（与 replayAfter 的 payload 列同形），不是对象
        assertTrue(live.get("payload") instanceof String);
        Map<String, Object> replayLike = JsonUtils.parseMap(String.valueOf(live.get("payload")));
        assertEquals(AskEventService.STEP_EVENT_NAME, replayLike.get("name"));
    }

    @Test
    void clearedChannelStopsDelivery() {
        Mockito.when(mapper.append(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
                .thenReturn(2L);
        List<Map<String, Object>> pushed = new ArrayList<>();
        service.bindLiveChannel(pushed::add);
        service.clearLiveChannel();

        service.emitStep("run-5", AskStep.ASSEMBLE, AskStep.Status.DONE, "已完成", null);

        assertTrue(pushed.isEmpty());
    }

    @Test
    void runStartedInputIsStillRedactedBeforeBothSinks() {
        Mockito.when(mapper.append(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
                .thenReturn(1L);
        List<Map<String, Object>> pushed = new ArrayList<>();
        service.bindLiveChannel(pushed::add);
        try {
            service.emit("run-6", AskEventService.RUN_STARTED,
                    Map.of("input", Map.of("utterance", "查销售额", "userId", "user-1")));
        } finally {
            service.clearLiveChannel();
        }

        // 落库与实时推送走同一份脱敏后 JSON（第二道防线不可只在其中一条路上生效）
        Map<String, Object> pushedPayload = JsonUtils.parseMap(
                String.valueOf(pushed.get(0).get("payload")));
        Map<?, ?> input = (Map<?, ?>) pushedPayload.get("input");
        assertEquals("[REDACTED]", input.get("userId"));
    }
}

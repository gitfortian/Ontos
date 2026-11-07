package com.ontos.framework.agent.core;

import java.util.List;
import java.util.function.Function;

import com.ontos.framework.agent.config.AgentProperties;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.ReasoningInput;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntentProposalRepairMiddlewareTest {

    private static final String GOOD_JSON = """
            {"scenario_id":null,"terminal":"query","sub_intents":[{"sub_intent_id":"s1",
            "analysis_mode":"query","metric_id":"metric.gmv","dimensions":[],"filters":[],
            "time_expression_raw":"上个月","variant_hint":null,"confidence":0.9}],
            "needs_discovery":false,"discovery":null,"clarification":null,
            "out_of_capability":false,"reason_code":null}
            """;
    private static final String BAD_TEXT = "这不是 JSON";

    private final Agent agent = Mockito.mock(Agent.class);
    private final AgentProperties properties = new AgentProperties();

    private IntentProposalRepairMiddleware middleware() {
        return new IntentProposalRepairMiddleware(new IntentProposalContract(java.util.Set::of), properties);
    }

    private static ReasoningInput input() {
        Msg user = Msg.builder().role(MsgRole.USER)
                .content(TextBlock.builder().text("上个月各区域 GMV").build()).build();
        return new ReasoningInput(List.of(user), List.of(), null);
    }

    private static Function<ReasoningInput, Flux<AgentEvent>> round(String text) {
        return ignored -> Flux.just(
                new TextBlockStartEvent("reply-0", "reply-0-0"),
                new TextBlockDeltaEvent("reply-0", "reply-0-0", text),
                new TextBlockEndEvent("reply-0", "reply-0-0"));
    }

    private static String textOf(List<AgentEvent> events) {
        return events.stream()
                .filter(TextBlockDeltaEvent.class::isInstance)
                .map(event -> ((TextBlockDeltaEvent) event).getDelta())
                .reduce("", String::concat);
    }

    @Test
    void shouldPassThroughValidRoundWithoutRepair() {
        var recorded = new java.util.ArrayList<ReasoningInput>();
        Function<ReasoningInput, Flux<AgentEvent>> next = input -> {
            recorded.add(input);
            return round(GOOD_JSON).apply(input);
        };

        List<AgentEvent> events = middleware().onReasoning(agent, null, input(), next).collectList().block();

        assertEquals(1, recorded.size(), "合法输出不得触发修复轮");
        assertTrue(events.stream().noneMatch(event -> event instanceof CustomEvent));
        assertTrue(textOf(events).contains("terminal"));
    }

    @Test
    void shouldReenterReasoningWithFeedbackWhenInvalid() {
        properties.setOutputMaxRepairs(1);
        var inputs = new java.util.ArrayList<ReasoningInput>();
        Function<ReasoningInput, Flux<AgentEvent>> next = input -> {
            inputs.add(input);
            return round(inputs.size() == 1 ? BAD_TEXT : GOOD_JSON).apply(input);
        };

        List<AgentEvent> events = middleware().onReasoning(agent, null, input(), next).collectList().block();

        assertEquals(2, inputs.size(), "不合法输出应触发一次修复轮");
        // 修复轮输入必须携带修正反馈
        Msg last = inputs.get(1).messages().get(inputs.get(1).messages().size() - 1);
        String feedback = last.getContentBlocks(TextBlock.class).get(0).getText();
        assertTrue(feedback.contains("NOT_JSON"));
        // 事件流：坏轮透传 + repair 事件 + 好轮透传；权威提案（最后完整文本）合法
        assertTrue(events.stream().anyMatch(event -> event instanceof CustomEvent custom
                && IntentProposalRepairMiddleware.REPAIR_EVENT.equals(custom.getName())));
        String finalText = textOf(events.subList(events.size() - 3, events.size()));
        assertTrue(finalText.contains("terminal"));
    }

    @Test
    void shouldSynthesizeDeterministicRejectWhenExhausted() {
        properties.setOutputMaxRepairs(0);
        var recorded = new java.util.ArrayList<ReasoningInput>();
        Function<ReasoningInput, Flux<AgentEvent>> next = input -> {
            recorded.add(input);
            return round(BAD_TEXT).apply(input);
        };

        List<AgentEvent> events = middleware().onReasoning(agent, null, input(), next).collectList().block();

        assertEquals(1, recorded.size(), "超限后不得再进模型");
        assertTrue(events.stream().anyMatch(event -> event instanceof CustomEvent custom
                && IntentProposalRepairMiddleware.REPAIR_EXHAUSTED_EVENT.equals(custom.getName())));
        String reject = textOf(events);
        assertTrue(reject.contains("\"terminal\""));
        assertTrue(reject.contains("out_of_capability"));
        // 合成的拒答块必须以 Start/End 事件收口
        assertEquals(1, events.stream().filter(TextBlockEndEvent.class::isInstance)
                .filter(event -> ((TextBlockEndEvent) event).getBlockId().startsWith("repair-")).count());
    }

    @Test
    void shouldStopRepairingOnceValid() {
        properties.setOutputMaxRepairs(3);
        var inputs = new java.util.ArrayList<ReasoningInput>();
        Function<ReasoningInput, Flux<AgentEvent>> next = input -> {
            inputs.add(input);
            return round(inputs.size() <= 2 ? BAD_TEXT : GOOD_JSON).apply(input);
        };

        List<AgentEvent> events = middleware().onReasoning(agent, null, input(), next).collectList().block();

        assertEquals(3, inputs.size(), "第三次输出合法后停止修复");
        assertTrue(events.stream().filter(event -> event instanceof CustomEvent custom
                && IntentProposalRepairMiddleware.REPAIR_EVENT.equals(custom.getName())).count() == 2);
    }

}

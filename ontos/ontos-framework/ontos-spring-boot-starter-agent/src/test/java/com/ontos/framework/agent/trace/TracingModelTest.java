package com.ontos.framework.agent.trace;

import com.ontos.framework.common.trace.LlmCallRecord;
import com.ontos.framework.common.trace.LlmCallTraceSink;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型调用追踪装饰器单测（M7/W5/T5.1）。
 *
 * <p>覆盖四种终态：成功（token 取末个 usage）、错误、取消（超时同路径）、
 * 以及 sink 抛异常不冒泡；另验证无租户来源时不记录。
 */
class TracingModelTest {

    private static final LlmCallOrigin ORIGIN =
            LlmCallOrigin.of("demo", "trace-1", "s-1", 2, "ask.intent");

    /** 记录型 sink。 */
    private static final class CapturingSink implements LlmCallTraceSink {
        final List<LlmCallRecord> records = new ArrayList<>();

        @Override
        public void record(LlmCallRecord record) {
            records.add(record);
        }
    }

    /** 可控模型：执行给定的 Flux 工厂。 */
    private static Model model(java.util.function.Supplier<Flux<ChatResponse>> stream) {
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools,
                                             GenerateOptions options) {
                return stream.get();
            }

            @Override
            public String getModelName() {
                return "stub-model";
            }
        };
    }

    private static ChatResponse chunk(ChatUsage usage, String finishReason) {
        return ChatResponse.builder().id("resp-1").content(List.of()).usage(usage)
                .finishReason(finishReason).build();
    }

    @Test
    void recordsSuccessWithLastUsageAndMetadata() {
        CapturingSink sink = new CapturingSink();
        Model traced = new TracingModel(model(() -> Flux.just(
                chunk(new ChatUsage.Builder().inputTokens(10).outputTokens(0).build(), null),
                chunk(new ChatUsage.Builder().inputTokens(10).outputTokens(7).build(), "stop"))),
                List.of(sink), ORIGIN);

        List<ChatResponse> received = traced.stream(List.of(), List.of(), null)
                .collectList().block();

        assertEquals(2, received.size(), "装饰器不得消费流");
        assertEquals(1, sink.records.size());
        LlmCallRecord record = sink.records.get(0);
        assertEquals("demo", record.tenantId());
        assertEquals("trace-1", record.traceId());
        assertEquals("s-1", record.sessionId());
        assertEquals(2, record.turnIndex());
        assertEquals("ask.intent", record.purpose());
        assertEquals("stub-model", record.model());
        assertEquals(10, record.inputTokens());
        assertEquals(7, record.outputTokens(), "取末个非空 usage");
        assertEquals(17, record.totalTokens());
        assertEquals("stop", record.finishReason());
        assertEquals("success", record.status());
        assertTrue(record.latencyMs() >= 0);
        assertNull(record.errorType());
    }

    @Test
    void recordsErrorWithTypeAndMessage() {
        CapturingSink sink = new CapturingSink();
        Model traced = new TracingModel(model(() ->
                Flux.error(new IllegalStateException("模型端点 502"))), List.of(sink), ORIGIN);

        assertDoesNotThrow(() -> traced.stream(List.of(), List.of(), null)
                .onErrorResume(error -> Flux.empty()).blockLast());

        assertEquals(1, sink.records.size());
        LlmCallRecord record = sink.records.get(0);
        assertEquals("error", record.status());
        assertEquals("IllegalStateException", record.errorType());
        assertEquals("模型端点 502", record.errorMessage());
        assertEquals(0, record.totalTokens());
    }

    @Test
    void recordsCancelledWhenDownstreamStopsEarly() {
        CapturingSink sink = new CapturingSink();
        Model traced = new TracingModel(model(() -> Flux.just(
                chunk(new ChatUsage.Builder().inputTokens(3).outputTokens(1).build(), "length"),
                chunk(null, "stop"))), List.of(sink), ORIGIN);

        // take(1) 在上游发完第一个 chunk 后取消剩余流——等价于 block(timeout) 超时取消
        traced.stream(List.of(), List.of(), null).take(1).blockLast();

        assertEquals(1, sink.records.size());
        assertEquals("cancelled", sink.records.get(0).status());
        assertEquals(3, sink.records.get(0).inputTokens(), "取消前已有的用量仍记录");
    }

    @Test
    void sinkFailureNeverPropagates() {
        LlmCallTraceSink failing = record -> {
            throw new IllegalStateException("DB down");
        };
        CapturingSink capturing = new CapturingSink();
        AtomicInteger calls = new AtomicInteger();
        Model traced = new TracingModel(model(() -> {
            calls.incrementAndGet();
            return Flux.just(chunk(new ChatUsage.Builder().inputTokens(1).outputTokens(1).build(),
                    "stop"));
        }), List.of(failing, capturing), ORIGIN);

        assertDoesNotThrow(() -> traced.stream(List.of(), List.of(), null).blockLast());

        assertEquals(1, calls.get());
        assertEquals(1, capturing.records.size(), "一个 sink 失败不影响其它 sink");
        assertEquals("success", capturing.records.get(0).status());
    }

    @Test
    void skipsTracingWithoutTenantOrOrigin() {
        CapturingSink sink = new CapturingSink();
        Consumer<LlmCallOrigin> run = origin -> {
            Model traced = new TracingModel(model(() -> Flux.just(chunk(null, "stop"))),
                    List.of(sink), origin);
            traced.stream(List.of(), List.of(), null).blockLast();
        };

        run.accept(null);
        run.accept(LlmCallOrigin.of("  ", "trace-2", null, null, "ask.intent"));

        assertTrue(sink.records.isEmpty(), "无租户不落库");
    }

    @Test
    void delegatesModelMetadata() {
        Model delegate = model(() -> Flux.empty());
        Model traced = new TracingModel(delegate, List.of(), ORIGIN);
        assertEquals(delegate.getModelName(), traced.getModelName());
        assertEquals(delegate.getContextWindowSize(), traced.getContextWindowSize());
        assertEquals(delegate.supportsNativeStructuredOutput(),
                traced.supportsNativeStructuredOutput());
        assertEquals(delegate.supportsNativeStructuredOutputWithTools(),
                traced.supportsNativeStructuredOutputWithTools());
    }
}

package com.ontos.framework.agent.trace;

import com.ontos.framework.common.trace.LlmCallRecord;
import com.ontos.framework.common.trace.LlmCallTraceSink;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

/**
 * 模型装饰器：为每次 {@code stream(...)} 调用产出一条 {@link LlmCallRecord}（M7/W5/T5.1）。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>透明</b>：只透传委托模型的流，不改变消息、工具与选项，也不消费流（下游照常收到每个 chunk）；</li>
 *   <li><b>不漏段</b>：{@code doFinally} 在完成/错误/取消三种终态都会触发——超时取消（{@code block(timeout)}）
 *       同样留痕，否则「慢到超时」的调用在观测上是空白；</li>
 *   <li><b>不阻断</b>：sink 抛出的任何运行时异常只记 warn 日志，绝不冒泡到业务链路；</li>
 *   <li><b>不落原文</b>：只记 token 用量/耗时/终态，不写提示词与输出（脱敏约束）。</li>
 * </ul>
 *
 * <p>token 用量取流中最后一个非空 {@link ChatUsage}（OpenAI 协议通常在末个 chunk 汇总）。
 */
public final class TracingModel implements Model {

    private static final Logger log = LoggerFactory.getLogger(TracingModel.class);

    private final Model delegate;
    private final List<LlmCallTraceSink> sinks;
    private final LlmCallOrigin origin;

    public TracingModel(Model delegate, List<LlmCallTraceSink> sinks, LlmCallOrigin origin) {
        this.delegate = delegate;
        this.sinks = sinks == null ? List.of() : List.copyOf(sinks);
        this.origin = origin;
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools,
                                     GenerateOptions options) {
        long startedAt = System.nanoTime();
        AtomicReference<ChatUsage> usage = new AtomicReference<>();
        AtomicReference<String> finishReason = new AtomicReference<>();
        AtomicReference<String> errorType = new AtomicReference<>();
        AtomicReference<String> errorMessage = new AtomicReference<>();
        AtomicBoolean failed = new AtomicBoolean(false);

        return delegate.stream(messages, tools, options)
                .doOnNext(response -> {
                    if (response == null) {
                        return;
                    }
                    if (response.getUsage() != null) {
                        usage.set(response.getUsage());
                    }
                    if (response.getFinishReason() != null) {
                        finishReason.set(response.getFinishReason());
                    }
                })
                .doOnError(error -> {
                    failed.set(true);
                    errorType.set(error.getClass().getSimpleName());
                    errorMessage.set(error.getMessage());
                })
                .doFinally(signal -> emit(startedAt, usage.get(), finishReason.get(), failed.get(),
                        errorType.get(), errorMessage.get(), signal));
    }

    private void emit(long startedAt, ChatUsage usage, String finishReason, boolean failed,
                      String errorType, String errorMessage, SignalType signal) {
        if (origin == null || !origin.traceable() || sinks.isEmpty()) {
            return;
        }
        String status = failed ? "error" : signal == SignalType.CANCEL ? "cancelled" : "success";
        LlmCallRecord record = new LlmCallRecord(
                origin.tenantId(),
                origin.traceId(),
                origin.sessionId(),
                origin.turnIndex(),
                origin.purpose(),
                delegate.getModelName(),
                usage == null ? 0 : usage.getInputTokens(),
                usage == null ? 0 : usage.getOutputTokens(),
                usage == null ? 0 : usage.getTotalTokens(),
                (System.nanoTime() - startedAt) / 1_000_000L,
                status,
                errorType,
                errorMessage,
                finishReason);
        for (LlmCallTraceSink sink : sinks) {
            try {
                sink.record(record);
            } catch (RuntimeException ex) {
                // 观测写失败不得影响业务：吞掉并留日志，由运维侧从日志补偿
                log.warn("[emit][模型调用追踪写入失败，不影响本次调用: {}] {}", record.purpose(),
                        ex.toString());
            }
        }
    }

    @Override
    public String getModelName() {
        return delegate.getModelName();
    }

    @Override
    public boolean supportsNativeStructuredOutput() {
        return delegate.supportsNativeStructuredOutput();
    }

    @Override
    public boolean supportsNativeStructuredOutputWithTools() {
        return delegate.supportsNativeStructuredOutputWithTools();
    }

    @Override
    public int getContextWindowSize() {
        return delegate.getContextWindowSize();
    }
}
